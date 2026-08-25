package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.evaluation.ArtifactEvaluationApplicationService.ArtifactRepository;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ActualArtifacts;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.EntityMappingActual;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.RelationActual;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.WikiClaimActual;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only persisted artifact view used by deterministic Golden evaluation. */
public final class JdbcArtifactEvaluationRepository implements ArtifactRepository {
  private final JdbcTemplate jdbc;

  public JdbcArtifactEvaluationRepository(JdbcTemplate jdbc) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    this.jdbc = jdbc;
  }

  @Override
  public ActualArtifacts load(List<String> versionIds) {
    Scope scope = scope(versionIds);
    return new ActualArtifacts(
        loadSummaryFacts(scope),
        loadRelations(scope),
        loadEntityMappings(scope),
        loadWikiClaims(scope));
  }

  private List<String> loadSummaryFacts(Scope scope) {
    String sql =
        "SELECT DISTINCT sf.fact_text FROM summary_fact sf "
            + "JOIN summary sm ON sm.summary_id=sf.summary_id "
            + "WHERE sm.lifecycle='ACTIVE' AND sm.version_id IN ("
            + scope.placeholders()
            + ") ORDER BY sf.fact_text";
    return jdbc.query(sql, (rs, rowNum) -> rs.getString(1), scope.args().toArray());
  }

  private List<RelationActual> loadRelations(Scope scope) {
    String sql =
        "SELECT DISTINCT src.canonical_name,rf.relation_type,dst.canonical_name FROM relation_fact rf "
            + "JOIN canonical_entity src ON src.canonical_entity_id=rf.source_entity_id "
            + "JOIN canonical_entity dst ON dst.canonical_entity_id=rf.target_entity_id "
            + "JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE "
            + "JOIN evidence e ON e.evidence_id=rs.evidence_id AND e.active=TRUE "
            + "WHERE rf.lifecycle='ACTIVE' AND e.version_id IN ("
            + scope.placeholders()
            + ") "
            + "ORDER BY src.canonical_name,rf.relation_type,dst.canonical_name";
    return jdbc.query(
        sql,
        (rs, rowNum) -> new RelationActual(rs.getString(1), rs.getString(2), rs.getString(3)),
        scope.args().toArray());
  }

  private List<EntityMappingActual> loadEntityMappings(Scope scope) {
    String sql =
        "SELECT DISTINCT se.surface_form,se.entity_type,ce.canonical_name FROM source_entity se "
            + "JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id AND em.status='APPLIED' "
            + "JOIN canonical_entity ce ON ce.canonical_entity_id=em.canonical_entity_id "
            + "WHERE se.version_id IN ("
            + scope.placeholders()
            + ") ORDER BY se.surface_form,se.entity_type,ce.canonical_name";
    return jdbc.query(
        sql,
        (rs, rowNum) -> new EntityMappingActual(rs.getString(1), rs.getString(2), rs.getString(3)),
        scope.args().toArray());
  }

  private List<WikiClaimActual> loadWikiClaims(Scope scope) {
    String sql =
        "SELECT wc.claim_id,wc.claim_text,wc.grounding_status,"
            + "MAX(CASE WHEN e.active=TRUE THEN 1 ELSE 0 END) AS has_active_evidence "
            + "FROM wiki_claim wc "
            + "JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version AND w.lifecycle='ACTIVE' "
            + "JOIN wiki_evidence we ON we.claim_id=wc.claim_id "
            + "JOIN evidence e ON e.evidence_id=we.evidence_id "
            + "WHERE e.version_id IN ("
            + scope.placeholders()
            + ") "
            + "GROUP BY wc.claim_id,wc.claim_text,wc.grounding_status ORDER BY wc.claim_id";
    return jdbc.query(
        sql,
        (rs, rowNum) ->
            new WikiClaimActual(
                rs.getString(2), "PASS".equalsIgnoreCase(rs.getString(3)) && rs.getInt(4) > 0),
        scope.args().toArray());
  }

  static Scope scope(List<String> versionIds) {
    Set<String> unique = new LinkedHashSet<>();
    if (versionIds != null) {
      for (String versionId : versionIds) {
        if (versionId != null && !versionId.isBlank()) unique.add(versionId.trim());
      }
    }
    if (unique.isEmpty())
      throw new IllegalArgumentException("evaluation requires at least one version_id");
    List<String> args = new ArrayList<>(unique);
    return new Scope(
        String.join(",", java.util.Collections.nCopies(args.size(), "?")), List.copyOf(args));
  }

  record Scope(String placeholders, List<String> args) {}
}
