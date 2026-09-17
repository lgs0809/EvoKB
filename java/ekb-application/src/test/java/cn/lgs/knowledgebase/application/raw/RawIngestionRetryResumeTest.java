package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeRepository;
import cn.lgs.knowledgebase.application.port.Ports.ObjectInfo;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.ParsedArtifactEncoder;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import cn.lgs.knowledgebase.application.port.Ports.StoredObject;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Document;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RawIngestionRetryResumeTest {
  @Test
  void embeddingFailureRetryReusesPersistedParsedBlocksWithoutCallingParser() {
    String documentId = "doc-1";
    String versionId = "version-1";
    Document document = new Document(documentId, "治理制度", "upload");
    DocumentVersion failed =
        new DocumentVersion(
            versionId,
            documentId,
            1,
            VersionLifecycle.FAILED,
            "rules.pdf",
            "application/pdf",
            128,
            "sha-1",
            "original/doc-1/version-1/rules.pdf",
            "",
            "",
            "",
            "");
    List<RawBlock> parsedBlocks =
        List.of(
            new RawBlock(
                "rb-heading",
                versionId,
                0,
                "text",
                "第二章 人员组成",
                "第二章 人员组成",
                List.of("第二章 人员组成"),
                1,
                1,
                List.of(100d, 400d, 800d, 440d),
                List.of(),
                List.of("rb-heading"),
                "heading-hash",
                8),
            new RawBlock(
                "rb-body",
                versionId,
                1,
                "text",
                "第四条 委员会成员由三名董事组成，其中独立董事二名。",
                "第四条 委员会成员由三名董事组成，其中独立董事二名。",
                List.of("第二章 人员组成"),
                1,
                1,
                List.of(100d, 480d, 850d, 510d),
                List.of(),
                List.of("rb-body"),
                "body-hash",
                26));

    FakeKnowledgeRepository repository =
        new FakeKnowledgeRepository(document, failed, parsedBlocks);
    MemoryObjectStore objects = new MemoryObjectStore();
    CapturingSearchIndex search = new CapturingSearchIndex();
    CountingParser parser = new CountingParser();
    FileSourcePort files =
        new FileSourcePort() {
          @Override
          public SourceFile inspect(String path) {
            return new SourceFile(path, "rules.pdf", 128, "sha-1", "application/pdf");
          }

          @Override
          public InputStream open(String path) {
            return new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public boolean exists(String path) {
            return true;
          }

          @Override
          public void delete(String path) {}
        };
    ParsedArtifactEncoder encoder =
        parsed -> {
          throw new AssertionError("artifact encoder must not run when parsed state is reusable");
        };
    EmbeddingPort embedder =
        new EmbeddingPort() {
          @Override
          public int dimensions() {
            return 3;
          }

          @Override
          public List<List<Float>> embed(List<String> texts) {
            return texts.stream().map(text -> List.of(0.1f, 0.2f, 0.3f)).toList();
          }
        };
    RawIngestionApplicationService service =
        new RawIngestionApplicationService(
            files, objects, repository, parser, encoder, search, embedder, null);
    List<ProgressStage> progress = new ArrayList<>();

    var result =
        service.ingest(
            new RawIngestionApplicationService.IngestCommand(
                "staging/rules.pdf",
                "治理制度",
                "web-upload:rules.pdf",
                new Metadata("董事会办公室", "公司治理", List.of("万科"), "万科", null, "POLICY")),
            progress::add);

    assertTrue(result.retried());
    assertEquals(versionId, result.versionId());
    assertEquals(0, parser.calls);
    assertTrue(repository.preserveParsed);
    assertFalse(repository.saveParsedCalled);
    assertTrue(repository.activated);
    assertTrue(search.activated);
    assertFalse(search.deletedBeforeRetry);
    assertFalse(progress.contains(ProgressStage.PARSING));
    assertTrue(
        progress.containsAll(
            List.of(
                ProgressStage.EVIDENCE,
                ProgressStage.EMBEDDING,
                ProgressStage.INDEXING,
                ProgressStage.ACTIVATING)));
    assertFalse(search.indexed.isEmpty());
    RawIndexDocument indexed = search.indexed.get(0);
    assertTrue(indexed.content().startsWith("第二章 人员组成\n第四条"));
    assertEquals(List.of("第二章 人员组成"), indexed.headingPath());
    assertEquals(1, indexed.evidenceIds().size());
  }

  private static final class CountingParser implements ParserPort {
    int calls;

    @Override
    public ParsedDocument parse(String filename, InputStream source) {
      calls++;
      throw new AssertionError("parser must not run when persisted parsed blocks are reusable");
    }
  }

  private static final class FakeKnowledgeRepository implements KnowledgeRepository {
    final Document document;
    final DocumentVersion failed;
    final List<RawBlock> blocks;
    boolean preserveParsed;
    boolean saveParsedCalled;
    boolean activated;

    FakeKnowledgeRepository(Document document, DocumentVersion failed, List<RawBlock> blocks) {
      this.document = document;
      this.failed = failed;
      this.blocks = blocks;
    }

    @Override
    public Optional<Document> findDocumentByLogicalName(String logicalName) {
      return Optional.of(document);
    }

    @Override
    public Optional<DocumentVersion> findVersionBySha(String documentId, String sha256) {
      return Optional.of(failed);
    }

    @Override
    public Optional<DocumentVersion> currentActiveVersion(String documentId) {
      return Optional.empty();
    }

    @Override
    public long nextVersionNo(String documentId) {
      return 2;
    }

    @Override
    public void createPendingVersion(
        Document document, DocumentVersion version, Metadata metadata) {
      throw new AssertionError("retry must not create a new version");
    }

    @Override
    public void resetFailedVersion(String versionId, Metadata metadata, boolean preserveParsed) {
      this.preserveParsed = preserveParsed;
    }

    @Override
    public List<RawBlock> rawBlocks(String versionId) {
      return blocks;
    }

    @Override
    public void saveParsed(
        String versionId,
        String markdownKey,
        String structuredKey,
        String parserName,
        String parserVersion,
        List<RawBlock> blocks,
        List<Evidence> evidence) {
      saveParsedCalled = true;
      throw new AssertionError("persisted parsed state must not be rewritten on resumed retry");
    }

    @Override
    public void activateVersion(String documentId, String versionId) {
      activated = true;
    }

    @Override
    public void markVersionFailed(String versionId, String code, String message) {}

    @Override
    public List<Evidence> resolveEvidence(List<String> evidenceIds) {
      return List.of();
    }
  }

  private static final class CapturingSearchIndex implements RawSearchIndex {
    boolean deletedBeforeRetry;
    boolean activated;
    List<RawIndexDocument> indexed = List.of();

    @Override
    public void ensureIndex(int embeddingDimensions) {}

    @Override
    public void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks) {
      indexed = List.copyOf(chunks);
    }

    @Override
    public void activateVersion(String documentId, String versionId) {
      activated = true;
    }

    @Override
    public void deactivateVersion(String versionId) {}

    @Override
    public void deleteVersion(String versionId) {
      deletedBeforeRetry = true;
    }

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

  private static final class MemoryObjectStore implements ObjectStore {
    final Map<String, byte[]> data = new LinkedHashMap<>();

    @Override
    public void put(String key, InputStream source, long size, String contentType) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.transferTo(out);
        data.put(key, out.toByteArray());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public StoredObject get(String key) {
      byte[] value = data.getOrDefault(key, new byte[0]);
      return new StoredObject(
          new ByteArrayInputStream(value), value.length, "application/octet-stream");
    }

    @Override
    public ObjectInfo stat(String key) {
      byte[] value = data.getOrDefault(key, new byte[0]);
      return new ObjectInfo(key, value.length, "application/octet-stream", "etag");
    }

    @Override
    public String presignGet(String key, Duration expiry) {
      return "https://example.test/" + key;
    }

    @Override
    public void delete(String key) {
      data.remove(key);
    }
  }
}
