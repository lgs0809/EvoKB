package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.GraphRelationView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** UI graph exploration reads the same published revisions used by online retrieval. */
public final class JdbcPublishedGraphReader {
  private final NamedParameterJdbcTemplate jdbc;
  private final String knowledgeBaseId;

  public JdbcPublishedGraphReader(JdbcTemplate jdbc, String knowledgeBaseId) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted graph knowledge base identity is required");
    this.knowledgeBaseId = knowledgeBaseId;
  }

  public List<GraphRelationView> browse(
      String query, int depth, int limit, String relationType, Instant validAt, int clearance) {
    if (query == null || query.isBlank()) return List.of();
    int max = Math.min(limit <= 0 ? 10 : limit, 100);
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("kb", knowledgeBaseId);
    parameters.put("clearance", clearance);
    parameters.put("like", "%" + query.trim() + "%");
    parameters.put("limit", max);
    var extra = new StringBuilder();
    if (relationType != null && !relationType.isBlank()) {
      extra.append(" AND r.relation_type=:type");
      parameters.put("type", relationType.trim());
    }
    if (validAt != null) {
      extra.append(
          " AND (r.valid_from IS NULL OR r.valid_from<=:valid) AND (r.valid_to IS NULL OR r.valid_to>:valid)");
      parameters.put("valid", Timestamp.from(validAt));
    }
    var first =
        read(
            "(COALESCE(sd.logical_name,se.canonical_name) ILIKE :like OR "
                + "COALESCE(td.logical_name,te.canonical_name) ILIKE :like OR r.relation_type ILIKE :like)"
                + extra,
            parameters);
    if (depth <= 1 || first.isEmpty() || first.size() >= max) return first;
    var nodes = new LinkedHashSet<String>();
    var relations = new LinkedHashSet<String>();
    for (var row : first) {
      nodes.add(row.sourceEntityId());
      nodes.add(row.targetEntityId());
      relations.add(row.relationId());
    }
    parameters.put("nodes", nodes);
    parameters.put("seen", relations);
    parameters.put("limit", max - first.size());
    var out = new ArrayList<>(first);
    out.addAll(
        read(
            "(r.source_entity_id IN (:nodes) OR r.target_entity_id IN (:nodes)) "
                + "AND r.relation_id NOT IN (:seen)"
                + extra,
            parameters));
    return List.copyOf(out);
  }

  private List<GraphRelationView> read(String condition, Map<String, Object> parameters) {
    return jdbc.query(
        SELECT + " AND " + condition + GROUP,
        parameters,
        (rs, n) ->
            new GraphRelationView(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(),
                rs.getString(11),
                Arrays.asList(rs.getString(12).split(",")),
                rs.getDouble(13)));
  }

  private static final String SELECT =
      """
      SELECT r.relation_id,r.source_entity_id,COALESCE(sd.logical_name,se.canonical_name),se.entity_type,
        r.target_entity_id,COALESCE(td.logical_name,te.canonical_name),te.entity_type,r.relation_type,
        r.valid_from,r.valid_to,r.conflict_status,string_agg(DISTINCT ge.evidence_id,',' ORDER BY ge.evidence_id),
        COALESCE(r.confidence,0)
      FROM kb_graph_relation r
      JOIN kb_graph_entity se ON se.kb_id=r.kb_id AND se.entity_id=r.source_entity_id
      JOIN kb_graph_entity te ON te.kb_id=r.kb_id AND te.entity_id=r.target_entity_id
      LEFT JOIN document sd ON se.entity_type='DOCUMENT' AND sd.document_id=se.object_id
      LEFT JOIN document td ON te.entity_type='DOCUMENT' AND td.document_id=te.object_id
      JOIN kb_graph_evidence ge ON ge.kb_id=r.kb_id AND ge.relation_id=r.relation_id AND ge.revision_id=r.revision_id
      WHERE
      """
          + SourceAccessSql.publishedGraphExpression("r", ":kb", ":clearance");
  private static final String GROUP =
      """
      GROUP BY r.relation_id,r.source_entity_id,se.canonical_name,se.entity_type,sd.logical_name,
        r.target_entity_id,te.canonical_name,te.entity_type,td.logical_name,r.relation_type,
        r.valid_from,r.valid_to,r.conflict_status,r.confidence
      ORDER BY COALESCE(r.confidence,0) DESC,r.relation_id LIMIT :limit
      """;
}
