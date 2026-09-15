import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real component control claims; retained synthetic fixtures, no manufactured model success. */
public final class PostgresKnowledgeBaseCompilationProbe {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Map<String,Boolean> checks=new LinkedHashMap<>();
  PostgresKnowledgeBaseCompilationProbe(){
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  }
  JdbcGenerationTaskRepository repository(String kb){return new JdbcGenerationTaskRepository(jdbc,tx,kb);}
  boolean rejected(Runnable call){try{call.run();return false;}catch(IllegalStateException expected){return true;}}
  void marker(String id){jdbc.update("INSERT INTO publication_probe(marker) VALUES(?)",id);}
  void run(String output, boolean expiry)throws Exception{
    var a=repository("kb-a");var b=repository("kb-b");
    var first=a.queue("version-public",GenerationTaskType.GRAPH,"shared-topic",Instant.now());
    var second=a.queue("version-other",GenerationTaskType.WIKI,"second-topic",Instant.now());
    var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
    var claims=new ArrayList<GenerationTask>();
    try{
      var futures=new ArrayList<Future<List<GenerationTask>>>();
      for(int i=0;i<8;i++)futures.add(pool.submit(()->{var worker=repository("kb-a");ready.countDown();if(!start.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture start timed out");return worker.claimPending(16,Instant.EPOCH);}));
      if(!ready.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture readiness timed out");
      start.countDown();for(var f:futures)claims.addAll(f.get(30,TimeUnit.SECONDS));
    }finally{pool.shutdownNow();}
    checks.put("eightConcurrentWorkersStartOnlyOneSameKnowledgeBaseStage",claims.size()==1&&claims.get(0).taskId().equals(first.taskId()));
    var active=claims.get(0);
    checks.put("databaseClaimUsesOneAttemptEpochAndExact600SecondDeadline",active.attempt()==1&&active.executionEpoch()==1&&active.sourceAclVersion()==1
        && jdbc.queryForObject("SELECT extract(epoch FROM stage_deadline-started_at) BETWEEN 599.99 AND 600.01 FROM compile_task WHERE task_id=?",Boolean.class,active.taskId()));
    checks.put("foreignKnowledgeBaseCannotPublishSameClaim",rejected(()->b.withinPublication(active,()->marker("foreign"))));
    var restarted=repository("kb-a");restarted.resetInterrupted(List.of(GenerationTaskType.GRAPH,GenerationTaskType.WIKI),Instant.EPOCH);
    checks.put("restartAndPollingNeverStealLiveKnowledgeBaseStage",restarted.claimPending(16,Instant.EPOCH).isEmpty()
        && jdbc.queryForObject("SELECT status='RUNNING' AND attempt=1 FROM compile_task WHERE task_id=?",Boolean.class,active.taskId()));
    a.withinPublication(active,()->marker("a-current"));
    var other=b.queue("version-public",GenerationTaskType.GRAPH,"shared-topic",Instant.now());
    var otherActive=b.claimPending(16,Instant.EPOCH).get(0);
    checks.put("differentKnowledgeBasesHaveIndependentIdempotencyAndConcurrentStages",!first.taskId().equals(other.taskId())
        && jdbc.queryForObject("SELECT count(*) FROM kb_compilation_control WHERE task_id IS NOT NULL",Integer.class)==2);
    b.withinPublication(otherActive,()->marker("b-current"));
    a.queue("version-public",GenerationTaskType.EMBEDDING_REBUILD,"raw-independent",Instant.now());
    var raw=a.claimPendingEmbeddingRebuild(1,Instant.EPOCH).get(0);
    a.withinPublication(raw,()->marker("raw-current"));a.complete(raw,"synthetic-component",Instant.now());
    checks.put("rawEncodingIsIndependentOfKnowledgeCompilationControl",jdbc.queryForObject("SELECT count(*) FROM publication_probe WHERE marker='raw-current'",Integer.class)==1);
    a.complete(active,"synthetic-component",Instant.now());
    var next=a.claimPending(16,Instant.EPOCH).get(0);
    checks.put("waitingInputsRemainSeparateTasksAndStartOnlyAfterPriorCommit",next.taskId().equals(second.taskId())&&next.attempt()==1
        && jdbc.queryForObject("SELECT control_epoch FROM kb_compilation_control WHERE knowledge_base_id='kb-a'",Long.class)==2);
    a.fail(next,"FIXTURE_FAILURE","explicit native component failure",Instant.now());
    checks.put("failedStageKeepsFailureEvidenceAndReleasesOnlyItsControl",jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=? AND outcome='FAILED'",Integer.class,next.taskId())==1
        && jdbc.queryForObject("SELECT task_id IS NULL FROM kb_compilation_control WHERE knowledge_base_id='kb-a'",Boolean.class));
    a.retry(next.taskId(),Instant.now());var retry=a.claimPending(1,Instant.EPOCH).get(0);
    checks.put("normalRetryGetsNewTaskEpochAndControlEpoch",retry.attempt()==2&&retry.executionEpoch()==2
        && jdbc.queryForObject("SELECT control_epoch FROM kb_compilation_control WHERE knowledge_base_id='kb-a'",Long.class)==3);
    checks.put("latePriorStageCannotWriteOrReleaseNewControl",rejected(()->a.withinPublication(next,()->marker("late")))
        && rejected(()->a.complete(next,"late",Instant.now()))
        && jdbc.queryForObject("SELECT task_id=? AND task_execution_epoch=? FROM kb_compilation_control WHERE knowledge_base_id='kb-a'",Boolean.class,retry.taskId(),retry.executionEpoch()));
    a.withinPublication(retry,()->marker("retry-current"));a.complete(retry,"synthetic-component",Instant.now());
    b.complete(otherActive,"synthetic-component",Instant.now());
    checks.put("allValidShortPublicationsCommitAndRejectedCallbacksLeaveNoRows",jdbc.queryForObject("SELECT count(*) FROM publication_probe",Integer.class)==4);
    checks.put("completedStagesReleaseBothControlsWithoutErasingEpochHistory",jdbc.queryForObject("SELECT count(*) FROM kb_compilation_control WHERE task_id IS NULL AND owner_id IS NULL AND stage_deadline IS NULL",Integer.class)==2);
    if(expiry){
      a.queue("version-public",GenerationTaskType.GRAPH,"expiry-topic",Instant.now());var old=a.claimPending(1,Instant.EPOCH).get(0);
      while(jdbc.queryForObject("SELECT stage_deadline>clock_timestamp() FROM compile_task WHERE task_id=?",Boolean.class,old.taskId()))Thread.sleep(1000);
      checks.put("actual600SecondExpiryRejectsOldPublication",rejected(()->a.withinPublication(old,()->marker("expired"))));
      restarted.resetInterrupted(List.of(GenerationTaskType.GRAPH),Instant.EPOCH);
      checks.put("nativeRecoveryRecordsRealDeadlineFailure",jdbc.queryForObject("SELECT status='RETRY_WAIT' AND error_code='STAGE_DEADLINE_EXPIRED' FROM compile_task WHERE task_id=?",Boolean.class,old.taskId()));
      while(jdbc.queryForObject("SELECT next_attempt_at>clock_timestamp() FROM compile_task WHERE task_id=?",Boolean.class,old.taskId()))Thread.sleep(500);
      var recovered=restarted.claimPending(1,Instant.EPOCH).get(0);
      checks.put("newProcessTakesControlAfterRealDeadlineAndNormalBackoff",recovered.taskId().equals(old.taskId())&&recovered.attempt()==2&&recovered.executionEpoch()==2&&!recovered.ownerId().equals(old.ownerId()));
      checks.put("expiredProcessCannotPublishAfterNewProcessTakesControl",rejected(()->a.withinPublication(old,()->marker("expired-after-takeover")))
          && rejected(()->a.complete(old,"expired",Instant.now())));
      restarted.withinPublication(recovered,()->marker("recovered-current"));restarted.complete(recovered,"synthetic-component",Instant.now());
      checks.put("recoveredProcessPublishesOnceWithoutDeadlineOrSuccessSqlEdits",jdbc.queryForObject("SELECT count(*) FROM publication_probe WHERE marker='recovered-current'",Integer.class)==1);
    }
    var report=new LinkedHashMap<String,Object>();
    report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_REAL_KB_COMPILATION_CONTROL":"FAIL_REAL_KB_COMPILATION_CONTROL");
    report.put("checks",checks);report.put("expiry",expiry?"ACTUAL_600_SECONDS_AND_NATIVE_BACKOFF":"NOT_RUN");
    report.put("controls",jdbc.queryForList("SELECT * FROM kb_compilation_control ORDER BY knowledge_base_id"));
    report.put("tasks",jdbc.queryForList("SELECT task_id,knowledge_base_id,status,attempt,execution_epoch,owner_id,started_at,stage_deadline,finished_at FROM compile_task ORDER BY created_at"));
    report.put("boundary","Native PostgreSQL/Spring component in a retained synthetic schema. Queue, claim, failure, retry, publication and completion use production methods. No manufactured model, workflow, business approval or generated content success; no deadline/status SQL editing. Different KB concurrency certifies stage-control isolation only, not all storage namespaces.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);
    if(checks.values().stream().anyMatch(v->!v))System.exit(1);
  }
  public static void main(String[] args)throws Exception{new PostgresKnowledgeBaseCompilationProbe().run(args[0],Boolean.parseBoolean(args[1]));}
}
