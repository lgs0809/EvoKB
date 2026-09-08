package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class RawExecutionBudgetTest {
  final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  @Test
  void nestedBudgetsCannotExtendOriginalDeadlineAndRestoreAfterClose() {
    assertFalse(RawExecutionBudget.active());
    try (var first = RawExecutionBudget.open(now.plusSeconds(2), () -> {}, clock)) {
      assertEquals(Duration.ofSeconds(2), RawExecutionBudget.limit(Duration.ofMinutes(10)));
      try (var second = RawExecutionBudget.open(now.plusSeconds(10), () -> {}, clock)) {
        assertEquals(Duration.ofSeconds(2), RawExecutionBudget.limit(Duration.ofSeconds(30)));
      }
      assertEquals(Duration.ofMillis(500), RawExecutionBudget.limit(Duration.ofMillis(500)));
    }
    assertFalse(RawExecutionBudget.active());
    assertEquals(Duration.ofSeconds(30), RawExecutionBudget.limit(Duration.ofSeconds(30)));
  }

  @Test
  void expiredAndRevokedScopesKeepTheErrorAndNeverAcquireAnotherWindow() {
    try (var scope =
        RawExecutionBudget.open(
            now, () -> fail("expired task cannot check database again"), clock)) {
      assertTrue(
          assertThrows(
                  IllegalStateException.class,
                  () -> RawExecutionBudget.limit(Duration.ofSeconds(30)))
              .getMessage()
              .contains("RAW_STAGE_EXPIRED"));
    }
    var failure = new IllegalStateException("RAW_SOURCE_REVOKED");
    try (var scope =
        RawExecutionBudget.open(
            now.plusSeconds(30),
            () -> {
              throw failure;
            },
            clock)) {
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class, () -> RawExecutionBudget.limit(Duration.ofSeconds(30))));
    }
  }

  @Test
  void asynchronousCompletionKeepsTheOriginalAuthorityWithoutInheritingAnotherScope()
      throws Exception {
    var checks = new AtomicInteger();
    Runnable captured;
    try (var scope = RawExecutionBudget.open(now.plusSeconds(30), checks::incrementAndGet, clock)) {
      captured = RawExecutionBudget.capturedCheck();
    }
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor
          .submit(
              () -> {
                assertFalse(RawExecutionBudget.active());
                captured.run();
              })
          .get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, checks.get());
    assertFalse(RawExecutionBudget.active());
  }

  @Test
  void transactionRemainingTimeDoesNotReenterAuthorityButRetainsEveryDeadline() {
    var checks = new AtomicInteger();
    try (var outer = RawExecutionBudget.open(now.plusMillis(500), checks::incrementAndGet, clock);
        var inner = RawExecutionBudget.open(now.plusSeconds(30), checks::incrementAndGet, clock)) {
      assertEquals(Duration.ofMillis(500), RawExecutionBudget.remaining(Duration.ofSeconds(10)));
      assertEquals(0, checks.get());
      assertEquals(Duration.ofMillis(500), RawExecutionBudget.limit(Duration.ofSeconds(10)));
      assertEquals(2, checks.get());
    }
    try (var expired = RawExecutionBudget.open(now, checks::incrementAndGet, clock)) {
      assertThrows(
          IllegalStateException.class, () -> RawExecutionBudget.remaining(Duration.ofSeconds(10)));
      assertEquals(2, checks.get());
    }
  }

  @Test
  void scopesMustCloseOnTheirOwnerThreadInReverseOrder() throws Exception {
    var first = RawExecutionBudget.open(now.plusSeconds(30), () -> {}, clock);
    var second = RawExecutionBudget.open(now.plusSeconds(30), () -> {}, clock);
    assertThrows(IllegalStateException.class, first::close);
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor
          .submit(() -> assertThrows(IllegalStateException.class, second::close))
          .get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    second.close();
    first.close();
    assertThrows(IllegalStateException.class, first::close);
    assertFalse(RawExecutionBudget.active());
  }
}
