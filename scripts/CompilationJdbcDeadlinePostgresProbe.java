import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL lock waits, using a shortened observation scope, never changing a task lease. */
public final class CompilationJdbcDeadlinePostgresProbe {
  private static final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
  private static final List<Object> observations = new ArrayList<>();

  private static void check(String name, boolean value) {
    checks.put(name, value);
    if (!value) throw new AssertionError(name);
  }

  private static void blocked(
      DriverManagerDataSource ds, CompilationPublication gate, String operation, Runnable action)
      throws Exception {
    try (var lock = ds.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.executeQuery(
            "SELECT version_id FROM document_metadata WHERE version_id='version-public' FOR UPDATE");
      }
      RuntimeException failure = null;
      long started = System.nanoTime();
      try (var ignored = CompilationExecutionBudget.open(
          Instant.now().plusMillis(500), gate, Clock.systemUTC())) {
        try {
          action.run();
        } catch (RuntimeException expected) {
          failure = expected;
        }
      } finally {
        lock.rollback();
      }
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
      String sqlState = "";
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.sql.SQLException sql) sqlState = sql.getSQLState();
      }
      var observation = new LinkedHashMap<String, Object>();
      observation.put("operation", operation);
      observation.put("observationBudgetMillis", 500);
      observation.put("elapsedMillis", elapsedMillis);
      observation.put("sqlState", sqlState);
      observation.put("failureType", failure == null ? "" : failure.getClass().getName());
      observations.add(observation);
      // PostgreSQL's four-second fallback prevents a bad client from hanging this probe.
      // The application must cancel earlier with Spring's one-second JDBC granularity.
      check(operation + "CancelsBeforeServerFallback", failure != null
          && "57014".equals(sqlState) && elapsedMillis >= 500 && elapsedMillis < 2500);
    }
  }

  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String, Object>();
    var mapper = new ObjectMapper().findAndRegisterModules();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
          "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var properties = new Properties();
      properties.setProperty("options", "-c statement_timeout=4000");
      ds.setConnectionProperties(properties);
      var jdbc = new JdbcTemplate(ds);
      var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var tasks = new JdbcGenerationTaskRepository(jdbc, transactions, "component-jdbc-budget");
      tasks.queue("version-public", GenerationTaskType.SUMMARY, "", Instant.now());
      var task = tasks.claimPending(1, Instant.now()).get(0);
      var gate = tasks.publication(task);
      var writesCalled = new AtomicBoolean();
      try (var scope = CompilationExecutionBudget.open(task.stageDeadline(), gate, Clock.systemUTC())) {
        blocked(ds, gate, "captureInputs", () -> gate.captureInputs(List.of("version-public")));
        check("captureTimeoutDoesNotPersistPartialInputSnapshot",
            jdbc.queryForObject("SELECT count(*) FROM compile_task_input WHERE task_id=?",
                Integer.class, task.taskId()) == 0);
        gate.captureInputs(List.of("version-public"));
        blocked(ds, gate, "check", gate::check);
        blocked(ds, gate, "publish", () -> gate.publish(() -> writesCalled.set(true)));
        check("blockedPublicationNeverRunsWrites", !writesCalled.get());
        gate.check();
        check("releasedLocksAllowNormalAuthorityCheck", true);
      }
      check("perCallTimeoutNeverMutatesSharedTransactionDefinition", transactions.getTimeout() == -1);
      check("stageLeaseAttemptAndFailureHistoryRemainUnchanged",
          jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RUNNING' "
                  + "AND attempt=1 AND stage_deadline=? AND execution_epoch=?",
              Integer.class, task.taskId(), java.sql.Timestamp.from(task.stageDeadline()),
              task.executionEpoch()) == 1
              && jdbc.queryForObject("SELECT count(*) FROM compile_task_failure", Integer.class) == 0
              && jdbc.queryForObject("SELECT count(*) FROM summary", Integer.class) == 0);
      report.put("taskId", task.taskId());
      report.put("originalStageDeadline", task.stageDeadline());
      report.put("executionEpoch", task.executionEpoch());
      report.put("status", "PASS_REAL_POSTGRES_COMPILATION_JDBC_TIMEOUT");
    } catch (Throwable failure) {
      failure.printStackTrace();
      report.put("status", "FAIL_REAL_POSTGRES_COMPILATION_JDBC_TIMEOUT");
      report.put("failureType", failure.getClass().getName());
      report.put("failure", failure.getMessage());
    }
    report.put("checks", checks);
    report.put("observations", observations);
    report.put("boundary", "Actual native claimed task in an isolated retained PostgreSQL schema. "
        + "A nested 500ms observation scope tests one-second JDBC cancellation under an unchanged "
        + "600-second task lease. No model, artifact publication or completed workflow. "
        + "Pool acquisition, network partition and inherited outer transactions are not covered.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    if (report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
}
