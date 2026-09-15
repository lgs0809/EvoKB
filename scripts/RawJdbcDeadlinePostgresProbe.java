import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import cn.lgs.knowledgebase.application.raw.RawExecutionBudget;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real row-lock cancellation under a normal native Raw claim in an isolated retained schema. */
public final class RawJdbcDeadlinePostgresProbe {
  static final Map<String, Boolean> checks = new LinkedHashMap<>();
  static final List<Map<String, Object>> observations = new ArrayList<>();
  static void check(String name, boolean result) {
    checks.put(name, result);
    if (!result) throw new AssertionError(name);
  }

  static void blocked(DriverManagerDataSource ds, String file, String operation, Runnable action)
      throws Exception {
    try (var connection = ds.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("SELECT file_id FROM ingestion_file WHERE file_id=? FOR UPDATE")) {
        statement.setString(1, file);
        statement.executeQuery().close();
      }
      RuntimeException failure = null;
      long started = System.nanoTime();
      try (var budget = RawExecutionBudget.open(Instant.now().plusMillis(500), () -> {}, Clock.systemUTC())) {
        try { action.run(); } catch (RuntimeException expected) { failure = expected; }
      } finally { connection.rollback(); }
      long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
      String sqlState = "";
      for (Throwable cause = failure; cause != null; cause = cause.getCause())
        if (cause instanceof java.sql.SQLException sql) sqlState = sql.getSQLState();
      observations.add(Map.of("operation", operation, "elapsedMillis", elapsed,
          "sqlState", sqlState, "observationBudgetMillis", 500,
          "failureType", failure == null ? "" : failure.getClass().getName()));
      check(operation + "UsesNativeOneSecondCancellationBeforeServerFallback",
          failure != null && "57014".equals(sqlState) && elapsed >= 500 && elapsed < 2500);
    }
  }

  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String, Object>();
    var mapper = new ObjectMapper().findAndRegisterModules();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var properties = new Properties(); properties.setProperty("options", "-c statement_timeout=4000");
      ds.setConnectionProperties(properties);
      var jdbc = new JdbcTemplate(ds);
      var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var repository = new JdbcIngestionRepository(jdbc, transactions, mapper);
      String batch = UUID.randomUUID().toString(), file = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'synthetic-jdbc-deadline',1)", batch);
      jdbc.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'fixture.md','fixture.md','fixture-only','fixture-only','fixture-only')", file, batch);
      repository.queueBatch(batch, Instant.now());
      var claim = repository.claimRaw(file, UUID.randomUUID().toString()).orElseThrow();
      var execution = repository.rawExecution(claim);
      var called = new AtomicBoolean();
      blocked(ds, file, "fenced", () -> execution.fenced(() -> called.set(true)));
      blocked(ds, file, "reserveSource", () -> execution.reserveSource("synthetic-jdbc-source", () -> { called.set(true); return true; }));
      blocked(ds, file, "verifyOriginal", () -> repository.rawVerifyOriginal(claim,
          new SourceFile("fixture-only", "fixture.md", 0, "fixture-only", "text/markdown")));
      blocked(ds, file, "rawComplete", () -> repository.rawComplete(claim, "not-a-completed-document", "not-a-published-version"));
      check("blockedTransactionsNeverRunBusinessWrites", !called.get());
      try (var scope = RawExecutionBudget.open(claim, execution, Clock.systemUTC())) {
        execution.check();
        execution.phase(ProgressStage.PARSING);
        Instant parsing = execution.stageDeadline();
        execution.phase(ProgressStage.EVIDENCE);
        check("samePhaseDoesNotRenewTheCurrentDeadline", parsing.equals(execution.stageDeadline()));
        execution.phase(ProgressStage.EMBEDDING);
        Instant embedding = execution.stageDeadline();
        check("nextPhaseUsesItsActualDatabaseDeadline", embedding.isAfter(parsing)
            && RawExecutionBudget.remaining(Duration.ofMinutes(10)).toMillis() > 590000);
        check("originalClaimDeadlineWasNotRewritten", claim.deadline().isBefore(embedding));
      }
      check("sharedTransactionDefinitionRemainsUnmodified", transactions.getTimeout() == -1);
      check("failedObservationsDoNotManufactureSuccessOrAnotherAttempt",
          jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='RUNNING' AND attempt=1 AND execution_epoch=1", Integer.class, file) == 1
          && jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_failure", Integer.class) == 0
          && jdbc.queryForObject("SELECT count(*) FROM raw_publication", Integer.class) == 0);
      report.put("taskId", file); report.put("originalClaimDeadline", claim.deadline());
      report.put("actualCurrentPhaseDeadline", execution.stageDeadline());
      report.put("status", "PASS_REAL_POSTGRES_RAW_JDBC_TIMEOUT");
    } catch (Throwable failure) {
      failure.printStackTrace(); report.put("status", "FAIL_REAL_POSTGRES_RAW_JDBC_TIMEOUT");
      report.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
    }
    report.put("checks", checks); report.put("observations", observations);
    report.put("boundary", "Native frozen-artifact PostgreSQL transactions, normal Raw queue/claim and 500ms nested observation. JDBC rounds up to one second; actual 600-second per-phase claim is unchanged. No model, parser, completed workflow, public business mutation, pool-exhaustion or network-partition claim.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    if (report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
}
