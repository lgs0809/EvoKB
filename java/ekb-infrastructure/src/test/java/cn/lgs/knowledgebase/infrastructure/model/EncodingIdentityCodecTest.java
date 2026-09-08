package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class EncodingIdentityCodecTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String MODEL = "fixture-model";
  static final String REVISION = "a".repeat(40);
  static final List<Float> VECTOR = List.of(1f, 0f, 0f);

  @Test
  void authenticatesCanonicalProfileCompleteUnicodeInputAndFloat32Vector() throws Exception {
    String input = "原文🙂\n条件并非无条件付款";
    var identity =
        new EncodingIdentityCodec(JSON)
            .response(
                certificate(List.of(input), false), MODEL, 3, List.of(input), List.of(VECTOR));
    assertTrue(identity.isPresent());
    assertEquals(REVISION, identity.get().modelRevision());
  }

  @Test
  void unknownMetadataCannotBePresentedAsActualCertification() throws Exception {
    var codec = new EncodingIdentityCodec(JSON);
    assertTrue(codec.profile(null, MODEL, 3).isEmpty());
    var unknown = certificate(List.of("原文"), false);
    unknown.put("schema", "future-unknown");
    assertTrue(codec.response(unknown, MODEL, 3, List.of("原文"), List.of(VECTOR)).isEmpty());
  }

  @Test
  void rejectsInputTruncationVectorMutationDimensionAndProfileHashMismatch() throws Exception {
    var codec = new EncodingIdentityCodec(JSON);
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.response(
                certificate(List.of("完整"), false), MODEL, 3, List.of("截断"), List.of(VECTOR)));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.response(
                certificate(List.of("完整"), false),
                MODEL,
                3,
                List.of("完整"),
                List.of(List.of(0f, 1f, 0f))));
    assertThrows(
        IllegalStateException.class,
        () -> codec.profile(certificate(List.of("完整"), false), MODEL, 1024));
    var bad = certificate(List.of("完整"), false);
    bad.withObject("/profile").put("cpu_threads", 99);
    assertThrows(IllegalStateException.class, () -> codec.profile(bad, MODEL, 3));
  }

  @Test
  void rejectsCountOrderMissingRuntimeFieldsAndNonFiniteOrUnnormalizedVectors() throws Exception {
    var codec = new EncodingIdentityCodec(JSON);
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.response(
                certificate(List.of("A", "B"), false), MODEL, 3, List.of("A"), List.of(VECTOR)));
    var bad = certificate(List.of("A"), false);
    ((ObjectNode) bad.path("inputs").get(0)).put("index", 1);
    assertThrows(
        IllegalStateException.class,
        () -> codec.response(bad, MODEL, 3, List.of("A"), List.of(VECTOR)));
    for (List<Float> vector : List.of(List.of(Float.NaN, 0f, 0f), List.of(.1f, .2f, .3f)))
      assertThrows(
          IllegalStateException.class,
          () ->
              codec.response(
                  certificate(List.of("A"), false), MODEL, 3, List.of("A"), List.of(vector)));
    var incomplete = certificate(List.of("A"), false);
    incomplete.withObject("/profile").remove("packages");
    assertThrows(IllegalStateException.class, () -> codec.profile(incomplete, MODEL, 3));
  }

  @Test
  void retainedActualPythonAtomicHttpResponseMatchesJavaCanonicalAndFloat32Hashes()
      throws Exception {
    com.fasterxml.jackson.databind.JsonNode retained;
    try (var stream = getClass().getResourceAsStream("/model/atomic-encoding-response.json")) {
      assertNotNull(stream);
      retained = JSON.readTree(stream);
    }
    var request = retained.path("request");
    var response = retained.path("response");
    List<String> inputs = new java.util.ArrayList<>();
    request.path("input").forEach(text -> inputs.add(text.asText()));
    List<List<Float>> vectors = new java.util.ArrayList<>();
    response
        .path("data")
        .forEach(
            item -> {
              List<Float> vector = new java.util.ArrayList<>();
              item.path("embedding").forEach(number -> vector.add(number.floatValue()));
              vectors.add(vector);
            });
    var result =
        new EncodingIdentityCodec(JSON)
            .response(
                response.path("encoding_identity"),
                request.path("model").asText(),
                request.path("dimensions").asInt(),
                inputs,
                vectors);
    assertTrue(result.isPresent());
    assertEquals(
        response.path("encoding_identity").path("profile_sha256").asText(),
        result.get().profileSha256());
    assertEquals(1024, result.get().dimensions());
  }

  @Test
  void pipelineOrComputeChangeProducesDifferentAuthenticatedIdentity() throws Exception {
    var codec = new EncodingIdentityCodec(JSON);
    assertNotEquals(
        codec.profile(certificate(List.of("A"), false), MODEL, 3),
        codec.profile(certificate(List.of("A"), true), MODEL, 3));
  }

  static ObjectNode certificate(List<String> texts, boolean changedRuntime) throws Exception {
    Map<String, Object> profile = new TreeMap<>();
    profile.put("model", MODEL);
    profile.put("loaded_revision", REVISION);
    profile.put("dimensions", 3);
    profile.put("input_type", "document");
    profile.put("normalize", true);
    profile.put("truncation", "reject-over-limit");
    profile.put("resize", "prefix-float32-renormalize-if-enabled-v1");
    profile.put("tokenizer_sha256", "1".repeat(64));
    profile.put("preprocessor_sha256", "2".repeat(64));
    profile.put("pipeline_sha256", (changedRuntime ? "4" : "3").repeat(64));
    profile.put("max_tokens", 262144);
    profile.put("device", "cpu");
    profile.put("parameter_dtype", "torch.bfloat16");
    profile.put("cpu_attention", "expanded-kv-fp32");
    profile.put("cpu_threads", 8);
    profile.put("interop_threads", 1);
    profile.put(
        "packages",
        new TreeMap<>(
            Map.of(
                "torch",
                "2.8.0",
                "transformers",
                "5.14.1",
                "sentence-transformers",
                "5.6.1",
                "tokenizers",
                "0.22.2")));
    ObjectNode cert = JSON.createObjectNode();
    cert.put("schema", "qwen-encoding-v1");
    cert.set("profile", JSON.valueToTree(profile));
    cert.put("profile_sha256", sha(JSON.writeValueAsBytes(profile)));
    var proofs = cert.putArray("inputs");
    ByteBuffer bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    VECTOR.forEach(bytes::putFloat);
    for (int i = 0; i < texts.size(); i++)
      proofs
          .addObject()
          .put("index", i)
          .put("input_sha256", sha(texts.get(i).getBytes(StandardCharsets.UTF_8)))
          .put("vector_sha256", sha(bytes.array()));
    return cert;
  }

  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
