package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import java.util.List;

/**
 * An attempt owns its immutable staging identity; publication is an authoritative short
 * transaction.
 */
public interface RawExecution {
  String projectionScope();

  void check();

  void phase(ProgressStage stage);

  void bind(String documentId, String versionId);

  void fenced(Runnable mutation);

  void publish(String documentId, String versionId, List<String> physicalIds, Runnable mutation);

  /** A multipart completion reserves one source version; it must never silently use another. */
  default java.util.Optional<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion>
      reservedInput() {
    return java.util.Optional.empty();
  }

  /** Close a retry after publication only when the exact source still has a valid projection. */
  default boolean completedInputCurrent(String documentId, String versionId) {
    return false;
  }

  /** Short source identity reservation only; external file/model work must remain outside it. */
  default <T> T reserveSource(String logicalName, java.util.function.Supplier<T> reservation) {
    return reservation.get();
  }

  /** Only retained for explicitly legacy synchronous callers and isolated unit components. */
  static RawExecution legacy() {
    return new RawExecution() {
      public String projectionScope() {
        return "";
      }

      public void check() {}

      public void phase(ProgressStage stage) {}

      public void bind(String document, String version) {}

      public void fenced(Runnable mutation) {
        mutation.run();
      }

      public void publish(String document, String version, List<String> ids, Runnable mutation) {
        mutation.run();
      }
    };
  }
}
