package cn.lgs.knowledgebase.application.raw;

import java.util.List;
import java.util.Optional;

/** Stores only vector calculations. A hit cannot authorize or publish any source. */
public interface RawVectorCache {
  Optional<List<Float>> find(String key, String profileHash, String inputHash, int dimensions);

  void put(String key, String profileHash, String inputHash, int dimensions, List<Float> vector);

  static RawVectorCache none() {
    return new RawVectorCache() {
      public Optional<List<Float>> find(String key, String profile, String input, int dimensions) {
        return Optional.empty();
      }

      public void put(
          String key, String profile, String input, int dimensions, List<Float> vector) {}
    };
  }
}
