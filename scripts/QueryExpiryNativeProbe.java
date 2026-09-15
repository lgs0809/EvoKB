import cn.lgs.knowledgebase.application.query.QueryStateChanged;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcQuerySessionRepository;
import cn.lgs.knowledgebase.infrastructure.query.ExpiredQueryRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual PostgreSQL component/race proof in a new isolated fixture schema, without inference. */
public class QueryExpiryNativeProbe {
  static QuerySession session(String id, SessionStatus status, Instant time) {
    return new QuerySession(id, "expiry-fixture-owner", "expiry fixture", "retained fixture question",
        new SearchFilters(null,null,null,List.of()), status, "", false, "", List.of(), List.of(), time, time);
  }
  public static void main(String[] args) throws Exception {
    String jdbcUrl=System.getenv("PROBE_JDBC_URL"), password=System.getenv("PROBE_DB_PASSWORD");
    String schema="query_expiry_probe_"+UUID.randomUUID().toString().replace("-", "");
    var admin=new JdbcTemplate(new DriverManagerDataSource(jdbcUrl,"evokb_target",password));
    admin.execute("CREATE SCHEMA "+schema);
    for (String table:List.of("query_session","query_turn","query_retrieval_reservation"))
      admin.execute("CREATE TABLE "+schema+"."+table+" (LIKE public."+table+" INCLUDING ALL)");
    var ds=new DriverManagerDataSource(jdbcUrl+(jdbcUrl.contains("?")?"&":"?")+"currentSchema="+schema,"evokb_target",password);
    var jdbc=new JdbcTemplate(ds);
    var repo=new JdbcQuerySessionRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),new ObjectMapper());
    Instant now=Instant.now(), old=now.minus(Duration.ofHours(3));
    var stale=session("expired",SessionStatus.RUNNING,old);
    repo.createSession(stale);
    repo.reserveRound(stale.userId(),stale.sessionId(),List.of("retained actual reservation"),8,8);
    repo.appendTurn(new QueryTurn(UUID.randomUUID().toString(),stale.sessionId(),TurnRole.USER,stale.originalQuestion(),old));
    repo.createSession(session("fresh",SessionStatus.RUNNING,now.minusSeconds(60)));
    repo.createSession(session("waiting",SessionStatus.WAITING_USER,old));
    repo.createSession(session("terminal",SessionStatus.ANSWERED,old));
    repo.createSession(session("deleted",SessionStatus.RUNNING,old));
    jdbc.update("UPDATE query_session SET deleted_at=? WHERE session_id='deleted'", java.sql.Timestamp.from(now));
    var recovery=new ExpiredQueryRecovery(repo,Clock.systemUTC(),Duration.ofHours(2));
    recovery.reconcile();
    var checks=new LinkedHashMap<String,Boolean>();
    var expired=repo.findSession("expired").orElseThrow();
    checks.put("staleRunningBecomesOperationalFailure",expired.status()==SessionStatus.FAILED && expired.evidenceOnly() && expired.finalAnswer().contains("不能据此认定"));
    checks.put("originalQuestionAndConsumedReservationPreserved",expired.originalQuestion().equals(stale.originalQuestion()) && repo.listReservations("expired").size()==1);
    checks.put("freshWorkRemainsRunning",repo.findSession("fresh").orElseThrow().status()==SessionStatus.RUNNING);
    checks.put("waitingAndTerminalResultsRemainUntouched",repo.findSession("waiting").orElseThrow().status()==SessionStatus.WAITING_USER && repo.findSession("terminal").orElseThrow().status()==SessionStatus.ANSWERED);
    checks.put("deletedHistoryUntouched","RUNNING".equals(jdbc.queryForObject("SELECT status FROM query_session WHERE session_id='deleted'",String.class)));
    boolean rejected=false;
    try { repo.updateSession(stale.withStatus(SessionStatus.ANSWERED,"late component-fixture result",false,"",List.of(),List.of(),Instant.now())); }
    catch(QueryStateChanged expected){ rejected=true; }
    checks.put("lateAnswerCannotResurrectExpiredSession",rejected && repo.findSession("expired").orElseThrow().status()==SessionStatus.FAILED);
    recovery.reconcile();
    checks.put("expiryAuditIsAtomicAndIdempotent",repo.listTurns("expired").stream().filter(t->t.content().equals("QUERY_EXECUTION_EXPIRED")).count()==1 && repo.listTurns("expired").size()==2);
    var races=new ArrayList<Map<String,Object>>();
    ExecutorService executor=Executors.newFixedThreadPool(2);
    try {
      for(int i=0;i<20;i++){
        var run=session("race-"+i,SessionStatus.RUNNING,old);repo.createSession(run);
        var start=new CountDownLatch(1);
        Future<Boolean> completion=executor.submit(()->{start.await();try{repo.updateSession(run.withStatus(SessionStatus.ANSWERED,"component-fixture result",false,"",List.of(),List.of(),Instant.now()));return true;}catch(QueryStateChanged lost){return false;}});
        Future<Integer> expiry=executor.submit(()->{start.await();return repo.expireRunningSessions(Instant.now().minus(Duration.ofHours(2)),Instant.now(),"component expiry fixture; not a model answer");});
        start.countDown();boolean completed=completion.get(15,TimeUnit.SECONDS);expiry.get(15,TimeUnit.SECONDS);
        var actual=repo.findSession(run.sessionId()).orElseThrow();
        long audit=repo.listTurns(run.sessionId()).stream().filter(t->t.content().equals("QUERY_EXECUTION_EXPIRED")).count();
        boolean valid=(completed && actual.status()==SessionStatus.ANSWERED && audit==0)||(!completed && actual.status()==SessionStatus.FAILED && audit==1);
        races.add(Map.of("case",i,"completionWon",completed,"actualStatus",actual.status().name(),"expiryAuditCount",audit,"consistent",valid));
      }
    } finally {executor.shutdownNow();}
    checks.put("twentyNativeCompletionExpiryRacesHaveOneDurableWinner",races.size()==20 && races.stream().allMatch(r->Boolean.TRUE.equals(r.get("consistent"))));
    var report=new LinkedHashMap<String,Object>();report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_NATIVE_QUERY_EXPIRY_COMPONENT_AND_RACES":"FAIL_NATIVE_QUERY_EXPIRY_COMPONENT_AND_RACES");
    report.put("schema",schema);report.put("actualOperationTime",Instant.now().toString());report.put("checks",checks);report.put("races",races);
    report.put("boundary","New isolated PostgreSQL fixture schema and production staged repository/recovery classes. Real row locks, transactions and state fences; no model calls, original public business rows changed or deleted, no claim of an actual successfully answered model task. Retain the schema for readback. Actual public host-interrupted queries require a separately verified deployment and normal scheduler observation.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(checks.values().stream().anyMatch(v->!v))System.exit(1);
  }
}
