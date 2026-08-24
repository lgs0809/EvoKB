package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Request-local fact references. Models copy short keys; persisted provenance keeps exact IDs. */
final class GroundedFactReferences {
  private final Map<String, SummaryFact> facts;
  private final Map<String, String> canonicalByReference = new LinkedHashMap<>();
  private final Map<String, String> referenceByCanonical = new LinkedHashMap<>();

  GroundedFactReferences(Map<String, SummaryFact> input) {
    facts = new LinkedHashMap<>();
    int next = 1;
    for (String id : input.keySet().stream().sorted().toList()) {
      String reference;
      do {
        reference = String.format(Locale.ROOT, "F%03d", next++);
      } while (input.containsKey(reference));
      facts.put(id, input.get(id));
      canonicalByReference.put(reference, id);
      referenceByCanonical.put(id, reference);
    }
  }

  String canonicalId(String reference) {
    String exact = reference == null ? "" : reference.trim();
    // Unknown values are preserved for the caller's existing strict grounding validation.
    // Never guess an ID from a prefix, similar spelling or the referenced fact's text.
    return canonicalByReference.getOrDefault(exact, exact);
  }

  List<String> canonicalIds(List<String> references) {
    return references == null ? List.of() : references.stream().map(this::canonicalId).toList();
  }

  String renderFacts() {
    StringBuilder out = new StringBuilder();
    for (SummaryFact fact : facts.values()) {
      out.append("<fact id=\"")
          .append(referenceByCanonical.get(fact.id()))
          .append("\" section=\"")
          .append(attribute(fact.section()))
          .append("\">\n")
          .append(fact.factText())
          .append("\n</fact>\n");
    }
    return out.toString();
  }

  private static String attribute(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
  }
}
