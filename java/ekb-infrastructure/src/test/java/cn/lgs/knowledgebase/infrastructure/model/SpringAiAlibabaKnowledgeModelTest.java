package cn.lgs.knowledgebase.infrastructure.model;

import static cn.lgs.knowledgebase.application.port.Ports.ModelRole.SMALL;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.ASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.compilation.GraphCompilationService.Extraction;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.Classification;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpringAiAlibabaKnowledgeModelTest {
  private final ObjectMapper mapper =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void readsRoleMappingFromCurrentConfigurationOnEveryCall() {
    MutableStore store = new MutableStore(configuration("small-a"));
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
          respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"" + model + "\"}}]}");
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("small-a", adapter.generate(SMALL, ASK, "system", "user"));

    store.set(configuration("small-b"));
    assertEquals("small-b", adapter.generate(SMALL, ASK, "system", "user"));
  }

  @Test
  void normalizesCamelCaseStructuredOutputForSnakeCaseMapper() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String content =
              "{\"documentType\":\"公司治理制度\",\"structureType\":\"bundle\",\"units\":[{\"title\":\"董事会议事规则\",\"documentType\":\"公司治理制度\",\"startBlockNo\":1,\"endBlockNo\":9}]}";
          respond(
              exchange,
              mapper.writeValueAsString(
                  Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    Classification output =
        adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class);

    assertEquals("公司治理制度", output.documentType());
    assertEquals("bundle", output.structureType());
    assertEquals(1, output.units().size());
    assertEquals("公司治理制度", output.units().get(0).documentType());
    assertEquals(1, output.units().get(0).startBlockNo());
    assertEquals(9, output.units().get(0).endBlockNo());
  }

  @Test
  void preservesNestedGraphScopeAnchorsAndFactCitationsInStructuredProtocol() {
    MutableStore store = new MutableStore(configuration("gpt-5.6-terra"));
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> request = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          request.set(mapper.readTree(exchange.getRequestBody()));
          String content =
              """
            {"entities":[],"factDecisions":[{"factId":"f1","graphEligible":true,"skipReason":""}],
             "relations":[{"sourceName":"甲公司","sourceType":"COMPANY","relationType":"PARTY_TO_CONTRACT",
               "targetName":"HT-7","targetType":"CONTRACT","supportingFactIds":["f1"],
               "validFrom":"","validTo":"","confidence":0.9,
               "businessScope":[{"type":"CONTRACT","value":"HT-7","supportingFactIds":["f1"]},
                                {"type":"COMPANY","value":"甲公司","supportingFactIds":["f1"]}],
               "scopeText":""}]}
            """;
          respond(
              exchange,
              mapper.writeValueAsString(
                  Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);
    Extraction output = adapter.generateStructured(SMALL, ASK, "system", "user", Extraction.class);
    var anchors = output.relations().get(0).businessScope();
    assertEquals(2, anchors.size());
    assertEquals("HT-7", anchors.get(0).value());
    assertEquals(List.of("f1"), anchors.get(0).supportingFactIds());
    assertEquals("甲公司", anchors.get(1).value());
    assertEquals(List.of("f1"), anchors.get(1).supportingFactIds());
    String actualPrompt = request.get().path("messages").toString();
    assertTrue(actualPrompt.contains("businessScope"), () -> actualPrompt);
    assertTrue(actualPrompt.contains("supportingFactIds"));
    assertTrue(actualPrompt.contains("value"));
    assertTrue(actualPrompt.contains("LEGAL_CASE"));
    assertTrue(actualPrompt.contains("LICENSE"));
  }

  @Test
  void omitsLegacySamplingOverridesFromGptCompatibleRequest() {
    MutableStore store = new MutableStore(configuration("gpt-5.6-luna"));
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> request = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          request.set(mapper.readTree(exchange.getRequestBody()));
          respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("ok", adapter.generate(SMALL, ASK, "system", "user"));
    assertFalse(request.get().has("temperature"));
    assertFalse(request.get().has("max_tokens"));
  }

  @Test
  void retriesTransientHttpFailuresAndSucceedsOnThirdAttempt() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int attempt = requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (attempt < 3) {
            respond(exchange, 503, "{\"error\":{\"message\":\"temporarily unavailable\"}}");
          } else {
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}");
          }
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(3, requests.get());
  }

  @Test
  void failsFastWhenGatewayHasNoAvailableToken() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 503, "{\"error\":{\"message\":\"no available token\"}}");
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));

    assertTrue(failure.getMessage().contains("no available token"));
    assertFalse(failure instanceof TransientDependencyFailure);
    assertEquals(1, requests.get());
  }

  @Test
  void retriesGatewayTimeout524AsTransientFailure() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int attempt = requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (attempt < 2) {
            respond(exchange, 524, "{\"error\":{\"message\":\"gateway timeout\"}}");
          } else {
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}");
          }
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(2, requests.get());
  }

  @Test
  void doesNotRetryNonTransientClientError() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 400, "{\"error\":{\"message\":\"bad request\"}}");
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));

    assertTrue(failure.getMessage().contains("HTTP 400"));
    assertFalse(failure instanceof TransientDependencyFailure);
    assertEquals(1, requests.get());
  }

  @Test
  void surfacesProviderBusinessErrorReturnedWithHttp200() {
    MutableStore store = new MutableStore(configuration("gpt-5.4-mini"));
    server.createContext(
        "/v1/chat/completions",
        exchange ->
            respond(
                exchange,
                "{\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"Insufficient account balance\"}"));
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));

    assertTrue(failure.getMessage().contains("INSUFFICIENT_BALANCE"));
    assertTrue(failure.getMessage().contains("Insufficient account balance"));
    assertFalse(failure instanceof TransientDependencyFailure);
  }

  @Test
  void exhaustedGatewayFailuresRetainTypedDurableRetrySignalAndFiveRequestLimit() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 503, "{\"error\":{\"message\":\"temporarily unavailable\"}}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-5.6-luna")), mapper);
    assertThrows(
        TransientDependencyFailure.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(5, requests.get());
  }

  @Test
  void invalidStructuredOutputIsNotARecoverableTransportFailure() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"not JSON\"}}]}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-5.6-luna")), mapper);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
    assertFalse(failure instanceof TransientDependencyFailure);
    assertEquals(1, requests.get());
  }

  private EffectiveConfiguration configuration(String smallModel) {
    return new EffectiveConfiguration(
        baseUrl,
        "secret",
        smallModel,
        "large",
        10,
        baseUrl,
        "",
        "embedding",
        3,
        16,
        10,
        baseUrl,
        "",
        "rerank",
        10,
        Instant.EPOCH);
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    respond(exchange, 200, json);
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static final class MutableStore implements Store {
    private final AtomicReference<EffectiveConfiguration> current;

    private MutableStore(EffectiveConfiguration initial) {
      current = new AtomicReference<>(initial);
    }

    private void set(EffectiveConfiguration value) {
      current.set(value);
    }

    @Override
    public EffectiveConfiguration current() {
      return current.get();
    }

    @Override
    public EffectiveConfiguration update(UpdateCommand command) {
      throw new UnsupportedOperationException();
    }
  }
}
