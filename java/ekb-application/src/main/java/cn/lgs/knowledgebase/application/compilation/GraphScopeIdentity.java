package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds identities from verified business anchors; names alone never identify a global object. */
public final class GraphScopeIdentity {
  private GraphScopeIdentity() {}

  public enum AnchorType {
    COMPANY,
    ORGANIZATION,
    PERSON,
    CONTRACT,
    PROJECT,
    LEGAL_CASE,
    LICENSE
  }

  public record Anchor(AnchorType type, String value, List<String> supportingFactIds) {
    public Anchor {
      supportingFactIds = supportingFactIds == null ? List.of() : List.copyOf(supportingFactIds);
    }

    public Anchor(String type, String value, List<String> supportingFactIds) {
      this(
          type == null ? null : AnchorType.valueOf(type.trim().toUpperCase(Locale.ROOT)),
          value,
          supportingFactIds);
    }
  }

  public record Scope(String key, Map<String, Object> attributes, List<String> evidenceIds) {}

  public static Scope scope(
      String documentId, List<Anchor> anchors, Map<String, SummaryFact> facts) {
    if (documentId == null || documentId.isBlank())
      throw new IllegalArgumentException("trusted document identity is required for graph scope");
    if (anchors == null || anchors.isEmpty()) {
      // An unresolved mention remains local to its actual document. This does not assert that two
      // equally named objects from different files are the same business object.
      return new Scope(
          "document:" + documentId,
          Map.of("identity_status", "SOURCE_SCOPED", "document_id", documentId),
          List.of());
    }
    var identity = new java.util.TreeMap<String, Map<String, String>>();
    var evidence = new java.util.TreeSet<String>();
    for (Anchor anchor : anchors) {
      if (anchor == null || anchor.type() == null || anchor.value() == null)
        throw new IllegalArgumentException("graph scope anchor is empty");
      String type = anchor.type().name();
      String value = GraphCompilationService.canonicalKey(anchor.value());
      if (value.isBlank())
        throw new IllegalArgumentException("graph scope anchor requires its literal fact value");
      if (anchor.supportingFactIds().isEmpty())
        throw new IllegalArgumentException("graph scope anchor requires exact supporting fact IDs");
      for (String id : anchor.supportingFactIds()) {
        SummaryFact fact = facts.get(id);
        if (fact == null
            || !"PASS".equals(fact.groundingStatus())
            || fact.evidenceIds().isEmpty()
            || !GraphCompilationService.canonicalKey(fact.factText()).contains(value))
          throw new IllegalArgumentException(
              "graph scope anchor is not stated in its supporting fact: "
                  + type
                  + ":"
                  + anchor.value()
                  + ", fact="
                  + id);
        evidence.addAll(fact.evidenceIds());
      }
      identity.put(type + "\u0000" + value, Map.of("type", type, "value", value));
    }
    String key =
        GraphCompilationService.stableId(
            "business_scope", String.join("\u0001", identity.keySet()));
    return new Scope(
        key,
        Map.of("identity_status", "SCOPE_GROUNDED", "anchors", List.copyOf(identity.values())),
        List.copyOf(evidence));
  }

  public static Entity entity(String name, String type, Scope scope) {
    String normalized = GraphCompilationService.canonicalKey(name);
    String id = GraphCompilationService.stableId("scoped_entity", type, scope.key(), normalized);
    var attributes = new LinkedHashMap<>(scope.attributes());
    attributes.put("scope_key", scope.key());
    attributes.put("object_id", id);
    attributes.put("surface_key", normalized);
    // The compatibility table has a prefix uniqueness index. A fixed-size identity key avoids
    // long names colliding in that prefix and never merges across scopes.
    return new Entity(
        id,
        name.trim(),
        type,
        GraphCompilationService.stableId("entity_key", type, scope.key(), normalized),
        List.of(),
        attributes);
  }

  public static Entity document(String documentId) {
    String id = GraphCompilationService.stableId("graph_document", documentId);
    return new Entity(
        id,
        "资料 " + documentId,
        "DOCUMENT",
        id,
        List.of(),
        Map.of(
            "scope_key",
            "document:" + documentId,
            "object_id",
            documentId,
            "document_id",
            documentId,
            "identity_status",
            "AUTHORITATIVE_DOCUMENT"));
  }
}
