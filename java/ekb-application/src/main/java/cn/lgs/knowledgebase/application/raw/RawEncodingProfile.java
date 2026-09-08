package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;

/** Exact serving identity, separate from source/body identity and from authorization. */
public record RawEncodingProfile(
    String model,
    String modelRevision,
    int dimensions,
    String tokenizerIdentity,
    String preprocessingIdentity,
    String instruction,
    String endpointIdentity) {
  public RawEncodingProfile {
    require(model, "embedding model");
    require(modelRevision, "embedding model revision");
    require(tokenizerIdentity, "tokenizer identity");
    require(preprocessingIdentity, "preprocessing identity");
    require(endpointIdentity, "embedding endpoint identity");
    if (instruction == null) throw new IllegalArgumentException("instruction must be explicit");
    if (dimensions <= 0)
      throw new IllegalArgumentException("embedding dimensions must be positive");
  }

  public String fingerprint() {
    return sha256(
        canonical(
            model,
            modelRevision,
            Integer.toString(dimensions),
            tokenizerIdentity,
            preprocessingIdentity,
            instruction,
            endpointIdentity));
  }

  public String key(String processingDomain, String actualInput) {
    require(processingDomain, "processing domain");
    return sha256(canonical(processingDomain, fingerprint(), actualInput));
  }

  public String certifiedFingerprint(EncodingIdentity identity) {
    if (!model.equals(identity.model())
        || !modelRevision.equals(identity.modelRevision())
        || dimensions != identity.dimensions())
      throw new IllegalStateException(
          "ENCODING_PROFILE_MISMATCH: runtime differs from frozen model");
    return sha256(canonical("raw-atomic-encoding-v1", fingerprint(), identity.profileSha256()));
  }

  public String certifiedKey(
      String processingDomain, String actualInput, EncodingIdentity identity) {
    require(processingDomain, "processing domain");
    return sha256(canonical(processingDomain, certifiedFingerprint(identity), actualInput));
  }

  // Length framing makes delimiters/newlines in user content unambiguous.
  private static String canonical(String... fields) {
    StringBuilder value = new StringBuilder();
    for (String field : fields) value.append(field.length()).append(':').append(field);
    return value.toString();
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }
}
