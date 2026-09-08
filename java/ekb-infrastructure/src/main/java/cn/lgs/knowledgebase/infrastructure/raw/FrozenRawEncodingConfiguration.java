package cn.lgs.knowledgebase.infrastructure.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import cn.lgs.knowledgebase.application.raw.RawEncodingProfile;
import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Frozen local assets, lazily opened so a missing raw tokenizer never prevents query startup. */
public final class FrozenRawEncodingConfiguration implements AutoCloseable {
  private static final String MODEL = "Qwen/Qwen3-VL-Embedding-2B";
  private static final List<String> FILES =
      List.of(
          "tokenizer.json",
          "tokenizer_config.json",
          "chat_template.jinja",
          "config_sentence_transformers.json",
          "sentence_bert_config.json",
          "modules.json");
  private final String manifestPath;
  private final ObjectMapper mapper;
  private final Store store;
  private final String servingPreprocessingContract;
  private volatile Frozen frozen;
  private boolean closed;
  private final RawTokenCounter tokens =
      new RawTokenCounter() {
        @Override
        public int count(String text) {
          return assets().tokens().count(text);
        }

        @Override
        public String identity() {
          return assets().tokens().identity();
        }
      };

  public FrozenRawEncodingConfiguration(
      String manifestPath, ObjectMapper mapper, Store store, String servingPreprocessingContract) {
    this.manifestPath = manifestPath == null ? "" : manifestPath.trim();
    this.mapper = Objects.requireNonNull(mapper);
    this.store = Objects.requireNonNull(store);
    // This is the actual adapter/serving contract, not a claimed runtime model revision check.
    this.servingPreprocessingContract = servingPreprocessingContract;
  }

  public RawTokenCounter tokens() {
    return tokens;
  }

  public Supplier<RawEncodingProfile> profiles() {
    return this::currentProfile;
  }

  public RawEncodingProfile currentProfile() {
    Frozen value = assets();
    var current = Objects.requireNonNull(store.current(), "effective embedding configuration");
    if (!value.model().equals(current.embeddingModel())
        || value.dimensions() != current.embeddingDimensions())
      throw new IllegalStateException(
          "ENCODING_PROFILE_MISMATCH: active embedding model/dimensions differ from frozen tokenizer manifest");
    URI endpoint;
    try {
      endpoint = URI.create(current.embeddingBaseUrl()).normalize();
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(
          "ENCODING_PROFILE_MISMATCH: invalid embedding endpoint", invalid);
    }
    if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null)
      throw new IllegalStateException(
          "ENCODING_PROFILE_MISMATCH: embedding endpoint must be an HTTP URL without credentials/query/fragment");
    String endpointText = endpoint.toString().replaceAll("/+$", "");
    return new RawEncodingProfile(
        value.model(),
        value.modelRevision(),
        value.dimensions(),
        value.tokens().identity(),
        value.preprocessingHash(),
        value.instruction(),
        KnowledgeDomain.sha256(endpointText));
  }

  private synchronized Frozen assets() {
    if (closed) throw unavailable("local tokenizer is closed", null);
    if (frozen != null) return frozen;
    if (manifestPath.isBlank())
      throw unavailable("explicit local manifest path is not configured", null);
    if (servingPreprocessingContract == null || servingPreprocessingContract.isBlank())
      throw unavailable("actual serving preprocessing contract is not configured", null);
    try {
      Path manifest = Path.of(manifestPath).toAbsolutePath().normalize();
      JsonNode root = mapper.readTree(Files.readAllBytes(manifest));
      String model = required(root, "model");
      String modelRevision = required(root, "modelRevision");
      String tokenizerRevision = required(root, "tokenizerRevision");
      if (!MODEL.equals(model)
          || !modelRevision.matches("[0-9a-f]{40}")
          || !modelRevision.equals(tokenizerRevision)
          || root.path("dimensions").asInt() != 1024)
        throw new IOException(
            "Only the explicitly frozen Qwen3-VL-Embedding-2B 1024-dimensional snapshot is supported");
      JsonNode files = root.path("files");
      if (!files.isObject() || files.size() != FILES.size())
        throw new IOException(
            "Manifest must contain exactly the six tokenizer/preprocessing assets");
      StringBuilder canonical = new StringBuilder();
      for (String filename : FILES) {
        String hash = required(files, filename);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid asset hash: " + filename);
        Path asset = manifest.getParent().resolve(filename);
        if (!Files.isRegularFile(asset) || !hash.equals(sha256(Files.readAllBytes(asset))))
          throw new IOException("Frozen asset checksum mismatch: " + filename);
        frame(canonical, filename);
        frame(canonical, hash);
      }
      String instruction = required(root.path("rendering"), "instruction");
      String prefix = required(root.path("rendering"), "prefix");
      String suffix = required(root.path("rendering"), "suffix");
      JsonNode st =
          mapper.readTree(
              Files.readAllBytes(
                  manifest.getParent().resolve("config_sentence_transformers.json")));
      String promptName = required(st, "default_prompt_name");
      if (!instruction.equals(st.path("prompts").path(promptName).asText())
          || !prefix.equals("<|im_start|>system\n" + instruction + "<|im_end|>\n<|im_start|>user\n")
          || !suffix.equals("<|im_end|>\n<|im_start|>assistant\n"))
        throw new IOException(
            "Manifest rendering differs from the frozen SentenceTransformer default prompt contract");
      JsonNode bert =
          mapper.readTree(
              Files.readAllBytes(manifest.getParent().resolve("sentence_bert_config.json")));
      if (!bert.path("processing_kwargs")
              .path("chat_template")
              .path("add_generation_prompt")
              .asBoolean(false)
          || !bert.path("modality_config").has("message"))
        throw new IOException("Unsupported native text-to-message rendering contract");
      for (String field :
          List.of(
              model,
              modelRevision,
              tokenizerRevision,
              instruction,
              prefix,
              suffix,
              "native-hf-postprocessor-special-tokens:no-truncation:no-padding",
              servingPreprocessingContract)) frame(canonical, field);
      LocalEmbeddingTokenizer nativeTokenizer =
          new LocalEmbeddingTokenizer(
              manifest.getParent().resolve("tokenizer.json"),
              tokenizerRevision,
              files.path("tokenizer.json").asText(),
              prefix,
              suffix);
      frozen =
          new Frozen(
              model,
              modelRevision,
              1024,
              instruction,
              KnowledgeDomain.sha256(canonical.toString()),
              nativeTokenizer);
      return frozen;
    } catch (IOException | RuntimeException | LinkageError failure) {
      throw unavailable("manifest/native tokenizer validation failed", failure);
    }
  }

  private static String required(JsonNode node, String field) throws IOException {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new IOException("Missing manifest field: " + field);
    return value.asText();
  }

  private static void frame(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException("TOKENIZER_UNAVAILABLE: " + message, cause);
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (frozen != null) frozen.tokens().close();
  }

  private record Frozen(
      String model,
      String modelRevision,
      int dimensions,
      String instruction,
      String preprocessingHash,
      LocalEmbeddingTokenizer tokens) {}
}
