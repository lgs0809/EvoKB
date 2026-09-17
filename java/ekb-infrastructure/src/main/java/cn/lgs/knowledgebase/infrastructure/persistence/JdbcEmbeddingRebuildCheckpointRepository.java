package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpoint;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpointRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persists evidence-bound raw embedding rebuild checkpoints.
 *
 * <p>The table is additive and is also created defensively for deployments that upgrade an existing
 * database without replaying the init scripts.
 */
public final class JdbcEmbeddingRebuildCheckpointRepository
    implements EmbeddingRebuildCheckpointRepository {
  private final JdbcTemplate jdbc;

  public JdbcEmbeddingRebuildCheckpointRepository(JdbcTemplate jdbc) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    this.jdbc = jdbc;
    ensureSchema();
  }

  @Override
  public List<EmbeddingRebuildCheckpoint> find(String versionId) {
    String id = requireText(versionId, "version id");
    return jdbc.query(
        "SELECT version_id,chunk_id,content_hash,evidence_fingerprint,evidence_ids,strategy_key FROM embedding_rebuild_checkpoint WHERE version_id=? ORDER BY chunk_id",
        (rs, rowNum) ->
            new EmbeddingRebuildCheckpoint(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)),
        id);
  }

  @Override
  public void markSucceeded(List<EmbeddingRebuildCheckpoint> checkpoints) {
    if (checkpoints == null || checkpoints.isEmpty()) return;
    for (EmbeddingRebuildCheckpoint checkpoint : checkpoints) {
      if (checkpoint == null) throw new IllegalArgumentException("checkpoint cannot be null");
      jdbc.update(
          "INSERT INTO embedding_rebuild_checkpoint(version_id,chunk_id,content_hash,evidence_fingerprint,evidence_ids,strategy_key,updated_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE content_hash=VALUES(content_hash),evidence_fingerprint=VALUES(evidence_fingerprint),evidence_ids=VALUES(evidence_ids),strategy_key=VALUES(strategy_key),updated_at=CURRENT_TIMESTAMP(6)",
          checkpoint.versionId(),
          checkpoint.chunkId(),
          checkpoint.contentHash(),
          checkpoint.evidenceFingerprint(),
          checkpoint.evidenceIds(),
          checkpoint.strategyKey());
    }
  }

  private void ensureSchema() {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS embedding_rebuild_checkpoint ("
            + "version_id CHAR(36) CHARACTER SET ascii NOT NULL,"
            + "chunk_id VARCHAR(255) CHARACTER SET ascii NOT NULL,"
            + "content_hash CHAR(64) CHARACTER SET ascii NOT NULL,"
            + "evidence_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,"
            + "evidence_ids TEXT NOT NULL,"
            + "strategy_key VARCHAR(128) CHARACTER SET ascii NOT NULL,"
            + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),"
            + "PRIMARY KEY(version_id,chunk_id),"
            + "KEY idx_embedding_checkpoint_version (version_id,updated_at)"
            + ") ENGINE=InnoDB");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
