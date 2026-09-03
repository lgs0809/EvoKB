package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.Batch;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileStatus;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class HttpViews {
  private HttpViews() {}

  static BatchView batch(Batch batch, boolean includeFiles) {
    return new BatchView(
        batch.batchId(),
        blank(batch.createdBy()),
        batch.createdByUsername(),
        batch.status().name(),
        batch.sourceCount(),
        batch.files().size(),
        count(batch, FileStatus.STAGED),
        count(batch, FileStatus.QUEUED),
        count(batch, FileStatus.RUNNING),
        count(batch, FileStatus.SUCCEEDED),
        count(batch, FileStatus.FAILED),
        batch.createdAt(),
        batch.updatedAt(),
        batch.startedAt(),
        batch.finishedAt(),
        includeFiles ? batch.files().stream().map(HttpViews::file).toList() : null);
  }

  static FileView file(FileItem file) {
    var metadata = file.metadata();
    var hint = file.duplicateHint();
    return new FileView(
        file.fileId(),
        file.batchId(),
        file.relativePath(),
        file.originalFilename(),
        file.sizeBytes(),
        file.sha256(),
        metadata.logicalName(),
        blank(metadata.sourceDepartment()),
        blank(metadata.materialCategory()),
        blank(metadata.company()),
        blank(metadata.documentType()),
        metadata.documentDate(),
        metadata.manualTags(),
        file.status().name(),
        file.progressStage().name(),
        file.attempt(),
        blank(file.documentId()),
        blank(file.versionId()),
        hint == null ? null : hint.expectedBehavior().name(),
        hint == null ? null : blank(hint.existingDocumentId()),
        hint == null ? 0 : hint.existingVersionNo(),
        hint == null ? null : blank(hint.duplicateDocumentId()),
        hint == null ? null : blank(hint.duplicateVersionId()),
        hint == null ? null : blank(hint.duplicateLogicalName()),
        hint == null ? null : blank(hint.duplicateVersionLifecycle()),
        blank(file.errorCode()),
        blank(file.errorMessage()),
        file.createdAt(),
        file.updatedAt(),
        file.startedAt(),
        file.finishedAt());
  }

  static EvidenceView evidence(Evidence evidence) {
    return new EvidenceView(
        evidence.id(),
        evidence.documentId(),
        evidence.versionId(),
        evidence.objectKey(),
        sourceFilename(evidence.objectKey()),
        evidence.location(),
        originalEvidenceContent(evidence),
        evidence.hash());
  }

  private static String originalEvidenceContent(Evidence evidence) {
    String content = evidence.content() == null ? "" : evidence.content().trim();
    Object rawHeadings = evidence.location().get("heading_path");
    if (!(rawHeadings instanceof java.util.List<?> headings) || headings.isEmpty()) return content;
    String remaining = content;
    for (Object value : headings) {
      String heading = value == null ? "" : value.toString().trim();
      if (heading.isBlank()) continue;
      if (remaining.equals(heading)) return "";
      String prefix = heading + "\n";
      if (remaining.startsWith(prefix))
        remaining = remaining.substring(prefix.length()).stripLeading();
    }
    return remaining;
  }

  private static String sourceFilename(String objectKey) {
    if (objectKey == null || objectKey.isBlank()) return "";
    String normalized = objectKey.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    return slash >= 0 ? normalized.substring(slash + 1) : normalized;
  }

  private static int count(Batch batch, FileStatus status) {
    return (int) batch.files().stream().filter(f -> f.status() == status).count();
  }

  private static String blank(String v) {
    return v == null || v.isBlank() ? null : v;
  }

  record EvidenceView(
      String evidenceId,
      String documentId,
      String versionId,
      String objectKey,
      String sourceFilename,
      java.util.Map<String, Object> rawLocation,
      String rawContent,
      String contentHash) {}

  record BatchView(
      String batchId,
      String createdBy,
      String createdByUsername,
      String status,
      int sourceCount,
      int fileCount,
      int stagedCount,
      int queuedCount,
      int runningCount,
      int succeededCount,
      int failedCount,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant finishedAt,
      List<FileView> files) {}

  record FileView(
      String fileId,
      String batchId,
      String relativePath,
      String originalFilename,
      long sizeBytes,
      String sha256,
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      List<String> manualTags,
      String status,
      String progressStage,
      int attempt,
      String documentId,
      String versionId,
      String expectedBehavior,
      String existingDocumentId,
      long existingVersionNo,
      String duplicateDocumentId,
      String duplicateVersionId,
      String duplicateLogicalName,
      String duplicateVersionLifecycle,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant finishedAt) {}
}
