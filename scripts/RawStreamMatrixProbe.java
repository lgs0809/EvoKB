import cn.lgs.knowledgebase.infrastructure.ingestion.RedisRawDeliveryQueue;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.lettuce.core.resource.DefaultClientResources;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Actual retained Redis/PG components and crashing JVMs; no inference/application success claim. */
public class RawStreamMatrixProbe {
  static final ObjectMapper JSON = new ObjectMapper();
  static JdbcTemplate db;
  static JdbcIngestionRepository repo;
  static DefaultClientResources resources;
  static LettuceConnectionFactory factory;
  static StringRedisTemplate redis;
  static final Map<String,Object> report = new LinkedHashMap<>();
  static final Map<String,Boolean> checks = new LinkedHashMap<>();
  static Path output;
  static String id() { return UUID.randomUUID().toString(); }
  static long count(String sql,Object...args) { return db.queryForObject(sql,Long.class,args); }
  static void open() {
    db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
    repo = new JdbcIngestionRepository(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),JSON);
    resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
    factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16385),LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(5)).build());
    factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
  }
  static void close() throws Exception { factory.destroy();resources.shutdown().get(5,java.util.concurrent.TimeUnit.SECONDS); }
  static RedisRawDeliveryQueue queue(String stream,String consumer) { return new RedisRawDeliveryQueue(redis,repo,stream,"native-matrix",consumer); }
  static String file() {
    String batch=id(),file=id();
    db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'native-stream-matrix',1)",batch);
    db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'component','component','component','component','component')",file,batch);
    repo.queueBatch(batch,Instant.now());return file;
  }
  static void save(String status) throws Exception {
    report.put("status",status);report.put("checks",checks);report.put("observedAt",Instant.now().toString());
    JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
  }
  static Map<String,Object> crashWorker(String phase,String file,String stream,String message) throws Exception {
    Path evidence=output.resolveSibling(output.getFileName()+"."+phase+".json");
    Path log=output.resolveSibling(output.getFileName()+"."+phase+".log");
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:ActiveProcessorCount=2","-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]","-cp",System.getProperty("java.class.path"),"RawStreamMatrixProbe",evidence.toString(),"crash",phase,file,stream,message).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    int exit=process.waitFor();
    if(!Files.exists(evidence))throw new IllegalStateException("Crash fixture exited "+exit+" before writing claim evidence; inspect retained owned-helper log "+log.getFileName());
    var detail=JSON.readValue(evidence.toFile(),Map.class);
    checks.put(phase+"ActuallyCrashedSeparateJvm",exit==71&&((Number)detail.get("pid")).longValue()==process.pid());
    return detail;
  }
  public static void main(String[] args)throws Exception {
    output=Path.of(args[0]);open();
    report.put("boundary","Frozen artifact classes/migrations; native isolated PG and Redis, actual separate helper JVMs abruptly halt before terminal commit and after INPUT_ERROR terminal commit without ACK. 105 actual PEL entries; real 600-second DB ownership wait without deadline/clock SQL injection. No model/parser/real application success claim, no runtime service restart or production data mutation.");
    if(args.length>1&&args[1].equals("crash")) {
      String phase=args[2],file=args[3],stream=args[4],expectedMessage=args[5];
      var delivered=queue(stream,id()).read(1).get(0);
      if(!delivered.id().equals(expectedMessage))throw new IllegalStateException("Exact expected crash delivery required");
      var claim=repo.claimRaw(file,id()).orElseThrow();
      if(phase.equals("after-terminal-commit"))repo.rawFailure(claim,"INPUT_ERROR","Synthetic component terminal failure; not an actual parse result",false);
      var detail=Map.of("phase",phase,"fileId",file,"messageId",delivered.id(),"ownerId",claim.ownerId(),"epoch",claim.epoch(),"deadline",claim.deadline().toString(),"pid",ProcessHandle.current().pid(),"dbStatus",repo.findFile(file).orElseThrow().status().name());
      JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),detail);
      Runtime.getRuntime().halt(71); // Only this owned helper JVM; leave real DB/PEL facts intact.
    }
    try {
      String pageStream="evokb:acceptance:raw:matrix-pages:"+id();
      var a=queue(pageStream,id());var b=queue(pageStream,id());var pageFiles=new ArrayList<String>();
      for(int i=0;i<105;i++) {String f=file();pageFiles.add(f);String message=a.append(f);repo.rawDispatched(repo.rawDispatchable(100).stream().filter(row->row.fileId().equals(f)).findFirst().orElseThrow(),message);}
      checks.put("xreadgroupActuallyCreates105PendingAcrossReadPages",a.read(100).size()==100&&a.read(100).size()==5&&redis.opsForStream().pending(pageStream,"native-matrix").getTotalPendingMessages()==105);
      for(int i=0;i<100;i++)repo.claimRaw(pageFiles.get(i),id()).orElseThrow();
      checks.put("firstHundredUnexpiredOwnersNotStolen",b.recover(100).isEmpty()&&count("SELECT count(*) FROM ingestion_file WHERE status='RUNNING' AND attempt=1 AND execution_epoch=1")==100);
      var tail=b.recover(100);
      checks.put("pendingCursorReachesRecoverableTailBeyondHundred",tail.size()==5&&new HashSet<>(tail.stream().map(m->m.fileId()).toList()).equals(new HashSet<>(pageFiles.subList(100,105))));
      for(var item:tail) {var claim=repo.claimRaw(item.fileId(),id()).orElseThrow();repo.rawFailure(claim,"INPUT_ERROR","Synthetic isolated fixture",false);b.acknowledge(item.id());}
      checks.put("tailTerminalDurabilityPrecedesAckAndBodiesRemain",redis.opsForStream().pending(pageStream,"native-matrix").getTotalPendingMessages()==100&&redis.opsForStream().size(pageStream)==105&&count("SELECT count(*) FROM raw_ingestion_failure WHERE outcome='ISOLATED'")==5);
      var restarted=queue(pageStream,id());
      checks.put("freshConsumerStartsCursorWithoutStealingUnexpiredOwners",restarted.recover(100).isEmpty()&&restarted.recover(100).isEmpty()&&redis.opsForStream().pending(pageStream,"native-matrix").getTotalPendingMessages()==100);
      String crashStream="evokb:acceptance:raw:matrix-crashes:"+id();var dispatch=queue(crashStream,id());
      String pre=file(),preMessage=dispatch.append(pre);repo.rawDispatched(repo.rawDispatchable(100).stream().filter(r->r.fileId().equals(pre)).findFirst().orElseThrow(),preMessage);
      var preCrash=crashWorker("before-terminal-commit",pre,crashStream,preMessage);
      String post=file(),postMessage=dispatch.append(post);repo.rawDispatched(repo.rawDispatchable(100).stream().filter(r->r.fileId().equals(post)).findFirst().orElseThrow(),postMessage);
      var postCrash=crashWorker("after-terminal-commit",post,crashStream,postMessage);
      report.put("crashedWorkers",List.of(preCrash,postCrash));report.put("pageStream",pageStream);report.put("crashStream",crashStream);
      var survivor=queue(crashStream,id());var terminal=survivor.recover(100);
      checks.put("postCommitCrashAckLossRecoveredWithoutNewAttempt",terminal.size()==1&&terminal.get(0).fileId().equals(post)&&repo.claimRaw(post,id()).isEmpty()&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",post)==1&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND outcome='ISOLATED'",post)==1);
      survivor.acknowledge(postMessage);
      checks.put("postCommitAckRemovesOnlyTerminalPelKeepsBody",redis.opsForStream().pending(crashStream,"native-matrix").getTotalPendingMessages()==1&&redis.opsForStream().size(crashStream)==2);
      Instant start=db.queryForObject("SELECT stage_started_at FROM ingestion_file WHERE file_id=?",java.sql.Timestamp.class,pre).toInstant();
      Instant deadline=Instant.parse(preCrash.get("deadline").toString());
      checks.put("crashedTaskBudgetIsExactly600Seconds",Duration.between(start,deadline).equals(Duration.ofSeconds(600)));
      report.put("stageStartedAt",start.toString());report.put("stageDeadline",deadline.toString());
      report.put("boundary","Frozen artifact classes/migrations; native isolated PG and Redis, actual separate helper JVMs abruptly halt before terminal commit and after INPUT_ERROR terminal commit without ACK. 105 actual PEL entries; real 600-second DB ownership wait without deadline/clock SQL injection. No model/parser/real application success claim, no runtime service restart or production data mutation.");
      var samples=new ArrayList<Object>();report.put("samples",samples);boolean preserved=true;
      while(true) {
        Instant now=db.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
        if(!now.isBefore(deadline))break;
        repo.resetInterrupted(Instant.now());
        preserved&=deadline.equals(db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=? AND status='RUNNING'",java.sql.Timestamp.class,pre).toInstant())&&survivor.recover(100).isEmpty()&&repo.claimRaw(pre,id()).isEmpty();
        samples.add(Map.of("databaseNow",now.toString(),"elapsedSeconds",Duration.between(start,now).toMillis()/1000d,"unexpiredOwnerPreserved",preserved));save("RUNNING_ACTUAL_REDIS_RAW_MATRIX_DB600");
        Thread.sleep(Math.min(15000,Math.max(1,Duration.between(now,deadline).toMillis())));
      }
      var oldClaim=new RawTaskClaim(repo.findFile(pre).orElseThrow(),preCrash.get("ownerId").toString(),((Number)preCrash.get("epoch")).longValue(),deadline);
      boolean denied=false;try{repo.rawExecution(oldClaim).fenced(()->db.update("UPDATE ingestion_file SET error_message='MUST_NOT_WRITE' WHERE file_id=?",pre));}catch(IllegalStateException expected){denied=true;}
      for(int i=0;i<3;i++)repo.resetInterrupted(Instant.now());
      var recoverable=survivor.recover(100);var next=repo.claimRaw(pre,id()).orElseThrow();
      checks.put("actual600WaitNeverRenewsOrStealsCrashedOwner",preserved&&Duration.between(start,db.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant()).toMillis()>=600000);
      checks.put("expiredOldOwnerCannotWriteEvenBeforeReplacement",denied&&count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND error_message='MUST_NOT_WRITE'",pre)==0);
      checks.put("expiredCrashRecoveredByStreamAndDbExactlyOnce",recoverable.size()==1&&recoverable.get(0).id().equals(preMessage)&&next.epoch()==2&&next.file().attempt()==2&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND error_code='RAW_STAGE_EXPIRED'",pre)==1);
      repo.rawFailure(next,"INPUT_ERROR","Synthetic recovered terminal fixture; no parse performed",false);survivor.acknowledge(preMessage);
      checks.put("recoveredTerminalAckLeavesNoCrashPelOrExtraAttempt",redis.opsForStream().pending(crashStream,"native-matrix").getTotalPendingMessages()==0&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",pre)==2&&redis.opsForStream().size(crashStream)==2);
      save(checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_ACTUAL_REDIS_RAW_MATRIX_DB600":"FAIL_ACTUAL_REDIS_RAW_MATRIX_DB600");
    } catch(Exception failure) {report.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());save("FAIL_ACTUAL_REDIS_RAW_MATRIX_DB600");throw failure;} finally {close();}
    if(checks.values().stream().anyMatch(value->!value))System.exit(1);
  }
}
