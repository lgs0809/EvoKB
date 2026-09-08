package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompilationExecutionBudgetTest {
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
  private static final Duration CONFIGURED = Duration.ofSeconds(120);

  @Test
  void transactionBudgetSetupDoesNotRecursivelyInvokeItsOwnJdbcAuthority() {
    var clock = new MutableClock();
    var checks = new AtomicInteger();
    try (var scope =
        CompilationExecutionBudget.open(
            NOW.plusSeconds(10), gate(checks::incrementAndGet), clock)) {
      assertEquals(Duration.ofSeconds(10), CompilationExecutionBudget.remaining(CONFIGURED));
      assertEquals(0, checks.get());
      assertEquals(Duration.ofSeconds(10), CompilationExecutionBudget.limit(CONFIGURED));
      assertEquals(1, checks.get());
      clock.now = NOW.plusSeconds(10);
      assertThrows(
          CompilationExecutionBudget.StageDeadlineExpired.class,
          () -> CompilationExecutionBudget.remaining(CONFIGURED));
    }
  }

  @Test
  void noTaskScopePreservesTheExistingConfiguredBudget() {
    assertEquals(CONFIGURED, CompilationExecutionBudget.limit(CONFIGURED));
    assertThrows(
        IllegalArgumentException.class, () -> CompilationExecutionBudget.limit(Duration.ZERO));
  }

  @Test
  void theOriginalAbsoluteDeadlineClampsCallsAndStopsNewCallsAtExpiry() {
    var clock = new MutableClock();
    var checks = new AtomicInteger();
    var gate = gate(checks::incrementAndGet);
    try (var scope = CompilationExecutionBudget.open(NOW.plusSeconds(600), gate, clock)) {
      gate.captureInputs(List.of("source"));
      assertEquals(CONFIGURED, CompilationExecutionBudget.limit(CONFIGURED));
      clock.now = NOW.plusSeconds(500);
      assertEquals(Duration.ofSeconds(100), CompilationExecutionBudget.limit(CONFIGURED));
      clock.now = NOW.plusSeconds(600);
      assertThrows(
          CompilationExecutionBudget.StageDeadlineExpired.class, CompilationExecutionBudget::check);
      assertEquals(2, checks.get());
    }
    assertEquals(CONFIGURED, CompilationExecutionBudget.limit(CONFIGURED));
  }

  @Test
  void nestedScopesCannotExtendTheirParentAndCloseRestoresTheParent() {
    var clock = new MutableClock();
    var gate = gate(() -> {});
    try (var outer = CompilationExecutionBudget.open(NOW.plusSeconds(10), gate, clock)) {
      try (var inner = CompilationExecutionBudget.open(NOW.plusSeconds(100), gate, clock)) {
        assertEquals(Duration.ofSeconds(10), CompilationExecutionBudget.limit(CONFIGURED));
        assertThrows(IllegalStateException.class, outer::close);
      }
      assertEquals(Duration.ofSeconds(10), CompilationExecutionBudget.limit(CONFIGURED));
    }
    assertEquals(CONFIGURED, CompilationExecutionBudget.limit(CONFIGURED));
  }

  @Test
  void permissionFailureRemainsARealAuthorityFailure() {
    var gate =
        gate(
            () -> {
              throw new IllegalStateException("synthetic source revoked");
            });
    try (var scope =
        CompilationExecutionBudget.open(NOW.plusSeconds(600), gate, new MutableClock())) {
      RuntimeException failure =
          assertThrows(
              IllegalStateException.class, () -> CompilationExecutionBudget.limit(CONFIGURED));
      assertFalse(failure instanceof CompilationExecutionBudget.StageDeadlineExpired);
      assertFalse(failure instanceof ModelOutputRejectedException);
      assertEquals("synthetic source revoked", failure.getMessage());
    }
  }

  @Test
  void capturedCallbackChecksTheExactOriginalGateOnAnotherThread() throws Exception {
    var authorized = new AtomicBoolean(true);
    var clock = new MutableClock();
    var gate =
        gate(
            () -> {
              if (!authorized.get()) throw new IllegalStateException("synthetic source revoked");
            });
    Runnable callback;
    try (var scope = CompilationExecutionBudget.open(NOW.plusSeconds(600), gate, clock)) {
      callback = CompilationExecutionBudget.capturedCheck();
    }
    authorized.set(false);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var error =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> executor.submit(callback).get());
      assertEquals("synthetic source revoked", error.getCause().getMessage());
      assertEquals(
          CONFIGURED, executor.submit(() -> CompilationExecutionBudget.limit(CONFIGURED)).get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void capturedCallbackKeepsNestedAbsoluteDeadlinesAndLeavesPooledThreadsClean() throws Exception {
    var clock = new MutableClock();
    Runnable callback;
    try (var outer = CompilationExecutionBudget.open(NOW.plusSeconds(10), gate(() -> {}), clock)) {
      try (var inner =
          CompilationExecutionBudget.open(NOW.plusSeconds(100), gate(() -> {}), clock)) {
        callback = CompilationExecutionBudget.capturedCheck();
      }
    }
    clock.now = NOW.plusSeconds(10);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var error =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> executor.submit(callback).get());
      assertInstanceOf(CompilationExecutionBudget.StageDeadlineExpired.class, error.getCause());
      assertEquals(
          CONFIGURED, executor.submit(() -> CompilationExecutionBudget.limit(CONFIGURED)).get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aPositiveSubMillisecondRemainderIsNotExtendedByTheScope() {
    var clock = new MutableClock();
    try (var scope =
        CompilationExecutionBudget.open(NOW.plusNanos(500_000), gate(() -> {}), clock)) {
      assertEquals(Duration.ofNanos(500_000), CompilationExecutionBudget.limit(CONFIGURED));
      clock.now = NOW.plusNanos(500_000);
      assertThrows(
          CompilationExecutionBudget.StageDeadlineExpired.class,
          () -> CompilationExecutionBudget.limit(CONFIGURED));
    }
  }

  private static CompilationPublication gate(Runnable check) {
    return new CompilationPublication() {
      public void captureInputs(List<String> versions) {}

      public void publish(Runnable writes) {
        writes.run();
      }

      public void check() {
        check.run();
      }

      public String objectKey(String key) {
        return "synthetic/" + key;
      }
    };
  }

  private static final class MutableClock extends Clock {
    volatile Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
