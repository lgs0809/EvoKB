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
      graph.upsertRelations(List.of(relation("r01","n0","n1","evidence-public"),relation("r12","n1","n2","evidence-other"),relation("r20","n2","n0","evidence-other"),relation("r23","n2","n3","evidence-other")));
      graph.upsertRelations(List.of(relation("r01","n0","n1","evidence-public")));
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
      mixed.upsertEntities(entities);mixed.upsertRelations(relations);
      var visible=mixed.expand(List.of(seed("version-public")),low);
      check("allSourcesAuthorizedBeforeSeedLimit",visible.candidates().size()==1&&visible.candidates().get(0).id().equals("public-only"));
      check("trustedHigherClearanceAllowsMixedSources",mixed.expand(List.of(seed("version-public")),high).candidates().stream().anyMatch(c->c.id().startsWith("secret-")));
      var reader=new JdbcPublishedGraphReader(jdbc,"mixed");
      check("uiOnlyReadsPublishedAuthorizedRevisions",reader.browse("合同参与方",1,100,null,null,10).size()==1 && reader.browse("合同参与方",1,100,null,null,30).size()==13);
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
      check("atomicPublicationRejectsInvalidEvidence",rejected(()->atomic.publish(List.of(document("atomic-source"),entity("atomic-target")),List.of(relation("atomic-r","atomic-source","atomic-target","unknown-proof")))));
      check("failedPublicationLeavesNoNodesOrEdges",jdbc.queryForObject("SELECT count(*) FROM kb_graph_entity WHERE kb_id='atomic'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='atomic'",Integer.class)==0);
      var projections=new JdbcCompilationRepository(jdbc,tx,mapper,null);
      var dual=store("dual-write");
      var dualEntities=List.of(document("dual-document"),entity("dual-object"));
      var dualRelations=List.of(relation("dual-relation","dual-document","dual-object","evidence-public"));
      var invalidMapping=new EntityResolution("dual-invalid-source","nonexistent-version","故障注入对象","ORGANIZATION","evidence-public","dual-object",1,"component-fault-injection");
      check("compatibilityWriteFailureActuallyOccurs",rejected(()->projections.withinPublication(()->{
        dual.publish(dualEntities,dualRelations);
        projections.saveGraph("component-only",dualEntities,dualRelations,List.of(invalidMapping));
      })));
      check("compatibilityWriteFailureRollsBackEveryGraphProjection",jdbc.queryForObject("SELECT count(*) FROM kb_graph_entity WHERE kb_id='dual-write'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='dual-write'",Integer.class)==0 && jdbc.queryForObject("SELECT count(*) FROM canonical_entity WHERE canonical_entity_id IN ('dual-document','dual-object')",Integer.class)==0);
      var validMapping=new EntityResolution("dual-valid-source","version-public","故障后正常对象","ORGANIZATION","evidence-public","dual-object",1,"component-success-after-failure");
      projections.withinPublication(()->{
        dual.publish(dualEntities,dualRelations);
        projections.saveGraph("component-only",dualEntities,dualRelations,List.of(validMapping));
      });
      check("publicationCanRecoverAfterRolledBackFailure",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='dual-write' AND is_active",Integer.class)==1 && jdbc.queryForObject("SELECT count(*) FROM canonical_entity WHERE canonical_entity_id IN ('dual-document','dual-object')",Integer.class)==2 && jdbc.queryForObject("SELECT count(*) FROM entity_mapping WHERE source_entity_id='dual-valid-source' AND status='APPLIED'",Integer.class)==1);
      check("logicalRelationCannotChangeEndpoints",rejected(()->graph.upsertRelations(List.of(relation("r01","n0","n2","evidence-public")))));
      var noDocument=store("no-doc");noDocument.upsertEntities(List.of(entity("unscoped-1"),entity("unscoped-2")));noDocument.upsertRelations(List.of(relation("unscoped-r","unscoped-1","unscoped-2","evidence-public")));
      check("missingDocumentIdentityCannotInventGraphSeeds",noDocument.expand(List.of(seed("version-public")),low).candidates().isEmpty());
      var cap=store("cap");entities=new ArrayList<>();relations=new ArrayList<>();entities.add(document("root"));
      for(int i=0;i<50;i++){String id=String.format("node-%02d",i);entities.add(entity(id));relations.add(relation(String.format("cap-%02d",i),"root",id,"evidence-public"));}
      cap.upsertEntities(entities);cap.upsertRelations(relations);var limited=cap.expand(List.of(seed("version-public")),low);
      check("perHopTwentyTotalFortyAndTruncation",limited.truncated()&&limited.hops()<=2&&limited.candidates().size()<=40&&limited.candidates().stream().filter(c->c.attributes().get("hop").equals(1)).count()<=20);
      var foreign=store("other-kb");foreign.upsertEntities(List.of(entity("only-there")));
      check("crossKnowledgeBaseEndpointsRejected",rejected(()->graph.upsertRelations(List.of(relation("cross","n0","only-there","evidence-public")))));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-secret'");
      check("changedAclInvalidatesExistingGraphRevision",mixed.expand(List.of(seed("version-public")),high).candidates().stream().noneMatch(c->c.id().startsWith("secret-")));
      check("uiAclChangeAlsoBlocksOldRevisions",reader.browse("合同参与方",1,100,null,null,30).size()==1);
      check("documentStatusAclChangeAlsoBlocksOldRevisions",operations.documentDetail("version-public",10,30).orElseThrow().knowledge().activeRelations()==1);
      check("goldenEvaluationAclChangeAlsoBlocksOldRevisions",evaluation.load(List.of("version-public"),30).relations().size()==1 && !evaluation.loadScoped(List.of("version-public"),30).sourceVersionIds().contains("version-secret"));
      mixed.upsertRelations(List.of(relation("secret-0","mixed-source","b-hidden-0","evidence-secret","evidence-public")));
      check("republishPreservesOldRevisionAndOneCurrent",jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='mixed' AND relation_id='secret-0'",Integer.class)==2&&jdbc.queryForObject("SELECT count(*) FROM kb_graph_relation WHERE kb_id='mixed' AND relation_id='secret-0' AND is_active",Integer.class)==1);
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
