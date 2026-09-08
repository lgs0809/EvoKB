package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** One normal retry chain across ordinary/repair/command subjects of the same Summary input. */
final class SummaryTaskFamily {
  private final JdbcTemplate jdbc;
  private final String knowledgeBase;

  SummaryTaskFamily(JdbcTemplate jdbc, String knowledgeBase) {
    this.jdbc = jdbc;
    this.knowledgeBase = knowledgeBase;
  }

  long sourceAcl(String version) {
    return jdbc.queryForObject(
        "SELECT m.acl_version FROM document_version v JOIN document d USING(document_id) "
            + "JOIN document_metadata m USING(version_id) WHERE v.version_id=? AND v.lifecycle='ACTIVE' AND d.status='ACTIVE' FOR SHARE OF v,d,m",
        Long.class,
        version);
  }

  void lockQueue(String version, long acl) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        "summary-family:"
            + knowledgeBase
            + ":"
            + CompilationContracts.generationPolicy(GenerationTaskType.SUMMARY)
            + ":"
            + version
            + ":"
            + acl);
  }

  record Member(
      String id,
      String subject,
      String status,
      int attempts,
      long acl,
      Instant createdAt,
      Instant finishedAt) {}

  List<Member> members(String version, long acl) {
    return jdbc
        .query(
            "SELECT task_id,COALESCE(subject_id,''),status,attempt,source_acl_version,created_at,finished_at,idempotency_key "
                + "FROM compile_task WHERE knowledge_base_id=? AND version_id=? AND task_type='SUMMARY' ORDER BY created_at,task_id",
            (rs, n) ->
                new Candidate(
                    new Member(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getLong(5),
                        rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                    rs.getString(8)),
            knowledgeBase,
            version)
        .stream()
        .filter(
            row ->
                row.key()
                    .equals(
                        CompilationContracts.generationTaskKey(
                            knowledgeBase,
                            version,
                            GenerationTaskType.SUMMARY,
                            row.member().subject())))
        .map(Candidate::member)
        .filter(row -> row.acl() == acl || row.acl() == 0 && queuedAcl(row.subject(), acl) == acl)
        .toList();
  }

  String canonical(String version, long acl) {
    return members(version, acl).stream().map(Member::id).findFirst().orElse(null);
  }

  Instant nextAttempt(String version, long acl) {
    var ids = members(version, acl).stream().map(Member::id).toList();
    if (ids.isEmpty()) return null;
    var value =
        jdbc.queryForObject(
            "SELECT max(next_attempt_at) FROM compile_task WHERE task_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                + ")",
            java.sql.Timestamp.class,
            ids.toArray());
    return value == null ? null : value.toInstant();
  }

  void inheritPriority(String version, long acl, String canonical) {
    var ids = members(version, acl).stream().map(Member::id).toList();
    if (canonical == null || ids.isEmpty()) return;
    jdbc.update(
        "UPDATE compile_task SET priority=GREATEST(priority,(SELECT max(priority) FROM compile_task WHERE task_id IN ("
            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
            + "))) WHERE task_id=?",
        appendId(ids, canonical));
  }

  private static Object[] appendId(List<String> ids, String canonical) {
    var result = new java.util.ArrayList<Object>(ids);
    result.add(canonical);
    return result.toArray();
  }

  private static long queuedAcl(String subject, long fallback) {
    if (!subject.matches("repair/acl/[0-9]+")) return fallback;
    try {
      return Long.parseLong(subject.substring("repair/acl/".length()));
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private record Candidate(Member member, String key) {}
}
