package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Shared currentness proof for resume, completed parser task and normal active-source reuse. */
public final class ParserCheckpointPolicy {
  private ParserCheckpointPolicy() {}

  /** Stale completed external output is retried only within the existing durable Raw attempts. */
  public static final class ResultStale extends IllegalStateException {
    public ResultStale(Throwable cause) {
      super(
          "PARSER_RESULT_STALE: completed external output cannot prove the pinned source/pipeline",
          cause);
    }
  }

  public static ParserPipelineIdentity current(
      ParserPort parser,
      String filename,
      RawChunkProfile profile,
      RawTokenCounter tokens,
      ParsedArtifactEncoder artifacts) {
    return parser
        .currentPipeline(filename)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "PARSER_IDENTITY_UNAVAILABLE: configured parser cannot prove its actual current pipeline"))
        .with("raw.router.sha256", ParserPipelineIdentity.classSha256(parser.getClass()))
        .with("raw.builder.sha256", ParserPipelineIdentity.classSha256(RawKnowledgeBuilder.class))
        .with("raw.evidence.sha256", ParserPipelineIdentity.classSha256(RawEvidencePolicy.class))
        .with(
            "raw.checkpoint.sha256",
            ParserPipelineIdentity.classSha256(ParserCheckpointPolicy.class))
        .with("raw.chunk.profile", profile.identity())
        .with("raw.tokenizer.identity", tokens.identity())
        .with(
            "raw.artifact.encoder.sha256",
            ParserPipelineIdentity.classSha256(artifacts.getClass()));
  }

  public static void requireCurrent(
      ParserPort parser,
      String filename,
      RawChunkProfile profile,
      RawTokenCounter tokens,
      ParsedArtifactEncoder artifacts,
      ParserPipelineIdentity pinned) {
    if (!pinned.equals(current(parser, filename, profile, tokens, artifacts)))
      throw new IllegalStateException(
          "PARSER_IDENTITY_CHANGED: current parser/preprocessor differs from this execution");
  }

  public static void requireParsed(
      ParsedDocument parsed, SourceFile original, ParserPipelineIdentity pinned) {
    if (parsed.blocks().isEmpty())
      throw new IllegalStateException(
          "PARSER_CHECKPOINT_UNVERIFIED: complete parsed blocks required");
    var base = new java.util.TreeMap<>(pinned.components());
    base.keySet().removeIf(key -> key.startsWith("raw."));
    for (var block : parsed.blocks()) {
      Object receipt = block.raw() == null ? null : block.raw().get("_evokb_parser_receipt");
      if (!(receipt instanceof Map<?, ?> raw)
          || !(raw.get("pipeline") instanceof Map<?, ?> pipeline)
          || !(pipeline.get("components") instanceof Map<?, ?> components))
        throw new IllegalStateException(
            "PARSER_CHECKPOINT_UNVERIFIED: every parsed block must bind its source/pipeline");
      var values = new java.util.TreeMap<String, String>();
      components.forEach(
          (key, value) -> {
            if (!(key instanceof String) || !(value instanceof String))
              throw new IllegalStateException(
                  "PARSER_PROFILE_INVALID: declared components must be text");
            values.put((String) key, (String) value);
          });
      var actual = new ParserPipelineIdentity(values);
      if (!actual.components().equals(base)
          || !actual.sha256().equals(pipeline.get("sha256"))
          || !original.sha256().equals(raw.get("sourceSha256")))
        throw new IllegalStateException(
            "PARSER_IDENTITY_CHANGED: parsed output does not bind this original/current pipeline");
    }
  }

  /** External object reads only; callers invoke this before acquiring a source reservation lock. */
  public static boolean reusable(
      RawParseCheckpoint proof,
      DocumentVersion version,
      SourceFile source,
      ParserPipelineIdentity current,
      List<RawBlock> blocks,
      ObjectStore objects) {
    if (proof == null
        || !version.sha256().equals(source.sha256())
        || version.sizeBytes() != source.size()
        || blocks.stream().anyMatch(block -> !block.versionId().equals(version.id()))
        || !proof.matchesSourceAndBlocks(current, source.sha256(), source.size(), blocks)
        || version.markdownObjectKey() == null
        || version.markdownObjectKey().isBlank()
        || version.structuredObjectKey() == null
        || version.structuredObjectKey().isBlank()) return false;
    return proof.markdownSha256().equals(objectSha(objects, version.markdownObjectKey()))
        && proof.artifactSha256().equals(objectSha(objects, version.structuredObjectKey()));
  }

  private static String objectSha(ObjectStore objects, String key) {
    try (var stream = objects.get(key).stream()) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int read;
      while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | java.security.NoSuchAlgorithmException invalid) {
      throw new IllegalStateException(
          "PARSER_CHECKPOINT_OBJECT_UNAVAILABLE: cannot verify retained parsed artifact", invalid);
    }
  }
}
