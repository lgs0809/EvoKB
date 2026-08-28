package cn.lgs.knowledgebase.infrastructure.model;

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
    Duration budget = request.timeout().orElse(Duration.ofSeconds(120));
    long deadline = System.nanoTime() + budget.toNanos();
    TimeoutRetryPolicy timeout = new TimeoutRetryPolicy();
    timeout.setTimeout(budget.toMillis());
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
          long remaining = deadline - System.nanoTime();
          if (remaining > 0) Thread.sleep(Math.min(millis, Math.max(1, remaining / 1_000_000L)));
        });
    RetryTemplate retries = new RetryTemplate();
    retries.setRetryPolicy(policy);
    retries.setBackOffPolicy(backoff);
    try {
      return retries.execute(
          context -> {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ObservationBudgetExpired(operation, null);
            HttpRequest attempt =
                HttpRequest.newBuilder(request, (name, value) -> true)
                    .timeout(Duration.ofNanos(remaining))
                    .build();
            var future = client.sendAsync(attempt, HttpResponse.BodyHandlers.ofByteArray());
            try {
              // JDK request.timeout may finish its timer when headers arrive. Awaiting the
              // complete byte-array future keeps body transfer inside the original budget.
              long bodyRemaining = deadline - System.nanoTime();
              if (bodyRemaining <= 0) throw new TimeoutException("HTTP body observation deadline");
              HttpResponse<byte[]> response = future.get(bodyRemaining, TimeUnit.NANOSECONDS);
              if (response.statusCode() / 100 == 2) return response.body();
              String message = operation + " request failed: HTTP " + response.statusCode();
              if (List.of(429, 502, 503, 504, 524).contains(response.statusCode()))
                throw new RetryableResponse(message, null);
              throw new IllegalStateException(message);
            } catch (TimeoutException error) {
              future.cancel(true);
              throw new ObservationBudgetExpired(operation, error);
            } catch (ExecutionException error) {
              Throwable cause = error.getCause();
              if (cause instanceof HttpTimeoutException timeoutFailure) {
                if (System.nanoTime() >= deadline || !retryTimeout)
                  throw new ObservationBudgetExpired(operation, timeoutFailure);
                throw new RetryableResponse(operation + " request timed out", timeoutFailure);
              }
              if (cause instanceof IOException io && transientIo(io))
                throw new RetryableResponse(operation + " request failed: " + io, io);
              throw new IllegalStateException(operation + " request failed: " + cause, cause);
            } catch (InterruptedException error) {
              future.cancel(true);
              Thread.currentThread().interrupt();
              throw new IllegalStateException(operation + " request interrupted", error);
            }
          });
    } catch (RetryableResponse error) {
      throw new TransientDependencyFailure(error.getMessage(), error);
    } catch (ObservationBudgetExpired error) {
      throw new TransientDependencyFailure(error.getMessage(), error);
    } catch (BackOffInterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(operation + " retry interrupted", error);
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
