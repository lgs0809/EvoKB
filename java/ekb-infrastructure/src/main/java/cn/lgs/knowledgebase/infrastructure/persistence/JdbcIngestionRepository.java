package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawExecution;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.Batch;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.BatchStatus;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.DuplicateHint;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ExpectedBehavior;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileStatus;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcIngestionRepository implements UploadRepository {
  private static final String FILE_COLUMNS =
      "file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,"
          + "source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,"
          + "COALESCE(document_id,''),COALESCE(version_id,''),COALESCE(error_code,''),COALESCE(error_message,''),created_at,updated_at,started_at,finished_at,requested_security_level";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private String rawRecoveryCursor = "";

  public JdbcIngestionRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
  }

  @Override
  public Batch create(Batch batch) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO ingestion_batch(batch_id,created_by,created_by_username,status,source_count,created_at,updated_at,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?)",
              batch.batchId(),
              nullable(batch.createdBy()),
              batch.createdByUsername(),
              BatchStatus.STAGED.name(),
              batch.sourceCount(),
              ts(batch.createdAt()),
              ts(batch.updatedAt()),
              ts(batch.startedAt()),
              ts(batch.finishedAt()));
          for (FileItem file : batch.files()) {
            FileMetadata metadata = file.metadata();
            jdbc.update(
                "INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,document_id,version_id,error_code,error_message,created_at,updated_at,started_at,finished_at,requested_security_level) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                file.fileId(),
                file.batchId(),
                file.relativePath(),
                file.originalFilename(),
                file.stagingPath(),
                file.sizeBytes(),
                file.sha256(),
                metadata.logicalName(),
                metadata.sourceDepartment(),
                metadata.materialCategory(),
                metadata.company(),
                metadata.documentType(),
                metadata.documentDate(),
                json(metadata.manualTags()),
                file.status().name(),
                file.progressStage().name(),
                file.attempt(),
                nullable(file.documentId()),
                nullable(file.versionId()),
                file.errorCode(),
                nullable(file.errorMessage()),
                ts(file.createdAt()),
                ts(file.updatedAt()),
                ts(file.startedAt()),
                ts(file.finishedAt()),
                metadata.securityLevel());
          }
        });
    return findBatch(batch.batchId()).orElseThrow();
  }

  @Override
  public Optional<Batch> findBatch(String batchId) {
    Optional<BatchHeader> header =
        jdbc
            .query(
                "SELECT batch_id,COALESCE(created_by,''),created_by_username,source_count,created_at,updated_at,started_at,finished_at FROM ingestion_batch WHERE batch_id=?",
                (rs, rowNum) ->
                    new BatchHeader(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6)),
                        instant(rs.getTimestamp(7)),
                        instant(rs.getTimestamp(8))),
                batchId)
            .stream()
            .findFirst();
    return header.map(
        value ->
            new Batch(
                value.batchId(),
                value.createdBy(),
                value.createdByUsername(),
                value.sourceCount(),
                listFiles(value.batchId()),
                value.createdAt(),
                value.updatedAt(),
                value.startedAt(),
                value.finishedAt()));
  }

  @Override
  public Optional<FileItem> findFile(String fileId) {
    return jdbc
        .query(
            "SELECT " + FILE_COLUMNS + " FROM ingestion_file WHERE file_id=?",
            (rs, rowNum) -> scanFile(rs),
            fileId)
        .stream()
        .findFirst()
        .map(this::decorate);
  }

  @Override
  public void saveFile(FileItem file) {
    FileMetadata metadata = file.metadata();
    int rows =
        jdbc.update(
            "UPDATE ingestion_file SET logical_name=?,source_department=?,material_category=?,company=?,document_type=?,document_date=?,manual_tags=?,status=?,progress_stage=?,attempt=?,document_id=?,version_id=?,error_code=?,error_message=?,updated_at=?,started_at=?,finished_at=?,requested_security_level=? WHERE file_id=? AND status='STAGED'",
            metadata.logicalName(),
            metadata.sourceDepartment(),
            metadata.materialCategory(),
            metadata.company(),
            metadata.documentType(),
            metadata.documentDate(),
            json(metadata.manualTags()),
            file.status().name(),
            file.progressStage().name(),
            file.attempt(),
            nullable(file.documentId()),
            nullable(file.versionId()),
            file.errorCode(),
            nullable(truncate(file.errorMessage(), 8000)),
            ts(file.updatedAt()),
            ts(file.startedAt()),
            ts(file.finishedAt()),
            metadata.securityLevel(),
            file.fileId());
    if (rows == 0)
      throw new cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService
          .Conflict("file metadata can only be edited while STAGED");
  }

  @Override
  public List<Batch> listBatches(
      String actorId, boolean admin, int offset, int limit, String status, String query) {
    StringBuilder sql = new StringBuilder("SELECT b.batch_id FROM ingestion_batch b WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (!admin) {
      sql.append(" AND b.created_by=?");
      args.add(actorId);
    }
    if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
      sql.append(" AND b.status=?");
      args.add(status.trim().toUpperCase());
    }
    if (query != null && !query.isBlank()) {
      sql.append(
          " AND (b.created_by_username LIKE ? OR EXISTS (SELECT 1 FROM ingestion_file fx WHERE fx.batch_id=b.batch_id AND (fx.logical_name LIKE ? OR fx.original_filename LIKE ? OR fx.relative_path LIKE ?)))");
      String like = "%" + query.trim() + "%";
      Collections.addAll(args, like, like, like, like);
    }
    sql.append(" ORDER BY b.created_at DESC LIMIT ? OFFSET ?");
    args.add(limit <= 0 ? 30 : Math.min(limit, 100));
    args.add(Math.max(0, offset));
    List<String> ids = jdbc.query(sql.toString(), (rs, rowNum) -> rs.getString(1), args.toArray());
    return ids.stream()
        .map(id -> findBatch(id).orElse(null))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Override
  public long countBatches(String actorId, boolean admin, String status, String query) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ingestion_batch b WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (!admin) {
      sql.append(" AND b.created_by=?");
      args.add(actorId);
    }
    if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
      sql.append(" AND b.status=?");
      args.add(status.trim().toUpperCase());
    }
    if (query != null && !query.isBlank()) {
      sql.append(
          " AND (b.created_by_username LIKE ? OR EXISTS (SELECT 1 FROM ingestion_file fx WHERE fx.batch_id=b.batch_id AND (fx.logical_name LIKE ? OR fx.original_filename LIKE ? OR fx.relative_path LIKE ?)))");
      String like = "%" + query.trim() + "%";
      Collections.addAll(args, like, like, like, like);
    }
    Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return count == null ? 0 : count;
  }

  @Override
  public void queueBatch(String batchId, Instant now) {
    transactions.executeWithoutResult(
        status -> {
          String current =
              jdbc.queryForObject(
                  "SELECT status FROM ingestion_batch WHERE batch_id=? FOR UPDATE",
                  String.class,
                  batchId);
          if (!"STAGED".equals(current))
            throw new IllegalStateException("batch is " + current + ", expected STAGED");
          int count =
              jdbc.update(
                  "UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,started_at=NULL,finished_at=NULL,updated_at=? WHERE batch_id=? AND status='STAGED'",
                  ts(now),
                  batchId);
          if (count == 0) throw new IllegalStateException("batch contains no staged files");
          jdbc.update(
              "INSERT INTO raw_ingestion_outbox(file_id) SELECT file_id FROM ingestion_file WHERE batch_id=? AND status='QUEUED' ON CONFLICT(file_id) DO UPDATE SET delivery_epoch=raw_ingestion_outbox.delivery_epoch+1,message_id=NULL,dispatched_at=NULL",
              batchId);
          jdbc.update(
              "UPDATE ingestion_batch SET status='QUEUED',started_at=COALESCE(started_at,?),finished_at=NULL,updated_at=? WHERE batch_id=?",
              ts(now),
              ts(now),
              batchId);
        });
  }

  @Override
  public void retryFile(String fileId, Instant now) {
    FileItem current =
        findFile(fileId).orElseThrow(() -> new IllegalStateException("ingestion file not found"));
    FileItem queued = current.retry(now);
    transactions.executeWithoutResult(
        status -> {
          int rows =
              jdbc.update(
                  "UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,started_at=NULL,finished_at=NULL,updated_at=? WHERE file_id=? AND status='FAILED'",
                  ts(now),
                  fileId);
          if (rows != 1)
            throw new IllegalStateException("only FAILED ingestion files can be retried");
          if (current.attempt() >= 5)
            throw new IllegalStateException("raw ingestion business attempts exhausted");
          jdbc.update(
              "UPDATE ingestion_file SET isolated=FALSE,next_attempt_at=NULL WHERE file_id=?",
              fileId);
          jdbc.update(
              "INSERT INTO raw_ingestion_outbox(file_id) VALUES(?) ON CONFLICT(file_id) DO UPDATE SET delivery_epoch=raw_ingestion_outbox.delivery_epoch+1,message_id=NULL,dispatched_at=NULL",
              fileId);
          jdbc.update(
              "UPDATE ingestion_batch SET status='QUEUED',finished_at=NULL,updated_at=? WHERE batch_id=?",
              ts(now),
              queued.batchId());
        });
  }

  @Override
  public void resetInterrupted(Instant now) {
    recoverExpiredRaw();
  }

  private synchronized void recoverExpiredRaw() {
    transactions.executeWithoutResult(
        ignored -> {
          var rows =
              jdbc.queryForList(
                  "SELECT file_id,execution_epoch,attempt,progress_stage,batch_id FROM ingestion_file WHERE file_id>? AND status='RUNNING' AND (stage_deadline IS NULL OR stage_deadline<=clock_timestamp()) ORDER BY file_id LIMIT 100 FOR UPDATE SKIP LOCKED",
                  rawRecoveryCursor);
          for (var row : rows) {
            String id = row.get("file_id").toString();
            int attempt = ((Number) row.get("attempt")).intValue();
            boolean isolated = attempt >= 5;
            jdbc.update(
                "INSERT INTO raw_ingestion_failure(file_id,execution_epoch,attempt,stage,outcome,error_code) VALUES(?,?,?,?,?,'RAW_STAGE_EXPIRED') ON CONFLICT DO NOTHING",
                id,
                row.get("execution_epoch"),
                attempt,
                row.get("progress_stage"),
                isolated ? "ISOLATED" : "INTERRUPTED");
            jdbc.update(
                "UPDATE ingestion_file SET status=?,progress_stage=?,isolated=?,owner_id=NULL,next_attempt_at=NULL,error_code='RAW_STAGE_EXPIRED',updated_at=clock_timestamp(),finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE file_id=?",
                isolated ? "FAILED" : "QUEUED",
                isolated ? "FAILED" : "QUEUED",
                isolated,
                isolated,
                id);
            if (!isolated)
              jdbc.update(
                  "INSERT INTO raw_ingestion_outbox(file_id) VALUES(?) ON CONFLICT(file_id) DO UPDATE SET delivery_epoch=raw_ingestion_outbox.delivery_epoch+1,message_id=NULL,dispatched_at=NULL",
                  id);
            recalculateBatch(row.get("batch_id").toString(), databaseNow());
          }
          rawRecoveryCursor =
              rows.size() < 100 ? "" : rows.get(rows.size() - 1).get("file_id").toString();
        });
  }

  @Override
  public List<RawOutbox> rawDispatchable(int limit) {
    return jdbc.query(
        "SELECT o.file_id,o.delivery_epoch FROM raw_ingestion_outbox o JOIN ingestion_file f USING(file_id) WHERE f.status='QUEUED' AND f.attempt<5 AND (f.next_attempt_at IS NULL OR f.next_attempt_at<=clock_timestamp()) AND (o.dispatched_at IS NULL OR o.dispatched_at<clock_timestamp()-interval '30 seconds') ORDER BY o.created_at,o.file_id LIMIT ?",
        (rs, n) -> new RawOutbox(rs.getString(1), rs.getLong(2)),
        Math.max(1, Math.min(100, limit)));
  }

  @Override
  public void rawDispatched(RawOutbox row, String messageId) {
    transactions.executeWithoutResult(
        ignored -> {
          jdbc.update(
              "INSERT INTO raw_ingestion_delivery_history(message_id,file_id,delivery_epoch) VALUES(?,?,?) ON CONFLICT DO NOTHING",
              messageId,
              row.fileId(),
              row.deliveryEpoch());
          jdbc.update(
              "UPDATE raw_ingestion_outbox SET message_id=?,dispatched_at=clock_timestamp() WHERE file_id=? AND delivery_epoch=?",
              messageId,
              row.fileId(),
              row.deliveryEpoch());
        });
  }

  @Override
  public Optional<RawTaskClaim> claimRaw(String fileId, String owner) {
    if (owner == null || !owner.matches("[0-9a-f-]{36}"))
      throw new IllegalArgumentException("raw owner UUID required");
    return transactions.execute(
        ignored -> {
          var ids =
              jdbc.query(
                  "WITH clock AS MATERIALIZED(SELECT clock_timestamp() AS now) UPDATE ingestion_file SET status='RUNNING',progress_stage='STARTING',execution_stage='STARTING',attempt=attempt+1,execution_epoch=execution_epoch+1,owner_id=?,stage_started_at=clock.now,stage_deadline=clock.now+interval '600 seconds',next_attempt_at=NULL,started_at=clock.now,finished_at=NULL,updated_at=clock.now,error_code='',error_message=NULL FROM clock WHERE file_id=? AND status='QUEUED' AND attempt<5 AND NOT isolated AND (next_attempt_at IS NULL OR next_attempt_at<=clock.now) RETURNING execution_epoch,stage_deadline",
                  (rs, n) -> new Object[] {rs.getLong(1), rs.getTimestamp(2).toInstant()},
                  owner,
                  fileId);
          if (ids.isEmpty()) return Optional.empty();
          var file = findFile(fileId).orElseThrow();
          jdbc.update(
              "UPDATE ingestion_batch SET status='RUNNING',started_at=COALESCE(started_at,clock_timestamp()),finished_at=NULL,updated_at=clock_timestamp() WHERE batch_id=?",
              file.batchId());
          return Optional.of(
              new RawTaskClaim(file, owner, (Long) ids.get(0)[0], (Instant) ids.get(0)[1]));
        });
  }

  @Override
  public boolean rawRecoverable(String fileId) {
    if (findFile(fileId).isEmpty()) return true;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT status<>'RUNNING' OR stage_deadline IS NULL OR stage_deadline<=clock_timestamp() FROM ingestion_file WHERE file_id=?",
            Boolean.class,
            fileId));
  }

  @Override
  public boolean rawAcknowledgeable(String fileId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT status IN ('SUCCEEDED','FAILED') OR (status='QUEUED' AND next_attempt_at IS NOT NULL) FROM ingestion_file WHERE file_id=?",
            Boolean.class,
            fileId));
  }

  @Override
  public void rawRejectDelivery(String messageId, String fileReference) {
    jdbc.update(
        "INSERT INTO raw_ingestion_delivery_failure(message_id,file_reference,error_code) VALUES(?,?,'RAW_DELIVERY_INVALID') ON CONFLICT DO NOTHING",
        messageId,
        truncate(fileReference, 128));
  }

  @Override
  public void rawMissingDelivery(String messageId) {
    transactions.executeWithoutResult(
        ignored -> {
          var ids =
              jdbc.query(
                  "SELECT file_id FROM raw_ingestion_delivery_history WHERE message_id=?",
                  (rs, n) -> rs.getString(1),
                  messageId);
          String file = ids.isEmpty() ? null : ids.get(0);
          jdbc.update(
              "INSERT INTO raw_ingestion_delivery_failure(message_id,file_reference,error_code) VALUES(?,?,'RAW_DELIVERY_BODY_MISSING') ON CONFLICT DO NOTHING",
              messageId,
              file);
          // A running task retains its exact DB owner/deadline and is recovered only when expired.
          // Queued tasks regain a durable dispatch intent; a missing unknown body is quarantined.
          if (file != null)
            jdbc.update(
                "INSERT INTO raw_ingestion_outbox(file_id) SELECT file_id FROM ingestion_file WHERE file_id=? AND status='QUEUED' AND NOT isolated AND attempt<5 ON CONFLICT(file_id) DO UPDATE SET delivery_epoch=raw_ingestion_outbox.delivery_epoch+1,message_id=NULL,dispatched_at=NULL",
                file);
        });
  }

  @Override
  public Optional<String> rawOriginalObject(String fileId) {
    return jdbc
        .query(
            "SELECT v.original_object_key FROM ingestion_file f JOIN document_version v USING(version_id) WHERE f.file_id=?",
            (rs, n) -> rs.getString(1),
            fileId)
        .stream()
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  @Override
  public void rawVerifyOriginal(
      RawTaskClaim claim, cn.lgs.knowledgebase.application.port.Ports.SourceFile source) {
    boolean valid =
        Boolean.TRUE.equals(
            transactions.execute(
                ignored -> {
                  assertRaw(claim, true);
                  var sessions =
                      jdbc.queryForList(
                          "SELECT s.session_id,s.client_sha256,s.expected_bytes,s.document_version_id,s.integrity_status,s.actual_sha256 FROM kb_upload_session s JOIN ingestion_file f ON f.upload_session_id=s.session_id WHERE f.file_id=? AND s.status='COMPLETED' FOR UPDATE OF s",
                          claim.file().fileId());
                  if (sessions.isEmpty())
                    return true; // Normal staged originals retain the existing path.
                  var session = sessions.get(0);
                  String expected =
                      session.get("client_sha256") == null
                          ? ""
                          : session.get("client_sha256").toString();
                  boolean matches =
                      ((Number) session.get("expected_bytes")).longValue() == source.size()
                          && (expected.isEmpty() || expected.equals(source.sha256()));
                  if (!matches) {
                    jdbc.update(
                        "UPDATE kb_upload_session SET integrity_status='FAILED',actual_sha256=? WHERE session_id=?",
                        source.sha256(),
                        session.get("session_id"));
                    return false;
                  }
                  if ("FAILED".equals(session.get("integrity_status"))) return false;
                  if ("VERIFIED".equals(session.get("integrity_status"))
                      && !source.sha256().equals(session.get("actual_sha256"))) return false;
                  jdbc.update(
                      "UPDATE document_version SET sha256=? WHERE version_id=? AND lifecycle IN ('PENDING','FAILED')",
                      source.sha256(),
                      session.get("document_version_id"));
                  jdbc.update(
                      "UPDATE ingestion_file SET sha256=? WHERE file_id=?",
                      source.sha256(),
                      claim.file().fileId());
                  jdbc.update(
                      "UPDATE kb_upload_session SET integrity_status='VERIFIED',actual_sha256=? WHERE session_id=?",
                      source.sha256(),
                      session.get("session_id"));
                  assertRaw(claim, false);
                  return true;
                }));
    if (!valid)
      throw new IllegalStateException(
          "INPUT_ERROR: completed original SHA-256 differs from upload identity");
  }

  private void assertRaw(RawTaskClaim claim, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT file_id FROM ingestion_file WHERE file_id=? AND status='RUNNING' AND owner_id=? AND execution_epoch=? AND stage_deadline>clock_timestamp()"
                + (lock ? " FOR UPDATE" : ""),
            claim.file().fileId(),
            claim.ownerId(),
            claim.epoch());
    if (rows.size() != 1)
      throw new IllegalStateException(
          "RAW_STALE_CLAIM: owner/epoch/stage deadline no longer current");
  }

  @Override
  public RawExecution rawExecution(RawTaskClaim claim) {
    return new RawExecution() {
      public String projectionScope() {
        return claim.scope();
      }

      public void check() {
        assertRaw(claim, false);
      }

      public java.util.Optional<
              cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion>
          reservedInput() {
        check();
        return jdbc
            .query(
                "SELECT v.* FROM ingestion_file f JOIN kb_upload_session s ON s.session_id=f.upload_session_id JOIN document_version v ON v.version_id=s.document_version_id WHERE f.file_id=? AND s.status='COMPLETED' AND s.integrity_status='VERIFIED' AND s.actual_sha256=v.sha256",
                (rs, n) ->
                    new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion(
                        rs.getString("version_id"),
                        rs.getString("document_id"),
                        rs.getLong("version_no"),
                        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle
                            .valueOf(rs.getString("lifecycle")),
                        rs.getString("original_filename"),
                        rs.getString("media_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("original_object_key"),
                        rs.getString("markdown_object_key"),
                        rs.getString("structured_object_key"),
                        rs.getString("parser_name"),
                        rs.getString("parser_version")),
                claim.file().fileId())
            .stream()
            .findFirst();
      }

      public <T> T reserveSource(String logicalName, java.util.function.Supplier<T> reservation) {
        return transactions.execute(
            ignored -> {
              assertRaw(claim, true);
              boolean reserved =
                  Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
                          Boolean.class,
                          "raw-logical-source:" + logicalName));
              if (!reserved)
                throw new IllegalStateException(
                    "RAW_SOURCE_BUSY: source reservation is already in progress");
              // Reuse the uploader/clearance guard of multipart reservation for the staged path.
              // An internal compatibility fixture may have no creator; normal HTTP batches do.
              var creator =
                  jdbc.queryForObject(
                      "SELECT created_by FROM ingestion_batch WHERE batch_id=?",
                      String.class,
                      claim.file().batchId());
              if (creator != null) {
                var accounts =
                    jdbc.queryForList(
                        "SELECT role,security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE' FOR SHARE",
                        creator);
                if (accounts.size() != 1)
                  throw new IllegalStateException(
                      "RAW_SOURCE_REVOKED: uploader account is no longer active");
                int inherited =
                    jdbc.queryForObject(
                        "SELECT COALESCE(max(m.security_level),10) FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id) WHERE d.logical_name=?",
                        Integer.class,
                        logicalName);
                Integer requested = claim.file().metadata().securityLevel();
                int required = requested == null ? inherited : Math.max(inherited, requested);
                if (((Number) accounts.get(0).get("security_clearance")).intValue() < required)
                  throw new IllegalStateException(
                      "RAW_SOURCE_REVOKED: source exceeds uploader clearance");
              }
              T result = reservation.get();
              assertRaw(claim, false);
              return result;
            });
      }

      public boolean completedInputCurrent(String document, String version) {
        check();
        return !jdbc.queryForList(
                "SELECT p.version_id FROM raw_publication p JOIN document_version v USING(version_id) JOIN document d ON d.document_id=v.document_id JOIN document_metadata m ON m.version_id=v.version_id WHERE v.document_id=? AND v.version_id=? AND d.status='ACTIVE' AND v.lifecycle='ACTIVE' AND p.acl_version=m.acl_version AND EXISTS(SELECT 1 FROM raw_publication_member pm WHERE pm.version_id=p.version_id) AND NOT EXISTS(SELECT 1 FROM document_version newer WHERE newer.document_id=v.document_id AND newer.version_no>v.version_no AND newer.lifecycle IN ('PENDING','ACTIVE'))",
                document,
                version)
            .isEmpty();
      }

      public void fenced(Runnable action) {
        transactions.executeWithoutResult(
            ignored -> {
              assertRaw(claim, true);
              action.run();
              assertRaw(claim, false);
            });
      }

      public void phase(ProgressStage visible) {
        String stage =
            switch (visible) {
              case EVIDENCE, PARSING -> "PARSING";
              case EMBEDDING, INDEXING -> "EMBEDDING";
              case ACTIVATING -> "ACTIVATING";
              default -> visible.name();
            };
        var order = List.of("STARTING", "STORING", "PARSING", "EMBEDDING", "ACTIVATING");
        if (!order.contains(stage))
          throw new IllegalArgumentException("raw phase must be a valid execution stage");
        fenced(
            () -> {
              String current =
                  jdbc.queryForObject(
                      "SELECT execution_stage FROM ingestion_file WHERE file_id=?",
                      String.class,
                      claim.file().fileId());
              if (order.indexOf(stage) < order.indexOf(current))
                throw new IllegalStateException("Raw phases never roll back to renew a deadline");
              jdbc.update(
                  "WITH clock AS MATERIALIZED(SELECT clock_timestamp() AS now) UPDATE ingestion_file SET progress_stage=?,stage_started_at=CASE WHEN execution_stage<>? THEN clock.now ELSE stage_started_at END,stage_deadline=CASE WHEN execution_stage<>? THEN clock.now+interval '600 seconds' ELSE stage_deadline END,execution_stage=?,updated_at=clock.now FROM clock WHERE file_id=?",
                  visible.name(),
                  stage,
                  stage,
                  stage,
                  claim.file().fileId());
            });
      }

      public void bind(String document, String version) {
        fenced(
            () -> {
              Long competing =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM ingestion_file WHERE file_id<>? AND version_id=? AND status='RUNNING' AND stage_deadline>clock_timestamp()",
                      Long.class,
                      claim.file().fileId(),
                      version);
              if (competing != null && competing > 0)
                throw new IllegalStateException(
                    "RAW_SOURCE_BUSY: another current raw attempt owns this input version");
              var acl =
                  jdbc.queryForObject(
                      "SELECT m.acl_version FROM document_version v JOIN document d USING(document_id) JOIN document_metadata m USING(version_id) WHERE v.version_id=? AND v.document_id=? AND d.status='ACTIVE' AND v.lifecycle IN ('PENDING','ACTIVE')",
                      Long.class,
                      version,
                      document);
              jdbc.update(
                  "UPDATE ingestion_file SET document_id=?,version_id=?,claimed_acl_version=? WHERE file_id=?",
                  document,
                  version,
                  acl,
                  claim.file().fileId());
            });
      }

      public void publish(
          String document, String version, List<String> physicalIds, Runnable action) {
        fenced(
            () -> {
              if (physicalIds == null || physicalIds.isEmpty())
                throw new IllegalArgumentException("raw publication requires physical members");
              var current =
                  jdbc.queryForList(
                      "SELECT v.version_id,m.acl_version FROM document_version v JOIN document d USING(document_id) JOIN document_metadata m USING(version_id) JOIN ingestion_file f ON f.version_id=v.version_id WHERE f.file_id=? AND v.version_id=? AND v.document_id=? AND v.lifecycle IN ('PENDING','ACTIVE') AND d.status='ACTIVE' AND m.acl_version=f.claimed_acl_version FOR UPDATE OF v,d,m",
                      claim.file().fileId(),
                      version,
                      document);
              if (current.size() != 1)
                throw new IllegalStateException(
                    "RAW_SOURCE_REVOKED: current source/ACL differs from this attempt");
              new cn.lgs.knowledgebase.infrastructure.raw.JdbcRawPublicationWriter(jdbc)
                  .publish(
                      claim.scope(),
                      claim.file().fileId(),
                      null,
                      claim.epoch(),
                      document,
                      version,
                      ((Number) current.get(0).get("acl_version")).longValue(),
                      physicalIds,
                      action);
              assertRaw(claim, false);
            });
      }
    };
  }

  @Override
  public void rawComplete(RawTaskClaim claim, String document, String version) {
    transactions.executeWithoutResult(
        ignored -> {
          assertRaw(claim, true);
          var source =
              jdbc.queryForList(
                  "SELECT v.version_id FROM ingestion_file f JOIN document_version v ON v.version_id=f.version_id JOIN document d ON d.document_id=v.document_id JOIN document_metadata m ON m.version_id=v.version_id WHERE f.file_id=? AND v.document_id=? AND v.version_id=? AND d.status='ACTIVE' AND v.lifecycle='ACTIVE' AND m.acl_version=f.claimed_acl_version AND NOT EXISTS(SELECT 1 FROM document_version newer WHERE newer.document_id=v.document_id AND newer.version_no>v.version_no AND newer.lifecycle IN ('PENDING','ACTIVE')) AND (f.upload_session_id IS NULL OR EXISTS(SELECT 1 FROM raw_publication p WHERE p.version_id=v.version_id AND p.acl_version=m.acl_version AND EXISTS(SELECT 1 FROM raw_publication_member pm WHERE pm.version_id=p.version_id))) FOR UPDATE OF v,d,m",
                  claim.file().fileId(),
                  document,
                  version);
          if (source.size() != 1)
            throw new IllegalStateException(
                "RAW_SOURCE_REVOKED: completed source or publication is no longer current");
          complete(claim.file().fileId(), document, version, databaseNow());
        });
  }

  @Override
  public void rawFailure(RawTaskClaim claim, String code, String message, boolean retryable) {
    transactions.executeWithoutResult(
        ignored -> {
          var rows =
              jdbc.queryForList(
                  "SELECT attempt,progress_stage,batch_id FROM ingestion_file WHERE file_id=? AND status='RUNNING' AND owner_id=? AND execution_epoch=? FOR UPDATE",
                  claim.file().fileId(),
                  claim.ownerId(),
                  claim.epoch());
          if (rows.isEmpty())
            throw new IllegalStateException(
                "RAW_STALE_CLAIM: cannot record failure of another epoch");
          int attempt = ((Number) rows.get(0).get("attempt")).intValue();
          boolean retry = retryable && attempt < 5;
          int seconds = retry ? new int[] {10, 30, 120, 300}[attempt - 1] : 0;
          Instant now = databaseNow(), next = retry ? now.plusSeconds(seconds) : null;
          jdbc.update(
              "INSERT INTO raw_ingestion_failure(file_id,execution_epoch,attempt,stage,outcome,error_code,error_message,occurred_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
              claim.file().fileId(),
              claim.epoch(),
              attempt,
              rows.get(0).get("progress_stage"),
              retry ? "RETRY_WAIT" : "ISOLATED",
              code,
              truncate(message, 8000),
              ts(now),
              ts(next));
          jdbc.update(
              "UPDATE ingestion_file SET status=?,progress_stage=?,owner_id=NULL,isolated=?,next_attempt_at=?,finished_at=?,error_code=?,error_message=?,updated_at=? WHERE file_id=?",
              retry ? "QUEUED" : "FAILED",
              retry ? "QUEUED" : "FAILED",
              !retry,
              ts(next),
              retry ? null : ts(now),
              code,
              truncate(message, 8000),
              ts(now),
              claim.file().fileId());
          if (retry)
            jdbc.update(
                "INSERT INTO raw_ingestion_outbox(file_id) VALUES(?) ON CONFLICT(file_id) DO UPDATE SET delivery_epoch=raw_ingestion_outbox.delivery_epoch+1,message_id=NULL,dispatched_at=NULL",
                claim.file().fileId());
          recalculateBatch(rows.get(0).get("batch_id").toString(), now);
        });
  }

  @Override
  public List<FileItem> claimQueued(int limit, Instant now) {
    int claimLimit = limit <= 0 ? 1 : Math.min(limit, 32);
    return transactions.execute(
        status -> {
          List<FileItem> selected =
              jdbc.query(
                  "SELECT "
                      + FILE_COLUMNS
                      + " FROM ingestion_file WHERE status='QUEUED' ORDER BY created_at,file_id LIMIT ? FOR UPDATE SKIP LOCKED",
                  (rs, rowNum) -> scanFile(rs),
                  claimLimit);
          List<FileItem> claimed = new ArrayList<>(selected.size());
          for (FileItem file : selected) {
            FileItem next = file.claim(now);
            jdbc.update(
                "UPDATE ingestion_file SET status='RUNNING',progress_stage='STARTING',attempt=?,started_at=?,finished_at=NULL,updated_at=? WHERE file_id=? AND status='QUEUED'",
                next.attempt(),
                ts(now),
                ts(now),
                file.fileId());
            jdbc.update(
                "UPDATE ingestion_batch SET status='RUNNING',started_at=COALESCE(started_at,?),finished_at=NULL,updated_at=? WHERE batch_id=?",
                ts(now),
                ts(now),
                file.batchId());
            claimed.add(next);
          }
          return List.copyOf(claimed);
        });
  }

  @Override
  public void updateProgress(String fileId, ProgressStage stage, Instant now) {
    jdbc.update(
        "UPDATE ingestion_file SET progress_stage=?,updated_at=? WHERE file_id=? AND status='RUNNING'",
        stage.name(),
        ts(now),
        fileId);
  }

  @Override
  public String parserTaskId(String fileId) {
    String value =
        jdbc.queryForObject(
            "SELECT COALESCE(parser_task_id,'') FROM ingestion_file WHERE file_id=?",
            String.class,
            fileId);
    return value == null ? "" : value.trim();
  }

  @Override
  public void updateParserTaskId(String fileId, String parserTaskId, Instant now) {
    int rows =
        jdbc.update(
            "UPDATE ingestion_file SET parser_task_id=?,updated_at=? WHERE file_id=?",
            nullable(parserTaskId),
            ts(now),
            fileId);
    if (rows == 0) throw new IllegalStateException("ingestion file not found: " + fileId);
  }

  @Override
  public void complete(String fileId, String documentId, String versionId, Instant now) {
    FileItem file =
        findFile(fileId).orElseThrow(() -> new IllegalStateException("ingestion file not found"));
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE ingestion_file SET status='SUCCEEDED',progress_stage='SUCCEEDED',document_id=?,version_id=?,parser_task_id=NULL,error_code='',error_message=NULL,finished_at=?,updated_at=? WHERE file_id=?",
              documentId,
              versionId,
              ts(now),
              ts(now),
              fileId);
          recalculateBatch(file.batchId(), now);
        });
  }

  @Override
  public void fail(String fileId, String errorCode, String errorMessage, Instant now) {
    FileItem file =
        findFile(fileId).orElseThrow(() -> new IllegalStateException("ingestion file not found"));
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE ingestion_file SET status='FAILED',progress_stage='FAILED',error_code=?,error_message=?,finished_at=?,updated_at=? WHERE file_id=?",
              errorCode == null ? "" : errorCode,
              truncate(errorMessage, 8000),
              ts(now),
              ts(now),
              fileId);
          recalculateBatch(file.batchId(), now);
        });
  }

  private void recalculateBatch(String batchId, Instant now) {
    int[] counts =
        jdbc.queryForObject(
            "SELECT COUNT(*) FILTER (WHERE status='STAGED'),COUNT(*) FILTER (WHERE status='QUEUED'),COUNT(*) FILTER (WHERE status='RUNNING'),COUNT(*) FILTER (WHERE status='SUCCEEDED'),COUNT(*) FILTER (WHERE status='FAILED') FROM ingestion_file WHERE batch_id=?",
            (rs, rowNum) ->
                new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)},
            batchId);
    if (counts == null) return;
    int staged = counts[0],
        queued = counts[1],
        running = counts[2],
        succeeded = counts[3],
        failed = counts[4];
    BatchStatus batchStatus;
    boolean terminal = false;
    if (running > 0) batchStatus = BatchStatus.RUNNING;
    else if (queued > 0) batchStatus = BatchStatus.QUEUED;
    else if (staged > 0 && succeeded == 0 && failed == 0) batchStatus = BatchStatus.STAGED;
    else if (failed > 0 && succeeded > 0) {
      batchStatus = BatchStatus.PARTIAL;
      terminal = true;
    } else if (failed > 0) {
      batchStatus = BatchStatus.FAILED;
      terminal = true;
    } else {
      batchStatus = BatchStatus.SUCCEEDED;
      terminal = true;
    }
    jdbc.update(
        "UPDATE ingestion_batch SET status=?,finished_at=?,updated_at=? WHERE batch_id=?",
        batchStatus.name(),
        terminal ? ts(now) : null,
        ts(now),
        batchId);
  }

  private List<FileItem> listFiles(String batchId) {
    return jdbc.query(
        "SELECT "
            + FILE_COLUMNS
            + " FROM ingestion_file WHERE batch_id=? ORDER BY created_at,file_id",
        (rs, rowNum) -> decorate(scanFile(rs)),
        batchId);
  }

  private FileItem decorate(FileItem file) {
    DuplicateHint hint =
        jdbc.query(
                "SELECT "
                    + "COALESCE((SELECT d.document_id FROM document d WHERE d.logical_name=? AND d.status='ACTIVE' ORDER BY d.created_at LIMIT 1),''),"
                    + "COALESCE((SELECT MAX(v.version_no) FROM document d JOIN document_version v ON v.document_id=d.document_id WHERE d.logical_name=? AND d.status='ACTIVE'),0),"
                    + "COALESCE((SELECT v.document_id FROM document_version v WHERE v.sha256=? ORDER BY v.created_at DESC LIMIT 1),''),"
                    + "COALESCE((SELECT v.version_id FROM document_version v WHERE v.sha256=? ORDER BY v.created_at DESC LIMIT 1),''),"
                    + "COALESCE((SELECT d.logical_name FROM document d JOIN document_version v ON v.document_id=d.document_id WHERE v.sha256=? ORDER BY v.created_at DESC LIMIT 1),''),"
                    + "COALESCE((SELECT v.lifecycle FROM document_version v WHERE v.sha256=? ORDER BY v.created_at DESC LIMIT 1),'')",
                (rs, rowNum) -> hint(rs),
                file.metadata().logicalName(),
                file.metadata().logicalName(),
                file.sha256(),
                file.sha256(),
                file.sha256(),
                file.sha256())
            .get(0);
    return file.withDuplicateHint(hint);
  }

  private DuplicateHint hint(ResultSet rs) throws SQLException {
    String existingDocumentId = rs.getString(1);
    long existingVersionNo = rs.getLong(2);
    String duplicateDocumentId = rs.getString(3);
    String duplicateVersionId = rs.getString(4);
    String duplicateLogicalName = rs.getString(5);
    String duplicateLifecycle = rs.getString(6);
    ExpectedBehavior behavior;
    if (!duplicateVersionId.isBlank()
        && !existingDocumentId.isBlank()
        && duplicateDocumentId.equals(existingDocumentId))
      behavior = ExpectedBehavior.REUSE_VERSION;
    else if (!duplicateVersionId.isBlank()) behavior = ExpectedBehavior.DUPLICATE_CONTENT;
    else if (!existingDocumentId.isBlank()) behavior = ExpectedBehavior.CREATE_VERSION;
    else behavior = ExpectedBehavior.CREATE_DOCUMENT;
    return new DuplicateHint(
        existingDocumentId,
        existingVersionNo,
        duplicateDocumentId,
        duplicateVersionId,
        duplicateLogicalName,
        duplicateLifecycle,
        behavior);
  }

  private FileItem scanFile(ResultSet rs) throws SQLException {
    return new FileItem(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getLong(6),
        rs.getString(7),
        new FileMetadata(
            rs.getString(8),
            rs.getString(9),
            rs.getString(10),
            rs.getString(11),
            rs.getString(12),
            rs.getDate(13) == null ? null : rs.getDate(13).toLocalDate(),
            tags(rs.getString(14)),
            rs.getObject(26, Integer.class)),
        FileStatus.valueOf(rs.getString(15)),
        ProgressStage.valueOf(rs.getString(16)),
        rs.getInt(17),
        rs.getString(18),
        rs.getString(19),
        null,
        rs.getString(20),
        rs.getString(21),
        instant(rs.getTimestamp(22)),
        instant(rs.getTimestamp(23)),
        instant(rs.getTimestamp(24)),
        instant(rs.getTimestamp(25)));
  }

  private record BatchHeader(
      String batchId,
      String createdBy,
      String createdByUsername,
      int sourceCount,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant finishedAt) {}

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize ingestion JSON", e);
    }
  }

  private List<String> tags(String value) {
    if (value == null || value.isBlank()) return List.of();
    try {
      return mapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot parse ingestion tags", e);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String truncate(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }
}
