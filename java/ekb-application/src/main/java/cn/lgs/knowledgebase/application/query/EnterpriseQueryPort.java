package cn.lgs.knowledgebase.application.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CQRS-style read port for the enterprise administration/UI projection. The values are read-model
 * DTOs, not domain aggregates, so adding UI facets does not pollute the write model.
 */
public interface EnterpriseQueryPort {
  Page<Map<String, Object>> documents(Query query);

  Optional<Map<String, Object>> document(String documentId);

  Page<Map<String, Object>> wiki(Query query);

  Optional<Map<String, Object>> wiki(String wikiId, Long version);

  Optional<Map<String, Object>> evidence(String evidenceId);

  Map<String, Object> graphNeighborhood(String entityId, int depth, int limit);

  Page<Map<String, Object>> tasks(TaskQuery query);

  Page<Map<String, Object>> audit(Query query);

  Map<String, Object> overview();

  Page<Map<String, Object>> attention(Query query);

  Map<String, Object> evaluationSummary();

  record Query(
      int page,
      int pageSize,
      String q,
      String status,
      String type,
      String department,
      String category,
      String company,
      String versionId) {
    public int normalizedPage() {
      return page <= 0 ? 1 : page;
    }

    public int normalizedPageSize() {
      return pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    }

    public int offset() {
      return (normalizedPage() - 1) * normalizedPageSize();
    }
  }

  record TaskQuery(
      int page,
      int pageSize,
      String q,
      String status,
      String taskType,
      String actorId,
      boolean administrator) {
    public int normalizedPage() {
      return page <= 0 ? 1 : page;
    }

    public int normalizedPageSize() {
      return pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    }

    public int offset() {
      return (normalizedPage() - 1) * normalizedPageSize();
    }
  }

  record Page<T>(List<T> items, long total, int page, int pageSize, Map<String, Object> facets) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
      facets = facets == null ? Map.of() : Map.copyOf(facets);
    }
  }
}
