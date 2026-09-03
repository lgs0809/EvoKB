package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.time.Instant;

/** Queues derived compilation only after Raw ingestion has committed successfully. */
final class IngestionCompilationTrigger {
  private final GenerationTaskRepository tasks;

  IngestionCompilationTrigger(GenerationTaskRepository tasks) {
    this.tasks = tasks;
  }

  void queueSummary(String versionId, Instant now) {
    if (tasks == null) return;
    try {
      tasks.queue(versionId, GenerationTaskType.SUMMARY, "", now);
    } catch (RuntimeException ignored) {
      // A derived trigger failure must not turn a committed Raw ingestion into FAILED.
    }
  }
}
