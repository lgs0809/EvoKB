import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real retained PG component; facts are synthetic fixtures, never real-model acceptance. */
public final class KnowledgeEntryProbe {
  final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final JdbcCompilationRepository compilation;
  final JdbcKnowledgeEntryCatalogue entries;
  final JdbcGenerationTaskRepository tasks;
  final JdbcAssetCatalogue assets;
  final Map<String,Boolean> checks=new LinkedHashMap<>();
  KnowledgeEntryProbe() {
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    compilation=new JdbcCompilationRepository(jdbc,tx,mapper,new ObjectStore(){
      public StoredObject get(String key){byte[] body="SYNTHETIC_COMPONENT_NOT_MODEL".getBytes(java.nio.charset.StandardCharsets.UTF_8);return new StoredObject(new java.io.ByteArrayInputStream(body),body.length,"text/markdown");}
      public void put(String key,java.io.InputStream in,long size,String type){throw new UnsupportedOperationException("component read fixture only");}
      public ObjectInfo stat(String key){throw new UnsupportedOperationException("component read fixture only");}
      public String presignGet(String key,java.time.Duration ttl){throw new UnsupportedOperationException("component read fixture only");}
      public void delete(String key){throw new UnsupportedOperationException("component read fixture only");}
    });
    entries=new JdbcKnowledgeEntryCatalogue(jdbc,tx,mapper,"component-kb");
    tasks=new JdbcGenerationTaskRepository(jdbc,tx,"component-kb");
    assets=new JdbcAssetCatalogue(jdbc,tx);
  }
  void check(String name,boolean value) {checks.put(name,value);if(!value)throw new AssertionError(name);}
  boolean rejected(Runnable run) {try{run.run();return false;}catch(RuntimeException expected){return true;}}
  SummaryRecord summary(String suffix,String factText) {
    String version="version-"+suffix,summary="summary-"+suffix;
    jdbc.update("UPDATE raw_block SET raw_content=? WHERE version_id=?",factText,version);
    compilation.replaceDocumentUnits(version,List.of(new DocumentUnit("unit-"+suffix,version,1,"Synthetic component contract","single","CONTRACT",1,1)));
    var fact=new SummaryFact("fact-"+suffix,summary,"Synthetic",factText,List.of("block-"+suffix),List.of("evidence-"+suffix),"PASS","synthetic component grounding fixture");
    var result=new SummaryRecord(summary,"unit-"+suffix,"doc-"+suffix,version,"component-only",cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(factText),"PASS","PASS","ACTIVE","SYNTHETIC_COMPONENT_NOT_MODEL",factText,List.of(fact),Instant.now());
    compilation.saveSummary(result);return result;
  }
  ConceptRecord concept(SummaryRecord summary,String type,String label) {
    return new ConceptRecord("concept-"+summary.id(),summary.id(),summary.versionId(),label,List.of(),type,"Synthetic supported component entry",summary.facts().stream().map(SummaryFact::id).toList(),"ACTIVE","SYNTHETIC_COMPONENT_NOT_MODEL",Instant.now());
  }
  GraphScopeIdentity.Scope scope(SummaryRecord summary,String contract) {
    return GraphScopeIdentity.scope(summary.documentId(),List.of(
        new GraphScopeIdentity.Anchor("COMPANY","甲公司",List.of(summary.facts().get(0).id())),
        new GraphScopeIdentity.Anchor("CONTRACT",contract,List.of(summary.facts().get(0).id()))),Map.of(summary.facts().get(0).id(),summary.facts().get(0)));
  }
  KnowledgeEntryCatalogue.Entry publish(SummaryRecord summary,ConceptRecord concept,KnowledgeEntryCatalogue.Publication plan,List<KnowledgeEntryCatalogue.Candidate> candidates) {
    tasks.queue(summary.versionId(),GenerationTaskType.CONCEPT,summary.id(),Instant.now());
    var task=tasks.claimPending(1,Instant.now()).get(0);
    var gate=tasks.publication(task);gate.captureInputs(plan.entry().versionIds());
    gate.publish(()->{
      compilation.replaceConceptsForSummary(summary.id(),List.of(concept));
      entries.publish(plan);
      var proofs=plan.entry().sources().stream().flatMap(source->{
        var parameters=new ArrayList<Object>();parameters.add(source.summaryId());parameters.addAll(source.factIds());
        return jdbc.query("SELECT DISTINCT se.evidence_id FROM summary_evidence se JOIN summary_fact f USING(fact_id) WHERE f.summary_id=? AND f.fact_id IN ("
            +String.join(",",Collections.nCopies(source.factIds().size(),"?"))+")",(rs,n)->rs.getString(1),parameters.toArray()).stream();
      }).distinct().toList();
      assets.register(plan.entry().id(),"CONCEPT",plan.entry().description(),proofs,compilation.derivedContextForSummaries(plan.entry().inputSummaryIds()));
    });
    tasks.complete(task,"component-only",Instant.now());
    return entries.find(plan.entry().id()).orElseThrow();
  }
  void usage(String session,String version,String document,String evidence,String asset) {
    jdbc.update("INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids) VALUES(?,'component-usage','Synthetic component','Component usage','{}','ANSWERED','[]',jsonb_build_array(?::text))",session,evidence);
    String pack="pack-"+session;
    var auth=new JdbcPackageAuthorizationRepository(jdbc,mapper);
    auth.save(new PackageAuthorization(pack,"component-usage","component-only/"+pack,List.of(evidence),Instant.now().plusSeconds(3600)));
    var usage=new JdbcDownloadUsageRepository(jdbc,tx);
    usage.saveManifest("component-usage",pack,new PackageManifest(Instant.now(),List.of(evidence),List.of(
        new PackageVersion(version,document,"component.md","component-only","component.md",List.of(evidence))),List.of(new PackageAnswer(session,List.of(evidence)))),9);
    if(asset!=null) {
      long revision=jdbc.queryForObject("SELECT asset_version FROM kb_asset_current WHERE asset_id=?",Long.class,asset);
      jdbc.update("INSERT INTO query_asset_expansion(session_id,asset_id,asset_version,source_version_id,evidence_id,first_seen_round) VALUES(?,?,?,?,?,1)",session,asset,revision,version,evidence);
    }
    usage.recordCompletedTransfer("component-usage",pack,9);
    usage.recordCompletedTransfer("component-usage",pack,9);
  }
  void run(String output) throws Exception {
    var report=new LinkedHashMap<String,Object>();
    try {
      var original=summary("public","甲公司与乙公司签订合同 HT-COMP-001，付款期限为30日。");
      var annex=summary("other","甲公司与乙公司确认合同 HT-COMP-001 的补充协议，付款期限调整为60日。");
      var privateSummary=summary("secret","甲公司与乙公司签订合同 HT-COMP-002，存在保密条款。");
      var initial=concept(original,"CONTRACT","合同履约专题");
      var initialPlan=entries.prepare(initial,scope(original,"HT-COMP-001"),"",List.of());
      check("entryCannotPublishOutsideOwnedDatabaseTransaction",rejected(()->entries.publish(initialPlan)));
      var first=publish(original,initial,initialPlan,List.of());
      check("groundedConceptPublishesStableEntryAndOneCurrentProjection",first.revision()==1 && first.sources().size()==1 && assets.currentSources(first.id(),1,new SearchFilters(null,null,null,List.of())).isPresent());
      var candidates=entries.candidates(annex.id(),24);
      check("sameBusinessScopeSuppliesExactFrozenCrossSourceCandidate",candidates.size()==1 && candidates.get(0).id().equals(first.id()));
      var incoming=concept(annex,"CONTRACT","合同履约专题");
      check("unknownModelEntryIdRejected",rejected(()->entries.prepare(incoming,scope(annex,"HT-COMP-001"),"not-a-candidate",candidates)));
      check("semanticTypeCannotChangeWithinMatchedEntry",rejected(()->entries.prepare(concept(annex,"COMPANY","合同履约专题"),scope(annex,"HT-COMP-001"),first.id(),candidates)));
      check("businessScopeCannotChangeWithinMatchedEntry",rejected(()->entries.prepare(concept(privateSummary,"CONTRACT","合同履约专题"),scope(privateSummary,"HT-COMP-002"),first.id(),candidates)));
      var merged=publish(annex,incoming,entries.prepare(incoming,scope(annex,"HT-COMP-001"),first.id(),candidates),candidates);
      check("crossSourceMergeKeepsIdentityAndIndependentSourceMembership",merged.id().equals(first.id()) && merged.revision()==2 && merged.sources().size()==2 && merged.versionIds().equals(List.of("version-other","version-public")));
      check("unrelatedSameNameScopeIsNotAModelCandidate",entries.candidates(privateSummary.id(),24).isEmpty());
      check("lateFrozenCandidateCannotOverwriteNewEntryRevision",rejected(()->entries.prepare(incoming,scope(annex,"HT-COMP-001"),first.id(),candidates)));
      tasks.queue("version-public",GenerationTaskType.WIKI,"component-stable-wiki",Instant.now());
      var wikiTask=tasks.claimPending(1,Instant.now()).get(0);var wikiGate=tasks.publication(wikiTask);wikiGate.captureInputs(merged.versionIds());
      wikiGate.publish(()->{
        jdbc.update("INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle) VALUES(?,1,'CONTRACT','Synthetic component stable Wiki','component-only','component-only','ACTIVE')",merged.id());
        entries.wikiPublished(merged);
        assets.register(merged.id(),"WIKI","Synthetic component Wiki projection",List.of("evidence-public","evidence-other"),compilation.derivedContextForSummaries(merged.inputSummaryIds()));
      });
      tasks.complete(wikiTask,"component-only",Instant.now());
      var promoted=entries.find(first.id()).orElseThrow();
      check("wikiPromotionRetainsAssetIdAndSemanticRevision",promoted.stage().equals("WIKI") && promoted.id().equals(first.id()) && promoted.revision()==2);
      check("oldConceptProjectionIsNoLongerCurrentAfterWikiPromotion",assets.currentSources(first.id(),2,new SearchFilters(null,null,null,List.of())).isEmpty()
          && "WIKI".equals(jdbc.queryForObject("SELECT stage FROM kb_derived_asset a JOIN kb_asset_current c USING(asset_id,asset_version) WHERE asset_id=?",String.class,first.id())));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
      long currentRevision=jdbc.queryForObject("SELECT asset_version FROM kb_asset_current WHERE asset_id=?",Long.class,first.id());
      check("uncitedOrCitedInputRevocationImmediatelyBlocksDerivedReads",assets.currentSources(first.id(),currentRevision,new SearchFilters(null,null,null,List.of())).isEmpty());
      var repairedCandidates=entries.candidates(annex.id(),24);
      check("obsoleteCandidateProseIsNotReusedInModelView",repairedCandidates.size()==1 && !repairedCandidates.get(0).completeMetadata() && repairedCandidates.get(0).description().isEmpty()
          && !repairedCandidates.get(0).versionIds().contains("version-public"));
      var scheduler=new SelectiveGenerationScheduler(jdbc,tx,tasks,"component-kb");
      check("revocationRecordsStaleEntryWithoutRestoringOldPermissions",scheduler.invalidateChangedInputs()==1 && entries.find(first.id()).orElseThrow().lifecycle().equals("STALE"));
      check("repairUsesFreshAclIdentityAndPriorityBeyondDailyQuota",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='SUMMARY' AND subject_id='repair/acl/2' AND priority=100",Integer.class)==1
          && jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection",Integer.class)==0);
      jdbc.update("UPDATE document SET status='DELETED' WHERE document_id='doc-public'");
      scheduler.invalidateChangedInputs();
      check("deletedInputRepairsFromSurvivingIndependentSource",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='SUMMARY' AND version_id='version-other' AND subject_id LIKE 'repair/entry/%' AND priority=100",Integer.class)==1);
      scheduler.invalidateChangedInputs();
      check("staleRecoveryDoesNotDuplicateOrSpendAttempts",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='SUMMARY' AND subject_id LIKE 'repair/entry/%' AND attempt=0",Integer.class)==1);
      var parent=tasks.claimPending(1,Instant.now()).get(0);
      check("deletedSourceCannotWinRepairClaim",parent.versionId().equals("version-other") && parent.taskType()==GenerationTaskType.SUMMARY);
      var repairedBinding=ConceptTaskBinding.from(annex,parent);
      check("unchangedSourceContentCarriesDistinctRepairCause",repairedBinding.subject().contains("/repair/")
          && !repairedBinding.subject().equals(new ConceptTaskBinding(annex.id(),1,annex.contentHash(),"").subject()));
      // Synthetic component result, used only to exercise downstream durable identity/fences.
      tasks.complete(parent,"SYNTHETIC_COMPONENT_NO_MODEL",Instant.now());
      tasks.queueRepair(annex.versionId(),GenerationTaskType.CONCEPT,repairedBinding.subject(),Instant.now());
      var downstream=tasks.claimPending(1,Instant.now()).get(0);
      check("newRepairConceptVerifiesExactSourceHashAndAcl",downstream.taskType()==GenerationTaskType.CONCEPT
          && ConceptTaskBinding.verifiedSummaryId(downstream,compilation).equals(annex.id()));
      jdbc.update("UPDATE summary SET content_hash='synthetic-concurrent-change' WHERE summary_id=?",annex.id());
      check("repairCauseNeverBypassesSourceHashFence",rejected(()->ConceptTaskBinding.verifiedSummaryId(downstream,compilation)));
      check("staleCandidateStillOmitsRevokedInputAndProse",entries.candidates(annex.id(),24).stream()
          .allMatch(candidate->candidate.description().isEmpty() && !candidate.versionIds().contains("version-public")));
      // Cold independent raw documents remain evidence-only and get only a background association.
      jdbc.update("INSERT INTO document(document_id,logical_name,status) VALUES('doc-cold','Cold component source','ACTIVE')");
      jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('version-cold','doc-cold',1,'ACTIVE','cold.md',9,'component-only','component-only')");
      jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES('version-cold',10)");
      jdbc.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES('block-cold','version-cold',1,'PARAGRAPH','Cold unrelated component facts','component-only')");
      jdbc.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES('evidence-cold','doc-cold','version-cold','block-cold','component-only','{}','component-only')");
      scheduler.recoverAssociations();scheduler.refreshCandidates();
      check("coldRawCommitRecoveryDoesNotGenerateSummaryWithoutUsage",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='version-cold' AND task_type='SOURCE_ASSOCIATION'",Integer.class)==1
          && jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='version-cold' AND task_type='SUMMARY'",Integer.class)==0);
      for(int i=1;i<=3;i++) usage("cold-use-"+i,"version-cold","doc-cold","evidence-cold",null);
      check("repeatedCompletionCannotInflateThreeIndependentSourceContributions",jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE version_id='version-cold'",Integer.class)==3);
      scheduler.selectDaily();
      check("threeActualNativeReceiptContributionsSelectOneDailySummary",jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='SUMMARY' AND target_id='version-cold'",Integer.class)==1);
      check("sameDayAndRestartSelectionIsIdempotent",scheduler.selectDaily()==0 && new SelectiveGenerationScheduler(jdbc,tx,tasks,"component-kb").selectDaily()==0);
      report.put("status","PASS_REAL_ENTRY_COMPONENT");
    }catch(Throwable error){error.printStackTrace();report.put("status","FAIL_REAL_ENTRY_COMPONENT");report.put("error",error.toString());}
    report.put("checks",checks);
    report.put("boundary","Real PostgreSQL, production repositories, actual owner/epoch/source fences and native completed-transfer callbacks in an isolated retained schema. Summary/Concept/Wiki/query/transfer payloads are synthetic component fixtures; no model, browser or genuine user download acceptance is claimed. No public business rows or existing volumes altered.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  public static void main(String[] args)throws Exception{new KnowledgeEntryProbe().run(args[0]);}
}
