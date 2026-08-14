package cn.lgs.knowledgebase.infrastructure.graph;

import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Neo4jGraphStoreAdapter implements GraphStore {
    private static final Set<String> ALLOWED_RELATIONS = Set.of(
            "ACTUAL_CONTROLLER_OF", "CONTROLS", "SHAREHOLDER_OF", "SUBSIDIARY_OF", "PARENT_OF",
            "DIRECTOR_OF", "LEGAL_REPRESENTATIVE_OF", "PARTY_TO_CONTRACT", "SIGNS_WITH", "GUARANTEES",
            "BORROWS_FROM", "INVESTS_IN", "INVOLVED_IN_CASE", "OWNS_IP", "HOLDS_LICENSE",
            "PARTICIPATES_IN_PROJECT", "APPROVES", "SUPPLIES_TO", "CUSTOMER_OF", "RELATED_TO");

    private final String endpoint;
    private final String authorization;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public Neo4jGraphStoreAdapter(String baseUrl, String user, String password, String database,
                                  ObjectMapper mapper, JdbcTemplate jdbc) {
        String root = trimSlash(baseUrl);
        String db = database == null || database.isBlank() ? "neo4j" : database.trim();
        this.endpoint = root + "/db/" + URLEncoder.encode(db, StandardCharsets.UTF_8).replace("+", "%20") + "/tx/commit";
        this.authorization = user == null || user.isBlank() ? "" : "Basic " + Base64.getEncoder().encodeToString((user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = mapper;
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        execute(List.of(
                statement("CREATE CONSTRAINT enterprise_entity_id IF NOT EXISTS FOR (n:Entity) REQUIRE n.id IS UNIQUE", Map.of()),
                statement("CREATE CONSTRAINT enterprise_support_id IF NOT EXISTS FOR (n:EvidenceSupport) REQUIRE n.id IS UNIQUE", Map.of()),
                statement("CREATE CONSTRAINT enterprise_evidence_id IF NOT EXISTS FOR (n:Evidence) REQUIRE n.id IS UNIQUE", Map.of()),
                statement("CREATE INDEX enterprise_entity_type IF NOT EXISTS FOR (n:Entity) ON (n.entity_type)", Map.of()),
                statement("CREATE FULLTEXT INDEX enterprise_entity_fulltext IF NOT EXISTS FOR (n:Entity) ON EACH [n.name, n.canonical_key]", Map.of())));
    }

    @Override
    public void upsertEntities(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return;
        List<Map<String, Object>> statements = new ArrayList<>();
        for (Entity entity : entities) {
            statements.add(statement(
                    "MERGE (n:Entity {id:$id}) SET n.name=$name,n.entity_type=$entity_type,n.canonical_key=$canonical_key,n.updated_at=datetime()",
                    Map.of("id", entity.canonicalId(), "name", entity.canonicalName(), "entity_type", entity.entityType(), "canonical_key", entity.canonicalKey())));
        }
        execute(statements);
    }

    @Override
    public void upsertRelations(List<Relation> relations) {
        if (relations == null || relations.isEmpty()) return;
        List<Map<String, Object>> statements = new ArrayList<>();
        for (Relation relation : relations) {
            String type = relation.predicate().trim().toUpperCase();
            if (!ALLOWED_RELATIONS.contains(type)) throw new IllegalArgumentException("unsupported relation type " + relation.predicate());
            if (!relation.publishable()) continue;
            List<String> evidenceIds = relation.evidenceIds().stream().distinct().sorted().toList();
            List<String> supportIds = evidenceIds.stream().map(e -> "support_" + shortHash(relation.id() + "\u0000" + e)).toList();
            String cypher = "MATCH (s:Entity {id:$source_id}),(t:Entity {id:$target_id}) " +
                    "MERGE (s)-[r:" + type + " {relation_id:$relation_id}]->(t) " +
                    "SET r.valid_from=$valid_from,r.valid_to=$valid_to,r.conflict_status=$conflict_status,r.lifecycle=$lifecycle," +
                    "r.updated_at=datetime(),r.evidence_ids=$evidence_ids,r.support_ids=$support_ids";
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source_id", relation.subjectEntityId());
            params.put("target_id", relation.objectEntityId());
            params.put("relation_id", relation.id());
            params.put("valid_from", relation.validFrom() == null ? null : relation.validFrom().toString());
            params.put("valid_to", relation.validTo() == null ? null : relation.validTo().toString());
            params.put("conflict_status", relation.conflictStatus().name());
            params.put("lifecycle", relation.lifecycle().name());
            params.put("evidence_ids", evidenceIds);
            params.put("support_ids", supportIds);
            statements.add(statement(cypher, params));

            List<Map<String, Object>> supports = new ArrayList<>();
            for (int i = 0; i < evidenceIds.size(); i++) {
                supports.add(Map.of("id", supportIds.get(i), "relation_id", relation.id(), "origin", "llm_triple",
                        "evidence_id", evidenceIds.get(i), "confidence", relation.confidence(), "active", true));
            }
            statements.add(statement(
                    "MATCH (source:Entity {id:$source_id}),(target:Entity {id:$target_id}) UNWIND $supports AS item " +
                            "MERGE (support:EvidenceSupport {id:item.id}) SET support.relation_id=item.relation_id,support.origin=item.origin," +
                            "support.evidence_id=item.evidence_id,support.confidence=item.confidence,support.active=item.active,support.updated_at=datetime() " +
                            "MERGE (evidence:Evidence {id:item.evidence_id}) MERGE (support)-[:USES_EVIDENCE]->(evidence) " +
                            "MERGE (support)-[:SUPPORTS_SOURCE]->(source) MERGE (support)-[:SUPPORTS_TARGET]->(target)",
                    Map.of("source_id", relation.subjectEntityId(), "target_id", relation.objectEntityId(), "supports", supports)));
        }
        execute(statements);
    }

    @Override
    public List<Candidate> search(String query, SearchFilters filters, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        int maxExpand = Math.min(10, limit);
        String cypher = "CALL db.index.fulltext.queryNodes('enterprise_entity_fulltext', $query, {limit: $max_expand}) YIELD node, score " +
                "MATCH p=(node)-[*1..2]-(other:Entity) WHERE length(p) <= $max_depth UNWIND relationships(p) AS r " +
                "WITH score, startNode(r) AS source, endNode(r) AS target, r " +
                "WHERE coalesce(r.lifecycle, 'ACTIVE') = 'ACTIVE' AND r.relation_id IS NOT NULL " +
                "OPTIONAL MATCH (support:EvidenceSupport {relation_id:r.relation_id})-[:USES_EVIDENCE]->(evidence:Evidence) " +
                "WHERE support.active = true WITH score, source, target, r, collect(DISTINCT evidence.id) AS evidence_ids " +
                "WHERE size(evidence_ids) > 0 RETURN DISTINCT r.relation_id, source.id, source.name, target.id, target.name, type(r), evidence_ids, score " +
                "ORDER BY score DESC LIMIT $max_expand";
        JsonNode response = execute(List.of(statement(cypher, Map.of("query", query.trim(), "max_depth", 2, "max_expand", maxExpand))));
        if (!response.path("errors").isEmpty()) throw neo4jError(response);
        JsonNode data = response.path("results").path(0).path("data");
        List<Candidate> results = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode row = item.path("row");
            if (!row.isArray() || row.size() != 8) throw new IllegalStateException("Neo4j graph search returned unexpected row: " + row);
            List<String> evidenceIds = strings(row.get(6));
            evidenceIds = filterEvidence(evidenceIds, filters);
            if (evidenceIds.isEmpty()) continue;
            String sourceId = row.get(1).asText("");
            String targetId = row.get(3).asText("");
            String knowledge = row.get(2).asText("") + " --" + row.get(5).asText("") + "--> " + row.get(4).asText("");
            results.add(new Candidate(row.get(0).asText(), "", "", LaneType.GRAPH, knowledge, row.get(7).asDouble(), evidenceIds,
                    Map.of("source_entity_id", sourceId, "target_entity_id", targetId, "relation_type", row.get(5).asText(""))));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    /**
     * Neo4j is a derived projection and existing Go behavior relies on the final active-Evidence resolver for version invalidation.
     * MySQL relation_evidence_support is the durable truth, so no business-time relation is modified here.
     */
    @Override
    public void deactivateVersion(String versionId) {
        // Intentionally no-op: stale graph candidates are filtered by active MySQL Evidence before exposure.
    }

    private List<String> filterEvidence(List<String> evidenceIds, SearchFilters filters) {
        if (evidenceIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(evidenceIds.size(), "?"));
        StringBuilder sql = new StringBuilder("SELECT e.evidence_id FROM evidence e JOIN document_metadata m ON m.version_id=e.version_id WHERE e.active=TRUE AND e.evidence_id IN (")
                .append(placeholders).append(')');
        List<Object> args = new ArrayList<>(evidenceIds);
        if (filters != null) {
            append(sql, args, "m.source_department=?", filters.sourceDepartment());
            append(sql, args, "m.material_category=?", filters.materialCategory());
            append(sql, args, "m.company=?", filters.company());
            append(sql, args, "COALESCE(m.manual_document_type_override,m.auto_document_type)=?", filters.documentType());
            if (filters.versionIds() != null && !filters.versionIds().isEmpty()) {
                sql.append(" AND e.version_id IN (").append(String.join(",", Collections.nCopies(filters.versionIds().size(), "?"))).append(')');
                args.addAll(filters.versionIds());
            }
        }
        Set<String> allowed = new LinkedHashSet<>(jdbc.query(sql.toString(), (rs, rowNum) -> rs.getString(1), args.toArray()));
        return evidenceIds.stream().filter(allowed::contains).toList();
    }

    private static void append(StringBuilder sql, List<Object> args, String clause, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(clause); args.add(value); }
    }

    private JsonNode execute(List<Map<String, Object>> statements) {
        try {
            String body = mapper.writeValueAsString(Map.of("statements", statements));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (!authorization.isEmpty()) request.header("Authorization", authorization);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Neo4j HTTP " + response.statusCode() + ": " + response.body());
            JsonNode decoded = mapper.readTree(response.body());
            if (!decoded.path("errors").isEmpty()) throw neo4jError(decoded);
            return decoded;
        } catch (IOException e) {
            throw new IllegalStateException("Neo4j request encode/decode failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Neo4j request interrupted", e);
        }
    }

    private static Map<String, Object> statement(String cypher, Map<String, Object> parameters) {
        return Map.of("statement", cypher, "parameters", parameters);
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(value -> out.add(value.asText()));
        return List.copyOf(out);
    }

    private static IllegalStateException neo4jError(JsonNode response) {
        JsonNode error = response.path("errors").path(0);
        return new IllegalStateException("Neo4j " + error.path("code").asText() + ": " + error.path("message").asText());
    }

    private static String shortHash(String value) {
        return cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(value).substring(0, 32);
    }

    private static String trimSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) throw new IllegalArgumentException("Neo4j base URL is required");
        return text;
    }
}
