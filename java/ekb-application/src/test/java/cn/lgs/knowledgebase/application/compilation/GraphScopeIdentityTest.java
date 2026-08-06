package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphScopeIdentityTest {
  private static SummaryFact fact(String id, String text, String evidence) {
    return new SummaryFact(
        id, "summary", "Scope", text, List.of("block"), List.of(evidence), "PASS", "");
  }

  private static GraphScopeIdentity.Anchor anchor(String type, String name, String fact) {
    return new GraphScopeIdentity.Anchor(type, name, List.of(fact));
  }

  @Test
  void equallyNamedUnresolvedObjectsDoNotMergeAcrossDocuments() {
    var a =
        GraphScopeIdentity.entity(
            "HT-017", "CONTRACT", GraphScopeIdentity.scope("doc-a", List.of(), Map.of()));
    var b =
        GraphScopeIdentity.entity(
            "HT-017", "CONTRACT", GraphScopeIdentity.scope("doc-b", List.of(), Map.of()));
    assertNotEquals(a.canonicalId(), b.canonicalId());
    assertEquals("SOURCE_SCOPED", a.attributes().get("identity_status"));
  }

  @Test
  void sameContractNumberWithDifferentPartiesRemainsDifferentEvenInOneFile() {
    var facts = Map.of("a", fact("a", "甲公司合同HT-017。", "ea"), "b", fact("b", "乙公司合同HT-017。", "eb"));
    var scopeA =
        GraphScopeIdentity.scope(
            "combined-doc",
            List.of(anchor("CONTRACT", "HT-017", "a"), anchor("COMPANY", "甲公司", "a")),
            facts);
    var scopeB =
        GraphScopeIdentity.scope(
            "combined-doc",
            List.of(anchor("CONTRACT", "HT-017", "b"), anchor("COMPANY", "乙公司", "b")),
            facts);
    assertNotEquals(
        GraphScopeIdentity.entity("HT-017", "CONTRACT", scopeA).canonicalId(),
        GraphScopeIdentity.entity("HT-017", "CONTRACT", scopeB).canonicalId());
  }

  @Test
  void groundedIdentityIsStableAcrossSourceAndFactVersionsAndAnchorOrder() {
    var anchors = List.of(anchor("COMPANY", "甲公司", "f"), anchor("CONTRACT", "HT-017", "f"));
    var reverse = List.of(anchor("CONTRACT", "HT-017", "new"), anchor("COMPANY", "甲公司", "new"));
    var a =
        GraphScopeIdentity.scope("original", anchors, Map.of("f", fact("f", "甲公司合同HT-017。", "e1")));
    var b =
        GraphScopeIdentity.scope(
            "amendment", reverse, Map.of("new", fact("new", "甲公司合同HT-017。", "e2")));
    assertEquals(a.key(), b.key());
    assertEquals(
        GraphScopeIdentity.entity("HT-017", "CONTRACT", a).canonicalId(),
        GraphScopeIdentity.entity("HT-017", "CONTRACT", b).canonicalId());
    assertNotEquals(a.evidenceIds(), b.evidenceIds());
  }

  @Test
  void scopeRejectsInventedOrUnsupportedAnchorsInsteadOfTrustingModelLabels() {
    var facts = Map.of("f", fact("f", "甲公司签署合同。", "e1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> GraphScopeIdentity.scope("doc", List.of(anchor("COMPANY", "乙公司", "f")), facts));
    assertThrows(
        IllegalArgumentException.class,
        () -> GraphScopeIdentity.scope("doc", List.of(anchor("COMPANY", "甲公司", "missing")), facts));
    assertThrows(
        IllegalArgumentException.class,
        () -> GraphScopeIdentity.scope("doc", List.of(anchor("WIKI", "甲公司", "f")), facts));
  }

  @Test
  void documentNodeKeepsAuthoritativeObjectIdentitySeparateFromBusinessObject() {
    var doc = GraphScopeIdentity.document("doc-id");
    assertEquals("DOCUMENT", doc.entityType());
    assertEquals("doc-id", doc.attributes().get("object_id"));
    assertNotEquals(
        doc.canonicalId(),
        GraphScopeIdentity.entity(
                "doc-id", "CONTRACT", GraphScopeIdentity.scope("doc-id", List.of(), Map.of()))
            .canonicalId());
  }
}
