package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Unit boundaries only; PG/Milvus publication and real model paths have independent probes. */
class RawReservedVersionRecoveryTest {
  @Test
  void exactActiveOriginalCannotSilentlyIgnoreAnotherRequestedClassification() {
    var bound = new AtomicBoolean();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service()
                    .ingest(
                        new RawIngestionApplicationService.IngestCommand(
                            "original.txt", "采购合同", "multipart", null, 30),
                        null,
                        "",
                        task -> fail("no parser expected"),
                        execution(true, bound)));
    assertTrue(failure.getMessage().startsWith("INPUT_ERROR"));
    assertFalse(bound.get());
  }

  @Test
  void postPublicationRetryClosesOnlyItsReservedCurrentVersionWithoutRepeatingExternalWork() {
    var bound = new AtomicBoolean();
    var result =
        service()
            .ingest(
                command(),
                null,
                "",
                task -> fail("no parser task expected"),
                execution(true, bound));
    assertTrue(result.reused());
    assertEquals("version-1", result.versionId());
    assertTrue(bound.get());
  }

  @Test
  void activeButRevokedProjectionCannotMasqueradeAsCompletedReservedInput() {
    var bound = new AtomicBoolean();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service()
                    .ingest(
                        command(),
                        null,
                        "",
                        task -> fail("no parser task expected"),
                        execution(false, bound)));
    assertTrue(failure.getMessage().startsWith("RAW_SOURCE_REVOKED"));
    assertFalse(bound.get());
  }

  @Test
  void retainedLegacyActiveWithoutACompleteCheckpointIsNotNewlyCertified() {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service(false)
                    .ingest(
                        command(),
                        null,
                        "",
                        task -> fail("no parse may start during active cache reuse"),
                        execution(true, new AtomicBoolean())));
    assertTrue(failure.getMessage().startsWith("PARSER_CHECKPOINT_UNVERIFIED"));
  }

  private static final String SOURCE_SHA =
      ParserPipelineIdentity.hash("X".repeat(42).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private static final byte[] MARKDOWN =
      "Synthetic published source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] ARTIFACT =
      "{\"fixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final ParserFixture PARSER = new ParserFixture();
  private static final ArtifactFixture ARTIFACTS = new ArtifactFixture();

  private static ParserPipelineIdentity pipeline() {
    return ParserCheckpointPolicy.current(
        PARSER, "original.txt", RawChunkProfile.DEFAULT, RawTestFixtures.TOKENS, ARTIFACTS);
  }

  private static RawBlock parsedBlock() {
    return new RawBlock(
        "block-1",
        "version-1",
        0,
        "text",
        "Synthetic published source",
        "Synthetic published source",
        List.of(),
        null,
        null,
        List.of(),
        List.of(),
        List.of("block-1"),
        ParserPipelineIdentity.hash(MARKDOWN),
        25);
  }

  private static RawParseCheckpoint proof() {
    return new RawParseCheckpoint(
        pipeline(),
        SOURCE_SHA,
        42,
        ParserPipelineIdentity.hash(MARKDOWN),
        ParserPipelineIdentity.hash(ARTIFACT),
        RawParseCheckpoint.blocksSha256(List.of(parsedBlock())));
  }

  private static final class ParserFixture implements ParserPort {
    public Optional<ParserPipelineIdentity> currentPipeline(String filename) {
      return Optional.of(
          new ParserPipelineIdentity(
              java.util.Map.of(
                  "parser.name",
                  "synthetic-unit-native",
                  "parser.version",
                  "1",
                  "parser.format",
                  ".txt",
                  "runtime.sha256",
                  ParserPipelineIdentity.classSha256(ParserFixture.class))));
    }

    public ParsedDocument parse(String filename, java.io.InputStream source) {
      throw new AssertionError("current complete checkpoint needs no new parse");
    }
  }

  private static final class ArtifactFixture implements ParsedArtifactEncoder {
    public byte[] structuredArtifact(ParsedDocument parsed) {
      throw new AssertionError("current complete checkpoint needs no new artifact");
    }
  }

  private static DocumentVersion version() {
    return new DocumentVersion(
        "version-1",
        "doc-1",
        1,
        VersionLifecycle.ACTIVE,
        "original.txt",
        "text/plain",
        42,
        SOURCE_SHA,
        "upload-sessions/enterprise/u/s/original",
        "parsed.md",
        "structured.json",
        "mineru",
        "3.4.5");
  }

  private static RawIngestionApplicationService.IngestCommand command() {
    return new RawIngestionApplicationService.IngestCommand(
        "original.txt", "采购合同", "multipart", null);
  }

  private static RawExecution execution(boolean current, AtomicBoolean bound) {
    return new RawExecution() {
      public String projectionScope() {
        return "file-1:e2";
      }

      public void check() {}

      public void phase(ProgressStage stage) {
        fail("already published input needs no external stage");
      }

      public void bind(String document, String version) {
        assertEquals("doc-1", document);
        assertEquals("version-1", version);
        bound.set(true);
      }

      public void fenced(Runnable mutation) {
        fail("already published input needs no mutation");
      }

      public void publish(String document, String version, List<String> ids, Runnable mutation) {
        fail("already published input needs no publication");
      }

      public Optional<DocumentVersion> reservedInput() {
        return Optional.of(version());
      }

      public boolean completedInputCurrent(String document, String version) {
        return current;
      }
    };
  }

  private static RawIngestionApplicationService service() {
    return service(true);
  }

  private static RawIngestionApplicationService service(boolean certified) {
    var files =
        (FileSourcePort)
            Proxy.newProxyInstance(
                FileSourcePort.class.getClassLoader(),
                new Class<?>[] {FileSourcePort.class},
                (p, m, a) -> {
                  if (m.getName().equals("inspect"))
                    return new SourceFile(
                        "original.txt", "original.txt", 42, SOURCE_SHA, "text/plain");
                  throw new AssertionError("unexpected file operation: " + m.getName());
                });
    var metadata =
        (KnowledgeRepository)
            Proxy.newProxyInstance(
                KnowledgeRepository.class.getClassLoader(),
                new Class<?>[] {KnowledgeRepository.class},
                (p, m, a) -> {
                  if (m.getName().equals("findDocumentByLogicalName"))
                    return Optional.of(new Document("doc-1", "采购合同", "multipart"));
                  if (m.getName().equals("sourceSecurityLevel")) return Optional.of(10);
                  if (m.getName().equals("parseCheckpoint"))
                    return certified ? Optional.of(proof()) : Optional.empty();
                  if (m.getName().equals("rawBlocks")) return List.of(parsedBlock());
                  throw new AssertionError("unexpected source mutation: " + m.getName());
                });
    return new RawIngestionApplicationService(
        files,
        (ObjectStore)
            Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class},
                (p, m, a) -> {
                  if (!m.getName().equals("get"))
                    throw new AssertionError("current cache only verifies immutable object bytes");
                  byte[] bytes = a[0].equals("parsed.md") ? MARKDOWN : ARTIFACT;
                  return new StoredObject(
                      new java.io.ByteArrayInputStream(bytes), bytes.length, "fixture");
                }),
        metadata,
        PARSER,
        ARTIFACTS,
        unused(RawSearchIndex.class),
        unused(EmbeddingPort.class),
        null,
        RawTestFixtures.TOKENS,
        RawChunkProfile.DEFAULT,
        null);
  }

  private static <T> T unused(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              throw new AssertionError(
                  "unexpected external operation: " + type.getSimpleName() + "." + m.getName());
            }));
  }
}
