import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.infrastructure.ingestion.RedisRawDeliveryQueue;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import cn.lgs.knowledgebase.infrastructure.search.MilvusKnowledgeIndexAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.resource.DefaultClientResources;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual fixed cases/native storage, publication and crash/network boundaries; no inference. */
public final class RawStreamThirtyProbe {
  static final ObjectMapper JSON = new ObjectMapper();
  static JdbcTemplate db; static TransactionTemplate tx; static JdbcIngestionRepository repo;
  static DefaultClientResources resources; static LettuceConnectionFactory redisFactory;
  static StringRedisTemplate redis; static MinioClient objects; static MilvusClientV2 milvus;
  static MilvusKnowledgeIndexAdapter index;
  static final Map<String,Object> report = new LinkedHashMap<>();
  static final List<Map<String,Object>> results = new ArrayList<>();
  static final Map<String,RawTaskClaim> oldOwners = new LinkedHashMap<>();
  static Path output; static String namespace;
  static boolean preserveExisting;
  static String id(){return UUID.randomUUID().toString();}
  static long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
  static Instant now(){return db.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
  static String text(Map<String,Object> value,String key){return String.valueOf(value.get(key));}
  static List<Float> vector(boolean strong){var x=new ArrayList<Float>(Collections.nCopies(1024,0f));x.set(0,1f);if(!strong)x.set(1,5f);return x;}
  static LettuceConnectionFactory factory(int port){var f=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port),LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofMillis(800)).build());f.afterPropertiesSet();f.start();return f;}
  static StringRedisTemplate template(LettuceConnectionFactory f){var r=new StringRedisTemplate(f);r.afterPropertiesSet();return r;}
  static RedisRawDeliveryQueue queue(Map<String,Object> c,StringRedisTemplate r){return new RedisRawDeliveryQueue(r,repo,text(c,"stream"),"fixed-thirty",id());}
  static RedisRawDeliveryQueue queue(Map<String,Object> c){return queue(c,redis);}
  static void open(boolean child)throws Exception {
    db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
    tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));repo=new JdbcIngestionRepository(db,tx,JSON);
    resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();redisFactory=factory(16385);redis=template(redisFactory);
    objects=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
    if(!child){var admin=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());try{if(!admin.listDatabases().getDatabaseNames().contains(namespace))admin.createDatabase(CreateDatabaseReq.builder().databaseName(namespace).build());}finally{admin.close();}}
    milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(namespace).rpcDeadlineMs(30000).build());
    if(preserveExisting&&!milvus.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq.builder().collectionName("knowledge_chunks").build()))throw new IllegalStateException("Original native collection must exist; never recreate it for recovery proof");
    index=new MilvusKnowledgeIndexAdapter(milvus,"knowledge_chunks",new JdbcProjectionAuthority(db));index.ensureIndex(1024);
  }
  static void close()throws Exception {if(milvus!=null)milvus.close();if(objects!=null)objects.close();if(redisFactory!=null)redisFactory.destroy();if(resources!=null)resources.shutdown().get(5,TimeUnit.SECONDS);}
  static RawIndexDocument chunk(Map<String,Object> c,boolean stale)throws Exception {
    String body=Files.readString(Path.of(text(c,"originalPath")));
    return new RawIndexDocument(text(c,"caseId")+(stale?"-late":"-current"),body,body,List.of("Fixed native reliability"),List.of(text(c,"blockId")),1,1,text(c,"originalSha256"),vector(stale),List.of(text(c,"evidenceId")),new Metadata(text(c,"caseId"),"ReliabilityFixture",List.of("synthetic-vector-not-model"),"Synthetic",null,"CONTRACT"));
  }
  static void fixture(Map<String,Object> c)throws Exception {
    String batch=id(),file=id(),document=id(),version=id(),block=id(),evidence=id();
    c.put("batchId",batch);c.put("fileId",file);c.put("documentId",document);c.put("versionId",version);c.put("blockId",block);c.put("evidenceId",evidence);
    c.put("stream","evokb:acceptance:raw:fixed30:"+namespace+":"+text(c,"caseId"));
    String key="acceptance/raw-stream30/"+namespace+"/"+text(c,"caseId")+"/original.md";c.put("objectKey",key);
    byte[] original=Files.readAllBytes(Path.of(text(c,"originalPath")));
    if(!text(c,"faultPosition").equals("MISSING_ORIGINAL"))objects.putObject(PutObjectArgs.builder().bucket("evokb-target").object(key).stream(new ByteArrayInputStream(original),original.length,-1).contentType("text/markdown").build());
    db.update("INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')",document,"Native fixture "+text(c,"caseId"));
    db.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,media_type,size_bytes,sha256,original_object_key) VALUES(?,?,1,'ACTIVE',?,'text/markdown',?,?,?)",version,document,text(c,"caseId")+".md",original.length,text(c,"originalSha256"),key);
    db.update("INSERT INTO document_metadata(version_id,security_level,source_department,material_category,auto_document_type) VALUES(?,10,?,'ReliabilityFixture','CONTRACT')",version,text(c,"caseId"));
    db.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?,?,1,'PARAGRAPH',?,?)",block,version,new String(original,java.nio.charset.StandardCharsets.UTF_8),text(c,"originalSha256"));
    db.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES(?,?,?,?,?,'{}',?)",evidence,document,version,block,key,text(c,"originalSha256"));
    db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'native-fixed-thirty',1)",batch);
    db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,?,?,?,?,?)",file,batch,text(c,"caseId"),text(c,"caseId")+".md",text(c,"originalPath"),text(c,"originalSha256"),"Native "+text(c,"caseId"));repo.queueBatch(batch,Instant.now());
    c.put("status","PREPARED_NOT_EXECUTED");c.put("checks",new LinkedHashMap<String,Boolean>());results.add(c);
  }
  @SuppressWarnings("unchecked") static Map<String,Boolean> checks(Map<String,Object> c){return (Map<String,Boolean>)c.get("checks");}
  static void dispatch(Map<String,Object> c,RedisRawDeliveryQueue q){String message=q.append(text(c,"fileId"));repo.rawDispatched(repo.rawDispatchable(100).stream().filter(row->row.fileId().equals(text(c,"fileId"))).findFirst().orElseThrow(),message);c.put("messageId",message);}
  static RawTaskClaim claim(Map<String,Object> c){var claim=repo.claimRaw(text(c,"fileId"),id()).orElseThrow();repo.rawExecution(claim).bind(text(c,"documentId"),text(c,"versionId"));return claim;}
  static void stage(Map<String,Object> c,RawTaskClaim claim)throws Exception {c.put("stagedPhysical",index.stageRaw(text(c,"documentId"),text(c,"versionId"),claim.scope(),List.of(chunk(c,true))));}
  static void publish(Map<String,Object> c,RawTaskClaim claim)throws Exception {
    var exec=repo.rawExecution(claim);exec.phase(ProgressStage.ACTIVATING);var physical=index.stageRaw(text(c,"documentId"),text(c,"versionId"),claim.scope(),List.of(chunk(c,false)));
    exec.publish(text(c,"documentId"),text(c,"versionId"),physical,()->index.activateRaw(text(c,"documentId"),text(c,"versionId"),physical));
    repo.rawComplete(claim,text(c,"documentId"),text(c,"versionId"));c.put("currentPhysical",physical);c.put("publicationScope",claim.scope());
  }
  static Map<String,Object> ownerFact(Map<String,Object> c){return db.queryForMap("SELECT owner_id,execution_epoch,attempt,stage_started_at,stage_deadline,execution_stage,status FROM ingestion_file WHERE file_id=?",text(c,"fileId"));}
  static RawTaskClaim reconstruct(Map<String,Object> c){var row=ownerFact(c);return new RawTaskClaim(repo.findFile(text(c,"fileId")).orElseThrow(),row.get("owner_id").toString(),((Number)row.get("execution_epoch")).longValue(),((Timestamp)row.get("stage_deadline")).toInstant());}
  static void save(String status)throws Exception {report.put("status",status);report.put("cases",results);report.put("observedAt",Instant.now().toString());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);}
  static void child(Map<String,Object> c,Path detail)throws Exception {
    var q=queue(c);var delivery=q.read(1).get(0);if(!delivery.fileId().equals(text(c,"fileId")))throw new IllegalStateException("Exact independent source required");
    var owner=claim(c);String fault=text(c,"faultPosition");
    if(fault.equals("STORING"))repo.rawExecution(owner).phase(ProgressStage.STORING);
    if(fault.equals("PARSING"))repo.rawExecution(owner).phase(ProgressStage.PARSING);
    if(fault.equals("STAGED_VECTOR")||fault.equals("UNCOMMITTED_PUBLICATION")){repo.rawExecution(owner).phase(ProgressStage.EMBEDDING);stage(c,owner);}
    Runnable record=()->{try{c.put("actualOwner",ownerFact(c));c.put("pid",ProcessHandle.current().pid());c.put("actualExitCode",71);JSON.writerWithDefaultPrettyPrinter().writeValue(detail.toFile(),c);}catch(Exception error){throw new IllegalStateException(error);}};
    if(fault.equals("UNCOMMITTED_PUBLICATION")){
      tx.executeWithoutResult(ignored->{try{publish(c,owner);record.run();Runtime.getRuntime().halt(71);}catch(Exception error){throw new IllegalStateException(error);}});
    } else {if(text(c,"group").equals("RESULT_UNACKED"))publish(c,owner);record.run();Runtime.getRuntime().halt(71);}
  }
  static void crash(Map<String,Object> c)throws Exception {
    Path config=output.resolveSibling(text(c,"caseId")+".launch.json"),detail=output.resolveSibling(text(c,"caseId")+".crash.json"),log=output.resolveSibling(text(c,"caseId")+".crash.log");JSON.writeValue(config.toFile(),c);
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-XX:ActiveProcessorCount=2","-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]","-cp",System.getProperty("java.class.path"),"RawStreamThirtyProbe",detail.toString(),namespace,"crash",config.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();int exit=process.waitFor();
    if(!Files.exists(detail))throw new IllegalStateException("Owned helper failed before saved crash proof; inspect "+log.getFileName());
    var actual=JSON.readValue(detail.toFile(),Map.class);c.put("crashProof",actual);checks(c).put("actualOwnedJvmAbruptExit",exit==71&&((Number)actual.get("pid")).longValue()==process.pid());
  }
  static boolean staleRejected(RawTaskClaim owner){try{repo.rawExecution(owner).check();return false;}catch(IllegalStateException failure){return failure.getMessage().contains("RAW_STALE_CLAIM");}}
  static void verify(Map<String,Object> c)throws Exception {
    var checks=checks(c);String file=text(c,"fileId"),version=text(c,"versionId");String expected=text(c,"expectedTerminal");
    var pending=redis.opsForStream().pending(text(c,"stream"),"fixed-thirty");checks.put("exactPelDrainedAfterDurableFact",pending.getTotalPendingMessages()==0);
    checks.put("nativeStreamBodyRetained",redis.opsForStream().size(text(c,"stream"))>=1);
    long publications=count("SELECT count(*) FROM raw_publication WHERE version_id=?",version);
    var hits=index.searchVector(vector(true),new SearchFilters(text(c,"caseId"),null,null,List.of(version)),1).stream().map(value->value.id()).toList();
    if(expected.equals("SUCCEEDED_COMPONENT_PUBLICATION")){
      var pointer=db.queryForMap("SELECT producer_scope,execution_epoch FROM raw_publication WHERE version_id=?",version);var physical=db.queryForList("SELECT physical_id FROM raw_publication_member WHERE version_id=?",String.class,version);
      checks.put("oneCurrentEffectivePublicationAndMembership",publications==1&&physical.size()==1&&hits.equals(physical));
      checks.put("actualSucceededAttemptMatchesExpectedOwnerEpoch",repo.findFile(file).orElseThrow().status().name().equals("SUCCEEDED")&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",file)==((text(c,"group").equals("CLAIM_EXIT")||text(c,"group").equals("EXPIRED_OWNER")||text(c,"faultPosition").equals("XPENDING_CONNECTION_CLOSED"))?2:1));
      c.put("actualPublication",pointer);c.put("actualCurrentPhysical",physical);
    }else{
      checks.put("poisonNeverCreatesEffectivePublicationOrRecall",publications==0&&hits.isEmpty());
      if(expected.equals("FAILED_ISOLATED"))checks.put("deterministicFailureIsolatedOnce",repo.findFile(file).orElseThrow().status().name().equals("FAILED")&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",file)==1&&count("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND outcome='ISOLATED'",file)==1);
      else checks.put("invalidDeliveryRecordedWithoutBusinessAttempt",count("SELECT count(*) FROM raw_ingestion_delivery_failure WHERE message_id=?",text(c,"messageId"))==1&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",file)==0);
    }
    if(text(c,"faultPosition").equals("MISSING_ORIGINAL")){
      try(var unused=objects.getObject(GetObjectArgs.builder().bucket("evokb-target").object(text(c,"objectKey")).build())){checks.put("missingOriginalRemainsAbsent",false);}catch(io.minio.errors.ErrorResponseException error){checks.put("missingOriginalRemainsAbsent",error.errorResponse().code().equals("NoSuchKey"));}
    }else{byte[] bytes;try(var stream=objects.getObject(GetObjectArgs.builder().bucket("evokb-target").object(text(c,"objectKey")).build())){bytes=stream.readAllBytes();}checks.put("nativeOriginalFullShaPreserved",cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).equals(text(c,"originalSha256")));}
    c.put("actualFile",db.queryForMap("SELECT file_id,status,attempt,execution_epoch,isolated,error_code FROM ingestion_file WHERE file_id=?",file));
    c.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_COMPONENT_CASE":"FAIL_COMPONENT_CASE");
  }

  /** Socket-level isolated relay only; uses framework Redis clients, never parses/implements RESP. */
  static final class Relay implements AutoCloseable {
    final ServerSocket server;final Set<Socket> active=ConcurrentHashMap.newKeySet();volatile String mode="NORMAL";volatile boolean closed;AtomicInteger droppedReplies=new AtomicInteger();
    Relay()throws Exception{server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));Thread accept=new Thread(()->{while(!closed){try{Socket client=server.accept();if(mode.equals("CLOSE")){client.close();continue;}Socket upstream=new Socket(Proxy.NO_PROXY);upstream.connect(new InetSocketAddress("127.0.0.1",16385),1000);active.add(client);active.add(upstream);copy(client,upstream,false);copy(upstream,client,true);}catch(Exception ignored){if(closed)return;}}},"owned-redis-fault-relay");accept.setDaemon(true);accept.start();}
    void copy(Socket from,Socket to,boolean reply){Thread t=new Thread(()->{try{byte[] buffer=new byte[8192];int n;while((n=from.getInputStream().read(buffer))>=0){if(reply&&mode.equals("DROP_REPLY")){droppedReplies.incrementAndGet();continue;}to.getOutputStream().write(buffer,0,n);to.getOutputStream().flush();}}catch(Exception ignored){}finally{try{from.close();to.close();}catch(Exception ignored){}active.remove(from);active.remove(to);}},"owned-redis-fault-wire");t.setDaemon(true);t.start();}
    void mode(String value){mode=value;if(value.equals("CLOSE")||value.equals("NORMAL")){for(Socket s:active)try{s.close();}catch(Exception ignored){}}}
    public void close()throws Exception{closed=true;server.close();for(Socket s:active)s.close();}
  }
  static boolean actualFault(Runnable action){try{action.run();return false;}catch(RuntimeException error){return true;}}
  static void network(Map<String,Object> c)throws Exception {
    try(var relay=new Relay()){
      var f=factory(relay.server.getLocalPort());try{
        var r=template(f);var q=queue(c,r);q.read(1); // Healthy framework group/connection before precise fault point.
        String fault=text(c,"faultPosition");
        if(fault.equals("XADD_CONNECTION_CLOSED")){
          relay.mode("CLOSE");checks(c).put("actualRedisNetworkOperationFails",actualFault(()->q.append(text(c,"fileId"))));checks(c).put("dbDispatchIntentSurvivesFailedXadd",count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=? AND dispatched_at IS NULL",text(c,"fileId"))==1&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",text(c,"fileId"))==0);
          relay.mode("NORMAL");var healthy=queue(c);dispatch(c,healthy);healthy.read(1);publish(c,claim(c));healthy.acknowledge(text(c,"messageId"));
        }else if(fault.equals("XADD_RESPONSE_LOST")){
          relay.mode("DROP_REPLY");checks(c).put("actualRedisNetworkOperationFails",actualFault(()->q.append(text(c,"fileId"))));checks(c).put("nativeXaddCommittedDespiteClientResponseLoss",relay.droppedReplies.get()>0&&redis.opsForStream().size(text(c,"stream"))>=1&&count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=? AND dispatched_at IS NULL",text(c,"fileId"))==1);
          relay.mode("NORMAL");var healthy=queue(c);dispatch(c,healthy);var rows=healthy.read(100);publish(c,claim(c));checks(c).put("responseLossReplayCannotClaimAnotherAttempt",repo.claimRaw(text(c,"fileId"),id()).isEmpty());for(var row:rows)healthy.acknowledge(row.id());
        }else{
          dispatch(c,q);
          if(fault.equals("XREADGROUP_CONNECTION_CLOSED")){
            relay.mode("CLOSE");checks(c).put("actualRedisNetworkOperationFails",actualFault(()->q.read(1)));checks(c).put("noBusinessAttemptBeforeSuccessfulDelivery",count("SELECT attempt FROM ingestion_file WHERE file_id=?",text(c,"fileId"))==0);relay.mode("NORMAL");var healthy=queue(c);var rows=healthy.read(100);if(rows.isEmpty())rows=healthy.recover(100);publish(c,claim(c));for(var row:rows)healthy.acknowledge(row.id());
          }else{
            q.read(1);var owner=claim(c);
            if(fault.equals("XACK_CONNECTION_CLOSED"))publish(c,owner);
            relay.mode("CLOSE");checks(c).put("actualRedisNetworkOperationFails",actualFault(()->{if(fault.equals("XACK_CONNECTION_CLOSED"))q.acknowledge(text(c,"messageId"));else q.recover(100);}));relay.mode("NORMAL");
            if(fault.equals("XACK_CONNECTION_CLOSED")){var healthy=queue(c);checks(c).put("failedAckRetainsTerminalAndRecoverablePel",healthy.recover(100).size()==1&&repo.claimRaw(text(c,"fileId"),id()).isEmpty());healthy.acknowledge(text(c,"messageId"));}
            else{oldOwners.put(text(c,"caseId"),owner);c.put("actualOwner",ownerFact(c));checks(c).put("pendingFaultCannotStealOrRenewOwner",queue(c).recover(100).isEmpty()&&count("SELECT attempt FROM ingestion_file WHERE file_id=?",text(c,"fileId"))==1);}
          }
        }
      }finally{f.destroy();}
    }
  }
  static void execute(Map<String,Object> c)throws Exception {
    var q=queue(c);String group=text(c,"group"),fault=text(c,"faultPosition");
    if(group.equals("REDIS_NETWORK")){network(c);return;}
    if(group.equals("POISON")&&((Number)c.get("variant")).intValue()<=3){
      Map<String,String> body=fault.equals("MISSING_FILE_FIELD")?Map.of("wrong_field","missing-file-id"):Map.of("file_id",fault.equals("BLANK_FILE_ID")?" ":id());q.read(1);String message=redis.opsForStream().add(text(c,"stream"),body).getValue();c.put("messageId",message);q.read(1);var recovered=q.recover(100);if(fault.equals("UNKNOWN_FILE_ID")){checks(c).put("unknownDeliveryCannotClaimKnownTask",recovered.size()==1&&repo.claimRaw(recovered.get(0).fileId(),id()).isEmpty());repo.rawRejectDelivery(message,recovered.get(0).fileId());q.acknowledge(message);}return;
    }
    dispatch(c,q);
    if(group.equals("CLAIM_EXIT")||group.equals("RESULT_UNACKED")){
      crash(c);if(group.equals("CLAIM_EXIT")){oldOwners.put(text(c,"caseId"),reconstruct(c));c.put("actualOwner",ownerFact(c));checks(c).put("crashHasNoCommittedPublication",count("SELECT count(*) FROM raw_publication WHERE version_id=?",text(c,"versionId"))==0);}else{var recovered=queue(c).recover(100);checks(c).put("terminalReplayOnlyAcknowledgesWithoutNewAttempt",recovered.size()==1&&repo.claimRaw(text(c,"fileId"),id()).isEmpty());queue(c).acknowledge(text(c,"messageId"));}return;
    }
    if(group.equals("DUPLICATE")){
      int n=fault.equals("TRIPLE_DELIVERY")?3:2;for(int i=1;i<n;i++)q.append(text(c,"fileId"));var delivered=q.read(100);RawTaskClaim owner;
      if(fault.equals("CONCURRENT_CLAIM")){var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(()->repo.claimRaw(text(c,"fileId"),id()));var second=pool.submit(()->repo.claimRaw(text(c,"fileId"),id()));var x=first.get();var y=second.get();checks(c).put("concurrentDeliveryOneOwner",x.isPresent()!=y.isPresent());owner=x.orElseGet(y::orElseThrow);repo.rawExecution(owner).bind(text(c,"documentId"),text(c,"versionId"));}finally{pool.shutdown();}}
      else owner=claim(c);
      checks(c).put("duplicatesDoNotConsumeAnotherBusinessAttempt",delivered.size()==n&&repo.claimRaw(text(c,"fileId"),id()).isEmpty());publish(c,owner);for(var message:delivered)q.acknowledge(message.id());
      if(fault.equals("TERMINAL_REPLAY")){String again=q.append(text(c,"fileId"));q.read(1);checks(c).put("postTerminalReplayHasNoNewClaim",repo.claimRaw(text(c,"fileId"),id()).isEmpty());q.acknowledge(again);}return;
    }
    q.read(1);var owner=claim(c);
    if(group.equals("EXPIRED_OWNER")){if(!fault.equals("STARTING"))repo.rawExecution(owner).phase(ProgressStage.valueOf(fault));stage(c,owner);oldOwners.put(text(c,"caseId"),owner);c.put("actualOwner",ownerFact(c));return;}
    if(fault.equals("MISSING_ORIGINAL")){boolean absent=false;try(var ignored=objects.getObject(GetObjectArgs.builder().bucket("evokb-target").object(text(c,"objectKey")).build())){}catch(io.minio.errors.ErrorResponseException error){absent=error.errorResponse().code().equals("NoSuchKey");}checks(c).put("actualNativeOriginalMissingBeforeFailure",absent);repo.rawFailure(owner,"STAGING_FILE_MISSING","Actual isolated fixture original absent",false);}
    else{stage(c,owner);db.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id=?",text(c,"versionId"));boolean rejected=false;try{publish(c,owner);}catch(IllegalStateException error){rejected=error.getMessage().contains("RAW_SOURCE_REVOKED");}checks(c).put("currentAclPreventsStalePublication",rejected);repo.rawFailure(owner,"RAW_SOURCE_REVOKED","Actual isolated source ACL changed",false);}
    q.acknowledge(text(c,"messageId"));
  }
  @SuppressWarnings("unchecked") static void resumeOriginal(Path previous)throws Exception {
    var prior=JSON.readValue(previous.toFile(),Map.class);
    if(!namespace.equals(text(prior,"postgresSchema"))||!text(prior,"status").equals("FAIL_FIXED_STREAM30_NATIVE"))throw new IllegalStateException("Exact retained failed-run schema required");
    var originalCases=(List<Map<String,Object>>)prior.get("cases");if(originalCases.size()!=30)throw new IllegalStateException("Original thirty independent cases required");
    report.put("boundary","Continuation of original frozen30 after an unplanned PG/Milvus outage; same source/file/version/streams/native database. No fixtures recreated, no owner/clock/deadline SQL injection, no new model/parser result. New normal native claim/publication only after fixed600 expiry. The original failed run and its source binding remain separate.");
    report.put("originalFailedReportSha256",cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(Files.readString(previous)));
    report.put("originalFailure",prior.get("failure"));report.put("originalExecutionJarSha256",prior.get("jarSha256"));report.put("frozenManifest",prior.get("frozenManifest"));report.put("manifestSha256",prior.get("manifestSha256"));report.put("originalDeadlineSamples",prior.get("deadlineSamples"));report.put("caseCount",30);report.put("milvusDatabase",namespace);
    for(var c:originalCases){var copy=new LinkedHashMap<String,Object>(c);copy.put("originalCapturedOwner",c.get("actualOwner"));results.add(copy);}
    for(var c:results)if(text(c,"status").equals("WAITING_ACTUAL600")){
      var captured=(Map<String,Object>)c.get("actualOwner");var facts=ownerFact(c);var owner=reconstruct(c);Instant start=((Timestamp)facts.get("stage_started_at")).toInstant(),deadline=((Timestamp)facts.get("stage_deadline")).toInstant();
      boolean same=text(facts,"owner_id").equals(text(captured,"owner_id"))&&((Number)facts.get("execution_epoch")).longValue()==((Number)captured.get("execution_epoch")).longValue()&&((Timestamp)facts.get("stage_started_at")).getTime()==((Number)captured.get("stage_started_at")).longValue()&&((Timestamp)facts.get("stage_deadline")).getTime()==((Number)captured.get("stage_deadline")).longValue();
      checks(c).put("unplannedOutagePreservedOriginalOwnerAndDeadline",same);checks(c).put("actualFixed600WithoutRenewal",Duration.between(start,deadline).equals(Duration.ofSeconds(600))&&!now().isBefore(deadline));checks(c).put("expiredOwnerRejectedBeforeReplacement",staleRejected(owner));
      if(!same||checks(c).values().stream().anyMatch(Boolean.FALSE::equals))throw new IllegalStateException("Preserved old owner facts required before controlled recovery: "+text(c,"caseId"));
      oldOwners.put(text(c,"caseId"),owner);c.put("originalClaimClock",Map.of("stageStartedAt",start.toString(),"stageDeadline",deadline.toString(),"actualRecoveryDbNow",now().toString()));
    }
    report.put("continuedExpiredCases",oldOwners.size());if(oldOwners.size()!=11)throw new IllegalStateException("Exactly original eleven unfinished owners required");save("RUNNING_ORIGINAL_STREAM30_RECOVERY");
    repo.resetInterrupted(Instant.now());
    for(var c:results)if(oldOwners.containsKey(text(c,"caseId"))){
      var old=oldOwners.get(text(c,"caseId"));var recovered=queue(c).recover(100);var next=claim(c);checks(c).put("actualExpiredRecoveryOneNewEpoch",recovered.size()==1&&next.epoch()==2&&next.file().attempt()==2);publish(c,next);
      index.stageRaw(text(c,"documentId"),text(c,"versionId"),old.scope(),List.of(chunk(c,true)));checks(c).put("lateOldOwnerCannotChangeCurrentDbScope",staleRejected(old)&&count("SELECT count(*) FROM raw_publication WHERE version_id=? AND producer_scope=?",text(c,"versionId"),next.scope())==1);queue(c).acknowledge(text(c,"messageId"));c.put("status","EXECUTED_AWAITING_READBACK");save("RUNNING_ORIGINAL_STREAM30_READBACK");
    }
    for(var c:results){verify(c);save("RUNNING_ORIGINAL_STREAM30_READBACK");}
    report.put("executedCases",30);report.put("passedCases",results.stream().filter(c->text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("developmentPassed",results.stream().filter(c->text(c,"split").equals("development")&&text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("holdoutPassed",results.stream().filter(c->text(c,"split").equals("holdout")&&text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("assertionCount",results.stream().mapToInt(c->checks(c).size()).sum());report.put("actualPublicationCount",count("SELECT count(*) FROM raw_publication"));report.put("actualNativeIndexRows",milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"RAW\"").outputFields(List.of("id")).limit(200).build()).getQueryResults().size());
    save(results.stream().allMatch(c->text(c,"status").equals("PASS_COMPONENT_CASE"))?"PASS_ORIGINAL_STREAM30_RECOVERED_NATIVE":"FAIL_ORIGINAL_STREAM30_RECOVERED_NATIVE");
  }
  @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
    output=Path.of(args[0]);namespace=args[1];boolean child=args.length>2&&args[2].equals("crash");preserveExisting=args.length>3&&args[2].equals("resume");open(child||preserveExisting);
    if(child){child(JSON.readValue(Path.of(args[3]).toFile(),Map.class),output);return;}
    if(args.length>3&&args[2].equals("resume")){
      try{resumeOriginal(Path.of(args[3]));}catch(Throwable failure){report.put("failure",failure.toString());try(var writer=new PrintWriter(output+".failure.log")){failure.printStackTrace(writer);}save("FAIL_ORIGINAL_STREAM30_RECOVERED_NATIVE");}finally{close();}
      System.exit(text(report,"status").startsWith("PASS")?0:1);return;
    }
    report.put("boundary","Frozen20dev/10holdout distinct cases/nativePG Redis MinIO Milvus, explicit synthetic1024 vectors and source initialization. Real separate JVM halt71, real owned loopback network failures, actual fixed600 wait, no SQL clock/deadline injection, no production restart or model/parser/efficiency success claim.");
    try{
      Path manifestPath=Path.of(args[2]);var manifest=JSON.readValue(manifestPath.toFile(),Map.class);report.put("frozenManifest",manifest);report.put("caseCount",30);report.put("manifestSha256",cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(Files.readString(manifestPath)));report.put("milvusDatabase",namespace);
      var cases=(List<Map<String,Object>>)manifest.get("cases");if(cases.size()!=30||cases.stream().filter(c->text(c,"split").equals("development")).count()!=20)throw new IllegalStateException("Frozen30/20/10 required");
      for(var c:cases)fixture(new LinkedHashMap<>(c));save("RUNNING_FIXED_STREAM30");
      var ordered=new ArrayList<>(results);ordered.sort(Comparator.comparing(c->text(c,"split"))); // development precedes holdout, artifact unchanged.
      for(var c:ordered){execute(c);c.put("status",oldOwners.containsKey(text(c,"caseId"))?"WAITING_ACTUAL600":"EXECUTED_AWAITING_READBACK");save("RUNNING_FIXED_STREAM30");}
      var samples=new ArrayList<Object>();report.put("deadlineSamples",samples);
      Instant last=oldOwners.keySet().stream().map(id->results.stream().filter(c->text(c,"caseId").equals(id)).findFirst().orElseThrow()).map(c->((Timestamp)ownerFact(c).get("stage_deadline")).toInstant()).max(Comparator.naturalOrder()).orElseThrow();report.put("latestRealDeadline",last.toString());
      while(now().isBefore(last)){
        boolean unchanged=true;for(var c:results)if(oldOwners.containsKey(text(c,"caseId"))){var facts=ownerFact(c);var initial=(Map<String,Object>)c.get("actualOwner");unchanged&=facts.get("owner_id").equals(initial.get("owner_id"))&&facts.get("stage_deadline").equals(initial.get("stage_deadline"));queue(c).recover(100);}
        samples.add(Map.of("databaseNow",now().toString(),"allOriginalOwnersDeadlinesUnchanged",unchanged));if(!unchanged)throw new IllegalStateException("Fixed ownership changed before scheduled recovery");save("RUNNING_FIXED_STREAM30_REAL600");Thread.sleep(15000);
      }
      for(var c:results)if(oldOwners.containsKey(text(c,"caseId"))){
        var owner=oldOwners.get(text(c,"caseId"));var original=(Map<String,Object>)c.get("actualOwner");Instant start=((Timestamp)original.get("stage_started_at")).toInstant(),deadline=((Timestamp)original.get("stage_deadline")).toInstant();checks(c).put("actualFixed600WithoutRenewal",Duration.between(start,deadline).equals(Duration.ofSeconds(600))&&!now().isBefore(deadline));checks(c).put("expiredOwnerRejectedBeforeReplacement",staleRejected(owner));
        repo.resetInterrupted(Instant.now());var recovered=queue(c).recover(100);var next=claim(c);checks(c).put("actualExpiredRecoveryOneNewEpoch",recovered.size()==1&&next.epoch()==2&&next.file().attempt()==2);publish(c,next);
        index.stageRaw(text(c,"documentId"),text(c,"versionId"),owner.scope(),List.of(chunk(c,true)));checks(c).put("lateOldOwnerCannotChangeCurrentDbScope",staleRejected(owner)&&count("SELECT count(*) FROM raw_publication WHERE version_id=? AND producer_scope=?",text(c,"versionId"),next.scope())==1);queue(c).acknowledge(text(c,"messageId"));
      }
      for(var c:results){verify(c);save("RUNNING_FIXED_STREAM30_READBACK");}
      report.put("executedCases",results.size());report.put("passedCases",results.stream().filter(c->text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("developmentPassed",results.stream().filter(c->text(c,"split").equals("development")&&text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("holdoutPassed",results.stream().filter(c->text(c,"split").equals("holdout")&&text(c,"status").equals("PASS_COMPONENT_CASE")).count());report.put("assertionCount",results.stream().mapToInt(c->checks(c).size()).sum());report.put("actualPublicationCount",count("SELECT count(*) FROM raw_publication"));report.put("actualNativeIndexRows",milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"RAW\"").outputFields(List.of("id")).limit(200).build()).getQueryResults().size());
      save(results.stream().allMatch(c->text(c,"status").equals("PASS_COMPONENT_CASE"))?"PASS_FIXED_STREAM30_NATIVE":"FAIL_FIXED_STREAM30_NATIVE");
    }catch(Throwable failure){report.put("failure",failure.toString());try(var writer=new PrintWriter(output+".failure.log")){failure.printStackTrace(writer);}save("FAIL_FIXED_STREAM30_NATIVE");}
    finally{close();}
    System.exit(text(report,"status").startsWith("PASS")?0:1);
  }
}
