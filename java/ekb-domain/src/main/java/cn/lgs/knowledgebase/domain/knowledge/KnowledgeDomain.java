package cn.lgs.knowledgebase.domain.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KnowledgeDomain {
    private KnowledgeDomain() {}

    public enum VersionLifecycle { PENDING, ACTIVE, INACTIVE, FAILED }

    public record Document(String id, String logicalName, String sourcePath) {
        public Document {
            id = requireText(id, "document id");
            logicalName = requireText(logicalName, "logicalName");
        }
    }

    public record Metadata(
            String sourceDepartment,
            String materialCategory,
            List<String> manualTags,
            String company,
            LocalDate documentDate,
            String documentType) {
        public Metadata {
            manualTags = manualTags == null ? List.of() : List.copyOf(manualTags);
        }
    }

    public record DocumentVersion(
            String id,
            String documentId,
            long versionNo,
            VersionLifecycle lifecycle,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            String originalObjectKey,
            String markdownObjectKey,
            String structuredObjectKey,
            String parserName,
            String parserVersion) {
        public DocumentVersion {
            id = requireText(id, "version id");
            documentId = requireText(documentId, "document id");
            if (versionNo <= 0) throw new IllegalArgumentException("versionNo must be positive");
            Objects.requireNonNull(lifecycle, "lifecycle");
            originalFilename = requireText(originalFilename, "originalFilename");
            sha256 = requireText(sha256, "sha256");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
        }

        public DocumentVersion withLifecycle(VersionLifecycle next) {
            return new DocumentVersion(id, documentId, versionNo, next, originalFilename, mediaType, sizeBytes, sha256,
                    originalObjectKey, markdownObjectKey, structuredObjectKey, parserName, parserVersion);
        }
    }

    public record VersionActivation(DocumentVersion previous, DocumentVersion activated) {
        public VersionActivation {
            Objects.requireNonNull(activated, "activated");
            if (activated.lifecycle() != VersionLifecycle.ACTIVE) throw new IllegalArgumentException("target must be active");
            if (previous != null) {
                if (!previous.documentId().equals(activated.documentId())) throw new IllegalArgumentException("versions must belong to same document");
                if (previous.lifecycle() != VersionLifecycle.INACTIVE) throw new IllegalArgumentException("previous version must be inactive");
            }
        }
    }

    public static VersionActivation activate(DocumentVersion current, DocumentVersion target) {
        Objects.requireNonNull(target, "target");
        if (target.lifecycle() == VersionLifecycle.FAILED) throw new IllegalStateException("failed version cannot be activated");
        if (current != null && !current.documentId().equals(target.documentId())) throw new IllegalArgumentException("versions belong to different documents");
        DocumentVersion previous = current == null ? null : current.withLifecycle(VersionLifecycle.INACTIVE);
        return new VersionActivation(previous, target.withLifecycle(VersionLifecycle.ACTIVE));
    }

    public record RawBlock(
            String id,
            String versionId,
            int blockNo,
            String blockType,
            String content,
            String normalized,
            List<String> headingPath,
            Integer pageStart,
            Integer pageEnd,
            List<Double> bbox,
            List<Double> pageSize,
            List<String> sourceBlockIds,
            String contentHash,
            int tokenCount) {
        public RawBlock {
            id = requireText(id, "block id");
            versionId = requireText(versionId, "version id");
            content = requireText(content, "content");
            headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
            bbox = bbox == null ? List.of() : List.copyOf(bbox);
            pageSize = pageSize == null ? List.of() : List.copyOf(pageSize);
            sourceBlockIds = sourceBlockIds == null ? List.of() : List.copyOf(sourceBlockIds);
        }
    }

    public record Evidence(
            String id,
            String documentId,
            String versionId,
            String blockId,
            String objectKey,
            Map<String, Object> location,
            String content,
            String hash) {
        public Evidence {
            id = requireText(id, "evidence id");
            documentId = requireText(documentId, "document id");
            versionId = requireText(versionId, "version id");
            blockId = requireText(blockId, "block id");
            objectKey = requireText(objectKey, "object key");
            content = requireText(content, "evidence content");
            location = location == null ? Map.of() : Map.copyOf(location);
        }

        public static Evidence fromBlock(String documentId, String objectKey, RawBlock block) {
            String hash = sha256(block.content());
            String id = "ev_" + sha256(documentId + "\u0000" + block.versionId() + "\u0000" + block.id() + "\u0000" + hash).substring(0, 32);
            return new Evidence(id, documentId, block.versionId(), block.id(), objectKey,
                    Map.of("page_start", block.pageStart() == null ? -1 : block.pageStart(),
                           "page_end", block.pageEnd() == null ? -1 : block.pageEnd(),
                           "bbox", block.bbox(), "page_size", block.pageSize()),
                    block.content(), hash);
        }
    }

    public record SearchFilters(String sourceDepartment, String materialCategory, String company,
                                String documentType, List<String> versionIds) {
        public SearchFilters {
            versionIds = versionIds == null ? List.of() : List.copyOf(versionIds);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
