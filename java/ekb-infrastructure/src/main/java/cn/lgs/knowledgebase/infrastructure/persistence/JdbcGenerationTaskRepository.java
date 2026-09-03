package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcGenerationTaskRepository implements GenerationTaskRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcGenerationTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    if (transactions == null) throw new IllegalArgumentException("transactions are required");
    this.jdbc = jdbc;
    this.transactions = transactions;
    ensureSchema();
  }

  @Override
  public GenerationTask queue(
      String versionId, GenerationTaskType taskType, String subjectId, Instant now) {
    String version = requireText(versionId, "version id");
    if (taskType == null) throw new IllegalArgumentException("generation task type is required");
    String subject = text(subjectId);
    String key = idempotencyKey(version, taskType, subject);
    jdbc.update(
        "INSERT IGNORE INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,input_hash,attempt,created_at) VALUES(?,?,?,?,?,'PENDING',?,0,?)",
        UUID.randomUUID().toString(),
        version,
        taskType.name(),
        nullable(subject),
        key,
        KnowledgeDomain.sha256(key),
        ts(now == null ? Instant.now() : now));
    // A completed SUMMARY task may still have produced an inactive artifact when
    // grounding or coverage failed.  Keep the same idempotency key and task row,
    // but make a subsequent explicit start a durable, in-place rebuild.  This
    // never creates duplicate downstream tasks and never replaces raw evidence.
    if (taskType == GenerationTaskType.SUMMARY) {
      jdbc.update(
          "UPDATE compile_task t SET status='PENDING',output_hash=NULL,error_code=NULL,error_message=NULL,started_at=NULL,finished_at=NULL "
              + "WHERE t.idempotency_key=? AND t.status='SUCCEEDED' "
              + "AND NOT EXISTS (SELECT 1 FROM summary s WHERE s.version_id=t.version_id "
              + "AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS')",
          key);
    }
    return findByIdempotencyKey(key);
  }

  @Override
  public List<GenerationTask> claimPending(int limit, Instant now) {
    int effectiveLimit = limit <= 0 ? 1 : Math.min(limit, 16);
    Instant claimedAt = now == null ? Instant.now() : now;
    List<GenerationTask> claimed =
        transactions.execute(
            status -> {
              List<String> ids =
                  jdbc.query(
                      "SELECT task_id FROM compile_task WHERE status='PENDING' AND task_type IN ('SUMMARY','CONCEPT','GRAPH','WIKI') ORDER BY CASE task_type WHEN 'SUMMARY' THEN 0 WHEN 'CONCEPT' THEN 1 WHEN 'GRAPH' THEN 2 WHEN 'WIKI' THEN 3 ELSE 4 END, created_at,task_id LIMIT ? FOR UPDATE SKIP LOCKED",
                      (rs, rowNum) -> rs.getString(1),
                      effectiveLimit);
              if (ids.isEmpty()) return List.of();
              String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
              Object[] updateArgs = new Object[ids.size() + 1];
              updateArgs[0] = ts(claimedAt);
              for (int i = 0; i < ids.size(); i++) updateArgs[i + 1] = ids.get(i);
              jdbc.update(
                  "UPDATE compile_task SET status='RUNNING',attempt=attempt+1,started_at=?,finished_at=NULL,error_code=NULL,error_message=NULL WHERE task_id IN ("
                      + marks
                      + ") AND status='PENDING'",
                  updateArgs);
              return readTasks(ids);
            });
    return claimed == null ? List.of() : List.copyOf(claimed);
  }

  @Override
  public List<GenerationTask> claimPendingEmbeddingRebuild(int limit, Instant now) {
    int effectiveLimit = limit <= 0 ? 1 : Math.min(limit, 1);
    Instant claimedAt = now == null ? Instant.now() : now;
    List<GenerationTask> claimed =
        transactions.execute(
            status -> {
              List<String> ids =
                  jdbc.query(
                      "SELECT task_id FROM compile_task WHERE status='PENDING' AND task_type='EMBEDDING_REBUILD' ORDER BY created_at,task_id LIMIT ? FOR UPDATE SKIP LOCKED",
                      (rs, rowNum) -> rs.getString(1),
                      effectiveLimit);
              if (ids.isEmpty()) return List.of();
              String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
              Object[] updateArgs = new Object[ids.size() + 1];
              updateArgs[0] = ts(claimedAt);
              for (int i = 0; i < ids.size(); i++) updateArgs[i + 1] = ids.get(i);
              jdbc.update(
                  "UPDATE compile_task SET status='RUNNING',attempt=attempt+1,started_at=?,finished_at=NULL,error_code=NULL,error_message=NULL WHERE task_id IN ("
                      + marks
                      + ") AND status='PENDING'",
                  updateArgs);
              return readTasks(ids);
            });
    return claimed == null ? List.of() : List.copyOf(claimed);
  }

  @Override
  public void complete(GenerationTask task, String outputHash, Instant now) {
    if (task == null) throw new IllegalArgumentException("generation task is required");
    int rows =
        jdbc.update(
            "UPDATE compile_task SET status='SUCCEEDED',output_hash=?,error_code=NULL,error_message=NULL,finished_at=? WHERE task_id=? AND status='RUNNING'",
            nullable(outputHash),
            ts(now == null ? Instant.now() : now),
            task.taskId());
    if (rows == 0)
      throw new IllegalStateException("generation task is not RUNNING: " + task.taskId());
  }

  @Override
  public void completeConceptAndQueueDerived(GenerationTask task, String outputHash, Instant now) {
    if (task == null || task.taskType() != GenerationTaskType.CONCEPT) {
      throw new IllegalArgumentException("running CONCEPT task is required");
    }
    if (task.subjectId().isBlank()) {
      throw new IllegalArgumentException("CONCEPT task summary ID is required");
    }
    Instant completedAt = now == null ? Instant.now() : now;
    transactions.executeWithoutResult(
        ignored -> {
          complete(task, outputHash, completedAt);
          queue(task.versionId(), GenerationTaskType.GRAPH, task.subjectId(), completedAt);
          Integer total =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM compile_task WHERE version_id=? AND task_type='CONCEPT'",
                  Integer.class,
                  task.versionId());
          Integer unfinished =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM compile_task WHERE version_id=? AND task_type='CONCEPT' AND status<>'SUCCEEDED'",
                  Integer.class,
                  task.versionId());
          if (total != null && total > 0 && unfinished != null && unfinished == 0) {
            queue(task.versionId(), GenerationTaskType.WIKI, "", completedAt);
          }
        });
  }

  @Override
  public void fail(String taskId, String errorCode, String errorMessage, Instant now) {
    int rows =
        jdbc.update(
            "UPDATE compile_task SET status='FAILED',error_code=?,error_message=?,finished_at=? WHERE task_id=? AND status='RUNNING'",
            text(errorCode).isEmpty() ? "GENERATION_FAILED" : text(errorCode),
            truncate(errorMessage, 8000),
            ts(now == null ? Instant.now() : now),
            requireText(taskId, "generation task id"));
    if (rows == 0) throw new IllegalStateException("generation task is not RUNNING: " + taskId);
  }

  @Override
  public void retry(String taskId, Instant now) {
    int rows =
        jdbc.update(
            "UPDATE compile_task SET status='PENDING',error_code=NULL,error_message=NULL,started_at=NULL,finished_at=NULL WHERE task_id=? AND status='FAILED'",
            requireText(taskId, "generation task id"));
    if (rows == 0) throw new IllegalStateException("only FAILED generation tasks can be retried");
  }

  @Override
  public void resetInterrupted(Instant now) {
    jdbc.update(
        "UPDATE compile_task SET status='PENDING',error_code='WORKER_INTERRUPTED',error_message='generation worker restarted before task completion',started_at=NULL,finished_at=NULL WHERE status='RUNNING' AND task_type IN ('SUMMARY','CONCEPT','GRAPH','WIKI','EMBEDDING_REBUILD')");
  }

  private GenerationTask findByIdempotencyKey(String key) {
    return jdbc
        .query(
            "SELECT task_id,version_id,task_type,COALESCE(subject_id,''),status,attempt,COALESCE(error_code,''),COALESCE(error_message,''),created_at FROM compile_task WHERE idempotency_key=?",
            (rs, rowNum) -> scan(rs),
            key)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("queued generation task not found"));
  }

  private List<GenerationTask> readTasks(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    return jdbc.query(
        "SELECT task_id,version_id,task_type,COALESCE(subject_id,''),status,attempt,COALESCE(error_code,''),COALESCE(error_message,''),created_at FROM compile_task WHERE task_id IN ("
            + marks
            + ") ORDER BY created_at,task_id",
        (rs, rowNum) -> scan(rs),
        ids.toArray());
  }

  private GenerationTask scan(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new GenerationTask(
        rs.getString(1),
        rs.getString(2),
        GenerationTaskType.valueOf(rs.getString(3)),
        rs.getString(4),
        GenerationTaskStatus.valueOf(rs.getString(5)),
        rs.getInt(6),
        rs.getString(7),
        rs.getString(8),
        rs.getTimestamp(9).toInstant());
  }

  private void ensureSchema() {
    Integer subjectColumns =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compile_task' AND column_name='subject_id'",
            Integer.class);
    if (subjectColumns == null || subjectColumns == 0) {
      jdbc.execute(
          "ALTER TABLE compile_task ADD COLUMN subject_id VARCHAR(255) CHARACTER SET ascii NULL AFTER task_type");
    }
    Integer indexes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compile_task' AND index_name='idx_compile_version_type_status'",
            Integer.class);
    if (indexes == null || indexes == 0) {
      jdbc.execute(
          "CREATE INDEX idx_compile_version_type_status ON compile_task(version_id,task_type,status)");
    }
  }

  private static String idempotencyKey(
      String versionId, GenerationTaskType taskType, String subjectId) {
    return "generation:" + taskType.name().toLowerCase() + ":" + versionId + ":" + subjectId;
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static String nullable(String value) {
    String text = text(value);
    return text.isEmpty() ? null : text;
  }

  private static String requireText(String value, String name) {
    String text = text(value);
    if (text.isEmpty()) throw new IllegalArgumentException(name + " is required");
    return text;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
