import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawPublicationWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native permission-change repair; synthetic projection IDs, no inference or Milvus claim. */
public final class RawAclRepairProbe {
  final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
      System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
  final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  final JdbcGenerationTaskRepository tasks = new JdbcGenerationTaskRepository(jdbc, tx, "raw-repair-component");
  final SelectiveGenerationScheduler scheduler = new SelectiveGenerationScheduler(jdbc,tx,tasks,"raw-repair-component");
  final JdbcRawPublicationWriter writer = new JdbcRawPublicationWriter(jdbc);
  final Map<String,Boolean> checks = new LinkedHashMap<>();
  void check(String name,boolean result) {checks.put(name,result);if(!result)throw new AssertionError(name);}
  long count(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
  boolean denied(Runnable action) {try{action.run();return false;}catch(IllegalStateException expected){return true;}}
  void run(String output)throws Exception {
    var report=new LinkedHashMap<String,Object>();
    try {
      tasks.queue("version-public",GenerationTaskType.EMBEDDING_REBUILD,"initial",Instant.now());
      var first=tasks.claimPendingEmbeddingRebuild(1,Instant.now()).get(0);
      var gate=tasks.publication(first);gate.captureInputs(List.of(first.versionId()));
      gate.publish(()->writer.publish(first.taskId()+":e"+first.executionEpoch(),null,first.taskId(),first.executionEpoch(),
          "doc-public","version-public",first.sourceAclVersion(),List.of("synthetic-physical-acl1"),()->{}));
      tasks.complete(first,"synthetic-only",Instant.now());
      check("unchangedAclHasNoRepair",scheduler.repairRawProjections()==0);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
      check("aclChangeHidesTheOldPublishedProjection",count("SELECT count(*) FROM raw_publication p JOIN kb_source_authority a USING(version_id) WHERE p.acl_version=a.acl_version")==0);
      var pool=Executors.newFixedThreadPool(2);
      try {for(var result:pool.invokeAll(List.<Callable<Integer>>of(scheduler::repairRawProjections,scheduler::repairRawProjections)))result.get();}
      finally {pool.shutdownNow();}
      check("concurrentRecoveryQueuesOnePriorityRepair",count("SELECT count(*) FROM compile_task WHERE subject_id='repair/raw/acl/2' AND priority=100")==1);
      check("repeatedRecoveryDoesNotInventAttempts",scheduler.repairRawProjections()==0 && count("SELECT attempt FROM compile_task WHERE subject_id='repair/raw/acl/2'")==0);
      var second=tasks.claimPendingEmbeddingRebuild(1,Instant.now()).get(0);
      check("repairCapturesCurrentAclAndHasItsOwnFixedStage",second.sourceAclVersion()==2 && second.attempt()==1
          && jdbc.queryForObject("SELECT extract(epoch FROM stage_deadline-started_at) FROM compile_task WHERE task_id=?",Double.class,second.taskId())==600d);
      var newGate=tasks.publication(second);newGate.captureInputs(List.of(second.versionId()));
      check("completedOldProducerCannotWriteAgain",denied(()->gate.publish(()->writer.publish(first.taskId()+":e1",null,first.taskId(),1,
          "doc-public","version-public",1,List.of("late-old"),()->{}))));
      newGate.publish(()->writer.publish(second.taskId()+":e"+second.executionEpoch(),null,second.taskId(),second.executionEpoch(),
          "doc-public","version-public",2,List.of("synthetic-physical-acl2"),()->{}));
      tasks.complete(second,"synthetic-only",Instant.now());
      check("ownedRepairSwitchesAclAndExactMembership",count("SELECT count(*) FROM raw_publication WHERE acl_version=2 AND compile_task_id=?",second.taskId())==1
          && count("SELECT count(*) FROM raw_publication_member WHERE physical_id='synthetic-physical-acl2'")==1
          && count("SELECT count(*) FROM raw_publication_member WHERE physical_id IN ('late-old','synthetic-physical-acl1')")==0);
      check("restartHasNoWorkAfterCurrentPublication",new SelectiveGenerationScheduler(jdbc,tx,tasks,"raw-repair-component").repairRawProjections()==0);
      jdbc.update("UPDATE document_metadata SET acl_version=3 WHERE version_id='version-public'");
      check("nextAclRevisionGetsDistinctTask",scheduler.repairRawProjections()==1 && count("SELECT count(*) FROM compile_task WHERE subject_id='repair/raw/acl/3'")==1);
      jdbc.update("UPDATE document SET status='DELETED' WHERE document_id='doc-public'");
      check("deletedSourceCannotBeClaimedOrRequeued",scheduler.repairRawProjections()==0 && tasks.claimPendingEmbeddingRebuild(1,Instant.now()).isEmpty());
      report.put("status","PASS_ACTUAL_RAW_ACL_REPAIR_COMPONENT");
    } catch(Throwable error){error.printStackTrace();report.put("status","FAIL_ACTUAL_RAW_ACL_REPAIR_COMPONENT");report.put("error",error.toString());}
    report.put("checks",checks);
    report.put("boundary","Retained isolated PostgreSQL, real scheduler/repository/owner-fenced publication transactions. Source and physical IDs are synthetic component fixtures. No model inference, Milvus write, browser ACL operation, or full deployment acceptance claimed.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  public static void main(String[] args)throws Exception{new RawAclRepairProbe().run(args[0]);}
}
