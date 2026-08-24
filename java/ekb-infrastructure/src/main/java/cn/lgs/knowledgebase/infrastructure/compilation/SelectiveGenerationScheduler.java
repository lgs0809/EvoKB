package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.compilation.KnowledgeEntryCatalogue;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Receipt-driven selection; native short transactions, no model calls and no invented heat. */
public final class SelectiveGenerationScheduler {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final GenerationTaskRepository tasks;
  private final String knowledgeBaseId;
  private final ExistingSourceAssociationRecovery existingSourceAssociations;

  public SelectiveGenerationScheduler(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      GenerationTaskRepository tasks,
      String knowledgeBaseId) {
    this(jdbc, transactions, tasks, knowledgeBaseId, null);
  }

  public SelectiveGenerationScheduler(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      GenerationTaskRepository tasks,
      String knowledgeBaseId,
      KnowledgeEntryCatalogue entries) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.tasks = Objects.requireNonNull(tasks);
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted knowledge base is required");
    this.knowledgeBaseId = knowledgeBaseId;
    this.existingSourceAssociations =
        entries == null
            ? null
            : new ExistingSourceAssociationRecovery(jdbc, tasks, entries, knowledgeBaseId);
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
  public void selectAtMidnight() {
    selectDaily();
  }

  /** Also catches a missed midnight after restart, once per persisted business day. */
  @Scheduled(fixedDelay = 15000, initialDelay = 15000)
  public void recoverAndCatchUp() {
    repairRawProjections();
    invalidateChangedInputs();
    recoverAssociations();
    recoverExistingSourceAssociations();
    refreshCandidates();
    selectDaily();
  }

  /** An ACL change blocks the old pointer immediately; rebuild through a new owned task. */
  public int repairRawProjections() {
    var pending =
        jdbc.query(
            "SELECT a.version_id,a.acl_version FROM raw_publication p JOIN kb_source_authority a USING(version_id) "
                + "WHERE p.acl_version<>a.acl_version AND NOT EXISTS(SELECT 1 FROM compile_task t "
                + "WHERE t.knowledge_base_id=? AND t.version_id=a.version_id AND t.task_type='EMBEDDING_REBUILD' "
                + "AND t.subject_id='repair/raw/acl/'||a.acl_version::text) "
                + "ORDER BY a.version_id LIMIT 100",
            (rs, n) -> new RawRepair(rs.getString(1), rs.getLong(2)),
            knowledgeBaseId);
    for (var item : pending)
      tasks.queueRepair(
          item.versionId(),
          GenerationTaskType.EMBEDDING_REBUILD,
          "repair/raw/acl/" + item.aclVersion(),
          Instant.now());
    return pending.size();
  }

  public void refreshCandidates() {
    transactions.executeWithoutResult(
        ignored -> {
          jdbc.queryForList(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              "selective-candidates:" + knowledgeBaseId);
          jdbc.update(
              "UPDATE kb_generation_candidate SET qualified_at='-infinity'::timestamptz WHERE knowledge_base_id=? AND selected_task_id IS NULL",
              knowledgeBaseId);
          jdbc.update(
              """
          INSERT INTO kb_generation_candidate(knowledge_base_id,stage,target_id,target_revision,source_version_id,contributions)
          SELECT ?,'SUMMARY',u.version_id,1,u.version_id,count(*) FROM source_usage_contribution u
          JOIN kb_source_authority a ON a.version_id=u.version_id
          WHERE u.created_at>=clock_timestamp()-INTERVAL '30 days'
            AND NOT EXISTS(SELECT 1 FROM summary s WHERE s.version_id=u.version_id AND s.lifecycle='ACTIVE'
              AND s.grounding_status='PASS' AND s.coverage_status='PASS')
            AND NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=? AND t.version_id=u.version_id AND t.task_type='SUMMARY')
          GROUP BY u.version_id HAVING count(*)>=3
          ON CONFLICT(knowledge_base_id,stage,target_id,target_revision) DO UPDATE
          SET contributions=EXCLUDED.contributions,qualified_at=clock_timestamp()
          """,
              knowledgeBaseId,
              knowledgeBaseId);
          jdbc.update(
              """
          INSERT INTO kb_generation_candidate(knowledge_base_id,stage,target_id,target_revision,source_version_id,contributions)
          SELECT e.knowledge_base_id,'WIKI',e.entry_id,e.revision,min(s.source_version_id),h.uses
          FROM kb_knowledge_entry e JOIN (SELECT DISTINCT knowledge_base_id,entry_id,source_version_id FROM kb_entry_source) s USING(knowledge_base_id,entry_id)
          JOIN (SELECT asset_id,count(*) uses FROM asset_usage_contribution
            WHERE created_at>=clock_timestamp()-INTERVAL '30 days' GROUP BY asset_id HAVING count(*)>=5) h ON h.asset_id=e.entry_id
          WHERE e.knowledge_base_id=? AND e.stage='CONCEPT' AND e.lifecycle='ACTIVE'
            AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id
              WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision
                AND (a.version_id IS NULL OR a.acl_version<>b.acl_version))
            AND NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=e.knowledge_base_id AND t.task_type='WIKI'
              AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text)
          GROUP BY e.knowledge_base_id,e.entry_id,e.revision,h.uses
          ON CONFLICT(knowledge_base_id,stage,target_id,target_revision) DO UPDATE
          SET contributions=EXCLUDED.contributions,qualified_at=clock_timestamp()
          """,
              knowledgeBaseId);
          // Source heat ranks graph work; it does not create any asset usage contribution.
          jdbc.update(
              """
          INSERT INTO kb_generation_candidate(knowledge_base_id,stage,target_id,target_revision,source_version_id,contributions)
          SELECT e.knowledge_base_id,'GRAPH',e.entry_id,e.revision,min(s.source_version_id),sum(h.uses)
          FROM kb_knowledge_entry e JOIN (SELECT DISTINCT knowledge_base_id,entry_id,source_version_id FROM kb_entry_source) s USING(knowledge_base_id,entry_id)
          JOIN (SELECT version_id,count(*) uses FROM source_usage_contribution
            WHERE created_at>=clock_timestamp()-INTERVAL '30 days' GROUP BY version_id HAVING count(*)>=3) h ON h.version_id=s.source_version_id
          WHERE e.knowledge_base_id=? AND e.lifecycle='ACTIVE' AND e.scope_json->>'identity_status'='SCOPE_GROUNDED'
            AND e.entry_type IN ('COMPANY','ORGANIZATION','PERSON','CONTRACT','PROJECT','LEGAL_CASE','LICENSE')
            AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id
              WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision
                AND (a.version_id IS NULL OR a.acl_version<>b.acl_version))
            AND NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=e.knowledge_base_id AND t.task_type='GRAPH'
              AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text)
          GROUP BY e.knowledge_base_id,e.entry_id,e.revision
          ON CONFLICT(knowledge_base_id,stage,target_id,target_revision) DO UPDATE
          SET contributions=EXCLUDED.contributions,qualified_at=clock_timestamp()
          """,
              knowledgeBaseId);
        });
  }

  /** Covers a crash after Raw commit but before its best-effort queue notification. */
  public int recoverAssociations() {
    var versions =
        jdbc.query(
            "SELECT a.version_id FROM kb_source_authority a WHERE EXISTS(SELECT 1 FROM raw_block b WHERE b.version_id=a.version_id) "
                + "AND NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=? AND t.task_type='SOURCE_ASSOCIATION' AND t.version_id=a.version_id) "
                + "ORDER BY a.version_id LIMIT 100",
            (rs, n) -> rs.getString(1),
            knowledgeBaseId);
    for (String version : versions)
      tasks.queue(version, GenerationTaskType.SOURCE_ASSOCIATION, "", Instant.now());
    return versions.size();
  }

  public int recoverExistingSourceAssociations() {
    return existingSourceAssociations == null ? 0 : existingSourceAssociations.recover();
  }

  public int selectDaily() {
    Integer selected =
        transactions.execute(
            ignored -> {
              jdbc.queryForList(
                  "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                  "selective-day:" + knowledgeBaseId);
              var day =
                  jdbc.queryForObject(
                      "SELECT (clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::date",
                      java.sql.Date.class);
              jdbc.update(
                  "INSERT INTO kb_generation_daily_batch(knowledge_base_id,business_day) VALUES(?,?) ON CONFLICT DO NOTHING",
                  knowledgeBaseId,
                  day);
              var completed =
                  jdbc.query(
                      "SELECT completed_at IS NOT NULL FROM kb_generation_daily_batch WHERE knowledge_base_id=? AND business_day=? FOR UPDATE",
                      (rs, n) -> rs.getBoolean(1),
                      knowledgeBaseId,
                      day);
              if (completed.get(0)) return 0;
              // Refresh and consume under the same database transaction. A competing refresh
              // must finish before this refresh acquires its advisory lock; otherwise SKIP LOCKED
              // could observe every candidate as locked and commit an empty completed day.
              refreshCandidates();
              int total = 0;
              for (var stage : List.of("SUMMARY", "WIKI", "GRAPH")) {
                int budget = stage.equals("SUMMARY") ? 20 : 10;
                Integer used =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM kb_generation_daily_selection WHERE knowledge_base_id=? AND business_day=? AND stage=?",
                        Integer.class,
                        knowledgeBaseId,
                        day,
                        stage);
                var candidates =
                    jdbc.query(
                        "SELECT target_id,target_revision,source_version_id FROM kb_generation_candidate c WHERE knowledge_base_id=? AND stage=? AND selected_task_id IS NULL "
                            + "AND qualified_at>=clock_timestamp()-INTERVAL '60 seconds' AND EXISTS(SELECT 1 FROM kb_source_authority a WHERE a.version_id=c.source_version_id) "
                            + "ORDER BY contributions DESC,first_queued_at,target_id,target_revision LIMIT ? FOR UPDATE SKIP LOCKED",
                        (rs, n) -> new Target(rs.getString(1), rs.getLong(2), rs.getString(3)),
                        knowledgeBaseId,
                        stage,
                        Math.max(0, budget - used));
                for (var target : candidates) {
                  String subject =
                      stage.equals("SUMMARY")
                          ? ""
                          : "entry/" + target.id() + "/revision/" + target.revision();
                  var task =
                      tasks.queue(
                          target.versionId(),
                          GenerationTaskType.valueOf(stage),
                          subject,
                          Instant.now());
                  jdbc.update(
                      "INSERT INTO kb_generation_daily_selection(knowledge_base_id,business_day,stage,target_id,target_revision,task_id) VALUES(?,?,?,?,?,?)",
                      knowledgeBaseId,
                      day,
                      stage,
                      target.id(),
                      target.revision(),
                      task.taskId());
                  jdbc.update(
                      "UPDATE kb_generation_candidate SET selected_task_id=? WHERE knowledge_base_id=? AND stage=? AND target_id=? AND target_revision=?",
                      task.taskId(),
                      knowledgeBaseId,
                      stage,
                      target.id(),
                      target.revision());
                  total++;
                }
              }
              jdbc.update(
                  "UPDATE kb_generation_daily_batch SET completed_at=clock_timestamp() WHERE knowledge_base_id=? AND business_day=?",
                  knowledgeBaseId,
                  day);
              return total;
            });
    return selected == null ? 0 : selected;
  }

  /**
   * Revocation is enforced by authority reads immediately; this records stale UI state and repairs.
   */
  public int invalidateChangedInputs() {
    Integer changed =
        transactions.execute(
            ignored -> {
              var affected =
                  jdbc.query(
                      "SELECT e.entry_id FROM kb_knowledge_entry e WHERE e.knowledge_base_id=? AND e.lifecycle='ACTIVE' "
                          + "AND EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id "
                          + "WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision "
                          + "AND (a.version_id IS NULL OR a.acl_version<>b.acl_version)) ORDER BY e.entry_id LIMIT 100 FOR UPDATE SKIP LOCKED",
                      (rs, n) -> rs.getString(1),
                      knowledgeBaseId);
              for (String id : affected) {
                jdbc.update(
                    "UPDATE kb_knowledge_entry SET lifecycle='STALE',updated_at=clock_timestamp() WHERE knowledge_base_id=? AND entry_id=?",
                    knowledgeBaseId,
                    id);
                jdbc.update(
                    "UPDATE wiki SET lifecycle='STALE' WHERE wiki_id=? AND lifecycle='ACTIVE'", id);
                // Never clear dependencies or lower clearance to keep the previous projection
                // visible.
              }
              var repairs =
                  jdbc.query(
                      "SELECT DISTINCT a.version_id FROM kb_entry_source s JOIN kb_knowledge_entry e USING(knowledge_base_id,entry_id) "
                          + "JOIN kb_source_authority a ON a.document_id=s.document_id WHERE e.knowledge_base_id=? AND e.lifecycle='STALE' "
                          + "AND (a.version_id<>s.source_version_id OR a.acl_version<>s.acl_version) "
                          + "ORDER BY a.version_id LIMIT 100",
                      (rs, n) -> rs.getString(1),
                      knowledgeBaseId);
              for (String version : repairs)
                tasks.queueRepair(version, GenerationTaskType.SUMMARY, "", Instant.now());
              // Deletion or an uncited model-input revocation may leave no changed member
              // version to regenerate. Rebuild from a surviving current source; the obsolete
              // entry description/dependencies remain inaccessible until a new owned build.
              var survivingRepairs =
                  jdbc.query(
                      """
                  SELECT e.entry_id,e.revision,min(a.version_id),
                    (SELECT string_agg(b.summary_id||':'||b.source_version_id||':'||coalesce(x.acl_version::text,'REVOKED'),',' ORDER BY b.summary_id)
                     FROM kb_entry_build_input b LEFT JOIN kb_source_authority x ON x.version_id=b.source_version_id
                     WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision)
                  FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id)
                  JOIN kb_source_authority a ON a.document_id=s.document_id
                  WHERE e.knowledge_base_id=? AND e.lifecycle='STALE'
                    AND EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority x ON x.version_id=b.source_version_id
                      WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision
                      AND (x.version_id IS NULL OR x.acl_version<>b.acl_version))
                    AND NOT EXISTS(SELECT 1 FROM kb_entry_source changed JOIN kb_source_authority current ON current.document_id=changed.document_id
                      WHERE changed.knowledge_base_id=e.knowledge_base_id AND changed.entry_id=e.entry_id
                      AND (current.version_id<>changed.source_version_id OR current.acl_version<>changed.acl_version))
                  GROUP BY e.knowledge_base_id,e.entry_id,e.revision ORDER BY e.entry_id LIMIT 100
                  """,
                      (rs, n) ->
                          new StaleRepair(
                              rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                      knowledgeBaseId);
              for (var repair : survivingRepairs)
                tasks.queueRepair(
                    repair.versionId(),
                    GenerationTaskType.SUMMARY,
                    "repair/entry/"
                        + repair.id()
                        + "/revision/"
                        + repair.revision()
                        + "/inputs/"
                        + KnowledgeDomain.sha256(repair.inputs()),
                    Instant.now());
              var wikiRepairs =
                  jdbc.query(
                      "SELECT e.entry_id,e.revision,min(s.source_version_id) FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id) "
                          + "WHERE e.knowledge_base_id=? AND e.lifecycle='STALE' AND e.stage='WIKI' "
                          + "AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id "
                          + "WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision AND (a.version_id IS NULL OR a.acl_version<>b.acl_version)) "
                          + "AND (NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=e.knowledge_base_id AND t.task_type='WIKI' AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text) "
                          + "OR NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=e.knowledge_base_id AND t.task_type='GRAPH' AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text)) "
                          + "GROUP BY e.entry_id,e.revision ORDER BY e.entry_id LIMIT 100",
                      (rs, n) -> new Target(rs.getString(1), rs.getLong(2), rs.getString(3)),
                      knowledgeBaseId);
              for (var repair : wikiRepairs) {
                String subject = "entry/" + repair.id() + "/revision/" + repair.revision();
                tasks.queueRepair(
                    repair.versionId(), GenerationTaskType.WIKI, subject, Instant.now());
                tasks.queueRepair(
                    repair.versionId(), GenerationTaskType.GRAPH, subject, Instant.now());
              }
              var graphRepairs =
                  jdbc.query(
                      "SELECT e.entry_id,e.revision,min(s.source_version_id) FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id) "
                          + "WHERE e.knowledge_base_id=? AND EXISTS(SELECT 1 FROM kb_entry_pending_source p WHERE p.knowledge_base_id=e.knowledge_base_id AND p.entry_id=e.entry_id AND p.associated_at IS NOT NULL AND p.graph_repaired_at IS NULL) "
                          + "AND NOT EXISTS(SELECT 1 FROM compile_task t WHERE t.knowledge_base_id=e.knowledge_base_id AND t.task_type='GRAPH' AND t.subject_id='entry/'||e.entry_id||'/revision/'||e.revision::text) "
                          + "AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id "
                          + "WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision AND (a.version_id IS NULL OR a.acl_version<>b.acl_version)) "
                          + "GROUP BY e.entry_id,e.revision ORDER BY e.entry_id LIMIT 100",
                      (rs, n) -> new Target(rs.getString(1), rs.getLong(2), rs.getString(3)),
                      knowledgeBaseId);
              for (var repair : graphRepairs)
                tasks.queueRepair(
                    repair.versionId(),
                    GenerationTaskType.GRAPH,
                    "entry/" + repair.id() + "/revision/" + repair.revision(),
                    Instant.now());
              return affected.size();
            });
    return changed == null ? 0 : changed;
  }

  private record Target(String id, long revision, String versionId) {}

  private record RawRepair(String versionId, long aclVersion) {}

  private record StaleRepair(String id, long revision, String versionId, String inputs) {}
}
