package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.RerankerPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class OpenAiCompatibleModelServingAdapter implements EmbeddingPort, RerankerPort {
  private static final int SINGLE_EMBEDDING_TIMEOUT_RETRIES = 2;

  /**
   * Keep each model request comfortably below the embedding model context window. This is a
   * request-size guard only: long source text is split into contiguous pieces and every character
   * contributes to the final weighted vector. We never truncate, summarize, or insert an ellipsis.
   */
  private static final int MAX_EMBED_REQUEST_CHARS = 4_000;

  private static final int LONG_TEXT_PIECE_BATCH_SIZE = 4;

  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final Store configurationStore;
  private final Semaphore embeddingPermits;
  private final Semaphore rerankPermits;

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
    this(
        embeddingBaseUrl,
        embeddingApiKey,
        embeddingModel,
        rerankBaseUrl,
        rerankApiKey,
        rerankModel,
        dimensions,
        batchSize,
        embeddingTimeout,
        rerankTimeout,
        new ObjectMapper());
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
      Duration rerankTimeout,
      ObjectMapper mapper) {
    this(
        embeddingBaseUrl,
        embeddingApiKey,
        embeddingModel,
        rerankBaseUrl,
        rerankApiKey,
        rerankModel,
        dimensions,
        batchSize,
        embeddingTimeout,
        rerankTimeout,
        mapper,
        1,
        1);
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
      Duration rerankTimeout,
      ObjectMapper mapper,
      int embeddingMaxConcurrency,
      int rerankMaxConcurrency) {
    this(
        new FixedStore(
            new EffectiveConfiguration(
                "http://unused",
                "",
                "unused-small",
                "unused-large",
                120,
                trimSlash(embeddingBaseUrl),
                normalizeApiKey(embeddingApiKey),
                requireText(embeddingModel, "embedding model"),
                dimensions,
                batchSize <= 0 ? 16 : batchSize,
                (int) positive(embeddingTimeout, Duration.ofSeconds(120)).toSeconds(),
                trimSlash(rerankBaseUrl),
                normalizeApiKey(rerankApiKey),
                requireText(rerankModel, "rerank model"),
                (int) positive(rerankTimeout, Duration.ofSeconds(30)).toSeconds(),
                java.time.Instant.EPOCH)),
        mapper,
        embeddingMaxConcurrency,
        rerankMaxConcurrency);
  }

  public OpenAiCompatibleModelServingAdapter(Store configurationStore, ObjectMapper mapper) {
    this(configurationStore, mapper, 1, 1);
  }

  public OpenAiCompatibleModelServingAdapter(
      Store configurationStore,
      ObjectMapper mapper,
      int embeddingMaxConcurrency,
      int rerankMaxConcurrency) {
    if (mapper == null) throw new IllegalArgumentException("object mapper is required");
    this.mapper = mapper;
    this.configurationStore =
        java.util.Objects.requireNonNull(configurationStore, "configuration store");
    this.embeddingPermits =
        new Semaphore(
            positiveConcurrency(embeddingMaxConcurrency, "embedding max concurrency"), true);
    this.rerankPermits =
        new Semaphore(positiveConcurrency(rerankMaxConcurrency, "rerank max concurrency"), true);
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  @Override
  public int dimensions() {
    return configurationStore.current().embeddingDimensions();
  }

  @Override
  public int preferredBatchSize() {
    return Math.max(1, configurationStore.current().embeddingBatchSize());
  }

  @Override
  public List<List<Float>> embed(List<String> texts) {
    if (texts == null || texts.isEmpty()) return List.of();
    EffectiveConfiguration config = configurationStore.current();
    validate(config);
    List<List<Float>> all = new ArrayList<>(texts.size());
    int batchSize = Math.max(1, config.embeddingBatchSize());
    for (int from = 0; from < texts.size(); from += batchSize) {
      int to = Math.min(texts.size(), from + batchSize);
      try {
        all.addAll(embedBatch(texts.subList(from, to), config));
      } catch (IllegalStateException failure) {
        int maxChars =
            texts.subList(from, to).stream()
                .mapToInt(value -> value == null ? 0 : value.length())
                .max()
                .orElse(0);
        throw new IllegalStateException(
            "embedding batch failed at offset="
                + from
                + ", size="
                + (to - from)
                + ", max_chars="
                + maxChars,
            failure);
      }
    }
    return List.copyOf(all);
  }

  private List<List<Float>> embedBatch(List<String> texts, EffectiveConfiguration config) {
    if (texts.stream()
        .noneMatch(value -> value != null && value.length() > MAX_EMBED_REQUEST_CHARS)) {
      return embedBatchRequest(texts, config, 0, false);
    }

    // A batch containing a very long parser block is handled in input order. Normal inputs are
    // still grouped into one request, while only the long input is fanned out into contiguous
    // pieces. This preserves the one-vector-per-input contract without making every neighbouring
    // chunk a separate HTTP request.
    List<List<Float>> result = new ArrayList<>(texts.size());
    for (int i = 0; i < texts.size(); i++) result.add(null);
    List<String> normalTexts = new ArrayList<>();
    List<Integer> normalIndexes = new ArrayList<>();
    for (int i = 0; i < texts.size(); i++) {
      String text = texts.get(i);
      if (text != null && text.length() > MAX_EMBED_REQUEST_CHARS) {
        flushNormalEmbeddings(normalTexts, normalIndexes, result, config);
        result.set(i, embedLongText(text, config));
      } else {
        normalTexts.add(text);
        normalIndexes.add(i);
      }
    }
    flushNormalEmbeddings(normalTexts, normalIndexes, result, config);
    if (result.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalStateException("embedding long-text fan-out returned incomplete result");
    }
    return List.copyOf(result);
  }

  private void flushNormalEmbeddings(
      List<String> texts,
      List<Integer> indexes,
      List<List<Float>> result,
      EffectiveConfiguration config) {
    if (texts.isEmpty()) return;
    List<List<Float>> vectors = embedBatchRequest(texts, config, 0, false);
    if (vectors.size() != indexes.size()) {
      throw new IllegalStateException("embedding normal fan-out count mismatch");
    }
    for (int i = 0; i < vectors.size(); i++) result.set(indexes.get(i), vectors.get(i));
    texts.clear();
    indexes.clear();
  }

  private List<Float> embedLongText(String text, EffectiveConfiguration config) {
    List<String> pieces = splitLongText(text);
    List<List<Float>> vectors = new ArrayList<>(pieces.size());
    for (int from = 0; from < pieces.size(); from += LONG_TEXT_PIECE_BATCH_SIZE) {
      int to = Math.min(pieces.size(), from + LONG_TEXT_PIECE_BATCH_SIZE);
      List<List<Float>> embedded = embedBatchRequest(pieces.subList(from, to), config, 0, true);
      if (embedded.size() != to - from) {
        throw new IllegalStateException("embedding long-text piece count mismatch");
      }
      vectors.addAll(embedded);
    }
    return weightedAverage(vectors, pieces);
  }

  private List<List<Float>> embedBatchRequest(
      List<String> texts,
      EffectiveConfiguration config,
      int timeoutRetry,
      boolean allowSingleTimeoutRetry) {
    try {
      return withPermit(
          embeddingPermits,
          () -> {
            byte[] payload =
                postJsonBytes(
                    config.embeddingBaseUrl(),
                    "/v1/embeddings",
                    config.embeddingApiKey(),
                    Duration.ofSeconds(config.embeddingTimeoutSeconds()),
                    Map.of("model", config.embeddingModel(), "input", texts),
                    "embedding",
                    false);
            return decodeEmbeddingBatch(payload, texts.size(), config.embeddingDimensions());
          },
          "embedding");
    } catch (IllegalStateException failure) {
      // Some OpenAI-compatible providers reject a request when the aggregate input exceeds their
      // context/payload limit, even though every individual chunk is valid. Split only these
      // explicit request-size responses and retry the exact original strings. This keeps the
      // complete-content/evidence contract intact without turning authentication, model, or
      // structured-decode failures into an unbounded retry loop.
      if (texts.size() > 1 && isRequestSizeFailure(failure)) {
        int midpoint = texts.size() / 2;
        List<List<Float>> split = new ArrayList<>(texts.size());
        split.addAll(
            embedBatchRequest(texts.subList(0, midpoint), config, 0, allowSingleTimeoutRetry));
        split.addAll(
            embedBatchRequest(
                texts.subList(midpoint, texts.size()), config, 0, allowSingleTimeoutRetry));
        return List.copyOf(split);
      }
      if (!causedByTimeout(failure)) throw failure;
      if (texts.size() <= 1) {
        // A profile explicitly using single-item batches is the bulk-ingestion mode that needs
        // to survive a model-container restart between requests. Keep the historical bisection
        // behavior unchanged for larger configured batches: their timed-out child requests must
        // still fail closed rather than silently multiplying retries.
        if (!allowSingleTimeoutRetry && config.embeddingBatchSize() > 1) throw failure;
        if (timeoutRetry >= SINGLE_EMBEDDING_TIMEOUT_RETRIES) throw failure;
        sleepBeforeRetry(timeoutRetry);
        return embedBatchRequest(texts, config, timeoutRetry + 1, allowSingleTimeoutRetry);
      }
      int midpoint = texts.size() / 2;
      List<List<Float>> split = new ArrayList<>(texts.size());
      split.addAll(embedBatchRequest(texts.subList(0, midpoint), config, 0, false));
      split.addAll(embedBatchRequest(texts.subList(midpoint, texts.size()), config, 0, false));
      return List.copyOf(split);
    }
  }

  private static List<String> splitLongText(String text) {
    List<String> pieces = new ArrayList<>();
    int offset = 0;
    while (offset < text.length()) {
      int end = Math.min(text.length(), offset + MAX_EMBED_REQUEST_CHARS);
      if (end < text.length()) {
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline >= offset + (MAX_EMBED_REQUEST_CHARS / 2)) end = newline + 1;
      }
      // Do not split a UTF-16 surrogate pair at a piece boundary.
      if (end < text.length()
          && Character.isHighSurrogate(text.charAt(end - 1))
          && Character.isLowSurrogate(text.charAt(end))) end--;
      if (end <= offset) end = Math.min(text.length(), offset + MAX_EMBED_REQUEST_CHARS);
      pieces.add(text.substring(offset, end));
      offset = end;
    }
    return List.copyOf(pieces);
  }

  private static List<Float> weightedAverage(List<List<Float>> vectors, List<String> pieces) {
    if (vectors.isEmpty())
      throw new IllegalStateException("embedding long-text produced no pieces");
    int dimensions = vectors.get(0).size();
    double[] sum = new double[dimensions];
    double totalWeight = 0d;
    for (int pieceIndex = 0; pieceIndex < vectors.size(); pieceIndex++) {
      List<Float> vector = vectors.get(pieceIndex);
      if (vector.size() != dimensions)
        throw new IllegalStateException("embedding piece dimension mismatch");
      double weight = Math.max(1, pieces.get(pieceIndex).length());
      totalWeight += weight;
      for (int dimension = 0; dimension < dimensions; dimension++) {
        sum[dimension] += vector.get(dimension) * weight;
      }
    }
    double norm = 0d;
    for (int dimension = 0; dimension < dimensions; dimension++) {
      sum[dimension] /= totalWeight;
      norm += sum[dimension] * sum[dimension];
    }
    norm = Math.sqrt(norm);
    if (norm == 0d || Double.isNaN(norm) || Double.isInfinite(norm)) {
      throw new IllegalStateException("embedding long-text produced zero vector");
    }
    List<Float> output = new ArrayList<>(dimensions);
    for (double value : sum) output.add((float) (value / norm));
    return List.copyOf(output);
  }

  private List<List<Float>> decodeEmbeddingBatch(
      byte[] payload, int expectedCount, int expectedDimensions) {
    EmbeddingResponse response = decode(payload, EmbeddingResponse.class, "embedding");
    if (response == null || response.data() == null || response.data().size() != expectedCount) {
      throw new IllegalStateException("embedding response count mismatch");
    }
    List<EmbeddingItem> sorted =
        response.data().stream().sorted(Comparator.comparingInt(EmbeddingItem::index)).toList();
    List<List<Float>> vectors = new ArrayList<>(sorted.size());
    for (int i = 0; i < sorted.size(); i++) {
      EmbeddingItem item = sorted.get(i);
      if (item.index() != i)
        throw new IllegalStateException("embedding response index mismatch: " + item.index());
      if (item.embedding() == null || item.embedding().size() != expectedDimensions) {
        throw new IllegalStateException("embedding dimension mismatch at " + item.index());
      }
      vectors.add(List.copyOf(item.embedding()));
    }
    return List.copyOf(vectors);
  }

  private static boolean causedByTimeout(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof HttpTimeoutException) return true;
      current = current.getCause();
    }
    return false;
  }

  private static boolean isRequestSizeFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("HTTP 400")
            || normalized.contains("HTTP 413")
            || normalized.contains("HTTP 422")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  @Override
  public List<Double> rerank(String query, List<String> documents) {
    if (documents == null || documents.isEmpty()) return List.of();
    return withPermit(
        rerankPermits,
        () -> {
          EffectiveConfiguration config = configurationStore.current();
          validate(config);
          byte[] payload =
              postJsonBytes(
                  config.rerankBaseUrl(),
                  "/v1/rerank",
                  config.rerankApiKey(),
                  Duration.ofSeconds(config.rerankTimeoutSeconds()),
                  Map.of(
                      "model",
                      config.rerankModel(),
                      "query",
                      query,
                      "documents",
                      documents,
                      "top_n",
                      documents.size()),
                  "rerank",
                  false);
          RerankResponse response = decode(payload, RerankResponse.class, "rerank");
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
        },
        "rerank");
  }

  public record EmbeddingResponse(List<EmbeddingItem> data) {}

  public record EmbeddingItem(int index, List<Float> embedding) {}

  public record RerankResponse(List<RerankItem> results) {}

  public record RerankItem(int index, @JsonProperty("relevance_score") double relevanceScore) {}

  private byte[] postJsonBytes(
      String baseUrl,
      String path,
      String apiKey,
      Duration timeout,
      Object body,
      String operation,
      boolean retryTimeout) {
    final byte[] requestBody;
    try {
      requestBody = mapper.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new IllegalStateException("cannot encode " + operation + " request", e);
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
    if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
    return sendWithRetry(request, operation, retryTimeout, 0);
  }

  private byte[] sendWithRetry(
      HttpRequest.Builder request, String operation, boolean retryTimeout, int attempt) {
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            operation + " request failed: HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException e) {
      if (isTransient(e) && attempt < 2 && (retryTimeout || !causedByTimeout(e))) {
        sleepBeforeRetry(attempt);
        return sendWithRetry(request, operation, retryTimeout, attempt + 1);
      }
      throw new IllegalStateException(
          operation + " request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " request interrupted: " + e.getMessage(), e);
    }
  }

  private static boolean isTransient(IOException e) {
    Throwable current = e;
    while (current != null) {
      if (current instanceof ConnectException || current instanceof HttpTimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void sleepBeforeRetry(int attempt) {
    try {
      Thread.sleep(1000L * (attempt + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("embedding retry interrupted", e);
    }
  }

  private <T> T decode(byte[] payload, Class<T> type, String operation) {
    if (payload == null || payload.length == 0) {
      throw new IllegalStateException(operation + " returned empty response");
    }
    try {
      return mapper.readValue(payload, type);
    } catch (IOException e) {
      throw new IllegalStateException("cannot parse " + operation + " response", e);
    }
  }

  private static <T> T withPermit(Semaphore permits, Supplier<T> action, String operation) {
    boolean acquired = false;
    try {
      permits.acquire();
      acquired = true;
      return action.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " concurrency wait interrupted", e);
    } finally {
      if (acquired) permits.release();
    }
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static int positiveConcurrency(int value, String field) {
    if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    return value;
  }

  private static String trimSlash(String value) {
    String text = requireText(value, "base URL");
    while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
    return text;
  }

  private static String normalizeApiKey(String value) {
    return value == null ? "" : value.trim();
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  private static void validate(EffectiveConfiguration config) {
    trimSlash(config.embeddingBaseUrl());
    trimSlash(config.rerankBaseUrl());
    requireText(config.embeddingModel(), "embedding model");
    requireText(config.rerankModel(), "rerank model");
    if (config.embeddingDimensions() <= 0)
      throw new IllegalArgumentException("embedding dimensions must be positive");
    if (config.embeddingBatchSize() <= 0)
      throw new IllegalArgumentException("embedding batch size must be positive");
    if (config.embeddingTimeoutSeconds() <= 0)
      throw new IllegalArgumentException("embedding timeout must be positive");
    if (config.rerankTimeoutSeconds() <= 0)
      throw new IllegalArgumentException("rerank timeout must be positive");
  }

  private static final class FixedStore implements Store {
    private final EffectiveConfiguration configuration;

    private FixedStore(EffectiveConfiguration configuration) {
      validate(configuration);
      this.configuration = configuration;
    }

    @Override
    public EffectiveConfiguration current() {
      return configuration;
    }

    @Override
    public EffectiveConfiguration update(UpdateCommand command) {
      throw new UnsupportedOperationException("fixed model configuration cannot be updated");
    }
  }
}
