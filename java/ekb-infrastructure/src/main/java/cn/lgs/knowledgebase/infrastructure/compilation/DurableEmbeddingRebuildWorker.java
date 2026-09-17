package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.application.raw.RawEmbeddingRebuildService;
import cn.lgs.knowledgebase.application.raw.RawEmbeddingRebuildService.RebuildResult;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
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
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;

  public DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks, RawEmbeddingRebuildService rebuild, Duration pollInterval) {
    this(tasks, rebuild, pollInterval, Clock.systemUTC());
  }

  DurableEmbeddingRebuildWorker(
      GenerationTaskRepository tasks,
      RawEmbeddingRebuildService rebuild,
      Duration pollInterval,
      Clock clock) {
    this.tasks = require(tasks, "generation task repository");
    this.rebuild = require(rebuild, "raw embedding rebuild service");
    this.pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
    this.clock = require(clock, "clock");
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    tasks.resetInterrupted(clock.instant());
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
      RebuildResult result = rebuild.rebuild(task.versionId());
      tasks.complete(
          task,
          KnowledgeDomain.sha256(result.fingerprint() + "|chunks=" + result.chunkCount()),
          clock.instant());
    } catch (RuntimeException failure) {
      // During a graceful application stop leave the row RUNNING. The repository resets it to
      // PENDING on the next startup, preserving the resumable task rather than recording a false
      // terminal failure.
      if (Thread.currentThread().isInterrupted()) return;
      tasks.fail(task.taskId(), "EMBEDDING_REBUILD_FAILED", failure.getMessage(), clock.instant());
    }
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
