package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small bounded cache for retrieval query embeddings. Query embeddings are deterministic for the
 * configured model, and Raw/Summary/Wiki lanes receive the same instance so one query is embedded
 * once instead of once per lane. Document/chunk embedding does not use this adapter.
 */
public final class CachedQueryEmbeddingPort implements EmbeddingPort {
  private final EmbeddingPort delegate;
  private final Map<String, List<Float>> cache;

  public CachedQueryEmbeddingPort(EmbeddingPort delegate, int maxEntries) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
    this.cache =
        new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, List<Float>> eldest) {
            return size() > maxEntries;
          }
        };
  }

  @Override
  public int dimensions() {
    return delegate.dimensions();
  }

  @Override
  public List<List<Float>> embed(List<String> texts) {
    if (texts == null || texts.size() != 1 || texts.get(0) == null) return delegate.embed(texts);
    String text = texts.get(0);
    String key = KnowledgeDomain.sha256(text);
    synchronized (cache) {
      List<Float> cached = cache.get(key);
      if (cached != null) return List.of(cached);
    }
    List<List<Float>> embedded = delegate.embed(texts);
    if (embedded == null
        || embedded.size() != 1
        || embedded.get(0) == null
        || embedded.get(0).isEmpty()) return embedded;
    List<Float> vector = List.copyOf(embedded.get(0));
    synchronized (cache) {
      cache.put(key, vector);
    }
    return List.of(vector);
  }
}
