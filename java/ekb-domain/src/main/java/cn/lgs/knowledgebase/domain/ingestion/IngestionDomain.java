package cn.lgs.knowledgebase.domain.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class IngestionDomain {
  private IngestionDomain() {}

  public enum FileStatus {
    STAGED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  public enum BatchStatus {
    STAGED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED
  }

  public enum ProgressStage {
    STAGED,
    QUEUED,
    STARTING,
    STORING,
    PARSING,
    EVIDENCE,
    EMBEDDING,
    INDEXING,
    ACTIVATING,
    SUCCEEDED,
    FAILED
  }

  public enum ExpectedBehavior {
    CREATE_DOCUMENT,
    CREATE_VERSION,
    REUSE_VERSION,
    DUPLICATE_CONTENT
  }

  public record FileMetadata(
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      List<String> manualTags,
      Integer securityLevel) {
    public FileMetadata(
        String logicalName,
        String sourceDepartment,
        String materialCategory,
        String company,
        String documentType,
        LocalDate documentDate,
        List<String> manualTags) {
      this(
          logicalName,
          sourceDepartment,
          materialCategory,
          company,
          documentType,
          documentDate,
          manualTags,
          null);
    }

    public FileMetadata {
      logicalName = requireText(logicalName, "logicalName");
      sourceDepartment = trim(sourceDepartment);
      materialCategory = trim(materialCategory);
      company = trim(company);
      documentType = trim(documentType);
      manualTags =
          manualTags == null
              ? List.of()
              : manualTags.stream()
                  .filter(Objects::nonNull)
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .distinct()
                  .toList();
    }

    public FileMetadata patch(
        String logicalName,
        String sourceDepartment,
        String materialCategory,
        String company,
        String documentType,
        LocalDate documentDate,
        boolean clearDocumentDate,
        List<String> manualTags,
        boolean replaceTags) {
      return patch(
          logicalName,
          sourceDepartment,
          materialCategory,
          company,
          documentType,
          documentDate,
          clearDocumentDate,
          manualTags,
          replaceTags,
          null);
    }

    public FileMetadata patch(
        String logicalName,
        String sourceDepartment,
        String materialCategory,
        String company,
        String documentType,
        LocalDate documentDate,
        boolean clearDocumentDate,
        List<String> manualTags,
        boolean replaceTags,
        Integer securityLevel) {
      return patch(
          logicalName,
          sourceDepartment,
          materialCategory,
          company,
          documentType,
          documentDate,
          clearDocumentDate,
          manualTags,
          replaceTags,
          securityLevel,
          false);
    }

    public FileMetadata patch(
        String logicalName,
        String sourceDepartment,
        String materialCategory,
        String company,
        String documentType,
        LocalDate documentDate,
        boolean clearDocumentDate,
        List<String> manualTags,
        boolean replaceTags,
        Integer securityLevel,
        boolean clearSecurityLevel) {
      return new FileMetadata(
          logicalName == null ? this.logicalName : requireText(logicalName, "logicalName"),
          sourceDepartment == null ? this.sourceDepartment : sourceDepartment,
          materialCategory == null ? this.materialCategory : materialCategory,
          company == null ? this.company : company,
          documentType == null ? this.documentType : documentType,
          clearDocumentDate ? null : (documentDate == null ? this.documentDate : documentDate),
          replaceTags ? manualTags : this.manualTags,
          clearSecurityLevel ? null : securityLevel == null ? this.securityLevel : securityLevel);
    }
  }

  public record DuplicateHint(
      String existingDocumentId,
      long existingVersionNo,
      String duplicateDocumentId,
      String duplicateVersionId,
      String duplicateLogicalName,
      String duplicateVersionLifecycle,
      ExpectedBehavior expectedBehavior) {
    public DuplicateHint {
      existingDocumentId = trim(existingDocumentId);
      duplicateDocumentId = trim(duplicateDocumentId);
      duplicateVersionId = trim(duplicateVersionId);
      duplicateLogicalName = trim(duplicateLogicalName);
      duplicateVersionLifecycle = trim(duplicateVersionLifecycle);
      if (existingVersionNo < 0)
        throw new IllegalArgumentException("existingVersionNo cannot be negative");
    }
  }

  public record FileItem(
      String fileId,
      String batchId,
      String relativePath,
      String originalFilename,
      String stagingPath,
      long sizeBytes,
      String sha256,
      FileMetadata metadata,
      FileStatus status,
      ProgressStage progressStage,
      int attempt,
      String documentId,
      String versionId,
      DuplicateHint duplicateHint,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant finishedAt) {
    public FileItem {
      fileId = requireText(fileId, "fileId");
      batchId = requireText(batchId, "batchId");
      relativePath = trim(relativePath);
      originalFilename = requireText(originalFilename, "originalFilename");
      stagingPath = requireText(stagingPath, "stagingPath");
      sha256 = requireText(sha256, "sha256");
      if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
      Objects.requireNonNull(metadata, "metadata");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(progressStage, "progressStage");
      Objects.requireNonNull(createdAt, "createdAt");
      updatedAt = updatedAt == null ? createdAt : updatedAt;
      if (attempt < 0) throw new IllegalArgumentException("attempt cannot be negative");
      documentId = trim(documentId);
      versionId = trim(versionId);
      errorCode = trim(errorCode);
      errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean metadataEditable() {
      return status == FileStatus.STAGED;
    }

    public boolean retryable() {
      return status == FileStatus.FAILED;
    }

    public FileItem withMetadata(FileMetadata next, Instant now) {
      if (!metadataEditable())
        throw new IllegalStateException("file metadata can only be edited while STAGED");
      return copy(
          next,
          status,
          progressStage,
          attempt,
          documentId,
          versionId,
          duplicateHint,
          errorCode,
          errorMessage,
          startedAt,
          finishedAt,
          now);
    }

    public FileItem queue(Instant now) {
      if (status != FileStatus.STAGED && status != FileStatus.FAILED)
        throw new IllegalStateException("only STAGED or FAILED files can be queued");
      return copy(
          metadata,
          FileStatus.QUEUED,
          ProgressStage.QUEUED,
          attempt,
          documentId,
          versionId,
          duplicateHint,
          "",
          "",
          null,
          null,
          now);
    }

    public FileItem claim(Instant now) {
      if (status != FileStatus.QUEUED)
        throw new IllegalStateException("only QUEUED files can be claimed");
      return copy(
          metadata,
          FileStatus.RUNNING,
          ProgressStage.STARTING,
          attempt + 1,
          documentId,
          versionId,
          duplicateHint,
          "",
          "",
          now,
          null,
          now);
    }

    public FileItem progress(ProgressStage stage, Instant now) {
      if (status != FileStatus.RUNNING)
        throw new IllegalStateException("progress can only update RUNNING files");
      return copy(
          metadata,
          status,
          Objects.requireNonNull(stage),
          attempt,
          documentId,
          versionId,
          duplicateHint,
          errorCode,
          errorMessage,
          startedAt,
          finishedAt,
          now);
    }

    public FileItem complete(String documentId, String versionId, Instant now) {
      if (status != FileStatus.RUNNING)
        throw new IllegalStateException("only RUNNING files can complete");
      return copy(
          metadata,
          FileStatus.SUCCEEDED,
          ProgressStage.SUCCEEDED,
          attempt,
          requireText(documentId, "documentId"),
          requireText(versionId, "versionId"),
          duplicateHint,
          "",
          "",
          startedAt,
          now,
          now);
    }

    public FileItem fail(String code, String message, Instant now) {
      return copy(
          metadata,
          FileStatus.FAILED,
          ProgressStage.FAILED,
          attempt,
          documentId,
          versionId,
          duplicateHint,
          trim(code),
          message == null ? "" : message,
          startedAt,
          now,
          now);
    }

    public FileItem retry(Instant now) {
      if (!retryable())
        throw new IllegalStateException("only FAILED ingestion files can be retried");
      return queue(now);
    }

    public FileItem recoverInterrupted(Instant now) {
      if (status != FileStatus.RUNNING) return this;
      return copy(
          metadata,
          FileStatus.QUEUED,
          ProgressStage.QUEUED,
          attempt,
          documentId,
          versionId,
          duplicateHint,
          "",
          "",
          null,
          null,
          now);
    }

    public FileItem withDuplicateHint(DuplicateHint hint) {
      return copy(
          metadata,
          status,
          progressStage,
          attempt,
          documentId,
          versionId,
          hint,
          errorCode,
          errorMessage,
          startedAt,
          finishedAt,
          updatedAt);
    }

    private FileItem copy(
        FileMetadata metadata,
        FileStatus status,
        ProgressStage stage,
        int attempt,
        String documentId,
        String versionId,
        DuplicateHint hint,
        String code,
        String message,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {
      return new FileItem(
          fileId,
          batchId,
          relativePath,
          originalFilename,
          stagingPath,
          sizeBytes,
          sha256,
          metadata,
          status,
          stage,
          attempt,
          documentId,
          versionId,
          hint,
          code,
          message,
          createdAt,
          updatedAt,
          startedAt,
          finishedAt);
    }
  }

  public record Batch(
      String batchId,
      String createdBy,
      String createdByUsername,
      int sourceCount,
      List<FileItem> files,
      Instant createdAt,
      Instant updatedAt,
      Instant startedAt,
      Instant finishedAt) {
    public Batch {
      batchId = requireText(batchId, "batchId");
      createdBy = trim(createdBy);
      createdByUsername = requireText(createdByUsername, "createdByUsername");
      if (sourceCount < 0) throw new IllegalArgumentException("sourceCount cannot be negative");
      files = List.copyOf(files == null ? List.of() : files);
      if (files.isEmpty())
        throw new IllegalArgumentException("ingestion batch requires at least one supported file");
      Objects.requireNonNull(createdAt, "createdAt");
      updatedAt = updatedAt == null ? createdAt : updatedAt;
      for (FileItem file : files)
        if (!batchId.equals(file.batchId()))
          throw new IllegalArgumentException("file belongs to another batch");
    }

    public BatchStatus status() {
      long staged = count(FileStatus.STAGED),
          queued = count(FileStatus.QUEUED),
          running = count(FileStatus.RUNNING);
      long succeeded = count(FileStatus.SUCCEEDED), failed = count(FileStatus.FAILED);
      if (running > 0) return BatchStatus.RUNNING;
      if (queued > 0) return BatchStatus.QUEUED;
      if (staged > 0 && succeeded == 0 && failed == 0) return BatchStatus.STAGED;
      if (failed > 0 && succeeded > 0) return BatchStatus.PARTIAL;
      if (failed > 0) return BatchStatus.FAILED;
      return BatchStatus.SUCCEEDED;
    }

    public Batch recoverInterrupted(Instant now) {
      List<FileItem> recovered = new ArrayList<>(files.size());
      files.forEach(f -> recovered.add(f.recoverInterrupted(now)));
      return new Batch(
          batchId,
          createdBy,
          createdByUsername,
          sourceCount,
          recovered,
          createdAt,
          now,
          status() == BatchStatus.RUNNING ? null : startedAt,
          null);
    }

    private long count(FileStatus status) {
      return files.stream().filter(f -> f.status() == status).count();
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
