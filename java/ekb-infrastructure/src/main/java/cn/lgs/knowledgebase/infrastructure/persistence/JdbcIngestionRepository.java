package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
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
          + "COALESCE(document_id,''),COALESCE(version_id,''),COALESCE(error_code,''),COALESCE(error_message,''),created_at,updated_at,started_at,finished_at";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

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
                "INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,document_id,version_id,error_code,error_message,created_at,updated_at,started_at,finished_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                ts(file.finishedAt()));
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
            "UPDATE ingestion_file SET logical_name=?,source_department=?,material_category=?,company=?,document_type=?,document_date=?,manual_tags=?,status=?,progress_stage=?,attempt=?,document_id=?,version_id=?,error_code=?,error_message=?,updated_at=?,started_at=?,finished_at=? WHERE file_id=?",
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
            file.fileId());
    if (rows == 0) throw new IllegalStateException("ingestion file not found: " + file.fileId());
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
          jdbc.update(
              "UPDATE ingestion_batch SET status='QUEUED',finished_at=NULL,updated_at=? WHERE batch_id=?",
              ts(now),
              queued.batchId());
        });
  }

  @Override
  public void resetInterrupted(Instant now) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,started_at=NULL,finished_at=NULL,updated_at=? WHERE status='RUNNING'",
              ts(now));
          jdbc.update(
              "UPDATE ingestion_batch SET status='QUEUED',finished_at=NULL,updated_at=? WHERE status='RUNNING'",
              ts(now));
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
            "SELECT COALESCE(SUM(status='STAGED'),0),COALESCE(SUM(status='QUEUED'),0),COALESCE(SUM(status='RUNNING'),0),COALESCE(SUM(status='SUCCEEDED'),0),COALESCE(SUM(status='FAILED'),0) FROM ingestion_file WHERE batch_id=?",
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
            tags(rs.getString(14))),
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
