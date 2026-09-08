package cn.lgs.knowledgebase.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MinioCompilationDeadlineTest {
  @Test
  void rawClaimAlsoBoundsCompleteObjectBodyAndRetainsOrdinaryClient() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      long started = System.nanoTime();
      try (var scope =
              cn.lgs.knowledgebase.application.raw.RawExecutionBudget.open(
                  Instant.now().plusMillis(250), () -> {}, Clock.systemUTC());
          var stream = adapter.get("slow").stream()) {
        assertTrue(
            assertThrows(IllegalStateException.class, () -> stream.readAllBytes())
                .getMessage()
                .contains("RAW_STAGE_EXPIRED"));
      } finally {
        fixture.release.countDown();
      }
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
      assertFalse(cn.lgs.knowledgebase.application.raw.RawExecutionBudget.active());
      try (var stream = adapter.get("fast").stream()) {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, stream.readAllBytes());
      }
    }
  }

  @Test
  void rawRevocationCannotStartAnObjectRequest() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      int before = fixture.requests.get();
      try (var scope =
          cn.lgs.knowledgebase.application.raw.RawExecutionBudget.open(
              Instant.now().plusSeconds(10),
              () -> {
                throw new IllegalStateException("RAW_SOURCE_REVOKED");
              },
              Clock.systemUTC())) {
        assertEquals(
            "RAW_SOURCE_REVOKED",
            assertThrows(IllegalStateException.class, () -> adapter.get("fast")).getMessage());
      }
      assertEquals(before, fixture.requests.get());
    }
  }

  @Test
  void headersDoNotLetAStalledObjectBodyOutliveTheCompilationDeadline() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      long started = System.nanoTime();
      try (var scope =
              CompilationExecutionBudget.open(
                  Instant.now().plusMillis(250), gate(), Clock.systemUTC());
          var stream = adapter.get("slow").stream()) {
        assertThrows(
            CompilationExecutionBudget.StageDeadlineExpired.class, () -> stream.readAllBytes());
      } finally {
        fixture.release.countDown();
      }
      assertTrue(
          Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000,
          "body deadline must cancel transport, not wait for the 5-minute SDK read timeout");
      assertEquals(1, fixture.slowGets.get());
    }
  }

  @Test
  void expiredOrRevokedAuthorityPreventsNetworkRequests() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      int initial = fixture.requests.get();
      try (var scope =
          CompilationExecutionBudget.open(
              Instant.now().minusSeconds(1), gate(), Clock.systemUTC())) {
        assertThrows(
            CompilationExecutionBudget.StageDeadlineExpired.class, () -> adapter.stat("fast"));
      }
      var denied =
          new CompilationPublication() {
            public void captureInputs(java.util.List<String> versions) {}

            public void publish(Runnable mutation) {
              throw new IllegalStateException("revoked");
            }

            public void check() {
              throw new IllegalStateException("revoked");
            }

            public String objectKey(String key) {
              return key;
            }
          };
      try (var scope =
          CompilationExecutionBudget.open(
              Instant.now().plusSeconds(10), denied, Clock.systemUTC())) {
        assertEquals(
            "revoked",
            assertThrows(IllegalStateException.class, () -> adapter.get("fast")).getMessage());
      }
      assertEquals(initial, fixture.requests.get());
    }
  }

  @Test
  void anUnscopedReadKeepsNormalBehaviorAndACompleteScopedBodyIsAccepted() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      try (var stream = adapter.get("fast").stream()) {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, stream.readAllBytes());
      }
      try (var scope =
              CompilationExecutionBudget.open(
                  Instant.now().plusSeconds(2), gate(), Clock.systemUTC());
          var stream = adapter.get("fast").stream()) {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, stream.readAllBytes());
      }
      assertFalse(CompilationExecutionBudget.active());
    }
  }

  @Test
  void oneAttemptTimeoutDoesNotChangeAnotherCallOnTheSharedAdapter() throws Exception {
    try (var fixture = new Endpoint()) {
      var adapter = fixture.adapter();
      var worker = Executors.newSingleThreadExecutor();
      try {
        var timedOut =
            worker.submit(
                () -> {
                  try (var scope =
                          CompilationExecutionBudget.open(
                              Instant.now().plusMillis(250), gate(), Clock.systemUTC());
                      var stream = adapter.get("slow").stream()) {
                    stream.readAllBytes();
                    return false;
                  } catch (CompilationExecutionBudget.StageDeadlineExpired expected) {
                    return true;
                  }
                });
        assertTrue(fixture.slowStarted.await(2, TimeUnit.SECONDS));
        assertTrue(timedOut.get(2, TimeUnit.SECONDS));
        try (var scope =
                CompilationExecutionBudget.open(
                    Instant.now().plusSeconds(2), gate(), Clock.systemUTC());
            var stream = adapter.get("fast").stream()) {
          assertEquals(4, stream.readAllBytes().length);
        }
      } finally {
        fixture.release.countDown();
        worker.shutdownNow();
      }
    }
  }

  private static CompilationPublication gate() {
    return CompilationPublication.direct(Runnable::run);
  }

  private static final class Endpoint implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final java.util.concurrent.ExecutorService pool = Executors.newCachedThreadPool();
    final CountDownLatch release = new CountDownLatch(1), slowStarted = new CountDownLatch(1);
    final AtomicInteger requests = new AtomicInteger(), slowGets = new AtomicInteger();

    Endpoint() throws Exception {
      server.setExecutor(pool);
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Last-Modified", "Sun, 01 Nov 2026 00:00:00 GMT");
            exchange.getResponseHeaders().add("ETag", "\"0123456789abcdef\"");
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            boolean object = exchange.getRequestURI().getPath().split("/").length > 2;
            if (exchange.getRequestMethod().equals("HEAD")) {
              exchange.getResponseHeaders().add("Content-Length", object ? "4" : "0");
              exchange.sendResponseHeaders(200, -1);
              exchange.close();
              return;
            }
            exchange.sendResponseHeaders(200, 4);
            try (var body = exchange.getResponseBody()) {
              if (exchange.getRequestURI().getPath().endsWith("slow")) {
                slowGets.incrementAndGet();
                body.write(1);
                body.flush();
                slowStarted.countDown();
                try {
                  release.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else body.write(new byte[] {1, 2, 3, 4});
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    MinioObjectStoreAdapter adapter() {
      return new MinioObjectStoreAdapter(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          "local-key",
          "local-test-secret",
          "test-bucket");
    }

    public void close() {
      release.countDown();
      server.stop(0);
      pool.shutdownNow();
    }
  }
}
