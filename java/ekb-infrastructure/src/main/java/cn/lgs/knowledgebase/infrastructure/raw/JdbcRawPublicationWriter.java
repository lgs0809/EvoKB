package cn.lgs.knowledgebase.infrastructure.raw;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared raw/rebuild membership writer; the caller must own a short exact task fence transaction.
 */
public final class JdbcRawPublicationWriter {
  private final JdbcTemplate jdbc;

  public JdbcRawPublicationWriter(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public long sourceAcl(String document, String version) {
    var rows =
        jdbc.queryForList(
            "SELECT m.acl_version FROM document_version v JOIN document d USING(document_id) JOIN document_metadata m USING(version_id) WHERE v.document_id=? AND v.version_id=? AND d.status='ACTIVE' AND v.lifecycle IN ('ACTIVE','PENDING')",
            document,
            version);
    if (rows.size() != 1) throw new IllegalStateException("RAW_SOURCE_REVOKED: source unavailable");
    return ((Number) rows.get(0).get("acl_version")).longValue();
  }

  public void publish(
      String scope,
      String fileId,
      String compileTaskId,
      long epoch,
      String document,
      String version,
      long expectedAcl,
      List<String> ids,
      Runnable mutation) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Raw publication requires the exact task-fenced transaction");
    if (scope == null
        || scope.isBlank()
        || epoch < 1
        || ((fileId == null) == (compileTaskId == null))
        || ids == null
        || ids.isEmpty()
        || ids.stream().distinct().count() != ids.size())
      throw new IllegalArgumentException(
          "raw producer scope/identity and unique physical members required");
    String producer = fileId == null ? compileTaskId : fileId;
    if (!scope.equals(producer + ":e" + epoch))
      throw new IllegalArgumentException("raw producer scope differs from exact task epoch");
    String table = fileId == null ? "compile_task" : "ingestion_file",
        column = fileId == null ? "task_id" : "file_id";
    var task =
        jdbc.queryForList(
            "SELECT "
                + column
                + " FROM "
                + table
                + " WHERE "
                + column
                + "=? AND status='RUNNING' AND execution_epoch=? AND stage_deadline>clock_timestamp() FOR UPDATE",
            producer,
            epoch);
    if (task.size() != 1)
      throw new IllegalStateException("RAW_STALE_CLAIM: publication producer no longer current");
    var rows =
        jdbc.queryForList(
            "SELECT v.version_id,m.acl_version FROM document_version v JOIN document d USING(document_id) JOIN document_metadata m USING(version_id) WHERE v.version_id=? AND v.document_id=? AND d.status='ACTIVE' AND v.lifecycle IN ('ACTIVE','PENDING') AND m.acl_version=? AND NOT EXISTS(SELECT 1 FROM document_version newer WHERE newer.document_id=v.document_id AND newer.version_no>v.version_no AND newer.lifecycle IN ('PENDING','ACTIVE')) FOR UPDATE OF v,d,m",
            version,
            document,
            expectedAcl);
    if (rows.size() != 1)
      throw new IllegalStateException(
          "RAW_SOURCE_REVOKED: source is outdated, deleted, or ACL changed");
    mutation.run();
    jdbc.update(
        "INSERT INTO raw_publication(version_id,document_id,file_id,compile_task_id,producer_scope,execution_epoch,acl_version) VALUES(?,?,?,?,?,?,?) ON CONFLICT(version_id) DO UPDATE SET file_id=EXCLUDED.file_id,compile_task_id=EXCLUDED.compile_task_id,producer_scope=EXCLUDED.producer_scope,execution_epoch=EXCLUDED.execution_epoch,acl_version=EXCLUDED.acl_version,published_at=clock_timestamp()",
        version,
        document,
        fileId,
        compileTaskId,
        scope,
        epoch,
        expectedAcl);
    jdbc.update("DELETE FROM raw_publication_member WHERE version_id=?", version);
    for (String id : ids)
      jdbc.update(
          "INSERT INTO raw_publication_member(version_id,physical_id) VALUES(?,?)", version, id);
    var stillCurrent =
        jdbc.queryForList(
            "SELECT "
                + column
                + " FROM "
                + table
                + " WHERE "
                + column
                + "=? AND status='RUNNING' AND execution_epoch=? AND stage_deadline>clock_timestamp()",
            producer,
            epoch);
    if (stillCurrent.size() != 1)
      throw new IllegalStateException(
          "RAW_STALE_CLAIM: publication crossed its fixed stage deadline");
  }
}
