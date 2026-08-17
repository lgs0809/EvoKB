package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.DeliveredResult;
import cn.lgs.knowledgebase.application.port.Ports.DeliveredSearch;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeGenerationRecommendationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcKnowledgeGenerationRecommendationRepository
    implements KnowledgeGenerationRecommendationRepository {
  public static final int DEFAULT_EFFECTIVE_HIT_THRESHOLD = 3;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final int threshold;

  public JdbcKnowledgeGenerationRecommendationRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, int threshold) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
    this.threshold = threshold <= 0 ? DEFAULT_EFFECTIVE_HIT_THRESHOLD : threshold;
  }

  @Override
  public void recordDeliveredSearch(DeliveredSearch search) {
    if (search == null || search.searchId() == null || search.searchId().isBlank()) {
      throw new IllegalArgumentException("delivered search is required");
    }
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT IGNORE INTO search_record(search_id,query_text,filters_json,mode) VALUES(?,?,?,'UNIFIED')",
              search.searchId(),
              search.query(),
              json(search.filters()));
          Set<String> versions = new LinkedHashSet<>();
          for (DeliveredResult result : search.results()) {
            jdbc.update(
                "INSERT INTO search_result(search_id,rank_no,result_type,result_id,score,evidence_ids,effective) VALUES(?,?,?,?,?,?,TRUE) "
                    + "ON DUPLICATE KEY UPDATE result_type=VALUES(result_type),result_id=VALUES(result_id),score=VALUES(score),evidence_ids=VALUES(evidence_ids),effective=TRUE",
                search.searchId(),
                result.rank(),
                result.resultType().name(),
                result.resultId(),
                result.score(),
                json(result.evidenceIds()));
            versions.addAll(result.versionIds());
          }
          for (String versionId : versions) {
            if (versionId == null || versionId.isBlank()) continue;
            jdbc.update(
                "INSERT IGNORE INTO effective_version_hit(search_id,version_id) VALUES(?,?)",
                search.searchId(),
                versionId);
            Integer hits =
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM effective_version_hit WHERE version_id=?",
                    Integer.class,
                    versionId);
            int count = hits == null ? 0 : hits;
            if (count >= threshold) upsertRecommendation(versionId, "RUNTIME_EFFECTIVE_HIT", count);
          }
        });
  }

  @Override
  public void recommend(String versionId, String reason, int distinctEffectiveHits) {
    if (versionId == null || versionId.isBlank())
      throw new IllegalArgumentException("knowledge generation version ID is required");
    transactions.executeWithoutResult(
        status -> {
          String lifecycle =
              jdbc
                  .query(
                      "SELECT lifecycle FROM document_version WHERE version_id=? FOR UPDATE",
                      (rs, rowNum) -> rs.getString(1),
                      versionId.trim())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("version not found: " + versionId));
          if (!"ACTIVE".equals(lifecycle))
            throw new IllegalStateException(
                "knowledge generation version " + versionId + " is not ACTIVE");
          upsertRecommendation(
              versionId.trim(),
              reason == null || reason.isBlank() ? "USER_MARKED" : reason.trim(),
              Math.max(0, distinctEffectiveHits));
        });
  }

  private void upsertRecommendation(String versionId, String reason, int hits) {
    jdbc.update(
        "INSERT INTO distill_recommendation(recommendation_id,version_id,reason,distinct_effective_hits,status) VALUES(?,?,?,?, 'RECOMMENDED') "
            + "ON DUPLICATE KEY UPDATE distinct_effective_hits=GREATEST(distinct_effective_hits,VALUES(distinct_effective_hits)),status=IF(status='DISMISSED',status,'RECOMMENDED')",
        UUID.randomUUID().toString(),
        versionId,
        reason,
        hits);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "knowledge generation recommendation JSON serialization failed", e);
    }
  }
}
