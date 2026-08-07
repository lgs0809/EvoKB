package cn.lgs.knowledgebase.infrastructure.parser;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/** Text-native formats retain semantic headings/tables and never invent PDF page coordinates. */
public final class TextDocumentParserAdapter implements ParserPort {
  private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
  private static final String SEMANTIC = "h1,h2,h3,h4,h5,h6,p,pre,li,table";
  private final ObjectMapper mapper;

  public TextDocumentParserAdapter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public ParsedDocument parse(String filename, InputStream source) {
    String extension = extension(filename);
    String raw;
    try {
      raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(source.readAllBytes()))
              .toString();
    } catch (IOException e) {
      throw new IllegalStateException(
          "INPUT_ERROR: cannot read strict UTF-8 text document " + filename, e);
    }
    int sourcePrefixCharacters = raw.startsWith("\uFEFF") ? 1 : 0;
    if (sourcePrefixCharacters > 0) raw = raw.substring(sourcePrefixCharacters);
    String normalized = normalizeNewlines(raw);
    List<ParsedBlock> blocks;
    if (List.of(".html", ".htm").contains(extension)) blocks = htmlBlocks(normalized);
    else if (".csv".equals(extension)) blocks = csvBlocks(raw, sourcePrefixCharacters);
    else if (".md".equals(extension)) blocks = markdownBlocks(normalized);
    else {
      if (".json".equals(extension)) {
        try {
          if (mapper.readTree(normalized) == null) throw new IOException("empty JSON");
        } catch (IOException invalid) {
          throw new IllegalStateException("INPUT_ERROR: invalid JSON document", invalid);
        }
      }
      blocks = paragraphBlocks(normalized);
    }
    try {
      List<Map<String, Object>> content = blocks.stream().map(ParsedBlock::raw).toList();
      String middle =
          mapper.writeValueAsString(
              Map.of(
                  "source_type",
                  extension.replace(".", ""),
                  "parser",
                  "local-text",
                  "coordinate_space",
                  "source-native"));
      String markdown =
          blocks.stream()
              .map(b -> "table".equals(b.type()) ? b.tableBody() : b.text())
              .reduce((a, b) -> a + "\n\n" + b)
              .orElse("");
      return new ParsedDocument(
          markdown,
          middle,
          mapper.writeValueAsString(content),
          "local-text",
          "2",
          List.copyOf(blocks));
    } catch (IOException e) {
      throw new IllegalStateException("cannot serialize local text parse result", e);
    }
  }

  private static List<ParsedBlock> htmlBlocks(String html) {
    var document = Jsoup.parse(html, "", Parser.htmlParser().setTrackPosition(true));
    document.select("script,style,template,noscript").remove();
    List<ParsedBlock> out = new ArrayList<>();
    for (var child : document.body().childNodes()) {
      if (child instanceof Element element) htmlElement(element, out);
      else if (child instanceof TextNode text && !text.getWholeText().isBlank())
        out.add(block(text.getWholeText().trim(), 0));
    }
    return out;
  }

  private static void htmlElement(Element element, List<ParsedBlock> out) {
    String tag = element.normalName();
    if ("table".equals(tag)) {
      element.ownerDocument().outputSettings().prettyPrint(false);
      Map<String, Object> raw = location(element);
      raw.put("type", "table");
      raw.put("table_body", element.outerHtml());
      String caption =
          element.select("caption").stream()
              .map(Element::wholeText)
              .reduce((a, b) -> a + "\n" + b)
              .orElse("");
      if (!caption.isBlank()) raw.put("table_caption", List.of(caption));
      out.add(
          new ParsedBlock(
              "table",
              "",
              0,
              null,
              List.of(),
              List.of(),
              "",
              element.outerHtml(),
              element.outerHtml(),
              Map.copyOf(raw)));
      return;
    }
    boolean semantic = tag.matches("h[1-6]|p|pre|li");
    if (semantic || element.select(SEMANTIC).isEmpty()) {
      String text = element.wholeText().trim();
      if (text.isBlank()) return;
      int level = tag.matches("h[1-6]") ? tag.charAt(1) - '0' : 0;
      Map<String, Object> raw = location(element);
      raw.put("type", "text");
      raw.put("text", text);
      raw.put("text_level", level);
      out.add(
          new ParsedBlock(
              "text", text, level, null, List.of(), List.of(), "", "", "", Map.copyOf(raw)));
      return;
    }
    for (var child : element.childNodes()) {
      if (child instanceof Element nested) htmlElement(nested, out);
      else if (child instanceof TextNode text && !text.getWholeText().isBlank())
        out.add(block(text.getWholeText().trim(), 0));
    }
  }

  private static Map<String, Object> location(Element element) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("source_selector", element.cssSelector());
    var position = element.sourceRange();
    if (position.isTracked()) {
      raw.put("source_line", position.start().lineNumber());
      raw.put("source_column", position.start().columnNumber());
    }
    return raw;
  }

  private static List<ParsedBlock> csvBlocks(String csv, int sourcePrefixCharacters) {
    try (var parser =
        CSVFormat.RFC4180
            .builder()
            .setIgnoreEmptyLines(false)
            .get()
            .parse(new java.io.StringReader(csv))) {
      List<org.apache.commons.csv.CSVRecord> records = parser.getRecords();
      if (records.isEmpty()) return List.of();
      int columns = records.get(0).size();
      var document = Jsoup.parse("<table><thead></thead><tbody></tbody></table>");
      document.outputSettings().prettyPrint(false);
      Element table = document.selectFirst("table");
      List<Map<String, Object>> originalRows = new ArrayList<>();
      for (int i = 0; i < records.size(); i++) {
        var record = records.get(i);
        if (record.size() != columns)
          throw new IllegalStateException(
              "INPUT_ERROR: inconsistent CSV column count at record " + record.getRecordNumber());
        Element row = table.selectFirst(i == 0 ? "thead" : "tbody").appendElement("tr");
        row.attr("data-source-record", Long.toString(record.getRecordNumber()));
        // Source records retain original positions/cells; rendered text keeps the existing LF
        // contract independently from source-coordinate calculation.
        for (String value : record)
          row.appendElement(i == 0 ? "th" : "td").text(normalizeNewlines(value));
        originalRows.add(
            Map.of(
                "record",
                record.getRecordNumber(),
                "character_offset",
                record.getCharacterPosition() + sourcePrefixCharacters,
                "cells",
                record.toList()));
      }
      String html = table.outerHtml();
      return List.of(
          new ParsedBlock(
              "table",
              "",
              0,
              null,
              List.of(),
              List.of(),
              "",
              html,
              html,
              Map.of(
                  "type",
                  "table",
                  "table_body",
                  html,
                  "source_csv_rows",
                  originalRows,
                  "source_offset_unit",
                  "UTF16",
                  "source_newlines_preserved",
                  true)));
    } catch (IOException | java.io.UncheckedIOException invalid) {
      throw new IllegalStateException("INPUT_ERROR: invalid RFC4180 CSV document", invalid);
    }
  }

  private static List<ParsedBlock> markdownBlocks(String text) {
    List<ParsedBlock> out = new ArrayList<>();
    StringBuilder paragraph = new StringBuilder();
    for (String line : text.split("\\n", -1)) {
      Matcher heading = MARKDOWN_HEADING.matcher(line);
      if (heading.matches()) {
        flush(paragraph, out);
        out.add(block(heading.group(2).trim(), heading.group(1).length()));
      } else if (line.isBlank()) flush(paragraph, out);
      else {
        if (!paragraph.isEmpty()) paragraph.append('\n');
        paragraph.append(line);
      }
    }
    flush(paragraph, out);
    return out;
  }

  private static List<ParsedBlock> paragraphBlocks(String text) {
    List<ParsedBlock> out = new ArrayList<>();
    StringBuilder paragraph = new StringBuilder();
    for (String line : text.split("\\n", -1)) {
      if (line.isBlank()) flush(paragraph, out);
      else {
        if (!paragraph.isEmpty()) paragraph.append('\n');
        paragraph.append(line);
      }
    }
    flush(paragraph, out);
    return out;
  }

  private static void flush(StringBuilder paragraph, List<ParsedBlock> out) {
    String text = paragraph.toString().trim();
    if (!text.isEmpty()) out.add(block(text, 0));
    paragraph.setLength(0);
  }

  private static ParsedBlock block(String text, int level) {
    return new ParsedBlock(
        "text",
        text,
        level,
        null,
        List.of(),
        List.of(),
        "",
        "",
        "",
        Map.of("type", "text", "text", text, "text_level", level));
  }

  private static String normalizeNewlines(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  static String extension(String filename) {
    if (filename == null) return "";
    int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    int dot = filename.lastIndexOf('.');
    return dot > slash ? filename.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
  }
}
