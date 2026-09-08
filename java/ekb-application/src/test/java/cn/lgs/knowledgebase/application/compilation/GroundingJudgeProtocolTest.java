package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundingJudgeProtocolTest {
  private static final String SOURCE = "验收合格且收到合法发票后，甲方向乙方交付两台设备。";

  @Test
  void fullCandidateBindingIsExactAndDoesNotPretendToLocateIndividualRoles() {
    var checks = GroundingJudgeProtocol.bind(SOURCE, constraints(SOURCE));
    assertTrue(checks.stream().allMatch(check -> SOURCE.equals(check.claimPart())));
    assertTrue(GroundingConstraintAudit.modelSupported(SOURCE, SOURCE, true, checks));
    var proof = GroundingConstraintAudit.acceptedFullCandidate(SOURCE, checks);
    assertEquals(GroundingConstraintAudit.ClaimBinding.FULL_CANDIDATE_V2, proof.claimBinding());
    assertTrue(GroundingConstraintAudit.current(SOURCE, SOURCE, proof));
    assertTrue(GroundingJudgeProtocol.criteria().contains("不是对角色在文本中的精确局部跨度定位"));
  }

  @Test
  void reversedRolesNegationAndMissingPreconditionsRemainNegativeDespiteOverallTrue() {
    var candidates =
        List.of("验收合格且收到合法发票后，乙方向甲方交付两台设备。", "验收合格且收到合法发票后，甲方不得向乙方交付两台设备。", "甲方向乙方交付两台设备。");
    var dimensions =
        List.of(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Dimension.POLARITY,
            GroundingConstraintAudit.Dimension.CONDITIONS);
    for (int index = 0; index < candidates.size(); index++) {
      var decisions = new ArrayList<>(constraints(SOURCE));
      var dimension = dimensions.get(index);
      decisions.set(
          dimension.ordinal(),
          new GroundingJudgeProtocol.Constraint(
              dimension,
              GroundingConstraintAudit.Finding.CONTRADICTED,
              List.of(SOURCE),
              "Explicit semantic rejection fixture"));
      assertFalse(
          GroundingConstraintAudit.modelSupported(
              candidates.get(index),
              SOURCE,
              true,
              GroundingJudgeProtocol.bind(candidates.get(index), decisions)));
    }
  }

  @Test
  void noUnknownDimensionCanBeOverriddenByBindingOrOverallVerdict() {
    for (var dimension : GroundingConstraintAudit.Dimension.values()) {
      var decisions = new ArrayList<>(constraints(SOURCE));
      decisions.set(
          dimension.ordinal(),
          new GroundingJudgeProtocol.Constraint(
              dimension,
              GroundingConstraintAudit.Finding.UNKNOWN,
              List.of(),
              "Source is insufficient"));
      assertFalse(
          GroundingConstraintAudit.modelSupported(
              SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, decisions)));
    }
  }

  @Test
  void missingDuplicatedNullOrInventedEvidenceStillFailsClosed() {
    assertThrows(
        ModelOutputRejectedException.class, () -> GroundingJudgeProtocol.bind(SOURCE, null));
    var incomplete = constraints(SOURCE).subList(0, 6);
    assertThrows(
        ModelOutputRejectedException.class,
        () ->
            GroundingConstraintAudit.modelSupported(
                SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, incomplete)));
    var duplicate = new ArrayList<>(constraints(SOURCE));
    duplicate.set(6, duplicate.get(0));
    assertThrows(
        ModelOutputRejectedException.class,
        () ->
            GroundingConstraintAudit.modelSupported(
                SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, duplicate)));
    var missing = new ArrayList<>(constraints(SOURCE));
    missing.set(0, null);
    assertThrows(
        ModelOutputRejectedException.class, () -> GroundingJudgeProtocol.bind(SOURCE, missing));
    assertThrows(
        ModelOutputRejectedException.class,
        () ->
            GroundingConstraintAudit.modelSupported(
                SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, constraints("虚构证据"))));
  }

  @Test
  void notApplicableCannotEraseCoreRolesOrEvidence() {
    for (var dimension : GroundingConstraintAudit.Dimension.values()) {
      var decisions = new ArrayList<>(constraints(SOURCE));
      decisions.set(
          dimension.ordinal(),
          new GroundingJudgeProtocol.Constraint(
              dimension,
              GroundingConstraintAudit.Finding.NOT_APPLICABLE,
              List.of(SOURCE),
              "Not applicable"));
      assertThrows(
          ModelOutputRejectedException.class,
          () ->
              GroundingConstraintAudit.modelSupported(
                  SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, decisions)));
    }
    for (var dimension :
        List.of(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Dimension.ACTION,
            GroundingConstraintAudit.Dimension.POLARITY)) {
      var decisions = new ArrayList<>(constraints(SOURCE));
      decisions.set(
          dimension.ordinal(),
          new GroundingJudgeProtocol.Constraint(
              dimension,
              GroundingConstraintAudit.Finding.NOT_APPLICABLE,
              List.of(),
              "Not applicable"));
      assertThrows(
          ModelOutputRejectedException.class,
          () ->
              GroundingConstraintAudit.modelSupported(
                  SOURCE, SOURCE, true, GroundingJudgeProtocol.bind(SOURCE, decisions)));
    }
  }

  @Test
  void aVersionMustBeExplicitAndLegacyLocatedProofsRemainLegacy() {
    assertThrows(
        ModelOutputRejectedException.class, () -> GroundingJudgeProtocol.requireVersion(null));
    assertDoesNotThrow(
        () ->
            GroundingJudgeProtocol.requireVersion(
                GroundingJudgeProtocol.Version.FULL_CANDIDATE_V2));
    var old =
        GroundingConstraintAudit.accepted(
            SOURCE, GroundingConstraintAudit.exactCopyChecks(SOURCE, List.of(SOURCE)));
    assertEquals(GroundingConstraintAudit.ClaimBinding.MODEL_QUOTED_SPAN_V1, old.claimBinding());
    assertTrue(GroundingConstraintAudit.current(SOURCE, SOURCE, old));
  }

  private static List<GroundingJudgeProtocol.Constraint> constraints(String quote) {
    return Arrays.stream(GroundingConstraintAudit.Dimension.values())
        .map(
            dimension ->
                new GroundingJudgeProtocol.Constraint(
                    dimension,
                    GroundingConstraintAudit.Finding.SUPPORTED,
                    List.of(quote),
                    "Explicit semantic decision fixture, not a real model"))
        .toList();
  }
}
