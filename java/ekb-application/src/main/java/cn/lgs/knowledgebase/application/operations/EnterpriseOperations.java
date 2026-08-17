package cn.lgs.knowledgebase.application.operations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EnterpriseOperations {
  private EnterpriseOperations() {}

  public record PageQuery(
      int page,
      int pageSize,
      String query,
      String lifecycle,
      String status,
      Boolean active,
      String company,
      String sourceDepartment,
      String documentType,
      String materialCategory,
      String documentId,
      String wikiType,
      String wikiId,
      String canonicalSubjectId,
      String actor,
      String action,
      String outcome) {
    public int normalizedPage() {
      return page <= 0 ? 1 : page;
    }

    public int normalizedPageSize() {
      return pageSize <= 0 ? 30 : Math.min(pageSize, 100);
    }

    public int offset() {
      return (normalizedPage() - 1) * normalizedPageSize();
    }
  }

  public record Page<T>(List<T> items, long total, int page, int pageSize) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record DocumentVersionView(
      String documentId,
      String logicalName,
      String versionId,
      long versionNo,
      String lifecycle,
      String originalFilename,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      Instant parsedAt,
      int summaryCount,
      int groundedSummaries,
      int coveredSummaries,
      String recommendation,
      String recommendationReason,
      int effectiveHits) {}

  public record ClaimView(
      String claimId,
      String section,
      String claimText,
      String groundingStatus,
      String temporalStatus,
      List<String> factIds,
      List<String> evidenceIds) {
    public ClaimView {
      factIds = factIds == null ? List.of() : List.copyOf(factIds);
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record WikiView(
      String wikiId,
      long wikiVersion,
      String wikiType,
      String canonicalSubjectId,
      String title,
      String lifecycle,
      List<ClaimView> claims) {
    public WikiView {
      claims = claims == null ? List.of() : List.copyOf(claims);
    }
  }

  public record OverviewView(
      int documents,
      int versions,
      int activeVersions,
      int pendingVersions,
      int failedVersions,
      int rawBlocks,
      int activeEvidence,
      int activeSummaries,
      int staleSummaries,
      int groundedSummaries,
      int coveredSummaries,
      int activeWikis,
      int staleWikis,
      int activeRelations,
      int activeGraphSupports,
      int effectiveHits,
      int recommendedSources,
      int pendingTasks,
      int runningTasks,
      int failedTasks) {}

  public record TaskView(
      String taskId,
      String source,
      String batchId,
      String fileId,
      String versionId,
      String logicalName,
      String taskType,
      String status,
      String progressStage,
      int attempt,
      String errorCode,
      String errorMessage,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt) {}

  public record EvidenceListView(
      String evidenceId,
      String documentId,
      String logicalName,
      String versionId,
      long versionNo,
      String originalFilename,
      String blockId,
      String blockType,
      Map<String, Object> rawLocation,
      String excerpt,
      boolean active,
      Instant createdAt) {
    public EvidenceListView {
      rawLocation = rawLocation == null ? Map.of() : Map.copyOf(rawLocation);
    }
  }

  public record EvidenceDetail(
      String evidenceId,
      String documentId,
      String versionId,
      String blockId,
      String objectKey,
      Map<String, Object> rawLocation,
      String rawContent,
      String contentHash,
      boolean active) {
    public EvidenceDetail {
      rawLocation = rawLocation == null ? Map.of() : Map.copyOf(rawLocation);
    }
  }

  public record Facets(
      List<String> companies,
      List<String> sourceDepartments,
      List<String> documentTypes,
      List<String> materialCategories) {
    public Facets {
      companies = safe(companies);
      sourceDepartments = safe(sourceDepartments);
      documentTypes = safe(documentTypes);
      materialCategories = safe(materialCategories);
    }

    private static List<String> safe(List<String> values) {
      return values == null ? List.of() : List.copyOf(values);
    }
  }

  public record AuditEventView(
      String eventId,
      String actorUserId,
      String actorUsername,
      String actorRole,
      String action,
      String targetType,
      String targetId,
      String targetName,
      String outcome,
      String requestId,
      String ipAddress,
      String userAgent,
      Map<String, Object> details,
      Instant createdAt) {
    public AuditEventView {
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  public record GraphRelationView(
      String relationId,
      String sourceEntityId,
      String sourceName,
      String targetEntityId,
      String targetName,
      String relationType,
      List<String> evidenceIds,
      double score) {
    public GraphRelationView {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public interface Repository {
    OverviewView overview();

    Page<DocumentVersionView> documents(PageQuery query);

    Page<WikiView> wikis(PageQuery query);

    Optional<WikiView> wiki(String wikiId, Long version);

    Optional<EvidenceDetail> evidence(String evidenceId);

    Page<EvidenceListView> evidences(PageQuery query);

    Page<TaskView> tasks(PageQuery query, String actorId, boolean admin, String source);

    Facets facets();

    Page<AuditEventView> audit(PageQuery query);

    List<GraphRelationView> graph(String query, int depth, int limit);

    List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds);
  }

  public record EvidenceSourceRef(
      String evidenceId,
      String documentId,
      String versionId,
      String originalFilename,
      String objectKey,
      Map<String, Object> location) {
    public EvidenceSourceRef {
      location = location == null ? Map.of() : Map.copyOf(location);
    }
  }
}
