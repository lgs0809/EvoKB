package cn.lgs.knowledgebase.application.compilation;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** A fixed input snapshot and a short database commit, outside model/object/index requests. */
public interface CompilationPublication {
  void captureInputs(List<String> sourceVersionIds);

  void publish(Runnable writes);

  /** Recheck the fixed snapshot before another external operation; never renew ownership. */
  default void check() {}

  String objectKey(String logicalKey);

  /** Non-persistent adapters retain their existing transaction semantics. */
  static CompilationPublication direct(Consumer<Runnable> transaction) {
    String namespace = "compilation/" + UUID.randomUUID() + "/";
    return new CompilationPublication() {
      public void captureInputs(List<String> versions) {}

      public void publish(Runnable writes) {
        transaction.accept(writes);
      }

      public String objectKey(String key) {
        return namespace + key;
      }
    };
  }
}
