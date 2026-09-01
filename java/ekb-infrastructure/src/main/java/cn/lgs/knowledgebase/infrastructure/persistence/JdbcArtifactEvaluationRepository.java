package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.evaluation.ArtifactEvaluationApplicationService.ArtifactRepository;
import cn.lgs.knowledgebase.application.evaluation.ArtifactEvaluationApplicationService.ArtifactSnapshot;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ActualArtifacts;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.EntityMappingActual;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.RelationActual;
import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.WikiClaimActual;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only persisted artifact view used by deterministic Golden evaluation. */
public final class JdbcArtifactEvaluationRepository implements ArtifactRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate reads;
  private final String knowledgeBaseId;

  public JdbcArtifactEvaluationRepository(JdbcTemplate jdbc) {
    this(jdbc, "enterprise");
  }

  public JdbcArtifactEvaluationRepository(JdbcTemplate jdbc, String knowledgeBaseId) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted graph knowledge base identity is required");
    this.jdbc = jdbc;
    this.knowledgeBaseId = knowledgeBaseId;
    reads =
        new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())));
    reads.setReadOnly(true);
    reads.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public ArtifactSnapshot loadScoped(List<String> versionIds, int clearance) {
    Scope scope = scope(versionIds);
    return reads.execute(
        status -> {
          var artifacts = load(versionIds, clearance);
          // Wiki inputs include private sources omitted from the final citations. Read them in
          // the same snapshot as the evaluated artifacts, then revalidate at application delivery.
          String sql =
              "SELECT DISTINCT dep.source_version_id FROM kb_asset_build_dependency dep JOIN wiki w ON dep.asset_type='WIKI' AND dep.asset_id=w.wiki_id AND dep.build_revision=w.wiki_version::text WHERE "
                  + SourceAccessSql.wiki("w", clearance)
                  + " AND EXISTS(SELECT 1 FROM wiki_claim wc JOIN wiki_evidence we ON we.claim_id=wc.claim_id JOIN evidence e ON e.evidence_id=we.evidence_id WHERE wc.wiki_id=w.wiki_id AND wc.wiki_version=w.wiki_version AND e.version_id IN ("
                  + scope.placeholders()
                  + "))";
          Set<String> inputs = new LinkedHashSet<>(scope.args());
          inputs.addAll(jdbc.query(sql, (rs, n) -> rs.getString(1), scope.args().toArray()));
          String relations =
              "SELECT DISTINCT e.version_id FROM kb_graph_relation rf JOIN kb_graph_evidence p ON p.kb_id=rf.kb_id AND p.relation_id=rf.relation_id AND p.revision_id=rf.revision_id JOIN evidence e ON e.evidence_id=p.evidence_id WHERE "
                  + SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId)
                  + " AND EXISTS(SELECT 1 FROM kb_graph_evidence selected JOIN evidence se ON se.evidence_id=selected.evidence_id WHERE selected.kb_id=rf.kb_id AND selected.relation_id=rf.relation_id AND selected.revision_id=rf.revision_id AND se.version_id IN ("
                  + scope.placeholders()
                  + "))";
          inputs.addAll(jdbc.query(relations, (rs, n) -> rs.getString(1), scope.args().toArray()));
          return new ArtifactSnapshot(artifacts, List.copyOf(inputs));
        });
  }

  @Override
  public ActualArtifacts load(List<String> versionIds) {
    return load(versionIds, 127);
  }

  @Override
  public ActualArtifacts load(List<String> versionIds, int clearance) {
    Scope scope = scope(versionIds);
    return new ActualArtifacts(
        loadSummaryFacts(scope, clearance),
        loadRelations(scope, clearance),
        loadEntityMappings(scope, clearance),
        loadWikiClaims(scope, clearance));
  }

  private List<String> loadSummaryFacts(Scope scope, int clearance) {
    String sql =
        "SELECT DISTINCT sf.fact_text FROM summary_fact sf "
            + "JOIN summary sm ON sm.summary_id=sf.summary_id "
            + "WHERE sm.lifecycle='ACTIVE' AND sm.version_id IN ("
            + scope.placeholders()
            + ") AND "
            + SourceAccessSql.version("sm.version_id", clearance)
            + " ORDER BY sf.fact_text";
    return jdbc.query(sql, (rs, rowNum) -> rs.getString(1), scope.args().toArray());
  }

  private List<RelationActual> loadRelations(Scope scope, int clearance) {
    String sql =
        "SELECT DISTINCT src.canonical_name,rf.relation_type,dst.canonical_name FROM kb_graph_relation rf "
            + "JOIN kb_graph_entity src ON src.kb_id=rf.kb_id AND src.entity_id=rf.source_entity_id "
            + "JOIN kb_graph_entity dst ON dst.kb_id=rf.kb_id AND dst.entity_id=rf.target_entity_id "
            + "JOIN kb_graph_evidence rs ON rs.kb_id=rf.kb_id AND rs.relation_id=rf.relation_id AND rs.revision_id=rf.revision_id "
            + "JOIN evidence e ON e.evidence_id=rs.evidence_id "
            + "WHERE rf.relation_type<>'DOCUMENTS' AND e.version_id IN ("
            + scope.placeholders()
            + ") AND "
            + SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId)
            + " "
            + "ORDER BY src.canonical_name,rf.relation_type,dst.canonical_name";
    return jdbc.query(
        sql,
        (rs, rowNum) -> new RelationActual(rs.getString(1), rs.getString(2), rs.getString(3)),
        scope.args().toArray());
  }

  private List<EntityMappingActual> loadEntityMappings(Scope scope, int clearance) {
    String sql =
        "SELECT DISTINCT se.surface_form,se.entity_type,ce.canonical_name FROM source_entity se "
            + "JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id AND em.status='APPLIED' "
            + "JOIN canonical_entity ce ON ce.canonical_entity_id=em.canonical_entity_id "
            + "WHERE se.version_id IN ("
            + scope.placeholders()
            + ") AND "
            + SourceAccessSql.version("se.version_id", clearance)
            + " AND EXISTS(SELECT 1 FROM kb_graph_relation r WHERE (r.source_entity_id=em.canonical_entity_id OR r.target_entity_id=em.canonical_entity_id) AND "
            + SourceAccessSql.publishedGraph("r", clearance, knowledgeBaseId)
            + ")"
            + " ORDER BY se.surface_form,se.entity_type,ce.canonical_name";
    return jdbc.query(
        sql,
        (rs, rowNum) -> new EntityMappingActual(rs.getString(1), rs.getString(2), rs.getString(3)),
        scope.args().toArray());
  }

  private List<WikiClaimActual> loadWikiClaims(Scope scope, int clearance) {
    String sql =
        "SELECT wc.claim_id,wc.claim_text,wc.grounding_status,"
            + "MAX(CASE WHEN e.active=TRUE THEN 1 ELSE 0 END) AS has_active_evidence "
            + "FROM wiki_claim wc "
            + "JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version AND w.lifecycle='ACTIVE' "
            + "JOIN wiki_evidence we ON we.claim_id=wc.claim_id "
            + "JOIN evidence e ON e.evidence_id=we.evidence_id "
            + "WHERE e.version_id IN ("
            + scope.placeholders()
            + ") AND "
            + SourceAccessSql.wiki("w", clearance)
            + " "
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
