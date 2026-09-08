package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Bounded, credential-scoped evidence that an otherwise ambiguous 404 followed a working route. */
final class RecentlyHealthyModelRoutes {
  private static final long MAX_AGE = Duration.ofMinutes(10).toNanos();
  private final LinkedHashMap<String, Long> successes = new LinkedHashMap<>();
  private final LongSupplier clock;

  RecentlyHealthyModelRoutes() {
    this(System::nanoTime);
  }

  RecentlyHealthyModelRoutes(LongSupplier clock) {
    this.clock = clock;
  }

  static String identity(String endpoint, String model, String credential) {
    return KnowledgeDomain.sha256(endpoint + "\u0000" + model + "\u0000" + credential);
  }

  synchronized void succeeded(String identity) {
    successes.remove(identity);
    successes.put(identity, clock.getAsLong());
    while (successes.size() > 16) successes.remove(successes.keySet().iterator().next());
  }

  synchronized boolean recentlySucceeded(String identity) {
    Long at = successes.get(identity);
    return at != null && clock.getAsLong() - at >= 0 && clock.getAsLong() - at <= MAX_AGE;
  }
}
