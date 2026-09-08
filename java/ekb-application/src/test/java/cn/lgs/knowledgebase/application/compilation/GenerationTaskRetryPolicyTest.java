package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GenerationTaskRetryPolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

  private static GenerationTask task(int attempt) {
    return new GenerationTask(
        "task",
        "version",
        GenerationTaskType.GRAPH,
        "summary",
        GenerationTaskStatus.RUNNING,
        attempt,
        "",
        "",
        NOW);
  }

  @Test
  void transientFailureKeepsSchedulingAfterFiveAndAtLargeCounters() {
    for (int attempt : new int[] {1, 2, 5, 6, 20, Integer.MAX_VALUE}) {
      var failure =
          new IllegalStateException("wrapper", new TransientDependencyFailure("transport", null));
      assertEquals(
          NOW.plus(
              cn.lgs.knowledgebase.application.task.DurableRetryBackoff.delay("task", attempt)),
          GenerationTaskRetryPolicy.nextAttemptAt(task(attempt), failure, NOW).orElseThrow());
    }
  }

  @Test
  void businessAndDecodeFailuresAreNotNetworkRetries() {
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(9), new IllegalStateException("source revoked"), NOW)
            .isEmpty());
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(9),
                new IllegalStateException("JSON decoding", new java.io.IOException("bad JSON")),
                NOW)
            .isEmpty());
  }

  @Test
  void semanticProposalRejectionKeepsItsOwnPersistentChain() {
    for (int attempt : new int[] {1, 5, 6, 100})
      assertTrue(
          GenerationTaskRetryPolicy.nextAttemptAt(
                  task(attempt), new ModelOutputRejectedException("unsupported anchor"), NOW)
              .orElseThrow()
              .isAfter(NOW));
  }

  @Test
  void expiredStageRetriesWithoutRenewingTheExpiredAttempt() {
    for (int attempt : new int[] {1, 5, 6, 100})
      assertTrue(
          GenerationTaskRetryPolicy.nextAttemptAt(
                  task(attempt),
                  new IllegalStateException(
                      "wrapper", new CompilationExecutionBudget.StageDeadlineExpired()),
                  NOW)
              .orElseThrow()
              .isAfter(NOW));
  }
}
