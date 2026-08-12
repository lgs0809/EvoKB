package cn.lgs.knowledgebase.infrastructure.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private static void respond(HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
