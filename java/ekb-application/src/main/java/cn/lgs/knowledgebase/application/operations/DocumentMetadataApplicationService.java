package cn.lgs.knowledgebase.application.operations;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.DocumentMetadataUpdate;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.DocumentVersionView;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.Repository;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.RuntimeConfigRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Updates user-managed document metadata without reparsing the original source file. */
public final class DocumentMetadataApplicationService {
  private final Repository repository;
  private final RuntimeConfigRepository config;
  private final RawSearchIndex rawIndex;
  private final DerivedSearchIndex derivedIndex;

  public DocumentMetadataApplicationService(
      Repository repository,
      RuntimeConfigRepository config,
      RawSearchIndex rawIndex,
      DerivedSearchIndex derivedIndex) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.config = Objects.requireNonNull(config, "runtime config");
    this.rawIndex = Objects.requireNonNull(rawIndex, "raw index");
    this.derivedIndex = Objects.requireNonNull(derivedIndex, "derived index");
  }

  public DocumentVersionView update(User actor, DocumentMetadataUpdate requested) {
    requireManageAccess(actor);
    if (requested == null)
      throw new IllegalArgumentException("document metadata update is required");
    DocumentClassificationPolicy.requireAllowed(actor, requested.securityLevel());
    DocumentMetadataUpdate normalized = normalize(requested);
    DocumentVersionView updated =
        repository
            .updateDocumentMetadata(normalized)
            .orElseThrow(
                () ->
                    new ManagedIngestionApplicationService.NotFound("document version not found"));

    Metadata metadata =
        new Metadata(
            updated.sourceDepartment(),
            updated.materialCategory(),
            updated.manualTags(),
            updated.company(),
            updated.documentDate(),
            updated.documentType());
    rawIndex.updateMetadata(updated.versionId(), metadata);
    // Summary artifacts are version-scoped and can be updated safely in place. Wiki artifacts may
    // aggregate multiple source versions; the adapter therefore preserves multi-source semantics.
    derivedIndex.updateMetadata(updated.versionId(), metadata);
    return updated;
  }

  private void requireManageAccess(User actor) {
    if (actor == null || !actor.active())
      throw new ManagedIngestionApplicationService.Forbidden("active user is required");
    if (!config.accessPolicy().canUpload(actor))
      throw new ManagedIngestionApplicationService.Forbidden(
          "document management is disabled for USER accounts");
  }

  private static DocumentMetadataUpdate normalize(DocumentMetadataUpdate value) {
    String versionId = required(value.versionId(), "versionId", 64);
    String logicalName = required(value.logicalName(), "logicalName", 512);
    String department = optional(value.sourceDepartment(), 255);
    String category = optional(value.materialCategory(), 255);
    String documentType = optional(value.documentType(), 128);
    List<String> tags = normalizeTags(value.manualTags());
    return new DocumentMetadataUpdate(
        versionId,
        logicalName,
        department,
        category,
        documentType,
        value.documentDate(),
        tags,
        value.securityLevel());
  }

  private static List<String> normalizeTags(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String raw : values) {
      String value = optional(raw, 128);
      if (!value.isBlank()) unique.add(value);
      if (unique.size() >= 100) break;
    }
    return List.copyOf(new ArrayList<>(unique));
  }

  private static String required(String value, String field, int maxLength) {
    String normalized = optional(value, maxLength);
    if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optional(String value, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > maxLength)
      throw new IllegalArgumentException("metadata value is too long");
    return normalized;
  }
}
