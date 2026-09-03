package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.time.Clock;

/** Durable user-facing entry point for the version knowledge-generation pipeline. */
public final class KnowledgeGenerationApplicationService {
  private final CompilationRepository compilation;
  private final GenerationTaskRepository tasks;
  private final Clock clock;

  public KnowledgeGenerationApplicationService(
      CompilationRepository compilation, GenerationTaskRepository tasks) {
    this(compilation, tasks, Clock.systemUTC());
  }

  KnowledgeGenerationApplicationService(
      CompilationRepository compilation, GenerationTaskRepository tasks, Clock clock) {
    if (compilation == null)
      throw new IllegalArgumentException("compilation repository is required");
    if (tasks == null) throw new IllegalArgumentException("generation task repository is required");
    if (clock == null) throw new IllegalArgumentException("clock is required");
    this.compilation = compilation;
    this.tasks = tasks;
    this.clock = clock;
  }

  public GenerationTask queueVersion(String versionId) {
    String id = requireText(versionId, "version id");
    // This also verifies that the version is ACTIVE and has raw evidence before accepting the task.
    compilation.loadVersionSource(id);
    return tasks.queue(id, GenerationTaskType.SUMMARY, "", clock.instant());
  }

  /**
   * Queue a durable raw-vector rebuild for an already active version. This operation consumes only
   * persisted RawBlock/Evidence and never creates a new upload or document version.
   */
  public GenerationTask queueEmbeddingRebuild(String versionId) {
    String id = requireText(versionId, "version id");
    compilation.loadVersionSource(id);
    return tasks.queue(id, GenerationTaskType.EMBEDDING_REBUILD, "", clock.instant());
  }

  public void retry(String taskId) {
    tasks.retry(requireText(taskId, "generation task id"), clock.instant());
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
