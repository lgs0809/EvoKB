package cn.lgs.knowledgebase.application.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawExecution;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/** Executes one already-claimed Raw task; callers own delivery and post-commit effects. */
public final class ClaimedRawIngestionExecutor {
  private final UploadRepository uploads;
  private final IngestionOperation ingestion;
  private final Clock clock;

  public ClaimedRawIngestionExecutor(
      UploadRepository uploads, RawIngestionApplicationService raw, Clock clock) {
    this(
        uploads,
        (command, parserTaskId, parserTaskSink, execution) ->
            raw.ingest(command, null, parserTaskId, parserTaskSink, execution),
        clock);
    Objects.requireNonNull(raw, "raw ingestion service");
  }

  ClaimedRawIngestionExecutor(UploadRepository uploads, IngestionOperation ingestion, Clock clock) {
    this.uploads = Objects.requireNonNull(uploads, "upload repository");
    this.ingestion = Objects.requireNonNull(ingestion, "raw ingestion operation");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RawIngestionApplicationService.IngestResult execute(
      RawTaskClaim claim, String sourceReference) {
    Objects.requireNonNull(claim, "raw task claim");
    var file = claim.file();
    var fields = file.metadata();
    var metadata =
        new Metadata(
            fields.sourceDepartment(),
            fields.materialCategory(),
            fields.manualTags(),
            fields.company(),
            fields.documentDate(),
            fields.documentType());
    var result =
        ingestion.ingest(
            new RawIngestionApplicationService.IngestCommand(
                file.stagingPath(),
                fields.logicalName(),
                sourceReference,
                metadata,
                fields.securityLevel()),
            uploads.parserTaskId(file.fileId()),
            id -> uploads.updateParserTaskId(file.fileId(), id, clock.instant()),
            uploads.rawExecution(claim));
    // Repository owns the exact owner/epoch/DB deadline and publication guard.
    uploads.rawComplete(claim, result.documentId(), result.versionId());
    return result;
  }

  @FunctionalInterface
  interface IngestionOperation {
    RawIngestionApplicationService.IngestResult ingest(
        RawIngestionApplicationService.IngestCommand command,
        String parserTaskId,
        Consumer<String> parserTaskSink,
        RawExecution execution);
  }
}
