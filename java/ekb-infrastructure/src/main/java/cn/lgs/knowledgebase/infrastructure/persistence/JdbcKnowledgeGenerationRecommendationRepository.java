package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.DeliveredResult;
import cn.lgs.knowledgebase.application.port.Ports.DeliveredSearch;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeGenerationRecommendationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcKnowledgeGenerationRecommendationRepository
    implements KnowledgeGenerationRecommendationRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

  public JdbcKnowledgeGenerationRecommendationRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
  }

  @Override
  public void recordDeliveredSearch(DeliveredSearch search) {
    if (search == null || search.searchId() == null || search.searchId().isBlank()) {
      throw new IllegalArgumentException("delivered search is required");
    }
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO search_record(search_id,query_text,filters_json,mode) VALUES(?,?,?,'UNIFIED') ON CONFLICT DO NOTHING",
              search.searchId(),
              search.query(),
              json(search.filters()));
          for (DeliveredResult result : search.results()) {
            jdbc.update(
                "INSERT INTO search_result(search_id,rank_no,result_type,result_id,score,evidence_ids,effective) VALUES(?,?,?,?,?,?,FALSE) ON CONFLICT (search_id,rank_no) DO NOTHING",
                search.searchId(),
                result.rank(),
                result.resultType().name(),
                result.resultId(),
                result.score(),
                json(result.evidenceIds()));
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
        "INSERT INTO distill_recommendation(recommendation_id,version_id,reason,distinct_effective_hits,status) VALUES(?,?,?,?, 'RECOMMENDED') ON CONFLICT (version_id,reason) DO UPDATE SET distinct_effective_hits=GREATEST(distill_recommendation.distinct_effective_hits,EXCLUDED.distinct_effective_hits),status=CASE WHEN distill_recommendation.status='DISMISSED' THEN distill_recommendation.status ELSE 'RECOMMENDED' END",
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
