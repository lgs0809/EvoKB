import cn.lgs.knowledgebase.application.query.QueryEvidenceContext;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcQuerySessionRepository;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic component fixtures in a fresh retained schema; never an answered model session. */
public final class QueryContextReceiptProbe {
  static final Map<String,Boolean> checks = new LinkedHashMap<>();
  static void check(String name, boolean value) {
    checks.put(name,value); if (!value) throw new AssertionError(name);
  }
  static boolean rejected(Runnable action) {
    try { action.run(); return false; } catch (RuntimeException expected) { return true; }
  }
  static QueryEvidenceContext.Snapshot snapshot(String hash, String second, String secondDoc,
      String secondVersion, int tokens) {
    return new QueryEvidenceContext.Snapshot("raw-chunk-context-v1",hash,"synthetic-component-counter",tokens,24000,100,
        List.of(new QueryEvidenceContext.Passage("chunk:public","RAW_CHUNK",List.of("evidence-public")),
            new QueryEvidenceContext.Passage("chunk:second","RAW_CHUNK",List.of(second))),
        List.of(new QueryEvidenceContext.SourceBinding(1,"evidence-public","doc-public","version-public","component-only"),
            new QueryEvidenceContext.SourceBinding(2,second,secondDoc,secondVersion,"component-only")));
  }
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var report = new LinkedHashMap<String,Object>();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(20);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var repo = new JdbcQuerySessionRepository(jdbc,tx,mapper);
      jdbc.update("INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role) VALUES('context-owner','context-owner','Synthetic context owner','unused-component-hash','USER')");
      repo.createSession(new QuerySession("context-session","context-owner","Context component","Synthetic context recording",
          new SearchFilters(null,null,null,List.of()),SessionStatus.RUNNING,"",false,"",List.of(),List.of(),Instant.now(),Instant.now()));
      var reserved = repo.reserveRound("context-owner","context-session",List.of("Synthetic query"),8,12).orElseThrow();
      repo.addEvidence(new QueryEvidenceRef("context-session","evidence-public",1,"Synthetic query"));
      repo.addEvidence(new QueryEvidenceRef("context-session","evidence-other",1,"Synthetic query"));
      String hash = KnowledgeDomain.sha256("Synthetic full rendered context");
      var selected = snapshot(hash,"evidence-other","doc-other","version-other",42);
      repo.recordEvidenceSelection("context-owner",reserved,selected);
      var row = jdbc.queryForMap("SELECT * FROM audit_event WHERE action='QUERY_CONTEXT_SELECTED'");
      String details = jdbc.queryForObject("SELECT details_json::text FROM audit_event WHERE action='QUERY_CONTEXT_SELECTED'",String.class);
      check("actualOwnedReservationAndActorRecorded",row.get("actor_user_id").equals("context-owner")
          && row.get("target_type").equals("query_session") && row.get("target_id").equals("context-session")
          && row.get("request_id").equals(reserved.reservationId()));
      check("exactTypedSnapshotRoundTripsThroughJsonb",mapper.readValue(details,QueryEvidenceContext.Snapshot.class).equals(selected));
      check("receiptContainsNoSourceBodyPromptOrAnswer",!details.contains("Synthetic fixture source")
          && !details.contains("Synthetic query") && !details.contains("Synthetic full rendered context"));
      check("recordingContextDoesNotCompleteTheQuestion",repo.findSession("context-session").orElseThrow().status()==SessionStatus.RUNNING
          && repo.listRounds("context-session").isEmpty());

      var workers = Executors.newFixedThreadPool(6);
      try {
        var jobs = new ArrayList<Future<?>>(); var gate = new CountDownLatch(1);
        for (int i=0;i<12;i++) jobs.add(workers.submit(()->{gate.await();repo.recordEvidenceSelection("context-owner",reserved,selected);return null;}));
        gate.countDown(); for (var job:jobs) job.get(30,TimeUnit.SECONDS);
      } finally { workers.shutdownNow(); }
      check("twelveConcurrentIdenticalReceiptsProduceOneRow",jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='QUERY_CONTEXT_SELECTED'",Integer.class)==1);
      check("changedContextCannotReplaceOriginalReceipt",rejected(()->repo.recordEvidenceSelection("context-owner",reserved,
          snapshot(KnowledgeDomain.sha256("changed"),"evidence-other","doc-other","version-other",42)))
          && details.equals(jdbc.queryForObject("SELECT details_json::text FROM audit_event WHERE action='QUERY_CONTEXT_SELECTED'",String.class)));
      check("foreignUserCannotRecordOwnedContext",rejected(()->repo.recordEvidenceSelection("foreign-user",reserved,selected)));
      var wrongRound = new RetrievalReservation(reserved.reservationId(),reserved.sessionId(),2,reserved.inputQueries(),reserved.startedAt());
      check("reservationCannotBeRelabelledAsAnotherRound",rejected(()->repo.recordEvidenceSelection("context-owner",wrongRound,selected)));
      check("unretrievedButExistingEvidenceCannotEnterReceipt",rejected(()->repo.recordEvidenceSelection("context-owner",reserved,
          snapshot(hash,"evidence-secret","doc-secret","version-secret",42))));
      check("sourceCannotBorrowAnotherDocumentIdentity",rejected(()->repo.recordEvidenceSelection("context-owner",reserved,
          snapshot(hash,"evidence-other","doc-public","version-other",42))));
      check("bodyTokenLimitAppliesBeforeAnyWrite",rejected(()->repo.recordEvidenceSelection("context-owner",reserved,
          snapshot(hash,"evidence-other","doc-other","version-other",6001))));
      check("allRejectedReceiptsLeaveOneOriginalUnchanged",jdbc.queryForObject("SELECT count(*) FROM audit_event",Integer.class)==1
          && mapper.readValue(details,QueryEvidenceContext.Snapshot.class).equals(selected));
      report.put("status","PASS_REAL_POSTGRES_QUERY_CONTEXT_RECEIPT"); report.put("receipt",row);
    } catch (Throwable failure) {
      report.put("status","FAIL_REAL_POSTGRES_QUERY_CONTEXT_RECEIPT"); report.put("failure",failure.toString());
    }
    report.put("checks",checks);
    report.put("boundary","Fresh retained PostgreSQL component schema, real current adapter and 12 concurrent writes. Synthetic owners/Raw fixtures and RUNNING question only; no model, production question, successful answer, publication, download or approval manufactured.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
}
