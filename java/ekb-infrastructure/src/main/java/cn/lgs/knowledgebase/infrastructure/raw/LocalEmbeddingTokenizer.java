package cn.lgs.knowledgebase.infrastructure.raw;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Local native HF tokenizer; never downloads weights/tokenizers and never truncates. */
public final class LocalEmbeddingTokenizer implements RawTokenCounter, AutoCloseable {
  private final HuggingFaceTokenizer tokenizer;
  private final String prefix;
  private final String suffix;
  private final String identity;

  public LocalEmbeddingTokenizer(
      Path path, String revision, String expectedSha256, String prefix, String suffix)
      throws IOException {
    if (revision == null
        || revision.isBlank()
        || expectedSha256 == null
        || !expectedSha256.matches("[0-9a-f]{64}")
        || prefix == null
        || suffix == null)
      throw new IllegalArgumentException(
          "Frozen tokenizer file hash, revision and input rendering required");
    if (!Files.isRegularFile(path))
      throw new IOException("Local tokenizer file not found: " + path);
    try {
      String actual =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
      if (!actual.equals(expectedSha256))
        throw new IOException("Local tokenizer hash differs from frozen profile");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    this.prefix = prefix;
    this.suffix = suffix;
    this.identity =
        KnowledgeDomain.sha256(
            "djl-hf-0.32.0:postprocessor-special-tokens:no-truncation:no-padding:"
                + revision
                + ":"
                + expectedSha256
                + ":"
                + prefix.length()
                + ":"
                + prefix
                + ":"
                + suffix.length()
                + ":"
                + suffix);
    this.tokenizer =
        HuggingFaceTokenizer.newInstance(
            path, Map.of("addSpecialTokens", "true", "truncation", "false", "padding", "false"));
  }

  @Override
  public int count(String content) {
    if (content == null) throw new IllegalArgumentException("Token input cannot be null");
    return tokenizer.encode(prefix + content + suffix, true, false).getIds().length;
  }

  @Override
  public String identity() {
    return identity;
  }

  @Override
  public void close() {
    tokenizer.close();
  }
}
