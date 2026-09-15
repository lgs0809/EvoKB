import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.infrastructure.search.MilvusKnowledgeIndexAdapter;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import cn.lgs.knowledgebase.application.port.Ports.UploadRepository.ReparseReservation;
import cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity;
import cn.lgs.knowledgebase.application.raw.RawParseCheckpoint;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcKnowledgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual V23 upgrade / constraints / normal reservations / publication fences; synthetic schema only. */
public final class ParserCheckpointPostgresProbe {
  private static final String OLD_SCOPE = "legacy-five:e4";
  private static final String OLD_PHYSICAL = "synthetic-old-physical:e" + sha(OLD_SCOPE).substring(0,24);
  private static final Map<String, Boolean> checks = new LinkedHashMap<>();
  private static void check(String name, boolean pass) {
    checks.put(name, pass);
    if (!pass) throw new AssertionError(name);
  }
  private static String owner(String label) {return java.util.UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8)).toString();}
  private static String sha(String value) { return ParserPipelineIdentity.hash(value.getBytes(StandardCharsets.UTF_8)); }
  private static ParserPipelineIdentity pipeline(String runtime) {
    return new ParserPipelineIdentity(Map.of("parser.name","synthetic-native-component",
        "parser.version","1","parser.format",".txt","runtime.sha256",sha(runtime)));
  }
  private static boolean rejected(Runnable action) {
    try { action.run(); return false; } catch (IllegalStateException expected) { return true; }
  }
  private static boolean forbidden(Runnable action) {
    try { action.run(); return false; }
    catch (cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService.Forbidden expected) {return true;}
  }
  private static boolean uniqueConstraint(Runnable action,String constraint) {
    try { action.run(); return false; }
    catch (org.springframework.dao.DataIntegrityViolationException rejected) {
      Throwable cause=rejected;
      while(cause.getCause()!=null)cause=cause.getCause();
      if(!(cause instanceof org.postgresql.util.PSQLException pg))throw rejected;
      return "23505".equals(pg.getSQLState())&&pg.getServerErrorMessage()!=null
          &&constraint.equals(pg.getServerErrorMessage().getConstraint());
    }
  }
  private static void saveCheckpoint(JdbcKnowledgeRepository repository, ReparseReservation version, String sourceSha, ParserPipelineIdentity current) {
    String body = "Synthetic source: both invoice and acceptance are required.";
    var block = new RawBlock("block-"+version.versionId(),version.versionId(),0,"text",body,body,
        List.of("Synthetic component"),null,null,List.of(),List.of(),List.of("component-native"),sha(body),12);
    var proof = new RawParseCheckpoint(current,
        sourceSha,42,sha(body),sha("fixture structured"),RawParseCheckpoint.blocksSha256(List.of(block)));
    repository.saveParsed(version.versionId(),"synthetic/markdown/"+version.versionId(),
        "synthetic/structured/"+version.versionId(),"synthetic-native-component","1",List.of(block),
        List.of(Evidence.fromBlock("reparse-doc","synthetic/original",block)),proof);
  }
  private static void requireOldServing(String phase,JdbcTemplate jdbc,JdbcKnowledgeRepository knowledge,
      cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority authority,
      cn.lgs.knowledgebase.application.compilation.CompilationPublication captured,
      MilvusKnowledgeIndexAdapter index, List<Float> query) {
    var full=new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters(null,null,null,List.of(),30);
    var low=full.withClearance(10);
    check(phase+"CurrentActiveAuthorityAndRawMembershipStillServeOnlyTheOldVersion",
        knowledge.currentActiveVersion("reparse-doc").orElseThrow().id().equals("legacy-retained")
        &&authority.accessible(List.of("legacy-retained"),full)
        &&!authority.accessible(List.of("legacy-retained"),low)
        &&authority.rawProjectionCurrent(OLD_PHYSICAL,List.of("legacy-retained"))
        &&authority.rawVisibility(full).orElseThrow().get("scopes").contains("legacy-five:e4")
        &&!authority.rawVisibility(low).orElseThrow().get("scopes").contains("legacy-five:e4"));
    check(phase+"ActualMilvusDenseTopKAndBm25KeepTheOldVersionWithCurrentAcl",
        index.searchVector(query,full,1).stream().map(hit->hit.id()).toList().equals(List.of(OLD_PHYSICAL))
        && index.searchBm25("发票 验收",full,10).stream().map(hit->hit.id()).toList().equals(List.of(OLD_PHYSICAL))
        && index.searchVector(query,low,10).isEmpty()
        && index.searchBm25("发票 验收",low,10).isEmpty());
    captured.check();
    check(phase+"CapturedCurrentCompilationInputRemainsAuthoritative",true);
  }

  private static RawIndexDocument vectorChunk(String id, List<Float> vector) {
    String body="发票且验收是付款条件。独立合成原生组件资料。";
    return new RawIndexDocument(id,body,body,List.of("合成组件"),List.of("synthetic-block"),1,1,
        sha(body),vector,List.of(),new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata(
            "component","synthetic",List.of(),"synthetic",null,"synthetic"));
  }

  private static String transport(JdbcTemplate jdbc,String name,long versionNo,String originalSha) {
    String version="transport-"+name,file="upload-"+name,batch="batch-"+name,session="session-"+name;
    jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,'reparse-doc',?,'PENDING','original.txt',42,'UNVERIFIED',?)",version,versionNo,"synthetic/retained-original-"+name);
    jdbc.update("INSERT INTO document_metadata(version_id,security_level,manual_tags) VALUES(?,30,'[]')",version);
    jdbc.update("INSERT INTO ingestion_batch(batch_id,created_by,created_by_username,status,source_count) VALUES(?,'admin','component_admin','QUEUED',1)",batch);
    jdbc.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,status,progress_stage,document_id,version_id) VALUES(?,?,'original.txt','original.txt','/synthetic/transport-staging',42,'UNVERIFIED','Synthetic retained parser original','QUEUED','QUEUED','reparse-doc',?)",file,batch,version);
    jdbc.update("INSERT INTO kb_upload_session(session_id,user_id,username,knowledge_base_id,bucket,object_key,filename,relative_path,expected_bytes,part_size,part_count,client_sha256,metadata_json,status,document_id,document_version_id,batch_id,file_id) VALUES(?,'admin','component_admin','synthetic-component','synthetic-bucket',?,'original.txt','original.txt',42,16777216,1,?,'{}','COMPLETED','reparse-doc',?,?,?)",session,"synthetic/retained-original-"+name,originalSha,version,batch,file);
    jdbc.update("UPDATE ingestion_file SET upload_session_id=? WHERE file_id=?",session,file);
    jdbc.update("INSERT INTO raw_ingestion_outbox(file_id) VALUES(?)",file);
    return file;
  }

  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var report = new LinkedHashMap<String,Object>();
    MilvusClientV2 client=null;
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      var uploads = new JdbcIngestionRepository(jdbc,tx,mapper,Path.of("/synthetic/component/staging"));
      var knowledge = new JdbcKnowledgeRepository(jdbc,tx,mapper);
      String originalSha = sha("X".repeat(42));
      check("actualV23UpgradeRetainsUncertifiedOriginalActiveAndFailedFive",
          jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id='legacy-retained' AND lifecycle='ACTIVE' AND sha256=? AND parser_pipeline_sha256 IS NULL AND parse_checkpoint_json IS NULL",Integer.class,originalSha)==1
          && jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id='legacy-five' AND status='FAILED' AND attempt=5 AND error_code='ORIGINAL_FAILURE'",Integer.class)==1
          && knowledge.parseCheckpoint("legacy-retained").isEmpty());
      check("legacyActualSourceRemainsIndependentlyUnique", uniqueConstraint(() -> jdbc.update(
          "INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('duplicate-legacy','reparse-doc',2,'PENDING','original.txt',42,?,'synthetic/duplicate')",originalSha),"uq_document_sha_legacy"));
      jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('transport-one','reparse-doc',100,'INACTIVE','original.txt',42,'UNVERIFIED','synthetic/transport-one'),('transport-two','reparse-doc',101,'INACTIVE','original.txt',42,'UNVERIFIED','synthetic/transport-two')");
      check("unknownTransportReservationsKeepIndependentUntrustedIdentity",jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id IN ('transport-one','transport-two') AND sha256='UNVERIFIED' AND parser_pipeline_sha256 IS NULL",Integer.class)==2);
      check("ownerRequiresEnabledUploadAndCurrentClearance",forbidden(() -> uploads.reparseSource("owner","legacy-retained")));
      jdbc.update("INSERT INTO enterprise_setting(setting_key,setting_value) VALUES('user_upload_enabled','true') ON CONFLICT(setting_key) DO UPDATE SET setting_value='true'");
      var ownerSource = uploads.reparseSource("owner","legacy-retained");
      check("enabledOriginalOwnerCanObserveRetainedSource",ownerSource.sourceSha256().equals(originalSha)&&ownerSource.sizeBytes()==42);
      check("readableNonOwnerAndLowClearanceAdminCannotReparse",forbidden(() -> uploads.reparseSource("other","legacy-retained"))
          && forbidden(() -> uploads.reparseSource("low-admin","legacy-retained")));
      var adminSource = uploads.reparseSource("admin","legacy-retained");
      jdbc.update("UPDATE enterprise_user SET status='DISABLED' WHERE user_id='admin'");
      check("authorityIsRecheckedAfterExternalParserObservation",forbidden(() -> uploads.reserveReparse("admin",adminSource,pipeline("runtime-A"),Instant.now())));
      jdbc.update("UPDATE enterprise_user SET status='ACTIVE' WHERE user_id='admin'");
      var projectionAuthority=new cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority(jdbc);
      String namespace=args[1];
      var adminClient=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(15000).build());
      try {
        if(adminClient.listDatabases().getDatabaseNames().contains(namespace))throw new IllegalStateException("Native Milvus namespace already exists");
        adminClient.createDatabase(CreateDatabaseReq.builder().databaseName(namespace).build());
      } finally { adminClient.close(); }
      client=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(namespace).rpcDeadlineMs(15000).build());
      var index=new MilvusKnowledgeIndexAdapter(client,"parser_raw_chunks",projectionAuthority);
      var query=new java.util.ArrayList<Float>(java.util.Collections.nCopies(1024,0f));query.set(0,1f);
      var weakerOld=new java.util.ArrayList<>(query);weakerOld.set(1,5f);
      var oldMembers=index.stageRaw("reparse-doc","legacy-retained",OLD_SCOPE,List.of(vectorChunk("synthetic-old-physical",weakerOld)));
      check("ActualRetainedMilvusPhysicalRowMatchesPreUpgradePublicationMember",oldMembers.equals(List.of(OLD_PHYSICAL))
          &&jdbc.queryForObject("SELECT count(*) FROM raw_publication_member WHERE version_id='legacy-retained' AND physical_id=?",Integer.class,OLD_PHYSICAL)==1);
      report.put("milvusDatabase",namespace);
      var generation=new cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository(jdbc,tx,"component-parser");
      generation.queue("legacy-retained",cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.SUMMARY,"",Instant.now());
      var priorTask=generation.claimPending(1,Instant.now()).get(0);
      var priorInput=generation.publication(priorTask);priorInput.captureInputs(List.of("legacy-retained"));
      requireOldServing("beforeNewProcessing",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      var early = uploads.reserveReparse("admin",adminSource,pipeline("runtime-metadata-failure"),Instant.now());
      var earlyClaim = uploads.claimRaw(early.fileId(),owner("component-metadata-failure")).orElseThrow();
      uploads.rawFailure(earlyClaim,"PARSER_IDENTITY_UNAVAILABLE","Synthetic early metadata identity failure",false);
      check("PreparatoryFailureFinalizesOnlyItsUnpublishedProcessingVersionAndRetainsHistory",
          jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id=?",String.class,early.versionId()).equals("FAILED")
          &&jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND attempt=1 AND error_code='PARSER_IDENTITY_UNAVAILABLE'",Integer.class,early.fileId())==1
          &&jdbc.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,early.fileId()).equals("FAILED"));
      requireOldServing("withNewFailedOnly",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      var retrying = uploads.reserveReparse("admin",adminSource,pipeline("runtime-retryable-metadata"),Instant.now());
      var retryClaim1=uploads.claimRaw(retrying.fileId(),owner("component-retry-1")).orElseThrow();
      uploads.rawFailure(retryClaim1,"ORIGINAL_RESTORE_FAILED","Synthetic retryable preparatory transport failure",true);
      check("RetryablePreparatoryFailureRetainsSameTaskPipelineAndNormalTenSecondBackoff",
          jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='QUEUED' AND attempt=1 AND next_attempt_at>clock_timestamp() AND NOT isolated",Integer.class,retrying.fileId())==1
          &&knowledge.findVersionBySourceAndPipeline("reparse-doc",originalSha,pipeline("runtime-retryable-metadata").sha256()).orElseThrow().lifecycle()==cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle.FAILED
          &&uploads.claimRaw(retrying.fileId(),owner("component-retry-2")).isEmpty());
      var sameRetry=uploads.reserveReparse("owner",ownerSource,pipeline("runtime-retryable-metadata"),Instant.now());
      check("NormalReparseWhileWaitingDoesNotResetTheOriginalTaskOrAttempts",sameRetry.reused()
          &&sameRetry.fileId().equals(retrying.fileId())&&jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,retrying.fileId())==1);
      var deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
      java.util.Optional<cn.lgs.knowledgebase.application.raw.RawTaskClaim> resumed;
      do {resumed=uploads.claimRaw(retrying.fileId(),owner("component-retry-2"));if(resumed.isEmpty())Thread.sleep(250);}
      while(resumed.isEmpty()&&System.nanoTime()<deadline);
      var retryClaim2=resumed.orElseThrow(()->new AssertionError("The retained task did not become normally claimable after its ten-second backoff"));
      uploads.rawVerifyOriginal(retryClaim2,new SourceFile("/synthetic/original","original.txt",42,originalSha,"text/plain"),pipeline("runtime-retryable-metadata").sha256());
      // These are the same normal repository operations the Raw application executes after claim.
      knowledge.resetFailedVersion(retrying.versionId(),new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata(null,null,List.of(),null,null,null),false);
      var retryGate=uploads.rawExecution(retryClaim2);retryGate.bind("reparse-doc",retrying.versionId());
      saveCheckpoint(knowledge,retrying,originalSha,pipeline("runtime-retryable-metadata"));
      check("NormalSecondAttemptContinuesTheSameRetainedVersionAndCheckpointWithoutResettingBudget",
          retryClaim2.epoch()==retryClaim1.epoch()+1
          &&jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,retrying.fileId())==2
          &&knowledge.parseCheckpoint(retrying.versionId()).orElseThrow().matchesSourceAndBlocks(pipeline("runtime-retryable-metadata"),originalSha,42,knowledge.rawBlocks(retrying.versionId()))
          &&jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND attempt=1 AND error_code='ORIGINAL_RESTORE_FAILED'",Integer.class,retrying.fileId())==1);
      uploads.rawFailure(retryClaim2,"SYNTHETIC_COMPONENT_STOP","Explicit independent component terminus; no business publication claimed",false);
      requireOldServing("afterNormalSecondAttemptRetainedFailure",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      check("NormalRetryCannotResetTheOriginalFiveAttemptFailure",rejected(()->uploads.retryFile("legacy-five",Instant.now()))
          &&uploads.claimRaw("legacy-five",owner("forbidden-sixth-attempt")).isEmpty()
          &&jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id='legacy-five' AND status='FAILED' AND attempt=5 AND error_code='ORIGINAL_FAILURE'",Integer.class)==1);
      var first = uploads.reserveReparse("admin",adminSource,pipeline("runtime-A"),Instant.now());
      check("realQualifiedReservationCreatesOnlyOneNormalQueuedTask",!first.reused()
          && jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id=? AND sha256=? AND parser_pipeline_sha256=? AND reparse_from_version_id='legacy-retained' AND lifecycle='PENDING'",Integer.class,first.versionId(),originalSha,pipeline("runtime-A").sha256())==1
          && jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND attempt=0 AND status='QUEUED'",Integer.class,first.fileId())==1
          && jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=?",Integer.class,first.fileId())==1);
      check("oldActiveAndOriginalFailedFiveStayUnchangedWhileNewTaskQueues",
          jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id='legacy-retained' AND lifecycle='ACTIVE' AND parser_pipeline_sha256 IS NULL",Integer.class)==1
          && jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id='legacy-five'",Integer.class)==5
          && jdbc.queryForObject("SELECT count(*) FROM kb_source_authority WHERE version_id='legacy-retained'",Integer.class)==1);
      requireOldServing("withNewPending",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      var repeat = uploads.reserveReparse("owner",ownerSource,pipeline("runtime-A"),Instant.now());
      check("sameIdentityReparseReturnsItsOriginalTaskWithoutReset",repeat.reused()&&repeat.fileId().equals(first.fileId())
          && repeat.versionId().equals(first.versionId())&&jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,first.fileId())==0);
      check("qualifiedOriginalPlusActualPipelineIsIndependentlyUnique",uniqueConstraint(() -> jdbc.update(
          "INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key,parser_pipeline_sha256) VALUES('qualified-duplicate','reparse-doc',9000,'PENDING','original.txt',42,?,'synthetic/x',?)",originalSha,pipeline("runtime-A").sha256()),"uq_document_source_pipeline"));
      var firstNumber=jdbc.queryForObject("SELECT version_no FROM document_version WHERE version_id=?",Long.class,first.versionId());
      check("VersionNumberUniquenessRemainsIndependentOfSourceAndPipeline",
          uniqueConstraint(()->jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key,parser_pipeline_sha256) VALUES('duplicate-version-no','reparse-doc',?,'PENDING','original.txt',42,?,'synthetic/another-source',?)",
              firstNumber,sha("different original"),pipeline("different-real-pipeline").sha256()),"uq_document_version_no"));
      var claimA = uploads.claimRaw(first.fileId(),owner("component-owner-a")).orElseThrow();
      uploads.rawVerifyOriginal(claimA,new SourceFile("/synthetic/original","original.txt",42,originalSha,"text/plain"),pipeline("runtime-A").sha256());
      var gateA = uploads.rawExecution(claimA);
      gateA.bind("reparse-doc",first.versionId());
      check("verifiedOriginalBindsTheActualClaimedPipelineWithoutExternalIoInTransaction",
          jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='RUNNING' AND attempt=1 AND claimed_parser_pipeline_sha256=?",Integer.class,first.fileId(),pipeline("runtime-A").sha256())==1
          && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
      check("sourceSizeOrHashMismatchIsRejectedBeforePublication",rejected(() -> uploads.rawVerifyOriginal(claimA,new SourceFile("/synthetic/wrong","original.txt",43,originalSha,"text/plain"),pipeline("runtime-A").sha256())));
      saveCheckpoint(knowledge,first,originalSha,pipeline("runtime-A"));
      var firstMembers=index.stageRaw("reparse-doc",first.versionId(),claimA.scope(),List.of(vectorChunk("synthetic-physical-A",query)));
      requireOldServing("withNewStrongerUnpublishedVector",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      var restored = knowledge.parseCheckpoint(first.versionId()).orElseThrow();
      check("typedCheckpointAndCompleteRawBlocksRoundTripThroughPostgres",
          restored.pipeline().equals(pipeline("runtime-A"))&&restored.matchesSourceAndBlocks(pipeline("runtime-A"),originalSha,42,knowledge.rawBlocks(first.versionId())));
      check("partialOrChangedRawBlocksDoNotReuseEvenWithIdenticalSource",
          !restored.matchesSourceAndBlocks(pipeline("runtime-A"),originalSha,42,List.of())
          && !restored.matchesSourceAndBlocks(pipeline("runtime-B"),originalSha,42,knowledge.rawBlocks(first.versionId())));
      var second = uploads.reserveReparse("admin",adminSource,pipeline("runtime-B"),Instant.now());
      check("actualDifferentPipelineCreatesANewProcessingVersionWithSameImmutableOriginal",!second.reused()&&!second.versionId().equals(first.versionId())
          && jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id=? AND original_object_key='synthetic/original' AND sha256=?",Integer.class,second.versionId(),originalSha)==1);
      boolean lateRejected = rejected(() -> gateA.publish("reparse-doc",first.versionId(),firstMembers,()-> knowledge.activateVersion("reparse-doc",first.versionId())));
      check("olderTaskCannotActivateAfterNewProcessingVersionReservation",lateRejected
          && jdbc.queryForObject("SELECT count(*) FROM raw_publication WHERE version_id=?",Integer.class,first.versionId())==0
          && jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id='legacy-retained'",String.class).equals("ACTIVE"));
      knowledge.markVersionFailed(first.versionId(),"SYNTHETIC_PARSE_FAILURE","Independent retained failure fixture");
      uploads.rawFailure(claimA,"SYNTHETIC_PARSE_FAILURE","Independent retained failure fixture",false);
      requireOldServing("withNewFailedAndAnotherPending",jdbc,knowledge,projectionAuthority,priorInput,index,query);
      var claimB = uploads.claimRaw(second.fileId(),owner("component-owner-b")).orElseThrow();
      uploads.rawVerifyOriginal(claimB,new SourceFile("/synthetic/original","original.txt",42,originalSha,"text/plain"),pipeline("runtime-B").sha256());
      var gateB = uploads.rawExecution(claimB);gateB.bind("reparse-doc",second.versionId());
      check("missingCompleteCheckpointCannotPublishCurrentProcessingVersion",rejected(() -> gateB.publish("reparse-doc",second.versionId(),List.of("synthetic-physical-B"),()->knowledge.activateVersion("reparse-doc",second.versionId()))));
      saveCheckpoint(knowledge,second,originalSha,pipeline("runtime-B"));
      var secondMembers=index.stageRaw("reparse-doc",second.versionId(),claimB.scope(),List.of(vectorChunk("synthetic-physical-B",query)));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id=?",second.versionId());
      check("revokedClaimedAclStillRejectsOtherwiseCurrentCheckpoint",rejected(() -> gateB.publish("reparse-doc",second.versionId(),List.of("synthetic-physical-B"),()->knowledge.activateVersion("reparse-doc",second.versionId()))));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version-1 WHERE version_id=?",second.versionId());
      gateB.publish("reparse-doc",second.versionId(),secondMembers,()-> {
        knowledge.activateVersion("reparse-doc",second.versionId());
        index.activateRaw("reparse-doc",second.versionId(),secondMembers);
      });
      uploads.rawComplete(claimB,"reparse-doc",second.versionId());
      check("normalRepositoryPublicationAtomicallyAdvancesOnlyTheCurrentVersion",
          jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id=?",String.class,second.versionId()).equals("ACTIVE")
          && jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id='legacy-retained'",String.class).equals("INACTIVE")
          && jdbc.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,second.fileId()).equals("SUCCEEDED")
          && jdbc.queryForObject("SELECT count(*) FROM raw_publication_member WHERE version_id=?",Integer.class,second.versionId())==1);
      var fullScope=new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters(null,null,null,List.of(),30);
      check("successfulActivationSwitchesCurrentVersionAndVectorAuthorityTogether",
          knowledge.currentActiveVersion("reparse-doc").orElseThrow().id().equals(second.versionId())
          && !projectionAuthority.accessible(List.of("legacy-retained"),fullScope)
          && !projectionAuthority.rawProjectionCurrent(OLD_PHYSICAL,List.of("legacy-retained"))
          && !projectionAuthority.rawVisibility(fullScope).orElseThrow().get("scopes").contains("legacy-five:e4")
          && projectionAuthority.rawVisibility(fullScope).orElseThrow().get("scopes").contains(claimB.scope())
          && projectionAuthority.rawProjectionCurrent(secondMembers.get(0),List.of(second.versionId()))
          && rejected(priorInput::check));
      check("ActualMilvusNormalAtomicPublicationSwitchesDenseAndBm25VisibilityOnlyOnSuccess",
          index.searchVector(query,fullScope,1).stream().map(hit->hit.id()).toList().equals(secondMembers)
          &&index.searchBm25("发票 验收",fullScope,10).stream().map(hit->hit.id()).toList().equals(secondMembers)
          &&index.searchVector(query,fullScope.withClearance(10),10).isEmpty());
      check("oldTaskAttemptFiveAndLegacyCheckpointRemainUnchangedAfterNewPublication",
          jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id='legacy-five' AND attempt=5 AND status='FAILED' AND error_code='ORIGINAL_FAILURE'",Integer.class)==1
          && knowledge.parseCheckpoint("legacy-retained").isEmpty());
      var repeatedSuccess=uploads.reserveReparse("admin",uploads.reparseSource("admin",second.versionId()),pipeline("runtime-B"),Instant.now());
      check("currentSuccessReparseIsExactIdempotencyNotANewAttempt",repeatedSuccess.reused()&&repeatedSuccess.fileId().equals(second.fileId())
          && jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,second.fileId())==1);
      var duplicateFile=transport(jdbc,"duplicate-current",999,originalSha);
      var duplicateClaim=uploads.claimRaw(duplicateFile,owner("duplicate-owner")).orElseThrow();
      uploads.rawVerifyOriginal(duplicateClaim,new SourceFile("/synthetic/transport","original.txt",42,originalSha,"text/plain"),pipeline("runtime-B").sha256());
      var duplicateGate=uploads.rawExecution(duplicateClaim);
      check("normalDuplicateUploadSelectsOnlyTheExactQualifiedActiveVersion",
          duplicateGate.reservedInput().orElseThrow().id().equals(second.versionId())&&duplicateGate.immutableOriginalReserved()
          &&jdbc.queryForObject("SELECT count(*) FROM document_version WHERE version_id='transport-duplicate-current' AND lifecycle='INACTIVE' AND sha256='UNVERIFIED' AND duplicate_of_version_id=? AND parser_pipeline_sha256 IS NULL",Integer.class,second.versionId())==1
          &&jdbc.queryForObject("SELECT count(*) FROM kb_upload_session WHERE session_id='session-duplicate-current' AND document_version_id='transport-duplicate-current' AND actual_sha256=? AND integrity_status='VERIFIED'",Integer.class,originalSha)==1);
      duplicateGate.bind("reparse-doc",second.versionId());uploads.rawComplete(duplicateClaim,"reparse-doc",second.versionId());
      check("duplicateTransportRetainsOriginalObjectSessionAndCanonicalTaskAttempts",
          jdbc.queryForObject("SELECT original_object_key FROM document_version WHERE version_id='transport-duplicate-current'",String.class).equals("synthetic/retained-original-duplicate-current")
          &&jdbc.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,duplicateFile).equals("SUCCEEDED")
          &&jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,second.fileId())==1
          &&jdbc.queryForObject("SELECT count(*) FROM raw_publication WHERE version_id=? AND file_id=?",Integer.class,second.versionId(),second.fileId())==1);
      var failedDuplicate=transport(jdbc,"duplicate-failure",1001,originalSha);
      var failedDuplicateClaim=uploads.claimRaw(failedDuplicate,owner("duplicate-failure-owner")).orElseThrow();
      uploads.rawVerifyOriginal(failedDuplicateClaim,new SourceFile("/synthetic/transport","original.txt",42,originalSha,"text/plain"),pipeline("runtime-B").sha256());
      uploads.rawFailure(failedDuplicateClaim,"SYNTHETIC_EXTERNAL_FAILURE","Synthetic failure after binding a canonical ACTIVE duplicate",false);
      check("FailureOfADuplicateTaskCannotRevokeTheAlreadyPublishedCanonicalVersion",
          knowledge.currentActiveVersion("reparse-doc").orElseThrow().id().equals(second.versionId())
          &&index.searchVector(query,fullScope,1).stream().map(hit->hit.id()).toList().equals(secondMembers)
          &&jdbc.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,second.fileId()).equals("SUCCEEDED")
          &&jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_failure WHERE file_id=? AND attempt=1",Integer.class,failedDuplicate)==1);
      var unfinishedDuplicate=transport(jdbc,"duplicate-unfinished",1000,originalSha);
      var unfinishedClaim=uploads.claimRaw(unfinishedDuplicate,owner("unfinished-duplicate-owner")).orElseThrow();
      check("duplicatePendingOrFailedPipelineCannotCreateFreshModelAttemptBudget",
          rejected(() -> uploads.rawVerifyOriginal(unfinishedClaim,new SourceFile("/synthetic/transport","original.txt",42,originalSha,"text/plain"),pipeline("runtime-A").sha256()))
          &&jdbc.queryForObject("SELECT attempt FROM ingestion_file WHERE file_id=?",Integer.class,first.fileId())==1
          &&jdbc.queryForObject("SELECT count(*) FROM kb_upload_session WHERE session_id='session-duplicate-unfinished' AND integrity_status='UNVERIFIED'",Integer.class)==1);

      // Two independent real JDBC transactions contend through the normal advisory reservation.
      jdbc.update("INSERT INTO document(document_id,logical_name,status) VALUES('concurrent-doc','Synthetic concurrent pipeline','ACTIVE')");
      jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('concurrent-old','concurrent-doc',1,'ACTIVE','original.txt',42,?,'synthetic/concurrent-original')",originalSha);
      jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES('concurrent-old',10)");
      var concurrentSource=uploads.reparseSource("admin","concurrent-old");
      var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
      try {
        var futures=new java.util.ArrayList<java.util.concurrent.Future<Object>>();
        for(int i=0;i<2;i++) futures.add(pool.submit(()->{latch.await();try{return uploads.reserveReparse("admin",concurrentSource,pipeline("concurrent-runtime"),Instant.now());}catch(cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService.Conflict contested){return contested;}}));
        latch.countDown();var responses=new java.util.ArrayList<Object>();for(var future:futures)responses.add(future.get(10,java.util.concurrent.TimeUnit.SECONDS));
        check("concurrentNormalRequestsCreateExactlyOneQualifiedVersionTaskAndOutbox",
            responses.stream().anyMatch(ReparseReservation.class::isInstance)
            &&jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id='concurrent-doc' AND parser_pipeline_sha256=?",Integer.class,pipeline("concurrent-runtime").sha256())==1
            &&jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE document_id='concurrent-doc'",Integer.class)==1
            &&jdbc.queryForObject("SELECT count(*) FROM raw_ingestion_outbox o JOIN ingestion_file f USING(file_id) WHERE f.document_id='concurrent-doc'",Integer.class)==1);
      } finally {pool.shutdownNow();}
      report.put("status","PASS_REAL_POSTGRES_PARSER_CHECKPOINTS");
      report.put("newNormalTaskIds",List.of(first.fileId(),second.fileId()));
    } catch(Throwable failure) {
      failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_PARSER_CHECKPOINTS");
      report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());
    }
    finally {if(client!=null)client.close();}
    report.put("checks",checks);
    report.put("boundary","Isolated retained synthetic PostgreSQL upgrade/unique constraints/normal owner reservation/claim/checkpoint/publication APIs. Fixture SQL creates or revokes component inputs only. Actual separate Milvus namespace executes production dense/BM25 filters with explicit synthetic1024 vectors and retained physical rows. No business task state rewrite, HTTP/UI, models, MinIO or complete Raw workflow success claim.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
