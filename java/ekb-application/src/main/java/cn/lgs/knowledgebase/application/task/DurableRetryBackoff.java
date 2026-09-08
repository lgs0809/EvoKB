package cn.lgs.knowledgebase.application.task;

import java.time.Duration;
import java.time.Instant;

/** Persistent jobs wait between attempts; an adapter's bounded request retries remain separate. */
public final class DurableRetryBackoff {
  public static final Duration MAX_DELAY = Duration.ofMinutes(15);

  private DurableRetryBackoff() {}

  /** Stable per-task/attempt jitter survives restart; the computed deadline is persisted once. */
  public static Duration delay(String identity, int attempt) {
    if (identity == null || identity.isBlank() || attempt < 1)
      throw new IllegalArgumentException("task identity and positive attempt required");
    long ceilingMillis = Math.min(MAX_DELAY.toMillis(), 10_000L << Math.min(attempt - 1, 20));
    String hash =
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(identity + ":" + attempt);
    long sample = Long.parseUnsignedLong(hash.substring(0, 8), 16);
    long minimum = ceilingMillis * 4 / 5;
    return Duration.ofMillis(minimum + sample % (ceilingMillis - minimum + 1));
  }

  public static Instant next(String identity, int attempt, Instant failedAt) {
    return failedAt.plus(delay(identity, attempt));
  }

  /** Old failures already due are staggered on recovery without discarding their old wait. */
  public static Instant recover(String identity, int attempt, Instant failedAt, Instant now) {
    Instant original = next(identity, attempt, failedAt);
    Instant stagger = next(identity, 1, now);
    return original.isAfter(stagger) ? original : stagger;
  }
}
