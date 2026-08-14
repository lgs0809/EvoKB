package cn.lgs.knowledgebase.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class OpenSearchHttpClient {
    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper;

    OpenSearchHttpClient(String baseUrl, ObjectMapper mapper) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("OpenSearch base URL is required");
        this.baseUrl = value;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    boolean indexExists(String index) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/" + segment(index))).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        if (response.statusCode() == 200) return true;
        if (response.statusCode() == 404) return false;
        throw failure("HEAD", index, response);
    }

    JsonNode json(String method, String path, Object body) {
        try {
            String payload = body == null ? "" : mapper.writeValueAsString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(90));
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            HttpResponse<String> response = send(request.build());
            if (response.statusCode() / 100 != 2) throw failure(method, path, response);
            if (response.body() == null || response.body().isBlank()) return mapper.createObjectNode();
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch JSON encode/decode failed", e);
        }
    }

    JsonNode ndjson(String path, String payload) {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) throw failure("POST", path, response);
        try { return mapper.readTree(response.body()); }
        catch (IOException e) { throw new IllegalStateException("OpenSearch NDJSON response decode failed", e); }
    }

    String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("OpenSearch JSON encode failed", e); }
    }

    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path) { return URI.create(baseUrl + path); }

    private HttpResponse<String> send(HttpRequest request) {
        try { return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IllegalStateException("OpenSearch request failed: " + request.method() + " " + request.uri(), e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenSearch request interrupted", e);
        }
    }

    private IllegalStateException failure(String method, String path, HttpResponse<String> response) {
        return new IllegalStateException("OpenSearch " + method + " " + path + " returned HTTP " + response.statusCode() + ": " + response.body());
    }
}
