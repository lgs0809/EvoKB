package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import java.util.List;

/** One round has a global dense50+sparse50 budget across all searchable asset types. */
public final class MilvusHybridRetrievalLane implements RetrievalLane {
  private static final System.Logger LOGGER =
      System.getLogger(MilvusHybridRetrievalLane.class.getName());
  private static final int RECALL_LIMIT = 50;
  private static final int RRF_CONSTANT = 60;
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
    var sparse = index.searchAllBm25(query, filters, RECALL_LIMIT);
    List<List<Float>> vector;
    try {
      vector = embeddings.embed(List.of(query));
    } catch (RuntimeException failure) {
      // Do not turn an outage into "no matching evidence". The normal query pipeline
      // still resolves and verifies the authorized evidence from the sparse arm.
      if (!TransientDependencyFailure.causedBy(failure) || sparse.isEmpty()) throw failure;
      LOGGER.log(System.Logger.Level.WARNING, "MILVUS_EMBEDDING_UNAVAILABLE_USING_SPARSE_RECALL");
      return RetrievalDomain.rrf(List.of(sparse), RECALL_LIMIT, RRF_CONSTANT);
    }
    if (vector == null || vector.size() != 1)
      throw new IllegalStateException("query embedding is missing");
    return RetrievalDomain.rrf(
        List.of(sparse, index.searchAllVector(vector.get(0), filters, RECALL_LIMIT)),
        RECALL_LIMIT,
        RRF_CONSTANT);
  }
}
