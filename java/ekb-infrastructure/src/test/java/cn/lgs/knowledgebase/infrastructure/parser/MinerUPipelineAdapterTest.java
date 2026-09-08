package cn.lgs.knowledgebase.infrastructure.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MinerUPipelineAdapterTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void failsBoundedlyWhenRemoteTaskNeverLeavesRunningState() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if ("POST".equals(exchange.getRequestMethod())) {
            respond(exchange, "{\"task_id\":\"task-never-finishes\",\"status\":\"queued\"}");
          } else {
            respond(exchange, "{\"task_id\":\"task-never-finishes\",\"status\":\"running\"}");
          }
        });
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    var adapter =
        new MinerUPipelineAdapter(
            baseUrl,
            new ObjectMapper(),
            Duration.ofMillis(5),
            Duration.ofMillis(400),
            "auto",
            "ch");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.parse(
                    "large.pdf",
                    new ByteArrayInputStream("fake-pdf".getBytes(StandardCharsets.UTF_8))));

    assertTrue(failure.getMessage().contains("task-never-finishes"));
    assertTrue(failure.getMessage().contains("timed out"));
  }

  @Test
  void resumesPersistedTaskWithoutSubmittingDuplicate() throws Exception {
    AtomicInteger submits = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks/resume-task/result",
        exchange ->
            respond(
                exchange,
                "{\"version\":\"3.4.5\",\"backend\":\"hybrid-engine\",\"results\":{\"resume.pdf\":{\"md_content\":\"# resumed\",\"middle_json\":{\"pdf_info\":[]},\"content_list\":[{\"type\":\"text\",\"text\":\"resumed source\",\"page_idx\":0}]}}}"));
    server.createContext(
        "/tasks/resume-task",
        exchange -> respond(exchange, "{\"task_id\":\"resume-task\",\"status\":\"completed\"}"));
    server.createContext(
        "/tasks",
        exchange -> {
          submits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, "{\"task_id\":\"unexpected\",\"status\":\"queued\"}");
        });
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    var adapter =
        new MinerUPipelineAdapter(
            baseUrl, new ObjectMapper(), Duration.ofMillis(5), Duration.ofSeconds(1), "auto", "ch");
    List<String> reported = new ArrayList<>();

    var parsed =
        adapter.parse(
            "resume.pdf",
            new ByteArrayInputStream("unused".getBytes(StandardCharsets.UTF_8)),
            "resume-task",
            reported::add);

    assertEquals(0, submits.get());
    assertTrue(reported.isEmpty());
    assertEquals("# resumed", parsed.markdown());
    assertEquals("mineru-hybrid-engine", parsed.parserName());
  }

  @Test
  void resubmitsOnlyWhenPersistedTaskIsGoneAfterServiceRestart() throws Exception {
    AtomicInteger submits = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks/stale-task",
        exchange -> {
          exchange.sendResponseHeaders(404, 0);
          exchange.close();
        });
    server.createContext(
        "/tasks/recovered-task/result",
        exchange ->
            respond(
                exchange,
                "{\"version\":\"3.4.5\",\"backend\":\"hybrid-engine\",\"results\":{\"resume.pdf\":{\"md_content\":\"# recovered\",\"middle_json\":{\"pdf_info\":[]},\"content_list\":[{\"type\":\"text\",\"text\":\"recovered source\",\"page_idx\":0}]}}}"));
    server.createContext(
        "/tasks/recovered-task",
        exchange -> respond(exchange, "{\"task_id\":\"recovered-task\",\"status\":\"completed\"}"));
    server.createContext(
        "/tasks",
        exchange -> {
          if ("POST".equals(exchange.getRequestMethod())) {
            submits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"task_id\":\"recovered-task\",\"status\":\"queued\"}");
          } else {
            respond(exchange, "{\"task_id\":\"unexpected\",\"status\":\"queued\"}");
          }
        });
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    var adapter =
        new MinerUPipelineAdapter(
            baseUrl, new ObjectMapper(), Duration.ofMillis(5), Duration.ofSeconds(1), "auto", "ch");
    List<String> reported = new ArrayList<>();

    var parsed =
        adapter.parse(
            "resume.pdf",
            new ByteArrayInputStream("staged-pdf".getBytes(StandardCharsets.UTF_8)),
            "stale-task",
            reported::add);

    assertEquals(1, submits.get());
    assertEquals(List.of("", "recovered-task"), reported);
    assertEquals("# recovered", parsed.markdown());
  }

  @Test
  void reportsSubmittedTaskIdBeforeTimeoutSoCallerCanPersistIt() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if ("POST".equals(exchange.getRequestMethod())) {
            respond(exchange, "{\"task_id\":\"persist-me\",\"status\":\"queued\"}");
          } else {
            respond(exchange, "{\"task_id\":\"persist-me\",\"status\":\"running\"}");
          }
        });
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    var adapter =
        new MinerUPipelineAdapter(
            baseUrl,
            new ObjectMapper(),
            Duration.ofMillis(5),
            Duration.ofMillis(400),
            "auto",
            "ch");
    List<String> reported = new ArrayList<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.parse(
                "slow.pdf",
                new ByteArrayInputStream("fake-pdf".getBytes(StandardCharsets.UTF_8)),
                "",
                reported::add));

    assertFalse(reported.isEmpty());
    assertEquals("persist-me", reported.get(0));
  }

  @Test
  void typedCurrentPipelineAcceptsOnlyItsExactSourceReceipt() throws Exception {
    var mapper = new ObjectMapper();
    var declaration = pipelineDeclaration();
    var sourceSha =
        cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity.hash(
            "synthetic-pdf-input".getBytes(StandardCharsets.UTF_8));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/pipeline",
        exchange ->
            respond(
                exchange,
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "components", declaration.components(), "sha256", declaration.sha256()))));
    server.createContext(
        "/tasks/certified/result",
        exchange ->
            respond(exchange, mapper.writeValueAsString(typedResult(declaration, sourceSha))));
    server.createContext(
        "/tasks/certified",
        exchange -> respond(exchange, "{\"task_id\":\"certified\",\"status\":\"completed\"}"));
    server.start();
    var adapter =
        new MinerUPipelineAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            mapper,
            Duration.ofMillis(5),
            Duration.ofSeconds(1),
            "auto",
            "ch");
    var current = adapter.currentPipeline("fixture.pdf").orElseThrow();
    var parsed =
        adapter.parse(
            "fixture.pdf",
            new ByteArrayInputStream(new byte[0]),
            "certified",
            task -> fail("no resubmit"),
            current,
            sourceSha);
    assertEquals("Synthetic native source", parsed.markdown());
    assertEquals(
        sourceSha,
        ((java.util.Map<?, ?>) parsed.blocks().get(0).raw().get("_evokb_parser_receipt"))
            .get("sourceSha256"));
  }

  @Test
  void completedForeignSourceClearsPersistedExternalTaskAndGetsBoundedDurableClassification()
      throws Exception {
    var mapper = new ObjectMapper();
    var declaration = pipelineDeclaration();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks/foreign/result",
        exchange ->
            respond(exchange, mapper.writeValueAsString(typedResult(declaration, "a".repeat(64)))));
    server.createContext(
        "/tasks/foreign",
        exchange -> respond(exchange, "{\"task_id\":\"foreign\",\"status\":\"completed\"}"));
    server.start();
    var adapter =
        new MinerUPipelineAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            mapper,
            Duration.ofMillis(5),
            Duration.ofSeconds(1),
            "auto",
            "ch");
    List<String> tasks = new ArrayList<>();
    var failure =
        assertThrows(
            cn.lgs.knowledgebase.application.raw.ParserCheckpointPolicy.ResultStale.class,
            () ->
                adapter.parse(
                    "fixture.pdf",
                    new ByteArrayInputStream(new byte[0]),
                    "foreign",
                    tasks::add,
                    declaration,
                    "b".repeat(64)));
    assertEquals(List.of(""), tasks);
    assertEquals(
        "PARSER_RESULT_STALE",
        cn.lgs.knowledgebase.application.raw.RawTaskRetryPolicy.code(failure, "FAILED"));
    assertTrue(
        cn.lgs.knowledgebase.application.raw.RawTaskRetryPolicy.retryable(
            "PARSER_RESULT_STALE", failure));
    assertFalse(
        cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure.causedBy(failure));
  }

  @Test
  void typedResumeRejectsLegacyCompletedTaskWithoutCurrentDeclaration() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tasks/legacy/result",
        exchange ->
            respond(
                exchange,
                "{\"version\":\"3.4.5\",\"backend\":\"hybrid-engine\",\"results\":{\"fixture.pdf\":{\"md_content\":\"Synthetic source\",\"middle_json\":{\"pdf_info\":[]},\"content_list\":[{\"type\":\"text\",\"text\":\"Synthetic source\",\"page_idx\":0}]}}}"));
    server.createContext(
        "/tasks/legacy",
        exchange -> respond(exchange, "{\"task_id\":\"legacy\",\"status\":\"completed\"}"));
    server.start();
    var adapter =
        new MinerUPipelineAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            new ObjectMapper(),
            Duration.ofMillis(5),
            Duration.ofSeconds(1),
            "auto",
            "ch");
    List<String> tasks = new ArrayList<>();
    assertThrows(
        cn.lgs.knowledgebase.application.raw.ParserCheckpointPolicy.ResultStale.class,
        () ->
            adapter.parse(
                "fixture.pdf",
                new ByteArrayInputStream(new byte[0]),
                "legacy",
                tasks::add,
                pipelineDeclaration(),
                "a".repeat(64)));
    assertEquals(List.of(""), tasks);
  }

  @Test
  void currentPipelineCannotCertifyAnotherFileFormatEvenWithAValidDigest() throws Exception {
    var mapper = new ObjectMapper();
    var wrong = new java.util.TreeMap<>(pipelineDeclaration().components());
    wrong.put("parser.format", ".docx");
    var declaration = new cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity(wrong);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/pipeline",
        exchange ->
            respond(
                exchange,
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "components", declaration.components(), "sha256", declaration.sha256()))));
    server.start();
    var adapter =
        new MinerUPipelineAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            mapper,
            Duration.ofMillis(5),
            Duration.ofSeconds(1),
            "auto",
            "ch");
    var failure =
        assertThrows(IllegalStateException.class, () -> adapter.currentPipeline("fixture.pdf"));
    assertTrue(failure.getMessage().startsWith("PARSER_PROFILE_INVALID"));
  }

  private static cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity pipelineDeclaration() {
    return new cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity(
        java.util.Map.of(
            "parser.name",
            "mineru-hybrid-engine",
            "parser.version",
            "3.4.5",
            "parser.format",
            ".pdf",
            "runtime.sha256",
            "1".repeat(64),
            "config.backend",
            "hybrid-engine",
            "config.effort",
            "medium",
            "config.parse_method",
            "auto",
            "config.language",
            "ch"));
  }

  private static java.util.Map<String, Object> typedResult(
      cn.lgs.knowledgebase.application.raw.ParserPipelineIdentity pipeline, String sourceSha) {
    return java.util.Map.of(
        "version",
        "3.4.5",
        "backend",
        "hybrid-engine",
        "parser_profile",
        java.util.Map.of(
            "pipeline",
            java.util.Map.of("components", pipeline.components(), "sha256", pipeline.sha256()),
            "sourceSha256",
            sourceSha,
            "backend",
            "hybrid-engine",
            "effort",
            "medium",
            "parseMethod",
            "auto",
            "language",
            "ch"),
        "results",
        java.util.Map.of(
            "fixture.pdf",
            java.util.Map.of(
                "md_content",
                "Synthetic native source",
                "middle_json",
                java.util.Map.of("pdf_info", List.of()),
                "content_list",
                List.of(
                    java.util.Map.of(
                        "type", "text", "text", "Synthetic native source", "page_idx", 0)))));
  }

  private static void respond(HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
