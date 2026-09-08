package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.compilation.GroundingJudgeProtocol;
import cn.lgs.knowledgebase.application.compilation.ModelOutputRejectedException;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompilationModelAuditTest {
  @Test
  void responseIsRetainedBeforeActualStructuredDecoderRejectsItWithoutCredentials()
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var events = new ArrayList<AuditEvent>();
    var mapper = new ObjectMapper();
    String returned = "{broken-json: 不是合格审计}";
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              mapper.writeValueAsBytes(
                  Map.of(
                      "model",
                      "gpt-6-luna",
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason", "stop", "message", Map.of("content", returned)))));
          exchange.sendResponseHeaders(200, body.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(body);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var config =
          new EffectiveConfiguration(
              base,
              "PRIVATE_TEST_HEADER",
              "gpt-6-luna",
              "gpt-6.1-terra",
              5,
              base,
              "PRIVATE_EMBEDDING_KEY",
              "unused",
              3,
              1,
              5,
              base,
              "PRIVATE_RERANK_KEY",
              "unused",
              5,
              Instant.EPOCH);
      Store store =
          (Store)
              Proxy.newProxyInstance(
                  Store.class.getClassLoader(),
                  new Class<?>[] {Store.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("current")) return config;
                    throw new UnsupportedOperationException();
                  });
      var model = new SpringAiAlibabaKnowledgeModel(store, mapper, events::add);
      try (var ignored = scope()) {
        assertThrows(
            ModelOutputRejectedException.class,
            () ->
                model.generateStructured(
                    ModelRole.SMALL,
                    ModelTask.SUMMARY_JUDGE,
                    "synthetic",
                    "synthetic",
                    GroundingJudgeProtocol.Decision.class));
      }
      assertEquals(1, events.size());
      var event = events.get(0);
      assertEquals("compile_task", event.targetType());
      assertEquals("claimed-task", event.targetId());
      assertEquals(7L, event.details().get("executionEpoch"));
      assertEquals(2, event.details().get("attempt"));
      assertEquals(returned, event.details().get("responseText"));
      assertEquals(KnowledgeDomain.sha256(returned), event.details().get("responseSha256"));
      assertEquals("RECEIVED_BEFORE_SCHEMA_AND_SEMANTIC_VALIDATION", event.details().get("phase"));
      assertFalse(event.toString().contains("PRIVATE_"));
      assertTrue(CompilationExecutionBudget.execution().isEmpty());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void oversizedUnicodeReceiptKeepsABoundedValidPrefixAndFullHash() {
    var events = new ArrayList<AuditEvent>();
    String original = "测🙂".repeat(50_000);
    var audit = new CompilationModelAudit(events::add);
    try (var ignored = scope()) {
      audit.received(
          ModelRole.SMALL,
          ModelTask.SUMMARY_JUDGE,
          "gpt-6-luna",
          GroundingJudgeProtocol.Decision.class,
          original);
    }
    var details = events.get(0).details();
    String retained = (String) details.get("responseText");
    assertTrue(original.startsWith(retained));
    assertTrue(
        retained.getBytes(StandardCharsets.UTF_8).length
            <= CompilationModelAudit.MAX_RESPONSE_BYTES);
    assertFalse(Character.isHighSurrogate(retained.charAt(retained.length() - 1)));
    assertEquals(true, details.get("responseTruncated"));
    assertEquals(original.getBytes(StandardCharsets.UTF_8).length, details.get("responseBytes"));
    assertEquals(KnowledgeDomain.sha256(original), details.get("responseSha256"));
  }

  @Test
  void failureMessagesAndUnscopedCallsCannotLeakIntoTheLedger() {
    var events = new ArrayList<AuditEvent>();
    var audit = new CompilationModelAudit(events::add);
    audit.received(
        ModelRole.SMALL,
        ModelTask.SUMMARY_JUDGE,
        "gpt-6-luna",
        GroundingJudgeProtocol.Decision.class,
        "not a durable task");
    assertTrue(events.isEmpty());
    try (var ignored = scope()) {
      audit.failed(
          ModelRole.SMALL,
          ModelTask.SUMMARY_JUDGE,
          "gpt-6-luna",
          GroundingJudgeProtocol.Decision.class,
          new IllegalStateException("Authorization: Bearer PRIVATE_TEST_SECRET"));
    }
    assertEquals(1, events.size());
    assertFalse(events.get(0).toString().contains("PRIVATE_TEST_SECRET"));
    assertFalse(events.get(0).details().containsKey("responseText"));
    assertEquals("FAILURE", events.get(0).outcome());
  }

  private static CompilationExecutionBudget scope() {
    var task =
        new GenerationTask(
            "claimed-task",
            "source-version",
            GenerationTaskType.SUMMARY,
            "",
            GenerationTaskStatus.RUNNING,
            2,
            "",
            "",
            Instant.now(),
            "worker",
            7,
            Instant.now().plusSeconds(600),
            1);
    return CompilationExecutionBudget.open(
        task, CompilationPublication.direct(Runnable::run), Clock.systemUTC());
  }
}
