package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import java.util.List;
import java.util.Optional;

/** Explicit admin requests share the existing durable, fixed-deadline compilation lane. */
public final class CompilationCommands {
  private CompilationCommands() {}

  public enum Kind {
    SUMMARY(GenerationTaskType.SUMMARY),
    GRAPH(GenerationTaskType.GRAPH),
    CONCEPT_PROJECTION(GenerationTaskType.CONCEPT),
    WIKI(GenerationTaskType.WIKI),
    WIKI_KEYWORDS(GenerationTaskType.WIKI);
    private final GenerationTaskType type;

    Kind(GenerationTaskType type) {
      this.type = type;
    }

    public GenerationTaskType taskType() {
      return type;
    }
  }

  public record Request(
      Kind kind,
      String versionId,
      List<String> summaryIds,
      String wikiType,
      String title,
      String canonicalSubjectId,
      String wikiId,
      long wikiVersion) {
    public Request {
      if (kind == null) throw new IllegalArgumentException("compilation kind is required");
      versionId = clean(versionId);
      wikiType = clean(wikiType);
      title = clean(title);
      canonicalSubjectId = clean(canonicalSubjectId);
      wikiId = clean(wikiId);
      summaryIds =
          summaryIds == null
              ? List.of()
              : summaryIds.stream().map(CompilationCommands::clean).distinct().sorted().toList();
      if (summaryIds.size() > 1000
          || summaryIds.stream().anyMatch(id -> id.isEmpty() || id.length() > 255)
          || versionId.length() > 36
          || wikiId.length() > 255
          || title.length() > 512
          || canonicalSubjectId.length() > 255
          || wikiType.length() > 64)
        throw new IllegalArgumentException("compilation request exceeds supported limits");
      switch (kind) {
        case SUMMARY -> {
          if (versionId.isEmpty()) throw new IllegalArgumentException("source version is required");
        }
        case GRAPH, CONCEPT_PROJECTION -> {
          if (summaryIds.size() != 1) throw new IllegalArgumentException("one Summary is required");
        }
        case WIKI -> {
          if (summaryIds.isEmpty() || wikiType.isEmpty() || title.isEmpty())
            throw new IllegalArgumentException(
                "Wiki type, title and source Summaries are required");
        }
        case WIKI_KEYWORDS -> {
          if (wikiId.isEmpty() || wikiVersion < 1)
            throw new IllegalArgumentException("Wiki revision is required");
        }
      }
    }
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  public record Command(String submittedBy, Request request, List<String> sourceVersions) {
    public Command {
      sourceVersions = List.copyOf(sourceVersions);
    }
  }

  public interface Store {
    GenerationTask submit(String userId, Request request);

    Optional<Command> find(GenerationTask task);

    CompilationPublication publication(
        GenerationTask task, Command command, CompilationPublication owned);
  }
}
