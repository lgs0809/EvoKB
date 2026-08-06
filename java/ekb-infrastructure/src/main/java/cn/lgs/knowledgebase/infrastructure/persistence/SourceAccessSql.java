package cn.lgs.knowledgebase.infrastructure.persistence;

/** Native pre-count/pre-limit current authority predicates shared by read adapters. */
final class SourceAccessSql {
  private SourceAccessSql() {}

  static String wiki(String alias, int clearance) {
    checked(clearance);
    String inputs =
        "SELECT dep.source_version_id,dep.acl_version FROM kb_asset_build_dependency dep WHERE dep.asset_type='WIKI' AND dep.asset_id="
            + alias
            + ".wiki_id AND dep.build_revision="
            + alias
            + ".wiki_version::text";
    return alias
        + ".lifecycle='ACTIVE' AND EXISTS ("
        + inputs
        + ") AND NOT EXISTS (SELECT 1 FROM ("
        + inputs
        + ") deps LEFT JOIN kb_source_authority a ON a.version_id=deps.source_version_id WHERE a.version_id IS NULL OR a.security_level > "
        + clearance
        + " OR a.acl_version<>deps.acl_version) AND NOT EXISTS (SELECT 1 FROM wiki_claim wc JOIN wiki_evidence we ON we.claim_id=wc.claim_id LEFT JOIN evidence e ON e.evidence_id=we.evidence_id LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE wc.wiki_id="
        + alias
        + ".wiki_id AND wc.wiki_version="
        + alias
        + ".wiki_version AND (e.evidence_id IS NULL OR e.active=FALSE OR a.version_id IS NULL OR a.security_level > "
        + clearance
        + "))";
  }

  static String relation(String alias, int clearance) {
    return "EXISTS(SELECT 1 FROM relation_evidence_support p WHERE p.relation_id="
        + alias
        + ".relation_id AND p.active=TRUE) AND NOT EXISTS(SELECT 1 FROM relation_evidence_support p LEFT JOIN evidence e ON e.evidence_id=p.evidence_id LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE p.relation_id="
        + alias
        + ".relation_id AND p.active=TRUE AND (e.evidence_id IS NULL OR e.active=FALSE OR a.version_id IS NULL OR a.security_level>"
        + checked(clearance)
        + "))";
  }

  static String publishedGraph(String alias, int clearance, String knowledgeBaseId) {
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank())
      throw new IllegalArgumentException("trusted graph knowledge base identity is required");
    return publishedGraphExpression(
        alias,
        "'" + knowledgeBaseId.replace("'", "''") + "'",
        Integer.toString(checked(clearance)));
  }

  /** Expressions are internal SQL literals or named parameters, never request text. */
  static String publishedGraphExpression(String alias, String kb, String clearance) {
    String dependency =
        "dep.kb_id="
            + alias
            + ".kb_id AND dep.relation_id="
            + alias
            + ".relation_id AND dep.revision_id="
            + alias
            + ".revision_id";
    return alias
        + ".kb_id="
        + kb
        + " AND "
        + alias
        + ".is_active AND "
        + alias
        + ".scope_key IS NOT NULL AND EXISTS(SELECT 1 FROM kb_graph_evidence dep WHERE "
        + dependency
        + ") AND NOT EXISTS(SELECT 1 FROM kb_graph_evidence dep "
        + "LEFT JOIN evidence e ON e.evidence_id=dep.evidence_id "
        + "LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE "
        + dependency
        + " AND (e.evidence_id IS NULL OR e.active IS DISTINCT FROM TRUE OR a.version_id IS NULL "
        + "OR a.security_level>"
        + clearance
        + " OR a.acl_version IS DISTINCT FROM dep.acl_version)) AND NOT EXISTS(SELECT 1 FROM kb_entry_pending_source pending WHERE pending.knowledge_base_id="
        + alias
        + ".kb_id AND pending.graph_repaired_at IS NULL AND EXISTS(SELECT 1 FROM kb_graph_evidence ge JOIN evidence ev ON ev.evidence_id=ge.evidence_id WHERE ge.kb_id="
        + alias
        + ".kb_id AND ge.relation_id="
        + alias
        + ".relation_id AND ge.revision_id="
        + alias
        + ".revision_id AND jsonb_exists(pending.affected_source_versions,ev.version_id)))";
  }

  static String version(String expression, int clearance) {
    return "EXISTS(SELECT 1 FROM kb_source_authority a WHERE a.version_id="
        + expression
        + " AND a.security_level<="
        + checked(clearance)
        + ")";
  }

  private static int checked(int clearance) {
    if (clearance < 10 || clearance > 127) throw new IllegalArgumentException("invalid clearance");
    return clearance;
  }
}
