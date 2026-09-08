package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Proof saved in the same short transaction as the complete parsed blocks/evidence. */
public record RawParseCheckpoint(
    ParserPipelineIdentity pipeline,
    String sourceSha256,
    long sourceSizeBytes,
    String markdownSha256,
    String artifactSha256,
    String rawBlocksSha256) {
  public RawParseCheckpoint {
    Objects.requireNonNull(pipeline, "actual parser pipeline required");
    for (String hash : List.of(sourceSha256, markdownSha256, artifactSha256, rawBlocksSha256))
      ParserPipelineIdentity.requireSha(hash);
    if (sourceSizeBytes < 0) throw new IllegalArgumentException("Original size cannot be negative");
  }

  public boolean matchesSourceAndBlocks(
      ParserPipelineIdentity current, String sourceHash, long size, List<RawBlock> blocks) {
    return pipeline.equals(current)
        && sourceSha256.equals(sourceHash)
        && sourceSizeBytes == size
        && !blocks.isEmpty()
        && rawBlocksSha256.equals(blocksSha256(blocks));
  }

  public static String blocksSha256(List<RawBlock> blocks) {
    var canonical = new StringBuilder();
    ParserPipelineIdentity.append(canonical, "raw-parsed-blocks-v1");
    ParserPipelineIdentity.append(canonical, Integer.toString(blocks.size()));
    for (RawBlock block : blocks) {
      for (String value :
          List.of(
              block.id(),
              block.versionId(),
              Integer.toString(block.blockNo()),
              block.blockType(),
              block.content(),
              Objects.toString(block.normalized(), ""),
              Objects.toString(block.pageStart(), ""),
              Objects.toString(block.pageEnd(), ""),
              block.contentHash(),
              Integer.toString(block.tokenCount())))
        ParserPipelineIdentity.append(canonical, value);
      appendList(canonical, block.headingPath());
      appendList(canonical, block.bbox());
      // PG's existing RawBlock schema persists normalized bbox/page index, not pageSize.
      // Full native page dimensions remain covered by the structured artifact bytes hash.
      appendList(canonical, block.sourceBlockIds());
    }
    return ParserPipelineIdentity.hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void appendList(StringBuilder builder, List<?> values) {
    ParserPipelineIdentity.append(builder, Integer.toString(values.size()));
    for (Object value : values) ParserPipelineIdentity.append(builder, Objects.toString(value, ""));
  }
}
