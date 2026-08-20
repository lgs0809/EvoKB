package cn.lgs.knowledgebase.infrastructure.graph;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.GraphExpansion;
import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Source-backed immutable graph revisions, read with two bounded PostgreSQL frontier queries. */
public final class PostgresGraphStoreAdapter implements GraphStore {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;
  private final String knowledgeBaseId;
  private static final Set<String> FORBIDDEN_TYPES = Set.of("CHUNK", "SUMMARY", "CONCEPT", "WIKI");

  public PostgresGraphStoreAdapter(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, String knowledgeBaseId) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.tx = Objects.requireNonNull(tx);
    this.mapper = Objects.requireNonNull(mapper);
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted knowledge base identity is required");
    this.knowledgeBaseId = knowledgeBaseId;
  }

  @Override
  public void publish(List<Entity> entities, List<Relation> relations) {
    tx.executeWithoutResult(
        ignored -> {
          upsertEntities(entities);
          upsertRelations(relations);
        });
  }

  @Override
  public void upsertEntities(List<Entity> entities) {
    if (entities == null || entities.isEmpty()) return;
    tx.executeWithoutResult(
        ignored -> {
          for (var entity : entities) {
            String type = entity.entityType().toUpperCase(java.util.Locale.ROOT);
            if (FORBIDDEN_TYPES.contains(type))
              throw new IllegalArgumentException("derived assets are not graph entities");
            Object scope = entity.attributes().get("scope_key");
            if (scope == null || scope.toString().isBlank())
              throw new IllegalArgumentException(
                  "business entity scope is required before graph publication");
            jdbc.update(
                "INSERT INTO kb_graph_entity(kb_id,entity_id,entity_type,canonical_name,canonical_key,scope_key,attributes,object_id) VALUES(?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(kb_id,entity_id) DO NOTHING",
                knowledgeBaseId,
                entity.canonicalId(),
                type,
                entity.canonicalName(),
                entity.canonicalKey(),
                scope.toString(),
                json(entity.attributes()),
                entity.attributes().get("object_id"));
            var existing =
                jdbc.queryForMap(
                    "SELECT entity_type,canonical_key,scope_key,object_id FROM kb_graph_entity WHERE kb_id=? AND entity_id=?",
                    knowledgeBaseId,
                    entity.canonicalId());
            if (!type.equals(existing.get("entity_type"))
                || !entity.canonicalKey().equals(existing.get("canonical_key"))
                || !scope.toString().equals(existing.get("scope_key"))
                || !Objects.equals(entity.attributes().get("object_id"), existing.get("object_id")))
              throw new IllegalStateException(
                  "graph identity cannot be reassigned to another scope");
          }
        });
  }

  @Override
  public void upsertRelations(List<Relation> relations) {
    if (relations == null || relations.isEmpty()) return;
    tx.executeWithoutResult(
        ignored -> {
          for (var relation : relations) {
            if (!relation.publishable()) continue;
            if ("COLLECTS_SOURCE".equalsIgnoreCase(relation.predicate()))
              throw new IllegalArgumentException(
                  "asset source catalogs belong in PostgreSQL asset tables");
            var ids = relation.evidenceIds().stream().distinct().sorted().toList();
            var sources =
                named.queryForList(
                    "SELECT e.evidence_id,m.acl_version FROM evidence e JOIN document_version v ON v.version_id=e.version_id JOIN document d ON d.document_id=e.document_id JOIN document_metadata dm ON dm.version_id=v.version_id JOIN kb_source_authority m ON m.version_id=e.version_id WHERE e.evidence_id IN (:ids) AND e.active AND v.lifecycle='ACTIVE' AND d.status='ACTIVE' ORDER BY e.evidence_id FOR SHARE OF e,v,d,dm",
                    Map.of("ids", ids));
            if (sources.size() != ids.size())
              throw new IllegalStateException("graph relation has invalid evidence dependencies");
            var endpointScopes =
                named.queryForList(
                    "SELECT entity_id,scope_key FROM kb_graph_entity WHERE kb_id=:kb AND entity_id IN (:endpoints) ORDER BY entity_id",
                    Map.of(
                        "kb",
                        knowledgeBaseId,
                        "endpoints",
                        List.of(relation.subjectEntityId(), relation.objectEntityId())));
            if (endpointScopes.size() != 2)
              throw new IllegalStateException(
                  "graph endpoints must belong to the trusted knowledge base");
            String scopeKey =
                relation
                    .qualifiers()
                    .getOrDefault("scope_key", sha256(json(endpointScopes)))
                    .toString();
            if (scopeKey.isBlank() || scopeKey.length() > 255)
              throw new IllegalArgumentException("validated graph business scope is required");
            var identity = new LinkedHashMap<String, Object>();
            identity.put("relation", relation);
            identity.put("evidence_acl", sources);
            String revision = sha256(json(identity));
            // Serialize only the short publication transaction for this relation, not any model
            // call.
            jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class,
                knowledgeBaseId + ":" + relation.id());
            var prior =
                jdbc.queryForList(
                    "SELECT source_entity_id,target_entity_id,relation_type,scope_key FROM kb_graph_relation WHERE kb_id=? AND relation_id=? LIMIT 1",
                    knowledgeBaseId,
                    relation.id());
            if (!prior.isEmpty()) {
              var original = prior.get(0);
              if (!relation.subjectEntityId().equals(original.get("source_entity_id"))
                  || !relation.objectEntityId().equals(original.get("target_entity_id"))
                  || !relation.predicate().equals(original.get("relation_type"))
                  || !Objects.equals(scopeKey, original.get("scope_key")))
                throw new IllegalStateException(
                    "graph logical identity cannot change endpoints, type or business scope");
            }
            jdbc.update(
                "UPDATE kb_graph_relation SET is_active=FALSE WHERE kb_id=? AND relation_id=? AND revision_id<>? AND is_active",
                knowledgeBaseId,
                relation.id(),
                revision);
            jdbc.update(
                "INSERT INTO kb_graph_relation(kb_id,relation_id,revision_id,source_entity_id,target_entity_id,relation_type,valid_from,valid_to,conflict_status,is_active,scope_key,scope_json,confidence) VALUES(?,?,?,?,?,?,?,?,?,TRUE,?,?::jsonb,?) ON CONFLICT(kb_id,relation_id,revision_id) DO UPDATE SET is_active=TRUE",
                knowledgeBaseId,
                relation.id(),
                revision,
                relation.subjectEntityId(),
                relation.objectEntityId(),
                relation.predicate(),
                timestamp(relation.validFrom()),
                timestamp(relation.validTo()),
                relation.conflictStatus().name(),
                scopeKey,
                json(relation.qualifiers().getOrDefault("scope_json", Map.of())),
                relation.confidence());
            for (var source : sources)
              jdbc.update(
                  "INSERT INTO kb_graph_evidence(kb_id,relation_id,revision_id,evidence_id,acl_version) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                  knowledgeBaseId,
                  relation.id(),
                  revision,
                  source.get("evidence_id"),
                  source.get("acl_version"));
          }
        });
  }

  /** Never infer graph seed names from the question. The retrieval pipeline supplies actual IDs. */
  @Override
  public List<Candidate> search(String query, SearchFilters filters, int limit) {
    return List.of();
  }

  @Override
  public GraphExpansion expand(List<Candidate> retrievedSeeds, SearchFilters filters) {
    if (retrievedSeeds == null || retrievedSeeds.isEmpty())
      return new GraphExpansion(List.of(), false, 0);
    var versions = new LinkedHashSet<String>();
    for (var seed : retrievedSeeds) {
      if (versions.size() >= 5) break;
      Object sourceVersions = seed.attributes().get("source_version_ids");
      if (sourceVersions instanceof List<?> list)
        for (Object value : list) {
          if (versions.size() >= 5) break;
          versions.add(value.toString());
        }
      else if (seed.versionId() != null && !seed.versionId().isBlank())
        versions.add(seed.versionId());
    }
    if (versions.isEmpty()) return new GraphExpansion(List.of(), false, 0);
    int clearance = filters == null ? 10 : filters.securityClearance();
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("kb", knowledgeBaseId);
    parameters.put("versions", List.copyOf(versions));
    parameters.put("clearance", clearance);
    // Raw/derived candidates map to their authoritative source documents. Do not promote arbitrary
    // endpoints of every fact mentioning a source into graph seeds.
    List<String> frontier =
        named.queryForList(
            """
        SELECT DISTINCT n.entity_id FROM kb_source_authority a
        JOIN kb_graph_entity n ON n.kb_id=:kb AND n.entity_type='DOCUMENT' AND n.object_id=a.document_id
        WHERE a.version_id IN (:versions) AND a.security_level<=:clearance
        ORDER BY n.entity_id LIMIT 5
        """,
            parameters,
            String.class);
    Set<String> visitedNodes = new LinkedHashSet<>(frontier);
    Set<String> visitedEdges = new LinkedHashSet<>();
    var candidates = new ArrayList<Candidate>();
    boolean truncated = false;
    int hops = 0;
    while (!frontier.isEmpty() && hops < 2) {
      hops++;
      parameters.put("frontier", frontier);
      parameters.put(
          "seen", visitedEdges.isEmpty() ? List.of("<no-real-edge>") : List.copyOf(visitedEdges));
      var rows = named.queryForList(FRONTIER_QUERY, parameters);
      Map<String, Integer> perNode = new LinkedHashMap<>();
      Set<String> hopEdges = new LinkedHashSet<>();
      var next = new LinkedHashSet<String>();
      for (var row : rows) {
        String node = row.get("frontier_id").toString();
        int count = perNode.merge(node, 1, Integer::sum);
        if (count > 20) {
          truncated = true;
          continue;
        }
        String id = row.get("relation_id").toString();
        if (!hopEdges.add(id)) continue;
        if (hopEdges.size() > 20 || candidates.size() >= 40) {
          truncated = true;
          continue;
        }
        if (!visitedEdges.add(id)) continue;
        String from = row.get("source_entity_id").toString();
        String to = row.get("target_entity_id").toString();
        if (!visitedNodes.contains(from)) next.add(from);
        if (!visitedNodes.contains(to)) next.add(to);
        var evidence = array(row.get("evidence_ids"));
        var sourceVersions = array(row.get("version_ids"));
        var documents = array(row.get("document_ids"));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source_entity_id", from);
        attrs.put("target_entity_id", to);
        attrs.put("relation_type", row.get("relation_type"));
        attrs.put("revision_id", row.get("revision_id"));
        attrs.put("hop", hops);
        attrs.put("source_version_ids", sourceVersions);
        attrs.put("source_document_ids", documents);
        candidates.add(
            new Candidate(
                id,
                documents.get(0),
                sourceVersions.get(0),
                LaneType.GRAPH,
                row.get("source_name")
                    + " "
                    + row.get("relation_type")
                    + " "
                    + row.get("target_name"),
                1.0 / hops,
                evidence,
                attrs));
      }
      visitedNodes.addAll(next);
      frontier = List.copyOf(next);
    }
    return new GraphExpansion(candidates, truncated, hops);
  }

  private static final String FRONTIER_QUERY =
      """
      SELECT f.entity_id frontier_id,q.* FROM kb_graph_entity f
      CROSS JOIN LATERAL (
        SELECT r.*,s.canonical_name source_name,t.canonical_name target_name,
          (SELECT array_agg(ge.evidence_id ORDER BY ge.evidence_id) FROM kb_graph_evidence ge WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id) evidence_ids,
          (SELECT array_agg(DISTINCT e.version_id) FROM kb_graph_evidence ge JOIN evidence e ON e.evidence_id=ge.evidence_id WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id) version_ids,
          (SELECT array_agg(DISTINCT e.document_id) FROM kb_graph_evidence ge JOIN evidence e ON e.evidence_id=ge.evidence_id WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id) document_ids
        FROM kb_graph_relation r
        JOIN kb_graph_entity s ON s.kb_id=r.kb_id AND s.entity_id=r.source_entity_id
        JOIN kb_graph_entity t ON t.kb_id=r.kb_id AND t.entity_id=r.target_entity_id
        WHERE r.kb_id=f.kb_id AND (r.source_entity_id=f.entity_id OR r.target_entity_id=f.entity_id)
          AND r.is_active AND r.relation_id NOT IN (:seen)
          AND EXISTS (SELECT 1 FROM kb_graph_evidence ge WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id)
          AND NOT EXISTS (
            SELECT 1 FROM kb_graph_evidence ge
            LEFT JOIN evidence e ON e.evidence_id=ge.evidence_id
            LEFT JOIN document_version v ON v.version_id=e.version_id
            LEFT JOIN document d ON d.document_id=e.document_id
            LEFT JOIN kb_source_authority m ON m.version_id=e.version_id
            WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id
              AND (e.evidence_id IS NULL OR NOT e.active OR v.lifecycle IS DISTINCT FROM 'ACTIVE'
                OR d.status IS DISTINCT FROM 'ACTIVE' OR m.security_level IS NULL
                OR m.security_level>:clearance OR m.acl_version IS DISTINCT FROM ge.acl_version)
          )
        ORDER BY r.relation_id LIMIT 21
      ) q WHERE f.kb_id=:kb AND f.entity_id IN (:frontier)
      ORDER BY f.entity_id,q.relation_id
      """;

  @Override
  public void deactivateVersion(String versionId) {
    jdbc.update(
        "UPDATE kb_graph_relation r SET is_active=FALSE WHERE r.kb_id=? AND EXISTS(SELECT 1 FROM kb_graph_evidence ge JOIN evidence e ON e.evidence_id=ge.evidence_id WHERE ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id AND e.version_id=?)",
        knowledgeBaseId,
        versionId);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("graph serialization failed", e);
    }
  }

  private static java.sql.Timestamp timestamp(java.time.Instant value) {
    return value == null ? null : java.sql.Timestamp.from(value);
  }

  private static List<String> array(Object value) {
    try {
      return java.util.Arrays.stream((Object[]) ((java.sql.Array) value).getArray())
          .map(Object::toString)
          .toList();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("graph source array failed", e);
    }
  }
}
