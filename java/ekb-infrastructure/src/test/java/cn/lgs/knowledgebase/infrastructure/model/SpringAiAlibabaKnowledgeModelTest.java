package cn.lgs.knowledgebase.infrastructure.model;

import static cn.lgs.knowledgebase.application.port.Ports.ModelRole.SMALL;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.ASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.compilation.GenerationTaskRetryPolicy;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService.Extraction;
import cn.lgs.knowledgebase.application.compilation.ModelOutputRejectedException;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.Classification;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
  void wrongOrMissingResponseIdentityNeverExposesContentOrWarmsTheRoute() {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> response = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(exchange, requests.get() % 2 == 0 ? 404 : 200, response.get());
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (String invalid :
        List.of(
            "{\"model\":\"codex-auto-review\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"must not escape\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"must not escape\"}}]}")) {
      response.set(invalid);
      var failure =
          assertThrows(
              IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertTrue(failure.getMessage().contains("model identity mismatch"));
      assertTrue(durableRetryable(failure));
      // The subsequent 404 remains a cold-route terminal failure, not a five-send retry.
      assertThrows(
          IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
    }
    assertEquals(4, requests.get());
  }

  @Test
  void incompleteOrFilteredCompletionNeverPassesAsAnAnswerOrHealthyRoute() {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> finish = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              mapper.writeValueAsString(
                  Map.of(
                      "model",
                      "gpt-6-luna",
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason",
                              finish.get(),
                              "message",
                              Map.of("content", "partial"))))));
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (String reason : List.of("", "length", "content_filter", "tool_calls")) {
      finish.set(reason);
      var failure =
          assertThrows(
              IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertTrue(failure.getMessage().contains("response incomplete"));
      assertTrue(durableRetryable(failure));
    }
    assertEquals(4, requests.get());
  }

  @Test
  void aRecentlyWorkingRouteRecoversAnIntermittent404WithoutChangingTheRequest() {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> original = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int attempt = requests.incrementAndGet();
          if (attempt == 1) original.set(body);
          assertEquals(original.get(), body);
          respond(
              exchange,
              attempt == 2 ? 404 : 200,
              attempt == 2
                  ? "{\"error\":{\"message\":\"route temporarily absent\"}}"
                  : "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    assertEquals("ok", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals("ok", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(3, requests.get());
  }

  @Test
  void cold404AndMalformed200NeverEstablishAWorkingRoute() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int attempt = requests.incrementAndGet();
          respond(
              exchange,
              attempt == 2 ? 200 : 404,
              attempt == 2 ? "{\"choices\":[]}" : "{\"error\":{\"message\":\"invalid route\"}}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (int index = 0; index < 3; index++) {
      var failure =
          assertThrows(
              IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertEquals(index == 1, failure instanceof TransientDependencyFailure);
      assertEquals(index == 1, durableRetryable(failure));
    }
    assertEquals(3, requests.get());
  }

  @Test
  void malformedOrEmptyProviderResponseIsDeferredWithoutReplayingInsideTheAdapter() {
    AtomicReference<String> response = new AtomicReference<>();
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, response.get());
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (String invalid :
        List.of(
            "{",
            "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}",
            "{\"model\":\"gpt-6-luna\",\"choices\":[]}")) {
      response.set(invalid);
      int before = requests.get();
      var failure =
          assertThrows(
              TransientDependencyFailure.class,
              () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertTrue(durableRetryable(failure));
      assertEquals(before + 1, requests.get());
    }
  }

  @Test
  void evenARecentlyWorkingRouteStopsAfterFive404Sends() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int attempt = requests.incrementAndGet();
          respond(
              exchange,
              attempt == 1 ? 200 : 404,
              attempt == 1
                  ? "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}"
                  : "{\"error\":{\"message\":\"route absent\"}}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    assertEquals("ok", adapter.generate(SMALL, ASK, "system", "user"));
    assertThrows(
        TransientDependencyFailure.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(6, requests.get());
  }

  @Test
  void readsRoleMappingFromCurrentConfigurationOnEveryCall() {
    MutableStore store = new MutableStore(configuration("small-a"));
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
          respond(
              exchange,
              "{\"model\":\""
                  + model
                  + "\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                  + model
                  + "\"}}]}");
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
                  Map.of(
                      "model",
                      "gpt-5.4-mini",
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason", "stop", "message", Map.of("content", content))))));
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
    MutableStore store = new MutableStore(configuration("gpt-6.1-terra"));
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
                  Map.of(
                      "model",
                      "gpt-6.1-terra",
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason", "stop", "message", Map.of("content", content))))));
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
    MutableStore store = new MutableStore(configuration("gpt-6-luna"));
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> request = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          request.set(mapper.readTree(exchange.getRequestBody()));
          respond(
              exchange,
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}");
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
            respond(
                exchange,
                "{\"model\":\"gpt-5.4-mini\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"recovered\"}}]}");
          }
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(3, requests.get());
  }

  @Test
  void temporaryGatewayCapacityStillUsesAtMostFiveHttpAttempts() {
    MutableStore store = new MutableStore(configuration("gpt-6-luna"));
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
    assertTrue(failure instanceof TransientDependencyFailure);
    assertEquals(5, requests.get());
  }

  @Test
  void gatewayCapacityCanRecoverWithoutChangingTheModelOrOriginalRequest() {
    MutableStore store = new MutableStore(configuration("gpt-6-luna"));
    AtomicInteger requests = new AtomicInteger();
    var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          bodies.add(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          if (requests.incrementAndGet() < 3)
            respond(exchange, 503, "{\"error\":{\"message\":\"no available tokens\"}}");
          else
            respond(
                exchange,
                "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"capacity recovered\"}}]}");
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);
    assertEquals("capacity recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(3, requests.get());
    assertEquals(1, bodies.stream().distinct().count());
    assertTrue(bodies.get(0).contains("gpt-6-luna"));
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
            respond(
                exchange,
                "{\"model\":\"gpt-5.4-mini\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"recovered\"}}]}");
          }
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(2, requests.get());
  }

  @Test
  void requestTimeoutsCanRecoverOnFifthActualHttpAttempt() {
    MutableStore store = new MutableStore(configuration("gpt-6-luna", 1));
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int attempt = requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (attempt < 5) {
            try {
              Thread.sleep(1200);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            exchange.close();
          } else {
            respond(
                exchange,
                "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"recovered\"}}]}");
          }
        });
    var adapter = new SpringAiAlibabaKnowledgeModel(store, mapper);

    assertEquals("recovered", adapter.generate(SMALL, ASK, "system", "user"));
    assertEquals(5, requests.get());
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
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
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
          respond(
              exchange,
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not JSON\"}}]}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    RuntimeException failure =
        assertThrows(
            ModelOutputRejectedException.class,
            () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
    assertFalse(failure instanceof TransientDependencyFailure);
    assertEquals(1, requests.get());
  }

  @Test
  void malformedJsonAndSchemaAreBoundedProposalFailuresWithoutExtraHttpRequests() {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> content = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              mapper.writeValueAsString(
                  Map.of(
                      "model",
                      "gpt-6-luna",
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of("content", content.get()))))));
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (String invalid :
        List.of(
            "not JSON",
            "{\"units\":[",
            "{\"units\":}",
            "{\"units\":{\"start_block_no\":1}}",
            "[]")) {
      content.set(invalid);
      int before = requests.get();
      RuntimeException failure =
          assertThrows(
              ModelOutputRejectedException.class,
              () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
      assertFalse(failure instanceof TransientDependencyFailure);
      assertTrue(durableRetryable(failure));
      assertEquals(before + 1, requests.get());
    }
  }

  @Test
  void localMapperDefinitionAndProgrammingErrorsAreNotProposalRetries() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}");
        });
    ObjectMapper invalidDefinition =
        new ObjectMapper() {
          @Override
          public <T> T treeToValue(TreeNode node, Class<T> type) throws JsonProcessingException {
            throw InvalidDefinitionException.from(
                (com.fasterxml.jackson.core.JsonParser) null,
                "synthetic local definition error",
                constructType(type));
          }
        };
    ObjectMapper programmingError =
        new ObjectMapper() {
          @Override
          public <T> T treeToValue(TreeNode node, Class<T> type) {
            throw new IllegalArgumentException("synthetic local mapper programming error");
          }
        };
    for (var localMapper : List.of(invalidDefinition, programmingError)) {
      var adapter =
          new SpringAiAlibabaKnowledgeModel(
              new MutableStore(configuration("gpt-6-luna")), localMapper);
      int before = requests.get();
      var failure =
          assertThrows(
              RuntimeException.class,
              () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
      assertFalse(failure instanceof ModelOutputRejectedException);
      assertFalse(durableRetryable(failure));
      assertEquals(before + 1, requests.get());
    }
  }

  @Test
  void structuredAuthenticationAndBusinessFailuresRemainTerminal() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger status = new AtomicInteger(401);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              status.get(),
              "{\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"Insufficient account balance\"}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    for (int responseStatus : List.of(401, 200)) {
      status.set(responseStatus);
      int before = requests.get();
      var failure =
          assertThrows(
              IllegalStateException.class,
              () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
      assertFalse(durableRetryable(failure));
      assertEquals(before + 1, requests.get());
    }
  }

  @Test
  void requestEncodingFailureIsNotARejectedModelResponseAndSendsNothing() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(exchange, "{}");
        });
    ObjectMapper brokenRequestEncoder =
        new ObjectMapper() {
          @Override
          public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
            throw new com.fasterxml.jackson.databind.JsonMappingException(
                (com.fasterxml.jackson.core.JsonParser) null, "synthetic request encoding defect");
          }
        };
    var adapter =
        new SpringAiAlibabaKnowledgeModel(
            new MutableStore(configuration("gpt-6-luna")), brokenRequestEncoder);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.generateStructured(SMALL, ASK, "system", "user", Classification.class));
    assertTrue(failure.getMessage().contains("cannot encode model request"));
    assertFalse(durableRetryable(failure));
    assertEquals(0, requests.get());
  }

  @Test
  void anExpiredStageCannotSendAnotherModelRequest() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"unused\"}}]}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    try (var scope =
        CompilationExecutionBudget.open(
            Instant.now().minusSeconds(1),
            CompilationPublication.direct(Runnable::run),
            Clock.systemUTC())) {
      assertThrows(
          CompilationExecutionBudget.StageDeadlineExpired.class,
          () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertEquals(0, requests.get());
    }
  }

  @Test
  void successfulHeadersCannotKeepChatBodyPastTheOriginalStageDeadline() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"complete\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body, 0, 2);
            out.flush();
            try {
              Thread.sleep(1600);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            out.write(body, 2, body.length - 2);
          } finally {
            exchange.close();
          }
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    long started = System.nanoTime();
    try (var scope =
        CompilationExecutionBudget.open(
            Instant.now().plusMillis(600),
            CompilationPublication.direct(Runnable::run),
            Clock.systemUTC())) {
      var failure =
          assertThrows(
              CompilationExecutionBudget.StageDeadlineExpired.class,
              () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertTrue(durableRetryable(failure));
      assertEquals(1, requests.get());
      assertTrue((System.nanoTime() - started) / 1_000_000d < 1200);
    }
  }

  @Test
  void revokedCapturedAuthorityIsTerminalAndNeverReachesHttp() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          respond(
              exchange,
              "{\"model\":\"gpt-6-luna\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"unused\"}}]}");
        });
    var gate =
        new CompilationPublication() {
          public void captureInputs(List<String> versions) {}

          public void check() {
            throw new IllegalStateException("synthetic source revoked");
          }

          public void publish(Runnable writes) {
            throw new AssertionError("must not publish");
          }

          public String objectKey(String key) {
            return key;
          }
        };
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    try (var scope =
        CompilationExecutionBudget.open(Instant.now().plusSeconds(600), gate, Clock.systemUTC())) {
      var failure =
          assertThrows(
              IllegalStateException.class, () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertEquals("synthetic source revoked", failure.getMessage());
      assertFalse(durableRetryable(failure));
      assertEquals(0, requests.get());
    }
  }

  @Test
  void transportBackoffCannotStartASecondWindowAfterStageExpiry() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 503, "{\"error\":{\"message\":\"temporarily unavailable\"}}");
        });
    var adapter =
        new SpringAiAlibabaKnowledgeModel(new MutableStore(configuration("gpt-6-luna")), mapper);
    long started = System.nanoTime();
    try (var scope =
        CompilationExecutionBudget.open(
            Instant.now().plusMillis(600),
            CompilationPublication.direct(Runnable::run),
            Clock.systemUTC())) {
      assertThrows(
          CompilationExecutionBudget.StageDeadlineExpired.class,
          () -> adapter.generate(SMALL, ASK, "system", "user"));
      assertEquals(1, requests.get());
      assertTrue((System.nanoTime() - started) / 1_000_000d < 1200);
    }
  }

  private boolean durableRetryable(RuntimeException failure) {
    var task =
        new cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask(
            "model-boundary-task",
            "source-version",
            cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.SUMMARY,
            "summary",
            cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskStatus.RUNNING,
            1,
            "",
            "",
            Instant.EPOCH);
    return GenerationTaskRetryPolicy.nextAttemptAt(task, failure, Instant.EPOCH).isPresent();
  }

  private EffectiveConfiguration configuration(String smallModel) {
    return configuration(smallModel, 10);
  }

  private EffectiveConfiguration configuration(String smallModel, int chatTimeoutSeconds) {
    return new EffectiveConfiguration(
        baseUrl,
        "secret",
        smallModel,
        "large",
        chatTimeoutSeconds,
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
