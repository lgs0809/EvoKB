package cn.lgs.knowledgebase.infrastructure.graph;

import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.application.port.Ports.RetrievalLane;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import java.util.List;

public final class GraphRetrievalLane implements RetrievalLane {
  private final GraphStore graph;

  public GraphRetrievalLane(GraphStore graph) {
    if (graph == null) throw new IllegalArgumentException("graph store is required");
    this.graph = graph;
  }

  @Override
  public LaneType type() {
    return LaneType.GRAPH;
  }

  @Override
  public List<Candidate> search(String query, SearchFilters filters, int limit) {
    return graph.search(query, filters, limit);
  }
}
