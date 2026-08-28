package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingBatch;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;
import cn.lgs.knowledgebase.application.port.Ports.RerankerPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class OpenAiCompatibleModelServingAdapter implements EmbeddingPort, RerankerPort {
  private static final int MAX_RERANK_BATCH_SIZE = 4;

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
    return embedInputs(texts, false);
  }

  /** Token-bounded raw blocks are encoded intact, including inputs longer than 4,000 chars. */
  public EmbeddingPort rawEmbeddingPort() {
    return new EmbeddingPort() {
      public int dimensions() {
        return OpenAiCompatibleModelServingAdapter.this.dimensions();
      }

      public int preferredBatchSize() {
        return OpenAiCompatibleModelServingAdapter.this.preferredBatchSize();
      }

      public List<List<Float>> embed(List<String> texts) {
        return embedInputs(texts, true);
      }

      public Optional<EncodingIdentity> currentEncodingIdentity() {
        return residentEncodingIdentity();
      }

      public EmbeddingBatch embedWithIdentity(List<String> texts) {
        if (texts == null || texts.isEmpty())
          return new EmbeddingBatch(List.of(), Optional.empty());
        EffectiveConfiguration config = configurationStore.current();
        validate(config);
        List<List<Float>> vectors = new ArrayList<>();
        List<Optional<EncodingIdentity>> identities = new ArrayList<>();
        int size = Math.max(1, config.embeddingBatchSize());
        for (int offset = 0; offset < texts.size(); offset += size) {
          EmbeddingBatch batch =
              requestEmbeddingBatch(
                  texts.subList(offset, Math.min(texts.size(), offset + size)), config, true);
          vectors.addAll(batch.vectors());
          identities.addAll(batch.identities());
        }
        return new EmbeddingBatch(vectors, identities);
      }
    };
  }

  private List<List<Float>> embedInputs(List<String> texts, boolean wholeInputs) {
    if (texts == null || texts.isEmpty()) return List.of();
    EffectiveConfiguration config = configurationStore.current();
    validate(config);
    List<List<Float>> all = new ArrayList<>(texts.size());
    int batchSize = Math.max(1, config.embeddingBatchSize());
    for (int from = 0; from < texts.size(); from += batchSize) {
      int to = Math.min(texts.size(), from + batchSize);
      try {
        all.addAll(
            wholeInputs
                ? embedBatchRequest(texts.subList(from, to), config)
                : embedBatch(texts.subList(from, to), config));
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
      return embedBatchRequest(texts, config);
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
    List<List<Float>> vectors = embedBatchRequest(texts, config);
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
      List<List<Float>> embedded = embedBatchRequest(pieces.subList(from, to), config);
      if (embedded.size() != to - from) {
        throw new IllegalStateException("embedding long-text piece count mismatch");
      }
      vectors.addAll(embedded);
    }
    return weightedAverage(vectors, pieces);
  }

  private List<List<Float>> embedBatchRequest(List<String> texts, EffectiveConfiguration config) {
    return requestEmbeddingBatch(texts, config, false).vectors();
  }

  private EmbeddingBatch requestEmbeddingBatch(
      List<String> texts, EffectiveConfiguration config, boolean certify) {
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
                    Map.of(
                        "model",
                        config.embeddingModel(),
                        "input",
                        texts,
                        "dimensions",
                        config.embeddingDimensions()),
                    "embedding",
                    false);
            List<List<Float>> vectors =
                decodeEmbeddingBatch(payload, texts.size(), config.embeddingDimensions());
            Optional<EncodingIdentity> identity =
                certify
                    ? new EncodingIdentityCodec(mapper)
                        .response(
                            decode(payload, EmbeddingResponse.class, "embedding")
                                .encodingIdentity(),
                            config.embeddingModel(),
                            config.embeddingDimensions(),
                            texts,
                            vectors)
                    : Optional.empty();
            return new EmbeddingBatch(vectors, identity);
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
        return combine(
            requestEmbeddingBatch(texts.subList(0, midpoint), config, certify),
            requestEmbeddingBatch(texts.subList(midpoint, texts.size()), config, certify));
      }
      // A timeout has consumed this request's total observation budget. Starting another
      // timeout window or children here would hide the true budget from the caller.
      throw failure;
    }
  }

  private static EmbeddingBatch combine(EmbeddingBatch first, EmbeddingBatch second) {
    List<List<Float>> vectors = new ArrayList<>(first.vectors());
    vectors.addAll(second.vectors());
    List<Optional<EncodingIdentity>> identities = new ArrayList<>(first.identities());
    identities.addAll(second.identities());
    return new EmbeddingBatch(vectors, identities);
  }

  private Optional<EncodingIdentity> residentEncodingIdentity() {
    EffectiveConfiguration config = configurationStore.current();
    validate(config);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    config.embeddingBaseUrl()
                        + "/v1/embedding-identity?input_type=document&dimensions="
                        + config.embeddingDimensions()))
            .timeout(Duration.ofSeconds(3))
            .GET();
    if (!config.embeddingApiKey().isBlank())
      request.header("Authorization", "Bearer " + config.embeddingApiKey());
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      // Metadata is observational: eviction, an uncertified dimension or an older server is
      // unknown. Do not infer or trigger a model load, and do not spend transport retry budget.
      if (response.statusCode() == 503 || response.statusCode() == 404) return Optional.empty();
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException(
            "encoding identity request failed: HTTP " + response.statusCode());
      return new EncodingIdentityCodec(mapper)
          .profile(
              decode(response.body(), JsonNode.class, "encoding identity"),
              config.embeddingModel(),
              config.embeddingDimensions());
    } catch (IOException failure) {
      return Optional.empty();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("encoding identity request interrupted", failure);
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
          List<Double> output = new ArrayList<>(documents.size());
          // Pairwise reranking scores remain in the original candidate order. Bound each
          // request rather than abandoning a large CPU inference while it still owns the
          // shared local service and blocks the next round's embedding request.
          for (int from = 0; from < documents.size(); from += MAX_RERANK_BATCH_SIZE) {
            output.addAll(
                rerankBatch(
                    query,
                    documents.subList(
                        from, Math.min(documents.size(), from + MAX_RERANK_BATCH_SIZE)),
                    config));
          }
          return List.copyOf(output);
        },
        "rerank");
  }

  private List<Double> rerankBatch(
      String query, List<String> documents, EffectiveConfiguration config) {
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
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmbeddingResponse(
      List<EmbeddingItem> data, @JsonProperty("encoding_identity") JsonNode encodingIdentity) {
    public EmbeddingResponse(List<EmbeddingItem> data) {
      this(data, null);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmbeddingItem(int index, List<Float> embedding) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RerankResponse(List<RerankItem> results) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
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
    return BudgetedHttpRetry.send(httpClient, request.build(), operation, retryTimeout);
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
