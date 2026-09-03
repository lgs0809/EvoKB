package cn.lgs.knowledgebase.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionCompilationTriggerTest {
  private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

  @Test
  void queuesSummaryAfterRawCommitWithIdempotentRepositoryContract() {
    Tasks tasks = new Tasks(false);

    new IngestionCompilationTrigger(tasks).queueSummary("v1", NOW);

    assertEquals(1, tasks.queued);
    assertEquals("v1", tasks.versionId);
  }

  @Test
  void derivedTriggerFailureDoesNotEscapeIntoRawWorker() {
    Tasks tasks = new Tasks(true);

    assertDoesNotThrow(() -> new IngestionCompilationTrigger(tasks).queueSummary("v1", NOW));
    assertEquals(0, tasks.queued);
  }

  private static final class Tasks implements GenerationTaskRepository {
    private final boolean fail;
    private int queued;
    private String versionId;

    private Tasks(boolean fail) {
      this.fail = fail;
    }

    @Override
    public GenerationTask queue(
        String versionId,
        cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType taskType,
        String subjectId,
        Instant now) {
      if (fail) throw new IllegalStateException("temporary queue outage");
      this.versionId = versionId;
      queued++;
      return null;
    }

    @Override
    public List<GenerationTask> claimPending(int limit, Instant now) {
      return List.of();
    }

    @Override
    public void complete(GenerationTask task, String outputHash, Instant now) {}

    @Override
    public void completeConceptAndQueueDerived(
        GenerationTask task, String outputHash, Instant now) {}

    @Override
    public void fail(String taskId, String errorCode, String errorMessage, Instant now) {}

    @Override
    public void retry(String taskId, Instant now) {}

    @Override
    public void resetInterrupted(Instant now) {}
  }
}
