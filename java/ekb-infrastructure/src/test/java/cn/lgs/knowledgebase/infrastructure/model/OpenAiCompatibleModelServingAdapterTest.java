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
        exchange -> respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    server.createContext(
        "/v1/rerank",
        exchange ->
            respond(
                exchange,
                "{\"results\":[{\"index\":0,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.2}]}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void parsesJsonBodiesEvenWhenProviderUsesOctetStreamContentType() {
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
  void splitsEmbeddingBatchAfterTimeoutUntilProviderCanServeIt() throws Exception {
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

    var vectors = adapter.embed(List.of("one", "two", "three", "four"));

    assertEquals(4, vectors.size());
    assertEquals(List.of(0.1f, 0.2f, 0.3f), vectors.get(0));
    // A timed-out child request can expire before the local HTTP handler is scheduled, so the
    // observable multi-request count is scheduler-dependent. The upper bound still proves that a
    // timeout never retries the same multi-item batch before bisection.
    assertTrue(multiRequests.get() >= 1);
    assertTrue(multiRequests.get() <= 3);
    assertEquals(4, singleRequests.get());
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
