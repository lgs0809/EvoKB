package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.ingestion.ClaimedRawIngestionExecutor;
import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.application.raw.RawDeliveryQueue;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.raw.RawTaskClaim;
import cn.lgs.knowledgebase.application.raw.RawTaskRetryPolicy;
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
  private static final System.Logger LOGGER =
      System.getLogger(DurableIngestionWorker.class.getName());
  private final UploadRepository uploads;
  private final RawIngestionApplicationService raw;
  private final ClaimedRawIngestionExecutor claimedIngestion;
  private final FileSourcePort files;
  private final IngestionCompilationTrigger compilationTrigger;
  private final Duration pollInterval;
  private final int claimLimit;
  private final int workerCount;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;
  private RawDeliveryQueue deliveries;
  private cn.lgs.knowledgebase.application.port.Ports.ObjectStore originalObjects;
  private java.util.concurrent.ScheduledExecutorService recovery;
  private final String ownerId = java.util.UUID.randomUUID().toString();
  private final java.util.concurrent.ArrayBlockingQueue<RawDeliveryQueue.Message>
      recoveredMessages = new java.util.concurrent.ArrayBlockingQueue<>(1000);
  private final java.util.Set<String> recoveredIds =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

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

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      GenerationTaskRepository generationTasks,
      Duration pollInterval,
      int claimLimit,
      int workerCount,
      RawDeliveryQueue deliveries) {
    this(uploads, raw, files, generationTasks, pollInterval, claimLimit, workerCount);
    this.deliveries = require(deliveries, "raw delivery queue");
  }

  public DurableIngestionWorker(
      UploadRepository uploads,
      RawIngestionApplicationService raw,
      FileSourcePort files,
      GenerationTaskRepository generationTasks,
      Duration pollInterval,
      int claimLimit,
      int workerCount,
      RawDeliveryQueue deliveries,
      cn.lgs.knowledgebase.application.port.Ports.ObjectStore originalObjects) {
    this(uploads, raw, files, generationTasks, pollInterval, claimLimit, workerCount, deliveries);
    this.originalObjects = require(originalObjects, "durable original object store");
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
    this.claimedIngestion = new ClaimedRawIngestionExecutor(this.uploads, this.raw, this.clock);
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    if (deliveries != null) {
      recovery =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                var thread = new Thread(r, "ekb-raw-recovery");
                thread.setDaemon(true);
                return thread;
              });
      recovery.scheduleWithFixedDelay(
          () -> {
            try {
              dispatchAndRecover();
            } catch (RuntimeException error) {
              LOGGER.log(
                  System.Logger.Level.WARNING,
                  "raw delivery recovery failed: {0}",
                  error.getClass().getSimpleName());
            }
          },
          0,
          15,
          java.util.concurrent.TimeUnit.SECONDS);
    }
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
    if (recovery != null) recovery.shutdownNow();
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
    if (deliveries != null) {
      List<RawDeliveryQueue.Message> messages = new java.util.ArrayList<>();
      while (messages.size() < claimLimit) {
        var recovered = recoveredMessages.poll();
        if (recovered == null) break;
        recoveredIds.remove(recovered.id());
        messages.add(recovered);
      }
      if (messages.size() < claimLimit)
        messages.addAll(deliveries.read(claimLimit - messages.size()));
      for (var message : messages) processDelivery(message);
      return messages.size();
    }
    List<FileItem> claimed = uploads.claimQueued(claimLimit, clock.instant());
    for (FileItem file : claimed) process(file);
    return claimed.size();
  }

  /** A scanner has fixed intervals but never renews running work or grabs it by PEL idle alone. */
  public int dispatchAndRecover() {
    var dispatched = new java.util.concurrent.atomic.AtomicInteger();
    deliveries.withRecoveryLease(
        () -> {
          uploads.resetInterrupted(clock.instant());
          for (var row : uploads.rawDispatchable(100)) {
            String id = deliveries.append(row.fileId());
            uploads.rawDispatched(row, id);
            dispatched.incrementAndGet();
          }
          for (var message : deliveries.recover(100))
            if (recoveredIds.add(message.id()) && !recoveredMessages.offer(message))
              recoveredIds.remove(message.id());
        });
    return dispatched.get();
  }

  private void processDelivery(RawDeliveryQueue.Message message) {
    if (message.fileId() == null
        || message.fileId().isBlank()
        || uploads.findFile(message.fileId()).isEmpty()) {
      uploads.rawRejectDelivery(message.id(), message.fileId());
      deliveries.acknowledge(message.id());
      return;
    }
    var claim = uploads.claimRaw(message.fileId(), ownerId);
    if (claim.isPresent()) process(claim.get());
    if (uploads.rawAcknowledgeable(message.fileId())) deliveries.acknowledge(message.id());
  }

  private void process(RawTaskClaim claim) {
    FileItem file = claim.file();
    if (!files.exists(file.stagingPath())) {
      try {
        if (!restoreOriginal(claim)) {
          uploads.rawFailure(
              claim, "STAGING_FILE_MISSING", "staged and durable original missing", false);
          return;
        }
      } catch (RuntimeException error) {
        uploads.rawFailure(
            claim,
            "ORIGINAL_RESTORE_FAILED",
            error.getMessage(),
            RawTaskRetryPolicy.retryable("ORIGINAL_RESTORE_FAILED", error));
        return;
      }
    }
    try {
      uploads.rawVerifyOriginal(claim, files.inspect(file.stagingPath()));
    } catch (RuntimeException error) {
      String code = RawTaskRetryPolicy.code(error, "ORIGINAL_INTEGRITY_FAILED");
      uploads.rawFailure(
          claim, code, error.getMessage(), RawTaskRetryPolicy.retryable(code, error));
      return;
    }
    try {
      var result = claimedIngestion.execute(claim, "web-batch:" + file.relativePath());
      queueAssociationAfterRawCommit(result.versionId());
      try {
        files.delete(file.stagingPath());
      } catch (RuntimeException ignored) {
      }
    } catch (RuntimeException error) {
      if (Thread.currentThread().isInterrupted()) return;
      String code = RawTaskRetryPolicy.code(error, "INGEST_FAILED");
      try {
        uploads.rawFailure(
            claim, code, error.getMessage(), RawTaskRetryPolicy.retryable(code, error));
      } catch (RuntimeException stale) {
        LOGGER.log(
            System.Logger.Level.WARNING,
            "raw failure cannot mutate another epoch: {0}",
            stale.getClass().getSimpleName());
      }
    }
  }

  private boolean restoreOriginal(RawTaskClaim claim) {
    if (originalObjects == null) return false;
    var key = uploads.rawOriginalObject(claim.file().fileId());
    if (key.isEmpty()) return false;
    uploads.rawExecution(claim).check();
    java.nio.file.Path temporary = null;
    try {
      var path = java.nio.file.Path.of(claim.file().stagingPath()).toAbsolutePath().normalize();
      java.nio.file.Files.createDirectories(path.getParent());
      temporary = java.nio.file.Files.createTempFile(path.getParent(), "restoring-", ".part");
      var object = originalObjects.get(key.get());
      long size = 0;
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = object.stream();
          var output = java.nio.file.Files.newOutputStream(temporary)) {
        byte[] bytes = new byte[65536];
        int read;
        while ((read = input.read(bytes)) != -1) {
          size += read;
          if (size > claim.file().sizeBytes())
            throw new IllegalStateException("INPUT_ERROR: restored original too large");
          digest.update(bytes, 0, read);
          output.write(bytes, 0, read);
        }
      }
      if (size != claim.file().sizeBytes()
          || (!cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.UNVERIFIED.equals(
                  claim.file().sha256())
              && !java.util.HexFormat.of()
                  .formatHex(digest.digest())
                  .equals(claim.file().sha256())))
        throw new IllegalStateException("INPUT_ERROR: restored original identity differs");
      uploads.rawExecution(claim).check();
      try {
        java.nio.file.Files.createLink(path, temporary);
      } catch (java.nio.file.FileAlreadyExistsException anotherOwner) {
        /* Never overwrite a newer staged file. */
      }
      return files.exists(path.toString());
    } catch (Exception failure) {
      throw new IllegalStateException("cannot restore original from durable object", failure);
    } finally {
      if (temporary != null)
        try {
          java.nio.file.Files.deleteIfExists(temporary);
        } catch (java.io.IOException ignored) {
        }
    }
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
        // Provider bodies and database exception messages may contain document content or secrets.
        LOGGER.log(
            System.Logger.Level.WARNING,
            "ingestion cycle failed: {0}; cause: {1}",
            e.getClass().getSimpleName(),
            e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName());
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
                  metadata,
                  file.metadata().securityLevel()),
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
      queueAssociationAfterRawCommit(result.versionId());
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

  private void queueAssociationAfterRawCommit(String versionId) {
    if (compilationTrigger != null) compilationTrigger.queueAssociation(versionId, clock.instant());
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
