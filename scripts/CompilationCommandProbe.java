import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.compilation.CompilationCommands.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native durable admin-command authority. Model/artifact content is explicitly synthetic. */
public final class CompilationCommandProbe {
  final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
  final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
  final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  final JdbcGenerationTaskRepository tasks=new JdbcGenerationTaskRepository(jdbc,tx,"command-component");
  final JdbcIdentityRepository users=new JdbcIdentityRepository(jdbc);
  final KnowledgeAccessService access=new KnowledgeAccessService(users,ids->List.of(),new JdbcQuerySessionRepository(jdbc,tx,mapper),new JdbcProjectionAuthority(jdbc));
  final JdbcCompilationCommandRepository commands=new JdbcCompilationCommandRepository(jdbc,tx,mapper,tasks,users,access,"command-component");
  final Map<String,Boolean> checks=new LinkedHashMap<>();
  void check(String name,boolean ok){checks.put(name,ok);if(!ok)throw new AssertionError(name);}
  boolean denied(Runnable action){try{action.run();return false;}catch(RuntimeException expected){return true;}}
  long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
  Request wiki(){return new Request(Kind.WIKI,"",List.of("summary-other","summary-public"),"CONTRACT","合成验收专题标题","synthetic-canonical","",0);}
  void run(String output)throws Exception {
    var report=new LinkedHashMap<String,Object>();
    try {
      jdbc.execute("CREATE TABLE synthetic_command_write(marker text)");
      check("nonAdminCreatesNoDurableCommand",denied(()->commands.submit("command-user",wiki())) && count("SELECT count(*) FROM kb_compilation_command")==0);
      jdbc.update("UPDATE enterprise_user SET security_clearance=10 WHERE user_id='command-admin'");
      check("clearanceBlocksPrivateSourceBeforeQueue",denied(()->commands.submit("command-admin",new Request(Kind.SUMMARY,"version-secret",List.of(),"","","","",0))) && count("SELECT count(*) FROM compile_task")==0);
      jdbc.update("UPDATE enterprise_user SET security_clearance=30 WHERE user_id='command-admin'");
      var pool=Executors.newFixedThreadPool(4);var ids=new HashSet<String>();
      try {var futures=new ArrayList<Future<String>>();for(int i=0;i<24;i++)futures.add(pool.submit(()->commands.submit("command-admin",wiki()).taskId()));for(var f:futures)ids.add(f.get());}
      finally{pool.shutdownNow();}
      check("twentyFourDuplicateRequestsAtomicallyCreateOneJob",ids.size()==1&&count("SELECT count(*) FROM compile_task")==1&&count("SELECT count(*) FROM kb_compilation_command")==1);
      check("httpAdmissionNeverSpendsAttemptOrRunsModel",count("SELECT attempt FROM compile_task")==0);
      var task=tasks.claimPending(1,Instant.now()).get(0);var command=commands.find(task).orElseThrow();
      check("allWikiArgumentsAndSummariesSurviveJacksonRoundTrip",command.request().equals(wiki())&&command.sourceVersions().equals(List.of("version-other","version-public")));
      check("secondWorkerCannotEnterSameKnowledgeBaseLane",new JdbcGenerationTaskRepository(jdbc,tx,"command-component").claimPending(1,Instant.now()).isEmpty());
      var gate=commands.publication(task,command,tasks.publication(task));gate.captureInputs(command.sourceVersions());gate.check();
      check("everyRequestedInputIsPinnedBeforeExternalWork",count("SELECT count(*) FROM compile_task_input WHERE task_id=?",task.taskId())==2);
      jdbc.update("UPDATE document_metadata SET acl_version=2 WHERE version_id='version-public'");
      check("permissionChangeBlocksCheckAndAtomicCommit",denied(gate::check)&&denied(()->gate.publish(()->jdbc.update("INSERT INTO synthetic_command_write VALUES('forbidden')")))&&count("SELECT count(*) FROM synthetic_command_write")==0);
      tasks.fail(task,"SYNTHETIC_REVOKED","component-only",Instant.now());
      var replacement=commands.submit("command-admin",wiki());
      check("changedSourceSnapshotCreatesNewCommandIdentity",!replacement.taskId().equals(task.taskId())&&count("SELECT count(*) FROM kb_compilation_command")==2);
      var next=tasks.claimPending(1,Instant.now()).get(0);var nextCommand=commands.find(next).orElseThrow();
      var nextGate=commands.publication(next,nextCommand,tasks.publication(next));nextGate.captureInputs(nextCommand.sourceVersions());
      jdbc.update("UPDATE enterprise_user SET role='USER' WHERE user_id='command-admin'");
      check("actorRevocationBlocksInFlightPublication",denied(nextGate::check)&&denied(()->nextGate.publish(()->jdbc.update("INSERT INTO synthetic_command_write VALUES('revoked-admin')")))&&count("SELECT count(*) FROM synthetic_command_write")==0);
      jdbc.update("UPDATE enterprise_user SET role='ADMIN' WHERE user_id='command-admin'");
      nextGate.publish(()->jdbc.update("INSERT INTO synthetic_command_write VALUES('synthetic-owned-command')"));
      tasks.complete(next,"SYNTHETIC_COMPONENT_NO_MODEL",Instant.now());
      check("ownedCurrentAuthorityCommitsExactlyOneSyntheticMutation",count("SELECT count(*) FROM synthetic_command_write")==1);
      check("completedCommandCannotPublishAgain",denied(()->nextGate.publish(()->jdbc.update("INSERT INTO synthetic_command_write VALUES('late')")))&&count("SELECT count(*) FROM synthetic_command_write")==1);
      check("newRepositoryRecoversIdenticalImmutableRequest",new JdbcCompilationCommandRepository(jdbc,tx,mapper,tasks,users,access,"command-component").find(next).orElseThrow().request().equals(wiki()));
      var keys=new Request(Kind.WIKI_KEYWORDS,"",List.of(),"","","","synthetic-wiki",1);
      var keywordTask=commands.submit("command-admin",keys);
      check("keywordProjectionPinsFullWikiInputs",commands.find(keywordTask).orElseThrow().sourceVersions().equals(List.of("version-other","version-public")));
      var projection=commands.submit("command-admin",new Request(Kind.CONCEPT_PROJECTION,"",List.of("summary-other"),"","","","",0));
      check("projectionOnlyModeIsPreservedInsteadOfModelReextraction",commands.find(projection).orElseThrow().request().kind()==Kind.CONCEPT_PROJECTION);
      check("missingSummaryRollsBackAllAdmissionRows",denied(()->commands.submit("command-admin",new Request(Kind.GRAPH,"",List.of("unknown-summary"),"","","","",0)))&&count("SELECT count(*) FROM kb_compilation_command")==4);
      jdbc.update("UPDATE kb_compilation_command SET request_json=jsonb_set(request_json,'{title}',to_jsonb('tampered'::text)) WHERE task_id=?",next.taskId());
      check("storedRequestTamperingFailsClosed",denied(()->commands.find(next)));
      report.put("status","PASS_ACTUAL_DURABLE_COMPILATION_COMMAND_COMPONENT");
    }catch(Throwable e){e.printStackTrace();report.put("status","FAIL_ACTUAL_DURABLE_COMPILATION_COMMAND_COMPONENT");report.put("error",e.toString());}
    report.put("checks",checks);report.put("boundary","Real retained PostgreSQL admission, Jackson persistence, actor/source authority, 24 duplicate transactions and existing ownership lane. Source/summary/wiki/projection/mutation payloads are synthetic component fixtures. No actual HTTP, model, Milvus projection, production stage completion or user workflow acceptance claimed.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  public static void main(String[] args)throws Exception{new CompilationCommandProbe().run(args[0]);}
}
