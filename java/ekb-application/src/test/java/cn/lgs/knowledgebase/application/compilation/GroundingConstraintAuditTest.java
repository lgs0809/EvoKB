package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundingConstraintAuditTest {
  private static final String SOURCE = "验收合格后，甲方应向乙方交付两台设备。";

  @Test
  void completeSourceCopyHasValidatedEvidenceForEveryConstraint() {
    var checks = GroundingConstraintAudit.exactCopyChecks(SOURCE, List.of(SOURCE));
    assertTrue(GroundingConstraintAudit.supported(SOURCE, SOURCE, true, checks));
    assertFalse(GroundingConstraintAudit.supported(SOURCE, SOURCE, false, checks));
  }

  @Test
  void modelBooleanCannotOverrideAnUnresolvedRoleOrAnyOtherConstraint() {
    for (var dimension : GroundingConstraintAudit.Dimension.values()) {
      List<GroundingConstraintAudit.Check> checks = new ArrayList<>(valid());
      int index = dimension.ordinal();
      checks.set(
          index,
          new GroundingConstraintAudit.Check(
              dimension,
              GroundingConstraintAudit.Finding.UNKNOWN,
              SOURCE,
              List.of(),
              "not established"));
      assertFalse(
          GroundingConstraintAudit.supported(SOURCE, SOURCE, true, checks), dimension.name());
    }
  }

  @Test
  void cacheRequiresCompleteTypedCurrentAuditRatherThanAReasonOrStatus() {
    var valid = GroundingConstraintAudit.accepted(SOURCE, valid());
    assertTrue(GroundingConstraintAudit.current(SOURCE, SOURCE, valid));
    assertFalse(GroundingConstraintAudit.current(SOURCE, SOURCE, null));
    assertFalse(
        GroundingConstraintAudit.current(
            SOURCE,
            SOURCE,
            new GroundingConstraintAudit.Audit("legacy-policy", SOURCE, true, valid())));
    assertFalse(
        GroundingConstraintAudit.current(
            SOURCE,
            SOURCE,
            new GroundingConstraintAudit.Audit(
                GroundingConstraintAudit.POLICY_VERSION, SOURCE, true, valid().subList(0, 6))));
    assertFalse(GroundingConstraintAudit.current(SOURCE + "并另有未说明条件。", SOURCE, valid));
    assertFalse(GroundingConstraintAudit.current(SOURCE, "不同来源，无法找到原引文。", valid));
    assertFalse(
        GroundingConstraintAudit.current(
            SOURCE,
            SOURCE,
            new GroundingConstraintAudit.Audit(
                GroundingConstraintAudit.POLICY_VERSION, SOURCE, false, valid())));
  }

  @Test
  void contradictionCannotBeOverriddenByTheFinalVerdict() {
    var checks = new ArrayList<>(valid());
    checks.set(
        0,
        new GroundingConstraintAudit.Check(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Finding.CONTRADICTED,
            SOURCE,
            List.of(SOURCE),
            "roles differ"));
    assertFalse(GroundingConstraintAudit.supported(SOURCE, SOURCE, true, checks));
  }

  @Test
  void missingDuplicatedNullOrUnlocatedChecksFailClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, null));
    assertThrows(
        IllegalStateException.class,
        () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, valid().subList(0, 6)));
    var duplicate = new ArrayList<>(valid());
    duplicate.set(6, duplicate.get(0));
    assertThrows(
        IllegalStateException.class,
        () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, duplicate));
    var nullCheck = new ArrayList<>(valid());
    nullCheck.set(1, null);
    assertThrows(
        IllegalStateException.class,
        () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, nullCheck));
    var invented = new ArrayList<>(valid());
    invented.set(
        0,
        new GroundingConstraintAudit.Check(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Finding.SUPPORTED,
            SOURCE,
            List.of("不存在的第三方负责交付。"),
            "invented"));
    assertThrows(
        IllegalStateException.class,
        () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, invented));
  }

  @Test
  void extractiveFallbackCannotApproveSubstringsRewritesOrLostConditions() {
    for (String changed : List.of("甲方应向乙方交付两台设备。", "验收合格后，乙方应向甲方交付两台设备。", "甲方不得交付设备。"))
      assertTrue(GroundingConstraintAudit.exactCopyChecks(changed, List.of(SOURCE)).isEmpty());
  }

  @Test
  void coreRolesCannotBeMarkedNotApplicable() {
    for (var dimension :
        List.of(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Dimension.ACTION,
            GroundingConstraintAudit.Dimension.POLARITY)) {
      var checks = new ArrayList<>(valid());
      checks.set(
          dimension.ordinal(),
          new GroundingConstraintAudit.Check(
              dimension,
              GroundingConstraintAudit.Finding.NOT_APPLICABLE,
              "",
              List.of(),
              "skipped"));
      assertThrows(
          IllegalStateException.class,
          () -> GroundingConstraintAudit.supported(SOURCE, SOURCE, true, checks));
    }
  }

  @Test
  void malformedFreshResponseIsRetryableWhileTheSameCachedProofStaysUnusable() {
    // Synthetic malformed span; the actual production receipt retains only its error, not
    // the discarded model response. Do not represent this fixture as that unseen response.
    var malformed = new ArrayList<>(valid());
    malformed.set(
        0,
        new GroundingConstraintAudit.Check(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Finding.SUPPORTED,
            "span absent from the candidate",
            List.of(SOURCE),
            "synthetic response shape counterexample"));
    var rejected =
        assertThrows(
            ModelOutputRejectedException.class,
            () -> GroundingConstraintAudit.modelSupported(SOURCE, SOURCE, true, malformed));
    assertEquals(
        "grounding constraint audit: constraint claim span is not in candidate SUBJECT",
        rejected.getMessage());
    assertInstanceOf(IllegalStateException.class, rejected.getCause());
    assertFalse(
        GroundingConstraintAudit.current(
            SOURCE, SOURCE, GroundingConstraintAudit.accepted(SOURCE, malformed)));
  }

  @Test
  void freshContradictionOrUnknownIsAValidNegativeDecisionRatherThanMalformedOutput() {
    assertFalse(GroundingConstraintAudit.modelSupported(SOURCE, SOURCE, false, valid()));
    for (var finding :
        List.of(
            GroundingConstraintAudit.Finding.CONTRADICTED,
            GroundingConstraintAudit.Finding.UNKNOWN)) {
      var checks = new ArrayList<>(valid());
      checks.set(
          0,
          new GroundingConstraintAudit.Check(
              GroundingConstraintAudit.Dimension.SUBJECT,
              finding,
              SOURCE,
              finding == GroundingConstraintAudit.Finding.UNKNOWN ? List.of() : List.of(SOURCE),
              "negative semantic decision"));
      assertFalse(GroundingConstraintAudit.modelSupported(SOURCE, SOURCE, true, checks));
    }
  }

  @Test
  void sharedModelBatchRejectsNullUnknownDuplicateAndMissingResponseIdentities() {
    var expected = List.of("first", "second");
    assertEquals(
        expected,
        new ArrayList<>(
            GroundingConstraintAudit.modelDecisions(
                    expected, expected, value -> value, "synthetic grounding batch")
                .keySet()));
    var malformed = new ArrayList<List<String>>();
    malformed.add(null);
    malformed.add(java.util.Arrays.asList("first", null));
    malformed.add(List.of("first", "unexpected"));
    malformed.add(List.of("first", "first"));
    malformed.add(List.of("first"));
    for (var records : malformed)
      assertThrows(
          ModelOutputRejectedException.class,
          () ->
              GroundingConstraintAudit.modelDecisions(
                  records, expected, value -> value, "synthetic grounding batch"));
    assertThrows(
        ModelOutputRejectedException.class,
        () -> GroundingConstraintAudit.requireModelDecision(null, "synthetic individual judge"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GroundingConstraintAudit.modelDecisions(
                expected, List.of("first", "first"), value -> value, "invalid caller input"));
  }

  private static List<GroundingConstraintAudit.Check> valid() {
    return GroundingConstraintAudit.exactCopyChecks(SOURCE, List.of(SOURCE));
  }
}
