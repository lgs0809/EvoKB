package cn.lgs.knowledgebase.infrastructure.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoutingParserAdapterTest {
  private final TextDocumentParserAdapter text = new TextDocumentParserAdapter(new ObjectMapper());

  @Test
  void parsesMarkdownLocallyAndPreservesHeadingLevel() {
    ParsedDocument parsed = text.parse("note.md", bytes("# Title\n\nVerified paragraph."));

    assertEquals("local-text", parsed.parserName());
    assertEquals(2, parsed.blocks().size());
    assertEquals("Title", parsed.blocks().get(0).text());
    assertEquals(1, parsed.blocks().get(0).textLevel());
    assertEquals("Verified paragraph.", parsed.blocks().get(1).text());
  }

  @Test
  void routesTextFormatsLocallyAndPdfToMinerUDelegate() {
    AtomicInteger minerCalls = new AtomicInteger();
    ParserPort miner =
        (filename, source) -> {
          minerCalls.incrementAndGet();
          return new ParsedDocument("miner", "{}", "[]", "mineru-pipeline", "test", List.of());
        };
    RoutingParserAdapter routing = new RoutingParserAdapter(text, miner);

    assertEquals("local-text", routing.parse("note.md", bytes("hello")).parserName());
    assertEquals(0, minerCalls.get());
    assertEquals("mineru-pipeline", routing.parse("scan.pdf", bytes("pdf")).parserName());
    assertEquals(1, minerCalls.get());
  }

  @Test
  void htmlParserDropsMarkupAndScriptContent() {
    ParsedDocument parsed =
        text.parse("page.html", bytes("<h1>Title</h1><script>bad()</script><p>A &amp; B</p>"));

    assertFalse(parsed.markdown().contains("bad()"));
    assertFalse(parsed.markdown().contains("<h1>"));
    assertEquals("Title\n\nA & B", parsed.markdown());
    assertEquals(1, parsed.blocks().get(0).textLevel());
    assertNull(parsed.blocks().get(0).page());
  }

  @Test
  void htmlPreservesMergedCellsUnitsCaptionAndSourceLocation() {
    var parsed =
        text.parse(
            "table.html",
            bytes(
                "<h2>回款</h2>\n<table><caption>单位：万元</caption><tr><th rowspan='2'>合同</th><th colspan='2'>付款</th></tr><tr><th>金额</th><th>条件</th></tr><tr><td>HT-9</td><td>900</td><td>发票且验收后60日</td></tr></table>"));
    assertEquals(2, parsed.blocks().size());
    var table = parsed.blocks().get(1);
    assertEquals("table", table.type());
    assertTrue(table.tableBody().contains("rowspan=\"2\""));
    assertTrue(table.tableBody().contains("colspan=\"2\""));
    assertTrue(table.tableBody().contains("900"));
    assertEquals(List.of("单位：万元"), table.raw().get("table_caption"));
    assertNotNull(table.raw().get("source_line"));
    assertTrue(table.bbox().isEmpty());
  }

  @Test
  void csvRetainsQuotedCommasNewlinesEmptyCellsAndOriginalRecordIdentity() {
    var parsed =
        text.parse(
            "invoice.csv",
            bytes("合同,金额（万元）,条件,备注\r\nHT-9,900,\"发票，验收\r\n后60日\",\r\nHT-10,0,\"包含\"\"引号\"\"\",结束"));
    assertEquals(1, parsed.blocks().size());
    var table = parsed.blocks().get(0);
    assertEquals("table", table.type());
    var dom = org.jsoup.Jsoup.parse(table.tableBody());
    assertEquals("金额（万元）", dom.select("thead th").get(1).text());
    assertEquals("发票，验收\n后60日", dom.select("tbody tr").get(0).select("td").get(2).wholeText());
    assertEquals("", dom.select("tbody tr").get(0).select("td").get(3).text());
    assertEquals("包含\"引号\"", dom.select("tbody tr").get(1).select("td").get(2).text());
    assertNotNull(table.raw().get("source_csv_rows"));
    assertNull(table.page());
  }

  @Test
  void invalidEncodingAndMalformedCsvOrJsonRequireInputRepair() {
    assertThrows(
        IllegalStateException.class,
        () -> text.parse("bad.txt", new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28})));
    assertThrows(IllegalStateException.class, () -> text.parse("bad.csv", bytes("a,b\n1,2,3")));
    assertThrows(IllegalStateException.class, () -> text.parse("bad.json", bytes("{bad}")));
    assertThrows(
        IllegalStateException.class, () -> text.parse("bad.csv", bytes("a,b\n\"unterminated,2")));
  }

  @Test
  void plainTextRetainsInternalWhitespaceCaseAndNumbers() {
    var parsed = text.parse("exact.txt", bytes("\r\n ABC  900\t万元  \r\n 条件60日\r\n"));
    assertEquals("ABC  900\t万元  \n 条件60日", parsed.blocks().get(0).text());
  }

  @Test
  void textIngestionRemainsAvailableWithoutBinaryParserButPdfFailsBeforeReading() {
    RoutingParserAdapter routing = new RoutingParserAdapter(text, null);
    assertEquals("local-text", routing.parse("note.md", bytes("# Verified text")).parserName());
    ByteArrayInputStream source = bytes("unparsed-pdf");
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> routing.parse("scan.pdf", source));
    assertTrue(failure.getMessage().contains("未配置 MinerU"));
    assertEquals(12, source.available());
    AtomicInteger submissions = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            routing.parse(
                "scan.pdf",
                source,
                "original-parser-task",
                ignored -> submissions.incrementAndGet()));
    assertEquals(0, submissions.get());
    assertEquals(12, source.available());
  }

  private static ByteArrayInputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
