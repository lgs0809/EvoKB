package cn.lgs.knowledgebase.infrastructure.upload;

import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** S3 calls never run inside these short authoritative database transactions. */
public final class JdbcMultipartUploadRepository implements Repository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final Path stagingRoot;
  private String cursor = "";

  public JdbcMultipartUploadRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, Path stagingRoot) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
    this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
  }

  public Session create(
      Create request, String user, String username, String kb, String bucket, long quota) {
    String id = UUID.randomUUID().toString();
    transactions.executeWithoutResult(
        ignored -> {
          // The lease is not an upload deadline; it only serializes the short quota reservation.
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "multipart-quota:" + kb);
          Long stored =
              jdbc.queryForObject(
                  "SELECT COALESCE(sum(size_bytes),0) FROM document_version", Long.class);
          Long reserved =
              jdbc.queryForObject(
                  "SELECT COALESCE(sum(expected_bytes),0) FROM kb_upload_session WHERE knowledge_base_id=? AND status IN ('UPLOADING','COMPLETING')",
                  Long.class,
                  kb);
          if (stored > quota - request.sizeBytes()
              || reserved > quota - request.sizeBytes() - stored)
            throw new ManagedIngestionApplicationService.Conflict(
                "knowledge base storage quota is reserved");
          jdbc.update(
              "WITH t AS(SELECT clock_timestamp() AS now) INSERT INTO kb_upload_session(session_id,user_id,username,knowledge_base_id,bucket,object_key,filename,relative_path,expected_bytes,part_size,part_count,client_sha256,metadata_json,status,created_at,expires_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'UPLOADING',now,now+interval '24 hours' FROM t",
              id,
              user,
              username,
              kb,
              bucket,
              "upload-sessions/" + kb + "/" + user + "/" + id + "/original",
              request.filename(),
              request.relativePath(),
              request.sizeBytes(),
              PART_BYTES,
              (int) ((request.sizeBytes() + PART_BYTES - 1) / PART_BYTES),
              request.clientSha256().isBlank() ? null : request.clientSha256(),
              json(request.metadata()));
        });
    return find(id).orElseThrow();
  }

  public Session attach(String id, String upload) {
    int changed =
        jdbc.update(
            "UPDATE kb_upload_session SET upload_id=?,revision=revision+1 WHERE session_id=? AND upload_id IS NULL",
            upload,
            id);
    var value = find(id).orElseThrow();
    if (changed == 0 && !upload.equals(value.uploadId()))
      throw new ManagedIngestionApplicationService.Conflict(
          "multipart identity is already attached");
    return value;
  }

  public Optional<Session> find(String id) {
    return jdbc.query("SELECT * FROM kb_upload_session WHERE session_id=?", this::row, id).stream()
        .findFirst();
  }

  public List<Session> list(String user, String kb, int limit) {
    return jdbc.query(
        "SELECT * FROM kb_upload_session WHERE user_id=? AND knowledge_base_id=? ORDER BY created_at DESC LIMIT ?",
        this::row,
        user,
        kb,
        Math.min(100, Math.max(1, limit)));
  }

  public Optional<Session> beginComplete(String id, long revision, String owner, List<Part> parts) {
    String manifest = json(parts);
    var result =
        jdbc.query(
            "UPDATE kb_upload_session SET status='COMPLETING',revision=revision+1,completion_owner=?,completion_deadline=clock_timestamp()+interval '600 seconds',completion_parts=?::jsonb,completion_parts_sha256=? WHERE session_id=? AND revision=? AND status='UPLOADING' AND upload_id IS NOT NULL AND expires_at>clock_timestamp() RETURNING *",
            this::row,
            owner,
            manifest,
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(manifest),
            id,
            revision);
    return result.stream().findFirst();
  }

  public Optional<Session> reclaimComplete(String id, String owner) {
    return jdbc
        .query(
            "UPDATE kb_upload_session SET completion_owner=?,completion_deadline=clock_timestamp()+interval '600 seconds',revision=revision+1 WHERE session_id=? AND status='COMPLETING' AND completion_deadline<=clock_timestamp() RETURNING *",
            this::row,
            owner,
            id)
        .stream()
        .findFirst();
  }

  public Session finish(Session claim, ObjectIdentity object) {
    return transactions.execute(
        ignored -> {
          var values =
              jdbc.query(
                  "SELECT * FROM kb_upload_session WHERE session_id=? FOR UPDATE",
                  this::row,
                  claim.sessionId());
          if (values.isEmpty())
            throw new ManagedIngestionApplicationService.NotFound("upload session not found");
          Session current = values.get(0);
          if (current.status() == State.COMPLETED) return current;
          // Creation reads both stored and reserved bytes. Moving bytes between them uses its lock.
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              "multipart-quota:" + current.knowledgeBaseId());
          Instant now =
              jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
          if (current.status() != State.COMPLETING
              || current.revision() != claim.revision()
              || !claim.completionOwner().equals(current.completionOwner())
              || !current.completionDeadline().isAfter(now))
            throw new ManagedIngestionApplicationService.Conflict("completion owner is stale");
          if (!current.expiresAt().isAfter(now)) {
            // Already merged objects are retained for reconciliation; expiration grants no
            // publication.
            jdbc.update(
                "UPDATE kb_upload_session SET status='EXPIRED',revision=revision+1,object_etag=?,object_version=? WHERE session_id=?",
                object.etag(),
                object.storageVersion(),
                claim.sessionId());
            return find(claim.sessionId()).orElseThrow();
          }
          if (object.sizeBytes() != current.sizeBytes()
              || !current.sessionId().equals(object.metadata().get("evokb-session-id")))
            throw new IllegalArgumentException("completed original identity is invalid");
          var account =
              jdbc.queryForList(
                  "SELECT user_id,role,security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE' FOR SHARE",
                  current.userId());
          if (account.isEmpty())
            throw new ManagedIngestionApplicationService.Forbidden("uploader account was revoked");
          var enabled =
              jdbc.queryForList(
                  "SELECT setting_value FROM enterprise_setting WHERE setting_key='user_upload_enabled' FOR SHARE");
          if (!"ADMIN".equals(account.get(0).get("role"))
              && (enabled.isEmpty()
                  || !List.of("true", "1")
                      .contains(
                          enabled
                              .get(0)
                              .get("setting_value")
                              .toString()
                              .toLowerCase(java.util.Locale.ROOT))))
            throw new ManagedIngestionApplicationService.Forbidden(
                "knowledge base write permission was revoked");
          // Content identity has not been trusted yet. No SHA match is used to reuse another
          // object.
          String logical = current.metadata().logicalName();
          Boolean reserved =
              jdbc.queryForObject(
                  "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
                  Boolean.class,
                  "raw-logical-source:" + logical);
          if (!Boolean.TRUE.equals(reserved))
            throw new ManagedIngestionApplicationService.Conflict(
                "RAW_SOURCE_BUSY: document is being reserved");
          var documents =
              jdbc.queryForList(
                  "SELECT document_id,status FROM document WHERE logical_name=? FOR UPDATE",
                  logical);
          String document;
          if (documents.isEmpty()) {
            document = UUID.randomUUID().toString();
            jdbc.update(
                "INSERT INTO document(document_id,logical_name,source_path,status) VALUES(?,?,?,'ACTIVE')",
                document,
                logical,
                "multipart:" + current.sessionId());
          } else {
            if (documents.size() != 1 || !"ACTIVE".equals(documents.get(0).get("status")))
              throw new ManagedIngestionApplicationService.Conflict(
                  "logical source is not writable");
            document = documents.get(0).get("document_id").toString();
          }
          String version = UUID.randomUUID().toString(),
              batch = UUID.randomUUID().toString(),
              file = UUID.randomUUID().toString();
          long number =
              jdbc.queryForObject(
                  "SELECT COALESCE(max(version_no),0)+1 FROM document_version WHERE document_id=?",
                  Long.class,
                  document);
          int inheritedSecurity =
              jdbc.queryForObject(
                  "SELECT COALESCE(max(m.security_level),10) FROM document_metadata m JOIN document_version v USING(version_id) WHERE v.document_id=?",
                  Integer.class,
                  document);
          FileMetadata m = current.metadata();
          cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy.requireAllowed(
              "ADMIN".equals(account.get(0).get("role")), m.securityLevel());
          int security = m.securityLevel() == null ? inheritedSecurity : m.securityLevel();
          if (((Number) account.get(0).get("security_clearance")).intValue()
              < Math.max(inheritedSecurity, security))
            throw new ManagedIngestionApplicationService.Forbidden(
                "existing source exceeds uploader clearance");
          jdbc.update(
              "INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,?,'PENDING',?,?,?,?)",
              version,
              document,
              number,
              current.filename(),
              current.sizeBytes(),
              UNVERIFIED,
              current.objectKey());
          jdbc.update(
              "INSERT INTO document_metadata(version_id,source_department,material_category,company,document_date,auto_document_type,manual_tags,security_level) VALUES(?,?,?,?,?,?,?::jsonb,?)",
              version,
              m.sourceDepartment(),
              m.materialCategory(),
              m.company(),
              m.documentDate(),
              m.documentType(),
              json(m.manualTags()),
              security);
          jdbc.update(
              "INSERT INTO ingestion_batch(batch_id,created_by,created_by_username,status,source_count) VALUES(?,?,?,'QUEUED',1)",
              batch,
              current.userId(),
              current.username());
          jdbc.update(
              "INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,document_id,version_id,upload_session_id,requested_security_level) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'QUEUED','QUEUED',?,?,?,?)",
              file,
              batch,
              current.relativePath(),
              current.filename(),
              stagingRoot
                  .resolve("multipart-" + current.sessionId())
                  .resolve(current.filename())
                  .toString(),
              current.sizeBytes(),
              UNVERIFIED,
              m.logicalName(),
              m.sourceDepartment(),
              m.materialCategory(),
              m.company(),
              m.documentType(),
              m.documentDate(),
              json(m.manualTags()),
              document,
              version,
              current.sessionId(),
              m.securityLevel());
          jdbc.update("INSERT INTO raw_ingestion_outbox(file_id) VALUES(?)", file);
          int committed =
              jdbc.update(
                  "UPDATE kb_upload_session SET status='COMPLETED',revision=revision+1,object_etag=?,object_version=?,document_id=?,document_version_id=?,batch_id=?,file_id=?,completed_at=clock_timestamp() WHERE session_id=? AND status='COMPLETING' AND revision=? AND completion_owner=? AND completion_deadline>clock_timestamp() AND expires_at>clock_timestamp()",
                  object.etag(),
                  object.storageVersion(),
                  document,
                  version,
                  batch,
                  file,
                  current.sessionId(),
                  claim.revision(),
                  claim.completionOwner());
          if (committed != 1)
            throw new ManagedIngestionApplicationService.Conflict(
                "completion crossed its fixed owner/session deadline");
          return find(current.sessionId()).orElseThrow();
        });
  }

  public Optional<Session> terminate(String id, long revision, State target) {
    if (target != State.ABORTED && target != State.EXPIRED)
      throw new IllegalArgumentException("invalid terminal state");
    return jdbc
        .query(
            "UPDATE kb_upload_session SET status=?,revision=revision+1 WHERE session_id=? AND revision=? AND status='UPLOADING'"
                + (target == State.EXPIRED ? " AND expires_at<=clock_timestamp()" : "")
                + " RETURNING *",
            this::row,
            target.name(),
            id,
            revision)
        .stream()
        .findFirst();
  }

  public void abortConfirmed(String id) {
    jdbc.update(
        "UPDATE kb_upload_session SET abort_confirmed=TRUE WHERE session_id=? AND status IN ('ABORTED','EXPIRED')",
        id);
  }

  public synchronized List<Session> recoverable(int limit) {
    var rows =
        jdbc.query(
            "SELECT * FROM kb_upload_session WHERE session_id>? AND ((status='UPLOADING' AND expires_at<=clock_timestamp()) OR (status='COMPLETING' AND completion_deadline<=clock_timestamp()) OR (status IN ('ABORTED','EXPIRED') AND NOT abort_confirmed)) ORDER BY session_id LIMIT ?",
            this::row,
            cursor,
            Math.min(100, Math.max(1, limit)));
    cursor =
        rows.size() < Math.min(100, Math.max(1, limit))
            ? ""
            : rows.get(rows.size() - 1).sessionId();
    return rows;
  }

  private Session row(ResultSet rs, int unused) throws SQLException {
    return new Session(
        rs.getString("session_id"),
        rs.getString("user_id"),
        rs.getString("username"),
        rs.getString("knowledge_base_id"),
        rs.getString("bucket"),
        rs.getString("object_key"),
        rs.getString("upload_id"),
        rs.getString("filename"),
        rs.getString("relative_path"),
        rs.getLong("expected_bytes"),
        rs.getInt("part_size"),
        rs.getInt("part_count"),
        empty(rs.getString("client_sha256")),
        parse(rs.getString("metadata_json"), FileMetadata.class),
        State.valueOf(rs.getString("status")),
        rs.getLong("revision"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("expires_at")),
        rs.getString("completion_owner"),
        instant(rs.getTimestamp("completion_deadline")),
        parts(rs.getString("completion_parts")),
        empty(rs.getString("object_etag")),
        empty(rs.getString("object_version")),
        empty(rs.getString("document_id")),
        empty(rs.getString("document_version_id")),
        empty(rs.getString("batch_id")),
        empty(rs.getString("file_id")),
        rs.getBoolean("abort_confirmed"),
        rs.getString("integrity_status"),
        empty(rs.getString("actual_sha256")));
  }

  private List<Part> parts(String data) {
    try {
      return data == null ? List.of() : mapper.readValue(data, new TypeReference<List<Part>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("invalid frozen multipart manifest", e);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot persist upload identity", e);
    }
  }

  private <T> T parse(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("invalid upload metadata", e);
    }
  }

  private static String empty(String value) {
    return value == null ? "" : value;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
