package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * Structure-first chunking. The final encoder text, rather than parser estimates, owns the budget.
 */
final class RawKnowledgeBuilder {
  private static final Set<String> AUXILIARY = Set.of("header", "footer", "page_number");

  private RawKnowledgeBuilder() {}

  static List<RawBlock> rawBlocks(String version, List<ParsedBlock> parsed) {
    // A legacy caller may preserve original evidence before an encoder is configured. Zero means
    // uncounted; smartChunks never trusts this metadata to enforce the encoder budget.
    return rawBlocks(version, parsed, null);
  }

  static List<RawBlock> rawBlocks(
      String version, List<ParsedBlock> parsed, RawTokenCounter tokens) {
    List<RawBlock> result = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    List<ParsedBlock> input = parsed == null ? List.of() : parsed;
    for (int i = 0; i < input.size(); i++) {
      ParsedBlock item = input.get(i);
      if (AUXILIARY.contains(safe(item.type()))) continue;
      String text = blockText(item);
      if (text.isBlank()) continue;
      if ("text".equals(item.type()) && item.textLevel() > 0) {
        int level = Math.min(item.textLevel(), headings.size() + 1);
        headings = new ArrayList<>(headings.subList(0, Math.min(headings.size(), level - 1)));
        headings.add(normalize(item.text()));
      }
      String normalized = normalize(text);
      String id =
          "rb_" + sha256(version + ":" + i + ":" + item.type() + ":" + normalized).substring(0, 40);
      Integer page = item.page() == null || item.page() < 0 ? null : item.page() + 1;
      result.add(
          new RawBlock(
              id,
              version,
              i,
              safe(item.type()),
              text,
              normalized,
              List.copyOf(headings),
              page,
              page,
              safeList(item.bbox()),
              safeList(item.pageSize()),
              List.of(id),
              sha256(normalized),
              tokens == null ? 0 : tokens.count(text)));
    }
    return List.copyOf(result);
  }

  static List<Evidence> evidence(String document, String object, List<RawBlock> blocks) {
    return blocks.stream()
        .filter(RawKnowledgeBuilder::evidenceBlock)
        .map(block -> Evidence.fromBlock(document, object, block))
        .toList();
  }

  static List<ChunkDraft> smartChunks(
      String document, String version, Metadata metadata, List<RawBlock> blocks) {
    return smartChunks(
        document,
        version,
        metadata,
        blocks,
        RawChunkProfile.DEFAULT,
        RawTokenCounter.unconfigured());
  }

  static List<ChunkDraft> smartChunks(
      String document,
      String version,
      Metadata metadata,
      List<RawBlock> blocks,
      int target,
      int overlap,
      int boundary) {
    return smartChunks(
        document,
        version,
        metadata,
        blocks,
        new RawChunkProfile(target, target, overlap, Math.min(150, target)),
        RawTokenCounter.unconfigured());
  }

  static List<ChunkDraft> smartChunks(
      String document,
      String version,
      Metadata metadata,
      List<RawBlock> blocks,
      RawChunkProfile profile,
      RawTokenCounter tokens) {
    List<ChunkDraft> out = new ArrayList<>();
    List<Part> pending = new ArrayList<>();
    for (RawBlock block : blocks) {
      if (!evidenceBlock(block)) continue;
      Part part =
          new Part(
              block.content(),
              block.headingPath(),
              List.of(block.id()),
              block.pageStart(),
              block.pageEnd(),
              block.blockType());
      boolean separate = "table".equals(block.blockType()) || structuralBoundary(block.content());
      if (!pending.isEmpty()
          && (separate
              || !pending.get(0).headings.equals(part.headings)
              || !pending.get(0).type.equals(part.type)))
        flush(document, version, metadata, pending, out, tokens, profile);
      String contextual = render(part.headings, part.body);
      if (tokens.count(contextual) > profile.hardLimitTokens()) {
        flush(document, version, metadata, pending, out, tokens, profile);
        List<String> pieces =
            "table".equals(part.type)
                ? tablePieces(part, profile, tokens)
                : textPieces(part.body, part.headings, profile, tokens, true);
        for (String body : pieces)
          out.add(
              chunk(document, version, metadata, List.of(part.withBody(body)), tokens, profile));
      } else if (separate) {
        out.add(chunk(document, version, metadata, List.of(part), tokens, profile));
      } else {
        List<Part> candidate = new ArrayList<>(pending);
        candidate.add(part);
        if (!pending.isEmpty() && tokens.count(renderParts(candidate)) > profile.targetTokens())
          flush(document, version, metadata, pending, out, tokens, profile);
        pending.add(part);
      }
    }
    flush(document, version, metadata, pending, out, tokens, profile);
    return List.copyOf(out);
  }

  private static void flush(
      String document,
      String version,
      Metadata metadata,
      List<Part> pending,
      List<ChunkDraft> out,
      RawTokenCounter tokens,
      RawChunkProfile profile) {
    if (pending.isEmpty()) return;
    ChunkDraft next = chunk(document, version, metadata, List.copyOf(pending), tokens, profile);
    if (!out.isEmpty()
        && !"table".equals(pending.get(0).type)
        && tokens.count(String.join("\n\n", pending.stream().map(part -> part.body).toList()))
            < profile.tailTokens()) {
      ChunkDraft previous = out.get(out.size() - 1);
      if (previous.structureType.equals(pending.get(0).type)
          && previous.headingPath.equals(next.headingPath)
          && !structuralBoundary(pending.get(0).body)) {
        String prefix = render(previous.headingPath, "");
        String priorBody =
            prefix.isEmpty()
                ? previous.content
                : previous.content.substring(prefix.length()).stripLeading();
        List<Part> merged = new ArrayList<>();
        merged.add(
            new Part(
                priorBody,
                previous.headingPath,
                previous.sourceBlockIds,
                previous.pageStart,
                previous.pageEnd,
                pending.get(0).type));
        merged.addAll(pending);
        if (tokens.count(renderParts(merged)) <= profile.hardLimitTokens()) {
          out.set(out.size() - 1, chunk(document, version, metadata, merged, tokens, profile));
          pending.clear();
          return;
        }
      }
    }
    out.add(next);
    pending.clear();
  }

  private static List<String> textPieces(
      String body,
      List<String> headings,
      RawChunkProfile profile,
      RawTokenCounter tokens,
      boolean overlap) {
    List<String> pieces = new ArrayList<>();
    NavigableSet<Integer> sentences = sentenceEnds(body);
    int start = 0;
    while (start < body.length()) {
      int end = fittingEnd(body, start, headings, profile.targetTokens(), tokens);
      if (end == start) end = fittingEnd(body, start, headings, profile.hardLimitTokens(), tokens);
      if (end == start)
        throw new IllegalStateException(
            "Title or an indivisible token exceeds the raw token budget");
      if (end < body.length()) {
        Integer sentence = sentences.floor(end);
        if (sentence != null && sentence > start) end = sentence;
        else {
          // The target is a preference: keep one complete sentence when it fits the hard budget.
          // A truly oversized sentence still has bounded token slices and the same source identity.
          int hardEnd = fittingEnd(body, start, headings, profile.hardLimitTokens(), tokens);
          Integer complete = sentences.floor(hardEnd);
          end = complete != null && complete > start ? complete : hardEnd;
        }
      }
      // A complete 600–900-token paragraph, or a small final tail, remains a semantic unit.
      if (tokens.count(render(headings, body.substring(start))) <= profile.hardLimitTokens()
          && (tokens.count(render(headings, body.substring(start))) <= profile.targetTokens()
              || tokens.count(body.substring(end)) < profile.tailTokens())) end = body.length();
      pieces.add(body.substring(start, end));
      if (end == body.length()) break;
      int next = end;
      if (overlap && profile.overlapTokens() > 0 && sentences.contains(end)) {
        Integer boundary = sentences.lower(end);
        if (boundary != null
            && boundary > start
            && tokens.count(body.substring(boundary, end)) <= profile.overlapTokens())
          next = boundary;
      }
      if (next <= start) next = end;
      start = next;
    }
    return List.copyOf(pieces);
  }

  private static int fittingEnd(
      String body, int start, List<String> headings, int limit, RawTokenCounter tokens) {
    int low = start + 1, high = body.length(), best = start;
    while (low <= high) {
      int mid = safeBoundary(body, low + (high - low) / 2);
      if (mid <= start) mid = start + Character.charCount(body.codePointAt(start));
      if (tokens.count(render(headings, body.substring(start, mid))) <= limit) {
        best = mid;
        low = mid + 1;
      } else high = mid - 1;
    }
    // Recount every emitted slice. BPE merges are not assumed perfectly monotone at boundaries.
    while (best > start && tokens.count(render(headings, body.substring(start, best))) > limit)
      best = body.offsetByCodePoints(best, -1);
    return best;
  }

  private static NavigableSet<Integer> sentenceEnds(String body) {
    // JDK sentence rules preserve decimal points and semicolon-linked conditions.
    BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
    iterator.setText(body);
    NavigableSet<Integer> boundaries = new TreeSet<>();
    for (int end = iterator.first(); end != BreakIterator.DONE; end = iterator.next())
      boundaries.add(end);
    return boundaries;
  }

  private static int safeBoundary(String body, int index) {
    if (index > 0
        && index < body.length()
        && Character.isLowSurrogate(body.charAt(index))
        && Character.isHighSurrogate(body.charAt(index - 1))) return index - 1;
    return index;
  }

  private static List<String> tablePieces(
      Part part, RawChunkProfile profile, RawTokenCounter tokens) {
    Element table = Jsoup.parse(part.body).selectFirst("table");
    if (table == null || table.select("tr").isEmpty())
      throw new IllegalStateException(
          "Oversized table has no recoverable row/cell structure; original retained for review");
    List<Element> rows =
        table.select("tr").stream().filter(row -> row.closest("table") == table).toList();
    List<List<Cell>> grid = expandGrid(rows);
    int headerCount = 0;
    while (headerCount < rows.size()
        && (rows.get(headerCount).closest("thead") != null
            || !rows.get(headerCount).select("th").isEmpty())) headerCount++;
    if (headerCount == 0) headerCount = 1;
    // Native Office HTML may use td for a multi-row header. Complete every span
    // rooted in the header before treating following rows as business data.
    for (int row = 0; row < headerCount; row++) {
      for (Element cell : rows.get(row).children()) {
        if (Set.of("td", "th").contains(cell.normalName()))
          headerCount = Math.max(headerCount, Math.min(rows.size(), row + span(cell, "rowspan")));
      }
    }
    List<List<Cell>> headers = grid.subList(0, headerCount);
    String outside = tableContext(part.body, table);
    List<String> pieces = new ArrayList<>();
    List<Integer> group = new ArrayList<>();
    for (int row = headerCount; row < grid.size(); row++) {
      List<Integer> candidate = new ArrayList<>(group);
      candidate.add(row);
      String assembled = tableFragment(outside, headers, grid, candidate, null);
      if (!group.isEmpty()
          && tokens.count(render(part.headings, assembled)) > profile.targetTokens()) {
        pieces.add(tableFragment(outside, headers, grid, group, null));
        group.clear();
      }
      String single = tableFragment(outside, headers, grid, List.of(row), null);
      if (tokens.count(render(part.headings, single)) > profile.hardLimitTokens()) {
        if (!group.isEmpty()) {
          pieces.add(tableFragment(outside, headers, grid, group, null));
          group.clear();
        }
        pieces.addAll(longRowPieces(outside, headers, grid, row, part.headings, profile, tokens));
      } else group.add(row);
    }
    if (!group.isEmpty()) pieces.add(tableFragment(outside, headers, grid, group, null));
    if (pieces.isEmpty()) pieces.add(tableFragment(outside, headers, grid, List.of(), null));
    return List.copyOf(pieces);
  }

  private static List<List<Cell>> expandGrid(List<Element> rows) {
    List<List<Cell>> grid = new ArrayList<>();
    java.util.Map<Integer, Span> spans = new java.util.HashMap<>();
    for (int rowNo = 0; rowNo < rows.size(); rowNo++) {
      java.util.Map<Integer, Cell> row = new java.util.TreeMap<>();
      java.util.Map<Integer, Span> next = new java.util.HashMap<>();
      for (var entry : spans.entrySet()) {
        row.put(entry.getKey(), entry.getValue().cell);
        if (entry.getValue().remaining > 1)
          next.put(entry.getKey(), new Span(entry.getValue().cell, entry.getValue().remaining - 1));
      }
      int column = 0;
      for (Element element : rows.get(rowNo).children()) {
        if (!Set.of("td", "th").contains(element.tagName())) continue;
        while (row.containsKey(column)) column++;
        int colspan = span(element, "colspan"), rowspan = span(element, "rowspan");
        String text = element.wholeText();
        for (int offset = 0; offset < colspan; offset++) {
          if (row.containsKey(column + offset))
            throw new IllegalStateException("Overlapping table spans require review");
          Cell cell = new Cell(text, rowNo, column, offset == 0);
          row.put(column + offset, cell);
          if (rowspan > 1) next.put(column + offset, new Span(cell, rowspan - 1));
        }
        column += colspan;
      }
      int width =
          row.isEmpty() ? 0 : row.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      List<Cell> cells = new ArrayList<>();
      for (int col = 0; col < width; col++)
        cells.add(row.getOrDefault(col, new Cell("", rowNo, col, true)));
      grid.add(List.copyOf(cells));
      spans = next;
    }
    if (!spans.isEmpty())
      throw new IllegalStateException(
          "Table rowspan exceeds available rows; original retained for review");
    return grid;
  }

  private static int span(Element element, String name) {
    String raw = element.attr(name);
    if (raw.isBlank()) return 1;
    try {
      int value = Integer.parseInt(raw);
      if (value <= 0 || value > 1000) throw new NumberFormatException();
      return value;
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid table " + name, ex);
    }
  }

  private static String tableContext(String body, Element table) {
    Element doc = Jsoup.parse(body).body();
    Element selected = doc.selectFirst("table");
    if (selected != null) selected.remove();
    return doc.wholeText().trim();
  }

  private static String tableFragment(
      String outside,
      List<List<Cell>> headers,
      List<List<Cell>> grid,
      List<Integer> rows,
      List<Integer> columns) {
    StringBuilder out = new StringBuilder();
    if (!outside.isBlank()) out.append(outside).append('\n');
    out.append("<table><thead>");
    for (List<Cell> header : headers) out.append(rowHtml(header, -1, columns, null));
    out.append("</thead><tbody>");
    for (int row : rows) out.append(rowHtml(grid.get(row), row, columns, null));
    return out.append("</tbody></table>").toString();
  }

  private static String rowHtml(
      List<Cell> cells,
      int row,
      List<Integer> selected,
      java.util.Map<Integer, String> replacements) {
    StringBuilder out = new StringBuilder("<tr");
    if (row >= 0) out.append(" data-source-row=\"").append(row).append("\"");
    out.append('>');
    Set<String> displayedOrigins = new java.util.HashSet<>();
    for (int col = 0; col < cells.size(); col++) {
      if (selected != null && !selected.contains(col)) continue;
      Cell cell = cells.get(col);
      String value =
          replacements != null && replacements.containsKey(col) ? replacements.get(col) : cell.text;
      out.append("<td data-origin=\"")
          .append(cell.originRow)
          .append(':')
          .append(cell.originCol)
          .append("\">");
      // Column-spanned text appears once within this row, but its originating identity is retained.
      if (displayedOrigins.add(cell.originRow + ":" + cell.originCol))
        out.append(Entities.escape(value));
      out.append("</td>");
    }
    return out.append("</tr>").toString();
  }

  private static List<String> longRowPieces(
      String outside,
      List<List<Cell>> headers,
      List<List<Cell>> grid,
      int row,
      List<String> headings,
      RawChunkProfile profile,
      RawTokenCounter tokens) {
    List<String> pieces = new ArrayList<>();
    List<Cell> cells = grid.get(row);
    for (int col = 1; col < cells.size(); col++) {
      Cell cell = cells.get(col);
      if (!cell.first) continue;
      List<Integer> selected = List.of(0, col);
      String shell = tableFragment(outside, headers, grid, List.of(row), selected);
      if (tokens.count(render(headings, shell)) <= profile.hardLimitTokens()) {
        pieces.add(shell);
        continue;
      }
      String emptyShell =
          outside
              + "\n<table><thead>"
              + headers.stream().map(h -> rowHtml(h, -1, selected, null)).reduce("", String::concat)
              + "</thead><tbody>"
              + rowHtml(cells, row, selected, java.util.Map.of(col, ""))
              + "</tbody></table>";
      int remaining = profile.hardLimitTokens() - tokens.count(render(headings, emptyShell));
      if (remaining < 1)
        throw new IllegalStateException(
            "Table row key/header exceeds raw token limit; original retained for review");
      RawTokenCounter withShell =
          new RawTokenCounter() {
            public int count(String value) {
              String body =
                  outside
                      + "\n<table><thead>"
                      + headers.stream()
                          .map(h -> rowHtml(h, -1, selected, null))
                          .reduce("", String::concat)
                      + "</thead><tbody>"
                      + rowHtml(cells, row, selected, java.util.Map.of(selected.get(1), value))
                      + "</tbody></table>";
              return tokens.count(render(headings, body));
            }

            public String identity() {
              return tokens.identity();
            }
          };
      for (String value : textPieces(cell.text, List.of(), profile, withShell, false)) {
        pieces.add(
            outside
                + "\n<table><thead>"
                + headers.stream()
                    .map(h -> rowHtml(h, -1, selected, null))
                    .reduce("", String::concat)
                + "</thead><tbody>"
                + rowHtml(cells, row, selected, java.util.Map.of(col, value))
                + "</tbody></table>");
      }
    }
    if (pieces.isEmpty())
      throw new IllegalStateException(
          "Oversized table row cannot be split without losing its row identity");
    return pieces;
  }

  private static ChunkDraft chunk(
      String document,
      String version,
      Metadata metadata,
      List<Part> parts,
      RawTokenCounter tokens,
      RawChunkProfile profile) {
    String content = renderParts(parts);
    int count = tokens.count(content);
    if (count > profile.hardLimitTokens())
      throw new IllegalStateException("Raw chunk exceeds hard token limit: " + count);
    List<String> sourceIds =
        parts.stream().flatMap(part -> part.sourceIds.stream()).distinct().toList();
    String hash =
        sha256(normalize(String.join("\n\n", parts.stream().map(part -> part.body).toList())));
    String id =
        "ch_"
            + sha256(version + ":" + String.join(",", sourceIds) + ":" + sha256(content))
                .substring(0, 40);
    Integer start =
        parts.stream()
            .map(part -> part.pageStart)
            .filter(java.util.Objects::nonNull)
            .min(Integer::compareTo)
            .orElse(null);
    Integer end =
        parts.stream()
            .map(part -> part.pageEnd)
            .filter(java.util.Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(null);
    return new ChunkDraft(
        id,
        document,
        version,
        content,
        normalize(content),
        parts.get(0).headings,
        sourceIds,
        start,
        end,
        hash,
        count,
        metadata,
        parts.get(0).type);
  }

  static List<RawIndexDocument> toIndexDocuments(
      List<ChunkDraft> chunks, List<Evidence> evidence, List<List<Float>> vectors) {
    if (chunks.size() != vectors.size())
      throw new IllegalArgumentException("embedding count mismatch");
    java.util.Map<String, String> byBlock = new java.util.HashMap<>();
    evidence.forEach(item -> byBlock.put(item.blockId(), item.id()));
    List<RawIndexDocument> docs = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      ChunkDraft chunk = chunks.get(i);
      LinkedHashSet<String> ids = new LinkedHashSet<>();
      chunk.sourceBlockIds.forEach(
          id -> {
            if (byBlock.containsKey(id)) ids.add(byBlock.get(id));
          });
      docs.add(
          new RawIndexDocument(
              chunk.id,
              chunk.content,
              chunk.normalized,
              chunk.headingPath,
              chunk.sourceBlockIds,
              chunk.pageStart,
              chunk.pageEnd,
              chunk.contentHash,
              vectors.get(i),
              List.copyOf(ids),
              chunk.metadata));
    }
    return List.copyOf(docs);
  }

  private static String renderParts(List<Part> parts) {
    return render(
        parts.get(0).headings, String.join("\n\n", parts.stream().map(part -> part.body).toList()));
  }

  private static String render(List<String> headings, String body) {
    return KnowledgeDomain.contextualEvidenceContent(headings, body);
  }

  private static boolean evidenceBlock(RawBlock block) {
    return RawEvidencePolicy.hasSemanticBody(block);
  }

  private static boolean structuralBoundary(String text) {
    return text.matches("^(第[一二三四五六七八九十百千〇零两0-9]+[章节条款]|[一二三四五六七八九十百]+、|[0-9]{1,3}[.．、])\\s*.*");
  }

  private static String blockText(ParsedBlock block) {
    if ("table".equals(block.type())) {
      String html =
          !safe(block.tableBody()).isBlank() ? block.tableBody() : safe(block.tableHtml());
      List<String> context = new ArrayList<>();
      for (String key : List.of("table_caption", "table_footnote")) {
        Object value = block.raw() == null ? null : block.raw().get(key);
        if (value instanceof List<?> values)
          values.forEach(
              item -> {
                if (item instanceof String text && !text.isBlank()) context.add(text);
              });
      }
      return (String.join("\n", context) + (context.isEmpty() ? "" : "\n") + html).trim();
    }
    if ("image".equals(block.type()) && safe(block.text()).isBlank())
      return safe(block.imagePath()).isBlank() ? "" : "[image] " + block.imagePath();
    return safe(block.text()).trim();
  }

  static String normalize(String value) {
    return safe(value).replace("\r\n", "\n").replace('\r', '\n').trim();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private record Part(
      String body,
      List<String> headings,
      List<String> sourceIds,
      Integer pageStart,
      Integer pageEnd,
      String type) {
    Part withBody(String value) {
      return new Part(value, headings, sourceIds, pageStart, pageEnd, type);
    }
  }

  private record Cell(String text, int originRow, int originCol, boolean first) {}

  private record Span(Cell cell, int remaining) {}

  record ChunkDraft(
      String id,
      String documentId,
      String versionId,
      String content,
      String normalized,
      List<String> headingPath,
      List<String> sourceBlockIds,
      Integer pageStart,
      Integer pageEnd,
      String contentHash,
      int tokenCount,
      Metadata metadata,
      String structureType) {
    ChunkDraft(
        String id,
        String documentId,
        String versionId,
        String content,
        String normalized,
        List<String> headingPath,
        List<String> sourceBlockIds,
        Integer pageStart,
        Integer pageEnd,
        String contentHash,
        int tokenCount,
        Metadata metadata) {
      this(
          id,
          documentId,
          versionId,
          content,
          normalized,
          headingPath,
          sourceBlockIds,
          pageStart,
          pageEnd,
          contentHash,
          tokenCount,
          metadata,
          "UNKNOWN");
    }
  }
}
