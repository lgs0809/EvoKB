package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.infrastructure.parser.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.raw.*;
import cn.lgs.knowledgebase.infrastructure.search.*;
import cn.lgs.knowledgebase.infrastructure.storage.*;
import cn.lgs.knowledgebase.infrastructure.upload.*;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real retained PG/MinIO/Milvus/whole-token Raw classification; explicit fixture vectors. */
public final class NativeInitialClassificationProbe {
  static JdbcTemplate db; static LocalFileSourceAdapter files; static Path originals;
  static Map<String,Boolean> checks = new LinkedHashMap<>();
  static String id() { return UUID.randomUUID().toString(); }
  static long count(String sql,Object...args) { return db.queryForObject(sql,Long.class,args); }
  static boolean rejected(Runnable action,String type) {
    try { action.run(); return false; } catch(RuntimeException e) { return type.isEmpty() || e.getClass().getSimpleName().equals(type) || Objects.toString(e.getMessage(),"").startsWith(type); }
  }
  static ManagedIngestionApplicationService.StagedDraft draft(String logical,String text,Integer level) throws Exception {
    Path path=originals.resolve(id()+".txt");Files.writeString(path,text);
    return new ManagedIngestionApplicationService.StagedDraft(path.getFileName().toString(),path.getFileName().toString(),path.toString(),logical,"Synthetic classification","Contract","","CONTRACT",null,List.of("development-classification"),level);
  }
  static String sha(byte[] data)throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
  public static void main(String[] args)throws Exception {
    var json=new ObjectMapper().findAndRegisterModules(); var report=new LinkedHashMap<String,Object>();
    Path output=Path.of(args[0]); originals=Path.of(args[1]);Files.createDirectories(originals);
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    var tasks=new JdbcIngestionRepository(db,tx,json);var metadata=new JdbcKnowledgeRepository(db,tx,json);files=new LocalFileSourceAdapter();
    var identity=new JdbcIdentityRepository(db);var managed=new ManagedIngestionApplicationService(tasks,identity,files);
    var now=Instant.now();var admin=new User(id(),"classification-admin","Development administrator",Role.ADMIN,UserStatus.ACTIVE,null,now,now,127);
    var reader=new User(id(),"classification-reader","Development reader",Role.USER,UserStatus.ACTIVE,null,now,now,10);
    var lowAdmin=new User(id(),"classification-low-admin","Development low-clearance administrator",Role.ADMIN,UserStatus.ACTIVE,null,now,now,10);
    for(var actor:List.of(admin,reader,lowAdmin))db.update("INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status,security_clearance) VALUES(?,?,?,'COMPONENT_NOT_LOGIN',?,'ACTIVE',?)",actor.id(),actor.username(),actor.displayName(),actor.role().name(),actor.securityClearance());
    managed.setUserUploadEnabled(admin,true);
    var objects=new MinioObjectStoreAdapter("http://127.0.0.1:19400",System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD"),"evokb-target");
    var adminClient=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());
    adminClient.createDatabase(CreateDatabaseReq.builder().databaseName(args[3]).build());adminClient.close();
    var client=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(args[3]).rpcDeadlineMs(30000).build());
    var store=new MinioMultipartUploadAdapter("http://127.0.0.1:19400","http://127.0.0.1:19400",System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD"),"evokb-target","us-east-1");
    var repo=new JdbcMultipartUploadRepository(db,tx,json,originals);
    var upload=new MultipartUploadApplicationService(repo,store,managed,"native-classification",256L*1024*1024,1024L*1024*1024);
    var token=json.readTree(Path.of(args[2]).toFile());
    var effective=new EffectiveConfiguration("http://127.0.0.1","","gpt-5.6-luna","gpt-5.6-terra",60,"http://127.0.0.1:8110","",token.path("model").asText(),1024,8,60,"http://127.0.0.1:8110","","Qwen3-VL-Reranker",60,Instant.EPOCH);
    cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store configuration=new cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store(){public EffectiveConfiguration current(){return effective;}public EffectiveConfiguration update(UpdateCommand ignored){throw new UnsupportedOperationException();}};
    var expectedByLogical=new HashMap<String,Integer>();var preEncoding=new ArrayList<Map<String,Object>>();
    EmbeddingPort fixture=new EmbeddingPort(){ public int dimensions(){return 1024;} public List<List<Float>> embed(List<String> input){
      for(var row:db.queryForList("SELECT d.logical_name,v.version_id,m.security_level FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id) WHERE v.lifecycle='PENDING'")) {
        String logical=row.get("logical_name").toString();if(expectedByLogical.containsKey(logical)) {
          boolean valid=((Number)row.get("security_level")).intValue()==expectedByLogical.get(logical);
          checks.put("classificationPersistedBeforeEncoderFor-"+logical,valid);preEncoding.add(Map.of("versionId",row.get("version_id"),"securityLevel",row.get("security_level"),"correct",valid));
          if(!valid)throw new IllegalStateException("initial classification absent before encoding");
        }
      }
      return input.stream().map(text->{var vector=new ArrayList<Float>(Collections.nCopies(1024,0f));vector.set(0,1f);return(List<Float>)vector;}).toList();
    }};
    try(var frozen=new FrozenRawEncodingConfiguration(args[2],json,configuration,"whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
      var index=new MilvusKnowledgeIndexAdapter(client,"knowledge_chunks",new JdbcProjectionAuthority(db));
      var raw=new RawIngestionApplicationService(files,objects,metadata,new TextDocumentParserAdapter(json),new JacksonParsedArtifactEncoder(json),index,fixture,null,frozen.tokens(),RawChunkProfile.DEFAULT,new RawVectorEncoder(fixture,frozen.profiles(),new JdbcRawVectorCache(db,json)));
      String secret="Initial classified source "+id();expectedByLogical.put(secret,30);
      var secretDraft=draft(secret,"采购合同：总额900万元。付款需书面验收与发票，缺一不得支付。",30);
      checks.put("nonAdminExplicitClassificationDeniedBeforeBatch",rejected(()->managed.createBatch(reader,1,List.of(secretDraft)),"Forbidden")&&count("SELECT count(*) FROM ingestion_file")==0);
      checks.put("lowAdminExplicitClassificationDeniedBeforeBatchAndSource",rejected(()->managed.createBatch(lowAdmin,1,List.of(secretDraft)),"Forbidden")&&count("SELECT count(*) FROM ingestion_file")==0&&count("SELECT count(*) FROM document")==0);
      for(int level:List.of(9,128)) {var invalid=draft("Invalid "+level,"Synthetic invalid classification",level);checks.put("adminInvalid"+level+"DeniedBeforeBatch",rejected(()->managed.createBatch(admin,1,List.of(invalid)),"IllegalArgumentException")&&count("SELECT count(*) FROM ingestion_file")==0);}
      var first=managed.ingestNow(admin,secretDraft,raw).result();
      checks.put("firstRawPublicationAndAuthorityAre30",count("SELECT count(*) FROM document_metadata m JOIN raw_publication p USING(version_id) JOIN kb_source_authority a USING(version_id) WHERE m.version_id=? AND m.security_level=30 AND p.acl_version=m.acl_version AND a.security_level=30",first.versionId())==1);
      var q=Collections.nCopies(1024,0.01f);
      checks.put("nativeLowClearanceCannotSearchFirstPublication",index.searchVector(q,new SearchFilters(null,null,null,List.of(first.versionId()),10),100).isEmpty());
      checks.put("nativeAdminCanSearchFirstPublication",!index.searchVector(q,new SearchFilters(null,null,null,List.of(first.versionId()),127),100).isEmpty());
      var second=managed.ingestNow(admin,draft(secret,"采购合同修订：总额901万元。付款需书面验收与发票，缺一不得支付。",null),raw).result();
      checks.put("omittedSuccessorClassificationInherits30",count("SELECT count(*) FROM document_metadata WHERE version_id=? AND security_level=30",second.versionId())==1&&index.searchVector(q,new SearchFilters(null,null,null,List.of(second.versionId()),10),100).isEmpty());
      String publicName="Unspecified new source "+id();expectedByLogical.put(publicName,10);
      var publicResult=managed.ingestNow(reader,draft(publicName,"公开资料：年度供应商会面日期为10月18日。",null),raw).result();
      checks.put("oldUnspecifiedUserUploadDefaultsTo10AndIsSearchable",metadata.sourceSecurityLevel(publicResult.versionId()).orElseThrow()==10&&!index.searchVector(q,new SearchFilters(null,null,null,List.of(publicResult.versionId()),10),100).isEmpty());
      var staged=managed.createBatch(admin,1,List.of(draft("Typed staged patch "+id(),"Typed patch synthetic source.",null)));
      String stagedFile=staged.files().get(0).fileId();
      var patch=new ManagedIngestionApplicationService.MetadataPatch(null,null,null,null,null,null,false,List.of(),false,30);
      checks.put("typedStagedPatchPersists30AndReadsItBack",managed.patchMetadata(admin,stagedFile,patch).metadata().securityLevel()==30&&tasks.findFile(stagedFile).orElseThrow().metadata().securityLevel()==30);
      var inheritPatch=new ManagedIngestionApplicationService.MetadataPatch(null,null,null,null,null,null,false,List.of(),false,null,true);
      checks.put("adminCanRestoreStagedInheritanceWithoutMakingVersionPublic",managed.patchMetadata(admin,stagedFile,inheritPatch).metadata().securityLevel()==null&&tasks.findFile(stagedFile).orElseThrow().metadata().securityLevel()==null&&count("SELECT count(*) FROM document WHERE logical_name=?",staged.files().get(0).metadata().logicalName())==0);
      managed.patchMetadata(admin,stagedFile,patch);
      var lowOwned=managed.createBatch(lowAdmin,1,List.of(draft("Low administrator owned patch "+id(),"Low administrator synthetic source.",null)));
      checks.put("lowAdminOwnPatchRejectedBeforeQueuedAdmission",rejected(()->managed.patchMetadata(lowAdmin,lowOwned.files().get(0).fileId(),patch),"Forbidden")&&tasks.findFile(lowOwned.files().get(0).fileId()).orElseThrow().metadata().securityLevel()==null&&tasks.findFile(lowOwned.files().get(0).fileId()).orElseThrow().status()==FileStatus.STAGED);
      var owned=managed.createBatch(reader,1,List.of(draft("Reader owned patch "+id(),"Reader owned synthetic source.",null)));
      checks.put("nonAdminCannotPatchEvenOwnExplicitPublicClassification",rejected(()->managed.patchMetadata(reader,owned.files().get(0).fileId(),new ManagedIngestionApplicationService.MetadataPatch(null,null,null,null,null,null,false,List.of(),false,10)),"Forbidden")&&tasks.findFile(owned.files().get(0).fileId()).orElseThrow().metadata().securityLevel()==null);
      checks.put("nonAdminCannotClearOwnRequestedClassification",rejected(()->managed.patchMetadata(reader,owned.files().get(0).fileId(),inheritPatch),"Forbidden"));
      checks.put("classificationAndClearTogetherRejectedWithoutWrite",rejected(()->managed.patchMetadata(admin,stagedFile,new ManagedIngestionApplicationService.MetadataPatch(null,null,null,null,null,null,false,List.of(),false,30,true)),"Validation")&&tasks.findFile(stagedFile).orElseThrow().metadata().securityLevel()==30);
      tasks.queueBatch(staged.batchId(),Instant.now());
      checks.put("staleMetadataSaveCannotRollQueuedTaskBackToStaged",rejected(()->tasks.saveFile(staged.files().get(0)),"Conflict")&&tasks.findFile(stagedFile).orElseThrow().status()==FileStatus.QUEUED);
      byte[] bytes="分片原件合同：保密金额120.50万元，取得验收与发票后付款。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String multipartName="Multipart classification "+id();expectedByLogical.put(multipartName,30);
      var meta=new FileMetadata(multipartName,"Synthetic classification","Contract","","CONTRACT",null,List.of(),30);
      checks.put("nonAdminMultipartClassificationDeniedBeforeStorageSession",rejected(()->upload.create(reader,new Create("secret.txt","secret.txt",bytes.length,shaUnchecked(bytes),meta)),"Forbidden")&&count("SELECT count(*) FROM kb_upload_session")==0);
      checks.put("lowAdminMultipartClassificationDeniedBeforeStorageSession",rejected(()->upload.create(lowAdmin,new Create("secret.txt","secret.txt",bytes.length,shaUnchecked(bytes),meta)),"Forbidden")&&count("SELECT count(*) FROM kb_upload_session")==0);
      var session=upload.create(admin,new Create("secret.txt","secret.txt",bytes.length,sha(bytes),meta));
      checks.put("nativeSessionMetadataStoresRequestedClassification",repo.find(session.sessionId()).orElseThrow().metadata().securityLevel()==30);
      var signed=upload.sign(admin,session.sessionId(),1,Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(bytes)),session.clientSha256());
      var put=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(signed.url())).header("Content-MD5",signed.headers().get("Content-MD5")).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
      checks.put("actualSignedMinioPartAccepted",put.statusCode()==200);
      var completed=upload.complete(admin,session.sessionId(),session.revision(),store.list(session));
      checks.put("multipartReservationHas30BeforeRawClaim",count("SELECT count(*) FROM document_metadata WHERE version_id=? AND security_level=30",completed.documentVersionId())==1&&tasks.findFile(completed.fileId()).orElseThrow().metadata().securityLevel()==30);
      var claim=tasks.claimRaw(completed.fileId(),id()).orElseThrow();Path saved=Path.of(claim.file().stagingPath());Files.createDirectories(saved.getParent());
      try(var input=objects.get(completed.objectKey()).stream()){Files.copy(input,saved);}
      tasks.rawVerifyOriginal(claim,files.inspect(saved.toString()));
      var mp=raw.ingest(new RawIngestionApplicationService.IngestCommand(saved.toString(),multipartName,"multipart-development",new Metadata("Synthetic classification","Contract",List.of(),"",null,"CONTRACT"),30),null,"",ignored->{},tasks.rawExecution(claim));
      tasks.rawComplete(claim,mp.documentId(),mp.versionId());
      checks.put("multipartFirstPublicationIs30AndReaderExcluded",metadata.sourceSecurityLevel(mp.versionId()).orElseThrow()==30&&count("SELECT count(*) FROM raw_publication WHERE version_id=? AND acl_version=1",mp.versionId())==1&&index.searchVector(q,new SearchFilters(null,null,null,List.of(mp.versionId()),10),100).isEmpty());
      var lateBatch=managed.createBatch(admin,1,List.of(draft("Late publisher "+id(),"Late publication fixture.",30)));
      tasks.queueBatch(lateBatch.batchId(),Instant.now());var late=tasks.claimRaw(lateBatch.files().get(0).fileId(),id()).orElseThrow();var execution=tasks.rawExecution(late);
      var doc=new Document(id(),late.file().metadata().logicalName(),"native-classification");
      var version=new DocumentVersion(id(),doc.id(),1,VersionLifecycle.PENDING,"late.txt","text/plain",1,id(),"component-only","","","","");
      execution.reserveSource(doc.logicalName(),()->{metadata.createPendingVersion(doc,version,new Metadata("","",List.of(),"",null,""),30);execution.bind(doc.id(),version.id());return true;});
      // Isolated fixture-only authority change, not manufacturing workflow success.
      db.update("UPDATE document_metadata SET security_level=40,acl_version=acl_version+1 WHERE version_id=?",version.id());
      checks.put("changedAuthorityRejectsOldEpochPublisher",rejected(()->execution.publish(doc.id(),version.id(),List.of("late-physical-fixture"),()->metadata.activateVersion(doc.id(),version.id())),"RAW_SOURCE_REVOKED")&&count("SELECT count(*) FROM raw_publication WHERE version_id=?",version.id())==0);
      report.put("preEncoderAuthorityObservations",preEncoding);
      report.put("sourceAuthorities",db.queryForList("SELECT d.logical_name,v.version_id,v.lifecycle,m.security_level,m.acl_version,p.acl_version AS publication_acl FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id) LEFT JOIN raw_publication p USING(version_id) ORDER BY d.logical_name,v.version_no"));
      report.put("ingestionStates",db.queryForList("SELECT file_id,status,attempt,execution_epoch,requested_security_level,version_id,claimed_acl_version FROM ingestion_file ORDER BY created_at"));
    }catch(Throwable e){checks.put("nativeClassificationPipelineCompleted",false);report.put("failure",e.toString());try(var log=new java.io.PrintWriter(output+".failure.log")){e.printStackTrace(log);}}
    finally{client.close();store.close();}
    report.put("checks",checks);report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_NATIVE_INITIAL_CLASSIFICATION":"FAIL_NATIVE_INITIAL_CLASSIFICATION");
    report.put("modelRequests",0);report.put("boundary","Frozen source-overlay component test, actual PG/MinIO signed HTTP/Milvus/DJL tokenizer and controlled Raw. Explicit synthetic vectors: no LLM, no deployed application HTTP or browser claim. Fixtures retained in isolated schema/database; source ACL change fixture is explicitly isolated. Normal task success is produced through production owner-fenced methods.");
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
  static String shaUnchecked(byte[] data){try{return sha(data);}catch(Exception e){throw new IllegalStateException(e);}}
}
