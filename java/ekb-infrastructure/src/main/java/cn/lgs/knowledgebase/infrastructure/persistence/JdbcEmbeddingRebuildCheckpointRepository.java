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
          "INSERT INTO embedding_rebuild_checkpoint(version_id,chunk_id,content_hash,evidence_fingerprint,evidence_ids,strategy_key,updated_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6)) ON CONFLICT (version_id,chunk_id) DO UPDATE SET content_hash=EXCLUDED.content_hash,evidence_fingerprint=EXCLUDED.evidence_fingerprint,evidence_ids=EXCLUDED.evidence_ids,strategy_key=EXCLUDED.strategy_key,updated_at=CURRENT_TIMESTAMP(6)",
          checkpoint.versionId(),
          checkpoint.chunkId(),
          checkpoint.contentHash(),
          checkpoint.evidenceFingerprint(),
          checkpoint.evidenceIds(),
          checkpoint.strategyKey());
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
