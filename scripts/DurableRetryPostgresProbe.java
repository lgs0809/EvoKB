import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.raw.*;
import cn.lgs.knowledgebase.application.task.DurableRetryBackoff;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native durable retries >5, restarts, alias concurrency and fixed single-attempt authority. */
public final class DurableRetryPostgresProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static JdbcTemplate db;static TransactionTemplate tx;
  static void check(String key,boolean ok){checks.put(key,ok);if(!ok)throw new AssertionError(key);}
  static JdbcGenerationTaskRepository generation(){return new JdbcGenerationTaskRepository(db,tx,"enterprise");}
  static JdbcIngestionRepository raw(){return new JdbcIngestionRepository(db,tx,new ObjectMapper());}
  static int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
  static Instant clock(){return db.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
  static Instant due(String table,String key,String id){return db.queryForObject("SELECT next_attempt_at FROM "+table+" WHERE "+key+"=?",Timestamp.class,id).toInstant();}
  static String file(){String b=UUID.randomUUID().toString(),f=UUID.randomUUID().toString();db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'synthetic-native-retry',1)",b);db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'fixture.md','fixture.md','fixture-only','fixture-only','fixture-only')",f,b);raw().queueBatch(b,clock());return f;}
  static boolean refused(Runnable action){try{action.run();return false;}catch(IllegalStateException expected){return true;}}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper().findAndRegisterModules();
    try{
      db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
      var tasks=generation();var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
      for(int i=0;i<24;i++){int n=i;futures.add(pool.submit(()->{start.await();return (n%3==0?generation().queueRepair("version-public",GenerationTaskType.SUMMARY,"",clock()):generation().queue("version-public",GenerationTaskType.SUMMARY,n%3==1?"":"command/synthetic-"+n,clock())).taskId();}));}
      start.countDown();var ids=new HashSet<String>();for(var f:futures)ids.add(f.get(20,TimeUnit.SECONDS));pool.shutdownNow();
      check("twentyFourOrdinaryRepairAndCommandSubmissionsShareOnePersistentTask",ids.size()==1&&count("SELECT count(*) FROM compile_task")==1);String id=ids.iterator().next();
      check("retryCannotRedirectAnotherKnowledgeBaseTask",refused(()->new JdbcGenerationTaskRepository(db,tx,"other-knowledge-base").retry(id,clock())));
      var originalLedger=new ArrayList<Object>();
      for(int attempt=1;attempt<=8;attempt++){
        tasks=generation();var claimed=tasks.claimPending(1,clock());check("generationAttempt"+attempt+"ContinuesSameIdentityAndMonotonicEpoch",claimed.size()==1&&claimed.get(0).taskId().equals(id)&&claimed.get(0).attempt()==attempt&&claimed.get(0).executionEpoch()==attempt);
        var task=claimed.get(0);var gate=tasks.publication(task);gate.captureInputs(List.of("version-public"));
        check("generationAttempt"+attempt+"HasOneFixed600SecondDatabaseDeadline",count("SELECT count(*) FROM compile_task WHERE task_id=? AND stage_deadline=started_at+interval '600 seconds'",id)==1);
        Instant now=clock(),next=GenerationTaskRetryPolicy.nextAttemptAt(task,new TransientDependencyFailure("synthetic transport",null),now).orElseThrow();tasks.defer(task,"SYNTHETIC_TRANSPORT","explicit fixture, no model",next,now);
        Instant persisted=due("compile_task","task_id",id);var restart=generation();check("generationAttempt"+attempt+"SurvivesRestartAndCannotClaimEarly",restart.claimPending(1,clock()).isEmpty());
        restart.queueRepair("version-public",GenerationTaskType.SUMMARY,"",clock());restart.queue("version-public",GenerationTaskType.SUMMARY,"command/new-admin",clock());restart.retry(id,clock());check("generationAttempt"+attempt+"AliasesAndManualRetryCannotAdvanceBackoff",persisted.equals(due("compile_task","task_id",id))&&count("SELECT count(*) FROM compile_task")==1);
        check("generationAttempt"+attempt+"HistoryRetainsItsOwnDueTime",count("SELECT count(*) FROM compile_task_failure WHERE task_id=? AND attempt=? AND next_attempt_at IS NOT NULL AND outcome='RETRY_WAIT'",id,attempt)==1);
        if(attempt<8)db.update("UPDATE compile_task SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      }
      originalLedger.addAll(db.queryForList("SELECT * FROM compile_task_failure WHERE task_id=? ORDER BY attempt",id));
      // Explicit retained pre-upgrade FAILED fixture, not a business success/state manufacture.
      db.update("UPDATE compile_task SET status='FAILED',finished_at=clock_timestamp(),next_attempt_at=NULL,error_code='STAGE_DEADLINE_EXPIRED' WHERE task_id=?",id);
      tasks=generation();tasks.resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());Instant legacyDue=due("compile_task","task_id",id);generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      check("historicalTerminalFailureIsNormallyRescheduledWithoutCounterOrHistoryReset",count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RETRY_WAIT' AND attempt=8 AND execution_epoch=8",id)==1&&legacyDue.equals(due("compile_task","task_id",id))&&originalLedger.equals(db.queryForList("SELECT * FROM compile_task_failure WHERE task_id=? ORDER BY attempt",id)));
      db.update("UPDATE compile_task SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE task_id=?",id);var ninth=generation().claimPending(1,clock()).get(0);var oldGate=generation().publication(ninth);
      db.update("UPDATE compile_task SET stage_deadline=clock_timestamp()-interval '1 second' WHERE task_id=?",id);generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      check("expiredNinthAttemptWaitsAndOldAuthorityCannotPublish",count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RETRY_WAIT' AND attempt=9",id)==1&&count("SELECT count(*) FROM compile_task_failure WHERE task_id=?",id)==9&&refused(oldGate::check));
      db.update("UPDATE compile_task SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      var orphan = generation().claimPending(1,clock()).get(0);
      db.update("UPDATE compile_task SET stage_deadline=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      db.update("UPDATE kb_compilation_control SET task_id=NULL,task_execution_epoch=NULL,owner_id=NULL,stage_deadline=NULL WHERE task_id=?",id);
      generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      check("expiredOrphanDoesNotBlockRecoveryOrDuplicateHistory",count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RETRY_WAIT' AND attempt=10 AND next_attempt_at>clock_timestamp()",id)==1
          && count("SELECT count(*) FROM compile_task_failure WHERE task_id=? AND attempt=10 AND error_code='STAGE_DEADLINE_EXPIRED'",id)==1
          && refused(generation().publication(orphan)::check));
      db.update("UPDATE compile_task SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      var replacement = generation().queue("version-secret",GenerationTaskType.SUMMARY,"",clock());
      tasks = generation();
      var stale = tasks.claimPending(1,clock()).get(0);
      check("replacedFixtureUsesOriginalAttempt",stale.taskId().equals(id) && stale.attempt()==11);
      db.update("UPDATE compile_task SET stage_deadline=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      db.update("UPDATE kb_compilation_control SET stage_deadline=clock_timestamp()-interval '1 second' WHERE task_id=?",id);
      var current = tasks.claimPending(1,clock()).get(0);
      check("replacementHasItsOwnLiveControl",current.taskId().equals(replacement.taskId()));
      var currentGate = tasks.publication(current);
      currentGate.captureInputs(List.of("version-secret"));
      generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      currentGate.check();
      check("expiredRecoveryPreservesReplacementControl",count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RETRY_WAIT' AND attempt=11",id)==1
          && count("SELECT count(*) FROM kb_compilation_control WHERE task_id=? AND task_execution_epoch=? AND owner_id=?",current.taskId(),current.executionEpoch(),current.ownerId())==1);
      var now = clock();
      tasks.defer(current,"SYNTHETIC_TRANSPORT","explicit fixture, no model",now.plusSeconds(300),now);
      // Revocation stays a terminal input boundary; it never reaches a model even after recovery.
      db.update("UPDATE compile_task SET status='FAILED',next_attempt_at=NULL,finished_at=clock_timestamp() WHERE task_id=?",id);db.update("UPDATE document SET status='DELETED' WHERE document_id='doc-public'");generation().resetInterrupted(List.of(GenerationTaskType.SUMMARY),clock());
      check("revokedGenerationSourceCannotBeRecoveredOrClaimed",count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='FAILED' AND attempt=11",id)==1&&generation().claimPending(1,clock()).isEmpty());

      var other=generation().queue("version-other",GenerationTaskType.SUMMARY,"",clock());String alias=UUID.randomUUID().toString(),aliasSubject="repair/acl/01";
      db.update("INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,input_hash,attempt,knowledge_base_id,source_acl_version,next_attempt_at,priority) VALUES(?,'version-other','SUMMARY',?,?,'RETRY_WAIT','synthetic-legacy-wait',1,'enterprise',1,clock_timestamp()+interval '300 seconds',100)",alias,aliasSubject,CompilationContracts.generationTaskKey("enterprise","version-other",GenerationTaskType.SUMMARY,aliasSubject));
      generation().queue("version-other",GenerationTaskType.SUMMARY,"",clock());
      check("canonicalChainInheritsExistingRepairPriorityWithoutInventingANewLevel",count("SELECT priority FROM compile_task WHERE task_id=?",other.taskId())==100);
      Instant inherited=due("compile_task","task_id",alias);
      check("legacyAliasFutureWaitIsInheritedBeforeCanonicalFirstClaim",generation().claimPending(1,clock()).isEmpty()&&inherited.equals(due("compile_task","task_id",other.taskId()))&&count("SELECT attempt FROM compile_task WHERE task_id=?",other.taskId())==0);
      generation().queueRepair("version-other",GenerationTaskType.SUMMARY,"",clock());generation().retry(alias,clock());
      check("repairAndRetryOfAliasCannotShortenSharedWait",inherited.equals(due("compile_task","task_id",other.taskId())));
      String file=file();var raw=raw();var rawHistory=new ArrayList<Object>();
      var rawPool=Executors.newFixedThreadPool(6);var rawStart=new CountDownLatch(1);var rawFutures=new ArrayList<Future<Optional<RawTaskClaim>>>();for(int i=0;i<18;i++)rawFutures.add(rawPool.submit(()->{rawStart.await();return raw().claimRaw(file,UUID.randomUUID().toString());}));rawStart.countDown();var rawWinners=new ArrayList<RawTaskClaim>();for(var f:rawFutures)f.get(20,TimeUnit.SECONDS).ifPresent(rawWinners::add);rawPool.shutdownNow();
      check("eighteenConcurrentRawClaimsHaveOneOwnerAndAttempt",rawWinners.size()==1&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",file)==1);

      for(int attempt=1;attempt<=8;attempt++){
        var c=attempt==1?rawWinners.get(0):raw.claimRaw(file,UUID.randomUUID().toString()).orElseThrow();check("rawAttempt"+attempt+"KeepsIdentityEpochAndFixedDeadline",c.file().attempt()==attempt&&c.epoch()==attempt&&count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND stage_deadline=stage_started_at+interval '600 seconds'",file)==1);
        raw.rawFailure(c,"EMBEDDING_FAILED","synthetic dependency, no model",true);Instant persisted=due("ingestion_file","file_id",file);raw=raw();raw.resetInterrupted(clock());raw.retryFile(file,clock());
        check("rawAttempt"+attempt+"PersistsWaitAcrossRestartAndManualRetry",persisted.equals(due("ingestion_file","file_id",file))&&persisted.equals(raw.findFile(file).orElseThrow().nextAttemptAt())&&raw.claimRaw(file,UUID.randomUUID().toString()).isEmpty()&&raw.rawDispatchable(100).stream().noneMatch(o->o.fileId().equals(file))&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND attempt=? AND next_attempt_at IS NOT NULL",file,attempt)==1);
        if(attempt<8)db.update("UPDATE ingestion_file SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE file_id=?",file);
      }
      rawHistory.addAll(db.queryForList("SELECT * FROM raw_ingestion_failure WHERE file_id=? ORDER BY execution_epoch",file));
      db.update("UPDATE ingestion_file SET status='FAILED',isolated=TRUE,next_attempt_at=NULL,finished_at=clock_timestamp() WHERE file_id=?",file);raw=raw();raw.resetInterrupted(clock());Instant rawLegacyDue=due("ingestion_file","file_id",file);raw().resetInterrupted(clock());
      check("historicalRawFailureRequeuesNormallyWithOutboxAndAllOldFailures",count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='QUEUED' AND NOT isolated AND attempt=8",file)==1&&rawLegacyDue.equals(due("ingestion_file","file_id",file))&&rawHistory.equals(db.queryForList("SELECT * FROM raw_ingestion_failure WHERE file_id=? ORDER BY execution_epoch",file))&&count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=?",file)==1);
      db.update("UPDATE ingestion_file SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE file_id=?",file);var rawNinth=raw().claimRaw(file,UUID.randomUUID().toString()).orElseThrow();db.update("UPDATE ingestion_file SET stage_deadline=clock_timestamp()-interval '1 second' WHERE file_id=?",file);raw().resetInterrupted(clock());
      check("expiredRawNinthAttemptKeepsRetryingWithoutRenewal",count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='QUEUED' AND attempt=9 AND next_attempt_at>clock_timestamp()",file)==1&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=?",file)==9&&refused(raw().rawExecution(rawNinth)::check));
      String bad=file();var badClaim=raw().claimRaw(bad,UUID.randomUUID().toString()).orElseThrow();raw().rawFailure(badClaim,"INPUT_ERROR","immutable input mismatch",false);raw().resetInterrupted(clock());check("knownInvalidRawInputRemainsIsolatedWithoutModelRetries",count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='FAILED' AND isolated AND attempt=1",bad)==1&&raw().claimRaw(bad,UUID.randomUUID().toString()).isEmpty());
      String revoked=file();db.update("UPDATE ingestion_file SET version_id='version-public' WHERE file_id=?",revoked);check("revokedRawSourceCannotBeClaimedOrReachPreparation",raw().claimRaw(revoked,UUID.randomUUID().toString()).isEmpty()&&count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND error_code='RAW_SOURCE_REVOKED' AND attempt=0",revoked)==1);
      check("noRealModelOrSuccessfulPublicationIsManufactured",count("SELECT count(*) FROM compile_task WHERE status='SUCCEEDED'")==0&&count("SELECT count(*) FROM summary")==0&&count("SELECT count(*) FROM ingestion_file WHERE status='SUCCEEDED'")==0);
      report.put("status","PASS_REAL_POSTGRES_PERSISTENT_UNBOUNDED_RETRY");report.put("generationTask",id);report.put("rawFile",file);report.put("generationFailures",db.queryForList("SELECT * FROM compile_task_failure WHERE task_id=? ORDER BY attempt",id));report.put("rawFailures",db.queryForList("SELECT * FROM raw_ingestion_failure WHERE file_id=? ORDER BY execution_epoch",file));
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_PERSISTENT_UNBOUNDED_RETRY");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);report.put("boundary","Real PostgreSQL repository/transactions, 24 concurrent aliases, >5 normal owned attempts, reconstructed repositories, durable nextAttempt and original deadline checks. Explicit isolated historical FAILED and due/expiry fault fixtures; no model, parser, MinIO/Milvus or public workflow success.");mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
