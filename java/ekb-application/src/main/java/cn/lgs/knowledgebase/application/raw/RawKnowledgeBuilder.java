package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RawKnowledgeBuilder {
  static final int DEFAULT_TARGET_TOKENS = 512;
  static final int DEFAULT_OVERLAP_TOKENS = 64;
  static final int DEFAULT_BOUNDARY_WINDOW = 96;
  // HTML tables contain many tags/attributes that are much more expensive for the embedding
  // tokenizer than the parser's token estimate suggests. Keep the ordinary-table fast path, but
  // force long HTML payloads through the bounded row splitter as well.
  private static final int MAX_TABLE_FRAGMENT_CHARS = 1200;

  private static final java.util.Set<String> AUXILIARY_BLOCK_TYPES =
      java.util.Set.of("header", "footer", "page_number");
  private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("(?is)<tr\\b[^>]*>.*?</tr\\s*>");
  private static final Pattern TABLE_CELL_PATTERN = Pattern.compile("(?is)<t[dh]\\b");

  private RawKnowledgeBuilder() {}

  static List<RawBlock> rawBlocks(String versionId, List<ParsedBlock> parsed) {
    List<RawBlock> blocks = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    List<ParsedBlock> source = parsed == null ? List.of() : parsed;
    for (int i = 0; i < source.size(); i++) {
      ParsedBlock item = source.get(i);
      if (isAuxiliary(item.type())) continue;
      String text = blockText(item);
      if (text.isBlank()) continue;
      if ("text".equals(item.type())
          && item.textLevel() > 0
          && item.text() != null
          && !item.text().isBlank()) {
        int level = Math.max(1, item.textLevel());
        if (level > headings.size() + 1) level = headings.size() + 1;
        if (level <= headings.size()) headings = new ArrayList<>(headings.subList(0, level - 1));
        headings.add(normalize(item.text()));
      }
      String normalized = normalize(text);
      String idSeed = versionId + ":" + i + ":" + safe(item.type()) + ":" + normalized;
      String id = "rb_" + sha256(idSeed).substring(0, 40);
      Integer page = item.page() == null || item.page() < 0 ? null : item.page() + 1;
      blocks.add(
          new RawBlock(
              id,
              versionId,
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
              sha256(text),
              estimateTokens(text)));
    }
    return List.copyOf(blocks);
  }

  static List<Evidence> evidence(
      String documentId, String originalObjectKey, List<RawBlock> blocks) {
    return blocks.stream()
        .filter(RawKnowledgeBuilder::isEvidenceBlock)
        .map(block -> Evidence.fromBlock(documentId, originalObjectKey, block))
        .toList();
  }

  static List<ChunkDraft> smartChunks(
      String documentId, String versionId, Metadata metadata, List<RawBlock> blocks) {
    return smartChunks(
        documentId,
        versionId,
        metadata,
        blocks,
        DEFAULT_TARGET_TOKENS,
        DEFAULT_OVERLAP_TOKENS,
        DEFAULT_BOUNDARY_WINDOW);
  }

  static List<ChunkDraft> smartChunks(
      String documentId,
      String versionId,
      Metadata metadata,
      List<RawBlock> blocks,
      int target,
      int overlap,
      int boundary) {
    if (target <= 0) target = DEFAULT_TARGET_TOKENS;
    if (overlap < 0) overlap = 0;
    if (boundary < 0) boundary = 0;
    List<ChunkDraft> out = new ArrayList<>();
    List<RawBlock> current = new ArrayList<>();
    int currentTokens = 0;
    boolean currentHasEvidence = false;
    for (RawBlock block : blocks) {
      if (block.content() == null || block.content().isBlank() || isAuxiliary(block.blockType()))
        continue;
      boolean heading = isHeading(block);
      boolean semanticBoundary = heading || startsStructuralBoundary(block);

      // Structural boundaries outrank token boundaries. Do not carry overlap across a new chapter,
      // section, article, or other clearly numbered unit: the previous section should not dilute
      // the
      // next unit's retrieval meaning.
      if (semanticBoundary && currentHasEvidence && !current.isEmpty()) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, 0);
        current = flushed.carry();
        currentTokens = flushed.tokens();
        currentHasEvidence = hasEvidenceBlock(current);
      }

      // A parser block can itself contain an entire long paragraph/section. Block-level packing
      // alone
      // would therefore leave a multi-thousand-token chunk intact. Preserve the structural heading
      // context, but fall back to bounded text splitting inside that one semantic unit. Ordinary
      // tables stay atomic; oversized tables are handled below by complete-row partitioning.
      if (!heading
          && !"table".equals(block.blockType())
          && block.tokenCount() > target + boundary) {
        List<RawBlock> headingContext =
            currentHasEvidence
                ? List.of()
                : current.stream().filter(RawKnowledgeBuilder::isHeading).toList();
        if (currentHasEvidence) flush(documentId, versionId, metadata, out, current, 0);
        out.addAll(
            splitOversizedBlock(
                documentId, versionId, metadata, headingContext, block, target, overlap, boundary));
        current = new ArrayList<>();
        currentTokens = 0;
        currentHasEvidence = false;
        continue;
      }

      // Keep ordinary tables atomic, but never send an unbounded HTML table to the embedding
      // model. Annual reports can contain a single project/guarantee table with tens of thousands
      // of tokens. Such a table is split only between complete <tr> rows (with its header rows
      // repeated), so every emitted chunk remains a self-contained table fragment and still
      // points back to the same raw evidence block.
      if ("table".equals(block.blockType())
          && (block.tokenCount() > target + boundary
              || safe(block.content()).length() > MAX_TABLE_FRAGMENT_CHARS)) {
        List<RawBlock> headingContext =
            currentHasEvidence
                ? List.of()
                : current.stream().filter(RawKnowledgeBuilder::isHeading).toList();
        if (currentHasEvidence) flush(documentId, versionId, metadata, out, current, 0);
        out.addAll(
            splitOversizedTableBlock(
                documentId, versionId, metadata, headingContext, block, target, boundary));
        current = new ArrayList<>();
        currentTokens = 0;
        currentHasEvidence = false;
        continue;
      }

      if ("table".equals(block.blockType())
          && !current.isEmpty()
          && currentTokens + block.tokenCount() > target) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
        currentHasEvidence = hasEvidenceBlock(current);
      }
      if (!heading
          && !"table".equals(block.blockType())
          && !current.isEmpty()
          && currentTokens + block.tokenCount() > target + boundary) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
        currentHasEvidence = hasEvidenceBlock(current);
      }
      current.add(block);
      currentTokens += block.tokenCount();
      currentHasEvidence |= isEvidenceBlock(block);
      if ("table".equals(block.blockType()) && currentTokens >= target) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
        currentHasEvidence = hasEvidenceBlock(current);
      }
    }
    if (!current.isEmpty()) flush(documentId, versionId, metadata, out, current, overlap);
    return List.copyOf(out);
  }

  static List<RawIndexDocument> toIndexDocuments(
      List<ChunkDraft> chunks, List<Evidence> evidence, List<List<Float>> vectors) {
    if (chunks.size() != vectors.size())
      throw new IllegalArgumentException("embedding count mismatch");
    java.util.Map<String, String> evidenceByBlock = new java.util.HashMap<>();
    evidence.forEach(item -> evidenceByBlock.put(item.blockId(), item.id()));
    List<RawIndexDocument> docs = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      ChunkDraft chunk = chunks.get(i);
      java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
      chunk
          .sourceBlockIds()
          .forEach(
              blockId -> {
                String id = evidenceByBlock.get(blockId);
                if (id != null && !id.isBlank()) ids.add(id);
              });
      docs.add(
          new RawIndexDocument(
              chunk.id(),
              chunk.content(),
              chunk.normalized(),
              chunk.headingPath(),
              chunk.sourceBlockIds(),
              chunk.pageStart(),
              chunk.pageEnd(),
              chunk.contentHash(),
              vectors.get(i),
              List.copyOf(ids),
              chunk.metadata()));
    }
    return List.copyOf(docs);
  }

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
      Metadata metadata) {}

  private static FlushResult flush(
      String documentId,
      String versionId,
      Metadata metadata,
      List<ChunkDraft> out,
      List<RawBlock> current,
      int overlap) {
    if (current.isEmpty()) return new FlushResult(List.of(), 0);
    if (!hasEvidenceBlock(current))
      return new FlushResult(new ArrayList<>(current), tokenCount(current));
    out.add(makeChunk(documentId, versionId, metadata, current));
    if (overlap == 0) return new FlushResult(new ArrayList<>(), 0);
    List<RawBlock> carryReverse = new ArrayList<>();
    int tokens = 0;
    for (int i = current.size() - 1; i >= 0; i--) {
      RawBlock block = current.get(i);
      if ("table".equals(block.blockType()) && !carryReverse.isEmpty()) break;
      if (tokens + block.tokenCount() > overlap && !carryReverse.isEmpty()) break;
      carryReverse.add(block);
      tokens += block.tokenCount();
    }
    java.util.Collections.reverse(carryReverse);
    return new FlushResult(new ArrayList<>(carryReverse), tokens);
  }

  private static ChunkDraft makeChunk(
      String documentId, String versionId, Metadata metadata, List<RawBlock> parts) {
    List<String> contents = new ArrayList<>();
    List<String> sourceIds = new ArrayList<>();
    List<String> heading = List.of();
    Integer pageStart = null, pageEnd = null;
    for (RawBlock block : parts) {
      sourceIds.add(block.id());
      if (!block.headingPath().isEmpty()) heading = block.headingPath();
      if (isEvidenceBlock(block)) contents.add(block.content());
      if (block.pageStart() != null && (pageStart == null || block.pageStart() < pageStart))
        pageStart = block.pageStart();
      if (block.pageEnd() != null && (pageEnd == null || block.pageEnd() > pageEnd))
        pageEnd = block.pageEnd();
    }
    String body = String.join("\n\n", contents);
    String content =
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
            heading, body);
    int tokens = estimateTokens(content);
    String hash = sha256(content);
    String seed = versionId + ":" + String.join(",", sourceIds) + ":" + hash;
    return new ChunkDraft(
        "ch_" + sha256(seed).substring(0, 40),
        documentId,
        versionId,
        content,
        normalize(content),
        List.copyOf(heading),
        List.copyOf(sourceIds),
        pageStart,
        pageEnd,
        hash,
        tokens,
        metadata);
  }

  private static List<ChunkDraft> splitOversizedBlock(
      String documentId,
      String versionId,
      Metadata metadata,
      List<RawBlock> headingContext,
      RawBlock block,
      int target,
      int overlap,
      int boundary) {
    List<String> sourceIds = new ArrayList<>();
    Integer pageStart = block.pageStart();
    Integer pageEnd = block.pageEnd();
    for (RawBlock context : headingContext) {
      sourceIds.add(context.id());
      if (context.pageStart() != null && (pageStart == null || context.pageStart() < pageStart))
        pageStart = context.pageStart();
      if (context.pageEnd() != null && (pageEnd == null || context.pageEnd() > pageEnd))
        pageEnd = context.pageEnd();
    }
    sourceIds.add(block.id());

    String headingOnly =
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
            block.headingPath(), "");
    int headingTokens = estimateTokens(headingOnly);
    int bodyTarget = Math.max(1, target - headingTokens);
    int bodyBoundary = Math.min(boundary, Math.max(0, bodyTarget - 1));
    int bodyOverlap = Math.min(overlap, Math.max(0, bodyTarget - 1));
    List<String> pieces = splitText(block.content(), bodyTarget, bodyOverlap, bodyBoundary);
    List<ChunkDraft> chunks = new ArrayList<>(pieces.size());
    for (int i = 0; i < pieces.size(); i++) {
      String content =
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
              block.headingPath(), pieces.get(i));
      String hash = sha256(content);
      String seed = versionId + ":" + String.join(",", sourceIds) + ":part:" + i + ":" + hash;
      chunks.add(
          new ChunkDraft(
              "ch_" + sha256(seed).substring(0, 40),
              documentId,
              versionId,
              content,
              normalize(content),
              List.copyOf(block.headingPath()),
              List.copyOf(sourceIds),
              pageStart,
              pageEnd,
              hash,
              estimateTokens(content),
              metadata));
    }
    return List.copyOf(chunks);
  }

  private static List<ChunkDraft> splitOversizedTableBlock(
      String documentId,
      String versionId,
      Metadata metadata,
      List<RawBlock> headingContext,
      RawBlock block,
      int target,
      int boundary) {
    String table = trim(block.content());
    Matcher matcher = TABLE_ROW_PATTERN.matcher(table);
    List<String> rows = new ArrayList<>();
    while (matcher.find()) rows.add(matcher.group());

    // MinerU normally emits HTML rows. If a provider returns a malformed table, retain the same
    // deterministic bounded-splitting fallback used for oversized text rather than allowing one
    // pathological payload to block the whole raw-ingestion task.
    if (rows.isEmpty()) {
      return splitOversizedTableFragments(
          documentId, versionId, metadata, headingContext, block, table, target, boundary);
    }

    List<String> heading = block.headingPath();
    if (heading == null || heading.isEmpty()) {
      heading =
          headingContext == null || headingContext.isEmpty()
              ? List.of()
              : headingContext.get(headingContext.size() - 1).headingPath();
    }
    String headingOnly =
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
            heading, "");
    int bodyTarget = Math.max(1, target - estimateTokens(headingOnly));
    int headerCount = tableHeaderRowCount(rows);
    List<String> headers = rows.subList(0, headerCount);
    List<List<String>> groups = new ArrayList<>();
    List<String> current = new ArrayList<>();
    for (int i = headerCount; i < rows.size(); i++) {
      String row = rows.get(i);
      List<String> candidate = new ArrayList<>(current);
      candidate.add(row);
      String candidateTable = tableFragment(headers, candidate);
      int candidateTokens =
          estimateTokens(
              cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
                  heading, candidateTable));
      if (!current.isEmpty()
          && (candidateTokens > bodyTarget || candidateTable.length() > MAX_TABLE_FRAGMENT_CHARS)) {
        groups.add(List.copyOf(current));
        current = new ArrayList<>();
      }
      current.add(row);
    }
    if (!current.isEmpty()) groups.add(List.copyOf(current));
    if (groups.isEmpty()) groups.add(List.of());

    List<String> sourceIds = new ArrayList<>();
    if (headingContext != null) headingContext.forEach(context -> sourceIds.add(context.id()));
    sourceIds.add(block.id());
    List<ChunkDraft> chunks = new ArrayList<>(groups.size());
    for (int i = 0; i < groups.size(); i++) {
      String content = tableFragment(headers, groups.get(i));
      String contextual =
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
              heading, content);
      String hash = sha256(contextual);
      String seed = versionId + ":" + String.join(",", sourceIds) + ":table-part:" + i + ":" + hash;
      chunks.add(
          new ChunkDraft(
              "ch_" + sha256(seed).substring(0, 40),
              documentId,
              versionId,
              contextual,
              normalize(contextual),
              List.copyOf(heading),
              List.copyOf(sourceIds),
              block.pageStart(),
              block.pageEnd(),
              hash,
              estimateTokens(contextual),
              metadata));
    }
    return List.copyOf(chunks);
  }

  private static List<ChunkDraft> splitOversizedTableFragments(
      String documentId,
      String versionId,
      Metadata metadata,
      List<RawBlock> headingContext,
      RawBlock block,
      String table,
      int target,
      int boundary) {
    List<String> heading = block.headingPath();
    if (heading == null || heading.isEmpty()) {
      heading =
          headingContext == null || headingContext.isEmpty()
              ? List.of()
              : headingContext.get(headingContext.size() - 1).headingPath();
    }
    String headingOnly =
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
            heading, "");
    int bodyTarget = Math.max(1, target - estimateTokens(headingOnly));
    int bodyBoundary = Math.min(boundary, Math.max(0, bodyTarget - 1));
    List<String> pieces = splitText(table, bodyTarget, 0, bodyBoundary);
    List<String> sourceIds = new ArrayList<>();
    if (headingContext != null) headingContext.forEach(context -> sourceIds.add(context.id()));
    sourceIds.add(block.id());
    List<ChunkDraft> chunks = new ArrayList<>(pieces.size());
    for (int i = 0; i < pieces.size(); i++) {
      String contextual =
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent(
              heading, pieces.get(i));
      String hash = sha256(contextual);
      String seed =
          versionId + ":" + String.join(",", sourceIds) + ":table-fragment:" + i + ":" + hash;
      chunks.add(
          new ChunkDraft(
              "ch_" + sha256(seed).substring(0, 40),
              documentId,
              versionId,
              contextual,
              normalize(contextual),
              List.copyOf(heading),
              List.copyOf(sourceIds),
              block.pageStart(),
              block.pageEnd(),
              hash,
              estimateTokens(contextual),
              metadata));
    }
    return List.copyOf(chunks);
  }

  private static int tableHeaderRowCount(List<String> rows) {
    if (rows.size() < 2) return Math.min(1, rows.size());
    int first = countTableCells(rows.get(0));
    int second = countTableCells(rows.get(1));
    return first > 0 && first <= 2 && second > first ? 2 : 1;
  }

  private static int countTableCells(String row) {
    int count = 0;
    Matcher matcher = TABLE_CELL_PATTERN.matcher(row);
    while (matcher.find()) count++;
    return count;
  }

  private static String tableFragment(List<String> headers, List<String> rows) {
    List<String> all = new ArrayList<>(headers.size() + rows.size());
    all.addAll(headers);
    all.addAll(rows);
    return "<table>" + String.join("", all) + "</table>";
  }

  private static List<String> splitText(
      String value, int targetTokens, int overlapTokens, int boundaryTokens) {
    String text = trim(value);
    if (text.isEmpty()) return List.of();
    if (estimateTokens(text) <= targetTokens) return List.of(text);

    List<String> pieces = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int hardEnd = maxEndForTokens(text, start, targetTokens);
      if (hardEnd <= start) hardEnd = nextCodePointOffset(text, start);
      int end = hardEnd;
      if (hardEnd < text.length() && boundaryTokens > 0) {
        int preferredFloor =
            maxEndForTokens(text, start, Math.max(1, targetTokens - boundaryTokens));
        int boundaryEnd = findBoundaryEnd(text, Math.max(start + 1, preferredFloor), hardEnd);
        if (boundaryEnd > start) end = boundaryEnd;
      }

      String piece = text.substring(start, end).trim();
      if (!piece.isEmpty()) pieces.add(piece);
      if (end >= text.length()) break;

      int nextStart = end;
      if (overlapTokens > 0) {
        nextStart = suffixStartForTokens(text, start, end, overlapTokens);
        nextStart = moveForwardToBoundary(text, nextStart, end);
      }
      if (nextStart <= start) nextStart = end;
      start = nextStart;
    }
    return List.copyOf(pieces);
  }

  private static int maxEndForTokens(String text, int start, int tokenLimit) {
    int low = start + 1;
    int high = text.length();
    int best = start;
    while (low <= high) {
      int mid = safeCharBoundary(text, low + (high - low) / 2);
      if (mid <= start) mid = nextCodePointOffset(text, start);
      int tokens = estimateTokens(text.substring(start, mid));
      if (tokens <= tokenLimit) {
        best = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return best;
  }

  private static int suffixStartForTokens(String text, int floor, int end, int tokenLimit) {
    int low = floor;
    int high = end;
    int best = end;
    while (low <= high) {
      int mid = safeCharBoundary(text, low + (high - low) / 2);
      int tokens = estimateTokens(text.substring(mid, end));
      if (tokens <= tokenLimit) {
        best = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }
    return best;
  }

  private static int findBoundaryEnd(String text, int floor, int ceiling) {
    for (int i = Math.min(ceiling, text.length()) - 1; i >= floor; i--) {
      char ch = text.charAt(i);
      if (ch == '\n'
          || ch == '。'
          || ch == '！'
          || ch == '？'
          || ch == '；'
          || ch == '.'
          || ch == '!'
          || ch == '?'
          || ch == ';') return i + 1;
    }
    return ceiling;
  }

  private static int moveForwardToBoundary(String text, int start, int end) {
    for (int i = start; i < end; i++) {
      char ch = text.charAt(i);
      if (Character.isWhitespace(ch)) return i + 1;
      if (ch == '。' || ch == '！' || ch == '？' || ch == '；') return i + 1;
    }
    return start;
  }

  private static int safeCharBoundary(String text, int offset) {
    int bounded = Math.max(0, Math.min(offset, text.length()));
    if (bounded > 0
        && bounded < text.length()
        && Character.isLowSurrogate(text.charAt(bounded))
        && Character.isHighSurrogate(text.charAt(bounded - 1))) return bounded - 1;
    return bounded;
  }

  private static int nextCodePointOffset(String text, int start) {
    if (start >= text.length()) return text.length();
    return start + Character.charCount(text.codePointAt(start));
  }

  private record FlushResult(List<RawBlock> carry, int tokens) {}

  private static String blockText(ParsedBlock item) {
    String type = safe(item.type());
    return switch (type) {
      case "text", "equation", "interline_equation" -> trim(item.text());
      case "table" ->
          !trim(item.tableBody()).isEmpty() ? trim(item.tableBody()) : trim(item.tableHtml());
      case "image" ->
          !trim(item.text()).isEmpty()
              ? trim(item.text())
              : (!trim(item.imagePath()).isEmpty() ? "[image] " + trim(item.imagePath()) : "");
      default -> trim(item.text());
    };
  }

  private static boolean isAuxiliary(String blockType) {
    return AUXILIARY_BLOCK_TYPES.contains(
        safe(blockType).trim().toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean isHeading(RawBlock block) {
    return "text".equals(block.blockType())
        && !block.headingPath().isEmpty()
        && block.normalized().equals(block.headingPath().get(block.headingPath().size() - 1));
  }

  private static boolean isEvidenceBlock(RawBlock block) {
    return !isAuxiliary(block.blockType()) && !isHeading(block);
  }

  private static boolean startsStructuralBoundary(RawBlock block) {
    if (!"text".equals(block.blockType())) return false;
    String text = normalize(block.content());
    if (text.isEmpty()) return false;
    return text.matches("^(第[一二三四五六七八九十百千〇零两0-9]+[章节条款]|[一二三四五六七八九十百]+、|[0-9]{1,3}[.．、])\\s*.*");
  }

  private static boolean hasEvidenceBlock(List<RawBlock> blocks) {
    return blocks.stream().anyMatch(RawKnowledgeBuilder::isEvidenceBlock);
  }

  private static int tokenCount(List<RawBlock> blocks) {
    return blocks.stream().mapToInt(RawBlock::tokenCount).sum();
  }

  private static int estimateTokens(String text) {
    int cjk = 0, latin = 0, other = 0;
    for (int offset = 0; offset < text.length(); ) {
      int cp = text.codePointAt(offset);
      offset += Character.charCount(cp);
      Character.UnicodeScript script = Character.UnicodeScript.of(cp);
      if (script == Character.UnicodeScript.HAN
          || script == Character.UnicodeScript.HANGUL
          || script == Character.UnicodeScript.HIRAGANA
          || script == Character.UnicodeScript.KATAKANA) cjk++;
      else if (cp <= 127 && Character.isLetterOrDigit(cp)) latin++;
      else if (!Character.isWhitespace(cp)) other++;
    }
    int tokens = cjk + (latin + 3) / 4 + (other + 2) / 3;
    return tokens == 0 && !text.isEmpty() ? 1 : tokens;
  }

  private static String normalize(String value) {
    return trim(value).replaceAll("\\s+", " ");
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> safeList(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
