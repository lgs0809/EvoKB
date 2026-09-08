package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.port.ExternalCallBudget;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/** Backpressure retries share one observation budget; they do not extend a business stage. */
final class BudgetedHttpRetry {
  private BudgetedHttpRetry() {}

  static byte[] send(
      HttpClient client, HttpRequest request, String operation, boolean retryTimeout) {
    Duration budget = ExternalCallBudget.limit(request.timeout().orElse(Duration.ofSeconds(120)));
    Runnable originalFence = ExternalCallBudget.capturedCheck();
    long deadline = System.nanoTime() + budget.toNanos();
    TimeoutRetryPolicy timeout = new TimeoutRetryPolicy();
    // Spring Retry uses milliseconds; the request and attempt fences retain the exact nanos.
    timeout.setTimeout(Math.max(1, budget.toMillis()));
    CompositeRetryPolicy policy = new CompositeRetryPolicy();
    policy.setPolicies(
        new org.springframework.retry.RetryPolicy[] {
          new SimpleRetryPolicy(5, Map.of(RetryableResponse.class, true), true), timeout
        });
    policy.setOptimistic(false);
    ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
    long initial = Math.max(1, Math.min(5000, budget.toMillis() / 20));
    backoff.setInitialInterval(initial);
    backoff.setMultiplier(2);
    backoff.setMaxInterval(Math.max(initial, Math.min(30000, budget.toMillis() / 4)));
    backoff.setSleeper(
        millis -> {
          originalFence.run();
          long remaining = deadline - System.nanoTime();
          if (remaining > 0)
            TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(millis), remaining));
          originalFence.run();
        });
    RetryTemplate retries = new RetryTemplate();
    retries.setRetryPolicy(policy);
    retries.setBackOffPolicy(backoff);
    try {
      return retries.execute(
          context -> {
            originalFence.run();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ObservationBudgetExpired(operation, null);
            HttpRequest attempt =
                HttpRequest.newBuilder(request, (name, value) -> true)
                    .timeout(Duration.ofNanos(remaining))
                    .build();
            try {
              HttpResponse<byte[]> response = observe(client, attempt);
              if (response.statusCode() / 100 == 2) return response.body();
              String message = operation + " request failed: HTTP " + response.statusCode();
              if (List.of(429, 502, 503, 504, 524).contains(response.statusCode()))
                throw new RetryableResponse(message, null);
              throw new IllegalStateException(message);
            } catch (HttpTimeoutException timeoutFailure) {
              if (System.nanoTime() >= deadline || !retryTimeout)
                throw new ObservationBudgetExpired(operation, timeoutFailure);
              throw new RetryableResponse(operation + " request timed out", timeoutFailure);
            } catch (IOException io) {
              if (transientIo(io))
                throw new RetryableResponse(operation + " request failed: " + io, io);
              throw new IllegalStateException(operation + " request failed: " + io, io);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(operation + " request interrupted", error);
            }
          });
    } catch (RetryableResponse error) {
      originalFence.run();
      throw new TransientDependencyFailure(error.getMessage(), error);
    } catch (ObservationBudgetExpired error) {
      originalFence.run();
      throw new TransientDependencyFailure(error.getMessage(), error);
    } catch (BackOffInterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " retry interrupted", error);
    }
  }

  /** One complete HTTP observation, also used by chat's existing status/proxy retry policy. */
  static HttpResponse<byte[]> observe(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    Duration budget = ExternalCallBudget.limit(request.timeout().orElse(Duration.ofSeconds(120)));
    Runnable originalFence = ExternalCallBudget.capturedCheck();
    long deadline = System.nanoTime() + budget.toNanos();
    HttpRequest bounded =
        HttpRequest.newBuilder(request, (name, value) -> true).timeout(budget).build();
    var future = client.sendAsync(bounded, HttpResponse.BodyHandlers.ofByteArray());
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException("HTTP body observation deadline");
      var response = future.get(remaining, TimeUnit.NANOSECONDS);
      originalFence.run();
      return response;
    } catch (TimeoutException failure) {
      future.cancel(true);
      originalFence.run();
      var timeout =
          new HttpTimeoutException("request timed out while observing complete HTTP body");
      timeout.initCause(failure);
      throw timeout;
    } catch (InterruptedException failure) {
      future.cancel(true);
      throw failure;
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      originalFence.run();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException("HTTP observation failed", cause);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private static boolean transientIo(IOException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof ConnectException) return true;
    String message =
        error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
    return message.contains("connection reset")
        || message.contains("header parser received no bytes");
  }

  private static final class RetryableResponse extends IllegalStateException {
    RetryableResponse(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final class ObservationBudgetExpired extends IllegalStateException {
    ObservationBudgetExpired(String operation, Throwable cause) {
      super(operation + " observation budget exhausted", cause);
    }
  }
}
