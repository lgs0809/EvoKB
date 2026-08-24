package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroundedFactReferencesTest {
  private static SummaryFact fact(String id) {
    return new SummaryFact(
        id,
        "summary",
        "费用\"与<期限>",
        "完整原文：" + id,
        List.of("block"),
        List.of("evidence"),
        "PASS",
        "direct");
  }

  @Test
  void referencesAreDeterministicAndDoNotDependOnRepositoryRowOrder() {
    var first = new LinkedHashMap<String, SummaryFact>();
    first.put("uuid-b", fact("uuid-b"));
    first.put("uuid-a", fact("uuid-a"));
    var refs = new GroundedFactReferences(first);
    var reverse =
        new GroundedFactReferences(Map.of("uuid-a", fact("uuid-a"), "uuid-b", fact("uuid-b")));
    assertEquals(refs.renderFacts(), reverse.renderFacts());
    assertEquals("uuid-a", refs.canonicalId("F001"));
    assertEquals("uuid-b", refs.canonicalId(" F002 "));
    assertTrue(refs.renderFacts().contains("<fact id=\"F001\""));
    assertTrue(refs.renderFacts().contains("完整原文：uuid-a"));
    assertTrue(refs.renderFacts().contains("费用&quot;与&lt;期限&gt;"));
  }

  @Test
  void unknownOrMisspelledReferencesAreNeverFuzzilyRepaired() {
    var refs = new GroundedFactReferences(Map.of("canonical-uuid", fact("canonical-uuid")));
    assertEquals("canonical-uuid", refs.canonicalId("canonical-uuid"));
    for (String unknown : List.of("F01", "f001", "F002", "canonical-other"))
      assertEquals(unknown, refs.canonicalId(unknown));
    assertEquals("", refs.canonicalId(null));
  }

  @Test
  void aliasesNeverShadowAnExistingCanonicalId() {
    var refs = new GroundedFactReferences(Map.of("F001", fact("F001"), "uuid-b", fact("uuid-b")));
    assertEquals("F001", refs.canonicalId("F001"));
    assertEquals("F001", refs.canonicalId("F002"));
    assertEquals("uuid-b", refs.canonicalId("F003"));
  }

  @Test
  void mappingsAreRequestLocalAndPersistedReferencesRemainCanonical() {
    var a = new GroundedFactReferences(Map.of("a", fact("a")));
    var b = new GroundedFactReferences(Map.of("b", fact("b")));
    assertEquals(List.of("a", "a", "unknown"), a.canonicalIds(List.of("F001", "a", "unknown")));
    assertEquals("b", b.canonicalId("F001"));
    assertEquals(List.of(), a.canonicalIds(null));
  }
}
