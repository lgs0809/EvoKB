package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied;
import cn.lgs.knowledgebase.application.port.Ports.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable download facts; unique contribution keys survive package regeneration and replay. */
public final class JdbcDownloadUsageRepository implements DownloadUsageRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcDownloadUsageRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public void saveManifest(
      String userId, String packageId, PackageManifest manifest, long sizeBytes) {
    transactions.executeWithoutResult(
        status -> {
          lockPackage(userId, packageId);
          jdbc.update(
              "INSERT INTO package_usage_manifest(package_id,size_bytes) VALUES(?,?)",
              packageId,
              sizeBytes);
          for (var source : manifest.versions()) {
            Integer valid =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM document_version WHERE version_id=? AND document_id=?",
                    Integer.class,
                    source.versionId(),
                    source.documentId());
            if (valid == null || valid != 1)
              throw new IllegalArgumentException("invalid source version identity");
            jdbc.update(
                "INSERT INTO package_manifest_source(package_id,document_id,version_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                packageId,
                source.documentId(),
                source.versionId());
          }
          for (var answer : manifest.answers()) {
            var citations =
                jdbc.query(
                    "SELECT citation_evidence_ids::text FROM query_session WHERE session_id=? AND user_id=? AND deleted_at IS NULL AND status IN ('ANSWERED','INSUFFICIENT') FOR SHARE",
                    (rs, n) -> rs.getString(1),
                    answer.sessionId(),
                    userId);
            if (citations.isEmpty()) throw new Denied();
            for (var evidenceId : answer.evidenceIds()) {
              Integer cited =
                  jdbc.queryForObject(
                      "SELECT COUNT(*) FROM query_session WHERE session_id=? AND citation_evidence_ids @> jsonb_build_array(?::text)",
                      Integer.class,
                      answer.sessionId(),
                      evidenceId);
              if (cited == null || cited != 1)
                throw new IllegalArgumentException("source is not a citation of this answer");
              String version =
                  manifest.versions().stream()
                      .filter(v -> v.evidenceIds().contains(evidenceId))
                      .map(PackageVersion::versionId)
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException("answer source is absent from package"));
              jdbc.update(
                  "INSERT INTO package_answer_source(package_id,session_id,version_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                  packageId,
                  answer.sessionId(),
                  version);
            }
          }
          requireCurrentAccess(userId, packageId);
        });
  }

  @Override
  public void recordCompletedTransfer(String userId, String packageId, long transferredBytes) {
    transactions.executeWithoutResult(
        status -> {
          lockPackage(userId, packageId);
          var sizes =
              jdbc.query(
                  "SELECT size_bytes FROM package_usage_manifest WHERE package_id=?",
                  (rs, n) -> rs.getLong(1),
                  packageId);
          // Old packages have no verified immutable manifest. Do not fabricate historical usage.
          if (sizes.isEmpty()) return;
          if (transferredBytes <= 0 || transferredBytes != sizes.get(0))
            throw new IllegalArgumentException(
                "incomplete package transfer cannot contribute usage");
          requireCurrentAccess(userId, packageId);
          String receipt = UUID.randomUUID().toString();
          int inserted =
              jdbc.update(
                  "INSERT INTO package_download_receipt(receipt_id,package_id,user_id,transferred_bytes) VALUES(?,?,?,?) ON CONFLICT(package_id,user_id) DO NOTHING",
                  receipt,
                  packageId,
                  userId,
                  transferredBytes);
          if (inserted == 0) return;
          jdbc.update(
              """
          INSERT INTO source_usage_contribution(user_id,session_id,document_id,version_id,receipt_id)
              SELECT DISTINCT ON (a.session_id,m.document_id) ?,a.session_id,m.document_id,a.version_id,? FROM package_answer_source a
              JOIN package_manifest_source m ON m.package_id=a.package_id AND m.version_id=a.version_id
              JOIN document_version v ON v.version_id=a.version_id
              WHERE a.package_id=? ORDER BY a.session_id,m.document_id,v.version_no DESC,v.version_id
              ON CONFLICT(user_id,session_id,document_id) DO NOTHING
          """,
              userId,
              receipt,
              packageId);
          jdbc.update(
              """
          INSERT INTO asset_usage_contribution(user_id,session_id,asset_id,asset_version,receipt_id)
          SELECT DISTINCT ON (x.session_id,x.asset_id) ?,x.session_id,x.asset_id,x.asset_version,?
          FROM query_asset_expansion x
          JOIN package_answer_source a ON a.session_id=x.session_id AND a.version_id=x.source_version_id
          JOIN package_manifest_source m ON m.package_id=a.package_id AND m.version_id=x.source_version_id
          WHERE a.package_id=? ORDER BY x.session_id,x.asset_id,x.asset_version DESC
          ON CONFLICT(user_id,session_id,asset_id) DO NOTHING
          """,
              userId,
              receipt,
              packageId);
        });
  }

  private void lockPackage(String userId, String packageId) {
    var owned =
        jdbc.query(
            "SELECT package_id FROM source_package_access WHERE package_id=? AND user_id=? AND deleted_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE",
            (rs, n) -> rs.getString(1),
            packageId,
            userId);
    if (owned.isEmpty()) throw new Denied();
  }

  private void requireCurrentAccess(String userId, String packageId) {
    var levels =
        jdbc.query(
            "SELECT security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE' FOR SHARE",
            (rs, n) -> rs.getInt(1),
            userId);
    if (levels.isEmpty()) throw new Denied();
    Integer forbidden =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM (
          SELECT m.version_id FROM package_manifest_source m WHERE m.package_id=?
          UNION
          SELECT d.version_id FROM package_answer_source a
          JOIN query_source_dependency d ON d.session_id=a.session_id WHERE a.package_id=?
          UNION
          SELECT e.version_id FROM package_answer_source a
          JOIN query_evidence q ON q.session_id=a.session_id
          JOIN evidence e ON e.evidence_id=q.evidence_id WHERE a.package_id=?
        ) inputs LEFT JOIN kb_source_authority s ON s.version_id=inputs.version_id
        WHERE s.version_id IS NULL OR s.security_level>?
        """,
            Integer.class,
            packageId,
            packageId,
            packageId,
            levels.get(0));
    Integer invalidAnswer =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM package_answer_source a JOIN query_session q ON q.session_id=a.session_id
        WHERE a.package_id=? AND (q.user_id<>? OR q.deleted_at IS NOT NULL OR q.status NOT IN ('ANSWERED','INSUFFICIENT'))
        """,
            Integer.class,
            packageId,
            userId);
    if (forbidden == null || forbidden != 0 || invalidAnswer == null || invalidAnswer != 0)
      throw new Denied();
  }
}
