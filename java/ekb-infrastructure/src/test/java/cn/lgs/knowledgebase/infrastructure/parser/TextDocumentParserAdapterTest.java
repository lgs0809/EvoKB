package cn.lgs.knowledgebase.infrastructure.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextDocumentParserAdapterTest {
  private final ObjectMapper json = new ObjectMapper();
  private final TextDocumentParserAdapter parser = new TextDocumentParserAdapter(json);

  @Test
  void csvLocationsReferToTheOriginalCrLfAndBomIncludingQuotedMultilineCells() {
    String original = "\uFEFF合同,条件\r\nHT-01,\"合法发票\r\n且书面验收\"\r\nHT-02,尚未付款\r\n";
    var parsed =
        parser.parse(
            "ledger.csv", new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));
    var raw = parsed.blocks().get(0).raw();
    var rows = json.valueToTree(raw.get("source_csv_rows"));

    assertEquals(1, rows.get(0).path("character_offset").asLong());
    assertEquals(original.indexOf("HT-01"), rows.get(1).path("character_offset").asLong());
    assertEquals(original.indexOf("HT-02"), rows.get(2).path("character_offset").asLong());
    assertEquals("合法发票\r\n且书面验收", rows.get(1).path("cells").get(1).asText());
    assertEquals(
        "合法发票\n且书面验收",
        org.jsoup.Jsoup.parse(parsed.blocks().get(0).tableBody())
            .select("tbody tr")
            .get(0)
            .select("td")
            .get(1)
            .wholeText());
    assertEquals("UTF16", raw.get("source_offset_unit"));
    assertEquals(true, raw.get("source_newlines_preserved"));
    assertNull(parsed.blocks().get(0).page());
  }

  @Test
  void csvOffsetUnitsAreExplicitUtf16AndNeverConfusedWithUtf8ByteOffsets() {
    String original = "合同,金额\r\n😀测试,900.50\r\nHT-03,120.25\r\n";
    var parsed =
        parser.parse(
            "ledger.csv", new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));
    var rows = json.valueToTree(parsed.blocks().get(0).raw().get("source_csv_rows"));
    long offset = rows.get(2).path("character_offset").asLong();

    assertEquals(original.indexOf("HT-03"), offset);
    assertNotEquals(
        original.substring(0, original.indexOf("HT-03")).getBytes(StandardCharsets.UTF_8).length,
        offset);
    assertEquals("😀测试", rows.get(1).path("cells").get(0).asText());
  }

  @Test
  void exactOriginalBytesAndEveryNativeBlockBindTheCurrentDeclaredParser() {
    String original = "\uFEFF# 合成资料\r\n\r\n必须提交发票且完成验收。\r\n\r\n币种为CNY。";
    byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
    var parsed = parser.parse("fixture.md", new ByteArrayInputStream(bytes));
    var identity = parser.currentPipeline("fixture.md").orElseThrow();
    var source =
        new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
            "fixture.md",
            "fixture.md",
            bytes.length,
            cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity.hash(bytes),
            "text/markdown");
    assertDoesNotThrow(
        () ->
            cn.lgs.knowledgebase.application.raw.ParserCheckpointPolicy.requireParsed(
                parsed, source, identity));
    assertFalse(parsed.blocks().isEmpty());
    for (var block : parsed.blocks()) {
      var receipt = json.valueToTree(block.raw().get("_evokb_parser_receipt"));
      assertEquals(source.sha256(), receipt.path("sourceSha256").asText());
      assertEquals(identity.sha256(), receipt.path("pipeline").path("sha256").asText());
    }
    assertEquals("local-text", parsed.parserName());
    assertEquals("2", parsed.parserVersion());
    assertTrue(parsed.markdown().contains("发票且完成验收"));
  }

  @Test
  void nativeFormatsHaveDistinctIdentitiesAndContentCannotReplaceOriginalByteIdentity() {
    assertNotEquals(parser.currentPipeline("fixture.csv"), parser.currentPipeline("fixture.md"));
    String original = "\uFEFFname,condition\r\nA,invoice and acceptance\r\n";
    var bytes = original.getBytes(StandardCharsets.UTF_8);
    var parsed = parser.parse("fixture.csv", new ByteArrayInputStream(bytes));
    var identity = parser.currentPipeline("fixture.csv").orElseThrow();
    var normalized =
        new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
            "fixture.csv",
            "fixture.csv",
            bytes.length,
            cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity.hash(
                parsed.markdown().getBytes(StandardCharsets.UTF_8)),
            "text/csv");
    assertThrows(
        IllegalStateException.class,
        () ->
            cn.lgs.knowledgebase.application.raw.ParserCheckpointPolicy.requireParsed(
                parsed, normalized, identity));
  }
}
