import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;
import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.infrastructure.upload.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.storage.LocalFileSourceAdapter;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** TCP interruption and an actual halted merge owner; native components, no parse/model claim. */
public class MultipartActualCrashProbe {
  static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  static JdbcTemplate db;static JdbcMultipartUploadRepository repo;static MinioMultipartUploadAdapter store;static MultipartUploadApplicationService service;
  static final Map<String,Boolean> checks=new LinkedHashMap<>();static final Map<String,Object> report=new LinkedHashMap<>();static Path output;static User user;
  static String id(){return UUID.randomUUID().toString();}
  static String sha(byte[] input)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));}
  static String md5(byte[] input)throws Exception{return Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(input));}
  static long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
  static void open(String kb) {
    db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
    var tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    repo=new JdbcMultipartUploadRepository(db,tx,JSON,output.resolveSibling(output.getFileName()+".originals"));
    var uploads=new JdbcIngestionRepository(db,tx,JSON);
    var managed=new ManagedIngestionApplicationService(uploads,new JdbcIdentityRepository(db),new LocalFileSourceAdapter());
    store=new MinioMultipartUploadAdapter("http://127.0.0.1:19400","http://127.0.0.1:19400",System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD"),"evokb-target","us-east-1");
    service=new MultipartUploadApplicationService(repo,store,managed,kb,256L*1024*1024,1024L*1024*1024);
  }
  static Session create(String label,long bytes,String hash) {
    return service.create(user,new Create(label+".txt",label+".txt",bytes,hash,new FileMetadata(label,"Synthetic acceptance","TRANSPORT","","CONTRACT",null,List.of("actual-multipart-crash"))));
  }
  static int put(Session session,int number,byte[] bytes)throws Exception {
    var url=service.sign(user,session.sessionId(),number,md5(bytes),session.clientSha256());
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(URI.create(url.url())).header("Content-MD5",md5(bytes)).timeout(Duration.ofSeconds(60)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
  }
  static String nativeSha(String key)throws Exception {
    try(var client=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();var source=client.getObject(GetObjectArgs.builder().bucket("evokb-target").object(key).build())) {
      var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] chunk=new byte[65536];for(int n;(n=source.read(chunk))!=-1;)digest.update(chunk,0,n);return HexFormat.of().formatHex(digest.digest());
    }
  }
  static void save(String status)throws Exception {report.put("status",status);report.put("checks",checks);report.put("observedAt",Instant.now());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);}
  public static void main(String[] args)throws Exception {
    output=Path.of(args[0]);String kb=args[1];open(kb);
    if(args.length>2&&args[2].equals("crash")) {
      var pending=repo.find(args[3]).orElseThrow();
      var owner=repo.beginComplete(pending.sessionId(),pending.revision(),id(),store.list(pending)).orElseThrow();
      var actual=store.complete(owner);
      JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("sessionId",owner.sessionId(),"objectKey",owner.objectKey(),"owner",owner.completionOwner(),"deadline",owner.completionDeadline(),"bytes",actual.sizeBytes(),"etag",actual.etag(),"pid",ProcessHandle.current().pid()));
      Runtime.getRuntime().halt(71);
    }
    report.put("boundary","Frozen immutable artifact/native PG/MinIO components. Actual TCP socket closes after 65536 of 16777216 declared PUT bytes; separate merge-owner JVM halts after native MinIO completion and before DB finalization. Real fixed completion600 elapsed, no DB deadline or clock injection. Retained isolated source versions are READY only, not parsed/indexed/model successes. Native KB prefix is test-owned; existing business data/objects unchanged.");
    try {
      Instant now=Instant.now();user=new User(id(),"native_crash_"+id(),"Synthetic native upload",Role.ADMIN,UserStatus.ACTIVE,null,now,now);
      db.update("INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status) VALUES(?,?,?,'COMPONENT_NOT_A_LOGIN','ADMIN','ACTIVE')",user.id(),user.username(),user.displayName());
      byte[] part=new byte[PART_BYTES];Arrays.fill(part,(byte)'n');byte[] tail="Synthetic short final part".getBytes(StandardCharsets.UTF_8);
      var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(part);hash.update(tail);String wholeSha=HexFormat.of().formatHex(hash.digest());
      var interrupted=create("native-tcp-interruption-"+id(),(long)part.length+tail.length,wholeSha);
      var signed=service.sign(user,interrupted.sessionId(),1,md5(part),wholeSha);var uri=URI.create(signed.url());
      try(var socket=new Socket(uri.getHost(),uri.getPort())) {
        socket.setSoTimeout(5000);var wire=socket.getOutputStream();
        String headers="PUT "+uri.getRawPath()+"?"+uri.getRawQuery()+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\nContent-MD5: "+md5(part)+"\r\nContent-Length: "+part.length+"\r\nConnection: close\r\n\r\n";
        wire.write(headers.getBytes(StandardCharsets.US_ASCII));wire.write(part,0,65536);wire.flush();socket.shutdownOutput();
      }
      Thread.sleep(500);
      checks.put("actualPartialTcpDisconnectLeavesNoCompletedPart",store.list(interrupted).isEmpty()&&repo.find(interrupted.sessionId()).orElseThrow().status()==State.UPLOADING);
      checks.put("sameSessionRealRetransferAndShortTailSucceed",put(interrupted,1,part)==200&&put(interrupted,2,tail)==200&&store.list(interrupted).size()==2);
      var complete=service.complete(user,interrupted.sessionId(),interrupted.revision(),store.list(interrupted));
      checks.put("tcpRecoveryMergesExactWholeOriginalOnce",complete.status()==State.COMPLETED&&wholeSha.equals(nativeSha(interrupted.objectKey()))&&count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?",interrupted.sessionId())==1);
      report.put("tcpFixture",Map.of("sessionId",interrupted.sessionId(),"objectKey",interrupted.objectKey(),"declaredPartBytes",part.length,"actualInterruptedBytes",65536,"wholeSha256",wholeSha));
      part=null;
      byte[] small="Synthetic actual process crash after native object merge".getBytes(StandardCharsets.UTF_8);var crash=create("native-real-merge-crash-"+id(),small.length,sha(small));if(put(crash,1,small)!=200)throw new IllegalStateException("actual crash part failed");
      Path workerEvidence=output.resolveSibling(output.getFileName()+".merge-worker.json"),workerLog=output.resolveSibling(output.getFileName()+".merge-worker.log");
      var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:ActiveProcessorCount=2","-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]","-cp",System.getProperty("java.class.path"),"MultipartActualCrashProbe",workerEvidence.toString(),kb,"crash",crash.sessionId()).redirectErrorStream(true).redirectOutput(workerLog.toFile()).start();
      int exit=process.waitFor();if(!Files.exists(workerEvidence))throw new IllegalStateException("Owned merge worker exited "+exit+" before crash evidence; retained "+workerLog.getFileName());
      var worker=JSON.readValue(workerEvidence.toFile(),Map.class);var owner=repo.find(crash.sessionId()).orElseThrow();
      checks.put("actualSeparateMergeOwnerHaltedAfterNativeObjectBeforeDbFinalize",exit==71&&((Number)worker.get("pid")).longValue()==process.pid()&&owner.status()==State.COMPLETING&&store.head(crash.objectKey()).isPresent()&&count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?",crash.sessionId())==0);
      report.put("mergeWorker",worker);report.put("completionDeadline",owner.completionDeadline());var samples=new ArrayList<Object>();report.put("samples",samples);boolean preserved=true;
      while(true) {
        Instant clock=db.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();if(!clock.isBefore(owner.completionDeadline()))break;
        service.recover();var current=repo.find(crash.sessionId()).orElseThrow();preserved&=current.status()==State.COMPLETING&&owner.completionOwner().equals(current.completionOwner())&&owner.completionDeadline().equals(current.completionDeadline())&&count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?",crash.sessionId())==0;
        samples.add(Map.of("databaseNow",clock,"originalOwnerDeadlinePreserved",preserved));save("RUNNING_NATIVE_MULTIPART_ACTUAL_CRASH_COMPLETION600");Thread.sleep(Math.min(15000,Math.max(1,Duration.between(clock,owner.completionDeadline()).toMillis())));
      }
      service.recover();var restored=repo.find(crash.sessionId()).orElseThrow();
      checks.put("fullActualCompletion600PreservesOwnerWithoutRenewalOrEarlyFinalize",preserved&&db.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant().isAfter(owner.completionDeadline()));
      checks.put("newOwnerRecoversMergedObjectByHeadExactlyOnce",restored.status()==State.COMPLETED&&sha(small).equals(nativeSha(crash.objectKey()))&&count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?",crash.sessionId())==1&&count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=?",restored.fileId())==1&&count("SELECT count(*) FROM document_version WHERE version_id=?",restored.documentVersionId())==1);
      service.recover();var duplicate=service.complete(user,crash.sessionId(),restored.revision(),List.of());
      checks.put("recoveryReplayKeepsSameVersionTaskAndPreexistingCompletedOriginal",duplicate.documentVersionId().equals(restored.documentVersionId())&&duplicate.fileId().equals(restored.fileId())&&count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?",crash.sessionId())==1&&wholeSha.equals(nativeSha(interrupted.objectKey())));
      report.put("restoredIdentity",Map.of("sessionId",restored.sessionId(),"versionId",restored.documentVersionId(),"fileId",restored.fileId(),"objectKey",restored.objectKey(),"wholeSha256",sha(small)));
      save(checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_NATIVE_MULTIPART_ACTUAL_CRASH_COMPLETION600":"FAIL_NATIVE_MULTIPART_ACTUAL_CRASH_COMPLETION600");
    }catch(Exception failure){report.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());save("FAIL_NATIVE_MULTIPART_ACTUAL_CRASH_COMPLETION600");throw failure;}finally{store.close();}
    if(checks.values().stream().anyMatch(value->!value))System.exit(1);
  }
}
