package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Validates a server certificate from the same admission as each complete encoder input. */
final class EncodingIdentityCodec {
  private final ObjectMapper mapper;

  EncodingIdentityCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  Optional<EncodingIdentity> profile(JsonNode certificate, String model, int dimensions) {
    if (certificate == null || certificate.isNull()) return Optional.empty();
    if (!"qwen-encoding-v1".equals(certificate.path("schema").asText())) return Optional.empty();
    JsonNode profile = certificate.path("profile");
    String fingerprint = hashText(certificate, "profile_sha256");
    try {
      if (!fingerprint.equals(sha256(mapper.writeValueAsBytes(canonical(profile)))))
        throw mismatch("profile canonical SHA-256 differs");
    } catch (java.io.IOException error) {
      throw mismatch("cannot canonicalize encoding profile", error);
    }
    if (!model.equals(requiredText(profile, "model"))
        || dimensions != requiredPositiveInt(profile, "dimensions")
        || !"document".equals(requiredText(profile, "input_type"))
        || !profile.path("normalize").isBoolean()
        || !profile.path("normalize").booleanValue()
        || !"reject-over-limit".equals(requiredText(profile, "truncation")))
      throw mismatch("model, dimensions, input type, or preprocessing contract differs");
    String revision = requiredText(profile, "loaded_revision");
    if (!revision.matches("[0-9a-f]{40}")) throw mismatch("actual model revision required");
    for (String field : List.of("tokenizer_sha256", "preprocessor_sha256", "pipeline_sha256"))
      hashText(profile, field);
    requiredPositiveInt(profile, "max_tokens");
    requiredPositiveInt(profile, "cpu_threads");
    requiredPositiveInt(profile, "interop_threads");
    for (String field : List.of("device", "parameter_dtype", "cpu_attention", "resize"))
      requiredText(profile, field);
    JsonNode packages = profile.path("packages");
    if (!packages.isObject()) throw mismatch("actual package versions required");
    for (String name : List.of("torch", "transformers", "sentence-transformers", "tokenizers"))
      requiredText(packages, name);
    return Optional.of(new EncodingIdentity(fingerprint, model, revision, dimensions));
  }

  Optional<EncodingIdentity> response(
      JsonNode certificate,
      String model,
      int dimensions,
      List<String> inputs,
      List<List<Float>> vectors) {
    Optional<EncodingIdentity> identity = profile(certificate, model, dimensions);
    if (identity.isEmpty()) return identity;
    JsonNode proofs = certificate.path("inputs");
    if (!proofs.isArray() || proofs.size() != inputs.size() || vectors.size() != inputs.size())
      throw mismatch("input certificate count differs");
    for (int index = 0; index < inputs.size(); index++) {
      JsonNode proof = proofs.get(index);
      if (!proof.path("index").isIntegralNumber() || proof.path("index").intValue() != index)
        throw mismatch("input certificate index differs");
      if (!hashText(proof, "input_sha256")
          .equals(sha256(inputs.get(index).getBytes(StandardCharsets.UTF_8))))
        throw mismatch("complete input SHA-256 differs");
      List<Float> vector = vectors.get(index);
      if (vector.size() != dimensions
          || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value)))
        throw mismatch("invalid vector dimensions or finite values");
      double norm = Math.sqrt(vector.stream().mapToDouble(value -> (double) value * value).sum());
      if (Math.abs(norm - 1d) > .005d) throw mismatch("normalized vector norm differs");
      ByteBuffer bytes =
          ByteBuffer.allocate(dimensions * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
      vector.forEach(bytes::putFloat);
      if (!hashText(proof, "vector_sha256").equals(sha256(bytes.array())))
        throw mismatch("returned float32 vector SHA-256 differs");
    }
    return identity;
  }

  private static Object canonical(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> sorted = new TreeMap<>();
      node.fields()
          .forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      List<Object> result = new ArrayList<>();
      node.forEach(value -> result.add(canonical(value)));
      return result;
    }
    if (node.isTextual()) return node.textValue();
    if (node.isBoolean()) return node.booleanValue();
    if (node.isIntegralNumber()) return node.bigIntegerValue();
    if (node.isNull()) return null;
    throw mismatch("unsupported profile value");
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank()) throw mismatch(field + " required");
    return value.textValue();
  }

  private static String hashText(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!value.matches("[0-9a-f]{64}")) throw mismatch(field + " is not SHA-256");
    return value;
  }

  private static int requiredPositiveInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
      throw mismatch(field + " must be a positive integer");
    return value.intValue();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static IllegalStateException mismatch(String message) {
    return mismatch(message, null);
  }

  private static IllegalStateException mismatch(String message, Throwable cause) {
    return new IllegalStateException("ENCODING_PROFILE_MISMATCH: " + message, cause);
  }
}
