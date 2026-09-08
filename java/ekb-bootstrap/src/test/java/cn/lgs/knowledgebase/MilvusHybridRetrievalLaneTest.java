package cn.lgs.knowledgebase;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.infrastructure.search.MilvusHybridRetrievalLane;
import cn.lgs.knowledgebase.infrastructure.search.MilvusKnowledgeIndexAdapter;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MilvusHybridRetrievalLaneTest {
  private final MilvusKnowledgeIndexAdapter index = mock(MilvusKnowledgeIndexAdapter.class);
  private final EmbeddingPort embeddings = mock(EmbeddingPort.class);
  private final MilvusHybridRetrievalLane lane = new MilvusHybridRetrievalLane(index, embeddings);
  private final SearchFilters scope = mock(SearchFilters.class);
  private final TransientDependencyFailure timeout =
      new TransientDependencyFailure("embedding failed", new HttpTimeoutException("timeout"));

  @Test
  void embeddingTimeoutRetainsAuthorizedSparseEvidence() {
    when(embeddings.embed(List.of("question"))).thenThrow(timeout);
    when(index.searchAllBm25("question", scope, 50)).thenReturn(List.of(candidate("sparse")));

    assertEquals(
        List.of("sparse"), lane.search("question", scope, 8).stream().map(Candidate::id).toList());
    verify(index, never()).searchAllVector(anyList(), any(), anyInt());
  }

  @Test
  void timeoutWithNoSparseEvidenceRemainsAnOperationalFailure() {
    when(embeddings.embed(List.of("question"))).thenThrow(timeout);
    when(index.searchAllBm25("question", scope, 50)).thenReturn(List.of());

    assertSame(
        timeout,
        assertThrows(TransientDependencyFailure.class, () -> lane.search("question", scope, 8)));
  }

  @Test
  void successfulEmbeddingKeepsTheTwoRecallArms() {
    var vector = List.of(1F);
    when(embeddings.embed(List.of("question"))).thenReturn(List.of(vector));
    when(index.searchAllBm25("question", scope, 50)).thenReturn(List.of(candidate("sparse")));
    when(index.searchAllVector(vector, scope, 50)).thenReturn(List.of(candidate("dense")));

    assertEquals(2, lane.search("question", scope, 8).size());
    verify(index).searchAllVector(vector, scope, 50);
  }

  private static Candidate candidate(String id) {
    return new Candidate(
        id,
        "document",
        "version",
        LaneType.RAW,
        "evidence text",
        1,
        List.of("evidence-" + id),
        Map.of());
  }
}
