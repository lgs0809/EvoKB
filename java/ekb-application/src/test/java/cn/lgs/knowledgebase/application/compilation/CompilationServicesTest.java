package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompilationServicesTest {
  @Test
  void summaryOnlyActivatesAfterIndependentGroundingAndCoveragePass() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    // Coverage is evaluated once per exact prompt fragment after the transport-boundary split.
    for (int i = 0; i < 4; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var service = new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived);
    var result = service.compileVersion("v1");

    assertEquals(1, result.size());
    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(1, f.derived.summaryIndexes);
    assertTrue(f.repo.summaries.containsKey(result.get(0).summary().id()));
  }

  @Test
  void summaryRetryReusesActiveUnitCheckpointWithoutCallingModelAgain() {
    Fixture f = new Fixture();
    f.repo.persistedDocumentUnits =
        List.of(new DocumentUnit("u1", "v1", 1, "", "single", "合同/协议", 0, 1));
    f.repo.summaries.put("checkpoint", activeSummary("checkpoint"));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertEquals(1, result.size());
    assertEquals("checkpoint", result.get(0).summary().id());
    assertTrue(
        f.model.structuredPrompts.stream().noneMatch(prompt -> prompt.startsWith("SUMMARY\n")));
    assertEquals(0, f.derived.summaryIndexes);
  }

  @Test
  void oversizedRawBlockIsSplitOnlyForModelPromptsAndEvidenceRemainsWhole() {
    Fixture f = new Fixture();
    StringBuilder raw = new StringBuilder("<table>");
    for (int i = 0; i < 180; i++)
      raw.append("<tr><td>row-").append(i).append("</td><td>这是完整的表格行内容，用于验证提示分片不会丢失原文。</td></tr>");
    raw.append("</table>");
    String large = raw.toString();
    RawBlock largeBlock = block("large", 0, large);
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "大表格文档",
            "",
            new Metadata("法务", "公告", List.of(), "甲公司", null, "公告"),
            List.of(largeBlock),
            Map.of(
                "large",
                new Evidence(
                    "ev-large",
                    "d1",
                    "v1",
                    "large",
                    "original",
                    Map.of(),
                    large,
                    largeBlock.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("公告", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "表格事实", "row-0 记录存在。", List.of("large")))));
    // The source-preserving prompt splitter may produce several fragments for this large block.
    // Queue enough empty extraction responses for every fragment; only the first response carries
    // the scripted fact used by the assertion below.
    for (int i = 0; i < 12; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY, new SummaryCompilationService.FactProposalSet(List.of()));
    f.model.enqueueType(
        SummaryCompilationService.SupportDecision.class,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    for (int i = 0; i < 12; i++)
      f.model.enqueueType(
          SummaryCompilationService.SupportDecision.class,
          new SummaryCompilationService.SupportDecision(false, "other fragment"));
    for (int i = 0; i < 24; i++)
      f.model.enqueueType(
          SummaryCompilationService.CoverageDecision.class,
          new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(large, f.repo.source.blocks().get(0).content());
    for (int i = 0; i < 180; i++) {
      int row = i;
      assertTrue(
          f.model.structuredPrompts.stream().anyMatch(prompt -> prompt.contains("row-" + row)),
          "row missing from model prompts: " + row);
    }
  }

  @Test
  void summaryUsesPersistedUnitIdWhenModelClassificationChangesUnitFields() {
    Fixture f = new Fixture();
    f.repo.persistedDocumentUnits =
        List.of(new DocumentUnit("legacy-unit", "v1", 1, "旧标题", "single", "合同/协议", 0, 1));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    // The extraction retry can split the oversized block more than once; queue one coverage
    // response for every exact source-preserving fragment produced by the recursive splitter.
    for (int i = 0; i < 8; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertEquals("legacy-unit", result.get(0).unit().id());
    assertEquals("legacy-unit", result.get(0).summary().unitId());
  }

  @Test
  void summaryFallsBackToDeterministicUnitsOnlyForTransientClassificationFailure() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new IllegalStateException("chat request failed for CLASSIFY_UNIT: HTTP 502"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertEquals(1, f.repo.documentUnits.size());
    assertEquals("single", f.repo.documentUnits.get(0).structureType());
    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryClassificationEmptyResponseFallsBackWithoutDroppingRawBlocks() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new IllegalStateException("chat response missing content for CLASSIFY_UNIT"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(1, f.repo.documentUnits.size());
    assertEquals(0, result.get(0).unit().startBlockNo());
    assertEquals(1, result.get(0).unit().endBlockNo());
  }

  @Test
  void summaryClassificationDecodeFailureRemainsFailClosed() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new IllegalStateException("cannot decode structured model output for CLASSIFY_UNIT"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
                    .compileVersion("v1"));

    assertTrue(error.getMessage().contains("cannot decode structured"));
    assertTrue(f.repo.documentUnits.isEmpty());
    assertTrue(f.repo.summaries.isEmpty());
  }

  @Test
  void transientClassificationFallbackSplitsOnRealHeadingBoundaries() {
    Fixture f = new Fixture();
    List<RawBlock> blocks =
        List.of(
            block("b1", 1, "甲".repeat(450), List.of("第一章")),
            block("b2", 2, "乙".repeat(450), List.of("第一章")),
            block("b3", 3, "丙".repeat(450), List.of("第二章")),
            block("b4", 4, "丁".repeat(450), List.of("第二章")));
    Map<String, Evidence> evidence = new LinkedHashMap<>();
    for (RawBlock block : blocks) {
      evidence.put(
          block.id(),
          new Evidence(
              "ev-" + block.id(),
              "d1",
              "v1",
              block.id(),
              "original",
              Map.of(),
              block.content(),
              block.contentHash()));
    }
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "制度文件",
            "公司治理制度",
            new Metadata("法务", "制度", List.of(), "甲公司", null, "公司治理制度"),
            blocks,
            evidence);
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new IllegalStateException("chat request failed for CLASSIFY_UNIT: HTTP 503"));
    f.model.enqueue(Ports.ModelTask.SUMMARY, new IllegalStateException("stop after unit planning"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
                    .compileVersion("v1"));

    assertEquals("stop after unit planning", error.getMessage());
    assertEquals(2, f.repo.documentUnits.size());
    assertEquals("第一章", f.repo.documentUnits.get(0).title());
    assertEquals(1, f.repo.documentUnits.get(0).startBlockNo());
    assertEquals(2, f.repo.documentUnits.get(0).endBlockNo());
    assertEquals("第二章", f.repo.documentUnits.get(1).title());
    assertEquals(3, f.repo.documentUnits.get(1).startBlockNo());
    assertEquals(4, f.repo.documentUnits.get(1).endBlockNo());
  }

  @Test
  void bundleClassificationAcceptsParserBlockNumberGaps() {
    Fixture f = new Fixture();
    RawBlock first = block("b1", 0, "甲公司与乙公司签署协议。");
    RawBlock second = block("b2", 2, "协议期限为一年。");
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(first, second),
            Map.of(
                "b1",
                new Evidence(
                    "ev1",
                    "d1",
                    "v1",
                    "b1",
                    "original",
                    Map.of(),
                    first.content(),
                    first.contentHash()),
                "b2",
                new Evidence(
                    "ev2",
                    "d1",
                    "v1",
                    "b2",
                    "original",
                    Map.of(),
                    second.content(),
                    second.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification(
            "合同/协议",
            "bundle",
            List.of(new SummaryCompilationService.UnitProposal("协议", "合同/协议", 0, 2))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "协议事实", "甲公司与乙公司签署协议，期限为一年。", List.of("b1", "b2")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertEquals(1, result.size());
    assertEquals(0, result.get(0).unit().startBlockNo());
    assertEquals(2, result.get(0).unit().endBlockNo());
    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryBatchesGroundingForMultipleFacts() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("合同主体", "甲公司与乙公司签署协议。", List.of("b1")),
                new SummaryCompilationService.FactProposal("期限", "协议期限为一年。", List.of("b2")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecisionSet(
            List.of(
                new SummaryCompilationService.SupportDecisionItem("C1", true, "direct"),
                new SummaryCompilationService.SupportDecisionItem("C2", true, "direct"))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(2, result.get(0).summary().facts().size());
    assertEquals(1, f.derived.summaryIndexes);
  }

  @Test
  void summaryGroundingFallsBackToIndividualRequestsWhenBatchHasNoContent() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("事实一", "甲公司与乙公司签署协议。", List.of("b1")),
                new SummaryCompilationService.FactProposal("事实二", "协议期限为一年。", List.of("b2")),
                new SummaryCompilationService.FactProposal("事实三", "协议适用中国法律。", List.of("b1")),
                new SummaryCompilationService.FactProposal("事实四", "协议以书面形式订立。", List.of("b2")),
                new SummaryCompilationService.FactProposal("事实五", "协议争议提交仲裁。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new IllegalStateException(
            "summary grounding judge failed for 4 candidates: chat response missing content for SUMMARY_JUDGE"));
    for (int i = 0; i < 4; i++) {
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE,
          new SummaryCompilationService.SupportDecision(true, "direct"));
    }
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecisionSet(
            List.of(new SummaryCompilationService.SupportDecisionItem("C5", true, "direct"))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(5, result.get(0).summary().facts().size());
  }

  @Test
  void summaryExtractionSplitsEmptyProviderResponsesAtRawBlockBoundaries() {
    Fixture f = new Fixture();
    RawBlock first = block("b1", 1, "甲公司与乙公司签署协议。");
    RawBlock second = block("b2", 2, "协议期限为一年。");
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(first, second),
            Map.of(
                "b1",
                new Evidence(
                    "ev1",
                    "d1",
                    "v1",
                    "b1",
                    "original",
                    Map.of(),
                    first.content(),
                    first.contentHash()),
                "b2",
                new Evidence(
                    "ev2",
                    "d1",
                    "v1",
                    "b2",
                    "original",
                    Map.of(),
                    second.content(),
                    second.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new IllegalStateException("chat response missing content for SUMMARY"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(new SummaryCompilationService.FactProposal("期限", "协议期限为一年。", List.of("b2")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecisionSet(
            List.of(
                new SummaryCompilationService.SupportDecisionItem("C1", true, "direct"),
                new SummaryCompilationService.SupportDecisionItem("C2", true, "direct"))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(2, result.get(0).summary().facts().size());
  }

  @Test
  void summaryDropsUngroundedProposalsButKeepsEvidenceOnlyFacts() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("有证据", "甲公司与乙公司签署协议。", List.of("b1")),
                new SummaryCompilationService.FactProposal("无证据", "乙公司已付款。", List.of("b2")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecisionSet(
            List.of(
                new SummaryCompilationService.SupportDecisionItem("C1", true, "direct"),
                new SummaryCompilationService.SupportDecisionItem("C2", false, "not stated"))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(1, result.get(0).summary().facts().size());
    assertEquals("有证据", result.get(0).summary().facts().get(0).section());
  }

  @Test
  void summaryAcceptsProductGovernanceDocumentType() {
    Fixture f = new Fixture();
    VersionSource source = f.repo.source;
    f.repo.source =
        new VersionSource(
            source.documentId(),
            source.versionId(),
            "万科董事会议事规则",
            "公司治理制度",
            new Metadata("投资者关系", "公开披露", List.of("万科"), "万科企业股份有限公司", null, "公司治理制度"),
            source.blocks(),
            source.evidenceByBlock());
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "适用范围", "本规则适用于董事会运作。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertEquals("公司治理制度", result.get(0).unit().documentType());
    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryFailsClosedWhenCoverageStillHasGroundedMissingFactAfterRepair() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    var missing = new SummaryCompilationService.MissingFact("协议期限为一年", "期限与时间节点", List.of("b2"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.CoverageDecision(List.of(missing)));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("期限与时间节点", "协议期限为一年。", List.of("b2")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.CoverageDecision(List.of(missing)));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertFalse(result.get(0).summary().activeAndGrounded());
    assertEquals("FAIL", result.get(0).summary().coverageStatus());
    assertEquals(0, f.derived.summaryIndexes);
  }

  @Test
  void summaryCoverageIgnoresParserOnlyPageNumberBlocksButKeepsSemanticBlocks() {
    Fixture f = new Fixture();
    RawBlock semantic = f.repo.source.blocks().get(0);
    RawBlock pageNumber =
        new RawBlock(
            "page",
            "v1",
            2,
            "page_number",
            "2",
            "2",
            List.of(),
            2,
            2,
            List.of(),
            List.of(),
            List.of("page"),
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256("2"),
            1);
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(semantic, pageNumber),
            Map.of(
                "b1",
                f.repo.source.evidenceByBlock().get("b1"),
                "page",
                new Evidence(
                    "ev-page",
                    "d1",
                    "v1",
                    "page",
                    "original",
                    Map.of(),
                    "2",
                    cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256("2"))));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryCoverageDoesNotReopenBlocksAlreadySupportedByPassFacts() {
    Fixture f = new Fixture();
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.CoverageDecision(
            List.of(
                new SummaryCompilationService.MissingFact("甲公司与乙公司签署协议。", "合同主体", List.of("b1")))));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryCoverageSplitsOnlyEmptyProviderResponsesAndKeepsFailClosedSemantics() {
    Fixture f = new Fixture();
    RawBlock first = block("b1", 1, "甲公司与乙公司签署协议。");
    RawBlock second = block("b2", 2, "协议期限为一年。");
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(first, second),
            Map.of(
                "b1",
                new Evidence(
                    "ev1",
                    "d1",
                    "v1",
                    "b1",
                    "original",
                    Map.of(),
                    first.content(),
                    first.contentHash()),
                "b2",
                new Evidence(
                    "ev2",
                    "d1",
                    "v1",
                    "b2",
                    "original",
                    Map.of(),
                    second.content(),
                    second.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal(
                    "合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new IllegalStateException(
            "summary coverage judge failed for 2 blocks: chat response missing content for SUMMARY_JUDGE"));
    // Splitting at newline boundaries can yield three fragments around the midpoint; queue
    // enough responses for every exact, source-preserving fragment produced by the retry path.
    for (int i = 0; i < 4; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
  }

  @Test
  void summaryCoverageSplitsASecondTimeWhenOneOversizedFragmentTimesOut() {
    Fixture f = new Fixture();
    String large =
        // Keep the fixture above the production 30k prompt boundary so the test exercises the
        // exact source-preserving split path (the persisted RawBlock remains one complete value).
        java.util.stream.IntStream.range(0, 1400)
            .mapToObj(i -> "row-" + i + "=" + "x".repeat(20))
            .collect(java.util.stream.Collectors.joining("\n"));
    RawBlock block = block("large", 1, large);
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(block),
            Map.of(
                "large",
                new Evidence(
                    "ev-large",
                    "d1",
                    "v1",
                    "large",
                    "original",
                    Map.of(),
                    large,
                    block.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("公告", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("表格事实", "row-0", List.of("large")))));
    // MODEL_PROMPT_FRAGMENT_CHARACTERS partitions this RawBlock before extraction; queue enough
    // empty responses for all prompt-only fragments after the first fact response so the test
    // remains source-preserving when the transport boundary changes.
    for (int i = 0; i < 12; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY, new SummaryCompilationService.FactProposalSet(List.of()));
    // Grounding inspects each complete evidence fragment independently. Typed scripting keeps
    // these responses separate from the subsequent CoverageDecision queue even when a very large
    // evidence block is split into many prompt-only fragments.
    for (int i = 0; i < 16; i++)
      f.model.enqueueType(
          SummaryCompilationService.SupportDecision.class,
          new SummaryCompilationService.SupportDecision(true, "direct"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY_JUDGE,
        new IllegalStateException(
            "summary coverage judge failed for 1 blocks: chat request failed for SUMMARY_JUDGE: HttpTimeoutException: request timed out"));
    // Splitting at newline boundaries can yield three fragments around the midpoint; queue
    // enough responses for every exact, source-preserving fragment produced by the retry path.
    for (int i = 0; i < 6; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(large, f.repo.source.blocks().get(0).content());
    assertTrue(
        f.model.structuredPrompts.stream().anyMatch(prompt -> prompt.contains("row-0")),
        "first split fragment was not sent to the coverage judge");
    assertTrue(
        f.model.structuredPrompts.stream().anyMatch(prompt -> prompt.contains("row-1399")),
        "second split fragment was not sent to the coverage judge");
  }

  @Test
  void summaryExtractionSplitsASecondTimeWhenOneOversizedFragmentTimesOut() {
    Fixture f = new Fixture();
    String large =
        // Keep the fixture above the production 30k prompt boundary so the test exercises the
        // exact source-preserving split path (the persisted RawBlock remains one complete value).
        java.util.stream.IntStream.range(0, 1400)
            .mapToObj(i -> "row-" + i + "=" + "x".repeat(20))
            .collect(java.util.stream.Collectors.joining("\n"));
    RawBlock block = block("large", 1, large);
    f.repo.source =
        new VersionSource(
            "d1",
            "v1",
            "合同A",
            "",
            f.repo.source.metadata(),
            List.of(block),
            Map.of(
                "large",
                new Evidence(
                    "ev-large",
                    "d1",
                    "v1",
                    "large",
                    "original",
                    Map.of(),
                    large,
                    block.contentHash())));
    f.model.enqueue(
        Ports.ModelTask.CLASSIFY_UNIT,
        new SummaryCompilationService.Classification("公告", "single", List.of()));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new IllegalStateException(
            "chat request failed for SUMMARY: HttpTimeoutException: request timed out"));
    f.model.enqueue(
        Ports.ModelTask.SUMMARY,
        new SummaryCompilationService.FactProposalSet(
            List.of(
                new SummaryCompilationService.FactProposal("表格事实", "row-0", List.of("large")))));
    // The exact-boundary splitter may produce more than two fragments; extra scripted outputs are
    // harmless and ensure the test covers every source-preserving retry fragment.
    for (int i = 0; i < 6; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY, new SummaryCompilationService.FactProposalSet(List.of()));
    for (int i = 0; i < 16; i++)
      f.model.enqueueType(
          SummaryCompilationService.SupportDecision.class,
          new SummaryCompilationService.SupportDecision(true, "direct"));
    // Coverage is evaluated once per exact prompt fragment after recursive splitting.
    for (int i = 0; i < 8; i++)
      f.model.enqueue(
          Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

    var result =
        new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compileVersion("v1");

    assertTrue(result.get(0).summary().activeAndGrounded());
    assertEquals(large, f.repo.source.blocks().get(0).content());
    assertTrue(
        f.model.structuredPrompts.stream().anyMatch(prompt -> prompt.contains("row-0")),
        "first extraction split fragment was not sent to the model");
    assertTrue(
        f.model.structuredPrompts.stream().anyMatch(prompt -> prompt.contains("row-1399")),
        "last extraction split fragment was not sent to the model");
  }

  @Test
  void graphRequiresDecisionForEveryFactAndEveryEligibleFactMustBeCovered() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.RELATION_EXTRACT,
        new GraphCompilationService.Extraction(
            List.of(),
            List.of(),
            List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1"));
    assertTrue(error.getMessage().contains("uncovered eligible"));
  }

  @Test
  void graphPersistsCanonicalEntitiesAndRawEvidenceBackedRelation() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.RELATION_EXTRACT,
        new GraphCompilationService.Extraction(
            List.of(),
            List.of(
                new GraphCompilationService.ProposedRelation(
                    "张三",
                    "PERSON",
                    "DIRECTOR_OF",
                    "甲公司",
                    "COMPANY",
                    List.of("f1"),
                    "2026-01-01",
                    "",
                    0.95)),
            List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

    var result = new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1");

    assertEquals(2, result.entities().size());
    assertEquals(1, result.relations().size());
    assertEquals(List.of("ev1"), result.relations().get(0).evidenceIds());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), result.relations().get(0).validFrom());
    assertEquals(1, f.graph.relations.size());
  }

  @Test
  void graphRejectsInvalidCandidatesIndividuallyWithoutDroppingValidRelations() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.RELATION_EXTRACT,
        new GraphCompilationService.Extraction(
            List.of(),
            List.of(
                new GraphCompilationService.ProposedRelation(
                    "张三", "PERSON", "DIRECTOR_OF", "甲公司", "COMPANY", List.of("f1"), "", "", 0.95),
                new GraphCompilationService.ProposedRelation(
                    "张三", "ALIEN", "DIRECTOR_OF", "甲公司", "COMPANY", List.of("f1"), "", "", 0.9),
                new GraphCompilationService.ProposedRelation(
                    "张三", "PERSON", "NOT_A_RELATION", "甲公司", "COMPANY", List.of("f1"), "", "", 0.9),
                new GraphCompilationService.ProposedRelation(
                    "甲公司", "COMPANY", "DIRECTOR_OF", "乙公司", "COMPANY", List.of("f1"), "", "", 0.9),
                new GraphCompilationService.ProposedRelation(
                    "张三",
                    "PERSON",
                    "DIRECTOR_OF",
                    "甲公司",
                    "COMPANY",
                    List.of("missing"),
                    "",
                    "",
                    0.9),
                new GraphCompilationService.ProposedRelation(
                    "甲公司", "COMPANY", "RELATED_TO", "甲公司", "COMPANY", List.of("f1"), "", "", 0.9),
                new GraphCompilationService.ProposedRelation(
                    "张三", "PERSON", "DIRECTOR_OF", "甲公司", "COMPANY", List.of(), "", "", 0.9),
                new GraphCompilationService.ProposedRelation(
                    "张三", "PERSON", "DIRECTOR_OF", "甲公司", "COMPANY", List.of("f1"), "", "", 1.2),
                new GraphCompilationService.ProposedRelation(
                    "张三",
                    "PERSON",
                    "DIRECTOR_OF",
                    "甲公司",
                    "COMPANY",
                    List.of("f1"),
                    "not-a-date",
                    "",
                    0.9)),
            List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

    var result = new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1");

    assertTrue(result.coverage().pass());
    assertEquals(1, result.relations().size());
    assertEquals(8, result.rejectedRelations().size());
    assertEquals(8, f.repo.graphRejections.size());
    assertEquals(1, f.graph.relations.size());
    assertTrue(
        result.rejectedRelations().stream()
            .anyMatch(rejected -> rejected.reason().contains("unsupported entity type")));
    assertTrue(
        result.rejectedRelations().stream()
            .anyMatch(rejected -> rejected.reason().contains("unknown fact missing")));
    assertTrue(
        result.rejectedRelations().stream()
            .anyMatch(rejected -> rejected.reason().contains("self relation")));
    assertTrue(
        result.rejectedRelations().stream()
            .anyMatch(rejected -> rejected.reason().contains("unsupported date/time")));
  }

  @Test
  void rejectedRelationStillFailsGraphWhenEligibleFactLosesCoverage() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.RELATION_EXTRACT,
        new GraphCompilationService.Extraction(
            List.of(),
            List.of(
                new GraphCompilationService.ProposedRelation(
                    "张三",
                    "PERSON",
                    "NOT_A_RELATION",
                    "甲公司",
                    "COMPANY",
                    List.of("f1"),
                    "",
                    "",
                    0.95)),
            List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1"));

    assertTrue(error.getMessage().contains("uncovered eligible facts: f1"));
    assertEquals(1, f.repo.graphRejections.size());
    assertTrue(f.repo.graphRejections.get(0).reason().contains("unsupported relation type"));
    assertTrue(f.graph.relations.isEmpty());
  }

  @Test
  void wikiRejectsClaimWhenIndependentJudgeDoesNotSupportIt() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.WIKI_WRITE,
        new WikiCompilationService.Proposal(
            "COMPANY",
            "甲公司",
            List.of(
                new WikiCompilationService.ProposedClaim(
                    "Governance", "[[张三]]担任[[甲公司]]董事。", List.of("f1"))),
            List.of(new WikiCompilationService.FactDecision("f1", true, ""))));
    f.model.enqueue(
        Ports.ModelTask.WIKI_JUDGE,
        new WikiCompilationService.SupportDecision(false, "unsupported"));

    assertThrows(
        IllegalStateException.class,
        () ->
            new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
                .compile(
                    new WikiCompilationService.CompileRequest(
                        "COMPANY", "甲公司", "entity-company", List.of("s1"))));
    assertEquals(0, f.derived.wikiIndexes);
  }

  @Test
  void wikiAcceptsDynamicTypeAndContentDrivenSection() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.WIKI_WRITE,
        new WikiCompilationService.Proposal(
            "CUSTOM_ENTERPRISE_TOPIC",
            "甲公司专题",
            List.of(
                new WikiCompilationService.ProposedClaim(
                    "动态治理事实", "[[张三]]担任[[甲公司]]董事。", List.of("f1"))),
            List.of(new WikiCompilationService.FactDecision("f1", true, ""))));
    f.model.enqueue(
        Ports.ModelTask.WIKI_JUDGE, new WikiCompilationService.SupportDecision(true, "direct"));

    var result =
        new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compile(
                new WikiCompilationService.CompileRequest(
                    "CUSTOM_ENTERPRISE_TOPIC", "甲公司专题", "entity-company", List.of("s1")));

    assertEquals("CUSTOM_ENTERPRISE_TOPIC", result.wiki().wikiType());
    assertTrue(result.wiki().markdown().contains("## 动态治理事实"));
    assertTrue(result.coverage().pass());
  }

  @Test
  void wikiBatchesGroundingForMultipleClaims() {
    Fixture f = new Fixture();
    SummaryRecord summary =
        new SummaryRecord(
            "s1",
            "u1",
            "d1",
            "v1",
            "summary/key",
            "hash",
            "PASS",
            "PASS",
            "ACTIVE",
            "large-model",
            "# summary",
            List.of(
                new SummaryFact(
                    "f1",
                    "s1",
                    "Governance",
                    "张三担任甲公司董事。",
                    List.of("b1"),
                    List.of("ev1"),
                    "PASS",
                    "direct"),
                new SummaryFact(
                    "f2",
                    "s1",
                    "Governance",
                    "李四担任甲公司监事。",
                    List.of("b2"),
                    List.of("ev2"),
                    "PASS",
                    "direct")),
            Instant.parse("2026-08-14T00:00:00Z"));
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.WIKI_WRITE,
        new WikiCompilationService.Proposal(
            "COMPANY",
            "甲公司",
            List.of(
                new WikiCompilationService.ProposedClaim("治理", "[[张三]]担任[[甲公司]]董事。", List.of("f1")),
                new WikiCompilationService.ProposedClaim(
                    "治理", "[[李四]]担任[[甲公司]]监事。", List.of("f2"))),
            List.of(
                new WikiCompilationService.FactDecision("f1", true, ""),
                new WikiCompilationService.FactDecision("f2", true, ""))));
    f.model.enqueue(
        Ports.ModelTask.WIKI_JUDGE,
        new WikiCompilationService.SupportDecisionSet(
            List.of(
                new WikiCompilationService.SupportDecisionItem("C1", true, "direct"),
                new WikiCompilationService.SupportDecisionItem("C2", true, "direct"))));

    var result =
        new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compile(
                new WikiCompilationService.CompileRequest(
                    "COMPANY", "甲公司", "entity-company", List.of("s1")));

    assertTrue(result.coverage().pass());
    assertEquals(2, result.wiki().claims().size());
    assertEquals(1, f.derived.wikiIndexes);
  }

  @Test
  void wikiPersistsOnlyGroundedCoverageCompletePageAndPreservesWikilinks() {
    Fixture f = new Fixture();
    SummaryRecord summary = activeSummary("s1");
    f.repo.summaries.put(summary.id(), summary);
    f.model.enqueue(
        Ports.ModelTask.WIKI_WRITE,
        new WikiCompilationService.Proposal(
            "COMPANY",
            "甲公司",
            List.of(
                new WikiCompilationService.ProposedClaim(
                    "Governance", "[[张三]]担任[[甲公司]]董事。", List.of("f1"))),
            List.of(new WikiCompilationService.FactDecision("f1", true, ""))));
    f.model.enqueue(
        Ports.ModelTask.WIKI_JUDGE, new WikiCompilationService.SupportDecision(true, "direct"));

    var result =
        new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
            .compile(
                new WikiCompilationService.CompileRequest(
                    "COMPANY", "甲公司", "entity-company", List.of("s1")));

    assertTrue(result.coverage().pass());
    assertEquals(List.of("张三", "甲公司"), result.wiki().links());
    assertEquals(1, f.derived.wikiIndexes);
    assertTrue(f.repo.wikis.containsKey(result.wiki().id()));
  }

  private static SummaryRecord activeSummary(String id) {
    SummaryFact fact =
        new SummaryFact(
            "f1", id, "Governance", "张三担任甲公司董事。", List.of("b1"), List.of("ev1"), "PASS", "direct");
    return new SummaryRecord(
        id,
        "u1",
        "d1",
        "v1",
        "summary/key",
        "hash",
        "PASS",
        "PASS",
        "ACTIVE",
        "large-model",
        "# summary",
        List.of(fact),
        Instant.parse("2026-08-14T00:00:00Z"));
  }

  private static final class Fixture {
    final FakeRepository repo = new FakeRepository();
    final ScriptedModel model = new ScriptedModel();
    final InMemoryObjects objects = new InMemoryObjects();
    final FakeEmbedding embedding = new FakeEmbedding();
    final FakeDerived derived = new FakeDerived();
    final FakeGraph graph = new FakeGraph();

    Fixture() {
      RawBlock b1 = block("b1", 0, "甲公司与乙公司签署协议。张三担任甲公司董事。");
      RawBlock b2 = block("b2", 1, "协议期限为一年。");
      Map<String, Evidence> evidence =
          Map.of(
              "b1",
                  new Evidence(
                      "ev1",
                      "d1",
                      "v1",
                      "b1",
                      "original",
                      Map.of(),
                      b1.content(),
                      b1.contentHash()),
              "b2",
                  new Evidence(
                      "ev2",
                      "d1",
                      "v1",
                      "b2",
                      "original",
                      Map.of(),
                      b2.content(),
                      b2.contentHash()));
      repo.source =
          new VersionSource(
              "d1",
              "v1",
              "合同A",
              "",
              new Metadata("法务", "合同", List.of(), "甲公司", null, "合同/协议"),
              List.of(b1, b2),
              evidence);
    }
  }

  private static RawBlock block(String id, int no, String content) {
    return block(id, no, content, List.of());
  }

  private static RawBlock block(String id, int no, String content, List<String> headingPath) {
    return new RawBlock(
        id,
        "v1",
        no,
        "text",
        content,
        content,
        headingPath,
        1,
        1,
        List.of(),
        List.of(),
        List.of(id),
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(content),
        10);
  }

  private static final class ScriptedModel implements Ports.KnowledgeModel {
    final Map<Ports.ModelTask, Deque<Object>> scripted = new LinkedHashMap<>();
    final Map<Class<?>, Deque<Object>> typedScripted = new LinkedHashMap<>();
    final List<String> structuredPrompts = new ArrayList<>();

    void enqueue(Ports.ModelTask task, Object value) {
      scripted.computeIfAbsent(task, k -> new ArrayDeque<>()).add(value);
    }

    void enqueueType(Class<?> outputType, Object value) {
      typedScripted.computeIfAbsent(outputType, k -> new ArrayDeque<>()).add(value);
    }

    @Override
    public String identity(Ports.ModelRole role) {
      return role == Ports.ModelRole.LARGE ? "large-model" : "small-judge";
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      structuredPrompts.add(task + "\n" + userPrompt);
      Object next =
          Optional.ofNullable(typedScripted.get(outputType))
              .map(Deque::pollFirst)
              .orElseGet(
                  () ->
                      Optional.ofNullable(scripted.get(task))
                          .map(Deque::pollFirst)
                          .orElseThrow(() -> new AssertionError("no scripted output for " + task)));
      if (next instanceof RuntimeException failure) throw failure;
      return outputType.cast(next);
    }
  }

  private static final class FakeRepository implements CompilationRepository {
    VersionSource source;
    final Map<String, SummaryRecord> summaries = new LinkedHashMap<>();
    List<DocumentUnit> documentUnits = List.of();
    List<DocumentUnit> persistedDocumentUnits = List.of();
    final Map<String, List<ConceptRecord>> conceptsBySummary = new LinkedHashMap<>();
    List<GraphRelationRejection> graphRejections = List.of();
    final Map<String, WikiRecord> wikis = new LinkedHashMap<>();
    long wikiVersion = 0;

    @Override
    public VersionSource loadVersionSource(String versionId) {
      return source;
    }

    @Override
    public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {
      documentUnits = List.copyOf(units);
    }

    @Override
    public List<DocumentUnit> loadDocumentUnits(String versionId) {
      return persistedDocumentUnits;
    }

    @Override
    public void saveSummary(SummaryRecord summary) {
      summaries.put(summary.id(), summary);
    }

    @Override
    public Optional<SummaryRecord> findSummary(String summaryId) {
      return Optional.ofNullable(summaries.get(summaryId));
    }

    @Override
    public List<SummaryRecord> loadSummaries(List<String> summaryIds) {
      return summaryIds.stream().map(summaries::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForVersion(String versionId) {
      return summaries.values().stream()
          .filter(summary -> summary.versionId().equals(versionId) && summary.activeAndGrounded())
          .toList();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForCompany(String company) {
      return summaries.values().stream().filter(SummaryRecord::activeAndGrounded).toList();
    }

    @Override
    public void replaceConceptsForSummary(String summaryId, List<ConceptRecord> concepts) {
      conceptsBySummary.put(summaryId, List.copyOf(concepts));
    }

    @Override
    public List<ConceptRecord> loadActiveConceptsForSummary(String summaryId) {
      return conceptsBySummary.getOrDefault(summaryId, List.of()).stream()
          .filter(concept -> "ACTIVE".equals(concept.lifecycle()))
          .toList();
    }

    @Override
    public List<ConceptRecord> loadActiveConceptsForVersion(String versionId) {
      return conceptsBySummary.values().stream()
          .flatMap(List::stream)
          .filter(
              concept ->
                  versionId.equals(concept.versionId()) && "ACTIVE".equals(concept.lifecycle()))
          .toList();
    }

    @Override
    public void replaceGraphRejections(String summaryId, List<GraphRelationRejection> rejections) {
      graphRejections = rejections == null ? List.of() : List.copyOf(rejections);
    }

    @Override
    public Entity resolveEntity(String name, String entityType) {
      String key = GraphCompilationService.canonicalKey(name);
      return new Entity(
          GraphCompilationService.stableId("entity", entityType, key),
          name,
          entityType,
          key,
          List.of(),
          Map.of());
    }

    @Override
    public void saveGraph(
        String summaryId,
        List<Entity> entities,
        List<Relation> relations,
        List<CompilationContracts.EntityResolution> resolutions) {}

    @Override
    public long nextWikiVersion(String wikiId) {
      return ++wikiVersion;
    }

    @Override
    public void saveWiki(WikiRecord wiki) {
      wikis.put(wiki.id(), wiki);
    }

    @Override
    public Ports.DerivedContext derivedContextForSummaries(List<String> summaryIds) {
      return new Ports.DerivedContext(
          List.of("d1"),
          List.of("v1"),
          List.of("法务"),
          List.of("合同"),
          List.of("甲公司"),
          List.of("合同/协议"),
          List.of());
    }
  }

  private static final class InMemoryObjects implements Ports.ObjectStore {
    final Map<String, byte[]> values = new LinkedHashMap<>();

    @Override
    public void put(String key, InputStream source, long size, String contentType) {
      try {
        values.put(key, source.readAllBytes());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Ports.StoredObject get(String key) {
      byte[] value = values.getOrDefault(key, new byte[0]);
      return new Ports.StoredObject(new ByteArrayInputStream(value), value.length, "text/plain");
    }

    @Override
    public Ports.ObjectInfo stat(String key) {
      byte[] value = values.getOrDefault(key, new byte[0]);
      return new Ports.ObjectInfo(key, value.length, "text/plain", "etag");
    }

    @Override
    public String presignGet(String key, java.time.Duration expiry) {
      return key;
    }

    @Override
    public void delete(String key) {
      values.remove(key);
    }
  }

  private static final class FakeEmbedding implements Ports.EmbeddingPort {
    @Override
    public int dimensions() {
      return 3;
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
      return texts.stream().map(t -> List.of(1f, 0f, 0f)).toList();
    }
  }

  private static final class FakeDerived implements Ports.DerivedSearchIndex {
    int summaryIndexes;
    int wikiIndexes;

    @Override
    public void ensureIndex(int embeddingDimensions) {}

    @Override
    public void indexSummary(
        cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact summary,
        Ports.DerivedContext context,
        List<Float> embedding) {
      summaryIndexes++;
    }

    @Override
    public void indexWiki(
        cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage page,
        Ports.DerivedContext context,
        List<Float> embedding) {
      wikiIndexes++;
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchSummaryBm25(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate>
        searchSummaryVector(
            List<Float> vector,
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
            int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiBm25(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiVector(
        List<Float> vector,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public void updateMetadata(
        String versionId,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata metadata) {}

    @Override
    public void deactivateVersion(String versionId) {}
  }

  private static final class FakeGraph implements Ports.GraphStore {
    final List<Relation> relations = new ArrayList<>();

    @Override
    public void upsertEntities(List<Entity> entities) {}

    @Override
    public void upsertRelations(List<Relation> relations) {
      this.relations.addAll(relations);
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> search(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public void deactivateVersion(String versionId) {}
  }
}
