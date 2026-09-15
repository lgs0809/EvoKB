import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawPublicationWriter;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual isolated DB protocol fixtures; no manufactured real workflow or model success. */
public class RawTaskOwnershipProbe {
  static JdbcTemplate db;static JdbcIngestionRepository repo;static final Map<String,Object> checks=new LinkedHashMap<>();
  static String id(){return UUID.randomUUID().toString();}
  static String file(){String b=id(),f=id();db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'synthetic-native-probe',1)",b);db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'fixture.md','fixture.md','fixture-only','fixture-only','fixture-only')",f,b);repo.queueBatch(b,Instant.now());return f;}
  static RawTaskClaim claim(String file){return repo.claimRaw(file,id()).orElseThrow();}
  static boolean denied(Runnable r){try{r.run();return false;}catch(IllegalStateException expected){return true;}}
  static long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
  public static void main(String[] args)throws Exception {
    db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
    repo=new JdbcIngestionRepository(db,new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())),new ObjectMapper());String f=file();
    checks.put("normalQueueCommitsOutbox",count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=?",f)==1);
    var pool=Executors.newFixedThreadPool(4);var results=new ArrayList<Future<Optional<RawTaskClaim>>>();
    for(int i=0;i<24;i++)results.add(pool.submit(()->repo.claimRaw(f,id())));
    var winners=new ArrayList<RawTaskClaim>();for(var result:results)result.get(10,TimeUnit.SECONDS).ifPresent(winners::add);pool.shutdown();
    RawTaskClaim winner=winners.get(0);checks.put("concurrent24ClaimsExactlyOneAttemptAndEpoch",winners.size()==1&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",f)==1&&winner.epoch()==1);
    double span=db.queryForObject("SELECT extract(epoch FROM stage_deadline-stage_started_at) FROM ingestion_file WHERE file_id=?",Double.class,f);
    checks.put("databaseClockStageBudgetExactly600",span>=599.99&&span<=600.01);
    var exec=repo.rawExecution(winner);exec.phase(ProgressStage.PARSING);Timestamp deadline=db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=?",Timestamp.class,f);
    Thread.sleep(40);exec.phase(ProgressStage.PARSING);exec.phase(ProgressStage.EVIDENCE);
    checks.put("sameStageAndEvidenceNeverRenewDeadline",deadline.equals(db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=?",Timestamp.class,f)));
    checks.put("backwardStageCannotRenew",denied(()->exec.phase(ProgressStage.STORING)));
    checks.put("unexpiredOwnerNotRecoverableByPelIdle",!repo.rawRecoverable(f));repo.resetInterrupted(Instant.now());
    checks.put("startupScanPreservesLiveOwner",count("SELECT execution_epoch FROM ingestion_file WHERE file_id=? AND status='RUNNING'",f)==1);
    // Explicit isolated deadline fault injection; not a claim that production 600 seconds elapsed.
    db.update("UPDATE ingestion_file SET stage_deadline=clock_timestamp()-interval '1 second' WHERE file_id=?",f);repo.resetInterrupted(Instant.now());RawTaskClaim replacement=claim(f);
    checks.put("expiredRecoveryGetsNewEpoch",replacement.epoch()==2&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND outcome='INTERRUPTED'",f)==1);
    checks.put("lateOldOwnerCannotMutateOrComplete",denied(()->exec.fenced(()->db.update("UPDATE ingestion_file SET error_message='STALE SHOULD NOT WRITE' WHERE file_id=?",f)))&&denied(()->repo.rawComplete(winner,"bad","bad"))&&count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND error_message='STALE SHOULD NOT WRITE'",f)==0);
    String retryFile=file();var delays=new ArrayList<Double>();boolean earlyClaims=true;
    for(int attempt=1;attempt<=5;attempt++) {
      RawTaskClaim c=claim(retryFile);repo.rawFailure(c,"UPSTREAM_TIMEOUT","synthetic transport failure",true);
      if(attempt<5){delays.add(db.queryForObject("SELECT extract(epoch FROM next_attempt_at-occurred_at) FROM raw_ingestion_failure WHERE file_id=? AND execution_epoch=?",Double.class,retryFile,c.epoch()));earlyClaims&=repo.claimRaw(retryFile,id()).isEmpty();db.update("UPDATE ingestion_file SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE file_id=?",retryFile);}
    }
    checks.put("fiveRealClaimsThenIsolate",count("SELECT attempt FROM ingestion_file WHERE file_id=? AND status='FAILED' AND isolated",retryFile)==5&&repo.claimRaw(retryFile,id()).isEmpty());
    checks.put("durableBackoff10_30_120_300AndNoEarlyClaim",delays.equals(List.of(10d,30d,120d,300d))&&earlyClaims);
    String bad=file();repo.rawFailure(claim(bad),"INPUT_ERROR","bad fixture",false);checks.put("knownBadInputOneAttemptNoRetry",count("SELECT attempt FROM ingestion_file WHERE file_id=? AND isolated",bad)==1);
    // A real epoch/deadline fence protects a real DB publication pointer in a short transaction.
    String p=file(),doc=id(),v=id();RawTaskClaim pc=claim(p);
    db.update("INSERT INTO document(document_id,logical_name) VALUES(?,'synthetic pointer source')",doc);
    db.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,'PENDING','fixture.md',0,'fixture-only','fixture-only')",v,doc);
    db.update("INSERT INTO document_metadata(version_id) VALUES(?)",v);var pe=repo.rawExecution(pc);pe.bind(doc,v);pe.phase(ProgressStage.ACTIVATING);
    pe.publish(doc,v,List.of("immutable-physical-epoch1"),()->db.update("UPDATE document_version SET lifecycle='ACTIVE' WHERE version_id=?",v));
    checks.put("fencedPublicationCommitsPointerAndMembership",count("SELECT count(*) FROM raw_publication WHERE version_id=? AND producer_scope=?",v,pc.scope())==1&&count("SELECT count(*) FROM raw_publication_member WHERE version_id=? AND physical_id='immutable-physical-epoch1'",v)==1);
    db.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id=?",v);
    checks.put("changedAclPreventsPublication",denied(()->pe.publish(doc,v,List.of("bad-acl-write"),()->{}))&&count("SELECT count(*) FROM raw_publication_member WHERE physical_id='bad-acl-write'")==0);
    db.update("UPDATE document SET status='DELETED' WHERE document_id=?",doc);checks.put("deletedSourcePreventsPublication",denied(()->pe.publish(doc,v,List.of("bad-deleted-write"),()->{})));
    String lost=file();var row=repo.rawDispatchable(100).stream().filter(r->r.fileId().equals(lost)).findFirst().orElseThrow();repo.rawDispatched(row,"1234-0");repo.rawMissingDelivery("1234-0");
    checks.put("trimmedBodyCommitsAssociationAndRedispatch",count("SELECT count(*) FROM raw_ingestion_delivery_history WHERE message_id='1234-0' AND file_id=?",lost)==1&&count("SELECT count(*) FROM raw_ingestion_delivery_failure WHERE message_id='1234-0' AND error_code='RAW_DELIVERY_BODY_MISSING'")==1&&count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=? AND dispatched_at IS NULL AND delivery_epoch=2",lost)==1);
    repo.rawRejectDelivery("poison-0","unknown-id");checks.put("poisonHasDurableIsolationFact",count("SELECT count(*) FROM raw_ingestion_delivery_failure WHERE message_id='poison-0'")==1);
    String b=id();db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'synthetic-cursor',105)",b);
    for(int i=0;i<105;i++)db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name,status,progress_stage,attempt,execution_epoch,owner_id,execution_stage,stage_deadline) VALUES(?,?,'fixture','fixture','fixture','fixture','fixture','RUNNING','PARSING',1,1,?,'PARSING',clock_timestamp()-interval '1 second')",id(),b,id());
    repo.resetInterrupted(Instant.now());long first=count("SELECT count(*) FROM ingestion_file WHERE batch_id=? AND status='QUEUED'",b);repo.resetInterrupted(Instant.now());long second=count("SELECT count(*) FROM ingestion_file WHERE batch_id=? AND status='QUEUED'",b);
    checks.put("recoveryBounded100CursorEventually105",first==100&&second==105);
    String sf=file(),sf2=file(),sd=id(),sv=id();var sx=repo.rawExecution(claim(sf));var sx2=repo.rawExecution(claim(sf2));
    db.update("INSERT INTO document(document_id,logical_name) VALUES(?,'native atomic reservation')",sd);
    db.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,'PENDING','fixture',0,'fixture-only','fixture-only')",sv,sd);db.update("INSERT INTO document_metadata(version_id) VALUES(?)",sv);
    sx.reserveSource("native atomic reservation",()->{sx.bind(sd,sv);return true;});
    checks.put("sameInputVersionCannotHaveTwoLiveOwners",denied(()->sx2.reserveSource("native atomic reservation",()->{db.update("UPDATE document_metadata SET acl_version=999 WHERE version_id=?",sv);sx2.bind(sd,sv);return true;}))&&count("SELECT acl_version FROM document_metadata WHERE version_id=?",sv)==1&&count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND version_id IS NOT NULL",sf2)==0);
    db.update("UPDATE ingestion_file SET stage_deadline=clock_timestamp()-interval '1 second' WHERE file_id=?",sf);sx2.reserveSource("native atomic reservation",()->{sx2.bind(sd,sv);return true;});
    checks.put("expiredSourceOwnerCannotBlockNewReservation",count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND version_id=?",sf2,sv)==1&&denied(sx::check));
    var reservationPool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var locked=reservationPool.submit(()->sx2.reserveSource("another logical source",()->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return true;}));entered.await(2,TimeUnit.SECONDS);
    boolean busy=denied(()->repo.rawExecution(replacement).reserveSource("another logical source",()->{throw new AssertionError("Concurrent source allocation must not run");}));release.countDown();locked.get(3,TimeUnit.SECONDS);reservationPool.shutdown();checks.put("logicalNameAdvisoryReservationIsAtomicAndShort",busy);
    var report=new LinkedHashMap<String,Object>();report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_ACTUAL_PG_RAW_OWNERSHIP":"FAIL_ACTUAL_PG_RAW_OWNERSHIP");report.put("checks",checks);report.put("backoffSeconds",delays);report.put("stageBudgetSeconds",span);report.put("publication",db.queryForList("SELECT * FROM raw_publication"));report.put("boundary","Actual PostgreSQL repository/CAS/transactions. Synthetic isolated file/source fixtures; forced-expiry and retry-due fault injection is explicit. No real parsing/model/browser, no production-600 elapsed or real user workflow completion claimed.");new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(checks.values().stream().anyMatch(vv->!Boolean.TRUE.equals(vv)))System.exit(1);
  }
}
