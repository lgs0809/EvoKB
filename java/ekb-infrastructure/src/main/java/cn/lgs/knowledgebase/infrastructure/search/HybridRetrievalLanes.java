package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;

import java.util.List;

public final class HybridRetrievalLanes {
    private HybridRetrievalLanes() {}

    public static final class Raw implements RetrievalLane {
        private final RawSearchIndex index;
        private final EmbeddingPort embedding;

        public Raw(RawSearchIndex index, EmbeddingPort embedding) {
            this.index = index;
            this.embedding = embedding;
        }

        @Override public LaneType type() { return LaneType.RAW; }

        @Override
        public List<Candidate> search(String query, SearchFilters filters, int limit) {
            List<Float> vector = singleQueryVector(embedding, query);
            return RetrievalDomain.rrf(List.of(
                    index.searchBm25(query, filters, limit),
                    index.searchVector(vector, filters, limit)), limit, 60);
        }
    }

    public static final class Summary implements RetrievalLane {
        private final DerivedSearchIndex index;
        private final EmbeddingPort embedding;

        public Summary(DerivedSearchIndex index, EmbeddingPort embedding) {
            this.index = index;
            this.embedding = embedding;
        }

        @Override public LaneType type() { return LaneType.SUMMARY; }

        @Override
        public List<Candidate> search(String query, SearchFilters filters, int limit) {
            List<Float> vector = singleQueryVector(embedding, query);
            return RetrievalDomain.rrf(List.of(
                    index.searchSummaryBm25(query, filters, limit),
                    index.searchSummaryVector(vector, filters, limit)), limit, 60);
        }
    }

    public static final class Wiki implements RetrievalLane {
        private final DerivedSearchIndex index;
        private final EmbeddingPort embedding;

        public Wiki(DerivedSearchIndex index, EmbeddingPort embedding) {
            this.index = index;
            this.embedding = embedding;
        }

        @Override public LaneType type() { return LaneType.WIKI; }

        @Override
        public List<Candidate> search(String query, SearchFilters filters, int limit) {
            List<Float> vector = singleQueryVector(embedding, query);
            return RetrievalDomain.rrf(List.of(
                    index.searchWikiBm25(query, filters, limit),
                    index.searchWikiVector(vector, filters, limit)), limit, 60);
        }
    }

    private static List<Float> singleQueryVector(EmbeddingPort embedding, String query) {
        List<List<Float>> vectors = embedding.embed(List.of(query));
        if (vectors == null || vectors.size() != 1 || vectors.get(0) == null || vectors.get(0).isEmpty()) {
            throw new IllegalStateException("query embedding returned invalid vector");
        }
        return vectors.get(0);
    }
}
