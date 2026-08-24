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
  private final String knowledgeBaseId;
  private final String ownerId = UUID.randomUUID().toString();
  private final java.util.Map<String, Long> recoveryTicks =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<String, String> recoveryCursors =
      new java.util.concurrent.ConcurrentHashMap<>();

  public JdbcGenerationTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this(jdbc, transactions, "enterprise");
  }

  public JdbcGenerationTaskRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, String knowledgeBaseId) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    if (transactions == null) throw new IllegalArgumentException("transactions are required");
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.knowledgeBaseId = requireText(knowledgeBaseId, "knowledge base identity");
  }

  @Override
  public GenerationTask queue(
      String versionId, GenerationTaskType taskType, String subjectId, Instant now) {
    String version = requireText(versionId, "version id");
    if (taskType == null) throw new IllegalArgumentException("generation task type is required");
    String subject = text(subjectId);
    String key = idempotencyKey(version, taskType, subject);
    if (!"enterprise".equals(knowledgeBaseId))
      key = KnowledgeDomain.sha256(knowledgeBaseId.length() + ":" + knowledgeBaseId + ":" + key);
    jdbc.update(
        "INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,input_hash,attempt,created_at,knowledge_base_id) VALUES(?,?,?,?,?,'PENDING',?,0,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID().toString(),
        version,
        taskType.name(),
        nullable(subject),
        key,
        KnowledgeDomain.sha256(key),
        ts(now == null ? Instant.now() : now),
        knowledgeBaseId);
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
  public GenerationTask queueRepair(
      String versionId, GenerationTaskType type, String subjectId, Instant now) {
    String subject = subjectId;
    if (type == GenerationTaskType.SUMMARY && text(subject).isBlank()) {
      var acl =
          jdbc.queryForObject(
              "SELECT acl_version FROM kb_source_authority WHERE version_id=?",
              Long.class,
              versionId);
      subject = "repair/acl/" + acl;
    }
    var task = queue(versionId, type, subject, now);
    jdbc.update(
        "UPDATE compile_task SET priority=100 WHERE task_id=? AND knowledge_base_id=?",
        task.taskId(),
        knowledgeBaseId);
    return task;
  }

  @Override
  public List<GenerationTask> claimPending(int limit, Instant now) {
    return claim(
        limit,
        List.of(
            GenerationTaskType.SOURCE_ASSOCIATION,
            GenerationTaskType.SUMMARY,
            GenerationTaskType.CONCEPT,
            GenerationTaskType.GRAPH,
            GenerationTaskType.WIKI));
  }

  @Override
  public List<GenerationTask> claimPendingEmbeddingRebuild(int limit, Instant now) {
    return claim(1, List.of(GenerationTaskType.EMBEDDING_REBUILD));
  }

  private List<GenerationTask> claim(int limit, List<GenerationTaskType> types) {
    boolean compilation = !types.contains(GenerationTaskType.EMBEDDING_REBUILD);
    String scope = types.toString();
    long tick = System.nanoTime();
    Long previous = recoveryTicks.get(scope);
    if (previous == null || tick - previous >= java.time.Duration.ofSeconds(15).toNanos()) {
      resetInterrupted(types, null);
      recoveryTicks.put(scope, tick);
    }
    List<GenerationTask> claimed =
        transactions.execute(
            ignored -> {
              if (compilation && !acquireCompilationControl()) return List.<GenerationTask>of();
              String typeMarks = String.join(",", Collections.nCopies(types.size(), "?"));
              List<Object> args =
                  new java.util.ArrayList<>(types.stream().map(Enum::name).toList());
              args.add(knowledgeBaseId);
              args.add(compilation ? 1 : Math.max(1, Math.min(limit, 16)));
              List<String> ids =
                  jdbc.query(
                      "SELECT task_id FROM compile_task t WHERE (status='PENDING' OR "
                          + "(status='RETRY_WAIT' AND next_attempt_at<=clock_timestamp())) AND attempt<5 "
                          + "AND task_type IN ("
                          + typeMarks
                          + ") AND knowledge_base_id=? "
                          + "AND EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=t.version_id) "
                          + "ORDER BY priority DESC,CASE task_type WHEN 'SOURCE_ASSOCIATION' THEN -1 WHEN 'SUMMARY' THEN 0 WHEN 'CONCEPT' THEN 1 "
                          + "WHEN 'GRAPH' THEN 2 WHEN 'WIKI' THEN 3 ELSE 4 END,created_at,task_id "
                          + "LIMIT ? FOR UPDATE SKIP LOCKED",
                      (rs, row) -> rs.getString(1),
                      args.toArray());
              List<String> started = new java.util.ArrayList<>();
              for (String id : ids) {
                List<Long> acl =
                    jdbc.query(
                        "SELECT m.acl_version FROM compile_task t "
                            + "JOIN document_version v ON v.version_id=t.version_id JOIN document d ON d.document_id=v.document_id "
                            + "JOIN document_metadata m ON m.version_id=v.version_id WHERE t.task_id=? AND d.status='ACTIVE' "
                            + "AND v.lifecycle='ACTIVE' FOR SHARE OF v,d,m",
                        (rs, row) -> rs.getLong(1),
                        id);
                if (acl.size() != 1) continue;
                jdbc.update(
                    "WITH stage AS MATERIALIZED (SELECT clock_timestamp() AS started_at) "
                        + "UPDATE compile_task SET status='RUNNING',attempt=attempt+1,"
                        + "execution_epoch=execution_epoch+1,owner_id=?,started_at=stage.started_at,"
                        + "stage_deadline=stage.started_at+INTERVAL '600 seconds',source_acl_version=?,"
                        + "finished_at=NULL,next_attempt_at=NULL,error_code=NULL,error_message=NULL FROM stage WHERE task_id=?",
                    ownerId,
                    acl.get(0),
                    id);
                started.add(id);
              }
              var tasks = readTasks(started);
              if (compilation && !tasks.isEmpty()) {
                var task = tasks.get(0);
                jdbc.update(
                    "UPDATE kb_compilation_control SET control_epoch=control_epoch+1,task_id=?,task_execution_epoch=?,owner_id=?,stage_deadline=? WHERE knowledge_base_id=?",
                    task.taskId(),
                    task.executionEpoch(),
                    task.ownerId(),
                    ts(task.stageDeadline()),
                    knowledgeBaseId);
              }
              return tasks;
            });
    return claimed == null ? List.of() : List.copyOf(claimed);
  }

  @Override
  public cn.lgs.knowledgebase.application.compilation.CompilationPublication publication(
      GenerationTask task) {
    requireClaim(task);
    return new JdbcCompilationPublication(
        jdbc, transactions, () -> assertCurrentClaim(task, true), task);
  }

  @Override
  public void withinPublication(GenerationTask task, Runnable publication) {
    transactions.executeWithoutResult(
        ignored -> {
          assertCurrentClaim(task, true);
          publication.run();
          // A database lock does not extend the stage. A late callback rolls back.
          assertCurrentClaim(task, true);
        });
  }

  private void assertCurrentClaim(GenerationTask task, boolean sourceRequired) {
    requireClaim(task);
    Integer valid =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT task_id FROM compile_task WHERE task_id=? AND status='RUNNING' "
                + "AND attempt=? AND version_id=? AND task_type=? AND COALESCE(subject_id,'')=? "
                + "AND owner_id=? AND execution_epoch=? AND source_acl_version=? AND stage_deadline=? AND stage_deadline>clock_timestamp() FOR UPDATE) current_claim",
            Integer.class,
            task.taskId(),
            task.attempt(),
            task.versionId(),
            task.taskType().name(),
            task.subjectId(),
            task.ownerId(),
            task.executionEpoch(),
            task.sourceAclVersion(),
            ts(task.stageDeadline()));
    if (valid == null || valid != 1)
      throw new IllegalStateException("generation execution claim expired or replaced");
    if (task.taskType() != GenerationTaskType.EMBEDDING_REBUILD) {
      Integer control =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM (SELECT knowledge_base_id FROM kb_compilation_control WHERE knowledge_base_id=? AND task_id=? AND task_execution_epoch=? AND owner_id=? AND stage_deadline=? AND stage_deadline>clock_timestamp() FOR UPDATE) current_control",
              Integer.class,
              knowledgeBaseId,
              task.taskId(),
              task.executionEpoch(),
              task.ownerId(),
              ts(task.stageDeadline()));
      if (control == null || control != 1)
        throw new IllegalStateException("knowledge base compilation claim expired or replaced");
    }
    if (sourceRequired) {
      List<Long> versions =
          jdbc.query(
              "SELECT m.acl_version FROM document_version v JOIN document d ON d.document_id=v.document_id "
                  + "JOIN document_metadata m ON m.version_id=v.version_id WHERE v.version_id=? "
                  + "AND v.lifecycle='ACTIVE' AND d.status='ACTIVE' AND m.acl_version=? FOR SHARE OF v,d,m",
              (rs, row) -> rs.getLong(1),
              task.versionId(),
              task.sourceAclVersion());
      if (versions.size() != 1)
        throw new IllegalStateException("generation source or permission changed");
    }
  }

  @Override
  public void complete(GenerationTask task, String outputHash, Instant now) {
    transactions.executeWithoutResult(
        ignored -> {
          assertCurrentClaim(task, true);
          int changed =
              jdbc.update(
                  "UPDATE compile_task SET status='SUCCEEDED',output_hash=?,error_code=NULL,error_message=NULL,"
                      + "finished_at=clock_timestamp() WHERE task_id=? AND stage_deadline>clock_timestamp()",
                  nullable(outputHash),
                  task.taskId());
          if (changed != 1)
            throw new IllegalStateException(
                "generation execution deadline passed before completion");
          releaseCompilationControl(task);
        });
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
  public void fail(GenerationTask task, String errorCode, String errorMessage, Instant now) {
    recordFailure(task, errorCode, errorMessage, null, now);
  }

  @Override
  public void defer(
      GenerationTask task,
      String errorCode,
      String errorMessage,
      Instant nextAttemptAt,
      Instant now) {
    if (nextAttemptAt == null || now == null || !nextAttemptAt.isAfter(now))
      throw new IllegalArgumentException("future retry deadline is required");
    recordFailure(task, errorCode, errorMessage, nextAttemptAt, now);
  }

  private void recordFailure(
      GenerationTask task,
      String errorCode,
      String errorMessage,
      Instant nextAttemptAt,
      Instant now) {
    requireClaim(task);
    Instant failedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    Instant retryAt =
        nextAttemptAt == null
            ? null
            : failedAt.plus(java.time.Duration.between(now, nextAttemptAt));
    String outcome = nextAttemptAt == null ? "FAILED" : "RETRY_WAIT";
    transactions.executeWithoutResult(
        ignored -> {
          assertCurrentClaim(task, false);
          int rows =
              jdbc.update(
                  "UPDATE compile_task SET status=?,error_code=?,error_message=?,finished_at=?,next_attempt_at=? WHERE task_id=? AND status='RUNNING' AND attempt=? AND version_id=? AND task_type=? AND COALESCE(subject_id,'')=?",
                  outcome,
                  text(errorCode).isEmpty() ? "GENERATION_FAILED" : text(errorCode),
                  truncate(errorMessage, 8000),
                  nextAttemptAt == null ? ts(failedAt) : null,
                  ts(retryAt),
                  task.taskId(),
                  task.attempt(),
                  task.versionId(),
                  task.taskType().name(),
                  task.subjectId());
          if (rows == 0)
            throw new IllegalStateException(
                "generation task claim is no longer current: " + task.taskId());
          jdbc.update(
              "INSERT INTO compile_task_failure(task_id,attempt,version_id,task_type,subject_id,outcome,error_code,error_message,occurred_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
              task.taskId(),
              task.attempt(),
              task.versionId(),
              task.taskType().name(),
              nullable(task.subjectId()),
              outcome,
              text(errorCode).isEmpty() ? "GENERATION_FAILED" : text(errorCode),
              truncate(errorMessage, 8000),
              ts(failedAt),
              ts(retryAt));
          releaseCompilationControl(task);
        });
  }

  @Override
  public void retry(String taskId, Instant now) {
    int rows =
        jdbc.update(
            "UPDATE compile_task SET status='PENDING',error_code=NULL,error_message=NULL,started_at=NULL,finished_at=NULL,next_attempt_at=NULL WHERE task_id=? AND knowledge_base_id=? AND status='FAILED' AND attempt<5",
            requireText(taskId, "generation task id"),
            knowledgeBaseId);
    if (rows == 0)
      throw new IllegalStateException(
          "only FAILED generation tasks with remaining business attempts can be retried");
  }

  @Override
  public synchronized void resetInterrupted(List<GenerationTaskType> taskTypes, Instant now) {
    if (taskTypes == null
        || taskTypes.isEmpty()
        || taskTypes.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("explicit generation task types are required");
    List<String> types = taskTypes.stream().map(Enum::name).distinct().sorted().toList();
    String scope = types.toString();
    String cursor = recoveryCursors.getOrDefault(scope, "");
    String marks = String.join(",", Collections.nCopies(types.size(), "?"));
    List<String> recovered =
        transactions.execute(
            ignored -> {
              List<Object> args = new java.util.ArrayList<>(types);
              args.add(cursor);
              List<String> ids =
                  jdbc.query(
                      "SELECT task_id FROM compile_task WHERE status='RUNNING' AND stage_deadline<=clock_timestamp() "
                          + "AND task_type IN ("
                          + marks
                          + ") AND task_id>? AND knowledge_base_id=? ORDER BY task_id LIMIT 100 FOR UPDATE SKIP LOCKED",
                      (rs, row) -> rs.getString(1),
                      append(args, knowledgeBaseId));
              for (GenerationTask task : readTasks(ids)) {
                Instant failedAt =
                    jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
                Instant next =
                    task.attempt() >= 5
                        ? null
                        : failedAt.plusSeconds(new long[] {10, 30, 120, 300}[task.attempt() - 1]);
                String outcome = next == null ? "FAILED" : "RETRY_WAIT";
                jdbc.update(
                    "UPDATE compile_task SET status=?,owner_id=NULL,error_code='STAGE_DEADLINE_EXPIRED',"
                        + "error_message='database stage deadline expired; no lease renewal',finished_at=?,next_attempt_at=? WHERE task_id=?",
                    outcome,
                    next == null ? ts(failedAt) : null,
                    ts(next),
                    task.taskId());
                jdbc.update(
                    "INSERT INTO compile_task_failure(task_id,attempt,version_id,task_type,subject_id,outcome,"
                        + "error_code,error_message,occurred_at,next_attempt_at) VALUES(?,?,?,?,?,?,'STAGE_DEADLINE_EXPIRED',"
                        + "'database stage deadline expired; no lease renewal',?,?)",
                    task.taskId(),
                    task.attempt(),
                    task.versionId(),
                    task.taskType().name(),
                    nullable(task.subjectId()),
                    outcome,
                    ts(failedAt),
                    ts(next));
              }
              return ids;
            });
    recoveryCursors.put(
        scope, recovered == null || recovered.isEmpty() ? "" : recovered.get(recovered.size() - 1));
  }

  private boolean acquireCompilationControl() {
    jdbc.update(
        "INSERT INTO kb_compilation_control(knowledge_base_id) VALUES(?) ON CONFLICT DO NOTHING",
        knowledgeBaseId);
    var available =
        jdbc.query(
            "SELECT knowledge_base_id FROM kb_compilation_control WHERE knowledge_base_id=? AND (task_id IS NULL OR stage_deadline<=clock_timestamp()) FOR UPDATE SKIP LOCKED",
            (rs, n) -> rs.getString(1),
            knowledgeBaseId);
    if (available.isEmpty()) return false;
    // Migration/startup does not steal an execution that began before the control row existed.
    return jdbc.queryForObject(
            "SELECT count(*) FROM compile_task WHERE knowledge_base_id=? AND status='RUNNING' AND task_type<>'EMBEDDING_REBUILD' AND stage_deadline>clock_timestamp()",
            Integer.class,
            knowledgeBaseId)
        == 0;
  }

  private void releaseCompilationControl(GenerationTask task) {
    if (task.taskType() == GenerationTaskType.EMBEDDING_REBUILD) return;
    int changed =
        jdbc.update(
            "UPDATE kb_compilation_control SET task_id=NULL,task_execution_epoch=NULL,owner_id=NULL,stage_deadline=NULL WHERE knowledge_base_id=? AND task_id=? AND task_execution_epoch=? AND owner_id=?",
            knowledgeBaseId,
            task.taskId(),
            task.executionEpoch(),
            task.ownerId());
    if (changed != 1)
      throw new IllegalStateException("knowledge base compilation control is no longer owned");
  }

  private static Object[] append(List<Object> values, Object value) {
    var args = new java.util.ArrayList<>(values);
    args.add(value);
    return args.toArray();
  }

  private static void requireClaim(GenerationTask task) {
    if (task == null
        || task.status() != GenerationTaskStatus.RUNNING
        || task.attempt() < 1
        || task.ownerId().isBlank()
        || task.executionEpoch() < 1
        || task.stageDeadline() == null) {
      throw new IllegalArgumentException("a claimed RUNNING generation task is required");
    }
  }

  private GenerationTask findByIdempotencyKey(String key) {
    return jdbc
        .query(
            "SELECT task_id,version_id,task_type,COALESCE(subject_id,''),status,attempt,COALESCE(error_code,''),COALESCE(error_message,''),created_at,COALESCE(owner_id,''),execution_epoch,stage_deadline,COALESCE(source_acl_version,0) FROM compile_task WHERE idempotency_key=?",
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
        "SELECT task_id,version_id,task_type,COALESCE(subject_id,''),status,attempt,COALESCE(error_code,''),COALESCE(error_message,''),created_at,COALESCE(owner_id,''),execution_epoch,stage_deadline,COALESCE(source_acl_version,0) FROM compile_task WHERE task_id IN ("
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
        rs.getTimestamp(9).toInstant(),
        rs.getString(10),
        rs.getLong(11),
        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant(),
        rs.getLong(13));
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
