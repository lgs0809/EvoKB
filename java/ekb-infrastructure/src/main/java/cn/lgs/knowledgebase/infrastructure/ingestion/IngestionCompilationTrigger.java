package cn.lgs.knowledgebase.infrastructure.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.time.Instant;

/**
 * Queue background association after Raw; cold documents do not automatically generate summaries.
 */
final class IngestionCompilationTrigger {
  private final GenerationTaskRepository tasks;

  IngestionCompilationTrigger(GenerationTaskRepository tasks) {
    this.tasks = tasks;
  }

  void queueAssociation(String versionId, Instant now) {
    if (tasks == null) return;
    try {
      tasks.queue(versionId, GenerationTaskType.SOURCE_ASSOCIATION, "", now);
    } catch (RuntimeException ignored) {
      // A derived trigger failure must not turn a committed Raw ingestion into FAILED.
    }
  }
}
