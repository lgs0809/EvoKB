package cn.lgs.knowledgebase.domain.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class IngestionDomain {
    private IngestionDomain() {}

    public enum FileStatus { STAGED, QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum BatchStatus { STAGED, QUEUED, RUNNING, SUCCEEDED, PARTIAL_FAILED, FAILED }
    public enum ProgressStage { STAGED, UPLOAD_ORIGINAL, PARSE, CHUNK, EMBED, INDEX, ACTIVATE, SUCCEEDED, FAILED }

    public record FileMetadata(
            String logicalName,
            String sourceDepartment,
            String materialCategory,
            String company,
            String documentType,
            LocalDate documentDate,
            List<String> manualTags) {
        public FileMetadata {
            logicalName = requireText(logicalName, "logicalName");
            manualTags = manualTags == null ? List.of() : manualTags.stream()
                    .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        }
    }

    public record FileItem(
            String fileId,
            String batchId,
            String relativePath,
            String originalFilename,
            long sizeBytes,
            String sha256,
            FileMetadata metadata,
            FileStatus status,
            ProgressStage progressStage,
            int attempt,
            String documentId,
            String versionId,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
        public FileItem {
            fileId = requireText(fileId, "fileId");
            batchId = requireText(batchId, "batchId");
            originalFilename = requireText(originalFilename, "originalFilename");
            sha256 = requireText(sha256, "sha256");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(progressStage, "progressStage");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (attempt < 0) throw new IllegalArgumentException("attempt cannot be negative");
        }

        public boolean retryable() { return status == FileStatus.FAILED; }

        public FileItem retry(Instant now) {
            if (!retryable()) throw new IllegalStateException("only failed files can be retried");
            return new FileItem(fileId, batchId, relativePath, originalFilename, sizeBytes, sha256, metadata,
                    FileStatus.QUEUED, ProgressStage.STAGED, attempt + 1, null, null, null, null, createdAt, now);
        }

        public FileItem recoverInterrupted(Instant now) {
            if (status != FileStatus.RUNNING) return this;
            return new FileItem(fileId, batchId, relativePath, originalFilename, sizeBytes, sha256, metadata,
                    FileStatus.QUEUED, ProgressStage.STAGED, attempt, documentId, versionId,
                    "INTERRUPTED", "worker interrupted before completion", createdAt, now);
        }
    }

    public record Batch(String batchId, String createdBy, List<FileItem> files, Instant createdAt, Instant updatedAt) {
        public Batch {
            batchId = requireText(batchId, "batchId");
            createdBy = requireText(createdBy, "createdBy");
            files = List.copyOf(files == null ? List.of() : files);
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            for (FileItem file : files) {
                if (!batchId.equals(file.batchId())) throw new IllegalArgumentException("file belongs to another batch");
            }
        }

        public BatchStatus status() {
            if (files.isEmpty()) return BatchStatus.STAGED;
            long succeeded = files.stream().filter(f -> f.status() == FileStatus.SUCCEEDED).count();
            long failed = files.stream().filter(f -> f.status() == FileStatus.FAILED).count();
            if (succeeded == files.size()) return BatchStatus.SUCCEEDED;
            if (failed == files.size()) return BatchStatus.FAILED;
            if (succeeded > 0 && failed > 0 && succeeded + failed == files.size()) return BatchStatus.PARTIAL_FAILED;
            if (files.stream().anyMatch(f -> f.status() == FileStatus.RUNNING)) return BatchStatus.RUNNING;
            if (files.stream().anyMatch(f -> f.status() == FileStatus.QUEUED)) return BatchStatus.QUEUED;
            return BatchStatus.STAGED;
        }

        public Batch recoverInterrupted(Instant now) {
            List<FileItem> recovered = new ArrayList<>(files.size());
            files.forEach(f -> recovered.add(f.recoverInterrupted(now)));
            return new Batch(batchId, createdBy, recovered, createdAt, now);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
