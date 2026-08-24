package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.compilation.GenerationTaskRetryPolicy;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import java.time.Instant;

/** Shared durable failure transition for compilation and embedding workers. */
final class GenerationTaskFailureHandler {
  private GenerationTaskFailureHandler() {}

  static void persist(
      GenerationTaskRepository tasks,
      GenerationTask task,
      String code,
      RuntimeException failure,
      Instant now) {
    var retry = GenerationTaskRetryPolicy.nextAttemptAt(task, failure, now);
    if (retry.isPresent()) tasks.defer(task, code, failure.getMessage(), retry.orElseThrow(), now);
    else tasks.fail(task, code, failure.getMessage(), now);
  }
}
