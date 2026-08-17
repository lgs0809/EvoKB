package cn.lgs.knowledgebase.application;

import cn.lgs.knowledgebase.application.port.Ports.DeliveredResult;
import cn.lgs.knowledgebase.application.port.Ports.DeliveredSearch;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeGenerationRecommendationRepository;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService.SearchResponse;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.util.LinkedHashSet;
import java.util.List;

public final class KnowledgeGenerationRecommendationService {
  private final KnowledgeGenerationRecommendationRepository repository;

  public KnowledgeGenerationRecommendationService(
      KnowledgeGenerationRecommendationRepository repository) {
    if (repository == null)
      throw new IllegalArgumentException(
          "knowledge generation recommendation repository is required");
    this.repository = repository;
  }

  public void recordDeliveredSearch(String query, SearchFilters filters, SearchResponse response) {
    if (response == null || response.searchId() == null || response.searchId().isBlank()) {
      throw new IllegalArgumentException("delivered search response is required");
    }
    List<DeliveredResult> results =
        java.util.stream.IntStream.range(0, response.results().size())
            .mapToObj(
                index -> {
                  var result = response.results().get(index);
                  LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
                  LinkedHashSet<String> versionIds = new LinkedHashSet<>();
                  result
                      .evidence()
                      .forEach(
                          evidence -> {
                            evidenceIds.add(evidence.id());
                            if (evidence.versionId() != null && !evidence.versionId().isBlank())
                              versionIds.add(evidence.versionId());
                          });
                  return new DeliveredResult(
                      index + 1,
                      result.candidate().lane(),
                      result.candidate().id(),
                      result.candidate().score(),
                      List.copyOf(evidenceIds),
                      List.copyOf(versionIds));
                })
            .toList();
    repository.recordDeliveredSearch(
        new DeliveredSearch(response.searchId(), query, filters, results));
  }

  public void recommendManual(String versionId) {
    requireVersion(versionId);
    repository.recommend(versionId.trim(), "USER_MARKED", 0);
  }

  public void recommendExistingQuestion(String versionId) {
    requireVersion(versionId);
    repository.recommend(versionId.trim(), "EXISTING_QUESTION_EVIDENCE", 0);
  }

  private static void requireVersion(String versionId) {
    if (versionId == null || versionId.isBlank())
      throw new IllegalArgumentException("version ID is required");
  }
}
