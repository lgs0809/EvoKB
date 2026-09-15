import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.infrastructure.model.CompilationModelAudit;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Source-scoped model receipts in a retained synthetic schema; no model or successful artifact. */
public final class CompilationModelAuditPostgresProbe {
  static final Map<String, Boolean> checks = new LinkedHashMap<>();
  static void check(String name, boolean value) { checks.put(name,value); if(!value) throw new AssertionError(name); }
  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String,Object>();
    var mapper = new ObjectMapper().findAndRegisterModules();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var tasks = new JdbcGenerationTaskRepository(jdbc, tx, "audit-component");
      var ledger = new JdbcAuditAdapter(jdbc,mapper);
      var recorder = new JdbcCompilationModelAudit(jdbc,tx,ledger);
      var audit = new CompilationModelAudit(recorder);
      var reads = new JdbcEnterpriseOperations(jdbc,tx,mapper);
      tasks.queue("version-public",GenerationTaskType.SUMMARY,"",Instant.now());
      var first = tasks.claimPending(1,Instant.now()).get(0);
      var gate = tasks.publication(first);
      gate.captureInputs(List.of("version-public","version-secret"));
      String response = "{malformed synthetic response referencing both captured sources}";
      try(var scope=CompilationExecutionBudget.open(first,gate,Clock.systemUTC())) {
        audit.received(ModelRole.SMALL,ModelTask.SUMMARY_JUDGE,"gpt-5.6-luna",GroundingJudgeProtocol.Decision.class,response);
      }
      var firstRows=reads.audit(null,"component-reader",30);
      check("exactResponseTaskAndAttemptArePersistedInExistingLedger",firstRows.total()==1
          && firstRows.items().get(0).targetId().equals(first.taskId())
          && firstRows.items().get(0).details().get("responseText").equals(response)
          && ((Number)firstRows.items().get(0).details().get("executionEpoch")).longValue()==first.executionEpoch()
          && ((Number)firstRows.items().get(0).details().get("inputSourceCount")).intValue()==2);
      var low=reads.audit(null,"component-reader",10);
      check("aSecretSecondarySourceHidesReceiptBeforeCountAndPagination",low.total()==0&&low.items().isEmpty());
      Instant now=Instant.now();
      tasks.defer(first,"SYNTHETIC_PROTOCOL_FAILURE","Synthetic receipt-only fixture, no inference",now.plusSeconds(10),now);
      Thread.sleep(10_500);
      var second=tasks.claimPending(1,Instant.now()).get(0);
      var secondGate=tasks.publication(second);
      secondGate.captureInputs(List.of("version-public"));
      try(var scope=CompilationExecutionBudget.open(second,secondGate,Clock.systemUTC())) {
        audit.received(ModelRole.SMALL,ModelTask.SUMMARY_JUDGE,"gpt-5.6-luna",GroundingJudgeProtocol.Decision.class,"new public attempt response");
      }
      low=reads.audit(null,"component-reader",10);
      check("newPublicEpochCannotExposeOldSecretAttemptOutput",second.attempt()==2
          &&second.executionEpoch()!=first.executionEpoch()&&low.total()==1&&low.items().size()==1
          &&low.items().get(0).details().get("responseText").equals("new public attempt response"));
      check("highClearanceCanReadBothOriginalAttemptReceipts",reads.audit(null,"component-reader",30).total()==2);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-secret'");
      var high=reads.audit(null,"component-reader",30);
      check("revokedOldInputHidesOldReceiptEvenForHighClearance",high.total()==1
          &&high.items().get(0).details().get("responseText").equals("new public attempt response"));
      jdbc.update("DELETE FROM compile_task_input WHERE task_id=? AND execution_epoch=? AND source_version_id='version-secret'",first.taskId(),first.executionEpoch());
      check("incompleteOldSnapshotCannotTurnIntoAVisiblePublicReceipt",reads.audit(null,"component-reader",10).total()==1
          &&reads.audit(null,"component-reader",30).total()==1);
      var malformed=new AuditEvent("","synthetic","SYSTEM","COMPILATION_MODEL_OUTPUT","compile_task",second.taskId(),"","SUCCESS","","","",
          Map.of("executionEpoch","not-a-number","inputSourceCount",1,"responseText","must be hidden"),Instant.now());
      ledger.record(malformed);
      check("malformedOrUnboundReceiptMetadataFailsClosedWithoutSqlCastError",reads.audit(null,"component-reader",30).total()==1);
      boolean rejected=false;
      try {recorder.record(new AuditEvent("","synthetic","SYSTEM","COMPILATION_MODEL_OUTPUT","compile_task","missing-task","","SUCCESS","","","",
          Map.of("taskId","missing-task","executionEpoch",1),Instant.now()));}
      catch(IllegalStateException expected){rejected=true;}
      check("unclaimedSourcesCannotCreateModelReceipts",rejected
          &&jdbc.queryForObject("SELECT count(*) FROM audit_event",Integer.class)==3);
      check("theProbeDoesNotManufactureTaskOrArtifactSuccess",jdbc.queryForObject("SELECT count(*) FROM summary",Integer.class)==0
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE status='SUCCEEDED'",Integer.class)==0
          &&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE outcome='RETRY_WAIT'",Integer.class)==1);
      report.put("taskId",first.taskId()); report.put("originalEpoch",first.executionEpoch()); report.put("newEpoch",second.executionEpoch());
      report.put("status","PASS_REAL_POSTGRES_SOURCE_SCOPED_MODEL_AUDIT");
    } catch(Throwable failure) {
      failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_SOURCE_SCOPED_MODEL_AUDIT");
      report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());
    }
    report.put("checks",checks);
    report.put("boundary","Actual native claim, captured inputs, audit storage and pre-count ACL reads in a retained isolated schema. Explicit synthetic response text and a normal ten-second deferred second attempt. No real model, successful compilation, publication, production mutation or recovery of the lost historical outputs.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
