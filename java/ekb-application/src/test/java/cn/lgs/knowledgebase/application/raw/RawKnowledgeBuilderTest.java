package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawKnowledgeBuilderTest {
  @Test
  void completeChineseConditionsAndDecimalAmountsAreNeverUsedAsSentenceCutPoints() {
    List<String> clauses =
        java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> "批次" + i + "金额768.50万元保持不变；合法发票且书面验收两项前提均继续有效，未验收不能请求付款。")
            .toList();
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "conditions",
            List.of(
                new ParsedBlock(
                    "text",
                    String.join("", clauses),
                    0,
                    null,
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    Map.of())));
    var chunks =
        RawKnowledgeBuilder.smartChunks(
            "doc",
            "conditions",
            new Metadata("legal", "contract", List.of(), "", null, "CONTRACT"),
            blocks,
            new RawChunkProfile(90, 140, 0, 20),
            RawTestFixtures.TOKENS);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 140));
    clauses.forEach(
        clause -> assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains(clause))));
  }

  @Test
  void aSentenceAboveTargetFitsWholeWithinHardBudgetUsingJdkBoundaries() {
    String clause =
        "Amount CNY 9,000.50; payment is due only with a valid invoice and written acceptance.";
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "english",
            List.of(
                new ParsedBlock(
                    "text",
                    (clause + " ").repeat(8).trim(),
                    0,
                    null,
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    Map.of())));
    var chunks =
        RawKnowledgeBuilder.smartChunks(
            "doc",
            "english",
            new Metadata("legal", "contract", List.of(), "", null, "CONTRACT"),
            blocks,
            new RawChunkProfile(60, 130, 0, 20),
            RawTestFixtures.TOKENS);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 130));
    assertEquals(8, chunks.stream().filter(chunk -> chunk.content().contains(clause)).count());
  }

  @Test
  void anOversizedSentenceHasBoundedSlicesAndCompleteOriginalSourceCoverage() {
    String original = "甲".repeat(501) + "付款必须有发票和书面验收。";
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "oversized",
            List.of(
                new ParsedBlock(
                    "text", original, 0, null, List.of(), List.of(), "", "", "", Map.of())));
    var chunks =
        RawKnowledgeBuilder.smartChunks(
            "doc",
            "oversized",
            new Metadata("legal", "contract", List.of(), "", null, "CONTRACT"),
            blocks,
            new RawChunkProfile(60, 120, 0, 10),
            RawTestFixtures.TOKENS);

    assertTrue(chunks.size() > 1);
    assertTrue(
        chunks.stream()
            .allMatch(
                chunk ->
                    chunk.tokenCount() <= 120
                        && chunk.sourceBlockIds().equals(List.of(blocks.get(0).id()))));
    assertEquals(
        original,
        chunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).reduce("", String::concat));
  }

  @Test
  void rawBlocksPreserveHeadingPageBboxAndPageSize() {
    var parsed =
        List.of(
            new ParsedBlock(
                "text",
                "第一章",
                1,
                0,
                List.of(1d, 2d, 3d, 4d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "text",
                "正文内容",
                0,
                0,
                List.of(5d, 6d, 7d, 8d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()));
    var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);

    assertEquals(2, blocks.size());
    assertTrue(blocks.get(0).id().startsWith("rb_"));
    assertEquals(43, blocks.get(0).id().length());
    assertEquals(List.of("第一章"), blocks.get(1).headingPath());
    assertEquals(1, blocks.get(1).pageStart());
    assertEquals(List.of(595d, 842d), blocks.get(1).pageSize());
    assertEquals(List.of(5d, 6d, 7d, 8d), blocks.get(1).bbox());
  }

  @Test
  void rawBlocksSkipParserEntriesWithoutRetrievableContent() {
    var parsed =
        List.of(
            new ParsedBlock("text", "", 0, 0, List.of(), List.of(), "", "", "", Map.of()),
            new ParsedBlock("image", "", 0, 0, List.of(), List.of(), "", "", "", Map.of()),
            new ParsedBlock("discarded", "", 0, 0, List.of(), List.of(), "", "", "", Map.of()),
            new ParsedBlock("text", "可检索正文", 0, 1, List.of(), List.of(), "", "", "", Map.of()));

    var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);

    assertEquals(1, blocks.size());
    assertEquals("可检索正文", blocks.get(0).content());
    assertEquals(3, blocks.get(0).blockNo());
    assertEquals(2, blocks.get(0).pageStart());
  }

  @Test
  void pageNumbersAreIgnoredAndHeadingContextIsAttachedToEvidenceAndChunks() {
    var parsed =
        List.of(
            new ParsedBlock(
                "text",
                "第二章 人员组成",
                2,
                0,
                List.of(400d, 200d, 600d, 230d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "text",
                "第三条 投资与决策委员会成员由三名董事组成，其中包括一名独立董事。",
                0,
                0,
                List.of(140d, 480d, 820d, 510d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "page_number",
                "1",
                0,
                0,
                List.of(490d, 928d, 510d, 942d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "header",
                "万科企业股份有限公司",
                0,
                0,
                List.of(100d, 20d, 900d, 45d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "footer",
                "董事会制度文件",
                0,
                0,
                List.of(100d, 950d, 900d, 980d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()),
            new ParsedBlock(
                "text",
                "第四条 委员由董事会选举产生。",
                0,
                1,
                List.of(140d, 100d, 600d, 125d),
                List.of(595d, 842d),
                "",
                "",
                "",
                Map.of()));

    var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);
    var evidence = RawKnowledgeBuilder.evidence("doc", "original/doc/v1/file.pdf", blocks);
    var chunks =
        fixtureChunks(
            "doc", "v1", new Metadata("dept", "cat", List.of(), "", null, "POLICY"), blocks);

    assertEquals(3, blocks.size());
    assertEquals(2, evidence.size());
    assertTrue(evidence.get(0).content().startsWith("第二章 人员组成\n第三条"));
    assertTrue(evidence.stream().noneMatch(item -> item.content().trim().equals("第二章 人员组成")));
    assertEquals("normalized_1000", evidence.get(0).location().get("bbox_space"));
    assertEquals(2, chunks.size());
    assertTrue(chunks.get(0).content().startsWith("第二章 人员组成\n第三条"));
    assertTrue(chunks.get(1).content().startsWith("第二章 人员组成\n第四条"));
  }

  @Test
  void tableRemainsAtomicAndChunkIdsAreStableForSameVersionAndBlocks() {
    String longText = "测试内容".repeat(1200);
    var parsed =
        List.of(
            new ParsedBlock("text", longText, 0, 0, List.of(), List.of(), "", "", "", Map.of()),
            new ParsedBlock(
                "table",
                "",
                0,
                1,
                List.of(),
                List.of(),
                "",
                "<table>x</table>",
                "a|b\n1|2",
                Map.of()),
            new ParsedBlock("text", "结尾", 0, 2, List.of(), List.of(), "", "", "", Map.of()));
    var blocks = RawKnowledgeBuilder.rawBlocks("v1", parsed);
    var md = new Metadata("dept", "cat", List.of(), "company", null, "REPORT");
    var first = fixtureChunks("d1", "v1", md, blocks);
    var second = fixtureChunks("d1", "v1", md, blocks);

    assertEquals(
        first.stream().map(RawKnowledgeBuilder.ChunkDraft::id).toList(),
        second.stream().map(RawKnowledgeBuilder.ChunkDraft::id).toList());
    assertTrue(first.stream().anyMatch(c -> c.content().contains("a|b\n1|2")));
    assertTrue(
        first.stream()
            .filter(c -> c.content().contains("a|b\n1|2"))
            .allMatch(c -> c.sourceBlockIds().contains(blocks.get(1).id())));
    assertEquals(1, first.stream().filter(c -> c.content().contains("a|b\n1|2")).count());
  }

  @Test
  void oversizedHtmlTableSplitsBetweenRowsAndRepeatsHeader() {
    StringBuilder html = new StringBuilder("<table><tr><td>项目</td><td>金额</td></tr>");
    for (int i = 0; i < 80; i++) {
      html.append("<tr><td>项目").append(i).append("</td><td>");
      html.append("金额说明").append("。".repeat(12));
      html.append("</td></tr>");
    }
    html.append("</table>");
    var parsed =
        List.of(
            new ParsedBlock(
                "table", "", 0, 1, List.of(), List.of(), "", html.toString(), "", Map.of()));
    var blocks = RawKnowledgeBuilder.rawBlocks("v-table", parsed);
    var chunks =
        fixtureChunks(
            "doc",
            "v-table",
            new Metadata("dept", "cat", List.of(), "company", null, "REPORT"),
            blocks,
            600,
            0,
            10);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 600));
    assertTrue(
        chunks.stream()
            .allMatch(
                chunk ->
                    org.jsoup.Jsoup.parse(chunk.content()).select("td").stream()
                        .anyMatch(cell -> cell.text().equals("项目"))));
    assertTrue(
        chunks.stream().allMatch(chunk -> chunk.sourceBlockIds().contains(blocks.get(0).id())));
    assertTrue(chunks.get(0).content().contains("项目0"));
    assertTrue(chunks.get(chunks.size() - 1).content().contains("项目79"));
  }

  @Test
  void longHtmlTableWithLowParserTokenCountIsStillBoundedForEmbedding() {
    StringBuilder html = new StringBuilder("<table><tr><td>项目</td><td>金额</td></tr>");
    for (int i = 0; i < 36; i++) {
      html.append("<tr><td>项目").append(i).append("</td><td>金额说明");
      html.append("</td></tr>");
    }
    html.append("</table>");
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "v-table-char",
            List.of(
                new ParsedBlock(
                    "table", "", 0, 1, List.of(), List.of(), "", html.toString(), "", Map.of())));

    assertTrue(blocks.get(0).tokenCount() < 608);
    assertTrue(html.length() > 1200);
    var chunks =
        fixtureChunks(
            "doc",
            "v-table-char",
            new Metadata("dept", "cat", List.of(), "company", null, "REPORT"),
            blocks);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= 1200));
    assertTrue(
        chunks.stream()
            .allMatch(
                chunk ->
                    org.jsoup.Jsoup.parse(chunk.content()).select("td").stream()
                        .anyMatch(cell -> cell.text().equals("项目"))));
    assertTrue(
        chunks.stream().allMatch(chunk -> chunk.sourceBlockIds().contains(blocks.get(0).id())));
    // The vector request uses the same complete chunk content as the raw index; table
    // structure must not be stripped or shortened for embedding.
    assertTrue(chunks.stream().allMatch(chunk -> chunk.content().contains("<td")));
  }

  @Test
  void longPayloadRemainsCompleteForEmbedding() {
    String body = "开头标题和正文。" + "中间内容。".repeat(120) + "结尾结论和风险提示。";
    var chunk =
        new RawKnowledgeBuilder.ChunkDraft(
            "ch-long",
            "doc",
            "v-long",
            body,
            body,
            List.of("章节"),
            List.of("rb-1"),
            1,
            1,
            "hash",
            900,
            new Metadata("dept", "cat", List.of(), "company", null, "REPORT"));

    // RawIngestionApplicationService maps ChunkDraft::content directly.  Keep this
    // invariant explicit so a future resource optimization cannot silently drop recall.
    assertEquals(body, chunk.content());
    assertTrue(chunk.content().length() > 64);
  }

  @Test
  void oversizedSemanticUnitFallsBackToBoundedChunksWithHeadingContext() {
    String body =
        "第一段说明投资决策职责和审议要求。"
            + "第二段说明成员应当独立判断并形成意见。"
            + "第三段说明会议材料需要完整留痕。"
            + "第四段说明重大事项需要持续跟踪。"
            + "第五段说明委员会应当向董事会报告。"
            + "第六段说明相关文件应当归档保存。"
            + "第七段说明必要时可以邀请专业人员列席。"
            + "第八段说明所有结论都应当有原始依据。";
    var parsed =
        List.of(
            new ParsedBlock(
                "text", "第二章 投资与决策委员会", 1, 0, List.of(), List.of(), "", "", "", Map.of()),
            new ParsedBlock("text", body, 0, 0, List.of(), List.of(), "", "", "", Map.of()));
    var blocks = RawKnowledgeBuilder.rawBlocks("v-long", parsed);
    var chunks =
        fixtureChunks(
            "doc",
            "v-long",
            new Metadata("dept", "cat", List.of(), "", null, "POLICY"),
            blocks,
            60,
            10,
            15);

    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.content().startsWith("第二章 投资与决策委员会\n")));
    assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 60));
    assertTrue(
        chunks.stream().allMatch(chunk -> chunk.sourceBlockIds().contains(blocks.get(1).id())));
  }

  @Test
  void semanticBoundaryDoesNotCarryOverlapIntoNextArticle() {
    String first = "第一条标记。" + "前一条内容需要独立保留。".repeat(8);
    String second = "第二条标记。" + "后一条内容不能被上一条重叠污染。".repeat(8);
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "v-boundary",
            List.of(
                new ParsedBlock("text", "第一章 总则", 1, 0, List.of(), List.of(), "", "", "", Map.of()),
                new ParsedBlock("text", first, 0, 0, List.of(), List.of(), "", "", "", Map.of()),
                new ParsedBlock("text", second, 0, 0, List.of(), List.of(), "", "", "", Map.of())));
    var chunks =
        fixtureChunks(
            "doc",
            "v-boundary",
            new Metadata("dept", "cat", List.of(), "", null, "POLICY"),
            blocks,
            55,
            12,
            12);

    assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("第一条标记")));
    assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("第二条标记")));
    assertTrue(
        chunks.stream()
            .filter(chunk -> chunk.content().contains("第二条标记"))
            .noneMatch(chunk -> chunk.content().contains("第一条标记")));
  }

  @Test
  void evidenceIdsFollowGoDeterministicUuidContract() {
    var blocks =
        RawKnowledgeBuilder.rawBlocks(
            "version-1",
            List.of(
                new ParsedBlock(
                    "text", "evidence", 0, 0, List.of(), List.of(), "", "", "", Map.of())));
    var a = RawKnowledgeBuilder.evidence("doc", "original/doc/version/file.pdf", blocks).get(0);
    var b = RawKnowledgeBuilder.evidence("doc", "original/doc/version/file.pdf", blocks).get(0);
    assertEquals(a.id(), b.id());
    assertEquals(36, a.id().length());
    assertEquals(blocks.get(0).contentHash(), a.hash());
    assertEquals(blocks.get(0).id(), a.location().get("block_id"));
  }

  private static java.util.List<RawKnowledgeBuilder.ChunkDraft> fixtureChunks(
      String document,
      String version,
      Metadata metadata,
      java.util.List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock> blocks) {
    return RawKnowledgeBuilder.smartChunks(
        document, version, metadata, blocks, RawChunkProfile.DEFAULT, RawTestFixtures.TOKENS);
  }

  private static java.util.List<RawKnowledgeBuilder.ChunkDraft> fixtureChunks(
      String document,
      String version,
      Metadata metadata,
      java.util.List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock> blocks,
      int target,
      int overlap,
      int boundary) {
    return RawKnowledgeBuilder.smartChunks(
        document,
        version,
        metadata,
        blocks,
        new RawChunkProfile(target, target, overlap, Math.min(150, target)),
        RawTestFixtures.TOKENS);
  }
}
