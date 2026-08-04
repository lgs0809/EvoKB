package cn.lgs.knowledgebase.domain.retrieval;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RetrievalDomain {
  private RetrievalDomain() {}

  public enum LaneType {
    RAW,
    SUMMARY,
    WIKI,
    GRAPH
  }

  public record Quotas(int raw, int summary, int wiki, int graph) {
    public static Quotas defaults() {
      return new Quotas(30, 20, 20, 10);
    }

    public int forLane(LaneType lane) {
      return switch (lane) {
        case RAW -> raw <= 0 ? 30 : raw;
        case SUMMARY -> summary <= 0 ? 20 : summary;
        case WIKI -> wiki <= 0 ? 20 : wiki;
        case GRAPH -> graph <= 0 ? 10 : graph;
      };
    }
  }

  public record Candidate(
      String id,
      String documentId,
      String versionId,
      LaneType lane,
      String knowledge,
      double score,
      List<String> evidenceIds,
      Map<String, Object> attributes) {
    public Candidate {
      id = requireText(id, "candidate id");
      Objects.requireNonNull(lane, "lane");
      knowledge = requireText(knowledge, "knowledge");
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public Candidate withScore(double nextScore) {
      return new Candidate(
          id, documentId, versionId, lane, knowledge, nextScore, evidenceIds, attributes);
    }
  }

  public record Result(Candidate candidate, List<Evidence> evidence) {
    public Result {
      Objects.requireNonNull(candidate, "candidate");
      evidence = List.copyOf(evidence == null ? List.of() : evidence);
      if (evidence.isEmpty())
        throw new IllegalArgumentException("retrieval result must resolve raw evidence");
    }
  }

  public static List<Candidate> balancedRerankPool(List<Candidate> candidates, int limit) {
    if (limit <= 0 || candidates.size() <= limit) return List.copyOf(candidates);
    Map<LaneType, List<Candidate>> groups = new EnumMap<>(LaneType.class);
    for (LaneType lane : LaneType.values()) groups.put(lane, new ArrayList<>());
    candidates.forEach(c -> groups.get(c.lane()).add(c));
    List<Candidate> out = new ArrayList<>(limit);
    for (int rank = 0; out.size() < limit; rank++) {
      boolean added = false;
      for (LaneType lane : LaneType.values()) {
        List<Candidate> group = groups.get(lane);
        if (rank < group.size()) {
          out.add(group.get(rank));
          added = true;
          if (out.size() == limit) break;
        }
      }
      if (!added) break;
    }
    return List.copyOf(out);
  }

  /** Reciprocal-rank fusion used inside a retrieval lane (for example BM25 + vector). */
  public static List<Candidate> rrf(List<List<Candidate>> rankedLists, int limit, int k) {
    int effectiveK = k <= 0 ? 60 : k;
    Map<String, Candidate> candidateByKey = new LinkedHashMap<>();
    Map<String, Double> scoreByKey = new LinkedHashMap<>();
    for (List<Candidate> list : rankedLists) {
      for (int i = 0; i < list.size(); i++) {
        Candidate candidate = list.get(i);
        String key = candidate.lane() + "\u0000" + candidate.id();
        candidateByKey.putIfAbsent(key, candidate);
        scoreByKey.merge(key, 1.0d / (effectiveK + i + 1), Double::sum);
      }
    }
    return scoreByKey.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
        .limit(limit <= 0 ? Long.MAX_VALUE : limit)
        .map(e -> candidateByKey.get(e.getKey()).withScore(e.getValue()))
        .toList();
  }

  public static List<Candidate> applyRerankScores(List<Candidate> pool, List<Double> scores) {
    if (pool.size() != scores.size())
      throw new IllegalArgumentException("unified rerank score count mismatch");
    List<Candidate> reranked = new ArrayList<>(pool.size());
    for (int i = 0; i < pool.size(); i++) reranked.add(pool.get(i).withScore(scores.get(i)));
    reranked.sort(Comparator.comparingDouble(Candidate::score).reversed());
    return List.copyOf(reranked);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
