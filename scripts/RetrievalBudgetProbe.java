import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcQuerySessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual compiled adapter + PostgreSQL. These are isolated component fixtures, not model results. */
public class RetrievalBudgetProbe {
  static final Map<String, Boolean> checks = new LinkedHashMap<>();
  static void check(String name, boolean value) {
    checks.put(name, value);
    if (!value) throw new AssertionError(name);
  }
  static QuerySession session(String id) {
    return new QuerySession(id, "budget-component-user", id, "检索预算组件验收",
        new SearchFilters(null,null,null,List.of()), SessionStatus.RUNNING, "", false, "",
        List.of(),List.of(),Instant.now(),Instant.now());
  }
  static RetrievalRound completed(RetrievalReservation r, Decision decision) {
    return new RetrievalRound(r.reservationId(),r.sessionId(),r.roundNo(),r.inputQueries(),decision,
        List.of("缺少付款资料"),List.of(),r.startedAt(),Instant.now());
  }
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var report = new LinkedHashMap<String,Object>();
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var repo = new JdbcQuerySessionRepository(jdbc,tx,mapper);
      if (args[0].equals("crash-after-reservation")) {
        repo.createSession(session("budget-crash"));
        var r = repo.reserveRound("budget-component-user","budget-crash",List.of("中断前线索"),8,8).orElseThrow();
        check("reservationCommittedBeforeAbruptProcessExit", r.roundNo()==1
            && jdbc.queryForObject("SELECT count(*) FROM query_retrieval_reservation WHERE session_id='budget-crash'",Integer.class)==1
            && repo.listRounds("budget-crash").isEmpty());
        report.put("status","PASS_BEFORE_INTENTIONAL_PROCESS_CRASH");
        report.put("checks",checks);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
        Runtime.getRuntime().halt(23);
      }
      check("uncleanExitDidNotRefundReservation",repo.listReservations("budget-crash").size()==1
          && repo.listRounds("budget-crash").isEmpty());
      var afterCrash=repo.reserveRound("budget-component-user","budget-crash",List.of("重启后线索"),8,8).orElseThrow();
      check("freshProcessContinuesAtNextOrdinal",afterCrash.roundNo()==2&&repo.listReservations("budget-crash").size()==2);

      check("upgradeBackfillsLegacyRoundAndTimestamp",repo.listReservations("budget-legacy").size()==1
          && repo.listReservations("budget-legacy").get(0).roundNo()==7
          && repo.listReservations("budget-legacy").get(0).startedAt().equals(repo.listRounds("budget-legacy").get(0).startedAt()));
      var upgraded=repo.reserveRound("budget-component-user","budget-legacy",List.of("最后线索"),999,99).orElseThrow();
      check("legacyOrdinalGapsDoNotResetBudget",upgraded.roundNo()==8);
      check("legacyGapCannotGrantNinthRound",repo.reserveRound("budget-component-user","budget-legacy",List.of("不能执行"),999,99).isEmpty());
      check("originalLegacyCompletionRemainsUnchanged",repo.listRounds("budget-legacy").size()==1
          && repo.listRounds("budget-legacy").get(0).decision()==Decision.CONTINUE_SEARCH);

      repo.createSession(session("budget-concurrent"));
      ExecutorService pool=Executors.newFixedThreadPool(8);
      List<Future<Optional<RetrievalReservation>>> futures=new ArrayList<>();
      CountDownLatch gate=new CountDownLatch(1);
      try {
        for(int i=0;i<32;i++) {
          String q="并发线索"+i;
          futures.add(pool.submit(()->{gate.await(); return repo.reserveRound("budget-component-user","budget-concurrent",List.of(q),999,999);}));
        }
        gate.countDown();
        List<RetrievalReservation> accepted=new ArrayList<>();
        for(var future:futures) future.get(60,TimeUnit.SECONDS).ifPresent(accepted::add);
        check("thirtyTwoConcurrentRequestsAllocateExactlyEight",accepted.size()==8);
        check("uniqueSequentialDurableOrdinals",new TreeSet<>(accepted.stream().map(RetrievalReservation::roundNo).toList())
            .equals(new TreeSet<>(List.of(1,2,3,4,5,6,7,8))));
        report.put("concurrentRequests",32);report.put("acceptedRounds",accepted.size());
      } finally {pool.shutdownNow();}
      var reloaded=new JdbcQuerySessionRepository(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),mapper);
      check("newRepositoryCannotReuseEightUnfinishedSlots",reloaded.reserveRound("budget-component-user","budget-concurrent",List.of("重复启动"),999,999).isEmpty()
          && reloaded.listRounds("budget-concurrent").isEmpty());

      repo.createSession(session("budget-query-cap"));
      var capped=repo.reserveRound("budget-component-user","budget-query-cap",List.of("A","B","C"),8,2).orElseThrow();
      check("queryCapAppliesBeforeDispatch",capped.inputQueries().equals(List.of("A","B"))
          &&repo.reserveRound("budget-component-user","budget-query-cap",List.of("D"),8,2).isEmpty());
      repo.createSession(session("budget-dedup"));
      var dedup=repo.reserveRound("budget-component-user","budget-dedup",List.of(" Same   Query ","same query","OTHER"),8,8).orElseThrow();
      check("normalizedQueriesDeduplicateInsideAtomicReservation",dedup.inputQueries().equals(List.of("Same   Query","OTHER"))
          &&repo.reserveRound("budget-component-user","budget-dedup",List.of("SAME QUERY","other"),8,8).isEmpty());
      boolean wrongUser=false;
      try{repo.reserveRound("foreign-user","budget-dedup",List.of("越权"),8,8);}catch(IllegalStateException expected){wrongUser=true;}
      check("foreignUserCannotConsumeOwnedBudget",wrongUser&&repo.listReservations("budget-dedup").size()==1);

      repo.createSession(session("budget-outer-rollback"));
      try { tx.executeWithoutResult(ignored->{
        repo.reserveRound("budget-component-user","budget-outer-rollback",List.of("已派发"),8,8).orElseThrow();
        throw new IllegalStateException("intentional outer rollback");
      }); }catch(IllegalStateException expected){}
      check("outerTransactionRollbackDoesNotRefundDispatch",repo.listReservations("budget-outer-rollback").size()==1);

      repo.createSession(session("budget-complete"));
      var r=repo.reserveRound("budget-component-user","budget-complete",List.of("已检索"),8,8).orElseThrow();
      boolean forged=false;
      try {repo.saveRound(new RetrievalRound(r.reservationId(),r.sessionId(),r.roundNo(),List.of("其他检索"),Decision.ANSWER,List.of(),List.of(),r.startedAt(),Instant.now()));}
      catch(IllegalStateException expected){forged=true;}
      check("completionCannotChangeReservedInputs",forged&&repo.listRounds(r.sessionId()).isEmpty());
      var done=completed(r,Decision.CONTINUE_SEARCH); repo.saveRound(done); repo.saveRound(done);
      check("identicalCompletionIsIdempotent",repo.listRounds(r.sessionId()).size()==1);
      boolean overwrite=false;
      try {repo.saveRound(completed(r,Decision.ANSWER));}catch(IllegalStateException expected){overwrite=true;}
      check("differentCompletionCannotOverwriteRound",overwrite&&repo.listRounds(r.sessionId()).get(0).decision()==Decision.CONTINUE_SEARCH);
      boolean unreserved=false;
      try {repo.saveRound(new RetrievalRound(UUID.randomUUID().toString(),r.sessionId(),2,List.of("没有占用"),Decision.ANSWER,List.of(),List.of(),Instant.now(),Instant.now()));}
      catch(IllegalStateException expected){unreserved=true;}
      check("completionRequiresItsOwnReservation",unreserved&&repo.listRounds(r.sessionId()).size()==1);
      repo.updateSession(repo.findSession("budget-complete").orElseThrow().withStatus(SessionStatus.INSUFFICIENT,"组件测试结束",true,"",List.of(),List.of(),Instant.now()));
      boolean terminal=false;
      try {repo.reserveRound("budget-component-user","budget-complete",List.of("终态再次启动"),8,8);}catch(IllegalStateException expected){terminal=true;}
      check("terminalSessionCannotAllocateMoreSlots",terminal&&repo.listReservations("budget-complete").size()==1);
      report.put("status","PASS_REAL_POSTGRES_DURABLE_RETRIEVAL_BUDGET");
    } catch(Throwable failure) {
      failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_DURABLE_RETRIEVAL_BUDGET");
      report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());
    }
    report.put("checks",checks);
    report.put("boundary","Compiled production repository against isolated PostgreSQL component fixtures. Includes an abrupt JVM exit and a second process. Does not certify automatic query restart recovery, real LLM coverage, or Stream task publication ownership.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
