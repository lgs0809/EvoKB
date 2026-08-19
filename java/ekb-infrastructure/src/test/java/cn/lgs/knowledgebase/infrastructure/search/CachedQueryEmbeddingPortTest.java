package cn.lgs.knowledgebase.infrastructure.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachedQueryEmbeddingPortTest {
  @Test
  void reusesSingleQueryEmbeddingAcrossRetrievalLanes() {
    AtomicInteger calls = new AtomicInteger();
    EmbeddingPort delegate =
        new EmbeddingPort() {
          @Override
          public int dimensions() {
            return 2;
          }

          @Override
          public List<List<Float>> embed(List<String> texts) {
            calls.incrementAndGet();
            return texts.stream().map(text -> List.of((float) text.length(), 1.0f)).toList();
          }
        };
    CachedQueryEmbeddingPort cached = new CachedQueryEmbeddingPort(delegate, 16);

    List<List<Float>> first = cached.embed(List.of("same query"));
    List<List<Float>> second = cached.embed(List.of("same query"));
    List<List<Float>> third = cached.embed(List.of("different query"));

    assertEquals(first, second);
    assertEquals(2, calls.get());
    assertEquals(List.of(15.0f, 1.0f), third.get(0));
  }
}
