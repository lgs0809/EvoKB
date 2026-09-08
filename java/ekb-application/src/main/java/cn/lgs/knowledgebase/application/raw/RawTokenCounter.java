package cn.lgs.knowledgebase.application.raw;

/** Counts the complete encoder input without truncation, using its frozen tokenizer. */
public interface RawTokenCounter {
  int count(String content);

  String identity();

  static RawTokenCounter unconfigured() {
    return new RawTokenCounter() {
      public int count(String text) {
        throw new IllegalStateException(
            "TOKENIZER_UNAVAILABLE: raw ingestion requires a configured embedding tokenizer");
      }

      public String identity() {
        throw new IllegalStateException(
            "TOKENIZER_UNAVAILABLE: raw ingestion requires a frozen tokenizer identity");
      }
    };
  }
}
