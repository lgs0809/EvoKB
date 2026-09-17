package cn.lgs.knowledgebase.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpViewsTest {
  @Test
  void evidenceViewUsesOriginalBodyAndHumanReadableSourceFilename() {
    Evidence evidence =
        new Evidence(
            "evidence-1",
            "document-1",
            "version-1",
            "block-1",
            "original/document-1/version-1/05-2025-investment-decision-committee-rules.pdf",
            Map.of("heading_path", List.of("董事会投资与决策委员会实施细则", "第二章 人员组成"), "page_start", 1),
            "董事会投资与决策委员会实施细则\n第二章 人员组成\n第三条 投资与决策委员会成员由三名董事组成，其中包括一名独立董事。",
            "hash-1");

    HttpViews.EvidenceView view = HttpViews.evidence(evidence);

    assertEquals("第三条 投资与决策委员会成员由三名董事组成，其中包括一名独立董事。", view.rawContent());
    assertEquals("05-2025-investment-decision-committee-rules.pdf", view.sourceFilename());
  }
}
