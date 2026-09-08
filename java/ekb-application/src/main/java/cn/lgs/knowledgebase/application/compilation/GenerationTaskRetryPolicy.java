package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.application.task.DurableRetryBackoff;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/** Durable retry scheduling above a dependency adapter's separately bounded request retries. */
public final class GenerationTaskRetryPolicy {

  private GenerationTaskRetryPolicy() {}

  public static Optional<Instant> nextAttemptAt(
      GenerationTask task, RuntimeException failure, Instant now) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof TransientDependencyFailure
          || cause instanceof ModelOutputRejectedException
          || cause instanceof CompilationExecutionBudget.StageDeadlineExpired) {
        if (task.attempt() < 1) throw new IllegalArgumentException("claimed attempt is required");
        return Optional.of(DurableRetryBackoff.next(task.taskId(), task.attempt(), now));
      }
    }
    return Optional.empty();
  }
}
