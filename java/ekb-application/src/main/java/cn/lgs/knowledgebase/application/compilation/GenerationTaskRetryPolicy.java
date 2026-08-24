package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/** A durable job retry budget above the dependency adapter's short transport retries. */
public final class GenerationTaskRetryPolicy {
  public static final int MAX_ATTEMPTS = 5;
  private static final long[] RETRY_SECONDS = {10, 30, 120, 300};

  private GenerationTaskRetryPolicy() {}

  public static Optional<Instant> nextAttemptAt(
      GenerationTask task, RuntimeException failure, Instant now) {
    if (task.attempt() >= MAX_ATTEMPTS) return Optional.empty();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof TransientDependencyFailure
          || cause instanceof ModelOutputRejectedException) {
        if (task.attempt() < 1) throw new IllegalArgumentException("claimed attempt is required");
        long seconds = RETRY_SECONDS[task.attempt() - 1];
        return Optional.of(now.plusSeconds(seconds));
      }
    }
    return Optional.empty();
  }
}
