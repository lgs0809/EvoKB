package cn.lgs.knowledgebase.application.raw;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Carries the original fixed Raw claim into shared remote clients; never renews ownership. */
public final class RawExecutionBudget implements AutoCloseable {
  private static final ThreadLocal<RawExecutionBudget> CURRENT = new ThreadLocal<>();
  private final RawExecutionBudget previous;
  private final java.util.function.Supplier<java.time.Instant> deadline;
  private final Runnable authority;
  private final Clock clock;
  private final Thread owner = Thread.currentThread();
  private boolean closed;

  private RawExecutionBudget(
      java.util.function.Supplier<java.time.Instant> deadline, Runnable authority, Clock clock) {
    this.deadline = Objects.requireNonNull(deadline);
    this.authority = Objects.requireNonNull(authority);
    this.clock = Objects.requireNonNull(clock);
    this.previous = CURRENT.get();
    CURRENT.set(this);
  }

  public static RawExecutionBudget open(RawTaskClaim claim, RawExecution execution, Clock clock) {
    Objects.requireNonNull(claim);
    Objects.requireNonNull(execution);
    return new RawExecutionBudget(
        () -> {
          var current = execution.stageDeadline();
          return current == null ? claim.deadline() : current;
        },
        execution::check,
        clock);
  }

  /**
   * The authority remains mandatory; opening an observation scope never grants publication rights.
   */
  public static RawExecutionBudget open(
      java.time.Instant deadline, Runnable authority, Clock clock) {
    return new RawExecutionBudget(() -> deadline, authority, clock);
  }

  public static boolean active() {
    return CURRENT.get() != null;
  }

  public static Duration limit(Duration configured) {
    return bound(configured, true);
  }

  /** Transaction setup must not recursively invoke the same authority's JDBC check. */
  public static Duration remaining(Duration configured) {
    return bound(configured, false);
  }

  private static Duration bound(Duration configured, boolean checkAuthority) {
    if (configured == null || configured.isZero() || configured.isNegative())
      throw new IllegalArgumentException("positive external request budget is required");
    Duration result = configured;
    for (var scope = CURRENT.get(); scope != null; scope = scope.previous) {
      if (checkAuthority) scope.checkCurrent();
      Duration remaining = Duration.between(scope.clock.instant(), scope.deadline.get());
      if (remaining.isZero() || remaining.isNegative()) throw expired();
      if (remaining.compareTo(result) < 0) result = remaining;
    }
    return result;
  }

  public static void check() {
    for (var scope = CURRENT.get(); scope != null; scope = scope.previous) scope.checkCurrent();
  }

  public static Runnable capturedCheck() {
    var original = CURRENT.get();
    return () -> {
      for (var scope = original; scope != null; scope = scope.previous) scope.checkCurrent();
    };
  }

  private void checkCurrent() {
    if (!clock.instant().isBefore(deadline.get())) throw expired();
    authority.run();
    if (!clock.instant().isBefore(deadline.get())) throw expired();
  }

  private static IllegalStateException expired() {
    return new IllegalStateException("RAW_STAGE_EXPIRED: original Raw execution deadline expired");
  }

  @Override
  public void close() {
    if (closed || Thread.currentThread() != owner || CURRENT.get() != this)
      throw new IllegalStateException("Raw execution scope must close in owner order");
    closed = true;
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
