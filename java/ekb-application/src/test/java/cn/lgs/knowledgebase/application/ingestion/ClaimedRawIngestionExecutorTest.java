package cn.lgs.knowledgebase.application.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawExecution;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService.IngestResult;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimedRawIngestionExecutorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final IngestResult RESULT =
      new IngestResult(
          "doc",
          "version",
          1,
          false,
          false,
          3,
          2,
          2,
          "original",
          "markdown",
          "structured",
          List.of());

  @Test
  void forwardsAllMetadataAndTheExactClaimBeforeCompleting() {
    var claim = claim(30);
    var calls = new ArrayList<String>();
    var uploads = uploads(claim, calls, null);
    var executor =
        new ClaimedRawIngestionExecutor(
            uploads,
            (command, resume, sink, execution) -> {
              assertEquals("/staging/source.pdf", command.path());
              assertEquals("contract", command.logicalName());
              assertEquals("web-batch:folder/source.pdf", command.sourcePath());
              assertEquals(30, command.securityLevel());
              assertEquals("department", command.metadata().sourceDepartment());
              assertEquals("category", command.metadata().materialCategory());
              assertEquals("company", command.metadata().company());
              assertEquals("CONTRACT", command.metadata().documentType());
              assertEquals(LocalDate.of(2025, 12, 1), command.metadata().documentDate());
              assertEquals(List.of("tag"), command.metadata().manualTags());
              assertEquals("parser-resume", resume);
              calls.add("ingest");
              return RESULT;
            },
            CLOCK);
    assertSame(RESULT, executor.execute(claim, "web-batch:folder/source.pdf"));
    assertEquals(List.of("rawExecution", "ingest", "rawComplete"), calls);
  }

  @Test
  void parserTaskCallbackPersistsTheSameFileAndCallerClock() {
    var claim = claim(30);
    var calls = new ArrayList<String>();
    var executor =
        new ClaimedRawIngestionExecutor(
            uploads(claim, calls, null),
            (command, resume, sink, execution) -> {
              sink.accept("parser-next");
              return RESULT;
            },
            CLOCK);
    executor.execute(claim, "web-upload:source.pdf");
    assertEquals(List.of("rawExecution", "parser:parser-next", "rawComplete"), calls);
  }

  @Test
  void ingestFailureDoesNotCompleteOrApplyCallerPostCommitEffects() {
    var claim = claim(30);
    var calls = new ArrayList<String>();
    var failure = new IllegalStateException("RAW_STALE_CLAIM");
    var executor =
        new ClaimedRawIngestionExecutor(
            uploads(claim, calls, null),
            (command, resume, sink, execution) -> {
              throw failure;
            },
            CLOCK);
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> executor.execute(claim, "web-upload:source.pdf")));
    assertEquals(List.of("rawExecution"), calls);
  }

  @Test
  void completionFailurePropagatesWithoutPretendingTheTaskCommitted() {
    var claim = claim(30);
    var calls = new ArrayList<String>();
    var failure = new IllegalStateException("RAW_SOURCE_REVOKED");
    var executor =
        new ClaimedRawIngestionExecutor(
            uploads(claim, calls, failure), (command, resume, sink, execution) -> RESULT, CLOCK);
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> executor.execute(claim, "web-upload:source.pdf")));
    assertEquals(List.of("rawExecution", "rawComplete"), calls);
  }

  @Test
  void absentClassificationRemainsAbsentAndBothReferencesRemainLiteral() {
    var claim = claim(null);
    var references = new ArrayList<String>();
    var executor =
        new ClaimedRawIngestionExecutor(
            uploads(claim, new ArrayList<>(), null),
            (command, resume, sink, execution) -> {
              assertNull(command.securityLevel());
              references.add(command.sourcePath());
              return RESULT;
            },
            CLOCK);
    executor.execute(claim, "web-upload:source.pdf");
    executor.execute(claim, "web-batch:folder/source.pdf");
    assertEquals(List.of("web-upload:source.pdf", "web-batch:folder/source.pdf"), references);
  }

  private static UploadRepository uploads(
      RawTaskClaim claim, List<String> calls, RuntimeException completionFailure) {
    return (UploadRepository)
        Proxy.newProxyInstance(
            UploadRepository.class.getClassLoader(),
            new Class<?>[] {UploadRepository.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "parserTaskId":
                  assertEquals(claim.file().fileId(), args[0]);
                  return "parser-resume";
                case "rawExecution":
                  assertSame(claim, args[0]);
                  calls.add("rawExecution");
                  return RawExecution
                      .legacy(); // Explicit unit fixture, never runtime ownership proof.
                case "updateParserTaskId":
                  assertEquals(claim.file().fileId(), args[0]);
                  assertEquals(NOW, args[2]);
                  calls.add("parser:" + args[1]);
                  return null;
                case "rawComplete":
                  assertSame(claim, args[0]);
                  assertEquals(RESULT.documentId(), args[1]);
                  assertEquals(RESULT.versionId(), args[2]);
                  calls.add("rawComplete");
                  if (completionFailure != null) throw completionFailure;
                  return null;
                default:
                  throw new AssertionError("Unexpected caller-owned effect: " + method.getName());
              }
            });
  }

  private static RawTaskClaim claim(Integer securityLevel) {
    var metadata =
        new FileMetadata(
            "contract",
            "department",
            "category",
            "company",
            "CONTRACT",
            LocalDate.of(2025, 12, 1),
            List.of("tag"),
            securityLevel);
    var file =
        new FileItem(
            "file",
            "batch",
            "folder/source.pdf",
            "source.pdf",
            "/staging/source.pdf",
            128,
            "sha",
            metadata,
            FileStatus.RUNNING,
            ProgressStage.STORING,
            2,
            "",
            "",
            null,
            "",
            "",
            NOW,
            NOW,
            NOW,
            null);
    return new RawTaskClaim(file, "owner", 3, NOW.plusSeconds(600));
  }
}
