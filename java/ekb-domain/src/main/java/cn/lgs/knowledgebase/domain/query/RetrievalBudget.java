package cn.lgs.knowledgebase.domain.query;

import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalReservation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Every dispatched round consumes a durable slot, including unfinished attempts. */
public final class RetrievalBudget {
  public static final int HARD_MAX_ROUNDS = 8;
  private final int maxRounds;
  private final int maxQueries;
  private final int usedRounds;
  private final int usedQueries;
  private final Set<String> previousQueries;

  public RetrievalBudget(List<RetrievalReservation> reservations, int maxRounds, int maxQueries) {
    this.maxRounds = roundLimit(maxRounds);
    this.maxQueries = maxQueries <= 0 ? 8 : maxQueries;
    this.usedRounds = reservations.stream().mapToInt(RetrievalReservation::roundNo).max().orElse(0);
    this.usedQueries = reservations.stream().mapToInt(r -> r.inputQueries().size()).sum();
    this.previousQueries = new LinkedHashSet<>();
    reservations.forEach(r -> r.inputQueries().forEach(q -> previousQueries.add(normalize(q))));
  }

  public static int roundLimit(int configured) {
    return configured <= 0 ? HARD_MAX_ROUNDS : Math.min(configured, HARD_MAX_ROUNDS);
  }

  public int nextRoundNo() {
    return usedRounds + 1;
  }

  public int usedQueries() {
    return usedQueries;
  }

  public boolean exhausted() {
    return usedRounds >= maxRounds || usedQueries >= maxQueries;
  }

  public List<String> previousQueries() {
    return List.copyOf(previousQueries);
  }

  public List<String> freshQueries(List<String> proposed, int perRoundLimit) {
    if (exhausted() || proposed == null || perRoundLimit <= 0) return List.of();
    Set<String> local = new LinkedHashSet<>();
    return proposed.stream()
        .filter(q -> q != null && !q.isBlank())
        .map(String::trim)
        .filter(q -> !previousQueries.contains(normalize(q)) && local.add(normalize(q)))
        .limit(Math.min(perRoundLimit, maxQueries - usedQueries))
        .toList();
  }

  private static String normalize(String value) {
    return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
