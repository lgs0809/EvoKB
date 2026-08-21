package cn.lgs.knowledgebase.application.retrieval;

import cn.lgs.knowledgebase.application.port.Ports.EvidenceResolver;
import cn.lgs.knowledgebase.application.port.Ports.RerankerPort;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
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
    private final Map<LaneType, RetrievalLane> lanes;
    private final RerankerPort reranker;
    private final EvidenceResolver resolver;
    private final Quotas quotas;
    private final int maxRerankCandidates;
    private final int maxRerankChars;

    public RetrievalApplicationService(List<RetrievalLane> lanes, RerankerPort reranker, EvidenceResolver resolver,
                                       Quotas quotas, int maxRerankCandidates, int maxRerankChars) {
        this.lanes = new EnumMap<>(LaneType.class);
        if (lanes != null) lanes.forEach(l -> this.lanes.put(l.type(), l));
        this.reranker = require(reranker, "reranker");
        this.resolver = require(resolver, "evidence resolver");
        this.quotas = quotas == null ? Quotas.defaults() : quotas;
        this.maxRerankCandidates = maxRerankCandidates <= 0 ? 20 : maxRerankCandidates;
        this.maxRerankChars = maxRerankChars <= 0 ? 400 : maxRerankChars;
    }

    public SearchResponse search(String query, SearchFilters filters, int finalLimit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        int limit = finalLimit <= 0 ? 10 : finalLimit;
        List<Candidate> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LaneType laneType : LaneType.values()) {
            RetrievalLane lane = lanes.get(laneType);
            if (lane == null) continue;
            for (Candidate candidate : lane.search(query.trim(), filters, quotas.forLane(laneType))) {
                if (candidate.knowledge() == null || candidate.knowledge().isBlank()) continue;
                String key = candidate.lane() + "\u0000" + candidate.id();
                if (seen.add(key)) merged.add(candidate);
            }
        }
        String searchId = UUID.randomUUID().toString();
        if (merged.isEmpty()) return new SearchResponse(searchId, List.of());

        List<Candidate> pool = RetrievalDomain.balancedRerankPool(merged, maxRerankCandidates);
        List<String> documents = pool.stream().map(c -> bounded(c.knowledge(), maxRerankChars)).toList();
        List<Candidate> reranked;
        try {
            reranked = RetrievalDomain.applyRerankScores(pool, reranker.rerank(query.trim(), documents));
        } catch (RuntimeException unavailable) {
            // Rerank is a derived ordering aid. Preserve the already-grounded fused pool rather than
            // failing the entire search when the optional model service is slow or unavailable.
            reranked = pool;
        }
        List<String> allEvidenceIds = reranked.stream().flatMap(candidate -> candidate.evidenceIds().stream()).distinct().toList();
        var resolved = allEvidenceIds.isEmpty() ? List.<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence>of() : resolver.resolve(allEvidenceIds);
        Map<String, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> evidenceById = new java.util.LinkedHashMap<>();
        if (resolved != null) resolved.forEach(evidence -> evidenceById.put(evidence.id(), evidence));

        List<Result> results = new ArrayList<>(limit);
        for (Candidate candidate : reranked) {
            if (results.size() >= limit) break;
            if (candidate.evidenceIds().isEmpty()) continue;
            var evidence = candidate.evidenceIds().stream().map(evidenceById::get).filter(java.util.Objects::nonNull).toList();
            if (evidence.isEmpty()) continue;
            results.add(new Result(candidate, evidence));
        }
        return new SearchResponse(searchId, List.copyOf(results));
    }

    public record SearchResponse(String searchId, List<Result> results) {}

    private static String bounded(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
