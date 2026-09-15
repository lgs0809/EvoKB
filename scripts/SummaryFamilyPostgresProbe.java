import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native concurrent queue/claim/retry budgets; explicit legacy leaf fixture, no model. */
public final class SummaryFamilyPostgresProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String key,boolean ok){checks.put(key,ok);if(!ok)throw new AssertionError(key);}
  static boolean refused(Runnable run){try{run.run();return false;}catch(IllegalStateException expected){return true;}}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper().findAndRegisterModules();
    try{
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      var tasks=new JdbcGenerationTaskRepository(jdbc,tx,"enterprise");
      var pool=Executors.newFixedThreadPool(8);var latch=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
      for(int i=0;i<16;i++){int n=i;futures.add(pool.submit(()->{latch.await();return (n%2==0?tasks.queue("version-public",GenerationTaskType.SUMMARY,"",Instant.now()):tasks.queueRepair("version-public",GenerationTaskType.SUMMARY,"",Instant.now())).taskId();}));}
      latch.countDown();var ids=new HashSet<String>();for(var future:futures)ids.add(future.get(15,TimeUnit.SECONDS));pool.shutdownNow();
      check("concurrentOrdinaryAndRepairQueuesShareOneTask",ids.size()==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task",Integer.class)==1);
      String original=ids.iterator().next();
      for(int attempt=1;attempt<=5;attempt++){
        var claimed=tasks.claimPending(1,Instant.now());check("nativeAttempt"+attempt+"HasOneOwnedClaim",claimed.size()==1&&claimed.get(0).attempt()==attempt);
        var task=claimed.get(0);tasks.publication(task).captureInputs(List.of("version-public"));
        tasks.fail(task,"SYNTHETIC_FAILURE","No model called; native budget fixture",Instant.now());
        if(attempt<5)tasks.retry(task.taskId(),Instant.now());
      }
      check("repairCannotCreateSixthAliasAndRetryCannotResetBudget",tasks.queueRepair("version-public",GenerationTaskType.SUMMARY,"",Instant.now()).taskId().equals(original)
          &&refused(()->tasks.retry(original,Instant.now()))&&refused(()->tasks.queue("version-public",GenerationTaskType.SUMMARY,"command/new-subject",Instant.now()))
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task",Integer.class)==1);
      String legacy=UUID.randomUUID().toString(),subject="repair/acl/01";
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,input_hash,attempt,knowledge_base_id,source_acl_version,priority) VALUES(?,'version-public','SUMMARY',?,?,'PENDING',?,0,'enterprise',1,100)",
          legacy,subject,CompilationContracts.generationTaskKey("enterprise","version-public",GenerationTaskType.SUMMARY,subject),"synthetic-existing-pending-alias");
      check("claimAlsoRejectsARecordedLegacyPendingAliasWithoutModelAttempt",tasks.claimPending(1,Instant.now()).isEmpty()
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='FAILED' AND attempt=0 AND error_code='SUMMARY_INPUT_ATTEMPTS_EXHAUSTED'",Integer.class,legacy)==1);
      Thread.sleep(5);
      String late=UUID.randomUUID().toString(),lateSubject="repair/acl/0001";
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,input_hash,attempt,knowledge_base_id,source_acl_version,execution_epoch,created_at,finished_at) VALUES(?,'version-public','SUMMARY',?,?,'FAILED',?,1,'enterprise',1,1,clock_timestamp(),clock_timestamp())",
          late,lateSubject,CompilationContracts.generationTaskKey("enterprise","version-public",GenerationTaskType.SUMMARY,lateSubject),"synthetic-historical-overlimit");
      var repo=new JdbcCompilationRepository(jdbc,tx,mapper,null);
      repo.replaceDocumentUnits("version-public",List.of(new DocumentUnit("legacy-overlimit-unit","version-public",1,"synthetic","single","test",1,1)));
      String text="Synthetic fixture source",summary="synthetic-overlimit-summary";
      var fact=new SummaryFact("overlimit-fact",summary,"source",text,List.of("block-public"),List.of("evidence-public"),"PASS","Synthetic historical fixture, no model",
          GroundingConstraintAudit.accepted(text,GroundingConstraintAudit.exactCopyChecks(text,List.of(text))));
      repo.saveSummary(new SummaryRecord(summary,"legacy-overlimit-unit","doc-public","version-public","compilation/"+late+"/epoch-1/summary/retained.md",KnowledgeDomain.sha256(text),"PASS","PASS","ACTIVE","SYNTHETIC_HISTORICAL_NO_MODEL",text,List.of(fact),Instant.now()));
      var catalogue=new JdbcAssetCatalogue(jdbc,tx);var asset=catalogue.register(summary,"SUMMARY",text,List.of("evidence-public"),new DerivedContext(List.of("doc-public"),List.of("version-public"),List.of(),List.of(),List.of(),List.of(),List.of()));
      long revision=((Number)asset.get("asset_version")).longValue();
      check("historicalLeafWasVisibleBeforeMaintenance",catalogue.currentSources(summary,revision,null).isPresent());
      check("normalMaintenanceQuarantinesLeafWithoutDeletingFactsOrHistory",tasks.quarantineOverBudgetPublications()==1
          &&jdbc.queryForObject("SELECT lifecycle FROM summary WHERE summary_id=?",String.class,summary).equals("STALE")
          &&catalogue.currentSources(summary,revision,null).isEmpty()
          &&jdbc.queryForObject("SELECT count(*) FROM summary_fact WHERE summary_id=?",Integer.class,summary)==1
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=?",Integer.class,original)==5
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='FAILED' AND attempt=1",Integer.class,late)==1);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
      var newInput=tasks.queueRepair("version-public",GenerationTaskType.SUMMARY,"",Instant.now());
      var changed=tasks.claimPending(1,Instant.now());
      check("realAclRevisionHasItsOwnNewInputBudget",!newInput.taskId().equals(original)&&changed.size()==1&&changed.get(0).taskId().equals(newInput.taskId())&&changed.get(0).attempt()==1&&changed.get(0).sourceAclVersion()==2);
      tasks.fail(changed.get(0),"SYNTHETIC_FAILURE","new ACL fixture",Instant.now());
      String longSubject="summary/11111111-1111-1111-1111-111111111111/acl/2/content/"+"a".repeat(64)+"/repair/"+"b".repeat(96);
      var longTask=tasks.queue("version-public",GenerationTaskType.CONCEPT,longSubject,Instant.now());
      String stored=jdbc.queryForObject("SELECT idempotency_key FROM compile_task WHERE task_id=?",String.class,longTask.taskId());
      check("actualLongRepairKeyFitsExistingColumnAndRemainsIdempotent",stored.length()<=255&&stored.startsWith("generation-key-sha256:")
          &&stored.equals(CompilationContracts.generationTaskKey("enterprise","version-public",GenerationTaskType.CONCEPT,longSubject))
          &&tasks.queue("version-public",GenerationTaskType.CONCEPT,longSubject,Instant.now()).taskId().equals(longTask.taskId()));
      check("noFixtureTaskWasCompletedAsARealModelSuccess",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE status='SUCCEEDED'",Integer.class)==0);
      report.put("originalTaskId",original);report.put("syntheticLateTaskId",late);report.put("status","PASS_REAL_POSTGRES_SUMMARY_FAMILY_BUDGET");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_SUMMARY_FAMILY_BUDGET");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);report.put("boundary","Real PostgreSQL and concurrent queue/claim/retry/maintenance. Explicit isolated historical overlimit leaf fixture tests existing STALE/STAGED revocation, with retained facts/history; no real model, MinIO, Milvus or public workflow success.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
