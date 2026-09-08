package cn.lgs.knowledgebase.application.raw;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Actual immutable parser/preprocessor declaration; unknown is never a certified identity. */
public record ParserPipelineIdentity(Map<String, String> components) {
  public static final String SCHEMA = "raw-parser-pipeline-v1";

  public ParserPipelineIdentity {
    if (components == null || components.isEmpty())
      throw new IllegalArgumentException(
          "PARSER_IDENTITY_UNAVAILABLE: complete components required");
    var ordered = new TreeMap<String, String>();
    components.forEach(
        (key, value) -> {
          if (key == null || !key.matches("[a-zA-Z0-9._-]+") || value == null || value.isBlank())
            throw new IllegalArgumentException(
                "PARSER_PROFILE_INVALID: identity component is invalid");
          ordered.put(key, value);
        });
    for (String name :
        java.util.List.of("parser.name", "parser.version", "parser.format", "runtime.sha256"))
      if (!ordered.containsKey(name))
        throw new IllegalArgumentException("PARSER_PROFILE_INVALID: missing " + name);
    requireSha(ordered.get("runtime.sha256"));
    components = Collections.unmodifiableSortedMap(ordered);
  }

  public String sha256() {
    StringBuilder canonical = new StringBuilder();
    append(canonical, SCHEMA);
    components.forEach(
        (key, value) -> {
          append(canonical, key);
          append(canonical, value);
        });
    return hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  public ParserPipelineIdentity with(String key, String value) {
    var updated = new TreeMap<>(components);
    if (updated.putIfAbsent(key, value) != null)
      throw new IllegalArgumentException(
          "PARSER_PROFILE_INVALID: identity component already exists");
    return new ParserPipelineIdentity(updated);
  }

  static void append(StringBuilder builder, String value) {
    builder.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
  }

  static void requireSha(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("PARSER_PROFILE_INVALID: actual SHA-256 required");
  }

  public static String classSha256(Class<?> type) {
    var classes = new TreeMap<String, String>();
    collectClassBytes(type, classes);
    var canonical = new StringBuilder();
    classes.forEach(
        (name, digest) -> {
          append(canonical, name);
          append(canonical, digest);
        });
    return hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void collectClassBytes(Class<?> type, Map<String, String> classes) {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (var stream = type.getResourceAsStream(resource)) {
      if (stream == null)
        throw new IllegalStateException(
            "PARSER_IDENTITY_UNAVAILABLE: actual implementation bytes unavailable");
      classes.put(type.getName(), hash(stream.readAllBytes()));
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(
          "PARSER_IDENTITY_UNAVAILABLE: implementation bytes cannot be read", invalid);
    }
    for (Class<?> nested : type.getDeclaredClasses()) collectClassBytes(nested, classes);
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }
}
