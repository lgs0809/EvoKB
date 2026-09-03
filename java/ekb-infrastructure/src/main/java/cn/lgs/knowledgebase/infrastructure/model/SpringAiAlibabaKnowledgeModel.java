package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * OpenAI-compatible knowledge-model boundary. The model endpoint, credentials and role mappings are
 * read from the administrator-managed configuration for every call so saved settings take effect
 * without an application restart.
 */
public final class SpringAiAlibabaKnowledgeModel implements KnowledgeModel {
  // The configured provider occasionally terminates the TLS handshake for the first few
  // connections in a burst.  Keep retries bounded, but leave enough room for the provider to
  // recover without ever retrying non-transient/business or structured-decoding failures.
  private static final int TRANSIENT_RETRY_ATTEMPTS = 5;
  private static final long TRANSIENT_RETRY_BACKOFF_MILLIS = 5_000L;
  private final Store configurationStore;
  private final ObjectMapper mapper;

  public SpringAiAlibabaKnowledgeModel(Store configurationStore, ObjectMapper mapper) {
    this.configurationStore = Objects.requireNonNull(configurationStore, "configuration store");
    this.mapper = Objects.requireNonNull(mapper, "object mapper");
  }

  @Override
  public String identity(ModelRole role) {
    EffectiveConfiguration config = configurationStore.current();
    return model(config, role);
  }

  @Override
  public String generate(ModelRole role, ModelTask task, String systemPrompt, String userPrompt) {
    String content = call(role, task, systemPrompt, userPrompt);
    if (content.isBlank())
      throw new IllegalStateException("model returned empty content for " + task);
    return content.trim();
  }

  @Override
  public <T> T generateStructured(
      ModelRole role, ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
    BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType, mapper);
    String structuredSystem =
        text(systemPrompt)
            + "\n\nReturn only a JSON value that follows this schema and formatting contract:\n"
            + converter.getFormat();
    String content = call(role, task, structuredSystem, userPrompt);
    try {
      JsonNode parsed = mapper.readTree(extractJson(content));
      T output = mapper.treeToValue(normalizeStructuredKeys(parsed), outputType);
      if (output == null)
        throw new IllegalStateException("model returned empty structured output for " + task);
      return output;
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("cannot decode structured model output for " + task, failure);
    }
  }

  private String extractJson(String content) {
    String value = content == null ? "" : content.trim();
    if (value.startsWith("```")) {
      int firstNewline = value.indexOf('\n');
      if (firstNewline >= 0) value = value.substring(firstNewline + 1).trim();
      if (value.endsWith("```")) value = value.substring(0, value.length() - 3).trim();
    }
    int objectStart = value.indexOf('{');
    int arrayStart = value.indexOf('[');
    int start;
    char endChar;
    if (objectStart < 0) {
      start = arrayStart;
      endChar = ']';
    } else if (arrayStart < 0 || objectStart < arrayStart) {
      start = objectStart;
      endChar = '}';
    } else {
      start = arrayStart;
      endChar = ']';
    }
    if (start < 0) throw new IllegalStateException("structured model output does not contain JSON");
    int end = value.lastIndexOf(endChar);
    if (end < start)
      throw new IllegalStateException("structured model output contains incomplete JSON");
    return value.substring(start, end + 1);
  }

  private JsonNode normalizeStructuredKeys(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) return node;
    if (node.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      node.forEach(item -> out.add(normalizeStructuredKeys(item)));
      return out;
    }
    ObjectNode out = mapper.createObjectNode();
    node.fields()
        .forEachRemaining(
            entry -> {
              String normalized = toSnakeCase(entry.getKey());
              JsonNode value = normalizeStructuredKeys(entry.getValue());
              if (!out.has(normalized) || normalized.equals(entry.getKey()))
                out.set(normalized, value);
            });
    return out;
  }

  private static String toSnakeCase(String value) {
    if (value == null || value.isBlank()) return value;
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0 && out.charAt(out.length() - 1) != '_') out.append('_');
        out.append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private String call(ModelRole role, ModelTask task, String systemPrompt, String userPrompt) {
    EffectiveConfiguration config = configurationStore.current();
    String model = model(config, role);
    String endpoint = chatCompletionEndpoint(config.chatBaseUrl());
    byte[] payload;
    try {
      payload =
          mapper.writeValueAsBytes(
              Map.of(
                  "model",
                  model,
                  // The configured GPT-5.6-compatible gateway applies its own structured-output
                  // budget. Do not send legacy temperature/max_tokens overrides: with these
                  // fields present it can route the request to a compatibility path that leaves
                  // the connection open. The complete RawBlock input is still sent verbatim;
                  // provider/decode failures remain fail-closed at the caller.
                  "messages",
                  List.of(
                      Map.of("role", "system", "content", text(systemPrompt)),
                      Map.of("role", "user", "content", text(userPrompt)))));
    } catch (IOException e) {
      throw new IllegalStateException("cannot encode model request for " + task, e);
    }

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(Math.max(1, config.chatTimeoutSeconds())))
            .header("Content-Type", "application/json")
            // Some OpenAI-compatible gateways route requests by the HTTP/1.1 client profile;
            // an explicit user agent avoids their default TLS/HTTP negotiation path while
            // retaining the same JSON contract and evidence payload.
            .header("User-Agent", "EvoKB/0.1")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
    if (config.chatApiKey() != null && !config.chatApiKey().isBlank()) {
      request.header("Authorization", "Bearer " + config.chatApiKey());
    }

    HttpResponse<byte[]> response = sendWithTransientRetry(request.build(), task);
    try {
      JsonNode root = mapper.readTree(response.body());
      String businessError = providerBusinessError(root);
      if (!businessError.isBlank()) {
        throw new IllegalStateException("chat request failed for " + task + ": " + businessError);
      }
      JsonNode content = root.path("choices").path(0).path("message").path("content");
      if (!content.isTextual() || content.asText().isBlank()) {
        throw new IllegalStateException("chat response missing content for " + task);
      }
      return content.asText();
    } catch (IOException e) {
      throw new IllegalStateException("cannot decode chat response for " + task, e);
    }
  }

  private HttpResponse<byte[]> sendWithTransientRetry(HttpRequest request, ModelTask task) {
    IOException lastIoFailure = null;
    boolean proxyConfigured = proxyConfigured();
    boolean directFallbackAllowed = proxyConfigured && directFallbackAllowed();
    for (int attempt = 1; attempt <= TRANSIENT_RETRY_ATTEMPTS; attempt++) {
      try {
        HttpResponse<byte[]> response =
            newHttpClient(attempt).send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status / 100 == 2) return response;
        if (!transientStatus(status)
            || nonRetryableTransientResponse(status, response.body())
            || attempt == TRANSIENT_RETRY_ATTEMPTS) {
          throw new IllegalStateException(
              "chat request failed for "
                  + task
                  + ": HTTP "
                  + status
                  + providerMessage(response.body()));
        }
      } catch (IOException failure) {
        lastIoFailure = failure;
        // A local proxy may be reachable from the host but not from the Docker gateway address.
        // If the connection failed before an HTTP response (connect/TLS handshake), make one
        // direct attempt before consuming the retry slot. This keeps the configured proxy as the
        // primary path while allowing the provider's direct route when the proxy binding is
        // loopback-only; HTTP status/business/structured failures remain fail-closed.
        if (directFallbackAllowed && proxyConnectionFailure(failure)) {
          try {
            HttpResponse<byte[]> directResponse =
                newHttpClient(attempt, false)
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            int directStatus = directResponse.statusCode();
            if (directStatus / 100 == 2) return directResponse;
            if (!transientStatus(directStatus)
                || nonRetryableTransientResponse(directStatus, directResponse.body())
                || attempt == TRANSIENT_RETRY_ATTEMPTS) {
              throw new IllegalStateException(
                  "chat request failed for "
                      + task
                      + ": HTTP "
                      + directStatus
                      + providerMessage(directResponse.body()));
            }
          } catch (IOException directFailure) {
            lastIoFailure = directFailure;
          } catch (InterruptedException directFailure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chat request interrupted for " + task, directFailure);
          }
        }
        if (retryBudgetExhausted(failure, attempt)) {
          throw new IllegalStateException(
              "chat request failed for "
                  + task
                  + ": "
                  + failure.getClass().getSimpleName()
                  + ": "
                  + failure.getMessage(),
              failure);
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("chat request interrupted for " + task, failure);
      }
      sleepBeforeRetry(task, attempt);
    }
    throw new IllegalStateException("chat request failed for " + task, lastIoFailure);
  }

  private static boolean transientStatus(int status) {
    // Cloudflare-style gateway timeouts (524) are transport failures just like 504s.  Retry
    // them with the same bounded fresh-connection policy; non-transient/business responses still
    // fail immediately and structured decoding remains fail-closed at the caller.
    return status == 429 || status == 502 || status == 503 || status == 504 || status == 524;
  }

  /**
   * A gateway can use HTTP 503 for a deterministic account/provider capacity response. Retrying
   * that exact request only burns the same provider quota and delays the durable checkpoint, so
   * fail fast while retaining retries for ordinary transient 503 responses.
   */
  private boolean nonRetryableTransientResponse(int status, byte[] payload) {
    if (status != 503 || payload == null || payload.length == 0) return false;
    String message = providerMessage(payload).toLowerCase(java.util.Locale.ROOT);
    return message.contains("no available token")
        || message.contains("no available tokens")
        || message.contains("token unavailable");
  }

  private static HttpClient newHttpClient(int attempt) {
    return newHttpClient(attempt, true);
  }

  private static HttpClient newHttpClient(int attempt, boolean allowProxy) {
    // Use the JVM's normal certificate-validated TLS negotiation. The provider's current
    // gateway accepts the default TLS profile; forcing a hand-built TLS 1.2 context made the
    // Linux-container -> local proxy handshake fail intermittently even when the same request
    // succeeded through curl. HTTP/1.1 and fresh clients per retry remain intentional.
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1);
    String proxyHost = System.getenv("EKB_CHAT_PROXY_HOST");
    String proxyPort = System.getenv("EKB_CHAT_PROXY_PORT");
    if (allowProxy
        && proxyHost != null
        && !proxyHost.isBlank()
        && proxyPort != null
        && !proxyPort.isBlank()) {
      try {
        int port = Integer.parseInt(proxyPort.trim());
        if (port < 1 || port > 65_535) throw new NumberFormatException("port out of range");
        builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), port)));
      } catch (NumberFormatException failure) {
        throw new IllegalStateException("invalid EKB_CHAT_PROXY_PORT", failure);
      }
    }
    return builder.build();
  }

  private static boolean proxyConfigured() {
    String proxyHost = System.getenv("EKB_CHAT_PROXY_HOST");
    String proxyPort = System.getenv("EKB_CHAT_PROXY_PORT");
    return proxyHost != null && !proxyHost.isBlank() && proxyPort != null && !proxyPort.isBlank();
  }

  private static boolean directFallbackAllowed() {
    String proxyHost = System.getenv("EKB_CHAT_PROXY_HOST");
    if (proxyHost == null) return false;
    String normalized = proxyHost.trim().toLowerCase(java.util.Locale.ROOT);
    // A direct attempt is useful only when the configured proxy is itself a loopback alias or
    // host.docker.internal. For an explicit LAN relay, direct egress from the container is not
    // an alternative and would merely add another full timeout to every failed request.
    return normalized.equals("127.0.0.1")
        || normalized.equals("localhost")
        || normalized.equals("host.docker.internal");
  }

  private static boolean proxyConnectionFailure(IOException failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ConnectException
          || current instanceof HttpConnectTimeoutException
          || current instanceof SSLHandshakeException) return true;
    }
    return false;
  }

  private static boolean retryBudgetExhausted(IOException failure, int attempt) {
    // A stalled TLS handshake or request timeout does not improve by repeating the same long
    // request five times. Keep one recovery attempt, then return to the durable compilation
    // checkpoint so an operator can switch route/provider without burning an hour per unit.
    boolean slowTransport =
        proxyConnectionFailure(failure) || failure instanceof java.net.http.HttpTimeoutException;
    return attempt >= (slowTransport ? 2 : TRANSIENT_RETRY_ATTEMPTS);
  }

  private static void sleepBeforeRetry(ModelTask task, int attempt) {
    try {
      Thread.sleep(attempt * TRANSIENT_RETRY_BACKOFF_MILLIS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("chat request interrupted for " + task, failure);
    }
  }

  private String providerMessage(byte[] payload) {
    if (payload == null || payload.length == 0) return "";
    try {
      JsonNode message = mapper.readTree(payload).path("error").path("message");
      if (!message.isTextual() || message.asText().isBlank()) return "";
      String value = message.asText().trim();
      return ": " + bounded(value);
    } catch (IOException ignored) {
      return "";
    }
  }

  private static String providerBusinessError(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) return "";
    JsonNode code = root.path("code");
    JsonNode message = root.path("message");
    if (!code.isTextual() && !message.isTextual()) return "";
    String codeText = code.isTextual() ? code.asText().trim() : "";
    String messageText = message.isTextual() ? message.asText().trim() : "";
    if (codeText.isBlank() && messageText.isBlank()) return "";
    if (codeText.isBlank()) return bounded(messageText);
    if (messageText.isBlank()) return bounded(codeText);
    return bounded(codeText + ": " + messageText);
  }

  private static String bounded(String value) {
    return value.length() > 500 ? value.substring(0, 500) : value;
  }

  private static String model(EffectiveConfiguration config, ModelRole role) {
    String value = role == ModelRole.LARGE ? config.largeModel() : config.smallModel();
    if (value == null || value.isBlank())
      throw new IllegalStateException("configured model is blank");
    return value.trim();
  }

  private static String chatCompletionEndpoint(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank())
      throw new IllegalStateException("chat base URL is not configured");
    String value = baseUrl.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    if (value.endsWith("/v1")) return value + "/chat/completions";
    return value + "/v1/chat/completions";
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
