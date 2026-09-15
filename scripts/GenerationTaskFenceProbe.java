import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL queue transitions only; no model, artifact generation or user success is claimed. */
public final class GenerationTaskFenceProbe {
  private static final Instant NOW = Instant.parse("2026-10-02T02:00:00Z");
  private static final List<GenerationTaskType> COMPILATION =
      List.of(GenerationTaskType.SUMMARY, GenerationTaskType.CONCEPT,
          GenerationTaskType.GRAPH, GenerationTaskType.WIKI);
  private final JdbcTemplate jdbc;
  private final JdbcGenerationTaskRepository tasks;

  private GenerationTaskFenceProbe() {
    DriverManagerDataSource ds = new DriverManagerDataSource(
        System.getenv("EKB_PROBE_JDBC"), "root", System.getenv("EKB_PROBE_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    tasks = new JdbcGenerationTaskRepository(jdbc,
        new TransactionTemplate(new DataSourceTransactionManager(ds)));
  }

  private String version() {
    String document = UUID.randomUUID().toString();
    String version = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO document(document_id,logical_name) VALUES(?,?)", document,
        "SYNTHETIC_GENERATION_QUEUE_FENCE_FIXTURE");
    jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,?,0,?,?)",
        version, document, "queue-fixture.txt", "0".repeat(64), "SYNTHETIC_NO_OBJECT/" + version);
    return version;
  }

  private void resetCompilation() throws Exception {
    reset(COMPILATION);
  }

  private void reset(List<GenerationTaskType> types) throws Exception {
    try {
      tasks.getClass().getMethod("resetInterrupted", List.class, Instant.class)
          .invoke(tasks, types, NOW.plusSeconds(1));
    } catch (NoSuchMethodException legacy) {
      tasks.getClass().getMethod("resetInterrupted", Instant.class).invoke(tasks, NOW.plusSeconds(1));
    }
  }

  private Map<String,Object> row(GenerationTask task) {
    return jdbc.queryForMap("SELECT status,attempt,output_hash,error_code FROM compile_task WHERE task_id=?", task.taskId());
  }

  private boolean rejected(Runnable operation) {
    try { operation.run(); return false; }
    catch (IllegalStateException expected) { return true; }
  }

  private void fail(GenerationTask old) {
    try {
      try {
        tasks.getClass().getMethod("fail", GenerationTask.class, String.class, String.class, Instant.class)
            .invoke(tasks, old, "STALE_WORKER", "late failure from the first attempt", NOW.plusSeconds(3));
      } catch (NoSuchMethodException legacy) {
        tasks.getClass().getMethod("fail", String.class, String.class, String.class, Instant.class)
            .invoke(tasks, old.taskId(), "STALE_WORKER", "late failure from the first attempt", NOW.plusSeconds(3));
      }
    } catch (InvocationTargetException rejected) {
      if (rejected.getCause() instanceof IllegalStateException failure) throw failure;
      throw new IllegalStateException(rejected.getCause());
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
  }

  private Map<String,Object> run() throws Exception {
    Map<String,Object> cases = new LinkedHashMap<>();
    GenerationTask queued = tasks.queue(version(), GenerationTaskType.EMBEDDING_REBUILD, "", NOW);
    GenerationTask embedding = tasks.claimPendingEmbeddingRebuild(1, NOW).get(0);
    resetCompilation();
    Map<String,Object> afterReset = row(embedding);
    boolean scope = "RUNNING".equals(afterReset.get("status")) && ((Number)afterReset.get("attempt")).intValue()==1;
    cases.put("compilation_startup_preserves_active_embedding", Map.of("passed",scope,"taskId",queued.taskId(),"observed",afterReset));
    if (!scope) embedding = tasks.claimPendingEmbeddingRebuild(1, NOW.plusSeconds(2)).get(0);
    tasks.complete(embedding, "settled-scope-fixture", NOW.plusSeconds(3));

    tasks.queue(version(), GenerationTaskType.GRAPH, UUID.randomUUID().toString(), NOW);
    GenerationTask graph = tasks.claimPending(1, NOW).get(0);
    reset(List.of(GenerationTaskType.EMBEDDING_REBUILD));
    Map<String,Object> graphAfterReset = row(graph);
    boolean reverseScope = "RUNNING".equals(graphAfterReset.get("status")) && ((Number)graphAfterReset.get("attempt")).intValue()==1;
    cases.put("embedding_startup_preserves_active_compilation", Map.of("passed",reverseScope,"taskId",graph.taskId(),"observed",graphAfterReset));
    if (!reverseScope) graph = tasks.claimPending(1, NOW.plusSeconds(2)).get(0);
    tasks.complete(graph,"settled-reverse-scope-fixture",NOW.plusSeconds(3));

    for (String kind : List.of("complete", "fail", "concept_fanout")) {
      String version = version();
      GenerationTaskType type = kind.equals("fail") ? GenerationTaskType.GRAPH : GenerationTaskType.CONCEPT;
      GenerationTask initial = tasks.queue(version, type, UUID.randomUUID().toString(), NOW);
      GenerationTask old = tasks.claimPending(1, NOW).get(0);
      resetCompilation();
      GenerationTask current = tasks.claimPending(1, NOW.plusSeconds(2)).get(0);
      boolean rejected = rejected(() -> {
        if (kind.equals("fail")) fail(old);
        else if (kind.equals("concept_fanout")) tasks.completeConceptAndQueueDerived(old,"old-worker-output",NOW.plusSeconds(3));
        else tasks.complete(old,"old-worker-output",NOW.plusSeconds(3));
      });
      Map<String,Object> observed = row(initial);
      int children = jdbc.queryForObject("SELECT COUNT(*) FROM compile_task WHERE version_id=? AND task_id<>?",Integer.class,version,initial.taskId());
      boolean passed = rejected && "RUNNING".equals(observed.get("status"))
          && ((Number)observed.get("attempt")).intValue()==current.attempt()
          && observed.get("output_hash")==null && observed.get("error_code")==null && children==0;
      Map<String,Object> proof = new LinkedHashMap<>();
      proof.put("passed",passed);proof.put("staleAttemptRejected",rejected);
      proof.put("taskId",initial.taskId());proof.put("firstAttempt",old.attempt());proof.put("currentAttempt",current.attempt());
      proof.put("observed",observed);proof.put("downstreamTasksBeforeCurrentCompletion",children);
      if (passed) {
        GenerationTask wrongIdentity = new GenerationTask(current.taskId(), UUID.randomUUID().toString(), current.taskType(),current.subjectId(),current.status(),current.attempt(),"","",current.createdAt());
        proof.put("wrongFrozenIdentityRejected",rejected(() -> tasks.complete(wrongIdentity,"wrong-source",NOW.plusSeconds(4))));
        if (kind.equals("fail")) {
          fail(current);
          proof.put("currentAttemptCanFail", "FAILED".equals(row(initial).get("status")));
          proof.put("duplicateCurrentFailureRejected",rejected(() -> fail(current)));
          tasks.retry(current.taskId(),NOW.plusSeconds(5));
          GenerationTask retried = tasks.claimPending(1,NOW.plusSeconds(6)).get(0);
          tasks.complete(retried,"current-worker-output",NOW.plusSeconds(7));
          proof.put("explicitRetryPreservesTaskIdentity",retried.taskId().equals(current.taskId()) && retried.attempt()==current.attempt()+1);
          proof.put("currentRetryCanComplete", "SUCCEEDED".equals(row(initial).get("status")));
        } else {
          if (kind.equals("concept_fanout")) tasks.completeConceptAndQueueDerived(current,"current-worker-output",NOW.plusSeconds(4));
          else tasks.complete(current,"current-worker-output",NOW.plusSeconds(4));
          proof.put("currentAttemptCanComplete", "SUCCEEDED".equals(row(initial).get("status")));
          proof.put("duplicateCurrentCompletionRejected",rejected(() -> tasks.complete(current,"duplicate",NOW.plusSeconds(5))));
          if (kind.equals("concept_fanout")) proof.put("currentFanoutExactlyGraphAndWiki",jdbc.queryForObject("SELECT COUNT(*) FROM compile_task WHERE version_id=? AND task_id<>? AND task_type IN ('GRAPH','WIKI')",Integer.class,version,initial.taskId())==2);
        }
        passed = proof.entrySet().stream().filter(e -> e.getValue() instanceof Boolean).allMatch(e -> Boolean.TRUE.equals(e.getValue()));
        proof.put("passed",passed);
      }
      cases.put("stale_"+kind,proof);
    }
    boolean passed = cases.values().stream().allMatch(v -> Boolean.TRUE.equals(((Map<?,?>)v).get("passed")));
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("status",passed ? "PASS_REAL_MYSQL_TASK_TRANSITION_FENCING" : "FAIL_REPRODUCED_TASK_TRANSITION_FENCING");
    result.put("cases",cases);
    result.put("boundary","Synthetic queue fixtures in a dedicated schema; real JDBC and transactions. No model, knowledge publication, user upload, multi-instance lease or full application acceptance claimed.");
    return result;
  }

  public static void main(String[] args) throws Exception {
    Map<String,Object> result = new GenerationTaskFenceProbe().run();
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(args[0]),result);
    if (!String.valueOf(result.get("status")).startsWith("PASS")) System.exit(1);
  }
}
