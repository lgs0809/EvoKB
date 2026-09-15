import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.*;
import cn.lgs.knowledgebase.infrastructure.graph.PostgresGraphStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcPublishedGraphReader;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcEnterpriseOperations;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcArtifactEvaluationRepository;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcCompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL protocol checks against the shipped graph adapter, never an in-memory graph. */
public class PostgresGraphProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static JdbcTemplate jdbc;
  static TransactionTemplate tx;
  static ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static void check(String name,boolean pass){checks.put(name,pass);if(!pass)throw new AssertionError(name);}
  static PostgresGraphStoreAdapter store(String kb){return new PostgresGraphStoreAdapter(jdbc,tx,mapper,kb);}
  static Entity entity(String id){return new Entity(id,"合同参与方 "+id,"ORGANIZATION",id,List.of(),Map.of("scope_key","contract:HT-017"));}
  static Entity document(String id){return new Entity(id,"源资料 "+id,"DOCUMENT",id,List.of(),Map.of("scope_key","document:doc-public","object_id","doc-public"));}
  static Relation relation(String id,String from,String to,String... evidence){return new Relation(id,from,"PAYS",to,null,null,ConflictStatus.NONE,RelationLifecycle.ACTIVE,List.of(evidence),1,Map.of());}
  static Candidate seed(String version){return new Candidate("retrieved-"+version,"doc-public",version,LaneType.RAW,"实际检索原文",1,List.of("evidence-public"),Map.of());}
  static boolean rejected(Runnable action){try{action.run();return false;}catch(RuntimeException expected){return true;}}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();
    try{
      var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
      var low=new SearchFilters(null,null,null,List.of());var high=low.withClearance(30);
      var graph=store("chain");graph.upsertEntities(List.of(document("n0"),entity("n1"),entity("n2"),entity("n3")));
      // Each synthetic publication explicitly declares its whole input set; citations are not
      // used to infer completeness in production.
      graph.publish(List.of(),List.of(relation("r01","n0","n1","evidence-public")),List.of("version-public"));
      graph.publish(List.of(),List.of(relation("r12","n1","n2","evidence-other"),relation("r20","n2","n0","evidence-other"),relation("r23","n2","n3","evidence-other")),List.of("version-other"));
      graph.publish(List.of(),List.of(relation("r01","n0","n1","evidence-public")),List.of("version-public"));
      check("replayKeepsOneImmutableRevision",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='chain' AND relation_id='r01'",Integer.class)==1);
      var expanded=graph.expand(List.of(seed("version-public")),low);
      check("actualRetrievedSourceStartsBoundedTwoHopTraversal",expanded.hops()==2&&expanded.candidates().size()==4);
      check("cyclesDoNotDuplicateEdges",expanded.candidates().stream().map(Candidate::id).distinct().count()==expanded.candidates().size());
      check("directionAndRevisionRetained",expanded.candidates().stream().allMatch(c->c.attributes().containsKey("source_entity_id")&&c.attributes().containsKey("target_entity_id")&&c.attributes().containsKey("revision_id")));
      check("questionTextCannotInventGraphSeeds",graph.search("合同参与方 n0",low,20).isEmpty());
      check("derivedAssetsCannotBecomeGraphNodes",rejected(()->graph.upsertEntities(List.of(new Entity("bad","错误节点","WIKI","bad",List.of(),Map.of("scope_key","contract:HT-017"))))));
      check("scopeCannotBeReassigned",rejected(()->graph.upsertEntities(List.of(new Entity("n0","相同名字","ORGANIZATION","n0",List.of(),Map.of("scope_key","contract:OTHER"))))));
      var mixed=store("mixed");var entities=new ArrayList<Entity>();var relations=new ArrayList<Relation>();entities.add(document("mixed-source"));
      for(int i=0;i<12;i++){entities.add(entity("b-hidden-"+i));relations.add(relation("secret-"+i,"mixed-source","b-hidden-"+i,"evidence-public","evidence-secret"));}
      entities.add(entity("z-visible-2"));relations.add(relation("public-only","mixed-source","z-visible-2","evidence-public"));
      mixed.publish(entities,relations.subList(0,12),List.of("version-public","version-secret"));
      mixed.publish(List.of(),List.of(relations.get(12)),List.of("version-public"));
      var visible=mixed.expand(List.of(seed("version-public")),low);
      check("allSourcesAuthorizedBeforeSeedLimit",visible.candidates().size()==1&&visible.candidates().get(0).id().equals("public-only"));
      check("trustedHigherClearanceAllowsMixedSources",mixed.expand(List.of(seed("version-public")),high).candidates().stream().anyMatch(c->c.id().startsWith("secret-")));
      var onlyPublic = new SearchFilters(null, null, null, List.of("version-public"), 30);
      check("explicitVersionScopeFiltersEdgesBeforeTraversal",
          mixed.expand(List.of(seed("version-public")), onlyPublic).candidates().stream()
              .noneMatch(c -> c.id().startsWith("secret-")));
      var reader=new JdbcPublishedGraphReader(jdbc,"mixed");
      check("uiOnlyReadsPublishedAuthorizedRevisions",reader.browse("合同参与方",1,100,null,null,10).size()==1 && reader.browse("合同参与方",1,100,null,null,30).size()==13);
      check("uiRelationTypeFilterWorksAtOneAndTwoHops",reader.browse("合同参与方",1,100,"PAYS",null,10).size()==1
          &&reader.browse("合同参与方",2,100,"PAYS",null,30).size()==13);
      check("uiUnknownAndUntrustedTypeCannotBypassAuthority",reader.browse("合同参与方",2,100,"NO_SUCH_TYPE",null,30).isEmpty()
          &&reader.browse("合同参与方",2,100,"PAYS' OR 1=1 --",null,30).isEmpty()
          &&reader.browse("合同参与方",2,100,"PAYS",null,10).size()==1);
      var timed=store("timed");var start=java.time.Instant.parse("2026-01-01T00:00:00Z");var end=java.time.Instant.parse("2026-02-01T00:00:00Z");
      timed.publish(List.of(document("timed-document"),entity("timed-target")),List.of(new Relation("timed-relation","timed-document","PAYS","timed-target",start,end,ConflictStatus.NONE,RelationLifecycle.ACTIVE,List.of("evidence-public"),1,Map.of())),List.of("version-public"));
      var timedReader=new JdbcPublishedGraphReader(jdbc,"timed");
      check("uiValidityFilterHasInclusiveStartAndExclusiveEnd",timedReader.browse("合同参与方",1,100,null,start,10).size()==1
          &&timedReader.browse("合同参与方",1,100,null,start.minusSeconds(1),10).isEmpty()
          &&timedReader.browse("合同参与方",1,100,null,end,10).isEmpty());
      check("uiTypeAndTimeFiltersComposeAcrossBothHops",timedReader.browse("合同参与方",2,100,"PAYS",end.minusSeconds(1),10).size()==1
          &&timedReader.browse("合同参与方",2,100,"PAYS",end,10).isEmpty());
      // A prior failed compiler wrote compatibility rows. They remain retained, but cannot be
      // counted as a publication by the UI, document status, quality view or Golden evaluator.
      jdbc.update("INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key) VALUES('legacy-a','遗留失败公司','COMPANY','legacy-a'),('legacy-b','遗留失败合同','CONTRACT','legacy-b')");
      jdbc.update("INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,conflict_status) VALUES('legacy-failed','legacy-a','PARTY_TO_CONTRACT','legacy-b','POTENTIAL')");
      jdbc.update("INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id) VALUES('legacy-proof','legacy-failed','llm_triple','evidence-public')");
      jdbc.update("INSERT INTO source_entity(source_entity_id,version_id,surface_form,entity_type,evidence_id) VALUES('legacy-source','version-public','遗留失败公司','COMPANY','evidence-public')");
      jdbc.update("INSERT INTO entity_mapping(source_entity_id,canonical_entity_id,status) VALUES('legacy-source','legacy-a','APPLIED')");
      var operations=new JdbcEnterpriseOperations(jdbc,tx,mapper,"mixed");
      check("publishedGraphEvidenceKeepsActualSourceFilename",operations.evidence("evidence-public",10).orElseThrow().sourceFilename().equals("component-fixture.md"));
      var evaluation=new JdbcArtifactEvaluationRepository(jdbc,"mixed");
      var status=operations.documentDetail("version-public",10,10).orElseThrow().knowledge();
      check("documentStatusCountsOnlyActualAuthorizedPublication",status.activeRelations()==1 && status.relationEvidenceSupports()==1 && status.appliedEntityMappings()==0);
      check("failedLegacyRelationsCannotAppearAsQualityConflicts",operations.quality(100,30).relationConflicts()==0);
      check("goldenEvaluationUsesSamePublishedGraph",evaluation.load(List.of("version-public"),10).relations().size()==1 && evaluation.load(List.of("version-public"),30).relations().size()==13);
      check("goldenEvaluationDoesNotAcceptLegacyOnlyEntityMappings",evaluation.load(List.of("version-public"),30).entityMappings().isEmpty());
      check("goldenDeliveryIncludesAllPublishedGraphDependencies",evaluation.loadScoped(List.of("version-public"),30).sourceVersionIds().contains("version-secret"));
      var atomic=store("atomic");
      check("atomicPublicationRejectsInvalidEvidence",rejected(()->atomic.publish(List.of(document("atomic-source"),entity("atomic-target")),List.of(relation("atomic-r","atomic-source","atomic-target","unknown-proof")),List.of("version-public"))));
      check("failedPublicationLeavesNoNodesOrEdges",jdbc.queryForObject("SELECT count(*) FROM kb_graph_entity WHERE kb_id='atomic'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='atomic'",Integer.class)==0);
      var projections=new JdbcCompilationRepository(jdbc,tx,mapper,null);
      var dual=store("dual-write");
      var dualEntities=List.of(document("dual-document"),entity("dual-object"));
      var dualRelations=List.of(relation("dual-relation","dual-document","dual-object","evidence-public"));
      var invalidMapping=new EntityResolution("dual-invalid-source","nonexistent-version","故障注入对象","ORGANIZATION","evidence-public","dual-object",1,"component-fault-injection");
      check("compatibilityWriteFailureActuallyOccurs",rejected(()->projections.withinPublication(()->{
        dual.publish(dualEntities,dualRelations,List.of("version-public"));
        projections.saveGraph("component-only",dualEntities,dualRelations,List.of(invalidMapping));
      })));
      check("compatibilityWriteFailureRollsBackEveryGraphProjection",jdbc.queryForObject("SELECT count(*) FROM kb_graph_entity WHERE kb_id='dual-write'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='dual-write'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM canonical_entity WHERE canonical_entity_id IN ('dual-document','dual-object')",Integer.class)==0);
      var validMapping=new EntityResolution("dual-valid-source","version-public","故障后正常对象","ORGANIZATION","evidence-public","dual-object",1,"component-success-after-failure");
      projections.withinPublication(()->{
        dual.publish(dualEntities,dualRelations,List.of("version-public"));
        projections.saveGraph("component-only",dualEntities,dualRelations,List.of(validMapping));
      });
      check("publicationCanRecoverAfterRolledBackFailure",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='dual-write' AND is_active",Integer.class)==1 && jdbc.queryForObject("SELECT count(*) FROM canonical_entity WHERE canonical_entity_id IN ('dual-document','dual-object')",Integer.class)==2 && jdbc.queryForObject("SELECT count(*) FROM entity_mapping WHERE source_entity_id='dual-valid-source' AND status='APPLIED'",Integer.class)==1);
      check("logicalRelationCannotChangeEndpoints",rejected(()->graph.publish(List.of(),List.of(relation("r01","n0","n2","evidence-public")),List.of("version-public"))));
      var noDocument=store("no-doc");noDocument.publish(List.of(entity("unscoped-1"),entity("unscoped-2")),List.of(relation("unscoped-r","unscoped-1","unscoped-2","evidence-public")),List.of("version-public"));
      check("missingDocumentIdentityCannotInventGraphSeeds",noDocument.expand(List.of(seed("version-public")),low).candidates().isEmpty());
      var cap=store("cap");entities=new ArrayList<>();relations=new ArrayList<>();entities.add(document("root"));
      for(int i=0;i<50;i++){String id=String.format("node-%02d",i);entities.add(entity(id));relations.add(relation(String.format("cap-%02d",i),"root",id,"evidence-public"));}
      cap.publish(entities,relations,List.of("version-public"));var limited=cap.expand(List.of(seed("version-public")),low);
      check("perHopTwentyTotalFortyAndTruncation",limited.truncated()&&limited.hops()<=2&&limited.candidates().size()<=40&&limited.candidates().stream().filter(c->c.attributes().get("hop").equals(1)).count()<=20);
      var foreign=store("other-kb");foreign.upsertEntities(List.of(entity("only-there")));
      check("crossKnowledgeBaseEndpointsRejected",rejected(()->graph.publish(List.of(),List.of(relation("cross","n0","only-there","evidence-public")),List.of("version-public"))));
      var uncertified=store("uncertified");
      uncertified.publish(List.of(document("legacy-document"),entity("legacy-target")),
          List.of(relation("legacy-evidence-only","legacy-document","legacy-target","evidence-public")));
      check("legacyEvidenceOnlyGraphIsRetainedButFailsClosedAcrossReaders",
          jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='uncertified' AND is_active AND input_policy_version IS NULL",Integer.class)==1
          &&uncertified.expand(List.of(seed("version-public")),high).candidates().isEmpty()
          &&new JdbcPublishedGraphReader(jdbc,"uncertified").browse("合同参与方",1,100,null,null,30).isEmpty());
      var uncited=store("uncited");
      uncited.publish(List.of(document("uncited-document"),entity("uncited-target")),
          List.of(relation("uncited-private-input","uncited-document","uncited-target","evidence-public")),List.of("version-public","version-secret"));
      var uncitedReader=new JdbcPublishedGraphReader(jdbc,"uncited");
      var uncitedOperations=new JdbcEnterpriseOperations(jdbc,tx,mapper,"uncited");
      var uncitedEvaluation=new JdbcArtifactEvaluationRepository(jdbc,"uncited");
      check("uncitedGraphInputIsPersistedSeparatelyFromItsSingleCitation",
          jdbc.queryForObject("SELECT count(*) FROM kb_graph_build_dependency WHERE kb_id='uncited'",Integer.class)==2
          &&jdbc.queryForObject("SELECT count(*) FROM kb_graph_evidence WHERE kb_id='uncited'",Integer.class)==1);
      check("uncitedRestrictedGraphInputBlocksBrowseAndExpansionBeforeLimit",
          uncitedReader.browse("合同参与方",1,100,null,null,10).isEmpty()
          &&uncitedReader.browse("合同参与方",1,100,null,null,30).size()==1
          &&uncited.expand(List.of(seed("version-public")),low).candidates().isEmpty()
          &&uncited.expand(List.of(seed("version-public")),high).candidates().size()==1);
      check("uncitedGraphInputUsesSameDocumentCountsAndGoldenAuthority",
          uncitedOperations.documentDetail("version-public",10,10).orElseThrow().knowledge().activeRelations()==0
          &&uncitedOperations.documentDetail("version-public",10,30).orElseThrow().knowledge().activeRelations()==1
          &&uncitedEvaluation.load(List.of("version-public"),10).relations().isEmpty()
          &&uncitedEvaluation.loadScoped(List.of("version-public"),30).sourceVersionIds().contains("version-secret"));
      check("graphCandidateCarriesAllInputVersionsButOnlyActualEvidenceIds",
          ((List<?>)uncited.expand(List.of(seed("version-public")),high).candidates().get(0).attributes().get("source_version_ids")).containsAll(List.of("version-public","version-secret"))
          &&uncited.expand(List.of(seed("version-public")),high).candidates().get(0).evidenceIds().equals(List.of("evidence-public")));
      var partial=store("partial-inputs");
      partial.publish(List.of(document("partial-document"),entity("partial-target")),
          List.of(relation("partial-relation","partial-document","partial-target","evidence-public")),List.of("version-public","version-other"));
      jdbc.update("DELETE FROM kb_graph_build_dependency WHERE kb_id='partial-inputs' AND source_version_id='version-other'");
      check("partialGraphInputSnapshotCannotTurnPolicyMarkerIntoAuthorization",
          partial.expand(List.of(seed("version-public")),high).candidates().isEmpty()
          &&new JdbcPublishedGraphReader(jdbc,"partial-inputs").browse("合同参与方",1,100,null,null,30).isEmpty());
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-secret'");
      check("uncitedInputAclRevocationImmediatelyBlocksAllGraphDeliveryPaths",
          uncitedReader.browse("合同参与方",1,100,null,null,30).isEmpty()
          &&uncited.expand(List.of(seed("version-public")),high).candidates().isEmpty()
          &&uncitedOperations.documentDetail("version-public",10,30).orElseThrow().knowledge().activeRelations()==0
          &&uncitedEvaluation.load(List.of("version-public"),30).relations().isEmpty()
          &&jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='uncited' AND is_active",Integer.class)==1);
      check("changedAclInvalidatesExistingGraphRevision",mixed.expand(List.of(seed("version-public")),high).candidates().stream().noneMatch(c->c.id().startsWith("secret-")));
      check("uiAclChangeAlsoBlocksOldRevisions",reader.browse("合同参与方",1,100,null,null,30).size()==1);
      check("documentStatusAclChangeAlsoBlocksOldRevisions",operations.documentDetail("version-public",10,30).orElseThrow().knowledge().activeRelations()==1);
      check("goldenEvaluationAclChangeAlsoBlocksOldRevisions",evaluation.load(List.of("version-public"),30).relations().size()==1 && !evaluation.loadScoped(List.of("version-public"),30).sourceVersionIds().contains("version-secret"));
      mixed.publish(List.of(),List.of(relation("secret-0","mixed-source","b-hidden-0","evidence-secret","evidence-public")),List.of("version-public","version-secret"));
      check("republishPreservesOldRevisionAndOneCurrent",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='mixed' AND relation_id='secret-0'",Integer.class)==2&&jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='mixed' AND relation_id='secret-0' AND is_active",Integer.class)==1);
      uncited.publish(List.of(),List.of(relation("uncited-private-input","uncited-document","uncited-target","evidence-public")),List.of("version-public","version-secret"));
      check("normalRepublishUsesNewInputAclAndPreservesPriorGraphRevision",
          uncitedReader.browse("合同参与方",1,100,null,null,30).size()==1
          &&jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='uncited'",Integer.class)==2);
      uncited.deactivateVersion("version-secret");
      check("explicitUncitedVersionInvalidationDeactivatesGraphRevision",
          jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='uncited' AND is_active",Integer.class)==0);
      jdbc.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id='version-secret'");
      check("sourceInvalidationBlocksGraphImmediately",mixed.expand(List.of(seed("version-public")),high).candidates().stream().noneMatch(c->c.id().startsWith("secret-")));
      graph.deactivateVersion("version-public");
      check("explicitVersionDeactivationDisablesDependentRelation",graph.expand(List.of(seed("version-public")),high).candidates().stream().noneMatch(c->c.id().equals("r01")) && jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='chain' AND relation_id='r01' AND is_active",Integer.class)==0);
      check("unrelatedEvidenceIsNotInvalidatedByAnotherVersion",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='chain' AND relation_id='r20' AND is_active",Integer.class)==1);
      jdbc.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id='version-public'");
      check("inactiveActualSourceVersionCannotProvideDocumentSeeds",graph.expand(List.of(seed("version-public")),high).candidates().isEmpty());
      report.put("status","PASS_REAL_POSTGRES_GRAPH_COMPONENTS");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_GRAPH_COMPONENTS");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
