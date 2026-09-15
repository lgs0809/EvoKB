import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.ModelOutputRejectedException;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact native captured-input authority; synthetic retained schema, no model/publication. */
public final class CompilationBudgetPostgresProbe {
  private static final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();

  private static void check(String name, boolean result) {
    checks.put(name, result);
    if (!result) throw new AssertionError(name);
  }

  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String, Object>();
    var mapper = new ObjectMapper().findAndRegisterModules();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
          "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var tasks = new JdbcGenerationTaskRepository(jdbc,
          new TransactionTemplate(new DataSourceTransactionManager(ds)), "component-budget");
      tasks.queue("version-public", GenerationTaskType.SUMMARY, "", Instant.now());
      var task = tasks.claimPending(1, Instant.now()).get(0);
      var gate = tasks.publication(task);
      boolean missingInputsRejected = false;
      try (var scope = CompilationExecutionBudget.open(task.stageDeadline(), gate, Clock.systemUTC())) {
        check("openingAnActualClaimDoesNotCheckItsUncapturedInputs", true);
        try { CompilationExecutionBudget.limit(Duration.ofSeconds(120)); }
        catch (IllegalStateException failure) { missingInputsRejected = true; }
        check("anExternalCallCannotProceedBeforeCapturingNativeInputs", missingInputsRejected
            && jdbc.queryForObject("SELECT count(*) FROM compile_task_input WHERE task_id=?",
                Integer.class, task.taskId()) == 0);
        gate.captureInputs(List.of("version-public"));
        var budget = CompilationExecutionBudget.limit(Duration.ofSeconds(120));
        check("normalCaptureThenRequestBudgetUsesTheOriginalClaim", budget.equals(Duration.ofSeconds(120))
            && jdbc.queryForObject("SELECT count(*) FROM compile_task_input WHERE task_id=? AND execution_epoch=?",
                Integer.class, task.taskId(), task.executionEpoch()) == 1);
        check("nativeAuthorityCheckEndsItsTransactionBeforeExternalIo",
            !TransactionSynchronizationManager.isActualTransactionActive());
        jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
        RuntimeException revoked = null;
        try { CompilationExecutionBudget.limit(Duration.ofSeconds(120)); }
        catch (RuntimeException failure) { revoked = failure; }
        check("revokedCapturedNativeInputIsTerminalBeforeAnyExternalCall", revoked != null
            && !(revoked instanceof CompilationExecutionBudget.StageDeadlineExpired)
            && !(revoked instanceof ModelOutputRejectedException));
      }
      check("scopeCloseDoesNotLeakIntoTheNextCaller",
          CompilationExecutionBudget.limit(Duration.ofSeconds(120)).equals(Duration.ofSeconds(120)));
      check("theProbeNeverCompletesOrRewritesTaskFailureHistory",
          jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='RUNNING' AND attempt=1",
              Integer.class, task.taskId()) == 1
          && jdbc.queryForObject("SELECT count(*) FROM compile_task_failure", Integer.class) == 0
          && jdbc.queryForObject("SELECT count(*) FROM summary", Integer.class) == 0);
      report.put("taskId", task.taskId());
      report.put("originalStageDeadline", task.stageDeadline());
      report.put("executionEpoch", task.executionEpoch());
      report.put("status", "PASS_REAL_POSTGRES_COMPILATION_BUDGET_AUTHORITY");
    } catch (Throwable failure) {
      failure.printStackTrace();
      report.put("status", "FAIL_REAL_POSTGRES_COMPILATION_BUDGET_AUTHORITY");
      report.put("failureType", failure.getClass().getName());
      report.put("failure", failure.getMessage());
    }
    report.put("checks", checks);
    report.put("boundary", "Actual native PostgreSQL claim/publication/input fence in an isolated retained synthetic schema. No model, HTTP, task completion, physical artifact or production transition; does not prove all I/O is cancellable.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    if (report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
}
