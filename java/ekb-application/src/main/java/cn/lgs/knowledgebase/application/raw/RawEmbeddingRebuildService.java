package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpoint;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpointRepository;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rebuilds raw vectors from the durable RawBlock/Evidence source.
 *
 * <p>This service deliberately never reads the original upload and never invokes the parser. It can
 * therefore repair a historical embedding strategy without creating a new document version or
 * touching the evidence source of truth. Chunks are indexed in inactive/preserved-active form,
 * stale IDs are removed only after every vector has been written, and the version is activated in
 * OpenSearch only after the complete rebuild succeeds.
 */
public final class RawEmbeddingRebuildService {
  private static final String CHECKPOINT_STRATEGY = "raw-smart-chunk-full-content-v1";
  private final CompilationRepository compilation;
  private final RawSearchIndex search;
  private final EmbeddingPort embedder;
  private final EmbeddingRebuildCheckpointRepository checkpoints;

  public RawEmbeddingRebuildService(
      CompilationRepository compilation, RawSearchIndex search, EmbeddingPort embedder) {
    this(compilation, search, embedder, new NoopCheckpointRepository());
  }

  public RawEmbeddingRebuildService(
      CompilationRepository compilation,
      RawSearchIndex search,
      EmbeddingPort embedder,
      EmbeddingRebuildCheckpointRepository checkpoints) {
    this.compilation = require(compilation, "compilation repository");
    this.search = require(search, "raw search index");
    this.embedder = require(embedder, "embedder");
    this.checkpoints = require(checkpoints, "embedding rebuild checkpoint repository");
  }

  public RebuildResult rebuild(String versionId) {
    String id = requireText(versionId, "version id");
    VersionSource source = compilation.loadVersionSource(id);
    if (!id.equals(source.versionId()))
      throw new IllegalStateException(
          "compilation repository returned a different version source: " + source.versionId());
    if (source.blocks().isEmpty())
      throw new IllegalStateException("cannot rebuild a version without RawBlocks: " + id);
    List<Evidence> evidence = new ArrayList<>(source.evidenceByBlock().values());
    if (evidence.isEmpty())
      throw new IllegalStateException("cannot rebuild a version without Raw Evidence: " + id);
    if (evidence.stream().anyMatch(item -> !id.equals(item.versionId())))
      throw new IllegalStateException(
          "version source contains evidence from another version: " + id);

    List<RawKnowledgeBuilder.ChunkDraft> chunks =
        RawKnowledgeBuilder.smartChunks(
            source.documentId(), source.versionId(), source.metadata(), source.blocks());
    if (chunks.isEmpty())
      throw new IllegalStateException("cannot rebuild a version without evidence chunks: " + id);

    search.ensureIndex(embedder.dimensions());
    int batchSize = Math.max(1, Math.min(embedder.preferredBatchSize(), 256));
    Set<String> keepChunkIds = new LinkedHashSet<>();
    Map<String, String> evidenceByBlock = new HashMap<>();
    for (Evidence item : evidence) evidenceByBlock.put(item.blockId(), item.id());
    Map<String, ChunkState> expected = new java.util.LinkedHashMap<>();
    StringBuilder fingerprint = new StringBuilder();
    for (RawKnowledgeBuilder.ChunkDraft chunk : chunks) {
      keepChunkIds.add(chunk.id());
      List<String> evidenceIds = evidenceIds(chunk, evidenceByBlock);
      if (evidenceIds.isEmpty())
        throw new IllegalStateException("rebuild produced an ungrounded raw chunk: " + chunk.id());
      String evidenceFingerprint = sha256(String.join("|", evidenceIds));
      ChunkState state =
          new ChunkState(chunk.contentHash(), evidenceFingerprint, String.join(",", evidenceIds));
      if (expected.put(chunk.id(), state) != null)
        throw new IllegalStateException("rebuild produced duplicate chunk id: " + chunk.id());
      fingerprint
          .append(chunk.id())
          .append(':')
          .append(chunk.contentHash())
          .append(':')
          .append(evidenceFingerprint)
          .append(';');
    }

    Set<String> completed = completedChunks(id, expected);
    List<RawKnowledgeBuilder.ChunkDraft> pending =
        chunks.stream().filter(chunk -> !completed.contains(chunk.id())).toList();
    int from = 0;
    while (from < pending.size()) {
      int to = Math.min(pending.size(), from + batchSize);
      List<RawKnowledgeBuilder.ChunkDraft> batch = pending.subList(from, to);
      // Use the exact persisted chunk content. There is intentionally no truncation, summary,
      // head/tail selection, or ellipsis insertion in this path.
      List<String> texts = batch.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
      List<List<Float>> vectors = embedder.embed(texts);
      if (vectors.size() != batch.size())
        throw new IllegalStateException(
            "embedding count " + vectors.size() + " != chunks " + batch.size());
      List<RawIndexDocument> indexed =
          RawKnowledgeBuilder.toIndexDocuments(batch, evidence, vectors);
      for (RawIndexDocument document : indexed) {
        if (document.content() == null || document.content().isBlank())
          throw new IllegalStateException("rebuild produced an empty raw chunk: " + document.id());
        if (document.evidenceIds() == null || document.evidenceIds().isEmpty())
          throw new IllegalStateException(
              "rebuild produced an ungrounded raw chunk: " + document.id());
      }
      search.indexRawRebuild(source.documentId(), source.versionId(), indexed);
      // The index write intentionally happens before the checkpoint write. If the process stops
      // between the two operations, the next run may repeat this batch, but it can never skip a
      // chunk whose vector was not durably written.
      checkpoints.markSucceeded(
          batch.stream()
              .map(
                  chunk -> {
                    ChunkState state = expected.get(chunk.id());
                    return new EmbeddingRebuildCheckpoint(
                        id,
                        chunk.id(),
                        state.contentHash(),
                        state.evidenceFingerprint(),
                        state.evidenceIds(),
                        CHECKPOINT_STRATEGY);
                  })
              .toList());
      from = to;
    }

    // The previous active index is still available if any step above failed. Only now remove IDs
    // that are no longer produced by the current deterministic chunk builder.
    search.deleteVersionExcept(source.versionId(), keepChunkIds);
    search.activateVersion(source.documentId(), source.versionId());
    return new RebuildResult(
        source.documentId(),
        source.versionId(),
        chunks.size(),
        evidence.size(),
        fingerprint.toString());
  }

  private Set<String> completedChunks(String versionId, Map<String, ChunkState> expected) {
    List<EmbeddingRebuildCheckpoint> stored = checkpoints.find(versionId);
    if (stored == null || stored.isEmpty()) return Set.of();
    Set<String> completed = new LinkedHashSet<>();
    for (EmbeddingRebuildCheckpoint checkpoint : stored) {
      ChunkState state = expected.get(checkpoint.chunkId());
      if (state == null) continue;
      if (versionId.equals(checkpoint.versionId())
          && CHECKPOINT_STRATEGY.equals(checkpoint.strategyKey())
          && state.contentHash().equals(checkpoint.contentHash())
          && state.evidenceFingerprint().equals(checkpoint.evidenceFingerprint())
          && state.evidenceIds().equals(checkpoint.evidenceIds())) {
        completed.add(checkpoint.chunkId());
      }
    }
    return completed;
  }

  private static List<String> evidenceIds(
      RawKnowledgeBuilder.ChunkDraft chunk, Map<String, String> evidenceByBlock) {
    return chunk.sourceBlockIds().stream()
        .map(evidenceByBlock::get)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .sorted()
        .collect(Collectors.toList());
  }

  private record ChunkState(String contentHash, String evidenceFingerprint, String evidenceIds) {}

  private static final class NoopCheckpointRepository
      implements EmbeddingRebuildCheckpointRepository {
    @Override
    public List<EmbeddingRebuildCheckpoint> find(String versionId) {
      return List.of();
    }

    @Override
    public void markSucceeded(List<EmbeddingRebuildCheckpoint> checkpoints) {}
  }

  public record RebuildResult(
      String documentId, String versionId, int chunkCount, int evidenceCount, String fingerprint) {}

  private static String requireText(String value, String name) {
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) throw new IllegalArgumentException(name + " is required");
    return text;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
