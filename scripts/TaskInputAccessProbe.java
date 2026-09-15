import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcEnterpriseOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Native task metadata authority; isolated fixtures, no production task transitions. */
public class TaskInputAccessProbe {
  static final Map<String, Boolean> checks = new LinkedHashMap<>();
  static final String PRIVATE_DETAIL = "Uncited classified component input";
  static void check(String name, boolean pass) {
    checks.put(name, pass);
    if (!pass) throw new AssertionError(name);
  }
  static EnterpriseOperations.PageQuery page(String query) {
    return new EnterpriseOperations.PageQuery(1, 1, query, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null);
  }
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var report = new LinkedHashMap<String, Object>();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
          "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var reads = new JdbcEnterpriseOperations(jdbc,
          new TransactionTemplate(new DataSourceTransactionManager(ds)), mapper);
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,idempotency_key,status,execution_epoch,error_message) VALUES('uncited-failed','version-public','WIKI','uncited-failed','FAILED',1,?)", PRIVATE_DETAIL);
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,idempotency_key,status,execution_epoch,error_message,next_attempt_at) VALUES('uncited-retry','version-public','WIKI','uncited-retry','RETRY_WAIT',1,?,clock_timestamp()+interval '60 seconds')", PRIVATE_DETAIL);
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,idempotency_key,status,execution_epoch,owner_id,stage_deadline) VALUES('uncited-running','version-public','GRAPH','uncited-running','RUNNING',1,'component-owner',clock_timestamp()+interval '600 seconds')");
      jdbc.update("INSERT INTO compile_task_input(task_id,execution_epoch,source_version_id,acl_version) SELECT t.task_id,1,a.version_id,a.acl_version FROM compile_task t CROSS JOIN kb_source_authority a WHERE a.version_id IN ('version-public','version-secret')");
      check("uncitedPrivateInputPreventsTaskSearchAndErrorLeakBeforePagination",
          reads.tasks(page(PRIVATE_DETAIL), "component-user", true, "COMPILE", 10).total()==0
          && reads.tasks(page(null), "component-user", true, "COMPILE", 10).items().isEmpty());
      check("fullClearanceReadsActualErrorAndCorrectCount",
          reads.tasks(page(PRIVATE_DETAIL), "component-user", true, "COMPILE", 30).total()==2
          && reads.tasks(page(PRIVATE_DETAIL), "component-user", true, "COMPILE", 30).items().get(0).errorMessage().equals(PRIVATE_DETAIL)
          && reads.tasks(page(null), "component-user", true, "COMPILE", 30).total()==3);
      check("dashboardTaskCountsExcludeUncitedPrivateInputs",
          reads.overview(10).failedTasks()==0 && reads.overview(10).runningTasks()==0 && reads.overview(10).pendingTasks()==0
          && reads.overview(30).failedTasks()==1 && reads.overview(30).runningTasks()==1 && reads.overview(30).pendingTasks()==1);
      check("documentKnowledgeTaskCountsUseCompleteInputs",
          reads.documentDetail("version-public",20,10).orElseThrow().knowledge().failedTasks()==0
          && reads.documentDetail("version-public",20,10).orElseThrow().knowledge().runningTasks()==0
          && reads.documentDetail("version-public",20,10).orElseThrow().knowledge().pendingTasks()==0
          && reads.documentDetail("version-public",20,30).orElseThrow().knowledge().failedTasks()==1
          && reads.documentDetail("version-public",20,30).orElseThrow().knowledge().runningTasks()==1
          && reads.documentDetail("version-public",20,30).orElseThrow().knowledge().pendingTasks()==1);
      check("qualityTaskCountAndErrorDetailsUseCompleteInputs",
          reads.quality(30,10).failedGenerationTasks()==0
          && !mapper.writeValueAsString(reads.quality(30,10)).contains(PRIVATE_DETAIL)
          && reads.quality(30,30).failedGenerationTasks()==1
          && mapper.writeValueAsString(reads.quality(30,30)).contains(PRIVATE_DETAIL));
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,idempotency_key,status,execution_epoch,error_message) VALUES('public-single','version-public','SUMMARY','public-single','FAILED',1,'Public component failure'),('public-queue','version-public','WIKI','public-queue','PENDING',0,NULL),('public-new-attempt','version-public','WIKI','public-new-attempt','FAILED',2,'Public current attempt failure')");
      jdbc.update("INSERT INTO compile_task_input(task_id,execution_epoch,source_version_id,acl_version) VALUES('public-single',1,'version-public',1),('public-new-attempt',1,'version-secret',1),('public-new-attempt',2,'version-public',1)");
      var publicPage=reads.tasks(page(null),"component-user",true,"COMPILE",10);
      check("singleSourceAndUncapturedQueueRemainVisibleBeforeLimit",
          publicPage.total()==3 && publicPage.items().size()==1
          && reads.overview(10).pendingTasks()==1 && reads.overview(10).failedTasks()==2);
      check("oldAttemptPrivateInputsCannotHidePublicCurrentAttempt",
          reads.tasks(page("public-new-attempt"),"component-user",true,"COMPILE",10).total()==1);
      check("nonAdminTaskListRetainsExistingIngestionOwnershipBoundary",
          reads.tasks(page(null),"component-user",false,"COMPILE",127).total()==0);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-secret'");
      check("capturedUncitedAclRevocationImmediatelyHidesTasksEvenAtHighClearance",
          reads.tasks(page(PRIVATE_DETAIL),"component-user",true,"COMPILE",127).total()==0
          && reads.tasks(page(null),"component-user",true,"COMPILE",127).total()==3
          && reads.overview(127).runningTasks()==0
          && reads.quality(30,127).failedGenerationTasks()==2);
      check("authorityReadsDoNotRewriteTaskHistoryOrCapturedInputs",
          jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id='uncited-failed' AND status='FAILED' AND error_message=?",Integer.class,PRIVATE_DETAIL)==1
          && jdbc.queryForObject("SELECT count(*) FROM compile_task_input WHERE task_id='uncited-failed' AND execution_epoch=1",Integer.class)==2);
      report.put("status","PASS_REAL_POSTGRES_TASK_INPUT_AUTHORITY");
    } catch (Throwable failure) {
      failure.printStackTrace();
      report.put("status","FAIL_REAL_POSTGRES_TASK_INPUT_AUTHORITY");
      report.put("failureType",failure.getClass().getName());
      report.put("failure",failure.getMessage());
    }
    report.put("checks",checks);
    report.put("boundary","Actual read repositories against isolated synthetic PostgreSQL task/input fixtures. No user task publication, model, UI or deployment acceptance claim.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if (report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
}
