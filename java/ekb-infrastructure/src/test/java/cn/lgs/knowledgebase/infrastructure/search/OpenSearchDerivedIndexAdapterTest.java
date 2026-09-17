package cn.lgs.knowledgebase.infrastructure.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenSearchDerivedIndexAdapterTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void missingDerivedIndexProducesEmptyCandidatesInsteadOfFailingRawOnlyRetrieval()
      throws Exception {
    AtomicInteger heads = new AtomicInteger();
    AtomicInteger searches = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ekb_derived_v1",
        exchange -> {
          if ("HEAD".equals(exchange.getRequestMethod())) {
            heads.incrementAndGet();
            respond(exchange, 404, "");
            return;
          }
          searches.incrementAndGet();
          respond(exchange, 500, "search must not run when the index is absent");
        });
    server.start();

    OpenSearchDerivedIndexAdapter adapter =
        new OpenSearchDerivedIndexAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "ekb_derived_v1",
            new ObjectMapper());
    SearchFilters filters = new SearchFilters(null, null, null, List.of());

    assertEquals(List.of(), adapter.searchSummaryBm25("question", filters, 5));
    assertEquals(List.of(), adapter.searchSummaryVector(List.of(0.1f, 0.2f), filters, 5));
    assertEquals(List.of(), adapter.searchWikiBm25("question", filters, 5));
    assertEquals(List.of(), adapter.searchWikiVector(List.of(0.1f, 0.2f), filters, 5));
    assertEquals(4, heads.get());
    assertEquals(0, searches.get());
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }
}
