import cn.lgs.knowledgebase.infrastructure.ingestion.RedisRawDeliveryQueue;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.lettuce.core.resource.DefaultClientResources;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.client.codec.StringCodec;
import java.nio.file.*;import java.sql.Timestamp;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

/** Real native Streams/PEL/ACK + DB durability + Redisson fixed lease. No inference/workflow claim. */
public class RawStreamRecoveryProbe {
  static JdbcTemplate db;static JdbcIngestionRepository repo;static Map<String,Object> checks=new LinkedHashMap<>();
  static String uuid(){return UUID.randomUUID().toString();}
  static String file(){String b=uuid(),f=uuid();db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'native-stream-fixture',1)",b);db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'fixture','fixture','fixture','fixture','fixture')",f,b);repo.queueBatch(b,Instant.now());return f;}
  static long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
  public static void main(String[] args)throws Exception {
    db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));repo=new JdbcIngestionRepository(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),new ObjectMapper());
    var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();var cf=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",16385),LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(3)).build());cf.afterPropertiesSet();cf.start();var redis=new StringRedisTemplate(cf);redis.afterPropertiesSet();Config c=new Config();c.setThreads(2).setNettyThreads(2).setCodec(StringCodec.INSTANCE);c.useSingleServer().setAddress("redis://127.0.0.1:16385").setConnectionMinimumIdleSize(1).setConnectionPoolSize(2).setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);var locks=Redisson.create(c);
    String stream="evokb:acceptance:raw:"+uuid(),group="raw-native";var a=new RedisRawDeliveryQueue(redis,repo,stream,group,uuid(),locks);var b=new RedisRawDeliveryQueue(redis,repo,stream,group,uuid(),locks);var events=new ArrayList<Object>();
    try {
      String f=file(),message=a.append(f);repo.rawDispatched(repo.rawDispatchable(100).stream().filter(r->r.fileId().equals(f)).findFirst().orElseThrow(),message);var delivery=a.read(1).get(0);RawTaskClaim claim=repo.claimRaw(f,uuid()).orElseThrow();
      checks.put("xreadgroupCreatesPendingWithExactBody",delivery.id().equals(message)&&delivery.fileId().equals(f)&&redis.opsForStream().pending(stream,group).getTotalPendingMessages()==1);
      checks.put("pelIdleNeverStealsUnexpiredDbOwner",b.recover(100).isEmpty()&&count("SELECT execution_epoch FROM ingestion_file WHERE file_id=? AND status='RUNNING'",f)==1);
      repo.rawFailure(claim,"INPUT_ERROR","fixture-only",false);var recovered=b.recover(100);checks.put("terminalFactPrecedesAckAndXclaimChangesConsumer",recovered.size()==1&&recovered.get(0).id().equals(message)&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND outcome='ISOLATED'",f)==1);b.acknowledge(message);
      checks.put("xackRemovesPelButKeepsStreamBody",redis.opsForStream().pending(stream,group).getTotalPendingMessages()==0&&redis.opsForStream().size(stream)==1);
      String lost=file(),lostId=a.append(lost);repo.rawDispatched(repo.rawDispatchable(100).stream().filter(r->r.fileId().equals(lost)).findFirst().orElseThrow(),lostId);a.read(1);redis.opsForStream().delete(stream,lostId);b.recover(100);
      checks.put("trimmedBodyLedgerAndRedispatchBeforeTombstoneAck",redis.opsForStream().pending(stream,group).getTotalPendingMessages()==0&&count("SELECT count(*) FROM raw_ingestion_delivery_failure WHERE message_id=? AND file_reference=? AND error_code='RAW_DELIVERY_BODY_MISSING'",lostId,lost)==1&&count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=? AND dispatched_at IS NULL",lost)==1);
      String malformed=redis.opsForStream().add(stream,Map.of("wrong_field","missing-file-id")).getValue();a.read(1);b.recover(100);checks.put("malformedPendingIsDurablyIsolatedThenAck",count("SELECT count(*) FROM raw_ingestion_delivery_failure WHERE message_id=? AND error_code='RAW_DELIVERY_INVALID'",malformed)==1&&redis.opsForStream().pending(stream,group).getTotalPendingMessages()==0);
      String duplicate=file(),first=a.append(duplicate),second=a.append(duplicate);var reads=a.read(10);var firstOwner=repo.claimRaw(duplicate,uuid());var secondOwner=repo.claimRaw(duplicate,uuid());checks.put("xaddBeforeOutboxMarkDuplicateCannotAddAttempt",reads.size()==2&&firstOwner.isPresent()&&secondOwner.isEmpty()&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",duplicate)==1);
      // 15 second real scheduling lease expires, unlike the 600 second DB business deadline.
      Timestamp businessDeadline=db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=?",Timestamp.class,duplicate);var thread=Executors.newSingleThreadExecutor();AtomicBoolean lockRejected=new AtomicBoolean(false),expiredAcquired=new AtomicBoolean(false);AtomicInteger scans=new AtomicInteger();
      a.withRecoveryLease(()->{
        try {
          lockRejected.set(!thread.submit(()->b.withRecoveryLease(scans::incrementAndGet)).get(3,TimeUnit.SECONDS));
          events.add(Map.of("leaseTtlMillis",redis.getExpire(stream+":recovery",TimeUnit.MILLISECONDS)));
          Thread.sleep(16000);
          expiredAcquired.set(thread.submit(()->b.withRecoveryLease(scans::incrementAndGet)).get(3,TimeUnit.SECONDS));
        }catch(Exception e){throw new IllegalStateException(e);}
      });thread.shutdown();
      checks.put("redissonFixed15SecondLeaseNoWatchdog",lockRejected.get()&&expiredAcquired.get()&&scans.get()==1);
      checks.put("schedulingLeaseCannotRenewBusinessDeadline",businessDeadline.equals(db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=?",Timestamp.class,duplicate))&&!repo.rawRecoverable(duplicate));
      events.add(Map.of("stream",stream,"streamBodyCount",redis.opsForStream().size(stream),"pendingCount",redis.opsForStream().pending(stream,group).getTotalPendingMessages()));
      var report=new LinkedHashMap<String,Object>();report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_ACTUAL_REDIS_RAW_RECOVERY":"FAIL_ACTUAL_REDIS_RAW_RECOVERY");report.put("checks",checks);report.put("events",events);report.put("stream",stream);report.put("group",group);report.put("boundary","Real local Redis16385, PostgreSQL isolated fixture schema, native SpringDataRedis3.5.6/Lettuce6.6.0 and Redisson3.27.2. Stream/body/DB facts retained. No actual model/parser/browser/API workflow or production600 elapsed claim.");new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    } finally {locks.shutdown();cf.destroy();resources.shutdown().get(5,TimeUnit.SECONDS);}
    if(checks.values().stream().anyMatch(v->!Boolean.TRUE.equals(v)))System.exit(1);
  }
}
