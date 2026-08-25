package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService.Repository;
import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService.WikiKeywordClaim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL fact-layer implementation for deterministic Wiki keyword graph projection. */
public final class JdbcWikiKeywordProjectionRepository implements Repository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

  public JdbcWikiKeywordProjectionRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
  }

  @Override
  public List<WikiKeywordClaim> loadClaims(String wikiId, long wikiVersion) {
    List<ClaimHeader> headers =
        jdbc.query(
            "SELECT claim_id,claim_text FROM wiki_claim WHERE wiki_id=? AND wiki_version=? AND grounding_status='PASS' ORDER BY claim_id",
            (rs, rowNum) -> new ClaimHeader(rs.getString(1), rs.getString(2)),
            wikiId,
            wikiVersion);
    if (headers.isEmpty()) return List.of();
    List<String> claimIds = headers.stream().map(ClaimHeader::id).toList();
    String placeholders = String.join(",", java.util.Collections.nCopies(claimIds.size(), "?"));
    Map<String, List<String>> evidence = new LinkedHashMap<>();
    jdbc.query(
        "SELECT we.claim_id,e.evidence_id FROM wiki_evidence we JOIN evidence e ON e.evidence_id=we.evidence_id "
            + "WHERE we.claim_id IN ("
            + placeholders
            + ") AND e.active=TRUE ORDER BY we.claim_id,e.evidence_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                evidence
                    .computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                    .add(rs.getString(2)),
        claimIds.toArray());
    List<WikiKeywordClaim> out = new ArrayList<>(headers.size());
    for (ClaimHeader header : headers) {
      out.add(
          new WikiKeywordClaim(
              header.id(),
              wikiId,
              wikiVersion,
              header.text(),
              List.copyOf(evidence.getOrDefault(header.id(), List.of()))));
    }
    return List.copyOf(out);
  }

  @Override
  public List<Entity> findCanonicalEntitiesByName(String canonicalName) {
    String name = canonicalName == null ? "" : canonicalName.trim();
    if (name.isEmpty()) return List.of();
    String compact = name.replace("%", "\\%").replace("_", "\\_");
    return jdbc.query(
        "SELECT canonical_entity_id,canonical_name,entity_type,canonical_key,COALESCE(attributes_json,'{}') "
            + "FROM canonical_entity WHERE LOWER(TRIM(canonical_name))=LOWER(TRIM(?)) "
            + "OR canonical_name LIKE ? ESCAPE '\\\\' OR canonical_name LIKE ? ESCAPE '\\\\' "
            + "ORDER BY CASE WHEN LOWER(TRIM(canonical_name))=LOWER(TRIM(?)) THEN 0 ELSE 1 END,canonical_entity_id LIMIT 2",
        (rs, rowNum) ->
            new Entity(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                List.of(),
                objectMap(rs.getString(5))),
        name,
        compact + "（%",
        compact + " (%",
        name);
  }

  @Override
  public Relation saveRelation(Relation relation, String wikiId, long wikiVersion, String claimId) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle) VALUES(?,?,?,?,NULL,NULL,?,?) "
                  + "ON DUPLICATE KEY UPDATE conflict_status=VALUES(conflict_status),lifecycle=VALUES(lifecycle)",
              relation.id(),
              relation.subjectEntityId(),
              relation.predicate(),
              relation.objectEntityId(),
              relation.conflictStatus().name(),
              relation.lifecycle().name());
          for (String evidenceId :
              relation.evidenceIds().stream()
                  .filter(v -> v != null && !v.isBlank())
                  .distinct()
                  .toList()) {
            String supportId =
                GraphCompilationService.stableId(
                    "support",
                    relation.id(),
                    "wiki_keyword",
                    evidenceId,
                    wikiId,
                    Long.toString(wikiVersion),
                    claimId);
            jdbc.update(
                "INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,wiki_id,wiki_version,confidence,active) "
                    + "VALUES(?,?,'wiki_keyword',?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE confidence=VALUES(confidence),active=TRUE",
                supportId,
                relation.id(),
                evidenceId,
                wikiId,
                wikiVersion,
                relation.confidence());
          }
        });
    return relation;
  }

  private Map<String, Object> objectMap(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse canonical entity attributes", e);
    }
  }

  private record ClaimHeader(String id, String text) {}
}
