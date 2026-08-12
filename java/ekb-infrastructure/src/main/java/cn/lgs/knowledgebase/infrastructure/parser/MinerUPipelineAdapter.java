package cn.lgs.knowledgebase.infrastructure.parser;

import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.ParsedBlock;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

public final class MinerUPipelineAdapter implements ParserPort {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final Duration pollInterval;
  private final Duration taskTimeout;
  private final String parseMethod;
  private final String language;
  private final String backend;
  private final String effort;
  private final String expectedVersion;
  private final ObjectStore objects;

  public MinerUPipelineAdapter(
      String baseUrl,
      ObjectMapper mapper,
      Duration pollInterval,
      String parseMethod,
      String language) {
    this(baseUrl, mapper, pollInterval, Duration.ofMinutes(10), parseMethod, language);
  }

  public MinerUPipelineAdapter(
      String baseUrl,
      ObjectMapper mapper,
      Duration pollInterval,
      Duration taskTimeout,
      String parseMethod,
      String language) {
    this(
        baseUrl,
        mapper,
        pollInterval,
        taskTimeout,
        parseMethod,
        language,
        "hybrid-engine",
        "medium",
        "3.4.5");
  }

  public MinerUPipelineAdapter(
      String baseUrl,
      ObjectMapper mapper,
      Duration pollInterval,
      Duration taskTimeout,
      String parseMethod,
      String language,
      String backend,
      String effort,
      String expectedVersion) {
    this(
        baseUrl,
        mapper,
        pollInterval,
        taskTimeout,
        parseMethod,
        language,
        backend,
        effort,
        expectedVersion,
        null);
  }

  public MinerUPipelineAdapter(
      String baseUrl,
      ObjectMapper mapper,
      Duration pollInterval,
      Duration taskTimeout,
      String parseMethod,
      String language,
      String backend,
      String effort,
      String expectedVersion,
      ObjectStore objects) {
    this.objects = objects;
    this.backend = blankTo(backend, "hybrid-engine");
    this.effort = blankTo(effort, "medium");
    this.expectedVersion = blankTo(expectedVersion, "3.4.5");
    Duration bounded = positive(taskTimeout, Duration.ofMinutes(10));
    if (bounded.compareTo(Duration.ofMinutes(10)) > 0) bounded = Duration.ofMinutes(10);
    java.net.http.HttpClient http =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(
        bounded.compareTo(Duration.ofSeconds(5)) < 0 ? bounded : Duration.ofSeconds(5));
    this.client = RestClient.builder().baseUrl(trimSlash(baseUrl)).requestFactory(factory).build();
    this.mapper = mapper;
    this.pollInterval = positive(pollInterval, Duration.ofMillis(500));
    this.taskTimeout = bounded;
    this.parseMethod = blankTo(parseMethod, "auto");
    this.language = blankTo(language, "ch");
  }

  @Override
  public ParsedDocument parse(String filename, InputStream source) {
    return parse(filename, source, "", ignored -> {});
  }

  @Override
  public ParsedDocument parse(
      String filename, InputStream source, String resumeTaskId, Consumer<String> taskIdSink) {
    Consumer<String> sink = taskIdSink == null ? ignored -> {} : taskIdSink;
    String taskId = resumeTaskId == null ? "" : resumeTaskId.trim();
    long startedNanos = System.nanoTime();
    if (taskId.isEmpty()) {
      taskId = submit(filename, source, sink);
    }
    boolean resubmittedMissingTask = false;
    while (true) {
      if (System.nanoTime() - startedNanos >= taskTimeout.toNanos()) {
        IllegalStateException timeout =
            new IllegalStateException(
                "mineru task "
                    + taskId
                    + " timed out after "
                    + taskTimeout.toSeconds()
                    + " seconds");
        cancel(taskId, timeout);
        throw timeout;
      }
      TaskStatus status;
      try {
        status = client.get().uri("/tasks/{id}", taskId).retrieve().body(TaskStatus.class);
      } catch (HttpClientErrorException.NotFound missing) {
        // A persisted task can disappear when the external MinerU service is restarted or
        // expires its task-retention window.  The source stream is still untouched while a
        // resume task id is being polled, so recover exactly once by submitting that same
        // staged file and persisting the new id.  Do not resubmit a task that still exists.
        if (resubmittedMissingTask) {
          throw new IllegalStateException(
              "mineru task " + taskId + " not found after recovery", missing);
        }
        sink.accept("");
        taskId = submit(filename, source, sink);
        resubmittedMissingTask = true;
        continue;
      }
      if (status == null) throw new IllegalStateException("mineru status returned empty response");
      String state = blankTo(status.status(), "").toLowerCase();
      if (List.of("completed", "succeeded", "success").contains(state)) {
        ParsedDocument parsed = result(taskId);
        if (System.nanoTime() - startedNanos >= taskTimeout.toNanos()) {
          IllegalStateException timeout =
              new IllegalStateException("mineru result arrived after stage deadline");
          cancel(taskId, timeout);
          throw timeout;
        }
        return parsed;
      }
      if (List.of("failed", "error", "cancelled", "canceled").contains(state)) {
        sink.accept("");
        throw new IllegalStateException(
            "mineru task " + taskId + " " + state + ": " + status.error());
      }
      try {
        Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException e) {
        IllegalStateException interrupted =
            new IllegalStateException("mineru polling interrupted", e);
        cancel(taskId, interrupted);
        Thread.currentThread().interrupt();
        throw interrupted;
      }
    }
  }

  private String submit(String filename, InputStream source, Consumer<String> sink) {
    MultipartBodyBuilder body = new MultipartBodyBuilder();
    body.part("files", new NamedInputStreamResource(source, filename));
    body.part("backend", backend);
    body.part("effort", effort);
    body.part("parse_method", parseMethod);
    body.part("lang_list", language);
    body.part("return_md", "true");
    body.part("return_middle_json", "true");
    body.part("return_content_list", "true");
    body.part("return_images", "true");

    TaskStatus submitted =
        client
            .post()
            .uri("/tasks")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body.build())
            .retrieve()
            .body(TaskStatus.class);
    if (submitted == null || submitted.taskId() == null || submitted.taskId().isBlank()) {
      throw new IllegalStateException("mineru submit returned empty task_id");
    }
    String taskId = submitted.taskId().trim();
    sink.accept(taskId);
    return taskId;
  }

  private ParsedDocument result(String taskId) {
    JsonNode envelope =
        client.get().uri("/tasks/{id}/result", taskId).retrieve().body(JsonNode.class);
    if (envelope == null) throw new IllegalStateException("mineru result is empty");
    if (!expectedVersion.equals(envelope.path("version").asText()))
      throw new IllegalStateException(
          "MinerU parser version differs from frozen baseline " + expectedVersion);
    if (!envelope.path("backend").isMissingNode()
        && !backend.equals(envelope.path("backend").asText()))
      throw new IllegalStateException("MinerU result backend differs from requested profile");
    if (envelope.has("quality") && !"PASS".equals(envelope.path("quality").path("status").asText()))
      throw new IllegalStateException(
          "MinerU quality review required: " + envelope.path("quality"));
    JsonNode results = envelope.path("results");
    if (!results.isObject() || results.size() != 1)
      throw new IllegalStateException("mineru expected exactly one result file");
    JsonNode file = results.elements().next();
    String markdown = file.path("md_content").asText("");
    String middleJson = normalizeJsonField(file.get("middle_json"));
    String contentList = normalizeJsonField(file.get("content_list"));
    Map<Integer, List<Double>> pageSizes = pageSizes(middleJson);
    boolean office = "office".equals(envelope.path("effective_backend").asText());
    Map<String, Object> profile =
        envelope.has("parser_profile")
            ? toMap(envelope.path("parser_profile"))
            : Map.of(
                "parserVersion",
                expectedVersion,
                "backend",
                backend,
                "effort",
                effort,
                "parseMethod",
                parseMethod,
                "language",
                language,
                "modelRevision",
                "UNVERIFIED_UPSTREAM");
    if (profile.containsKey("backend") && !backend.equals(profile.get("backend"))
        || profile.containsKey("effort") && !effort.equals(profile.get("effort"))
        || profile.containsKey("parseMethod") && !parseMethod.equals(profile.get("parseMethod"))
        || profile.containsKey("language") && !language.equals(profile.get("language")))
      throw new IllegalStateException("MinerU parser receipt differs from submitted profile");
    Map<String, String> images = persistImages(taskId, file.path("images"));
    Map<String, Object> receipt = new LinkedHashMap<>(profile);
    receipt.put("imageObjects", images);
    List<ParsedBlock> blocks = parsedBlocks(contentList, pageSizes, office, receipt, images);
    if (blocks.stream().noneMatch(block -> !block.text().isBlank() || !block.tableBody().isBlank()))
      throw new IllegalStateException(
          "MinerU parsed no retrievable text/table; quality review required");
    return new ParsedDocument(
        markdown,
        middleJson,
        contentList,
        "office".equals(envelope.path("effective_backend").asText())
            ? "mineru-office"
            : "mineru-" + backend,
        envelope.path("version").asText(""),
        blocks);
  }

  private Map<String, String> persistImages(String taskId, JsonNode manifest) {
    if (manifest.isMissingNode() || manifest.isNull() || manifest.size() == 0) return Map.of();
    if (!manifest.isObject()) throw new IllegalStateException("Invalid MinerU image manifest");
    if (objects == null)
      throw new IllegalStateException(
          "IMAGE_STORAGE_UNAVAILABLE: parser images require the object store");
    Map<String, String> imageObjects = new LinkedHashMap<>();
    manifest
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey(), relative = entry.getValue().path("path").asText();
              if (!name.equals("images/" + relative)
                  || !relative.matches("[A-Za-z0-9._/-]+")
                  || relative.contains("..")
                  || relative.startsWith("/"))
                throw new IllegalStateException("Unsafe MinerU image path");
              byte[] bytes =
                  client
                      .get()
                      .uri("/tasks/{id}/images/{name}", taskId, relative)
                      .retrieve()
                      .body(byte[].class);
              String expected = entry.getValue().path("sha256").asText();
              String actual;
              try {
                actual =
                    java.util.HexFormat.of()
                        .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(bytes == null ? new byte[0] : bytes));
              } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
              }
              if (!expected.matches("[0-9a-f]{64}")
                  || !expected.equals(actual)
                  || bytes == null
                  || bytes.length != entry.getValue().path("size").asLong(-1))
                throw new IllegalStateException(
                    "MinerU image bytes differ from immutable result manifest");
              String suffix =
                  relative
                      .substring(relative.lastIndexOf('.') + 1)
                      .toLowerCase(java.util.Locale.ROOT);
              if (!List.of("jpg", "jpeg", "png", "webp").contains(suffix))
                throw new IllegalStateException("Unsupported parser image type");
              String key = "parsed/mineru/" + taskId + "/images/" + actual + "." + suffix;
              objects.put(
                  key,
                  new java.io.ByteArrayInputStream(bytes),
                  bytes.length,
                  "image/" + (suffix.equals("jpg") ? "jpeg" : suffix));
              imageObjects.put(name, key);
            });
    return Map.copyOf(imageObjects);
  }

  private void cancel(String taskId, RuntimeException failure) {
    // The project's isolated service implements DELETE. Upstream 3.4.5 does not; a failed
    // cancellation remains visible instead of being reported as released compute resources.
    try {
      JsonNode cancellation =
          client.delete().uri("/tasks/{id}", taskId).retrieve().body(JsonNode.class);
      if (cancellation == null || !cancellation.path("resource_release_confirmed").asBoolean(false))
        throw new IllegalStateException(
            "Remote parser did not confirm release of its owned execution resource");
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(
          new IllegalStateException("MinerU remote cancellation unconfirmed", cleanup));
    }
  }

  private String normalizeJsonField(JsonNode node) {
    if (node == null || node.isNull()) return "null";
    if (node.isTextual()) return node.asText();
    try {
      return mapper.writeValueAsString(node);
    } catch (IOException e) {
      throw new IllegalStateException("cannot normalize MinerU JSON", e);
    }
  }

  private Map<Integer, List<Double>> pageSizes(String middleJson) {
    Map<Integer, List<Double>> sizes = new HashMap<>();
    try {
      JsonNode root = mapper.readTree(middleJson);
      for (JsonNode page : root.path("pdf_info")) {
        JsonNode sizeNode = page.path("page_size");
        if (sizeNode.isArray()
            && sizeNode.size() >= 2
            && sizeNode.get(0).asDouble() > 0
            && sizeNode.get(1).asDouble() > 0) {
          List<Double> size = new ArrayList<>();
          sizeNode.forEach(v -> size.add(v.asDouble()));
          sizes.put(page.path("page_idx").asInt(-1), List.copyOf(size));
        }
      }
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid MinerU middle JSON", invalid);
    }
    return sizes;
  }

  private List<ParsedBlock> parsedBlocks(
      String contentList,
      Map<Integer, List<Double>> pageSizes,
      boolean office,
      Map<String, Object> receipt,
      Map<String, String> images) {
    try {
      JsonNode root = mapper.readTree(contentList);
      if (!root.isArray())
        throw new IllegalStateException("MinerU content-list v1 must be an array");
      List<ParsedBlock> blocks = new ArrayList<>(root.size());
      for (JsonNode block : root) {
        int page = block.path("page_idx").asInt(-1);
        String text = block.path("text").asText("");
        if ("image".equals(block.path("type").asText())
            && text.isBlank()
            && block.path("image_caption").isArray()) {
          List<String> captions = new ArrayList<>();
          block
              .path("image_caption")
              .forEach(
                  item -> {
                    if (!item.asText("").isBlank()) captions.add(item.asText());
                  });
          text = String.join(" ", captions);
        }
        List<Double> bbox = doubles(block.path("bbox"));
        if (!bbox.isEmpty()
            && (bbox.size() != 4
                || bbox.stream().anyMatch(v -> !Double.isFinite(v) || v < 0 || v > 1000)
                || bbox.get(0) > bbox.get(2)
                || bbox.get(1) > bbox.get(3)))
          throw new IllegalStateException(
              "Invalid normalized_1000 MinerU bbox; quality review required");
        String table = block.path("table_body").asText("");
        if (table.isBlank()) table = block.path("table_html").asText("");
        Map<String, Object> raw = toMap(block);
        raw.put("_evokb_parser_receipt", receipt);
        if (office) {
          raw.put("source_office_element_page_idx", page);
          raw.put("position_space", "office-element-order");
        }
        String originalImage = block.path("img_path").asText("");
        String storedImage = images.getOrDefault(originalImage, originalImage);
        blocks.add(
            new ParsedBlock(
                block.path("type").asText(""),
                text,
                block.path("text_level").asInt(0),
                office || page < 0 ? null : page,
                office ? List.of() : bbox,
                office ? List.of() : pageSizes.getOrDefault(page, List.of()),
                storedImage,
                table,
                table,
                Map.copyOf(raw)));
      }
      return List.copyOf(blocks);
    } catch (IOException e) {
      throw new IllegalStateException("cannot parse MinerU content_list", e);
    }
  }

  private List<Double> doubles(JsonNode node) {
    if (!node.isArray()) return List.of();
    List<Double> values = new ArrayList<>();
    node.forEach(v -> values.add(v.asDouble()));
    return List.copyOf(values);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(JsonNode node) {
    return mapper.convertValue(node, LinkedHashMap.class);
  }

  public record TaskStatus(
      @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
      String status,
      Object error) {}

  private static final class NamedInputStreamResource extends InputStreamResource {
    private final String filename;

    private NamedInputStreamResource(InputStream inputStream, String filename) {
      super(inputStream);
      this.filename = filename;
    }

    @Override
    public String getFilename() {
      return filename;
    }

    @Override
    public long contentLength() {
      return -1;
    }
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String trimSlash(String value) {
    String text = blankTo(value, "");
    if (text.isEmpty()) throw new IllegalArgumentException("MinerU base URL is required");
    while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
    return text;
  }
}
