package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.application.raw.RawEmbeddingRebuildService;
import cn.lgs.knowledgebase.application.raw.RawEmbeddingRebuildService.RebuildResult;
import cn.lgs.knowledgebase.application.raw.RawExecution;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawPublicationWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/**
 * Single-file-at-a-time worker for durable raw-vector repairs. It is intentionally separate from
 * the Summary/Concept/Graph/Wiki worker so a large re-embedding run cannot starve derived knowledge
 * generation.
 */
public final class DurableEmbeddingRebuildWorker implements SmartLifecycle {
  private final GenerationTaskRepository tasks;
  private final RawEmbeddingRebuildService rebuild;
  private final Duration pollInterval;
  private final Clock clock;
  private final JdbcRawPublicationWriter publicationWriter;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;

  public DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks, RawEmbeddingRebuildService rebuild, Duration pollInterval) {
    this(tasks, rebuild, pollInterval, Clock.systemUTC());
  }

  public DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks,
      RawEmbeddingRebuildService rebuild,
      Duration pollInterval,
      JdbcRawPublicationWriter publicationWriter) {
    this(
        tasks,
        rebuild,
        pollInterval,
        Clock.systemUTC(),
        require(publicationWriter, "raw publication writer"));
  }

  DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks,
      RawEmbeddingRebuildService rebuild,
      Duration pollInterval,
      Clock clock) {
    this(tasks, rebuild, pollInterval, clock, null);
  }

  private DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks,
      RawEmbeddingRebuildService rebuild,
      Duration pollInterval,
      Clock clock,
      JdbcRawPublicationWriter publicationWriter) {
    this.tasks = require(tasks, "generation task repository");
    this.rebuild = require(rebuild, "raw embedding rebuild service");
    this.pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
    this.clock = require(clock, "clock");
    this.publicationWriter = publicationWriter;
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    tasks.resetInterrupted(List.of(GenerationTaskType.EMBEDDING_REBUILD), clock.instant());
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "evokb-embedding-rebuild-worker");
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
    return 105;
  }

  /** Visible for deterministic tests and an operational manual drain. */
  public int runOneCycle() {
    List<GenerationTask> claimed = tasks.claimPendingEmbeddingRebuild(1, clock.instant());
    for (GenerationTask task : claimed) process(task);
    return claimed.size();
  }

  private void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        if (runOneCycle() == 0) Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException ignored) {
        try {
          Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void process(GenerationTask task) {
    try {
      RebuildResult result =
          publicationWriter == null
              ? rebuild.rebuild(task.versionId())
              : rebuild.rebuild(task.versionId(), execution(task));
      tasks.complete(
          task,
          KnowledgeDomain.sha256(result.fingerprint() + "|chunks=" + result.chunkCount()),
          clock.instant());
    } catch (RuntimeException failure) {
      // Leave an interrupted stage RUNNING until its database deadline expires.
      // A different process starting is never evidence that the old executor has stopped.
      if (Thread.currentThread().isInterrupted()) return;
      GenerationTaskFailureHandler.persist(
          tasks, task, "EMBEDDING_REBUILD_FAILED", failure, clock.instant());
    }
  }

  private RawExecution execution(GenerationTask task) {
    var gate = tasks.publication(task);
    gate.captureInputs(List.of(task.versionId()));
    return new RawExecution() {
      private String documentId;
      private long aclVersion;

      public String projectionScope() {
        return task.taskId() + ":e" + task.executionEpoch();
      }

      public void check() {
        tasks.withinPublication(task, () -> {});
      }

      public void phase(ProgressStage stage) {
        check();
      }

      public void bind(String document, String version) {
        if (!task.versionId().equals(version))
          throw new IllegalStateException("rebuild source differs from claimed task");
        gate.publish(
            () -> {
              long current = publicationWriter.sourceAcl(document, version);
              if (current != task.sourceAclVersion())
                throw new IllegalStateException("rebuild source permission changed");
              if (documentId != null && (!documentId.equals(document) || aclVersion != current))
                throw new IllegalStateException("rebuild input binding cannot change");
              documentId = document;
              aclVersion = current;
            });
      }

      public void fenced(Runnable mutation) {
        gate.publish(mutation);
      }

      public void publish(String document, String version, List<String> ids, Runnable mutation) {
        if (documentId == null || !documentId.equals(document) || !task.versionId().equals(version))
          throw new IllegalStateException("rebuild publication source has not been bound");
        gate.publish(
            () ->
                publicationWriter.publish(
                    projectionScope(),
                    null,
                    task.taskId(),
                    task.executionEpoch(),
                    document,
                    version,
                    aclVersion,
                    ids,
                    mutation));
      }
    };
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
