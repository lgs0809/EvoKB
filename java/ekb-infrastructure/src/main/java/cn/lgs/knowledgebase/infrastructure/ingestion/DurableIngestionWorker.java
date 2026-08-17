package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
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
  private final Duration pollInterval;
  private final int claimLimit;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit) {
    this(uploads, raw, files, pollInterval, claimLimit, Clock.systemUTC());
  }

  DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      Duration pollInterval,
      int claimLimit,
      Clock clock) {
    this.uploads = require(uploads, "upload repository");
    this.raw = require(raw, "raw ingestion service");
    this.files = require(files, "file source");
    this.pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
    this.claimLimit = claimLimit <= 0 ? 1 : Math.min(claimLimit, 32);
    this.clock = require(clock, "clock");
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    uploads.resetInterrupted(clock.instant());
    executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread thread = new Thread(r, "ekb-ingestion-worker");
              thread.setDaemon(true);
              return thread;
            });
    executor.submit(this::loop);
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
              stage -> uploads.updateProgress(file.fileId(), stage, clock.instant()));
      try {
        uploads.complete(file.fileId(), result.documentId(), result.versionId(), clock.instant());
      } catch (RuntimeException persistFailure) {
        uploads.fail(
            file.fileId(), "TASK_PERSIST_FAILED", persistFailure.getMessage(), clock.instant());
        return;
      }
      try {
        files.delete(file.stagingPath());
      } catch (RuntimeException ignored) {
      }
    } catch (RawIngestionApplicationService.RawIngestFailure e) {
      uploads.fail(file.fileId(), e.code(), e.getMessage(), clock.instant());
    } catch (RuntimeException e) {
      uploads.fail(file.fileId(), "INGEST_FAILED", e.getMessage(), clock.instant());
    }
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
