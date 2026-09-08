package cn.lgs.knowledgebase.application.query;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryEvidenceContextIdentityTest {
  private Result source(String id, String version, String content) {
    var evidence = new Evidence(id, "doc", version, id, id, Map.of(), content, id);
    return new Result(
        new Candidate(id, "doc", version, LaneType.RAW, content, 1, List.of(id), Map.of()),
        List.of(evidence));
  }

  @Test
  void explicitObjectIdentityPrioritizesItsWholeVersionBeforeGenericClauseOverlap() {
    var results = new ArrayList<Result>();
    for (int i = 0; i < 8; i++)
      results.add(
          source("noise-" + i, "other-" + i, "合同总价、质保期限、补充协议修改、付款期限、保持不变的条款都属于采购项目 PO-420。"));
    results.add(source("identity", "selected", "采购项目 PO-42。"));
    results.add(source("amount", "selected", "总价为120万元。"));
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select("采购项目 PO-42 的合同总价、质保期限、补充协议修改与保持不变条款是什么？", results, 12000);
    assertEquals(
        List.of("identity", "amount"),
        selection.sources().subList(0, 2).stream().map(s -> s.evidence().id()).toList());
    assertEquals(8, selection.sources().size());
  }

  @Test
  void sourceIdentityDoesNotTransferAcrossVersionsOfTheSameDocument() {
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "项目 ZX-7 的总价和付款条件",
                List.of(
                    source("old", "old-version", "项目 ZX-70 的总价和付款条件。"),
                    source("current-id", "current-version", "项目 ZX-7。"),
                    source("current-clause", "current-version", "付款以书面确认完成为前提。")),
                12000);
    assertEquals(
        List.of("current-id", "current-clause", "old"),
        selection.sources().stream().map(s -> s.evidence().id()).toList());
  }

  @Test
  void absentIdentifierDoesNotInventAScopeOrRemoveAvailableEvidence() {
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "项目 ZX-9 的金额", List.of(source("available", "version", "项目 ZX-7 的金额为20万元。")), 12000);
    assertEquals(1, selection.sources().size());
    assertFalse(selection.rendered().contains("ZX-9"));
  }

  @Test
  void focusedMissingClauseCannotBeCrowdedOutByRepeatedObjectIdentifiers() {
    var results = new ArrayList<Result>();
    for (int index = 0; index < 8; index++)
      results.add(source("identity-" + index, "policy-version", "制度 POLICY-620 的归档编号与登记信息。"));
    results.add(source("missing-clause", "policy-version", "原版维护期限为14天，维护期限起算须满足书面确认和登记条件。"));
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "比较制度 POLICY-620 的原版维护期限、后续变更与起算条件。",
                List.of("原版维护期限14天与起算条件的完整原文。"),
                results,
                12000);
    assertTrue(
        selection.sources().stream().anyMatch(s -> s.evidence().id().equals("missing-clause")));
    assertTrue(
        selection.sources().stream().anyMatch(s -> s.evidence().id().startsWith("identity-")));
    assertEquals(8, selection.sources().size());
    assertTrue(selection.bodyTokens() <= QueryEvidenceContext.MAX_BODY_TOKENS);
  }

  @Test
  void supplementaryFocusStillCannotBorrowAnotherVersionsIdentity() {
    var results = new ArrayList<Result>();
    for (int index = 0; index < 8; index++)
      results.add(source("other-" + index, "other-version", "制度 POLICY-6200 原版维护期限14天与起算条件的完整原文。"));
    results.add(source("correct-id", "correct-version", "制度 POLICY-620。"));
    results.add(source("correct-clause", "correct-version", "维护期限起算须满足书面确认。"));
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select("比较制度 POLICY-620 的维护期限和起算条件。", List.of("原版维护期限14天与起算条件的完整原文。"), results, 12000);
    assertEquals(
        java.util.Set.of("correct-id", "correct-clause"),
        new java.util.HashSet<>(
            selection.sources().subList(0, 2).stream().map(s -> s.evidence().id()).toList()));
  }
}
