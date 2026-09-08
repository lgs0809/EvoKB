package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class OpenSearchDerivedIndexAdapter implements DerivedSearchIndex {
  private final OpenSearchHttpClient client;
  private final String index;

  public OpenSearchDerivedIndexAdapter(String baseUrl, String index, ObjectMapper mapper) {
    this.client = new OpenSearchHttpClient(baseUrl, mapper);
    this.index = index == null || index.isBlank() ? "ekb_derived_v1" : index.trim();
  }

  @Override
  public synchronized void ensureIndex(int embeddingDimensions) {
    if (embeddingDimensions <= 0)
      throw new IllegalArgumentException("embedding dimensions must be > 0");
    if (client.indexExists(index)) {
      validateEmbeddingMapping(embeddingDimensions);
      return;
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("knowledge_type", keyword());
    properties.put("knowledge_id", keyword());
    properties.put("group_id", keyword());
    properties.put("document_id", keyword());
    properties.put("version_id", keyword());
    properties.put("content", Map.of("type", "text"));
    properties.put("content_hash", keyword());
    properties.put("evidence_ids", keyword());
    properties.put("source_department", keyword());
    properties.put("material_category", keyword());
    properties.put("manual_tags", keyword());
    properties.put("company", keyword());
    properties.put("document_type", keyword());
    properties.put("is_active", Map.of("type", "boolean"));
    properties.put(
        "embedding",
        Map.of(
            "type",
            "knn_vector",
            "dimension",
            embeddingDimensions,
            "method",
            Map.of(
                "name",
                "hnsw",
                "space_type",
                "cosinesimil",
                "engine",
                "lucene",
                "parameters",
                Map.of("ef_construction", 128, "m", 16))));
    client.json(
        "PUT",
        "/" + OpenSearchHttpClient.segment(index),
        Map.of(
            "settings",
                Map.of(
                    "index", Map.of("knn", true, "number_of_shards", 1, "number_of_replicas", 0)),
            "mappings", Map.of("dynamic", "strict", "properties", properties)));
  }

  private void validateEmbeddingMapping(int dimensions) {
    JsonNode mappings = client.json("GET", path("/_mapping"), null);
    if (mappings.isEmpty()) throw new IllegalStateException("derived index mapping is missing");
    for (JsonNode mapping : mappings) {
      JsonNode embedding = mapping.path("mappings").path("properties").path("embedding");
      if (!"knn_vector".equals(embedding.path("type").asText())
          || embedding.path("dimension").asInt() != dimensions) {
        throw new IllegalStateException(
            "derived index embedding mapping is incompatible; preserve the existing index and rebuild into a new index with "
                + dimensions
                + " dimensions");
      }
    }
  }

  @Override
  public void indexSummary(SummaryArtifact summary, DerivedContext context, List<Float> embedding) {
    if (summary == null) throw new IllegalArgumentException("summary is required");
    DerivedContext source =
        context == null
            ? new DerivedContext(
                List.of(summary.documentId()),
                List.of(summary.versionId()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of())
            : context;
    indexDocument(
        summary.id(),
        summary.versionId(),
        "SUMMARY",
        summary.content(),
        evidenceIds(summary.claims()),
        source,
        embedding,
        summary.status().name().equals("ACTIVE") || summary.status().name().equals("GROUNDED"));
  }

  @Override
  public void indexWiki(WikiPage page, DerivedContext context, List<Float> embedding) {
    if (page == null) throw new IllegalArgumentException("wiki page is required");
    if (context == null) throw new IllegalArgumentException("wiki derived context is required");
    indexDocument(
        page.id(),
        page.id(),
        "WIKI",
        page.content(),
        evidenceIds(page.claims()),
        context,
        embedding,
        page.status().name().equals("ACTIVE") || page.status().name().equals("GROUNDED"));
  }

  private void indexDocument(
      String id,
      String groupId,
      String type,
      String content,
      List<String> evidenceIds,
      DerivedContext context,
      List<Float> embedding,
      boolean active) {
    if (embedding == null || embedding.isEmpty())
      throw new IllegalArgumentException("derived embedding is required");
    // OpenSearch otherwise auto-creates a float-array field on the first write. Configure
    // the vector contract before every publication, including fresh installs and rebuilds.
    ensureIndex(embedding.size());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("knowledge_type", type);
    body.put("knowledge_id", id);
    body.put("group_id", groupId);
    body.put("document_id", context.documentIds());
    body.put("version_id", context.versionIds());
    body.put("content", content);
    body.put("content_hash", cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(content));
    body.put("evidence_ids", evidenceIds);
    body.put("source_department", context.sourceDepartments());
    body.put("material_category", context.materialCategories());
    body.put("manual_tags", context.manualTags());
    body.put("company", context.companies());
    body.put("document_type", context.documentTypes());
    body.put("is_active", active);
    body.put("embedding", embedding);
    client.json(
        "PUT", path("/_doc/" + OpenSearchHttpClient.segment(id) + "?refresh=wait_for"), body);
  }

  @Override
  public List<Candidate> searchSummaryBm25(String query, SearchFilters filters, int limit) {
    return searchBm25("SUMMARY", LaneType.SUMMARY, query, filters, limit);
  }

  @Override
  public List<Candidate> searchSummaryVector(List<Float> vector, SearchFilters filters, int limit) {
    return searchVector("SUMMARY", LaneType.SUMMARY, vector, filters, limit);
  }

  @Override
  public List<Candidate> searchWikiBm25(String query, SearchFilters filters, int limit) {
    return searchBm25("WIKI", LaneType.WIKI, query, filters, limit);
  }

  @Override
  public List<Candidate> searchWikiVector(List<Float> vector, SearchFilters filters, int limit) {
    return searchVector("WIKI", LaneType.WIKI, vector, filters, limit);
  }

  private List<Candidate> searchBm25(
      String type, LaneType lane, String query, SearchFilters filters, int limit) {
    if (!client.indexExists(index)) return List.of();
    int effectiveLimit = limit <= 0 ? 20 : limit;
    JsonNode response =
        client.json(
            "POST",
            path("/_search"),
            Map.of(
                "size",
                effectiveLimit,
                "query",
                Map.of(
                    "bool",
                    Map.of(
                        "must", List.of(Map.of("match", Map.of("content", Map.of("query", query)))),
                        "filter", filters(type, filters)))));
    return decode(response, lane);
  }

  private List<Candidate> searchVector(
      String type, LaneType lane, List<Float> vector, SearchFilters filters, int limit) {
    if (!client.indexExists(index)) return List.of();
    int effectiveLimit = limit <= 0 ? 20 : limit;
    JsonNode response =
        client.json(
            "POST",
            path("/_search"),
            Map.of(
                "size",
                effectiveLimit,
                "query",
                Map.of(
                    "knn",
                    Map.of(
                        "embedding",
                        Map.of(
                            "vector",
                            vector,
                            "k",
                            effectiveLimit,
                            "filter",
                            Map.of("bool", Map.of("filter", filters(type, filters))))))));
    return decode(response, lane);
  }

  @Override
  public void updateMetadata(String versionId, Metadata metadata) {
    if (versionId == null || versionId.isBlank() || metadata == null || !client.indexExists(index))
      return;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("source_department", singleton(metadata.sourceDepartment()));
    params.put("material_category", singleton(metadata.materialCategory()));
    params.put("manual_tags", metadata.manualTags() == null ? List.of() : metadata.manualTags());
    params.put("company", singleton(metadata.company()));
    params.put("document_type", singleton(metadata.documentType()));
    Map<String, Object> script =
        Map.of(
            "lang",
            "painless",
            "source",
            "ctx._source.source_department=params.source_department; ctx._source.material_category=params.material_category; ctx._source.manual_tags=params.manual_tags; ctx._source.company=params.company; ctx._source.document_type=params.document_type;",
            "params",
            params);
    List<Object> metadataFilters =
        List.of(
            Map.of("term", Map.of("knowledge_type", "SUMMARY")),
            Map.of("term", Map.of("version_id", versionId)));
    Map<String, Object> query = Map.of("bool", Map.of("filter", metadataFilters));
    client.json(
        "POST",
        path("/_update_by_query?refresh=true&conflicts=proceed"),
        Map.of("script", script, "query", query));
  }

  @Override
  public void deactivateVersion(String versionId) {
    if (versionId == null || versionId.isBlank()) return;
    client.json(
        "POST",
        path("/_update_by_query?refresh=true&conflicts=proceed"),
        Map.of(
            "script", Map.of("lang", "painless", "source", "ctx._source.is_active = false"),
            "query",
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(
                            Map.of("term", Map.of("version_id", versionId)),
                            Map.of("term", Map.of("is_active", true)))))));
  }

  private List<Object> filters(String type, SearchFilters filters) {
    List<Object> clauses = new ArrayList<>();
    clauses.add(Map.of("term", Map.of("knowledge_type", type)));
    clauses.add(Map.of("term", Map.of("is_active", true)));
    if (filters == null) return clauses;
    addTerm(clauses, "source_department", filters.sourceDepartment());
    addTerm(clauses, "material_category", filters.materialCategory());
    addTerm(clauses, "document_type", filters.documentType());
    if (filters.versionIds() != null && !filters.versionIds().isEmpty())
      clauses.add(Map.of("terms", Map.of("version_id", filters.versionIds())));
    return clauses;
  }

  private List<Candidate> decode(JsonNode response, LaneType lane) {
    List<Candidate> results = new ArrayList<>();
    for (JsonNode hit : response.path("hits").path("hits")) {
      JsonNode source = hit.path("_source");
      List<String> documentIds = strings(source.path("document_id"));
      List<String> versionIds = strings(source.path("version_id"));
      String docId =
          documentIds.isEmpty() ? source.path("document_id").asText("") : documentIds.get(0);
      String versionId =
          versionIds.isEmpty() ? source.path("version_id").asText("") : versionIds.get(0);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("group_id", source.path("group_id").asText(""));
      attributes.put("document_ids", documentIds);
      attributes.put("version_ids", versionIds);
      results.add(
          new Candidate(
              source.path("knowledge_id").asText(),
              docId,
              versionId,
              lane,
              source.path("content").asText(),
              hit.path("_score").asDouble(),
              strings(source.path("evidence_ids")),
              attributes));
    }
    return List.copyOf(results);
  }

  private static List<String> evidenceIds(List<Claim> claims) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (claims != null) claims.forEach(claim -> ids.addAll(claim.evidenceIds()));
    return List.copyOf(ids);
  }

  private static List<String> strings(JsonNode node) {
    if (node.isArray()) {
      List<String> values = new ArrayList<>();
      node.forEach(value -> values.add(value.asText()));
      return List.copyOf(values);
    }
    if (node.isTextual() && !node.asText().isBlank()) return List.of(node.asText());
    return List.of();
  }

  private static void addTerm(List<Object> clauses, String field, String value) {
    if (value != null && !value.isBlank()) clauses.add(Map.of("term", Map.of(field, value)));
  }

  private static List<String> singleton(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(value.trim());
  }

  private static Map<String, Object> keyword() {
    return Map.of("type", "keyword");
  }

  private String path(String suffix) {
    return "/" + OpenSearchHttpClient.segment(index) + suffix;
  }
}
