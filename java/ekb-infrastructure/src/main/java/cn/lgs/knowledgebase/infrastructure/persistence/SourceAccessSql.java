package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native pre-count/pre-limit current authority predicates shared by read adapters. */
public final class SourceAccessSql {
  private SourceAccessSql() {}

  public static RetrievalScope retrievalScope(SearchFilters filters) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    if (filters != null) {
      if (!filters.versionIds().isEmpty()) parameters.put("scopeVersions", filters.versionIds());
      putFilter(parameters, "scopeDepartment", filters.sourceDepartment());
      putFilter(parameters, "scopeCategory", filters.materialCategory());
      putFilter(parameters, "scopeType", filters.documentType());
    }
    return new RetrievalScope(Map.copyOf(parameters));
  }

  private static void putFilter(Map<String, Object> parameters, String name, String value) {
    if (value != null && !value.isBlank()) parameters.put(name, value);
  }

  /** Version expressions are static SQL references. Requested values are bound parameters. */
  public record RetrievalScope(Map<String, Object> parameters) {
    public String matches(String versionExpression) {
      var checks = new ArrayList<String>();
      var metadata = new ArrayList<String>();
      if (parameters.containsKey("scopeVersions"))
        checks.add(versionExpression + " IN (:scopeVersions)");
      if (parameters.containsKey("scopeDepartment"))
        metadata.add("scope_metadata.source_department=:scopeDepartment");
      if (parameters.containsKey("scopeCategory"))
        metadata.add("scope_metadata.material_category=:scopeCategory");
      if (parameters.containsKey("scopeType"))
        metadata.add(
            "COALESCE(scope_metadata.manual_document_type_override,scope_metadata.auto_document_type)=:scopeType");
      if (!metadata.isEmpty())
        checks.add(
            "EXISTS(SELECT 1 FROM document_metadata scope_metadata WHERE scope_metadata.version_id="
                + versionExpression
                + " AND "
                + String.join(" AND ", metadata)
                + ")");
      return checks.isEmpty() ? "TRUE" : String.join(" AND ", checks);
    }
  }

  static String wiki(String alias, int clearance) {
    checked(clearance);
    String inputs =
        "SELECT dep.source_version_id,dep.acl_version FROM kb_asset_build_dependency dep WHERE dep.asset_type='WIKI' AND dep.asset_id="
            + alias
            + ".wiki_id AND dep.build_revision="
            + alias
            + ".wiki_version::text";
    return alias
        + ".lifecycle='ACTIVE' AND "
        + completeInputs(alias, inputs, Integer.toString(clearance))
        + " AND NOT EXISTS (SELECT 1 FROM wiki_claim wc JOIN wiki_evidence we ON we.claim_id=wc.claim_id LEFT JOIN evidence e ON e.evidence_id=we.evidence_id LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE wc.wiki_id="
        + alias
        + ".wiki_id AND wc.wiki_version="
        + alias
        + ".wiki_version AND (e.evidence_id IS NULL OR e.active=FALSE OR a.version_id IS NULL OR a.security_level > "
        + clearance
        + " OR NOT EXISTS(SELECT 1 FROM ("
        + inputs
        + ") complete WHERE complete.source_version_id=e.version_id)))";
  }

  static String relation(String alias, int clearance) {
    return "EXISTS(SELECT 1 FROM kb_graph_relation certified WHERE certified.relation_id="
        + alias
        + ".relation_id AND "
        + publishedGraphExpression(
            "certified", "certified.kb_id", Integer.toString(checked(clearance)))
        + ")";
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
  public static String publishedGraphExpression(String alias, String kb, String clearance) {
    String dependency =
        "dep.kb_id="
            + alias
            + ".kb_id AND dep.relation_id="
            + alias
            + ".relation_id AND dep.revision_id="
            + alias
            + ".revision_id";
    String inputs =
        "SELECT dep.source_version_id,dep.acl_version FROM kb_graph_build_dependency dep WHERE "
            + dependency;
    return alias
        + ".kb_id="
        + kb
        + " AND "
        + alias
        + ".is_active AND "
        + alias
        + ".scope_key IS NOT NULL AND "
        + completeInputs(alias, inputs, clearance)
        + " AND EXISTS(SELECT 1 FROM kb_graph_evidence dep WHERE "
        + dependency
        + ") AND NOT EXISTS(SELECT 1 FROM kb_graph_evidence dep "
        + "LEFT JOIN evidence e ON e.evidence_id=dep.evidence_id "
        + "LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE "
        + dependency
        + " AND (e.evidence_id IS NULL OR e.active IS DISTINCT FROM TRUE OR a.version_id IS NULL "
        + "OR a.security_level>"
        + clearance
        + " OR a.acl_version IS DISTINCT FROM dep.acl_version OR NOT EXISTS(SELECT 1 FROM ("
        + inputs
        + ") complete WHERE complete.source_version_id=e.version_id))) AND NOT EXISTS(SELECT 1 FROM kb_entry_pending_source pending WHERE pending.knowledge_base_id="
        + alias
        + ".kb_id AND pending.graph_repaired_at IS NULL AND EXISTS(SELECT 1 FROM kb_graph_build_dependency ge WHERE ge.kb_id="
        + alias
        + ".kb_id AND ge.relation_id="
        + alias
        + ".relation_id AND ge.revision_id="
        + alias
        + ".revision_id AND jsonb_exists(pending.affected_source_versions,ge.source_version_id)))";
  }

  /** A policy marker alone cannot certify an empty or incomplete persisted snapshot. */
  private static String completeInputs(String alias, String inputs, String clearance) {
    return alias
        + ".input_policy_version='"
        + cn.lgs.knowledgebase.application.compilation.CompilationContracts.COMPLETE_INPUT_POLICY
        + "' AND "
        + alias
        + ".input_source_count>0 AND "
        + alias
        + ".input_source_count=(SELECT COUNT(*) FROM ("
        + inputs
        + ") complete_count) AND NOT EXISTS(SELECT 1 FROM ("
        + inputs
        + ") deps LEFT JOIN kb_source_authority a ON a.version_id=deps.source_version_id WHERE "
        + "a.version_id IS NULL OR a.security_level>"
        + clearance
        + " OR a.acl_version IS DISTINCT FROM deps.acl_version)";
  }

  static String version(String expression, int clearance) {
    return "EXISTS(SELECT 1 FROM kb_source_authority a WHERE a.version_id="
        + expression
        + " AND a.security_level<="
        + checked(clearance)
        + ")";
  }

  /** Preserve unexecuted queue visibility; captured model inputs govern error and status reads. */
  static String compilationTask(String alias, int clearance) {
    return version(alias + ".version_id", clearance)
        + " AND NOT EXISTS(SELECT 1 FROM compile_task_input dep LEFT JOIN kb_source_authority a "
        + "ON a.version_id=dep.source_version_id WHERE dep.task_id="
        + alias
        + ".task_id AND dep.execution_epoch="
        + alias
        + ".execution_epoch AND (a.version_id IS NULL OR a.security_level>"
        + checked(clearance)
        + " OR a.acl_version IS DISTINCT FROM dep.acl_version))";
  }

  /** Old-attempt receipts use that attempt's complete inputs, never the latest task epoch. */
  static String compilationReceipt(String task, String receipt, int clearance) {
    String epoch = receiptNumber(receipt, "executionEpoch");
    String count = receiptNumber(receipt, "inputSourceCount");
    String inputs = "dep.task_id=" + task + ".task_id AND dep.execution_epoch=" + epoch;
    return version(task + ".version_id", clearance)
        + " AND "
        + count
        + ">0 AND "
        + count
        + "=(SELECT count(*) FROM compile_task_input dep WHERE "
        + inputs
        + ")"
        + " AND NOT EXISTS(SELECT 1 FROM compile_task_input dep LEFT JOIN kb_source_authority a "
        + "ON a.version_id=dep.source_version_id WHERE "
        + inputs
        + " AND (a.version_id IS NULL OR a.security_level>"
        + checked(clearance)
        + " OR a.acl_version IS DISTINCT FROM dep.acl_version))";
  }

  private static String receiptNumber(String alias, String key) {
    return "(CASE WHEN jsonb_typeof("
        + alias
        + ".details_json->'"
        + key
        + "')='number' THEN ("
        + alias
        + ".details_json->>'"
        + key
        + "')::numeric ELSE NULL END)";
  }

  private static int checked(int clearance) {
    if (clearance < 10 || clearance > 127) throw new IllegalArgumentException("invalid clearance");
    return clearance;
  }
}
