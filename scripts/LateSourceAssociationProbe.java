import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native PG recovery and publication fences; synthetic fixture/model decisions are labelled. */
public final class LateSourceAssociationProbe {
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final JdbcKnowledgeEntryCatalogue entries;
  final JdbcGenerationTaskRepository tasks;
  final Map<String,Boolean> checks = new LinkedHashMap<>();
  LateSourceAssociationProbe() {
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
    jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    entries = new JdbcKnowledgeEntryCatalogue(jdbc, tx, mapper, "component-kb");
    tasks = new JdbcGenerationTaskRepository(jdbc, tx, "component-kb");
  }
  void check(String name, boolean result) { checks.put(name,result); if(!result) throw new AssertionError(name); }
  int queued() { return jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='SOURCE_ASSOCIATION' AND subject_id LIKE 'related/%'", Integer.class); }
  void source(String id, String text) {
    jdbc.update("INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')", "doc-"+id,"Synthetic component "+id);
    jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,'ACTIVE','synthetic.md',9,'COMPONENT_ONLY','COMPONENT_ONLY')",id,"doc-"+id);
    jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES(?,10)",id);
    jdbc.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?,?,1,'PARAGRAPH',?,'COMPONENT_ONLY')","block-"+id,id,text);
    jdbc.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES(?,?,?,?,'COMPONENT_ONLY','{}','COMPONENT_ONLY')","proof-"+id,"doc-"+id,id,"block-"+id);
  }
  KnowledgeEntryCatalogue.Entry entry(String suffix, String scope, String label) {
    String text="甲公司确认合同 "+scope+"，有书面验收后30日付款。";
    source(suffix,text);
    String summary="summary-"+suffix, fact="fact-"+suffix, concept="concept-"+suffix;
    jdbc.update("INSERT INTO document_unit(unit_id,version_id,unit_no,title,structure_type,document_type) VALUES(?,?,1,'Synthetic component','single','CONTRACT')","unit-"+suffix,suffix);
    jdbc.update("INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES(?,?,?,'COMPONENT_ONLY',?,'PASS','PASS','ACTIVE','SYNTHETIC_COMPONENT_NOT_MODEL')",summary,"unit-"+suffix,suffix,KnowledgeDomain.sha256(text));
    jdbc.update("INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status) VALUES(?,?,'Synthetic',?,'PASS')",fact,summary,text);
    jdbc.update("INSERT INTO summary_evidence(fact_id,evidence_id) VALUES(?,?)",fact,"proof-"+suffix);
    var f = new SummaryFact(fact,summary,"Synthetic",text,List.of("block-"+suffix),List.of("proof-"+suffix),"PASS","Synthetic component fixture");
    var c = new ConceptRecord(concept,summary,suffix,label,List.of(),"CONTRACT","SYNTHETIC_COMPONENT_NOT_MODEL",List.of(fact),"ACTIVE","Synthetic component fixture",Instant.now());
    var s = GraphScopeIdentity.scope("doc-"+suffix,List.of(new GraphScopeIdentity.Anchor("CONTRACT",scope,List.of(fact))),Map.of(fact,f));
    var plan = entries.prepare(c,s,"",List.of());
    tasks.queue(suffix,GenerationTaskType.CONCEPT,summary,Instant.now());
    var claimed=tasks.claimPending(1,Instant.now()).get(0);var gate=tasks.publication(claimed);
    gate.captureInputs(plan.entry().versionIds());
    gate.publish(()->{
      jdbc.update("INSERT INTO concept_candidate(concept_id,summary_id,version_id,preferred_label,concept_type_hint,description,lifecycle,model) VALUES(?,?,?,?,'CONTRACT','SYNTHETIC_COMPONENT_NOT_MODEL','ACTIVE','SYNTHETIC_COMPONENT_NOT_MODEL')",concept,summary,suffix,label);
      jdbc.update("INSERT INTO concept_fact_support(concept_id,fact_id) VALUES(?,?)",concept,fact);
      entries.publish(plan);
    });
    tasks.complete(claimed,"SYNTHETIC_COMPONENT_NOT_MODEL",Instant.now());
    return entries.find(plan.entry().id()).orElseThrow();
  }
  void run(String output) throws Exception {
    var report=new LinkedHashMap<String,Object>();
    try {
      source("zz-relevant","甲公司确认合同 HT-LATE-001 的补充协议，验收后付款期限调整为15日。");
      source("zy-unrelated","甲公司确认合同 HT-OTHER-002 的补充协议，独立项目不合并。");
      var old=tasks.queue("zz-relevant",GenerationTaskType.SOURCE_ASSOCIATION,"",Instant.now());
      var cold=tasks.claimPending(1,Instant.now()).get(0);var coldGate=tasks.publication(cold);coldGate.captureInputs(List.of(cold.versionId()));coldGate.publish(()->{});
      tasks.complete(cold,KnowledgeDomain.sha256("related_entries=0"),Instant.now());
      check("coldSourceAssociationInitiallyCompletesWithoutAnyEntry",old.taskId().equals(cold.taskId())&&entries.relatedCandidates("zz-relevant",24).isEmpty());
      for(int i=0;i<105;i++) source(String.format(Locale.ROOT,"ca-%03d",i),"Unrelated synthetic component "+i);
      var first=entry("entry-first","HT-LATE-001","合同付款专题");
      var scheduler=new SelectiveGenerationScheduler(jdbc,tx,tasks,"component-kb",entries);
      check("firstRecoveryCycleIsBoundedTo100Sources",scheduler.recoverExistingSourceAssociations()==0&&queued()==0);
      var parallel=new SelectiveGenerationScheduler(jdbc,tx,new JdbcGenerationTaskRepository(jdbc,tx,"component-kb"),"component-kb",entries);
      parallel.recoverExistingSourceAssociations();
      var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        var work=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for(var runner:List.of(scheduler,parallel))work.add(pool.submit(()->{ready.countDown();if(!go.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("native concurrent launch did not start");return runner.recoverExistingSourceAssociations();}));
        if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("native concurrent launch did not become ready");go.countDown();
        for(var future:work)future.get(30,java.util.concurrent.TimeUnit.SECONDS);
      } finally {pool.shutdownNow();}
      check("laterPageFindsRelevantAlreadyCompletedColdSource",queued()==1);
      check("twoConcurrentSchedulersAdmitOneDurableRelatedTask",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='zz-relevant' AND subject_id LIKE 'related/%' AND status='PENDING' AND attempt=0",Integer.class)==1);
      check("sameSnapshotDoesNotRepeatSweepOrAdmission",scheduler.recoverExistingSourceAssociations()==0&&queued()==1);
      var restarted=new SelectiveGenerationScheduler(jdbc,tx,new JdbcGenerationTaskRepository(jdbc,tx,"component-kb"),"component-kb",entries);
      restarted.recoverExistingSourceAssociations();restarted.recoverExistingSourceAssociations();
      check("restartRescansButDurableIdentityDoesNotDuplicate",queued()==1);
      check("similarCompanyWithDifferentContractIsNotQueued",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='zy-unrelated'",Integer.class)==0);
      check("coldRecoveryDoesNotCreateUsageSummaryWikiOrGraph",jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution",Integer.class)==0&&jdbc.queryForObject("SELECT count(*) FROM asset_usage_contribution",Integer.class)==0&&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type IN ('SUMMARY','WIKI','GRAPH')",Integer.class)==0);
      var associated=tasks.claimPending(1,Instant.now()).get(0);var owned=tasks.publication(associated);owned.captureInputs(List.of("entry-first","zz-relevant"));
      owned.publish(()->entries.relatedSource(first,"zz-relevant",List.of("proof-zz-relevant"),"验收后付款期限调整为15日"));
      tasks.complete(associated,"SYNTHETIC_MODEL_DECISION_NATIVE_FENCED_PUBLICATION",Instant.now());
      check("pendingSourceBlocksRepeatedAssociationAndOldConclusion",entries.relatedCandidates("zz-relevant",24).isEmpty()&&entries.find(first.id()).orElseThrow().lifecycle().equals("STALE"));
      var afterPending=new SelectiveGenerationScheduler(jdbc,tx,tasks,"component-kb",entries);afterPending.recoverExistingSourceAssociations();afterPending.recoverExistingSourceAssociations();
      check("restartAfterPendingMatchDoesNotGenerateAnotherModelTask",queued()==1);
      // Seed a synthetic historical completion in this isolated component schema only.
      // A later entry revision can lose membership; that historical marker is not a tombstone.
      jdbc.update("UPDATE kb_entry_pending_source SET associated_at=clock_timestamp(),graph_repaired_at=clock_timestamp() WHERE entry_id=?",first.id());
      check("historicalCompletedAssociationDoesNotHideAnOtherwiseCurrentCandidate",entries.relatedCandidates("zz-relevant",24).stream().anyMatch(e->e.id().equals(first.id())));
      tasks.queue("zz-relevant",GenerationTaskType.SOURCE_ASSOCIATION,"SYNTHETIC_COMPONENT_NEW_ASSOCIATION",Instant.now());
      var fresh=tasks.claimPending(1,Instant.now()).get(0);var freshGuard=tasks.publication(fresh);freshGuard.captureInputs(List.of("entry-first","zz-relevant"));
      var currentFirst=entries.find(first.id()).orElseThrow();
      freshGuard.publish(()->entries.relatedSource(currentFirst,"zz-relevant",List.of("proof-zz-relevant"),"付款期限调整为15日"));
      tasks.complete(fresh,"SYNTHETIC_MODEL_DECISION_NATIVE_REASSOCIATION",Instant.now());
      check("newOwnedMatchReopensPendingMembershipAndGraphRepair",jdbc.queryForObject("SELECT count(*) FROM kb_entry_pending_source WHERE entry_id=? AND associated_at IS NULL AND graph_repaired_at IS NULL AND impact_text='付款期限调整为15日'",Integer.class,first.id())==1&&entries.relatedCandidates("zz-relevant",24).isEmpty());
      var second=entry("entry-second","HT-LATE-001","合同交付专题");
      scheduler.recoverExistingSourceAssociations();scheduler.recoverExistingSourceAssociations();
      check("newEntryIdentityRevisitsPreviouslyInspectedSource",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='zz-relevant' AND subject_id LIKE 'related/%'",Integer.class)==2);
      int beforeRevocation=jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='zz-relevant' AND subject_id LIKE 'related/%'",Integer.class);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='entry-second'");
      check("revokedEntryInputNeverAppearsAsAssociationCandidate",entries.relatedCandidates("zz-relevant",24).isEmpty());
      var rejected=new SelectiveGenerationScheduler(jdbc,tx,tasks,"component-kb",entries);rejected.recoverExistingSourceAssociations();rejected.recoverExistingSourceAssociations();
      check("revokedEntryInputDoesNotReadmitColdAnnex",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='zz-relevant' AND subject_id LIKE 'related/%'",Integer.class)==beforeRevocation);
      jdbc.update("UPDATE document SET status='DELETED' WHERE document_id='doc-zz-relevant'");
      var claimedVersions=new ArrayList<String>();
      // Other current sources legitimately contain the shared contract scope. Drain their
      // component tasks through native fences; do not assume the cold annex is the only job.
      for(int i=0;i<16;i++) {
        var pending=tasks.claimPending(1,Instant.now());if(pending.isEmpty())break;
        var task=pending.get(0);claimedVersions.add(task.versionId());
        var guard=tasks.publication(task);guard.captureInputs(List.of(task.versionId()));guard.publish(()->{});
        tasks.complete(task,"SYNTHETIC_COMPONENT_DRAIN_NO_MODEL_OR_ENTRY_MUTATION",Instant.now());
      }
      check("deletedIncomingSourceCannotBeClaimedForQueuedAssociation",!claimedVersions.contains("zz-relevant")&&tasks.claimPending(1,Instant.now()).isEmpty());
      report.put("status","PASS_NATIVE_LATE_SOURCE_ASSOCIATION");
    } catch(Throwable failure) {failure.printStackTrace();report.put("status","FAIL_NATIVE_LATE_SOURCE_ASSOCIATION");report.put("error",failure.toString());}
    report.put("checks",checks);report.put("boundary","Real PostgreSQL and production scheduler/catalogue/owner/epoch/current-source fences in a retained isolated schema. Source/Summary/Concept and the pending association decision are synthetic component fixtures. No LLM/Milvus/browser/business acceptance or public-row mutation claimed.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);
    if(report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
  public static void main(String[] args) throws Exception {new LateSourceAssociationProbe().run(args[0]);}
}
