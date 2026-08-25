package cn.lgs.knowledgebase.infrastructure.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
  }

  private static ByteArrayInputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
