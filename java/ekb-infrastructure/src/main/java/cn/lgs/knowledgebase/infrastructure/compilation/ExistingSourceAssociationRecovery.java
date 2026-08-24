package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.compilation.KnowledgeEntryCatalogue;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Revisit cold sources when grounded entry identities or source authority change. */
public final class ExistingSourceAssociationRecovery {
  private final JdbcTemplate jdbc;
  private final GenerationTaskRepository tasks;
  private final KnowledgeEntryCatalogue entries;
  private final String knowledgeBaseId;
  private String snapshot;
  private String cursor = "";
  private boolean complete;

  public ExistingSourceAssociationRecovery(
      JdbcTemplate jdbc,
      GenerationTaskRepository tasks,
      KnowledgeEntryCatalogue entries,
      String knowledgeBaseId) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.tasks = Objects.requireNonNull(tasks);
    this.entries = Objects.requireNonNull(entries);
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted knowledge base is required");
    this.knowledgeBaseId = knowledgeBaseId;
  }

  /** No model calls or source heat; at most 100 current sources are inspected in one cycle. */
  public synchronized int recover() {
    var identities =
        jdbc.query(
            """
            SELECT e.entry_id||':'||e.revision::text||':'||e.scope_json::text||':'||
              (SELECT string_agg(b.summary_id||':'||b.source_version_id||':'||b.acl_version::text||':'||s.content_hash,',' ORDER BY b.summary_id)
               FROM kb_entry_build_input b JOIN summary s ON s.summary_id=b.summary_id
               WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision)
            FROM kb_knowledge_entry e
            WHERE e.knowledge_base_id=? AND e.lifecycle IN ('ACTIVE','STALE')
              AND e.scope_json->>'identity_status'='SCOPE_GROUNDED'
              AND jsonb_array_length(e.scope_json->'anchors')>0
              AND EXISTS(SELECT 1 FROM kb_entry_build_input b WHERE b.knowledge_base_id=e.knowledge_base_id
                AND b.entry_id=e.entry_id AND b.entry_revision=e.revision)
              AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b
                LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id
                LEFT JOIN summary s ON s.summary_id=b.summary_id
                WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision
                  AND (a.version_id IS NULL OR a.acl_version<>b.acl_version OR s.summary_id IS NULL
                    OR s.version_id<>b.source_version_id OR s.lifecycle<>'ACTIVE'
                    OR s.grounding_status<>'PASS' OR s.coverage_status<>'PASS'))
            ORDER BY e.entry_id
            """,
            (rs, n) -> rs.getString(1),
            knowledgeBaseId);
    if (identities.isEmpty()) {
      snapshot = null;
      cursor = "";
      complete = true;
      return 0;
    }
    var authorities =
        jdbc.query(
            "SELECT a.version_id||':'||a.acl_version::text FROM kb_source_authority a "
                + "WHERE EXISTS(SELECT 1 FROM raw_block b WHERE b.version_id=a.version_id) ORDER BY a.version_id",
            (rs, n) -> rs.getString(1));
    String current = KnowledgeDomain.sha256(identities.toString() + "\n" + authorities);
    // Finish a bounded sweep before restarting it, so frequent arrivals cannot starve high IDs.
    if (snapshot == null || complete && !current.equals(snapshot)) {
      snapshot = current;
      cursor = "";
      complete = false;
    }
    if (complete) return 0;
    var versions =
        jdbc.query(
            "SELECT a.version_id,a.acl_version FROM kb_source_authority a WHERE a.version_id>? "
                + "AND EXISTS(SELECT 1 FROM raw_block b WHERE b.version_id=a.version_id) ORDER BY a.version_id LIMIT 100",
            (rs, n) -> new Source(rs.getString(1), rs.getLong(2)),
            cursor);
    int queued = 0;
    for (var source : versions) {
      var candidates = entries.relatedCandidates(source.versionId(), 24);
      if (!candidates.isEmpty()) {
        String subject = "related/" + fingerprint(source, candidates);
        int existing =
            jdbc.queryForObject(
                "SELECT count(*) FROM compile_task WHERE knowledge_base_id=? AND version_id=? AND task_type='SOURCE_ASSOCIATION' AND subject_id=?",
                Integer.class,
                knowledgeBaseId,
                source.versionId(),
                subject);
        if (existing == 0) {
          tasks.queue(
              source.versionId(), GenerationTaskType.SOURCE_ASSOCIATION, subject, Instant.now());
          queued++;
        }
      }
      cursor = source.versionId();
    }
    complete = versions.size() < 100;
    return queued;
  }

  private static String fingerprint(Source source, List<KnowledgeEntryCatalogue.Entry> candidates) {
    var value = new StringBuilder().append(source.aclVersion()).append('\n');
    candidates.stream()
        .sorted(Comparator.comparing(KnowledgeEntryCatalogue.Entry::id))
        .forEach(
            entry -> {
              value
                  .append(entry.id())
                  .append(':')
                  .append(entry.revision())
                  .append(':')
                  .append(entry.scopeKey())
                  .append('\n');
              entry.buildInputs().stream()
                  .sorted(Comparator.comparing(KnowledgeEntryCatalogue.BuildInput::summaryId))
                  .forEach(
                      input ->
                          value
                              .append(input.summaryId())
                              .append(':')
                              .append(input.versionId())
                              .append(':')
                              .append(input.aclVersion())
                              .append('\n'));
            });
    return KnowledgeDomain.sha256(value.toString());
  }

  private record Source(String versionId, long aclVersion) {}
}
