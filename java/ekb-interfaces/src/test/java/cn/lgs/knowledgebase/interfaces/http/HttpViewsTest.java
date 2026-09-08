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

  @Test
  void rawRetryDeadlineSurvivesMetadataDecorationAndHttpView() {
    var now = java.time.Instant.parse("2026-11-02T00:00:00Z");
    var next = now.plusSeconds(300);
    var file =
        new cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem(
            "file",
            "batch",
            "source.md",
            "source.md",
            "synthetic",
            1,
            "hash",
            new cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata(
                "synthetic", "", "", "", "", null, List.of(), 10),
            cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileStatus.QUEUED,
            cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage.QUEUED,
            12,
            "doc",
            "version",
            null,
            "EMBEDDING_FAILED",
            "synthetic dependency",
            now,
            now,
            now,
            null,
            next);
    assertEquals(next, HttpViews.file(file.withDuplicateHint(null)).nextAttemptAt());
    assertEquals(12, HttpViews.file(file).attempt());
  }
}
