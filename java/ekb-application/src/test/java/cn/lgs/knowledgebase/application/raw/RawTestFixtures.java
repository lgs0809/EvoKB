package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;

/** Synthetic token units for deterministic structure tests; never claimed as model tokenizer. */
final class RawTestFixtures {
  static final RawTokenCounter TOKENS =
      new RawTokenCounter() {
        public int count(String text) {
          return text.codePointCount(0, text.length());
        }

        public String identity() {
          return "fixture-code-points-v1";
        }
      };

  static RawVectorEncoder encoder(EmbeddingPort embedder) {
    return new RawVectorEncoder(
        embedder,
        () ->
            new RawEncodingProfile(
                "fixture",
                "fixture-revision",
                embedder.dimensions(),
                TOKENS.identity(),
                "fixture-preprocess-v1",
                "",
                "fixture-only"),
        RawVectorCache.none());
  }

  private RawTestFixtures() {}
}
