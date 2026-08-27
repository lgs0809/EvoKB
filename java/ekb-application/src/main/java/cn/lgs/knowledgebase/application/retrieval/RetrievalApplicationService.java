package cn.lgs.knowledgebase.application.retrieval;

import cn.lgs.knowledgebase.application.port.Ports.EvidenceResolver;
import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.application.port.Ports.RerankerPort;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RetrievalApplicationService {
  private static final System.Logger LOGGER =
      System.getLogger(RetrievalApplicationService.class.getName());

  private final Map<LaneType, RetrievalLane> lanes;
  private final RerankerPort reranker;
  private final EvidenceResolver resolver;
  private final Quotas quotas;
  private final int maxRerankCandidates;
  private final int maxRerankChars;
  private final GraphStore graph;
  private final cn.lgs.knowledgebase.application.port.Ports.SourceVersionAuthority authority;

  public RetrievalApplicationService(
      List<RetrievalLane> lanes,
      RerankerPort reranker,
      EvidenceResolver resolver,
      Quotas quotas,
      int maxRerankCandidates,
      int maxRerankChars) {
    this(lanes, reranker, resolver, quotas, maxRerankCandidates, maxRerankChars, null);
  }

  public RetrievalApplicationService(
      List<RetrievalLane> lanes,
      RerankerPort reranker,
      EvidenceResolver resolver,
      Quotas quotas,
      int maxRerankCandidates,
      int maxRerankChars,
      GraphStore graph) {
    this(lanes, reranker, resolver, quotas, maxRerankCandidates, maxRerankChars, graph, null);
  }

  public RetrievalApplicationService(
      List<RetrievalLane> lanes,
      RerankerPort reranker,
      EvidenceResolver resolver,
      Quotas quotas,
      int maxRerankCandidates,
      int maxRerankChars,
      GraphStore graph,
      cn.lgs.knowledgebase.application.port.Ports.SourceVersionAuthority authority) {
    this.authority = authority;
    this.lanes = new EnumMap<>(LaneType.class);
    if (lanes != null) lanes.forEach(l -> this.lanes.put(l.type(), l));
    this.reranker = require(reranker, "reranker");
    this.resolver = require(resolver, "evidence resolver");
    this.quotas = quotas == null ? Quotas.defaults() : quotas;
    this.maxRerankCandidates = maxRerankCandidates <= 0 ? 20 : maxRerankCandidates;
    this.maxRerankChars = maxRerankChars <= 0 ? 400 : maxRerankChars;
    this.graph = graph;
  }

  public SearchResponse search(String query, SearchFilters filters, int finalLimit) {
    return searchCurrent(query, () -> filters, finalLimit);
  }

  public SearchResponse searchCurrent(
      String query, java.util.function.Supplier<SearchFilters> scope, int finalLimit) {
    SearchFilters filters = scope.get();
    if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
    int limit =
        graph == null
            ? (finalLimit <= 0 ? 10 : finalLimit)
            : Math.min(8, finalLimit <= 0 ? 8 : finalLimit);
    List<Candidate> merged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (LaneType laneType : LaneType.values()) {
      if (graph != null && laneType == LaneType.GRAPH) continue;
      RetrievalLane lane = lanes.get(laneType);
      if (lane == null) continue;
      List<Candidate> candidates;
      try {
        candidates = lane.search(query.trim(), filters, quotas.forLane(laneType));
      } catch (RuntimeException unavailable) {
        if (laneType == LaneType.RAW) throw unavailable;
        LOGGER.log(
            System.Logger.Level.WARNING,
            "retrieval lane {0} unavailable; continuing without it: {1}",
            laneType,
            unavailable.getMessage());
        continue;
      }
      if (candidates == null) continue;
      for (Candidate candidate : candidates) {
        if (candidate.knowledge() == null || candidate.knowledge().isBlank()) continue;
        String key = candidate.lane() + "\u0000" + candidate.id();
        if (seen.add(key)) merged.add(candidate);
      }
    }
    boolean graphTruncated = false;
    int graphHops = 0;
    if (graph != null) {
      var expansion = graph.expand(List.copyOf(merged), scope.get());
      graphTruncated = expansion.truncated();
      graphHops = expansion.hops();
      // Preserve ten base candidates and ten graph candidates; unavailable graph slots
      // may be filled from the base pool, never by multiplying the total budget.
      var base = new ArrayList<>(merged);
      merged.clear();
      merged.addAll(base.stream().limit(10).toList());
      merged.addAll(expansion.candidates().stream().limit(10).toList());
      if (merged.size() < 20)
        merged.addAll(base.stream().skip(10).limit(20 - merged.size()).toList());
    }
    String searchId = UUID.randomUUID().toString();
    if (merged.isEmpty()) return new SearchResponse(searchId, List.of());

    int resultScaledRerankLimit =
        limit > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : Math.max(limit, limit * 2);
    List<Candidate> pool =
        RetrievalDomain.balancedRerankPool(
            merged, graph == null ? Math.min(maxRerankCandidates, resultScaledRerankLimit) : 20);
    List<String> poolEvidenceIds =
        pool.stream().flatMap(c -> c.evidenceIds().stream()).distinct().toList();
    var rawEvidence =
        graph == null ? List.<Evidence>of() : resolver.resolve(poolEvidenceIds, scope.get());
    Map<String, Evidence> rawById = new java.util.LinkedHashMap<>();
    if (rawEvidence != null) rawEvidence.forEach(e -> rawById.put(e.id(), e));
    if (graph != null)
      pool =
          pool.stream()
              .filter(
                  c ->
                      !c.evidenceIds().isEmpty()
                          && c.evidenceIds().stream().allMatch(rawById::containsKey))
              .toList();
    if (authority != null) {
      var current = scope.get();
      pool =
          pool.stream()
              .filter(
                  c ->
                      authority.accessible(
                          cn.lgs.knowledgebase.application.access.KnowledgeAccessService
                              .sourceVersions(c),
                          current))
              .toList();
    }
    if (pool.isEmpty()) return new SearchResponse(searchId, List.of(), graphTruncated, graphHops);
    List<String> documents =
        pool.stream()
            .map(
                c ->
                    bounded(
                        graph == null
                            ? c.knowledge()
                            : c.evidenceIds().stream()
                                .map(rawById::get)
                                .map(Evidence::content)
                                .distinct()
                                .collect(java.util.stream.Collectors.joining("\n")),
                        maxRerankChars))
            .toList();
    List<Candidate> reranked;
    try {
      reranked = RetrievalDomain.applyRerankScores(pool, reranker.rerank(query.trim(), documents));
    } catch (RuntimeException unavailable) {
      // Rerank is a derived ordering aid. Preserve the already-grounded fused pool rather than
      // failing the entire search when the optional model service is slow or unavailable.
      reranked = pool;
    }
    List<String> allEvidenceIds =
        reranked.stream()
            .flatMap(candidate -> candidate.evidenceIds().stream())
            .distinct()
            .toList();
    var resolved =
        allEvidenceIds.isEmpty()
            ? List.<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence>of()
            : resolver.resolve(allEvidenceIds, scope.get());
    Map<String, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> evidenceById =
        new java.util.LinkedHashMap<>();
    if (resolved != null) resolved.forEach(evidence -> evidenceById.put(evidence.id(), evidence));

    List<Result> results = new ArrayList<>(limit);
    SearchFilters deliveryScope = scope.get();
    for (Candidate candidate : reranked) {
      if (authority != null
          && !authority.accessible(
              cn.lgs.knowledgebase.application.access.KnowledgeAccessService.sourceVersions(
                  candidate),
              deliveryScope)) continue;
      if (results.size() >= limit) break;
      if (candidate.evidenceIds().isEmpty()) continue;
      var evidence =
          candidate.evidenceIds().stream()
              .map(evidenceById::get)
              .filter(java.util.Objects::nonNull)
              .filter(RetrievalApplicationService::substantiveEvidence)
              .toList();
      if (evidence.isEmpty()
          || candidate.evidenceIds().stream().anyMatch(id -> !evidenceById.containsKey(id)))
        continue;
      results.add(new Result(candidate, evidence));
    }
    return new SearchResponse(searchId, List.copyOf(results), graphTruncated, graphHops);
  }

  public record SearchResponse(
      String searchId, List<Result> results, boolean graphTruncated, int graphHops) {
    public SearchResponse(String searchId, List<Result> results) {
      this(searchId, results, false, 0);
    }
  }

  private static boolean substantiveEvidence(Evidence evidence) {
    Object blockType = evidence.location().get("block_type");
    String type =
        blockType == null ? "" : blockType.toString().trim().toLowerCase(java.util.Locale.ROOT);
    if (Set.of("header", "footer", "page_number").contains(type)) return false;
    Object headingPath = evidence.location().get("heading_path");
    if (headingPath instanceof List<?> headings && !headings.isEmpty()) {
      String last =
          String.valueOf(headings.get(headings.size() - 1)).trim().replaceAll("\\s+", " ");
      String content = evidence.content().trim().replaceAll("\\s+", " ");
      if (!last.isEmpty() && content.equals(last)) return false;
    }
    return true;
  }

  private static String bounded(String value, int maxChars) {
    if (value.length() <= maxChars) return value;
    return value.substring(0, maxChars);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
