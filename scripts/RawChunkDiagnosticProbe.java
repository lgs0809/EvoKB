package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.text.BreakIterator;
import java.util.*;

/** Read-only diagnostic: no Gold edits, production overlays, or model calls. */
public final class RawChunkDiagnosticProbe {
  public static void main(String[] args) throws Exception {
    var json = new ObjectMapper(); var root = Path.of(args[0]);
    var manifest = json.readTree(root.resolve("manifest.json").toFile());
    var rows = new ArrayList<Map<String, Object>>();
    for (var gold : manifest.path("cases")) {
      var parser = new TextDocumentParserAdapter(json);
      var parsed = parser.parse(gold.path("filename").asText(), Files.newInputStream(root.resolve(gold.path("filename").asText())));
      if (gold.path("filename").asText().endsWith(".csv"))
        rows.add(Map.of("caseId", gold.path("id").asText(), "gold", gold.path("expectedParsedBlocks"), "actual", parsed.blocks().stream().map(b -> b.raw()).toList()));
    }
    String text = "金额768.50万元人民币和24个月质保期保持不变；合法发票且书面验收两项前提均继续有效，未验收不能请求付款。Amount CNY 9,000.50; only with a valid invoice and written acceptance. No invoice means no payment obligation.";
    var iterator = BreakIterator.getSentenceInstance(Locale.ROOT); iterator.setText(text);
    var segments = new ArrayList<String>(); int start = iterator.first();
    for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) { segments.add(text.substring(start, end)); start = end; }
    json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), Map.of("csvPositionReadback", rows, "jdkSentenceSegments", segments));
  }
}
