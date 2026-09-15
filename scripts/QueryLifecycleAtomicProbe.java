import cn.lgs.knowledgebase.application.query.QueryStateChanged;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcQuerySessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL failures and concurrent lifecycle writes in the harness's retained isolated schema. */
public final class QueryLifecycleAtomicProbe {
  static QuerySession session(String id, String owner) {
    Instant now=Instant.now();
    return new QuerySession(id,owner,"atomic lifecycle fixture","synthetic component question",
        new SearchFilters(null,null,null,List.of()),SessionStatus.RUNNING,"",false,"",List.of(),List.of(),now,now);
  }
  static QueryTurn turn(QuerySession session,TurnRole role,String content) {
    return new QueryTurn(UUID.randomUUID().toString(),session.sessionId(),role,content,Instant.now());
  }
  static boolean markedWriteFails(Runnable action) {
    try {action.run();return false;}
    catch(DataAccessException failure) {
      for(Throwable cause=failure;cause!=null;cause=cause.getCause())
        if(cause instanceof SQLException sql && "P0001".equals(sql.getSQLState()))return true;
      throw failure;
    }
  }
  public static void main(String[] args)throws Exception {
    var dataSource=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    var jdbc=new JdbcTemplate(dataSource);
    String schema=jdbc.queryForObject("SELECT current_schema()",String.class);
    if(!schema.equals(args[1])||!schema.startsWith("evokb_query_lifecycle_"))throw new IllegalStateException("isolated lifecycle schema required");
    var repo=new JdbcQuerySessionRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(dataSource)),new ObjectMapper());
    jdbc.execute("CREATE FUNCTION reject_fault_turn() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.content='FAIL_TURN_WRITE' THEN RAISE EXCEPTION 'synthetic message write failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER query_lifecycle_fault BEFORE INSERT ON query_turn FOR EACH ROW EXECUTE FUNCTION reject_fault_turn()");
    var checks=new LinkedHashMap<String,Boolean>();
    var gaps=new LinkedHashMap<String,Boolean>();
    var legacyOpen=session("legacy-open","owner");
    repo.createSession(legacyOpen);
    boolean legacyFailure=markedWriteFails(()->repo.appendTurn(turn(legacyOpen,TurnRole.USER,"FAIL_TURN_WRITE")));
    gaps.put("separateCreationAndMessageCanLeaveAnOrphan",legacyFailure&&repo.findSession(legacyOpen.sessionId()).isPresent()&&repo.listTurns(legacyOpen.sessionId()).isEmpty());

    var atomicOpen=session("atomic-open-failure","owner");
    boolean openFailure=markedWriteFails(()->repo.createSessionWithTurn(atomicOpen,turn(atomicOpen,TurnRole.USER,"FAIL_TURN_WRITE")));
    checks.put("failedInitialMessageRollsBackSessionCreation",openFailure&&repo.findSession(atomicOpen.sessionId()).isEmpty());
    var legacyTerminal=session("legacy-terminal","owner");
    repo.createSessionWithTurn(legacyTerminal,turn(legacyTerminal,TurnRole.USER,"component question"));
    repo.updateSession(legacyTerminal.withStatus(SessionStatus.ANSWERED,"component result",false,"",List.of(),List.of(),Instant.now()));
    boolean legacyTerminalFailure=markedWriteFails(()->repo.appendTurn(turn(legacyTerminal,TurnRole.ASSISTANT,"FAIL_TURN_WRITE")));
    gaps.put("separateCompletionAndMessageCanLeaveAnUnrecordedTerminalResult",legacyTerminalFailure&&repo.findSession(legacyTerminal.sessionId()).orElseThrow().status()==SessionStatus.ANSWERED&&repo.listTurns(legacyTerminal.sessionId()).size()==1);

    var atomicTerminal=session("atomic-terminal-failure","owner");
    repo.createSessionWithTurn(atomicTerminal,turn(atomicTerminal,TurnRole.USER,"component question"));
    boolean completionFailure=markedWriteFails(()->repo.transitionSessionWithTurn(atomicTerminal.withStatus(SessionStatus.ANSWERED,"component result",false,"",List.of(),List.of(),Instant.now()),turn(atomicTerminal,TurnRole.ASSISTANT,"FAIL_TURN_WRITE")));
    checks.put("failedCompletionMessageRollsBackTheStateTransition",completionFailure&&repo.findSession(atomicTerminal.sessionId()).orElseThrow().status()==SessionStatus.RUNNING&&repo.listTurns(atomicTerminal.sessionId()).size()==1);

    var normal=session("normal-completion","owner");repo.createSessionWithTurn(normal,turn(normal,TurnRole.USER,"component question"));
    repo.transitionSessionWithTurn(normal.withStatus(SessionStatus.ANSWERED,"component result",false,"",List.of(),List.of(),Instant.now()),turn(normal,TurnRole.ASSISTANT,"component result"));
    checks.put("normalCompletionPersistsStateAndMatchingMessage",repo.findSession(normal.sessionId()).orElseThrow().status()==SessionStatus.ANSWERED&&repo.listTurns(normal.sessionId()).size()==2);
    boolean terminalRejected=false;
    try {repo.transitionSessionWithTurn(normal.withStatus(SessionStatus.FAILED,"late stale result",true,"",List.of(),List.of(),Instant.now()),turn(normal,TurnRole.SYSTEM_EVENT,"late stale result"));}
    catch(QueryStateChanged expected){terminalRejected=true;}
    checks.put("staleCompletionCannotAppendAContradictoryHistoryMessage",terminalRejected&&repo.findSession(normal.sessionId()).orElseThrow().status()==SessionStatus.ANSWERED&&repo.listTurns(normal.sessionId()).size()==2);

    var owned=session("owner-fence","owner");repo.createSessionWithTurn(owned,turn(owned,TurnRole.USER,"component question"));
    var wrongOwner=session(owned.sessionId(),"other-owner");boolean ownerRejected=false;
    try {repo.transitionSessionWithTurn(wrongOwner.withStatus(SessionStatus.ANSWERED,"forbidden component result",false,"",List.of(),List.of(),Instant.now()),turn(wrongOwner,TurnRole.ASSISTANT,"forbidden component result"));}
    catch(QueryStateChanged expected){ownerRejected=true;}
    checks.put("ownerFenceProtectsBothStateAndMessage",ownerRejected&&repo.findSession(owned.sessionId()).orElseThrow().status()==SessionStatus.RUNNING&&repo.listTurns(owned.sessionId()).size()==1);
    boolean bindingRejected=false;
    try {repo.transitionSessionWithTurn(owned.withStatus(SessionStatus.FAILED,"bad binding",true,"",List.of(),List.of(),Instant.now()),turn(normal,TurnRole.SYSTEM_EVENT,"bad binding"));}
    catch(IllegalArgumentException expected){bindingRejected=true;}
    checks.put("mismatchedMessageIdentityRejectedBeforeAnyWrite",bindingRejected&&repo.findSession(owned.sessionId()).orElseThrow().status()==SessionStatus.RUNNING);

    var races=new ArrayList<Map<String,Object>>();var executor=Executors.newFixedThreadPool(2);
    try {
      for(int i=0;i<20;i++){
        var question=session("clarification-"+i,"owner");repo.createSessionWithTurn(question,turn(question,TurnRole.USER,"component question"));
        var waiting=question.withStatus(SessionStatus.WAITING_USER,"",false,"choose an object",List.of("A","B"),List.of(),Instant.now());
        repo.transitionSessionWithTurn(waiting,turn(waiting,TurnRole.ASSISTANT,"choose an object"));
        var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
        for(String choice:List.of("A","B"))futures.add(executor.submit(()->{
          start.await();try {repo.transitionSessionWithTurn(waiting.withStatus(SessionStatus.RUNNING,"",false,"",List.of(),List.of(),Instant.now()),turn(waiting,TurnRole.USER,choice));return true;}catch(QueryStateChanged rejected){return false;}
        }));
        start.countDown();int winners=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))winners++;
        var turns=repo.listTurns(question.sessionId());long accepted=turns.stream().filter(t->t.role()==TurnRole.USER&&Set.of("A","B").contains(t.content())).count();
        races.add(Map.of("case",i,"winners",winners,"acceptedClarifications",accepted,"turnCount",turns.size(),"consistent",winners==1&&accepted==1&&turns.size()==3&&repo.findSession(question.sessionId()).orElseThrow().status()==SessionStatus.RUNNING));
      }
    } finally {executor.shutdownNow();}
    checks.put("twentyDuplicateClarificationRacesCommitOneMessageAndTransition",races.size()==20&&races.stream().allMatch(r->Boolean.TRUE.equals(r.get("consistent"))));
    checks.put("legacySeparateWriteGapsWereActuallyReproduced",gaps.values().stream().allMatch(Boolean.TRUE::equals));
    var report=new LinkedHashMap<String,Object>();report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_NATIVE_ATOMIC_QUERY_LIFECYCLE":"FAIL_NATIVE_ATOMIC_QUERY_LIFECYCLE");
    report.put("checks",checks);report.put("observedSeparateWriteGaps",gaps);report.put("clarificationRaces",races);
    report.put("boundary","Real PostgreSQL trigger failures, transactions, owner/state fences and20 concurrency races in a fresh retained harness schema. Synthetic component rows and the explicit legacy control gaps are not real model answers or workflow successes. No public business rows, clocks, model settings or evidence sources changed; no model or inference calls.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(checks.values().stream().anyMatch(v->!v))System.exit(1);
  }
}
