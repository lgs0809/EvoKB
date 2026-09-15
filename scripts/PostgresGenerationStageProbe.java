import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated PostgreSQL lifecycle fixtures, never fabricated model or user success. */
public final class PostgresGenerationStageProbe {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Map<String, Boolean> checks = new LinkedHashMap<>();

  PostgresGenerationStageProbe() {
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
        "evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  private JdbcGenerationTaskRepository repository() {
    return new JdbcGenerationTaskRepository(jdbc,transactions);
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
  }

  private boolean rejected(Runnable operation) {
    try { operation.run(); return false; }
    catch (IllegalStateException expected) { return true; }
  }

  private void marker(String name) {
    jdbc.update("INSERT INTO publication_probe(marker) VALUES(?)",name);
  }

  private int count(String name) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM publication_probe WHERE marker=?",Integer.class,name);
  }

  private GenerationTask altered(GenerationTask task,String owner,long epoch,long acl) {
    return new GenerationTask(task.taskId(),task.versionId(),task.taskType(),task.subjectId(),
        task.status(),task.attempt(),task.errorCode(),task.errorMessage(),task.createdAt(),
        owner,epoch,task.stageDeadline(),acl);
  }

  private String status(GenerationTask task) {
    return jdbc.queryForObject("SELECT status FROM compile_task WHERE task_id=?",String.class,task.taskId());
  }

  private void checkpoint(String path,String status,GenerationTask task) throws Exception {
    var report = new LinkedHashMap<String,Object>();
    report.put("status",status);report.put("checks",checks);
    report.put("taskId",task.taskId());report.put("actualDatabaseTime",databaseNow().toString());
    report.put("configuredStageSeconds",600);report.put("stageDeadline",task.stageDeadline().toString());
    report.put("boundary","Actual JDBC/transactions in retained isolated component schema. No model, upload, business approval or UI success claimed. Actual expiry waits only when --wait-deadline is selected; no SQL deadline/status rewriting.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(path),report);
  }

  private void run(String path,boolean waitDeadline) throws Exception {
    var a=repository();var b=repository();
    a.queue("version-public",GenerationTaskType.GRAPH,"scope-fixture",Instant.EPOCH);
    var before=databaseNow();
    var old=a.claimPending(1,Instant.EPOCH).get(0);
    var after=databaseNow();
    checks.put("claimUsesDatabaseClockAndFixed600Seconds",!old.stageDeadline().isBefore(before.plusSeconds(600))
        && !old.stageDeadline().isAfter(after.plusSeconds(600)));
    checks.put("claimContainsOwnerEpochAndFrozenSourceAcl",!old.ownerId().isBlank()
        && old.executionEpoch()==1 && old.attempt()==1 && old.sourceAclVersion()==1);
    b.resetInterrupted(List.of(GenerationTaskType.GRAPH),Instant.parse("2099-01-01T00:00:00Z"));
    checks.put("anotherProcessStartupCannotStealUnexpiredStage","RUNNING".equals(status(old))
        && b.claimPending(1,Instant.parse("2099-01-01T00:00:00Z")).isEmpty());
    var forgedOwner=altered(old,UUID.randomUUID().toString(),old.executionEpoch(),old.sourceAclVersion());
    checks.put("wrongOwnerCannotPublish",rejected(()->a.withinPublication(forgedOwner,()->marker("wrong-owner")))
        && count("wrong-owner")==0);
    var forgedEpoch=altered(old,old.ownerId(),old.executionEpoch()+1,old.sourceAclVersion());
    checks.put("wrongEpochCannotPublish",rejected(()->a.withinPublication(forgedEpoch,()->marker("wrong-epoch")))
        && count("wrong-epoch")==0);
    checks.put("failedPublicationCallbackRollsBack",rejected(()->a.withinPublication(old,()->{
      marker("rollback");throw new IllegalStateException("synthetic second write failure");})) && count("rollback")==0);
    a.withinPublication(old,()->marker("valid-current"));
    checks.put("currentOwnerCanPublish",count("valid-current")==1);
    jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
    checks.put("changedAclCannotPublishOrComplete",rejected(()->a.withinPublication(old,()->marker("stale-acl")))
        && rejected(()->a.complete(old,"forbidden",Instant.now())) && count("stale-acl")==0);
    var forgedAcl=altered(old,old.ownerId(),old.executionEpoch(),2);
    checks.put("callerCannotReplaceFrozenAcl",rejected(()->a.withinPublication(forgedAcl,()->marker("forged-acl")))
        && count("forged-acl")==0);
    a.fail(old,"ACL_CHANGED","synthetic current ACL revision changed",Instant.now());
    checks.put("knownDeterministicFailureSettlesWithoutWaiting","FAILED".equals(status(old)));
    a.retry(old.taskId(),Instant.now());
    var current=b.claimPending(1,Instant.now()).get(0);
    checks.put("normalExplicitRetryRetainsTaskAndAdvancesClaim",current.taskId().equals(old.taskId())
        && current.executionEpoch()==2 && current.attempt()==2 && current.sourceAclVersion()==2
        && !current.ownerId().equals(old.ownerId()));
    checks.put("oldWorkerCannotPublishCompleteOrFail",rejected(()->a.withinPublication(old,()->marker("late")))
        && rejected(()->a.complete(old,"late",Instant.now()))
        && rejected(()->a.fail(old,"LATE","late old worker",Instant.now())) && count("late")==0);
    b.withinPublication(current,()->marker("retry-current"));
    b.complete(current,"component-current-owner",Instant.now());
    checks.put("newClaimCanPublishAndComplete","SUCCEEDED".equals(status(current)) && count("retry-current")==1);
    checks.put("duplicateCompletionRejected",rejected(()->b.complete(current,"duplicate",Instant.now())));

    a.queue("version-other",GenerationTaskType.GRAPH,"real-expiry-fixture",Instant.now());
    var expiry=a.claimPending(1,Instant.now()).get(0);
    if (!waitDeadline) {
      checkpoint(path,checks.values().stream().allMatch(Boolean.TRUE::equals)
          ? "PASS_POSTGRES_STAGE_COMPONENTS_EXPIRY_NOT_RUN" : "FAIL_POSTGRES_STAGE_COMPONENTS",expiry);
      return;
    }
    checkpoint(path+".waiting.json","RUNNING_ACTUAL_600_SECOND_DEADLINE_WAIT",expiry);
    System.out.println("Waiting actual unchanged 600-second database deadline; no lease renewal or SQL deadline edits");
    while (databaseNow().isBefore(expiry.stageDeadline().plusMillis(150))) Thread.sleep(1000);
    checks.put("expiredOwnerCannotPublishCompleteOrFail",rejected(()->a.withinPublication(expiry,()->marker("expired")))
        && rejected(()->a.complete(expiry,"expired",Instant.now()))
        && rejected(()->a.fail(expiry,"LATE","expired failure",Instant.now())) && count("expired")==0);
    b.resetInterrupted(List.of(GenerationTaskType.GRAPH),Instant.EPOCH);
    checks.put("expiredRecoveryPersistsRetryWaitAndOneFailure","RETRY_WAIT".equals(status(expiry))
        && jdbc.queryForObject("SELECT COUNT(*) FROM compile_task_failure WHERE task_id=? AND error_code='STAGE_DEADLINE_EXPIRED'",Integer.class,expiry.taskId())==1);
    b.resetInterrupted(List.of(GenerationTaskType.GRAPH),Instant.now());
    checks.put("repeatedRecoveryDoesNotSpendAttemptOrDuplicateFailure",jdbc.queryForObject(
        "SELECT attempt FROM compile_task WHERE task_id=?",Integer.class,expiry.taskId())==1
        && jdbc.queryForObject("SELECT COUNT(*) FROM compile_task_failure WHERE task_id=?",Integer.class,expiry.taskId())==1);
    checks.put("hostFutureCannotSkipDatabaseRetryBackoff",b.claimPending(1,Instant.parse("2099-01-01T00:00:00Z")).isEmpty());
    var due=jdbc.queryForObject("SELECT next_attempt_at FROM compile_task WHERE task_id=?",java.sql.Timestamp.class,expiry.taskId()).toInstant();
    while (databaseNow().isBefore(due.plusMillis(150))) Thread.sleep(250);
    var replacement=b.claimPending(1,Instant.EPOCH).get(0);
    checks.put("legitimateRecoveryAdvancesOwnerEpochAndBusinessAttempt",replacement.taskId().equals(expiry.taskId())
        && replacement.executionEpoch()==2 && replacement.attempt()==2 && !replacement.ownerId().equals(expiry.ownerId()));
    b.withinPublication(replacement,()->marker("after-expiry"));b.complete(replacement,"recovered",Instant.now());
    checks.put("replacementPublishesOnceAfterNormalBackoff",count("after-expiry")==1 && "SUCCEEDED".equals(status(replacement)));
    checkpoint(path,checks.values().stream().allMatch(Boolean.TRUE::equals)
        ? "PASS_REAL_POSTGRES_STAGE_AND_600_SECOND_EXPIRY" : "FAIL_REAL_POSTGRES_STAGE",expiry);
  }

  public static void main(String[] args) throws Exception {
    var probe=new PostgresGenerationStageProbe();probe.run(args[0],Boolean.parseBoolean(args[1]));
    if (probe.checks.values().stream().anyMatch(v->!v)) System.exit(1);
  }
}
