package cn.lgs.knowledgebase.application.task;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DurableRetryBackoffTest {
  @Test
  void exponentialJitterIsBoundedAndCannotOverflowLargeCounters() {
    for (int attempt : new int[] {1, 2, 5, 6, 7, 8, 20, Integer.MAX_VALUE})
      for (int id = 0; id < 40; id++) {
        long max = Math.min(900_000L, 10_000L << Math.min(attempt - 1, 20));
        long actual = DurableRetryBackoff.delay("task" + id, attempt).toMillis();
        assertTrue(actual >= max * 4 / 5 && actual <= max);
      }
  }

  @Test
  void jitterDiffersAcrossTaskIdentitiesAndIsStableAcrossRecovery() {
    var delays = new HashSet<Duration>();
    for (int id = 0; id < 40; id++) {
      var delay = DurableRetryBackoff.delay("task" + id, 9);
      assertEquals(delay, DurableRetryBackoff.delay("task" + id, 9));
      delays.add(delay);
    }
    assertTrue(delays.size() > 35);
  }

  @Test
  void historicalRecoveryPreservesUnelapsedWaitAndStaggersOverdueJobs() {
    Instant now = Instant.parse("2026-11-02T00:00:00Z");
    assertEquals(
        DurableRetryBackoff.next("task", 9, now), DurableRetryBackoff.recover("task", 9, now, now));
    Instant old = now.minusSeconds(3600);
    assertEquals(
        DurableRetryBackoff.next("task", 1, now), DurableRetryBackoff.recover("task", 9, old, now));
  }
}
