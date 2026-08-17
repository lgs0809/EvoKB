package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.util.ArrayList;
import java.util.List;

final class RawKnowledgeBuilder {
  static final int DEFAULT_TARGET_TOKENS = 900;
  static final int DEFAULT_OVERLAP_TOKENS = 135;
  static final int DEFAULT_BOUNDARY_WINDOW = 200;

  private RawKnowledgeBuilder() {}

  static List<RawBlock> rawBlocks(String versionId, List<ParsedBlock> parsed) {
    List<RawBlock> blocks = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    List<ParsedBlock> source = parsed == null ? List.of() : parsed;
    for (int i = 0; i < source.size(); i++) {
      ParsedBlock item = source.get(i);
      String text = blockText(item);
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
    for (RawBlock block : blocks) {
      if (block.content() == null || block.content().isBlank()) continue;
      boolean heading =
          "text".equals(block.blockType())
              && !block.headingPath().isEmpty()
              && block.normalized().equals(block.headingPath().get(block.headingPath().size() - 1));
      if (heading && currentTokens >= target - boundary && !current.isEmpty()) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
      }
      if ("table".equals(block.blockType())
          && !current.isEmpty()
          && currentTokens + block.tokenCount() > target) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
      }
      if (!"table".equals(block.blockType())
          && !current.isEmpty()
          && currentTokens + block.tokenCount() > target + boundary) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
      }
      current.add(block);
      currentTokens += block.tokenCount();
      if ("table".equals(block.blockType()) && currentTokens >= target) {
        FlushResult flushed = flush(documentId, versionId, metadata, out, current, overlap);
        current = flushed.carry();
        currentTokens = flushed.tokens();
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
    int tokens = 0;
    for (RawBlock block : parts) {
      contents.add(block.content());
      sourceIds.add(block.id());
      tokens += block.tokenCount();
      if (!block.headingPath().isEmpty()) heading = block.headingPath();
      if (block.pageStart() != null && (pageStart == null || block.pageStart() < pageStart))
        pageStart = block.pageStart();
      if (block.pageEnd() != null && (pageEnd == null || block.pageEnd() > pageEnd))
        pageEnd = block.pageEnd();
    }
    String content = String.join("\n\n", contents);
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
