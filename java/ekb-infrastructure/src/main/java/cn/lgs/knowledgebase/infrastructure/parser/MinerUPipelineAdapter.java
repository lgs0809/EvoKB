package cn.lgs.knowledgebase.infrastructure.parser;

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
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

public final class MinerUPipelineAdapter implements ParserPort {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final Duration pollInterval;
  private final String parseMethod;
  private final String language;

  public MinerUPipelineAdapter(
      String baseUrl,
      ObjectMapper mapper,
      Duration pollInterval,
      String parseMethod,
      String language) {
    this.client = RestClient.builder().baseUrl(trimSlash(baseUrl)).build();
    this.mapper = mapper;
    this.pollInterval =
        pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
            ? Duration.ofMillis(500)
            : pollInterval;
    this.parseMethod = blankTo(parseMethod, "auto");
    this.language = blankTo(language, "ch");
  }

  @Override
  public ParsedDocument parse(String filename, InputStream source) {
    MultipartBodyBuilder body = new MultipartBodyBuilder();
    body.part("files", new NamedInputStreamResource(source, filename));
    body.part("backend", "pipeline");
    body.part("parse_method", parseMethod);
    body.part("lang_list", language);
    body.part("return_md", "true");
    body.part("return_middle_json", "true");
    body.part("return_content_list", "true");

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
    while (true) {
      TaskStatus status =
          client.get().uri("/tasks/{id}", submitted.taskId()).retrieve().body(TaskStatus.class);
      if (status == null) throw new IllegalStateException("mineru status returned empty response");
      String state = blankTo(status.status(), "").toLowerCase();
      if (List.of("completed", "succeeded", "success").contains(state))
        return result(submitted.taskId());
      if (List.of("failed", "error", "cancelled", "canceled").contains(state)) {
        throw new IllegalStateException(
            "mineru task " + submitted.taskId() + " " + state + ": " + status.error());
      }
      try {
        Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("mineru polling interrupted", e);
      }
    }
  }

  private ParsedDocument result(String taskId) {
    JsonNode envelope =
        client.get().uri("/tasks/{id}/result", taskId).retrieve().body(JsonNode.class);
    if (envelope == null) throw new IllegalStateException("mineru result is empty");
    JsonNode results = envelope.path("results");
    if (!results.isObject() || results.size() != 1)
      throw new IllegalStateException("mineru expected exactly one result file");
    JsonNode file = results.elements().next();
    String markdown = file.path("md_content").asText("");
    String middleJson = normalizeJsonField(file.get("middle_json"));
    String contentList = normalizeJsonField(file.get("content_list"));
    Map<Integer, List<Double>> pageSizes = pageSizes(middleJson);
    List<ParsedBlock> blocks = parsedBlocks(contentList, pageSizes);
    return new ParsedDocument(
        markdown,
        middleJson,
        contentList,
        "mineru-pipeline",
        envelope.path("version").asText(""),
        blocks);
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
    } catch (IOException ignored) {
      return Map.of();
    }
    return sizes;
  }

  private List<ParsedBlock> parsedBlocks(String contentList, Map<Integer, List<Double>> pageSizes) {
    try {
      JsonNode root = mapper.readTree(contentList);
      if (!root.isArray()) return List.of();
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
        String table = block.path("table_body").asText("");
        if (table.isBlank()) table = block.path("table_html").asText("");
        blocks.add(
            new ParsedBlock(
                block.path("type").asText(""),
                text,
                block.path("text_level").asInt(0),
                page < 0 ? null : page,
                bbox,
                pageSizes.getOrDefault(page, List.of()),
                block.path("img_path").asText(""),
                table,
                table,
                toMap(block)));
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
