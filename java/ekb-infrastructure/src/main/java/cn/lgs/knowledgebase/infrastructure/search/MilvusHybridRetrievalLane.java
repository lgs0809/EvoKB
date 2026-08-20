package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import java.util.List;

/** One round has a global dense50+sparse50 budget across all searchable asset types. */
public final class MilvusHybridRetrievalLane implements RetrievalLane {
  private final MilvusKnowledgeIndexAdapter index;
  private final EmbeddingPort embeddings;

  public MilvusHybridRetrievalLane(MilvusKnowledgeIndexAdapter index, EmbeddingPort embeddings) {
    this.index = index;
    this.embeddings = embeddings;
  }

  @Override
  public LaneType type() {
    return LaneType.RAW;
  }

  @Override
  public List<Candidate> search(String query, SearchFilters filters, int limit) {
    var vector = embeddings.embed(List.of(query));
    if (vector == null || vector.size() != 1)
      throw new IllegalStateException("query embedding is missing");
    return RetrievalDomain.rrf(
        List.of(
            index.searchAllBm25(query, filters, 50),
            index.searchAllVector(vector.get(0), filters, 50)),
        50,
        60);
  }
}
