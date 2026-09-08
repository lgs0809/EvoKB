package cn.lgs.knowledgebase.application.query;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryEvidenceContextPassageTest {
  @Test
  void followupKeepsPrerequisitesLinkedByAReferencePairInOriginalEvidence() {
    var results = new ArrayList<Result>();
    results.add(
        passage(
            "link",
            LaneType.RAW,
            List.of(evidence("link", "link", "任务 TASK-71 对应采购记录 ORDER-83，已完成交付确认。"))));
    results.add(
        passage(
            "receipt",
            LaneType.RAW,
            List.of(evidence("receipt", "receipt", "采购记录 ORDER-83 的支付凭证日期为2029-01-05。"))));
    results.add(
        passage(
            "amendment",
            LaneType.RAW,
            List.of(evidence("amendment", "amendment", "采购记录 ORDER-83 的变更后期限为12日。"))));
    for (int i = 0; i < 12; i++) {
      String id = "detail-" + i;
      results.add(
          passage(
              id,
              LaneType.RAW,
              List.of(evidence(id, "link", "任务 TASK-71 的交付确认流程、支付凭证保管流程、变更后期限登记流程，第" + i + "段。"))));
    }
    String question = "任务 TASK-71 的交付确认情况、支付凭证日期和变更后期限各是什么？";
    for (String focus : List.of("采购记录 ORDER-83 的支付凭证日期", "采购记录 ORDER-83 的变更后期限")) {
      var selection =
          QueryEvidenceContext.conservativeStandalone()
              .select(question, List.of(focus), results, 24000);
      var ids = selection.sources().stream().map(item -> item.evidence().id()).toList();
      assertTrue(ids.containsAll(List.of("receipt", "amendment")), ids.toString());
      assertTrue(selection.passages().size() <= QueryEvidenceContext.MAX_PASSAGES);
      selection.snapshot().validate();
    }
  }

  @Test
  void aFollowupCannotEvictAllOriginalQuestionPrerequisitesFromOtherSourceVersions() {
    var results = new ArrayList<Result>();
    results.add(
        passage(
            "acceptance",
            LaneType.RAW,
            List.of(evidence("acceptance", "acceptance", "PJ-X9验收日期2029-01-03。"))));
    results.add(
        passage(
            "invoice",
            LaneType.RAW,
            List.of(evidence("invoice", "finance", "PJ-X9发票登记日期2029-01-05，未记录到账。"))));
    results.add(
        passage(
            "amendment",
            LaneType.RAW,
            List.of(evidence("amendment", "amendment", "PJ-X9修订后付款期限12日。"))));
    results.add(
        passage(
            "contract",
            LaneType.RAW,
            List.of(evidence("contract", "contract", "PJ-X9付款前提是完成验收并取得发票。"))));
    for (int i = 0; i < 12; i++)
      results.add(
          passage(
              "focus-" + i,
              LaneType.RAW,
              List.of(evidence("focus-" + i, "amendment", "PJ-X9工作日自然日起算口径补充说明，第" + i + "段。"))));
    String question = "PJ-X9验收日期、发票登记和修订后的付款期限，是否已到账？";
    var selection =
        QueryEvidenceContext.conservativeStandalone()
            .select(question, List.of("PJ-X9工作日自然日起算口径补充说明"), results, 24000);
    var ids = selection.sources().stream().map(x -> x.evidence().id()).toList();
    assertTrue(ids.containsAll(List.of("acceptance", "invoice", "amendment", "contract")));
    assertTrue(ids.stream().anyMatch(id -> id.startsWith("focus-")));
    assertTrue(selection.passages().size() <= 8);
    assertTrue(selection.bodyTokens() <= 6000);
    selection.snapshot().validate();
    // Revoked/removed sources are not pinned across selections.
    results.removeIf(r -> r.evidence().stream().anyMatch(e -> e.id().equals("invoice")));
    assertFalse(
        QueryEvidenceContext.conservativeStandalone()
            .select(question, List.of("PJ-X9工作日自然日起算口径补充说明"), results, 24000)
            .sources()
            .stream()
            .anyMatch(x -> x.evidence().id().equals("invoice")));
  }

  @Test
  void rawLocationRendersCanonicalKeysIncludingNestedCoordinates() {
    var raw =
        new Evidence(
            "source",
            "doc",
            "version",
            "block",
            "original",
            Map.of("z", "last", "a", "first", "coordinates", List.of(Map.of("y", 2, "x", 1))),
            "Unchanged original text.",
            "hash");
    String rendered =
        QueryEvidenceContext.renderSources(List.of(new QueryEvidenceContext.Source(1, "raw", raw)));
    assertTrue(rendered.contains("Raw location: {a=first, coordinates=[{x=1, y=2}], z=last}\n"));
    assertTrue(rendered.contains("Raw evidence: Unchanged original text.\n"));
    assertEquals(
        raw.location(),
        Map.of("z", "last", "a", "first", "coordinates", List.of(Map.of("y", 2, "x", 1))));
  }

  private Evidence evidence(String id, String version, String text) {
    return new Evidence(
        id, "doc-" + version, version, id, "original-" + version, Map.of(), text, id);
  }

  private Result passage(String id, LaneType lane, List<Evidence> evidence) {
    return new Result(
        new Candidate(
            id,
            evidence.get(0).documentId(),
            evidence.get(0).versionId(),
            lane,
            "INDEX_PROJECTION_MUST_NOT_BECOME_RAW",
            1,
            evidence.stream().map(Evidence::id).toList(),
            Map.of()),
        evidence);
  }

  @Test
  void twoWholeRetrievedChunksKeepTheirTwelveOriginalParagraphs() {
    var results = new ArrayList<Result>();
    for (int file = 0; file < 2; file++) {
      var blocks = new ArrayList<Evidence>();
      for (int paragraph = 0; paragraph < 6; paragraph++)
        blocks.add(
            evidence(
                "e-" + file + "-" + paragraph,
                "v-" + file,
                "原文段落 " + file + "/" + paragraph + "：身份、范围、条件和条款分别保存。"));
      results.add(passage("chunk-" + file, LaneType.RAW, blocks));
    }
    var selected =
        QueryEvidenceContext.conservativeStandalone().select("比较两份资料的条款。", results, 24000);
    assertEquals(12, selected.sources().size());
    assertTrue(selected.rendered().contains("e-1-5"));
    assertFalse(selected.rendered().contains("INDEX_PROJECTION_MUST_NOT_BECOME_RAW"));
    assertTrue(selected.bodyTokens() <= QueryEvidenceContext.MAX_BODY_TOKENS);
    assertEquals(2, selected.passages().size());
    selected.snapshot().validate();
    assertEquals(12, selected.snapshot().sources().size());
    assertFalse(selected.snapshot().toString().contains("身份、范围、条件和条款分别保存"));
  }

  @Test
  void eightIsAWholeChunkBudgetRatherThanAnEightParagraphBudget() {
    var results = new ArrayList<Result>();
    for (int chunk = 0; chunk < 9; chunk++)
      results.add(
          passage(
              "chunk-" + chunk,
              LaneType.RAW,
              List.of(
                  evidence("a-" + chunk, "v", "第一条维护原文。"),
                  evidence("b-" + chunk, "v", "第二条维护原文。"))));
    var selected = QueryEvidenceContext.conservativeStandalone().select("维护原文。", results, 24000);
    assertEquals(16, selected.sources().size());
    assertEquals(8, selected.passages().size());
    assertFalse(selected.sources().stream().anyMatch(s -> s.evidence().id().equals("a-8")));
  }

  @Test
  void oversizedWholeChunkCannotBePartiallyPresentedAsItsCompleteContext() {
    var large =
        passage(
            "oversized",
            LaneType.RAW,
            List.of(
                evidence("large-a", "v", "a".repeat(2500)),
                evidence("large-b", "v", "b".repeat(2500)),
                evidence("large-c", "v", "c".repeat(2500))));
    var small =
        passage("small", LaneType.RAW, List.of(evidence("small-source", "other", "完整且可放入预算的原文。")));
    var selected =
        QueryEvidenceContext.conservativeStandalone()
            .select("说明现有资料。", List.of(large, small), 24000);
    assertEquals(
        List.of("small-source"), selected.sources().stream().map(s -> s.evidence().id()).toList());
  }

  @Test
  void rawChunkCannotMergeDifferentImmutableSourceVersions() {
    var malformed =
        passage(
            "mixed",
            LaneType.RAW,
            List.of(evidence("one", "v1", "原文一。"), evidence("two", "v2", "原文二。")));
    assertThrows(
        IllegalStateException.class,
        () ->
            QueryEvidenceContext.conservativeStandalone().select("原文", List.of(malformed), 24000));
  }

  @Test
  void derivedCandidateWithoutRawChunkMembershipDoesNotBecomeOneLargePassage() {
    var blocks = new ArrayList<Evidence>();
    for (int index = 0; index < 12; index++)
      blocks.add(evidence("derived-" + index, "v", "可核验原文 " + index));
    var selected =
        QueryEvidenceContext.conservativeStandalone()
            .select("原文。", List.of(passage("wiki", LaneType.WIKI, blocks)), 24000);
    assertEquals(8, selected.sources().size());
  }

  @Test
  void overlappingChunksRetainAllRawBindingsWithoutDuplicateSourceNumbers() {
    var one = evidence("one", "v", "第一条原文。");
    var two = evidence("two", "v", "第二条原文。");
    var three = evidence("three", "v", "第三条原文。");
    var selected =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "原文。",
                List.of(
                    passage("first", LaneType.RAW, List.of(one, two)),
                    passage("second", LaneType.RAW, List.of(two, three))),
                24000);
    assertEquals(
        List.of("one", "two", "three"),
        selected.sources().stream().map(s -> s.evidence().id()).toList());
    assertEquals(
        List.of(1, 2, 3),
        selected.sources().stream().map(QueryEvidenceContext.Source::index).toList());
    assertEquals(2, selected.passages().size());
    assertEquals(List.of("two", "three"), selected.passages().get(1).evidenceIds());
    selected.snapshot().validate();
  }

  @Test
  void derivedEvidenceReusesOnlyAnActuallyRetrievedRawChunksMembership() {
    var one = evidence("one", "v", "第一条原文。");
    var two = evidence("two", "v", "第二条原文。");
    var selected =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "原文。",
                List.of(
                    passage("wiki", LaneType.WIKI, List.of(one)),
                    passage("raw", LaneType.RAW, List.of(one, two))),
                24000);
    assertEquals(
        List.of("one", "two"), selected.sources().stream().map(s -> s.evidence().id()).toList());
    assertEquals(1, selected.passages().size());
    assertEquals("chunk:raw", selected.passages().get(0).retrievalId());
    assertEquals("RAW_CHUNK", selected.passages().get(0).kind());
  }

  @Test
  void contextReceiptRejectsUnboundSourcesAndCrossVersionPassages() {
    var selected =
        QueryEvidenceContext.conservativeStandalone()
            .select(
                "原文。",
                List.of(
                    passage("first", LaneType.RAW, List.of(evidence("one", "v1", "第一条。"))),
                    passage("second", LaneType.RAW, List.of(evidence("two", "v2", "第二条。")))),
                24000);
    var snapshot = selected.snapshot();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new QueryEvidenceContext.Snapshot(
                    snapshot.policy(),
                    snapshot.contextSha256(),
                    snapshot.tokenizerIdentity(),
                    snapshot.bodyTokens(),
                    snapshot.maxCharacters(),
                    snapshot.renderedCharacters(),
                    List.of(
                        new QueryEvidenceContext.Passage(
                            "bad", "RAW_CHUNK", List.of("one", "two"))),
                    snapshot.sources())
                .validate());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new QueryEvidenceContext.Snapshot(
                    snapshot.policy(),
                    snapshot.contextSha256(),
                    snapshot.tokenizerIdentity(),
                    snapshot.bodyTokens(),
                    snapshot.maxCharacters(),
                    snapshot.renderedCharacters(),
                    List.of(
                        new QueryEvidenceContext.Passage(
                            "bad", "RAW_FRAGMENT", List.of("missing"))),
                    snapshot.sources())
                .validate());
  }
}
