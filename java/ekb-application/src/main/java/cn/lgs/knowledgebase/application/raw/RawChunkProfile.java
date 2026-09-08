package cn.lgs.knowledgebase.application.raw;

/** All limits count the final retrieval text, including titles, table headers and overlap. */
public record RawChunkProfile(
    int targetTokens, int hardLimitTokens, int overlapTokens, int tailTokens) {
  public static final RawChunkProfile DEFAULT = new RawChunkProfile(600, 900, 80, 150);

  public RawChunkProfile {
    if (targetTokens <= 0
        || hardLimitTokens < targetTokens
        || overlapTokens < 0
        || overlapTokens >= targetTokens
        || tailTokens < 0
        || tailTokens > targetTokens)
      throw new IllegalArgumentException("Invalid raw chunk token limits");
  }

  public String identity() {
    return "raw-structure-v3:"
        + targetTokens
        + ":"
        + hardLimitTokens
        + ":"
        + overlapTokens
        + ":"
        + tailTokens;
  }
}
