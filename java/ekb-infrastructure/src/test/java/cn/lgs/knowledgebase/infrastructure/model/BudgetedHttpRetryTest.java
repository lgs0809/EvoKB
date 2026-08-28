package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetedHttpRetryTest {
  HttpServer server;
  URI uri;
  AtomicInteger calls = new AtomicInteger();
  List<String> bodies = new CopyOnWriteArrayList<>();
  volatile int status = 503;
  volatile long delay;
  CountDownLatch firstRequest = new CountDownLatch(1);

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/gate",
        exchange -> {
          calls.incrementAndGet();
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          firstRequest.countDown();
          try {
            if (delay > 0) Thread.sleep(delay);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          byte[] result = "unchanged-server-body".getBytes(StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(status, result.length);
            exchange.getResponseBody().write(result);
          } finally {
            exchange.close();
          }
        });
    server.start();
    uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/gate");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void delayedGateRecoveryKeepsTheExactWholeBodyWithinOneBudget() throws Exception {
    var release =
        new Thread(
            () -> {
              try {
                firstRequest.await();
                Thread.sleep(700);
                status = 200;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    release.start();
    long start = System.nanoTime();
    byte[] result = send(Duration.ofSeconds(3));
    release.join(1000);
    assertEquals("unchanged-server-body", new String(result, StandardCharsets.UTF_8));
    assertTrue(calls.get() >= 3 && calls.get() <= 5);
    assertEquals(1, bodies.stream().distinct().count());
    assertTrue((System.nanoTime() - start) / 1_000_000d < 3100);
  }

  @Test
  void overloadExhaustsFiveAttemptsWithoutCreatingAnUnlimitedWait() {
    long start = System.nanoTime();
    var error = assertThrows(TransientDependencyFailure.class, () -> send(Duration.ofSeconds(1)));
    assertTrue(error.getMessage().contains("HTTP 503"));
    assertEquals(5, calls.get());
    assertTrue((System.nanoTime() - start) / 1_000_000d < 1200);
  }

  @Test
  void slowResponseConsumesTheOriginalDeadlineAndCannotStartAnotherWindow() {
    status = 200;
    delay = 700;
    long start = System.nanoTime();
    var error = assertThrows(TransientDependencyFailure.class, () -> send(Duration.ofMillis(200)));
    assertTrue(error.getMessage().contains("budget exhausted"));
    assertEquals(1, calls.get());
    assertTrue((System.nanoTime() - start) / 1_000_000d < 500);
  }

  @Test
  void successfulHeadersCannotLetSlowBodyEscapeTheOriginalDeadline() {
    server.removeContext("/gate");
    server.createContext(
        "/gate",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = "a complete response body".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes, 0, 2);
            out.flush();
            try {
              Thread.sleep(700);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
            out.write(bytes, 2, bytes.length - 2);
          } finally {
            exchange.close();
          }
        });
    long start = System.nanoTime();
    var error = assertThrows(TransientDependencyFailure.class, () -> send(Duration.ofMillis(200)));
    assertTrue(error.getMessage().contains("budget exhausted"));
    assertEquals(1, calls.get());
    assertTrue((System.nanoTime() - start) / 1_000_000d < 500);
  }

  @Test
  void interruptDuringBackoffExitsAndRetainsInterruptStatus() throws Exception {
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicReference<Boolean>();
    var caller =
        new Thread(
            () -> {
              try {
                send(Duration.ofSeconds(10));
              } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    caller.start();
    assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
    Thread.sleep(30);
    caller.interrupt();
    caller.join(1000);
    assertFalse(caller.isAlive());
    assertNotNull(failure.get());
    assertEquals(Boolean.TRUE, interrupted.get());
    assertEquals(1, calls.get());
  }

  @Test
  void unauthorizedResponseDoesNotSpendASecondAttempt() {
    status = 401;
    assertThrows(IllegalStateException.class, () -> send(Duration.ofSeconds(3)));
    assertEquals(1, calls.get());
  }

  byte[] send(Duration budget) {
    return BudgetedHttpRetry.send(
        HttpClient.newHttpClient(),
        HttpRequest.newBuilder(uri)
            .timeout(budget)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"完整正文🙂及其前提\"}"))
            .build(),
        "embedding",
        false);
  }
}
