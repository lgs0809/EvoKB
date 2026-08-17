package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.RerankerPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class OpenAiCompatibleModelServingAdapter implements EmbeddingPort, RerankerPort {
  private final RestClient embeddingClient;
  private final RestClient rerankClient;
  private final String embeddingModel;
  private final String rerankModel;
  private final int dimensions;
  private final int batchSize;

  public OpenAiCompatibleModelServingAdapter(
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      int dimensions,
      int batchSize) {
    this(
        embeddingBaseUrl,
        embeddingApiKey,
        embeddingModel,
        rerankBaseUrl,
        rerankApiKey,
        rerankModel,
        dimensions,
        batchSize,
        Duration.ofSeconds(120),
        Duration.ofSeconds(30));
  }

  public OpenAiCompatibleModelServingAdapter(
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      int dimensions,
      int batchSize,
      Duration embeddingTimeout,
      Duration rerankTimeout) {
    this.embeddingClient =
        client(
            trimSlash(embeddingBaseUrl),
            embeddingApiKey,
            positive(embeddingTimeout, Duration.ofSeconds(120)));
    this.rerankClient =
        client(
            trimSlash(rerankBaseUrl),
            rerankApiKey,
            positive(rerankTimeout, Duration.ofSeconds(30)));
    this.embeddingModel = requireText(embeddingModel, "embedding model");
    this.rerankModel = requireText(rerankModel, "rerank model");
    if (dimensions <= 0)
      throw new IllegalArgumentException("embedding dimensions must be positive");
    this.dimensions = dimensions;
    this.batchSize = batchSize <= 0 ? 16 : batchSize;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @Override
  public List<List<Float>> embed(List<String> texts) {
    if (texts == null || texts.isEmpty()) return List.of();
    List<List<Float>> all = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += batchSize) {
      int to = Math.min(texts.size(), from + batchSize);
      EmbeddingResponse response =
          embeddingClient
              .post()
              .uri("/v1/embeddings")
              .body(Map.of("model", embeddingModel, "input", texts.subList(from, to)))
              .retrieve()
              .body(EmbeddingResponse.class);
      if (response == null || response.data() == null || response.data().size() != to - from) {
        throw new IllegalStateException("embedding response count mismatch");
      }
      List<EmbeddingItem> sorted =
          response.data().stream().sorted(Comparator.comparingInt(EmbeddingItem::index)).toList();
      for (int i = 0; i < sorted.size(); i++) {
        EmbeddingItem item = sorted.get(i);
        if (item.index() != i)
          throw new IllegalStateException("embedding response index mismatch: " + item.index());
        if (item.embedding() == null || item.embedding().size() != dimensions) {
          throw new IllegalStateException("embedding dimension mismatch at " + item.index());
        }
        all.add(List.copyOf(item.embedding()));
      }
    }
    return List.copyOf(all);
  }

  @Override
  public List<Double> rerank(String query, List<String> documents) {
    if (documents == null || documents.isEmpty()) return List.of();
    RerankResponse response =
        rerankClient
            .post()
            .uri("/v1/rerank")
            .body(
                Map.of(
                    "model",
                    rerankModel,
                    "query",
                    query,
                    "documents",
                    documents,
                    "top_n",
                    documents.size()))
            .retrieve()
            .body(RerankResponse.class);
    if (response == null || response.results() == null || response.results().isEmpty()) {
      throw new IllegalStateException("rerank returned empty results");
    }
    Double[] scores = new Double[documents.size()];
    for (RerankItem item : response.results()) {
      if (item.index() < 0 || item.index() >= scores.length)
        throw new IllegalStateException("rerank invalid index " + item.index());
      scores[item.index()] = item.relevanceScore();
    }
    List<Double> output = new ArrayList<>(scores.length);
    for (int i = 0; i < scores.length; i++) {
      if (scores[i] == null)
        throw new IllegalStateException("rerank missing score for document " + i);
      output.add(scores[i]);
    }
    return List.copyOf(output);
  }

  public record EmbeddingResponse(List<EmbeddingItem> data) {}

  public record EmbeddingItem(int index, List<Float> embedding) {}

  public record RerankResponse(List<RerankItem> results) {}

  public record RerankItem(int index, @JsonProperty("relevance_score") double relevanceScore) {}

  private static RestClient client(String baseUrl, String apiKey, Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(readTimeout);
    RestClient.Builder builder =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    if (apiKey != null && !apiKey.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
    }
    return builder.build();
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static String trimSlash(String value) {
    String text = requireText(value, "base URL");
    while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
    return text;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
