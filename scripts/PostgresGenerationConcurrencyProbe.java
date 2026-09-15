import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JDBC concurrent claims; isolated synthetic ledger fixtures only. */
public final class PostgresGenerationConcurrencyProbe {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  PostgresGenerationConcurrencyProbe() {
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc=new JdbcTemplate(ds);transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  private JdbcGenerationTaskRepository repository(String kb) {
    return new JdbcGenerationTaskRepository(jdbc,transactions,kb);
  }

  private List<GenerationTask> parallelClaims(boolean independentKnowledgeBases) throws Exception {
    var ready=new CountDownLatch(8);var start=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(8);
    try {
      var futures=new ArrayList<java.util.concurrent.Future<List<GenerationTask>>>();
      for(int i=0;i<8;i++) {
        String kb=independentKnowledgeBases ? "parallel-kb-"+i : "enterprise";
        futures.add(pool.submit(()->{
        var worker=repository(kb);ready.countDown();
        if(!start.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture launch timed out");
        return worker.claimPending(1,Instant.EPOCH);
      })); }
      if(!ready.await(20,TimeUnit.SECONDS))throw new IllegalStateException("fixture workers not ready");
      start.countDown();var claimed=new ArrayList<GenerationTask>();
      for(var future:futures)claimed.addAll(future.get(30,TimeUnit.SECONDS));
      return List.copyOf(claimed);
    } finally { pool.shutdownNow(); }
  }

  private void run(String path) throws Exception {
    var checks=new LinkedHashMap<String,Boolean>();var tasks=repository("enterprise");
    var owners=new LinkedHashMap<String,JdbcGenerationTaskRepository>();
    for(int i=0;i<8;i++) {
      var owner=repository("parallel-kb-"+i);
      var queued=owner.queue("version-public",GenerationTaskType.GRAPH,"parallel-distinct-"+i,Instant.now());
      owners.put(queued.taskId(),owner);
    }
    var distinct=parallelClaims(true);
    checks.put("eightConcurrentWorkersClaimOneStageEachAcrossEightKnowledgeBases",distinct.size()==8
        && distinct.stream().map(GenerationTask::taskId).distinct().count()==8);
    checks.put("eachLegalStartHasExactlyOneBusinessAttemptAndEpoch",distinct.stream().allMatch(t->t.attempt()==1
        && t.executionEpoch()==1 && t.sourceAclVersion()==1 && t.stageDeadline()!=null));
    checks.put("concurrentProcessesKeepSeparateOwnerIdentity",distinct.stream().map(GenerationTask::ownerId).distinct().count()==8);
    for(var task:distinct)owners.get(task.taskId()).withinPublication(task,()->jdbc.update("INSERT INTO publication_probe VALUES(?)",task.taskId()));
    checks.put("allCurrentClaimsCanUseTheSameAuthoritativePublicationGate",jdbc.queryForObject("SELECT count(*) FROM publication_probe",Integer.class)==8);
    for(var task:distinct)owners.get(task.taskId()).complete(task,"synthetic-ledger-component",Instant.now());
    tasks.queue("version-other",GenerationTaskType.GRAPH,"one-job-many-workers",Instant.now());
    var one=parallelClaims(false);
    checks.put("eightConcurrentWorkersStartOneSharedJobOnce",one.size()==1 && one.get(0).attempt()==1);
    checks.put("allEightOriginalJobsSettleWithoutDuplicatedPublication",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE status='SUCCEEDED'",Integer.class)==8
        && jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE status='RUNNING'",Integer.class)==1);
    var result=new LinkedHashMap<String,Object>();result.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)
        ? "PASS_REAL_POSTGRES_CONCURRENT_STAGE_CLAIMS" : "FAIL_REAL_POSTGRES_CONCURRENT_STAGE_CLAIMS");
    result.put("checks",checks);result.put("concurrentWorkers",8);
    result.put("boundary","V14 native PostgreSQL locking and Spring transactions in retained isolated synthetic schema. Eight distinct jobs belong to eight knowledge bases, consistent with one compilation stage per KB. Shared-job contention remains eight workers in one KB. Fixtures use normal queue/claim/publication/complete methods. Control isolation does not certify all storage namespaces. No model, real source generation, workflow approval or user success claimed; no SQL success/deadline edits.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(path),result);
    if(checks.values().stream().anyMatch(v->!v))System.exit(1);
  }

  public static void main(String[] args) throws Exception {new PostgresGenerationConcurrencyProbe().run(args[0]);}
}
