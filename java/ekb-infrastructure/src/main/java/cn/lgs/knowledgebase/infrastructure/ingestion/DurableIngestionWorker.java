package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

public final class DurableIngestionWorker implements SmartLifecycle {
  private final UploadRepository uploads;
  private final RawIngestionApplicationService raw;
  private final FileSourcePort files;
  private final IngestionCompilationTrigger compilationTrigger;
  private final Duration pollInterval;
  private final int claimLimit;
  private final int workerCount;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit) {
    this(uploads, raw, files, pollInterval, claimLimit, 1, Clock.systemUTC(), null);
  }

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit,
      int workerCount) {
    this(uploads, raw, files, pollInterval, claimLimit, workerCount, Clock.systemUTC(), null);
  }

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      GenerationTaskRepository generationTasks,
      Duration pollInterval,
      int claimLimit,
      int workerCount) {
    this(
        uploads,
        raw,
        files,
        pollInterval,
        claimLimit,
        workerCount,
        Clock.systemUTC(),
        generationTasks);
  }

  DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit,
      int workerCount,
      Clock clock) {
    this(uploads, raw, files, pollInterval, claimLimit, workerCount, clock, null);
  }

  DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit,
      int workerCount,
      Clock clock,
      GenerationTaskRepository generationTasks) {
    this.uploads = require(uploads, "upload repository");
    this.raw = require(raw, "raw ingestion service");
    this.files = require(files, "file source");
    this.compilationTrigger =
        generationTasks == null ? null : new IngestionCompilationTrigger(generationTasks);
    this.pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
    this.claimLimit = claimLimit <= 0 ? 1 : Math.min(claimLimit, 32);
    this.workerCount = workerCount <= 0 ? 1 : Math.min(workerCount, 16);
    this.clock = require(clock, "clock");
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    uploads.resetInterrupted(clock.instant());
    var workerNumber = new java.util.concurrent.atomic.AtomicInteger();
    executor =
        Executors.newFixedThreadPool(
            workerCount,
            r -> {
              Thread thread =
                  new Thread(r, "ekb-ingestion-worker-" + workerNumber.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    for (int i = 0; i < workerCount; i++) executor.submit(this::loop);
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) return;
    if (executor != null) executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return 100;
  }

  /** Visible for deterministic tests and operational manual drain. */
  public int runOneCycle() {
    List<FileItem> claimed = uploads.claimQueued(claimLimit, clock.instant());
    for (FileItem file : claimed) process(file);
    return claimed.size();
  }

  private void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        int processed = runOneCycle();
        if (processed == 0) Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        try {
          Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void process(FileItem file) {
    if (!files.exists(file.stagingPath())) {
      uploads.fail(
          file.fileId(), "STAGING_FILE_MISSING", "staged source file is missing", clock.instant());
      return;
    }
    Metadata metadata =
        new Metadata(
            file.metadata().sourceDepartment(),
            file.metadata().materialCategory(),
            file.metadata().manualTags(),
            file.metadata().company(),
            file.metadata().documentDate(),
            file.metadata().documentType());
    try {
      var result =
          raw.ingest(
              new RawIngestionApplicationService.IngestCommand(
                  file.stagingPath(),
                  file.metadata().logicalName(),
                  "web-batch:" + file.relativePath(),
                  metadata),
              stage -> uploads.updateProgress(file.fileId(), stage, clock.instant()),
              uploads.parserTaskId(file.fileId()),
              taskId -> uploads.updateParserTaskId(file.fileId(), taskId, clock.instant()));
      try {
        uploads.complete(file.fileId(), result.documentId(), result.versionId(), clock.instant());
      } catch (RuntimeException persistFailure) {
        uploads.fail(
            file.fileId(), "TASK_PERSIST_FAILED", persistFailure.getMessage(), clock.instant());
        return;
      }
      queueSummaryAfterRawCommit(result.versionId());
      try {
        files.delete(file.stagingPath());
      } catch (RuntimeException ignored) {
      }
    } catch (RawIngestionApplicationService.RawIngestFailure e) {
      if (Thread.currentThread().isInterrupted()) return;
      uploads.fail(file.fileId(), e.code(), e.getMessage(), clock.instant());
    } catch (RuntimeException e) {
      if (Thread.currentThread().isInterrupted()) return;
      uploads.fail(file.fileId(), "INGEST_FAILED", e.getMessage(), clock.instant());
    }
  }

  private void queueSummaryAfterRawCommit(String versionId) {
    if (compilationTrigger != null) compilationTrigger.queueSummary(versionId, clock.instant());
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
