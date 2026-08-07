package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenSearchRawIndexAdapter implements RawSearchIndex {
    private final OpenSearchHttpClient client;
    private final String index;

    public OpenSearchRawIndexAdapter(String baseUrl, String index, ObjectMapper mapper) {
        this.client = new OpenSearchHttpClient(baseUrl, mapper);
        this.index = index == null || index.isBlank() ? "ekb_raw_v1" : index.trim();
    }

    @Override
    public void ensureIndex(int embeddingDimensions) {
        if (embeddingDimensions <= 0) throw new IllegalArgumentException("embedding dimensions must be > 0");
        if (client.indexExists(index)) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("knowledge_type", keyword());
        properties.put("chunk_id", keyword());
        properties.put("document_id", keyword());
        properties.put("version_id", keyword());
        properties.put("raw_content", text());
        properties.put("normalized_content", text());
        properties.put("embedding_content", Map.of("type", "text", "index", false));
        properties.put("heading_path", Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword", "ignore_above", 1024))));
        properties.put("source_block_ids", keyword());
        properties.put("page_start", Map.of("type", "integer"));
        properties.put("page_end", Map.of("type", "integer"));
        properties.put("source_department", keyword());
        properties.put("material_category", keyword());
        properties.put("manual_tags", keyword());
        properties.put("company", keyword());
        properties.put("document_date", Map.of("type", "date", "format", "strict_date_optional_time||yyyy-MM-dd"));
        properties.put("document_type", keyword());
        properties.put("is_active", Map.of("type", "boolean"));
        properties.put("content_hash", keyword());
        properties.put("evidence_ids", keyword());
        properties.put("embedding", Map.of(
                "type", "knn_vector",
                "dimension", embeddingDimensions,
                "method", Map.of("name", "hnsw", "space_type", "cosinesimil", "engine", "lucene",
                        "parameters", Map.of("ef_construction", 128, "m", 16))));
        client.json("PUT", "/" + OpenSearchHttpClient.segment(index), Map.of(
                "settings", Map.of("index", Map.of("knn", true, "number_of_shards", 1, "number_of_replicas", 0)),
                "mappings", Map.of("dynamic", "strict", "properties", properties)));
    }

    @Override
    public void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        StringBuilder bulk = new StringBuilder();
        for (RawIndexDocument chunk : chunks) {
            if (chunk.embedding() == null || chunk.embedding().isEmpty()) {
                throw new IllegalArgumentException("raw chunk " + chunk.id() + " has empty embedding");
            }
            bulk.append(client.encode(Map.of("index", Map.of("_index", index, "_id", chunk.id())))).append('\n');
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("knowledge_type", "RAW");
            doc.put("chunk_id", chunk.id());
            doc.put("document_id", documentId);
            doc.put("version_id", versionId);
            doc.put("raw_content", chunk.content());
            doc.put("normalized_content", chunk.normalized());
            doc.put("embedding_content", chunk.content());
            doc.put("heading_path", safe(chunk.headingPath()));
            doc.put("source_block_ids", safe(chunk.sourceBlockIds()));
            if (chunk.pageStart() != null) doc.put("page_start", chunk.pageStart());
            if (chunk.pageEnd() != null) doc.put("page_end", chunk.pageEnd());
            applyMetadata(doc, chunk.metadata());
            doc.put("is_active", false);
            doc.put("content_hash", chunk.contentHash());
            doc.put("evidence_ids", safe(chunk.evidenceIds()));
            doc.put("embedding", chunk.embedding());
            bulk.append(client.encode(doc)).append('\n');
        }
        JsonNode response = client.ndjson("/_bulk?refresh=wait_for", bulk.toString());
        if (response.path("errors").asBoolean(false)) throw new IllegalStateException("OpenSearch raw bulk reported item errors: " + response);
    }

    @Override
    public void activateVersion(String documentId, String versionId) {
        client.json("POST", path("/_update_by_query?refresh=true&conflicts=proceed"), Map.of(
                "script", Map.of("lang", "painless",
                        "source", "ctx._source.is_active = (ctx._source.version_id == params.version_id)",
                        "params", Map.of("version_id", versionId)),
                "query", Map.of("term", Map.of("document_id", documentId))));
    }

    @Override
    public void deactivateVersion(String versionId) {
        client.json("POST", path("/_update_by_query?refresh=true&conflicts=proceed"), Map.of(
                "script", Map.of("lang", "painless", "source", "ctx._source.is_active = false"),
                "query", Map.of("term", Map.of("version_id", versionId))));
    }

    @Override
    public void deleteVersion(String versionId) {
        client.json("POST", path("/_delete_by_query?refresh=true&conflicts=proceed"),
                Map.of("query", Map.of("term", Map.of("version_id", versionId))));
    }

    @Override
    public List<Candidate> searchBm25(String query, SearchFilters filters, int limit) {
        int effectiveLimit = limit <= 0 ? 20 : limit;
        Map<String, Object> body = Map.of(
                "size", effectiveLimit,
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("multi_match", Map.of(
                                "query", query,
                                "fields", List.of("raw_content^1.0", "normalized_content^1.2", "heading_path^1.5"),
                                "type", "best_fields"))),
                        "filter", filterClauses(filters))));
        return decode(client.json("POST", path("/_search"), body));
    }

    @Override
    public List<Candidate> searchVector(List<Float> vector, SearchFilters filters, int limit) {
        int effectiveLimit = limit <= 0 ? 20 : limit;
        Map<String, Object> body = Map.of(
                "size", effectiveLimit,
                "query", Map.of("knn", Map.of("embedding", Map.of(
                        "vector", vector,
                        "k", effectiveLimit,
                        "filter", Map.of("bool", Map.of("filter", filterClauses(filters)))))));
        return decode(client.json("POST", path("/_search"), body));
    }

    private List<Object> filterClauses(SearchFilters filters) {
        List<Object> clauses = new ArrayList<>();
        clauses.add(Map.of("term", Map.of("knowledge_type", "RAW")));
        clauses.add(Map.of("term", Map.of("is_active", true)));
        if (filters == null) return clauses;
        addTerm(clauses, "source_department", filters.sourceDepartment());
        addTerm(clauses, "material_category", filters.materialCategory());
        addTerm(clauses, "company", filters.company());
        addTerm(clauses, "document_type", filters.documentType());
        if (filters.versionIds() != null && !filters.versionIds().isEmpty()) {
            clauses.add(Map.of("terms", Map.of("version_id", filters.versionIds())));
        }
        return clauses;
    }

    private List<Candidate> decode(JsonNode response) {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("heading_path", strings(source.path("heading_path")));
            if (!source.path("page_start").isMissingNode() && !source.path("page_start").isNull()) attributes.put("page_start", source.path("page_start").asInt());
            if (!source.path("page_end").isMissingNode() && !source.path("page_end").isNull()) attributes.put("page_end", source.path("page_end").asInt());
            out.add(new Candidate(source.path("chunk_id").asText(), source.path("document_id").asText(), source.path("version_id").asText(),
                    LaneType.RAW, source.path("raw_content").asText(), hit.path("_score").asDouble(), strings(source.path("evidence_ids")), attributes));
        }
        return List.copyOf(out);
    }

    private static void applyMetadata(Map<String, Object> doc, Metadata metadata) {
        if (metadata == null) return;
        doc.put("source_department", nullableText(metadata.sourceDepartment()));
        doc.put("material_category", nullableText(metadata.materialCategory()));
        doc.put("manual_tags", safe(metadata.manualTags()));
        doc.put("company", nullableText(metadata.company()));
        doc.put("document_type", nullableText(metadata.documentType()));
        if (metadata.documentDate() != null) doc.put("document_date", metadata.documentDate().toString());
    }

    private static void addTerm(List<Object> clauses, String name, String value) {
        if (value != null && !value.isBlank()) clauses.add(Map.of("term", Map.of(name, value)));
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
    private static String nullableText(String value) { return value == null ? "" : value; }
    private static Map<String, Object> keyword() { return Map.of("type", "keyword"); }
    private static Map<String, Object> text() { return Map.of("type", "text"); }
    private String path(String suffix) { return "/" + OpenSearchHttpClient.segment(index) + suffix; }
}
