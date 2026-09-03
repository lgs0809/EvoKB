package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.GraphRelationRejection;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpoint;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingRebuildCheckpointRepository;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RawEmbeddingRebuildServiceTest {
  @Test
  void rebuildEmbedsTheCompletePersistedChunkAndActivatesOnlyAfterSuccess() {
    Fixture fixture = new Fixture(false);
    RawEmbeddingRebuildService.RebuildResult result = fixture.service.rebuild("v1");

    assertEquals(1, result.chunkCount());
    assertEquals(
        fixture.source.blocks().get(0).content().length() + 3,
        fixture.embedder.texts.get(0).length());
    assertTrue(fixture.embedder.texts.get(0).contains("完整正文"));
    assertEquals(1, fixture.index.rebuildDocuments.size());
    assertFalse(fixture.index.rebuildDocuments.get(0).content().contains("…"));
    assertEquals(Set.of(fixture.index.rebuildDocuments.get(0).id()), fixture.index.keepChunkIds);
    assertTrue(fixture.index.activated);
  }

  @Test
  void embeddingFailureLeavesExistingIndexUntouched() {
    Fixture fixture = new Fixture(true);
    try {
      fixture.service.rebuild("v1");
    } catch (RuntimeException expected) {
      assertTrue(expected.getMessage().contains("embedding unavailable"));
    }
    assertTrue(fixture.index.rebuildDocuments.isEmpty());
    assertFalse(fixture.index.activated);
    assertTrue(fixture.index.keepChunkIds.isEmpty());
  }

  @Test
  void restartReusesOnlyAnEvidenceBoundCheckpoint() {
    RecordingCheckpointRepository checkpoints = new RecordingCheckpointRepository();
    Fixture fixture = new Fixture(false, checkpoints);
    fixture.service.rebuild("v1");

    RecordingEmbedder resumedEmbedder = new RecordingEmbedder(false);
    RecordingIndex resumedIndex = new RecordingIndex();
    RawEmbeddingRebuildService resumed =
        new RawEmbeddingRebuildService(
            new Repository(fixture.source), resumedIndex, resumedEmbedder, checkpoints);

    resumed.rebuild("v1");

    assertTrue(resumedEmbedder.texts.isEmpty());
    assertTrue(resumedIndex.rebuildDocuments.isEmpty());
    assertTrue(resumedIndex.activated);
  }

  @Test
  void checkpointWithDifferentEvidenceIsIgnoredAndReembedded() {
    RecordingCheckpointRepository checkpoints = new RecordingCheckpointRepository();
    Fixture fixture = new Fixture(false, checkpoints);
    RawKnowledgeBuilder.ChunkDraft chunk =
        RawKnowledgeBuilder.smartChunks(
                fixture.source.documentId(),
                fixture.source.versionId(),
                fixture.source.metadata(),
                fixture.source.blocks())
            .get(0);
    checkpoints.markSucceeded(
        List.of(
            new EmbeddingRebuildCheckpoint(
                "v1",
                chunk.id(),
                chunk.contentHash(),
                "different-evidence-fingerprint",
                "different-evidence-id",
                "raw-smart-chunk-full-content-v1")));

    RecordingEmbedder embedder = new RecordingEmbedder(false);
    new RawEmbeddingRebuildService(
            new Repository(fixture.source), new RecordingIndex(), embedder, checkpoints)
        .rebuild("v1");

    assertEquals(1, embedder.texts.size());
  }

  private static final class Fixture {
    final VersionSource source;
    final RecordingEmbedder embedder;
    final RecordingIndex index = new RecordingIndex();
    final RecordingCheckpointRepository checkpoints;
    final RawEmbeddingRebuildService service;

    Fixture(boolean failEmbedding) {
      this(failEmbedding, new RecordingCheckpointRepository());
    }

    Fixture(boolean failEmbedding, RecordingCheckpointRepository checkpoints) {
      Metadata metadata = new Metadata(null, null, List.of(), "万科", null, "REPORT");
      String body = "这是一段用于验证完整向量输入的完整正文，历史策略曾经只取两端。";
      RawBlock block =
          new RawBlock(
              "rb-1",
              "v1",
              0,
              "text",
              body,
              body,
              List.of("治理"),
              1,
              1,
              List.of(),
              List.of(),
              List.of("rb-1"),
              "hash",
              20);
      Evidence evidence = Evidence.fromBlock("d1", "original/d1/v1.pdf", block);
      source =
          new VersionSource(
              "d1",
              "v1",
              "vanke.pdf",
              "REPORT",
              metadata,
              List.of(block),
              Map.of(block.id(), evidence));
      embedder = new RecordingEmbedder(failEmbedding);
      this.checkpoints = checkpoints;
      service =
          new RawEmbeddingRebuildService(new Repository(source), index, embedder, checkpoints);
    }
  }

  private static final class RecordingEmbedder implements Ports.EmbeddingPort {
    final boolean fail;
    final List<String> texts = new ArrayList<>();

    RecordingEmbedder(boolean fail) {
      this.fail = fail;
    }

    @Override
    public int dimensions() {
      return 3;
    }

    @Override
    public int preferredBatchSize() {
      return 1;
    }

    @Override
    public List<List<Float>> embed(List<String> values) {
      if (fail) throw new IllegalStateException("embedding unavailable");
      texts.addAll(values);
      return List.of(List.of(1f, 0f, 0f));
    }
  }

  private static final class RecordingIndex implements RawSearchIndex {
    final List<RawIndexDocument> rebuildDocuments = new ArrayList<>();
    Set<String> keepChunkIds = Set.of();
    boolean activated;

    @Override
    public void ensureIndex(int embeddingDimensions) {}

    @Override
    public void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks) {}

    @Override
    public void indexRawRebuild(
        String documentId, String versionId, List<RawIndexDocument> chunks) {
      rebuildDocuments.addAll(chunks);
    }

    @Override
    public void deleteVersionExcept(String versionId, Set<String> keepChunkIds) {
      this.keepChunkIds = keepChunkIds;
    }

    @Override
    public void activateVersion(String documentId, String versionId) {
      activated = true;
    }

    @Override
    public void deactivateVersion(String versionId) {}

    @Override
    public void deleteVersion(String versionId) {}

    @Override
    public void updateMetadata(String versionId, Metadata metadata) {}

    @Override
    public List<Candidate> searchBm25(String query, SearchFilters filters, int limit) {
      return List.of();
    }

    @Override
    public List<Candidate> searchVector(List<Float> vector, SearchFilters filters, int limit) {
      return List.of();
    }
  }

  private static final class RecordingCheckpointRepository
      implements EmbeddingRebuildCheckpointRepository {
    final Map<String, EmbeddingRebuildCheckpoint> stored = new LinkedHashMap<>();

    @Override
    public List<EmbeddingRebuildCheckpoint> find(String versionId) {
      return stored.values().stream().filter(item -> item.versionId().equals(versionId)).toList();
    }

    @Override
    public void markSucceeded(List<EmbeddingRebuildCheckpoint> checkpoints) {
      if (checkpoints == null) return;
      for (EmbeddingRebuildCheckpoint checkpoint : checkpoints) {
        stored.put(checkpoint.versionId() + ":" + checkpoint.chunkId(), checkpoint);
      }
    }
  }

  private static final class Repository implements CompilationRepository {
    private final VersionSource source;

    Repository(VersionSource source) {
      this.source = source;
    }

    @Override
    public VersionSource loadVersionSource(String versionId) {
      return source;
    }

    @Override
    public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {}

    @Override
    public void saveSummary(SummaryRecord summary) {}

    @Override
    public Optional<SummaryRecord> findSummary(String summaryId) {
      return Optional.empty();
    }

    @Override
    public List<SummaryRecord> loadSummaries(List<String> summaryIds) {
      return List.of();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForVersion(String versionId) {
      return List.of();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForCompany(String company) {
      return List.of();
    }

    @Override
    public void replaceConceptsForSummary(
        String summaryId, List<CompilationContracts.ConceptRecord> concepts) {}

    @Override
    public List<CompilationContracts.ConceptRecord> loadActiveConceptsForSummary(String summaryId) {
      return List.of();
    }

    @Override
    public List<CompilationContracts.ConceptRecord> loadActiveConceptsForVersion(String versionId) {
      return List.of();
    }

    @Override
    public Entity resolveEntity(String name, String entityType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void replaceGraphRejections(String summaryId, List<GraphRelationRejection> rejections) {}

    @Override
    public void saveGraph(
        String summaryId,
        List<Entity> entities,
        List<Relation> relations,
        List<EntityResolution> resolutions) {}

    @Override
    public long nextWikiVersion(String wikiId) {
      return 1;
    }

    @Override
    public void saveWiki(WikiRecord wiki) {}

    @Override
    public DerivedContext derivedContextForSummaries(List<String> summaryIds) {
      throw new UnsupportedOperationException();
    }
  }
}
