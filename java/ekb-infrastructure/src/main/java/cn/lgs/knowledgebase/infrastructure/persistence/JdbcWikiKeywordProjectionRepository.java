package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.GroundingConstraintAudit;
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

/** PostgreSQL fact-layer implementation for deterministic Wiki keyword graph projection. */
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
  public List<String> loadBuildInputVersions(String wikiId, long wikiVersion) {
    return jdbc.query(
        "SELECT dep.source_version_id FROM wiki w JOIN kb_asset_build_dependency dep "
            + "ON dep.asset_type='WIKI' AND dep.asset_id=w.wiki_id AND dep.build_revision=w.wiki_version::text "
            + "WHERE w.wiki_id=? AND w.wiki_version=? AND "
            + SourceAccessSql.wiki("w", 127)
            + " ORDER BY dep.source_version_id",
        (rs, n) -> rs.getString(1),
        wikiId,
        wikiVersion);
  }

  @Override
  public List<WikiKeywordClaim> loadClaims(String wikiId, long wikiVersion) {
    List<ClaimHeader> headers =
        jdbc.query(
            "SELECT claim_id,claim_text,grounding_audit FROM wiki_claim WHERE wiki_id=? AND wiki_version=? AND grounding_status='PASS' ORDER BY claim_id",
            (rs, rowNum) ->
                new ClaimHeader(rs.getString(1), rs.getString(2), readAudit(rs.getString(3))),
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
    Map<String, Map<String, SupportFact>> supports = new LinkedHashMap<>();
    jdbc.query(
        "SELECT cf.claim_id,f.fact_id,f.fact_text,f.grounding_audit,f.grounding_status,s.lifecycle,s.coverage_status,e.active,b.raw_content "
            + "FROM wiki_claim_fact cf JOIN summary_fact f USING(fact_id) JOIN summary s USING(summary_id) "
            + "JOIN kb_source_authority a ON a.version_id=s.version_id "
            + "LEFT JOIN summary_evidence se USING(fact_id) LEFT JOIN evidence e ON e.evidence_id=se.evidence_id "
            + "LEFT JOIN raw_block b ON b.block_id=e.block_id WHERE cf.claim_id IN ("
            + placeholders
            + ") "
            + "ORDER BY cf.claim_id,f.fact_id,e.evidence_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              var rows =
                  supports.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>());
              var fact = rows.get(rs.getString(2));
              if (fact == null) {
                fact =
                    new SupportFact(
                        rs.getString(2),
                        rs.getString(3),
                        readAudit(rs.getString(4)),
                        "PASS".equals(rs.getString(5))
                            && "ACTIVE".equals(rs.getString(6))
                            && "PASS".equals(rs.getString(7)));
                rows.put(fact.id, fact);
              }
              fact.active &= rs.getBoolean(8) && rs.getString(9) != null;
              if (rs.getString(9) != null) fact.evidence.append("\n---\n").append(rs.getString(9));
            },
        claimIds.toArray());
    List<WikiKeywordClaim> out = new ArrayList<>(headers.size());
    for (ClaimHeader header : headers) {
      var facts = supports.getOrDefault(header.id(), Map.of()).values();
      String supportingText =
          facts.stream()
              .map(f -> "[" + f.id + "] " + f.text)
              .collect(java.util.stream.Collectors.joining("\n"));
      boolean currentSources =
          !facts.isEmpty()
              && facts.stream()
                  .allMatch(
                      f ->
                          f.active
                              && GroundingConstraintAudit.current(
                                  f.text, f.evidence.toString(), f.audit));
      out.add(
          new WikiKeywordClaim(
              header.id(),
              wikiId,
              wikiVersion,
              header.text(),
              List.copyOf(evidence.getOrDefault(header.id(), List.of())),
              header.audit(),
              supportingText,
              currentSources));
    }
    return List.copyOf(out);
  }

  @Override
  public List<Entity> findCanonicalEntitiesByName(String canonicalName) {
    String name = canonicalName == null ? "" : canonicalName.trim();
    if (name.isEmpty()) return List.of();
    String compact = name.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return jdbc.query(
        "SELECT canonical_entity_id,canonical_name,entity_type,canonical_key,COALESCE(attributes_json,'{}') "
            + "FROM canonical_entity WHERE LOWER(TRIM(canonical_name))=LOWER(TRIM(?)) "
            + "OR canonical_name LIKE ? ESCAPE '!' OR canonical_name LIKE ? ESCAPE '!' "
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
              "INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle) VALUES(?,?,?,?,NULL,NULL,?,?) ON CONFLICT (relation_id) DO UPDATE SET conflict_status=EXCLUDED.conflict_status,lifecycle=EXCLUDED.lifecycle",
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
                "INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,wiki_id,wiki_version,confidence,active) VALUES(?,?,'wiki_keyword',?,?,?,?,TRUE) ON CONFLICT (support_id) DO UPDATE SET confidence=EXCLUDED.confidence,active=TRUE",
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

  private GroundingConstraintAudit.Audit readAudit(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return mapper.readValue(value, GroundingConstraintAudit.Audit.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("invalid stored grounding audit", failure);
    }
  }

  private static final class SupportFact {
    final String id, text;
    final GroundingConstraintAudit.Audit audit;
    final StringBuilder evidence = new StringBuilder();
    boolean active;

    SupportFact(String id, String text, GroundingConstraintAudit.Audit audit, boolean active) {
      this.id = id;
      this.text = text;
      this.audit = audit;
      this.active = active;
    }
  }

  private record ClaimHeader(String id, String text, GroundingConstraintAudit.Audit audit) {}
}
