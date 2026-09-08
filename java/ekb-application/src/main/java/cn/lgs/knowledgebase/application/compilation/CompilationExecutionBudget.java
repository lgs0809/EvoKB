package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed attempt budget shared by external calls; opening it never extends a database claim. */
public final class CompilationExecutionBudget implements AutoCloseable {
  private static final ThreadLocal<CompilationExecutionBudget> CURRENT = new ThreadLocal<>();
  private final CompilationExecutionBudget previous;
  private final Instant deadline;
  private final CompilationPublication publication;
  private final Clock clock;
  private final Thread owner;
  private final GenerationTask task;
  private boolean closed;

  private CompilationExecutionBudget(
      Instant deadline, CompilationPublication publication, Clock clock, GenerationTask task) {
    this.previous = CURRENT.get();
    this.deadline = deadline;
    this.publication = Objects.requireNonNull(publication, "compilation publication");
    this.clock = Objects.requireNonNull(clock, "execution clock");
    this.owner = Thread.currentThread();
    this.task = task;
    CURRENT.set(this);
  }

  public static CompilationExecutionBudget open(
      Instant deadline, CompilationPublication publication, Clock clock) {
    return new CompilationExecutionBudget(deadline, publication, clock, null);
  }

  public static CompilationExecutionBudget open(
      GenerationTask task, CompilationPublication publication, Clock clock) {
    Objects.requireNonNull(task, "claimed compilation task");
    return new CompilationExecutionBudget(task.stageDeadline(), publication, clock, task);
  }

  /** The original claim identity for bounded, source-scoped audit receipts; never credentials. */
  public static java.util.Optional<GenerationTask> execution() {
    for (var scope = CURRENT.get(); scope != null; scope = scope.previous)
      if (scope.task != null) return java.util.Optional.of(scope.task);
    return java.util.Optional.empty();
  }

  /** Clients keep their normal configuration when no durable compilation owns the call. */
  public static boolean active() {
    return CURRENT.get() != null;
  }

  /** Outside a durable compilation, the caller retains its existing configured timeout. */
  public static Duration limit(Duration configured) {
    return bound(configured, true);
  }

  /** Transaction timeout setup must not re-enter the publication authority's own JDBC check. */
  public static Duration remaining(Duration configured) {
    return bound(configured, false);
  }

  private static Duration bound(Duration configured, boolean checkAuthority) {
    if (configured == null || configured.isZero() || configured.isNegative())
      throw new IllegalArgumentException("positive external request budget is required");
    var current = CURRENT.get();
    if (current == null) return configured;
    Duration budget = configured;
    for (var scope = current; scope != null; scope = scope.previous) {
      if (checkAuthority) scope.checkCurrent();
      if (scope.deadline != null) {
        Duration remaining = Duration.between(scope.clock.instant(), scope.deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new StageDeadlineExpired();
        if (remaining.compareTo(budget) < 0) budget = remaining;
      }
    }
    return budget;
  }

  public static void check() {
    var current = CURRENT.get();
    for (var scope = current; scope != null; scope = scope.previous) scope.checkCurrent();
  }

  /** A future's callback uses this exact original authority, independent of callback thread. */
  public static Runnable capturedCheck() {
    var original = CURRENT.get();
    return () -> {
      for (var scope = original; scope != null; scope = scope.previous) scope.checkCurrent();
    };
  }

  private void checkCurrent() {
    if (deadline != null && !clock.instant().isBefore(deadline)) throw new StageDeadlineExpired();
    // Reuse the exact fixed input authority in a short transaction before remote I/O.
    publication.check();
    if (deadline != null && !clock.instant().isBefore(deadline)) throw new StageDeadlineExpired();
  }

  @Override
  public void close() {
    if (Thread.currentThread() != owner || CURRENT.get() != this || closed)
      throw new IllegalStateException("compilation execution scope must close in owner order");
    closed = true;
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }

  public static final class StageDeadlineExpired extends IllegalStateException {
    public StageDeadlineExpired() {
      super("database stage deadline expired; no lease renewal");
    }
  }
}
