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
  void transientFailureUsesIncreasingDurableDeadlines() {
    long[] seconds = {10, 30, 120, 300};
    for (int attempt = 1; attempt < 5; attempt++) {
      var failure =
          new IllegalStateException(
              "compilation wrapper", new TransientDependencyFailure("transport", null));
      assertEquals(
          NOW.plusSeconds(seconds[attempt - 1]),
          GenerationTaskRetryPolicy.nextAttemptAt(task(attempt), failure, NOW).orElseThrow());
    }
  }

  @Test
  void fifthFailedAttemptStopsAutomaticRetries() {
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(5), new TransientDependencyFailure("transport", null), NOW)
            .isEmpty());
  }

  @Test
  void businessAndDecodeFailuresAreNotNetworkRetries() {
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(1), new IllegalStateException("business rejected"), NOW)
            .isEmpty());
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(1),
                new IllegalStateException("JSON decoding", new java.io.IOException("bad JSON")),
                NOW)
            .isEmpty());
  }

  @Test
  void groundedModelProposalMayRetryWithinTheSameFiveAttemptBudget() {
    var rejected =
        new ModelOutputRejectedException(
            "anchor was absent from its declared fact",
            new IllegalArgumentException("unsupported anchor"));
    assertEquals(
        NOW.plusSeconds(30),
        GenerationTaskRetryPolicy.nextAttemptAt(task(2), rejected, NOW).orElseThrow());
    assertTrue(GenerationTaskRetryPolicy.nextAttemptAt(task(5), rejected, NOW).isEmpty());
    assertTrue(
        GenerationTaskRetryPolicy.nextAttemptAt(
                task(2), new IllegalStateException("source revoked"), NOW)
            .isEmpty());
  }
}
