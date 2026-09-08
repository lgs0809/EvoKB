package cn.lgs.knowledgebase.infrastructure.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ArtifactStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void firstPublicationCreatesVectorMappingBeforeWritingAnyDocument() throws Exception {
    AtomicBoolean created = new AtomicBoolean();
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> mapping = new AtomicReference<>();
    AtomicInteger writes = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ekb_derived_v1",
        exchange -> {
          if ("HEAD".equals(exchange.getRequestMethod())) {
            respond(exchange, created.get() ? 200 : 404, "");
          } else if (exchange.getRequestURI().getPath().equals("/ekb_derived_v1")) {
            mapping.set(mapper.readTree(exchange.getRequestBody()));
            created.set(true);
            respond(exchange, 200, "{}");
          } else if (exchange.getRequestURI().getPath().equals("/ekb_derived_v1/_mapping")) {
            respond(
                exchange,
                200,
                mapper.writeValueAsString(
                    java.util.Map.of(
                        "ekb_derived_v1",
                        java.util.Map.of("mappings", mapping.get().get("mappings")))));
          } else {
            if (!created.get())
              throw new AssertionError("document must never trigger dynamic index creation");
            writes.incrementAndGet();
            respond(exchange, 200, "{}");
          }
        });
    server.start();
    var adapter =
        new OpenSearchDerivedIndexAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(), "ekb_derived_v1", mapper);
    var summary =
        new SummaryArtifact(
            "summary",
            "document",
            "version",
            "body",
            List.of(),
            ArtifactStatus.ACTIVE,
            Instant.EPOCH);
    adapter.indexSummary(summary, null, List.of(0.1f, 0.2f));
    adapter.indexSummary(summary, null, List.of(0.1f, 0.2f));
    assertEquals("knn_vector", mapping.get().at("/mappings/properties/embedding/type").asText());
    assertEquals(2, mapping.get().at("/mappings/properties/embedding/dimension").asInt());
    assertEquals(2, writes.get());
  }

  @Test
  void incompatibleExistingMappingRequiresPreservedIndexRebuildInsteadOfWriting() throws Exception {
    AtomicInteger writes = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ekb_derived_v1",
        exchange -> {
          if ("HEAD".equals(exchange.getRequestMethod())) respond(exchange, 200, "");
          else if ("GET".equals(exchange.getRequestMethod()))
            respond(
                exchange,
                200,
                "{\"ekb_derived_v1\":{\"mappings\":{\"properties\":{\"embedding\":{\"type\":\"float\"}}}}}");
          else {
            writes.incrementAndGet();
            respond(exchange, 200, "{}");
          }
        });
    server.start();
    var adapter =
        new OpenSearchDerivedIndexAdapter(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "ekb_derived_v1",
            new ObjectMapper());
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.indexSummary(
                    new SummaryArtifact(
                        "summary",
                        "document",
                        "version",
                        "body",
                        List.of(),
                        ArtifactStatus.ACTIVE,
                        Instant.EPOCH),
                    null,
                    List.of(0.1f, 0.2f)));
    assertTrue(failure.getMessage().contains("preserve the existing index"));
    assertEquals(0, writes.get());
  }
}
