import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;
import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.infrastructure.upload.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.storage.*;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.*;
import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated PG/MinIO transport and Raw verification components, not browser/model claims. */
public class NativeMultipartUploadProbe {
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static JdbcTemplate db;
  static final Map<String, Boolean> checks = new LinkedHashMap<>();
  static final Map<String, Object> report = new LinkedHashMap<>();
  static final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
  static Path output, staging;
  static MultipartUploadApplicationService service;
  static JdbcMultipartUploadRepository repo;
  static MinioMultipartUploadAdapter store;
  static MinioObjectStoreAdapter originals;
  static LocalFileSourceAdapter files;
  static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  static User admin, other;
  static String id() { return UUID.randomUUID().toString(); }
  static String sha(byte[] input) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
  }
  static String md5(byte[] input) throws Exception {
    return Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(input));
  }
  static FileMetadata meta(String name) {
    return new FileMetadata(name, "Synthetic acceptance", "TRANSPORT", "", "CONTRACT", null, List.of("multipart-native"));
  }
  static Session create(String name, byte[] input, String hash) {
    return service.create(admin, new Create(name + ".txt", name + ".txt", input.length, hash, meta(name)));
  }
  static HttpResponse<String> put(Session session, int number, byte[] bytes, String digest) throws Exception {
    var signed = service.sign(admin, session.sessionId(), number, digest, session.clientSha256());
    return send(session, number, signed.url(), bytes, digest);
  }
  static HttpResponse<String> send(Session session, int number, String url, byte[] bytes, String digest) throws Exception {
    var response = http.send(HttpRequest.newBuilder(URI.create(url))
        .header("Content-MD5", digest).header("Origin", "http://127.0.0.1:18481")
        .timeout(Duration.ofSeconds(60)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
    requests.add(Map.of("sessionId", session.sessionId(), "number", number, "bytes", bytes.length,
        "inputSha256", sha(bytes), "status", response.statusCode(), "at", Instant.now(),
        "signedQueryParameters", URI.create(url).getRawQuery()
          .replaceAll("X-Amz-Signature=[^&]*", "X-Amz-Signature=REDACTED")
          .replaceAll("X-Amz-Credential=[^&]*", "X-Amz-Credential=REDACTED"),
        "response", response.body(), "responseHeaders", response.headers().map()));
    return response;
  }
  static SourceFile actualSource(Session session) throws Exception {
    var original = originals.get(session.objectKey());
    var path = staging.resolve(session.sessionId() + ".txt");
    long bytes = 0;
    try (var source = original.stream(); var target = Files.newOutputStream(path)) {
      byte[] block = new byte[65536];
      for (int size; (size = source.read(block)) != -1;) {
        bytes += size;
        if (bytes > session.sizeBytes()) throw new IllegalStateException("stored original exceeds frozen size");
        target.write(block, 0, size);
      }
    }
    if (bytes != session.sizeBytes()) throw new IllegalStateException("stored original is truncated");
    return files.inspect(path.toString());
  }
  static boolean denied(Runnable action) {
    try { action.run(); return false; } catch (RuntimeException expected) { return true; }
  }
  static boolean deniedCode(Runnable call,String code) {
    try {call.run();return false;} catch(IllegalStateException error) {return error.getMessage()!=null&&error.getMessage().startsWith(code);}
  }
  static long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
  static void save(String status) throws Exception {
    report.put("status", status); report.put("checks", checks); report.put("requests", requests);
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }
  public static void main(String[] args) throws Exception {
    output = Path.of(args[0]); staging = Path.of(args[1]); Files.createDirectories(staging);
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
    db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    repo = new JdbcMultipartUploadRepository(db, tx, json, staging);
    var uploads = new JdbcIngestionRepository(db, tx, json);
    files = new LocalFileSourceAdapter();
    var managed = new ManagedIngestionApplicationService(uploads, new JdbcIdentityRepository(db), files);
    store = new MinioMultipartUploadAdapter("http://127.0.0.1:19400", "http://127.0.0.1:19400",
        System.getenv("PROBE_MINIO_USER"), System.getenv("PROBE_MINIO_PASSWORD"), "evokb-target", "us-east-1");
    originals = new MinioObjectStoreAdapter("http://127.0.0.1:19400", System.getenv("PROBE_MINIO_USER"), System.getenv("PROBE_MINIO_PASSWORD"), "evokb-target");
    service = new MultipartUploadApplicationService(repo, store, managed, "native_" + args[2], 256L * 1024 * 1024, 1024L * 1024 * 1024);
    Instant now = Instant.now();
    admin = new User(id(), "component_admin", "Synthetic uploader", Role.ADMIN, UserStatus.ACTIVE, null, now, now);
    other = new User(id(), "component_other", "Synthetic other uploader", Role.ADMIN, UserStatus.ACTIVE, null, now, now);
    for (var user : List.of(admin, other)) db.update("INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status) VALUES(?,?,?,'COMPONENT_NOT_A_LOGIN','ADMIN','ACTIVE')", user.id(), user.username(), user.displayName());
    report.put("boundary", "Real isolated PostgreSQL/native MinIO SDK/HTTP checks. Interrupted-owner and 24h-expiration fixtures modify only isolated DB boundaries. Actual 600s signature expiry uses elapsed wall time. Actual Raw SHA source is streamed from MinIO then inspected with LocalFileSourceAdapter. No parsing/model/browser/deployed API claim.");
    report.put("startedAt", Instant.now());
    try {
      byte[] small = "Synthetic native multipart SHA test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var expiry = create("native-real-url-expiry-" + id(), small, sha(small));
      var oldUrl = service.sign(admin, expiry.sessionId(), 1, md5(small), expiry.clientSha256());
      long signedAt = System.nanoTime();
      report.put("actualExpiryStart", Instant.now());
      checks.put("actual600SecondUrlInitiallyUsable", send(expiry, 1, oldUrl.url(), small, md5(small)).statusCode() == 200);
      byte[] first = new byte[PART_BYTES]; for (int i = 0; i < first.length; i++) first[i] = (byte)(i % 251);
      byte[] last = new byte[93]; Arrays.fill(last, (byte)77);
      byte[] whole = new byte[first.length + last.length]; System.arraycopy(first, 0, whole, 0, first.length); System.arraycopy(last, 0, whole, first.length, last.length);
      var large = create("native-boundary-" + id(), whole, sha(whole));
      var valid = put(large, 1, first, md5(first));
      checks.put("native16MiBPartSignedLengthAndMd5", valid.statusCode() == 200 && store.list(large).get(0).sizeBytes() == PART_BYTES);
      checks.put("actualPutExposesEtagOnlyToAllowedOrigin", valid.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals("http://127.0.0.1:18481") && valid.headers().firstValue("Access-Control-Expose-Headers").orElse("").toLowerCase(Locale.ROOT).contains("etag"));
      if (!checks.get("native16MiBPartSignedLengthAndMd5")) throw new IllegalStateException("native signed part failed");
      checks.put("lostResponseResumesFromActualListParts", service.view(admin, large.sessionId()).uploadedParts().size() == 1 && repo.find(large.sessionId()).orElseThrow().status() == State.UPLOADING);
      checks.put("reselectedSameNameSizeDifferentDigestDenied", denied(() -> service.sign(admin, large.sessionId(), 2, "AAAAAAAAAAAAAAAAAAAAAA==", "0".repeat(64))));
      checks.put("missingPartCannotEnterCompleting", denied(() -> service.complete(admin, large.sessionId(), large.revision(), store.list(large))) && repo.find(large.sessionId()).orElseThrow().status() == State.UPLOADING);
      checks.put("actualFinalShortPartAccepted", put(large, 2, last, md5(last)).statusCode() == 200 && store.list(large).get(1).sizeBytes() == 93);
      var frozen = store.list(large);
      var completed = service.complete(admin, large.sessionId(), large.revision(), frozen);
      checks.put("normalCompletedHeadMatchesFrozenSessionAndReadyOutbox", completed.status() == State.COMPLETED && store.head(large.objectKey()).orElseThrow().sizeBytes() == whole.length && count("SELECT count(*) FROM raw_ingestion_outbox WHERE file_id=?", completed.fileId()) == 1 && count("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='QUEUED' AND sha256='UNVERIFIED'", completed.fileId()) == 1);
      var duplicate = service.complete(admin, large.sessionId(), large.revision(), frozen);
      checks.put("duplicateCompleteCannotCreateAnotherVersionOrTask", duplicate.documentVersionId().equals(completed.documentVersionId()) && count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?", large.sessionId()) == 1);
      checks.put("otherUploaderCannotReadOrSignOwnedSession", denied(() -> service.view(other, large.sessionId())) && denied(() -> service.sign(other, large.sessionId(), 1, "AAAAAAAAAAAAAAAAAAAAAA==", large.clientSha256())));
      var bad = create("native-bad-md5-" + id(), small, sha(small));
      var corrupt = put(bad, 1, small, "AAAAAAAAAAAAAAAAAAAAAA==");
      checks.put("actualStorageRejectsWrongContentMd5", corrupt.statusCode() == 400 && corrupt.body().contains("BadDigest") && store.list(bad).isEmpty());
      var cancelled = service.cancel(admin, bad.sessionId(), bad.revision());
      checks.put("cancelAbortsOnlyOwnedIncompleteUpload", cancelled.status() == State.ABORTED && cancelled.abortConfirmed() && store.findUploads(bad.objectKey()).isEmpty());
      var crash = create("native-merge-crash-" + id(), small, sha(small));
      if (put(crash, 1, small, md5(small)).statusCode() != 200) throw new IllegalStateException("crash fixture failed");
      var claim = repo.beginComplete(crash.sessionId(), crash.revision(), id(), store.list(crash)).orElseThrow();
      store.complete(claim);
      db.update("UPDATE kb_upload_session SET completion_deadline=clock_timestamp()-interval '1 second' WHERE session_id=?", crash.sessionId());
      service.recover();
      var recovered = repo.find(crash.sessionId()).orElseThrow();
      checks.put("mergeSuccessCrashRecoveredByHeadWithoutDuplicateTask", recovered.status() == State.COMPLETED && store.head(crash.objectKey()).isPresent() && count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?", crash.sessionId()) == 1);
      var expired = create("native-expiry-" + id(), small, sha(small));
      db.update("WITH t AS(SELECT clock_timestamp() AS now) UPDATE kb_upload_session SET created_at=t.now-interval '25 hours',expires_at=t.now-interval '1 hour' FROM t WHERE session_id=?", expired.sessionId());
      service.recover();
      var expiration = repo.find(expired.sessionId()).orElseThrow();
      checks.put("hourlyCleanerAbortsExpiredFixtureAndPreservesOtherOriginal", expiration.status() == State.EXPIRED && expiration.abortConfirmed() && store.findUploads(expired.objectKey()).isEmpty() && store.head(large.objectKey()).isPresent());
      var wrong = create("native-wrong-sha-" + id(), small, "0".repeat(64));
      if (put(wrong, 1, small, md5(small)).statusCode() != 200) throw new IllegalStateException("wrong SHA fixture failed");
      var wrongDone = service.complete(admin, wrong.sessionId(), wrong.revision(), store.list(wrong));
      var owner = uploads.claimRaw(wrongDone.fileId(), id()).orElseThrow();
      SourceFile wrongSource = actualSource(wrongDone);
      boolean mismatch = denied(() -> uploads.rawVerifyOriginal(owner, wrongSource));
      checks.put("actualServerShaGuardRejectsClientClaimBeforeAnyPublication", mismatch && "FAILED".equals(repo.find(wrong.sessionId()).orElseThrow().integrityStatus()) && count("SELECT count(*) FROM raw_publication WHERE version_id=?", wrongDone.documentVersionId()) == 0);
      var current = uploads.claimRaw(completed.fileId(), id()).orElseThrow();
      SourceFile source = actualSource(completed);
      uploads.rawVerifyOriginal(current, source);
      checks.put("serverHashShortOwnerTransactionBindsReservedVersion", uploads.rawExecution(current).reservedInput().orElseThrow().id().equals(completed.documentVersionId()) && sha(whole).equals(repo.find(large.sessionId()).orElseThrow().actualSha256()) && source.sha256().equals(sha(whole)));
      uploads.rawExecution(current).bind(completed.documentId(), completed.documentVersionId());
      checks.put("pendingSourceCannotBeMarkedCompleteBeforeActualPublication", deniedCode(() -> uploads.rawComplete(current, completed.documentId(), completed.documentVersionId()),"RAW_SOURCE_REVOKED") && "RUNNING".equals(db.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?", String.class, completed.fileId())));
      var noHash = create("native-optional-sha-" + id(), small, "");
      if (put(noHash, 1, small, md5(small)).statusCode() != 200) throw new IllegalStateException("optional SHA fixture failed");
      var unverified = service.complete(admin, noHash.sessionId(), noHash.revision(), store.list(noHash));
      boolean sentinel = "UNVERIFIED".equals(db.queryForObject("SELECT sha256 FROM document_version WHERE version_id=?", String.class, unverified.documentVersionId()));
      var uncheckedOwner = uploads.claimRaw(unverified.fileId(), id()).orElseThrow(); uploads.rawVerifyOriginal(uncheckedOwner, actualSource(unverified));
      checks.put("optionalClientHashNeverPublishesSentinelAsVerified", sentinel && repo.find(noHash.sessionId()).orElseThrow().integrityStatus().equals("VERIFIED") && repo.find(noHash.sessionId()).orElseThrow().actualSha256().equals(sha(small)));
      var concurrent = create("native-concurrent-complete-" + id(), small, sha(small));
      if (put(concurrent, 1, small, md5(small)).statusCode() != 200) throw new IllegalStateException("concurrent fixture failed");
      var concurrentParts = store.list(concurrent); var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(4);
      try {
        var futures = new ArrayList<Future<Session>>();
        for (int i = 0; i < 24; i++) futures.add(pool.submit(() -> { gate.await(); return service.complete(admin, concurrent.sessionId(), concurrent.revision(), concurrentParts); }));
        gate.countDown(); for (var future : futures) future.get(60, TimeUnit.SECONDS);
      } finally { pool.shutdownNow(); }
      checks.put("twentyFourConcurrentCompletionsCreateOneTaskAndVersion", repo.find(concurrent.sessionId()).orElseThrow().status() == State.COMPLETED && count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?", concurrent.sessionId()) == 1);
      var deniedName = "native-secret-source-" + id(); var deniedId = id();
      db.update("INSERT INTO document(document_id,logical_name,status) VALUES(? ,?,'ACTIVE')", deniedId, deniedName);
      db.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,'ACTIVE','synthetic.md',0,'COMPONENT_FIXTURE','ISOLATED_COMPONENT_ONLY')", deniedId, deniedId);
      db.update("INSERT INTO document_metadata(version_id,security_level) VALUES(?,30)", deniedId);
      var secret = create(deniedName, small, sha(small));
      if (put(secret, 1, small, md5(small)).statusCode() != 200) throw new IllegalStateException("ACL fixture failed");
      checks.put("currentClearancePreventsOverwritingHigherAclSource", denied(() -> service.complete(admin, secret.sessionId(), secret.revision(), store.list(secret))) && count("SELECT count(*) FROM document_version WHERE document_id=?", deniedId) == 1 && count("SELECT count(*) FROM ingestion_file WHERE upload_session_id=?", secret.sessionId()) == 0 && store.head(secret.objectKey()).isPresent());
      var repeatedDigest = java.security.MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < 4; i++) repeatedDigest.update(first); repeatedDigest.update(last);
      String parallelName = "native-four-parallel-" + id();
      var parallel = service.create(admin, new Create(parallelName + ".txt", parallelName + ".txt", 4L * PART_BYTES + last.length, HexFormat.of().formatHex(repeatedDigest.digest()), meta(parallelName)));
      var partPool = Executors.newFixedThreadPool(4); var partGate = new CountDownLatch(1);
      try {
        var parts = new ArrayList<Future<Integer>>();
        for (int number = 1; number <= 4; number++) {
          int slot = number;
          parts.add(partPool.submit(() -> { partGate.await(); return put(parallel, slot, first, md5(first)).statusCode(); }));
        }
        partGate.countDown(); for (var part : parts) if (part.get(60, TimeUnit.SECONDS) != 200) throw new IllegalStateException("real parallel part failed");
      } finally { partPool.shutdownNow(); }
      if (put(parallel, 5, last, md5(last)).statusCode() != 200) throw new IllegalStateException("parallel last part failed");
      var parallelDone = service.complete(admin, parallel.sessionId(), parallel.revision(), store.list(parallel));
      checks.put("fourConcurrent16MiBPartsAndShortTailUseOneFrozenOriginal", parallelDone.status() == State.COMPLETED && store.head(parallel.objectKey()).orElseThrow().sizeBytes() == 4L * PART_BYTES + last.length && actualSource(parallelDone).sha256().equals(parallel.clientSha256()));
      report.put("retainedSessions", db.queryForList("SELECT session_id,status,integrity_status,actual_sha256,expected_bytes,part_count,expires_at,document_version_id,file_id FROM kb_upload_session ORDER BY created_at"));
      report.put("beforeExpiryWaitAt", Instant.now());
      save("RUNNING_ACTUAL_600_SECOND_URL_EXPIRY");
      if(args.length>3&&args[3].equals("skip-signature-expiry")) {
        service.cancel(admin,expiry.sessionId(),repo.find(expiry.sessionId()).orElseThrow().revision());
        report.put("signatureExpiryBoundary","NOT_REPEATED: separate raw-multipart-native-2 verifies actual603s storage expiry. This run verifies new repository guards.");
        save(checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_NATIVE_MULTIPART_COMPONENTS_WITHOUT_EXPIRY_REPEAT":"FAIL_NATIVE_MULTIPART_COMPONENTS");
        store.close();System.exit(report.get("status").toString().startsWith("PASS")?0:1);
      }
      while ((System.nanoTime() - signedAt) / 1_000_000_000.0 < 603) Thread.sleep(1000);
      var stale = send(expiry, 1, oldUrl.url(), small, md5(small));
      checks.put("actual600SecondSignatureExpiresWithoutExpiring24hSession", stale.statusCode() == 403 && stale.body().contains("Request has expired") && repo.find(expiry.sessionId()).orElseThrow().expiresAt().isAfter(Instant.now()));
      checks.put("expiredUrlReauthenticatedAndRenewed", put(expiry, 1, small, md5(small)).statusCode() == 200);
      service.cancel(admin, expiry.sessionId(), repo.find(expiry.sessionId()).orElseThrow().revision());
      report.put("actualExpiryElapsedSeconds", (System.nanoTime() - signedAt) / 1_000_000_000.0);
      report.put("finishedAt", Instant.now());
      save(checks.values().stream().allMatch(Boolean::booleanValue) ? "PASS_NATIVE_MULTIPART_COMPONENTS" : "FAIL_NATIVE_MULTIPART_COMPONENTS");
    } catch (Throwable error) {
      error.printStackTrace(); report.put("failure", error.toString()); save("FAIL_NATIVE_MULTIPART_COMPONENTS");
    } finally { store.close(); }
    System.exit(report.get("status").toString().startsWith("PASS") ? 0 : 1);
  }
}
