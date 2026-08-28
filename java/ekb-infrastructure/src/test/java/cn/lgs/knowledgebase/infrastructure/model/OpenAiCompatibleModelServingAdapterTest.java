package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelServingAdapterTest {
  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange ->
            respond(
                exchange,
                "{\"object\":\"list\",\"model\":\"embedding-model\",\"usage\":{\"total_tokens\":3},\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    server.createContext(
        "/v1/rerank",
        exchange ->
            respond(
                exchange,
                "{\"model\":\"rerank-model\",\"usage\":{\"total_tokens\":3},\"results\":[{\"index\":0,\"relevance_score\":0.8,\"document\":{\"text\":\"a\"}},{\"index\":1,\"relevance_score\":0.2,\"document\":{\"text\":\"b\"}}]}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void tokenBoundedRawInputIsSentIntactEvenBeyondLegacyCharacterLimit() {
    server.removeContext("/v1/embeddings");
    var bodies = new CopyOnWriteArrayList<String>();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });
    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            new ObjectMapper());
    String complete =
        "A complete exceptionallylongterm document paragraph. ".repeat(100)
            + "FINAL SOURCE CONDITION";
    assertTrue(complete.length() > 4000);
    assertEquals(
        List.of(List.of(0.1f, 0.2f, 0.3f)), adapter.rawEmbeddingPort().embed(List.of(complete)));
    assertEquals(1, bodies.size());
    try {
      assertEquals(
          complete, new ObjectMapper().readTree(bodies.get(0)).path("input").get(0).asText());
    } catch (IOException error) {
      throw new AssertionError(error);
    }
  }

  @Test
  void oversizeWholeRawInputFailsWithoutSplittingItsTextOrAveragingVectors() {
    server.removeContext("/v1/embeddings");
    var calls = new AtomicInteger();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          calls.incrementAndGet();
          respondStatus(exchange, 413, "input too large");
        });
    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            new ObjectMapper());
    assertThrows(
        IllegalStateException.class,
        () -> adapter.rawEmbeddingPort().embed(List.of("unchanged original ".repeat(500))));
    assertEquals(1, calls.get());
  }

  @Test
  void parsesStandardProviderFieldsWithStrictMapperAndOctetStreamContentType() {
    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            16,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            new ObjectMapper());

    assertEquals(List.of(List.of(0.1f, 0.2f, 0.3f)), adapter.embed(List.of("text")));
    assertEquals(List.of(0.8d, 0.2d), adapter.rerank("query", List.of("a", "b")));
  }

  @Test
  void requestsFrozenOutputDimensionsInsteadOfUsingProviderDefault() {
    server.removeContext("/v1/embeddings");
    AtomicInteger requested = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          requested.set(mapper.readTree(exchange.getRequestBody()).path("dimensions").asInt());
          respond(
              exchange,
              requested.get() == 3
                  ? "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"
                  : "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}");
        });
    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            mapper);
    assertEquals(
        List.of(List.of(0.1f, 0.2f, 0.3f)), adapter.embed(List.of("complete original input")));
    assertEquals(3, requested.get());
  }

  @Test
  void retriesTransientHttpResponsesAndRecoversTheUnchangedEmbeddingRequest() {
    server.removeContext("/v1/embeddings");
    AtomicInteger calls = new AtomicInteger();
    List<String> bodies = new CopyOnWriteArrayList<>();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int count = calls.incrementAndGet();
          if (count < 3) respondStatus(exchange, 503, "temporarily unavailable");
          else respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });
    var adapter = retryAdapter();
    assertEquals(List.of(List.of(0.1f, 0.2f, 0.3f)), adapter.embed(List.of("完整问题")));
    assertEquals(3, calls.get());
    assertEquals(1, bodies.stream().distinct().count());
  }

  @Test
  void exhaustedHttpRetriesRemainTypedTransientFailures() {
    server.removeContext("/v1/embeddings");
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          calls.incrementAndGet();
          respondStatus(exchange, 429, "temporarily overloaded");
        });
    var failure =
        assertThrows(IllegalStateException.class, () -> retryAdapter().embed(List.of("完整问题")));
    assertTrue(
        cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure.causedBy(failure));
    assertEquals(5, calls.get());
  }

  @Test
  void permanentAuthorizationFailureDoesNotRetryOrBecomeTransient() {
    server.removeContext("/v1/embeddings");
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          calls.incrementAndGet();
          respondStatus(exchange, 401, "unauthorized");
        });
    var failure =
        assertThrows(IllegalStateException.class, () -> retryAdapter().embed(List.of("完整问题")));
    assertTrue(
        !cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure.causedBy(failure));
    assertEquals(1, calls.get());
  }

  @Test
  void pairwiseRerankBatchesPreserveEveryCandidateAndItsGlobalScore() {
    server.removeContext("/v1/rerank");
    AtomicInteger calls = new AtomicInteger();
    List<String> seen = new CopyOnWriteArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    server.createContext(
        "/v1/rerank",
        exchange -> {
          var body = mapper.readTree(exchange.getRequestBody());
          var docs = body.path("documents");
          assertTrue(docs.size() <= 4);
          assertEquals(docs.size(), body.path("top_n").asInt());
          calls.incrementAndGet();
          StringBuilder result = new StringBuilder("{\"results\":[");
          for (int i = docs.size() - 1; i >= 0; i--) {
            String document = docs.get(i).asText();
            seen.add(document);
            if (i != docs.size() - 1) result.append(',');
            result
                .append("{\"index\":")
                .append(i)
                .append(",\"relevance_score\":")
                .append(Integer.parseInt(document.substring(4)) / 10.0)
                .append('}');
          }
          result.append("]}");
          respond(exchange, result.toString());
        });
    List<String> inputs = java.util.stream.IntStream.range(0, 9).mapToObj(i -> "doc-" + i).toList();
    List<Double> expected = java.util.stream.IntStream.range(0, 9).mapToObj(i -> i / 10.0).toList();
    assertEquals(expected, retryAdapter().rerank("原始问题", inputs));
    assertEquals(3, calls.get());
    assertEquals(new java.util.HashSet<>(inputs), new java.util.HashSet<>(seen));
    assertEquals(inputs.size(), seen.size());
  }

  private OpenAiCompatibleModelServingAdapter retryAdapter() {
    return new OpenAiCompatibleModelServingAdapter(
        baseUrl,
        "",
        "embedding-model",
        baseUrl,
        "",
        "rerank-model",
        3,
        1,
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        new ObjectMapper());
  }

  private static void respondStatus(HttpExchange exchange, int status, String text)
      throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var stream = exchange.getResponseBody()) {
      stream.write(bytes);
    }
  }

  @Test
  void embeddingTimeoutCannotCreateAnotherObservationWindowOrFanOutExpiredRequest()
      throws Exception {
    server.stop(0);
    AtomicInteger multiRequests = new AtomicInteger();
    AtomicInteger singleRequests = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          int count = mapper.readTree(exchange.getRequestBody()).path("input").size();
          if (count > 1) {
            multiRequests.incrementAndGet();
            try {
              Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          } else {
            singleRequests.incrementAndGet();
          }
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int i = 0; i < count; i++) {
            if (i > 0) body.append(',');
            body.append("{\"index\":").append(i).append(",\"embedding\":[0.1,0.2,0.3]}");
          }
          body.append("]}");
          respond(exchange, body.toString());
        });
    server.createContext(
        "/v1/rerank",
        exchange -> respond(exchange, "{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            mapper);

    long started = System.nanoTime();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.embed(List.of("one", "two", "three", "four")));
    assertTrue(
        cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure.causedBy(failure));
    assertTrue((System.nanoTime() - started) / 1_000_000d < 1600);
    assertEquals(1, multiRequests.get());
    assertEquals(0, singleRequests.get());
  }

  @Test
  void splitsEmbeddingBatchAfterProviderRejectsAggregateRequestSize() throws Exception {
    server.stop(0);
    ObjectMapper mapper = new ObjectMapper();
    AtomicInteger rejectedRequests = new AtomicInteger();
    List<String> receivedInputs = new CopyOnWriteArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          var input = mapper.readTree(exchange.getRequestBody()).path("input");
          int totalChars = 0;
          for (var item : input) {
            String text = item.asText();
            receivedInputs.add(text);
            totalChars += text.length();
          }
          if (totalChars > 12) {
            rejectedRequests.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"input is too large\"}");
            return;
          }
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int i = 0; i < input.size(); i++) {
            if (i > 0) body.append(',');
            body.append("{\"index\":").append(i).append(",\"embedding\":[0.1,0.2,0.3]}");
          }
          body.append("]}");
          respond(exchange, body.toString());
        });
    server.createContext(
        "/v1/rerank",
        exchange -> respond(exchange, "{\"results\":[{\"index\":0,\"relevance_score\":1.0}] }"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            4,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            mapper);

    List<String> inputs = List.of("123456", "abcdef", "UVWXYZ", "ghijkl");
    var vectors = adapter.embed(inputs);

    assertEquals(4, vectors.size());
    assertTrue(rejectedRequests.get() >= 1);
    assertTrue(receivedInputs.size() > inputs.size());
    assertTrue(receivedInputs.containsAll(inputs));
  }

  @Test
  void limitsConcurrentEmbeddingCalls() throws Exception {
    server.stop(0);
    AtomicInteger activeRequests = new AtomicInteger();
    AtomicInteger maxActiveRequests = new AtomicInteger();
    CountDownLatch firstRequestStarted = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          int active = activeRequests.incrementAndGet();
          maxActiveRequests.accumulateAndGet(active, Math::max);
          firstRequestStarted.countDown();
          try {
            try {
              Thread.sleep(250);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
          } finally {
            activeRequests.decrementAndGet();
          }
        });
    server.createContext(
        "/v1/rerank",
        exchange -> respond(exchange, "{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            new ObjectMapper(),
            1,
            1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> adapter.embed(List.of("one")));
      assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));
      var second = executor.submit(() -> adapter.embed(List.of("two")));
      assertEquals(List.of(List.of(0.1f, 0.2f, 0.3f)), first.get(2, TimeUnit.SECONDS));
      assertEquals(List.of(List.of(0.1f, 0.2f, 0.3f)), second.get(2, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertEquals(1, maxActiveRequests.get());
  }

  @Test
  void letsSingleQueryEmbeddingRunBetweenBulkBatches() throws Exception {
    server.stop(0);
    ObjectMapper mapper = new ObjectMapper();
    List<String> requestOrder = new CopyOnWriteArrayList<>();
    AtomicInteger activeRequests = new AtomicInteger();
    AtomicInteger maxActiveRequests = new AtomicInteger();
    CountDownLatch firstBulkRequestStarted = new CountDownLatch(1);
    CountDownLatch queryCallStarted = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          String text = mapper.readTree(exchange.getRequestBody()).path("input").get(0).asText();
          requestOrder.add(text);
          int active = activeRequests.incrementAndGet();
          maxActiveRequests.accumulateAndGet(active, Math::max);
          if ("bulk-1".equals(text)) firstBulkRequestStarted.countDown();
          try {
            try {
              Thread.sleep(250);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
          } finally {
            activeRequests.decrementAndGet();
          }
        });
    server.createContext(
        "/v1/rerank",
        exchange -> respond(exchange, "{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            mapper,
            1,
            1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var bulk = executor.submit(() -> adapter.embed(List.of("bulk-1", "bulk-2", "bulk-3")));
      assertTrue(firstBulkRequestStarted.await(10, TimeUnit.SECONDS));
      var query =
          executor.submit(
              () -> {
                queryCallStarted.countDown();
                return adapter.embed(List.of("query"));
              });
      assertTrue(queryCallStarted.await(1, TimeUnit.SECONDS));
      Thread.sleep(100);

      assertEquals(List.of(List.of(0.1f, 0.2f, 0.3f)), query.get(2, TimeUnit.SECONDS));
      assertEquals(3, bulk.get(3, TimeUnit.SECONDS).size());
    } finally {
      executor.shutdownNow();
    }

    assertEquals(1, maxActiveRequests.get());
    assertTrue(requestOrder.indexOf("query") > 0);
    assertTrue(requestOrder.indexOf("query") < requestOrder.indexOf("bulk-3"));
  }

  @Test
  void doesNotRetryRerankTimeoutBecauseRetrievalCanUseFusedOrdering() throws Exception {
    server.stop(0);
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/v1/embeddings",
        exchange -> respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    server.createContext(
        "/v1/rerank",
        exchange -> {
          requests.incrementAndGet();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, "{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}");
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    var adapter =
        new OpenAiCompatibleModelServingAdapter(
            baseUrl,
            "",
            "embedding-model",
            baseUrl,
            "",
            "rerank-model",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            new ObjectMapper());

    assertThrows(IllegalStateException.class, () -> adapter.rerank("query", List.of("document")));
    assertEquals(1, requests.get());
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    respond(exchange, 200, json);
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
