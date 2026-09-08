package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RawAtomicEmbeddingHttpTest {
  HttpServer server;
  String endpoint;
  AtomicInteger calls;
  int metadataStatus;
  boolean rejectLargeBatch, changedChild, badVectorProof, omitProof;
  int embeddingStatus;
  List<List<String>> requests;

  @BeforeEach
  void start() throws Exception {
    calls = new AtomicInteger();
    requests = new ArrayList<>();
    metadataStatus = 503;
    embeddingStatus = 200;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embedding-identity",
        exchange -> {
          try {
            if (metadataStatus != 200) send(exchange, metadataStatus, "{}");
            else
              send(
                  exchange,
                  200,
                  EncodingIdentityCodecTest.certificate(List.of(), false).toString());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          try {
            int number = calls.incrementAndGet();
            var body =
                EncodingIdentityCodecTest.JSON.readTree(exchange.getRequestBody().readAllBytes());
            List<String> texts = new ArrayList<>();
            body.path("input").forEach(value -> texts.add(value.asText()));
            requests.add(texts);
            if (embeddingStatus != 200) {
              send(exchange, embeddingStatus, "{}");
              return;
            }
            if (rejectLargeBatch && texts.size() > 1) {
              send(exchange, 413, "{}");
              return;
            }
            ObjectNode response = EncodingIdentityCodecTest.JSON.createObjectNode();
            var data = response.putArray("data");
            for (int i = 0; i < texts.size(); i++)
              data.addObject()
                  .put("index", i)
                  .set(
                      "embedding",
                      EncodingIdentityCodecTest.JSON.valueToTree(EncodingIdentityCodecTest.VECTOR));
            if (!omitProof) {
              var cert = EncodingIdentityCodecTest.certificate(texts, changedChild && number > 2);
              if (badVectorProof)
                ((ObjectNode) cert.path("inputs").get(0)).put("vector_sha256", "f".repeat(64));
              response.set("encoding_identity", cert);
            }
            send(exchange, 200, response.toString());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void coldAndEvictedMetadataIsUnknownAndDoesNotTriggerInferenceOrRetries() {
    var port = adapter().rawEmbeddingPort();
    assertTrue(port.currentEncodingIdentity().isEmpty());
    assertEquals(0, calls.get());
    metadataStatus = 404;
    assertTrue(port.currentEncodingIdentity().isEmpty());
    assertEquals(0, calls.get());
    var encoded = port.embedWithIdentity(List.of("完整原文"));
    assertTrue(encoded.identity().isPresent());
    assertEquals(1, calls.get());
    metadataStatus = 200;
    assertEquals(encoded.identity(), port.currentEncodingIdentity());
    assertEquals(1, calls.get());
    metadataStatus = 503;
    assertTrue(port.currentEncodingIdentity().isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void rawAtomicPathPreservesCompleteLongTextAndAuthenticatesItsWholeInput() {
    String text = "完整条件🙂以及金额900.50万元。".repeat(400) + "最终前提";
    assertTrue(text.length() > 4000);
    var result = adapter().rawEmbeddingPort().embedWithIdentity(List.of(text));
    assertTrue(result.identity().isPresent());
    assertEquals(List.of(List.of(text)), requests);
  }

  @Test
  void explicitAggregate413SplitsOnlyInputBatchAndRetainsEachCertificate() {
    rejectLargeBatch = true;
    var result = adapter().rawEmbeddingPort().embedWithIdentity(List.of("原文A", "原文B"));
    assertEquals(3, calls.get());
    assertEquals(2, result.identities().size());
    assertTrue(result.identities().stream().allMatch(value -> value.equals(result.identity())));
    assertEquals(List.of("原文A"), requests.get(1));
    assertEquals(List.of("原文B"), requests.get(2));
  }

  @Test
  void splitRequestsUsingDifferentRuntimeProfilesFailBeforeReturningBatch() {
    rejectLargeBatch = changedChild = true;
    assertThrows(
        IllegalStateException.class,
        () -> adapter().rawEmbeddingPort().embedWithIdentity(List.of("A", "B")));
    assertEquals(3, calls.get());
  }

  @Test
  void mismatchingVectorProofFailsWithoutRetryAndMissingProofStaysUnknown() {
    badVectorProof = true;
    assertThrows(
        IllegalStateException.class,
        () -> adapter().rawEmbeddingPort().embedWithIdentity(List.of("A")));
    assertEquals(1, calls.get());
    omitProof = true;
    assertTrue(adapter().rawEmbeddingPort().embedWithIdentity(List.of("A")).identity().isEmpty());
    assertEquals(2, calls.get());
  }

  @Test
  void authenticationFailureIsNotRetriedOrMisreportedAsCertifiedVectors() {
    embeddingStatus = 401;
    assertThrows(
        IllegalStateException.class,
        () -> adapter().rawEmbeddingPort().embedWithIdentity(List.of("A")));
    assertEquals(1, calls.get());
  }

  OpenAiCompatibleModelServingAdapter adapter() {
    return new OpenAiCompatibleModelServingAdapter(
        endpoint,
        "",
        EncodingIdentityCodecTest.MODEL,
        endpoint,
        "",
        "fixture-reranker",
        3,
        8,
        Duration.ofSeconds(3),
        Duration.ofSeconds(3));
  }

  static void send(HttpExchange exchange, int status, String body) throws Exception {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getRequestBody().close();
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
