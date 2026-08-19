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

  private static DocumentVersion version() {
    return new DocumentVersion(
        "version-1",
        "doc-1",
        1,
        VersionLifecycle.ACTIVE,
        "original.txt",
        "text/plain",
        42,
        "verified-sha",
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
    var files =
        (FileSourcePort)
            Proxy.newProxyInstance(
                FileSourcePort.class.getClassLoader(),
                new Class<?>[] {FileSourcePort.class},
                (p, m, a) -> {
                  if (m.getName().equals("inspect"))
                    return new SourceFile(
                        "original.txt", "original.txt", 42, "verified-sha", "text/plain");
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
                  throw new AssertionError("unexpected source mutation: " + m.getName());
                });
    return new RawIngestionApplicationService(
        files,
        unused(ObjectStore.class),
        metadata,
        unused(ParserPort.class),
        unused(ParsedArtifactEncoder.class),
        unused(RawSearchIndex.class),
        unused(EmbeddingPort.class),
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
