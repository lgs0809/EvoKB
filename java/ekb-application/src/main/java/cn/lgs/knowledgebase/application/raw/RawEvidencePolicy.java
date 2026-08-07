package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.util.Set;

/** The same parser-body boundary governs Evidence creation and downstream consumers. */
public final class RawEvidencePolicy {
  private static final Set<String> AUXILIARY = Set.of("header", "footer", "page_number");

  private RawEvidencePolicy() {}

  public static boolean hasSemanticBody(RawBlock block) {
    return !AUXILIARY.contains(block.blockType())
        && !("text".equals(block.blockType())
            && !block.headingPath().isEmpty()
            && normalize(block.content())
                .equals(block.headingPath().get(block.headingPath().size() - 1)));
  }

  private static String normalize(String text) {
    return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
  }
}
