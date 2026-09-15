import cn.lgs.knowledgebase.application.compilation.GenerationTaskRetryPolicy;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
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

/** Faulted dependency is synthetic; all queue transitions use the actual compiled adapter and MySQL. */
public final class GenerationTaskRetryProbe {
  private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");
  private final DriverManagerDataSource ds = new DriverManagerDataSource(
      System.getenv("EKB_PROBE_JDBC"), "root", System.getenv("EKB_PROBE_PASSWORD"));
  private final JdbcTemplate jdbc = new JdbcTemplate(ds);
  private JdbcGenerationTaskRepository tasks = repository();
  private final Map<String,Object> cases = new LinkedHashMap<>();

  private JdbcGenerationTaskRepository repository() {
    return new JdbcGenerationTaskRepository(jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(ds)));
  }

  private String version() {
    String document = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO document(document_id,logical_name) VALUES(?,?)", document,
        "SYNTHETIC_GENERATION_RETRY_PROTOCOL_FIXTURE");
    jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,?,0,?,?)",
        version,document,"queue-fixture.txt","0".repeat(64),"SYNTHETIC_NO_OBJECT/"+version);
    return version;
  }

  private Map<String,Object> row(String id) {
    return jdbc.queryForMap("SELECT status,attempt,error_code,output_hash,next_attempt_at FROM compile_task WHERE task_id=?",id);
  }

  private int failures(String id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM compile_task_failure WHERE task_id=?",Integer.class,id);
  }

  private boolean rejects(Runnable operation) {
    try { operation.run(); return false; }
    catch (IllegalStateException expected) { return true; }
  }

  private void check(String name, boolean passed, Object observed) {
    cases.put(name,Map.of("passed",passed,"observed",observed));
  }

  private Map<String,Object> run() {
    tasks.queue(version(),GenerationTaskType.GRAPH,UUID.randomUUID().toString(),NOW);
    GenerationTask first=tasks.claimPending(1,NOW).get(0);
    RuntimeException network=new TransientDependencyFailure("SYNTHETIC network timeout",null);
    Instant due=GenerationTaskRetryPolicy.nextAttemptAt(first,network,NOW).orElseThrow();
    tasks.defer(first,"SYNTHETIC_TIMEOUT",network.getMessage(),due,NOW);
    check("first_failure_atomically_records_deadline_and_ledger",
        due.equals(NOW.plusSeconds(5)) && "RETRY_WAIT".equals(row(first.taskId()).get("status"))
        && failures(first.taskId())==1,row(first.taskId()));

    tasks=repository();
    check("new_repository_does_not_claim_before_persisted_deadline",
        tasks.claimPending(1,NOW.plusSeconds(4)).isEmpty(),row(first.taskId()));
    tasks.queue(version(),GenerationTaskType.SUMMARY,"",NOW.plusSeconds(1));
    GenerationTask unrelated=tasks.claimPending(1,NOW.plusSeconds(4)).get(0);
    check("unrelated_job_runs_while_failed_dependency_waits",
        !first.taskId().equals(unrelated.taskId()) && unrelated.attempt()==1,
        Map.of("waiting",first.taskId(),"independent",unrelated.taskId()));
    tasks.complete(unrelated,"SYNTHETIC_QUEUE_ONLY_NO_ARTIFACT",NOW.plusSeconds(4));
    GenerationTask current=tasks.claimPending(1,due).get(0);
    check("deadline_reclaims_same_identity_with_incremented_attempt",
        current.taskId().equals(first.taskId()) && current.attempt()==2
        && row(current.taskId()).get("next_attempt_at")==null,row(current.taskId()));
    final Instant oldDue=due;
    boolean complete=rejects(()->tasks.complete(first,"STALE_NO_ARTIFACT",oldDue));
    boolean fail=rejects(()->tasks.fail(first,"STALE","old attempt",oldDue));
    boolean defer=rejects(()->tasks.defer(first,"STALE","old attempt",oldDue.plusSeconds(5),oldDue));
    check("late_complete_fail_defer_cannot_change_current_claim_or_ledger",
        complete && fail && defer && failures(first.taskId())==1
        && "RUNNING".equals(row(first.taskId()).get("status")),row(first.taskId()));

    var expectedSeconds=List.of(10L,20L,40L);
    for(long seconds:expectedSeconds) {
      Instant now=due, next=GenerationTaskRetryPolicy.nextAttemptAt(current,network,now).orElseThrow();
      tasks.defer(current,"SYNTHETIC_TIMEOUT",network.getMessage(),next,now);
      tasks=repository();
      boolean early=tasks.claimPending(1,next.minusSeconds(1)).isEmpty();
      check("attempt_"+current.attempt()+"_durable_exponential_wait",next.equals(now.plusSeconds(seconds)) && early,row(current.taskId()));
      current=tasks.claimPending(1,next).get(0);due=next;
    }
    boolean exhausted=current.attempt()==5 && GenerationTaskRetryPolicy.nextAttemptAt(current,network,due).isEmpty();
    tasks.fail(current,"SYNTHETIC_TIMEOUT","attempt budget exhausted",due);
    check("fifth_failure_is_terminal_without_automatic_sixth_claim",
        exhausted && "FAILED".equals(row(first.taskId()).get("status")) && failures(first.taskId())==5
        && tasks.claimPending(1,due.plusSeconds(3600)).isEmpty(),row(first.taskId()));
    var ledger=jdbc.queryForList("SELECT attempt,outcome,error_message FROM compile_task_failure WHERE task_id=? ORDER BY attempt",first.taskId());
    tasks.retry(first.taskId(),due.plusSeconds(1));
    GenerationTask manual=tasks.claimPending(1,due.plusSeconds(2)).get(0);
    check("explicit_retry_keeps_history_identity_and_lifetime_counter",
        manual.taskId().equals(first.taskId()) && manual.attempt()==6
        && GenerationTaskRetryPolicy.nextAttemptAt(manual,network,due).isEmpty()
        && ledger.equals(jdbc.queryForList("SELECT attempt,outcome,error_message FROM compile_task_failure WHERE task_id=? ORDER BY attempt",first.taskId())),ledger);
    tasks.complete(manual,"SYNTHETIC_QUEUE_ONLY_NO_ARTIFACT",due.plusSeconds(3));

    tasks.queue(version(),GenerationTaskType.EMBEDDING_REBUILD,"",NOW);
    GenerationTask embedding=tasks.claimPendingEmbeddingRebuild(1,NOW).get(0);
    tasks.defer(embedding,"SYNTHETIC_TIMEOUT","embedding transport fault",NOW.plusSeconds(5),NOW);
    boolean scope=tasks.claimPending(1,NOW.plusSeconds(5)).isEmpty()
        && tasks.claimPendingEmbeddingRebuild(1,NOW.plusSeconds(4)).isEmpty();
    GenerationTask nextEmbedding=tasks.claimPendingEmbeddingRebuild(1,NOW.plusSeconds(5)).get(0);
    check("embedding_retry_deadline_and_worker_type_scope",
        scope && nextEmbedding.taskId().equals(embedding.taskId()) && nextEmbedding.attempt()==2,row(embedding.taskId()));
    tasks.complete(nextEmbedding,"SYNTHETIC_QUEUE_ONLY_NO_ARTIFACT",NOW.plusSeconds(6));
    boolean passed=cases.values().stream().allMatch(c->Boolean.TRUE.equals(((Map<?,?>)c).get("passed")));
    return Map.of("status",passed?"PASS_REAL_MYSQL_DURABLE_RETRY_PROTOCOL":"FAIL_REAL_MYSQL_DURABLE_RETRY_PROTOCOL",
        "cases",cases,"boundary","Synthetic dependency faults and marked queue fixtures in a fresh isolated schema; real compiled JDBC and transactions. No real model outage, artifact acceptance, lease or multi-instance safety claimed.");
  }

  public static void main(String[] args) throws Exception {
    var result=new GenerationTaskRetryProbe().run();
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(args[0]),result);
    if (!String.valueOf(result.get("status")).startsWith("PASS"))System.exit(1);
  }
}
