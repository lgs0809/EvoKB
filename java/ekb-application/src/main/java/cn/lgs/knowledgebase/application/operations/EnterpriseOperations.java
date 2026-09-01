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
      List<String> manualTags,
      Instant parsedAt,
      int summaryCount,
      int groundedSummaries,
      int coveredSummaries,
      String recommendation,
      String recommendationReason,
      int effectiveHits,
      Integer securityLevel) {
    public DocumentVersionView {
      manualTags = manualTags == null ? List.of() : List.copyOf(manualTags);
    }

    public DocumentVersionView(
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
        List<String> manualTags,
        Instant parsedAt,
        int summaryCount,
        int groundedSummaries,
        int coveredSummaries,
        String recommendation,
        String recommendationReason,
        int effectiveHits) {
      this(
          documentId,
          logicalName,
          versionId,
          versionNo,
          lifecycle,
          originalFilename,
          sourceDepartment,
          materialCategory,
          company,
          documentType,
          documentDate,
          manualTags,
          parsedAt,
          summaryCount,
          groundedSummaries,
          coveredSummaries,
          recommendation,
          recommendationReason,
          effectiveHits,
          null);
    }
  }

  public record DocumentMetadataUpdate(
      String versionId,
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String documentType,
      LocalDate documentDate,
      List<String> manualTags,
      Integer securityLevel) {
    public DocumentMetadataUpdate(
        String versionId,
        String logicalName,
        String sourceDepartment,
        String materialCategory,
        String documentType,
        LocalDate documentDate,
        List<String> manualTags) {
      this(
          versionId,
          logicalName,
          sourceDepartment,
          materialCategory,
          documentType,
          documentDate,
          manualTags,
          null);
    }

    public DocumentMetadataUpdate {
      manualTags = manualTags == null ? List.of() : List.copyOf(manualTags);
    }
  }

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

  public record EntryQuery(int page, int pageSize, String query, String stage) {
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

  public record EntryFactView(String factId, String factText, List<String> evidenceIds) {
    public EntryFactView {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record EntrySourceView(
      String documentId,
      String versionId,
      long versionNo,
      String logicalName,
      String summaryId,
      String conceptId,
      List<EntryFactView> facts) {
    public EntrySourceView {
      facts = facts == null ? List.of() : List.copyOf(facts);
    }
  }

  public record KnowledgeEntryView(
      String entryId,
      long revision,
      String entryType,
      String label,
      List<String> aliases,
      String description,
      String stage,
      String lifecycle,
      Map<String, Object> scope,
      Instant updatedAt,
      int sourceCount,
      List<EntrySourceView> sources) {
    public KnowledgeEntryView {
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      scope = scope == null ? Map.of() : Map.copyOf(scope);
      sources = sources == null ? List.of() : List.copyOf(sources);
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
      Instant createdAt,
      Instant nextAttemptAt) {}

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
      boolean active,
      String sourceFilename) {
    public EvidenceDetail {
      rawLocation = rawLocation == null ? Map.of() : Map.copyOf(rawLocation);
    }
  }

  public record RawBlockPreview(
      String blockId,
      int blockNo,
      String blockType,
      List<String> headingPath,
      Integer pageStart,
      Integer pageEnd,
      String evidenceId,
      String content,
      String contentHash) {
    public RawBlockPreview {
      headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }
  }

  public record DocumentKnowledgeStatus(
      int rawBlocks,
      int evidence,
      int summaries,
      int activeSummaries,
      int groundedSummaries,
      int coveredSummaries,
      int sourceEntities,
      int appliedEntityMappings,
      int activeRelations,
      int relationEvidenceSupports,
      int wikiClaims,
      int wikiEvidence,
      int pendingTasks,
      int runningTasks,
      int failedTasks) {}

  public record DocumentDetailView(
      String documentId,
      String versionId,
      DocumentKnowledgeStatus knowledge,
      List<RawBlockPreview> blocks) {
    public DocumentDetailView {
      blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
  }

  public record QualityIssueView(
      String issueType,
      String severity,
      String title,
      String detail,
      String versionId,
      String artifactId,
      List<String> evidenceIds,
      Instant createdAt) {
    public QualityIssueView {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record QualityView(
      int summaryGroundingFailures,
      int summaryCoverageFailures,
      int wikiGroundingFailures,
      int wikiTemporalConflicts,
      int relationConflicts,
      int unresolvedEntities,
      int failedGenerationTasks,
      List<QualityIssueView> issues) {
    public QualityView {
      issues = issues == null ? List.of() : List.copyOf(issues);
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
      String sourceType,
      String targetEntityId,
      String targetName,
      String targetType,
      String relationType,
      Instant validFrom,
      Instant validTo,
      String conflictStatus,
      List<String> evidenceIds,
      double score) {
    public GraphRelationView {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public interface Repository {
    OverviewView overview();

    default OverviewView overview(int clearance) {
      return overview();
    }

    Page<DocumentVersionView> documents(PageQuery query);

    default Page<DocumentVersionView> documents(PageQuery query, int clearance) {
      return documents(query);
    }

    Optional<DocumentVersionView> updateDocumentMetadata(DocumentMetadataUpdate update);

    Optional<DocumentDetailView> documentDetail(String versionId, int blockLimit);

    default Optional<DocumentDetailView> documentDetail(
        String versionId, int blockLimit, int clearance) {
      return documentDetail(versionId, blockLimit);
    }

    Page<WikiView> wikis(PageQuery query);

    default Page<WikiView> wikis(PageQuery query, int clearance) {
      return wikis(query);
    }

    Optional<WikiView> wiki(String wikiId, Long version);

    default Optional<WikiView> wiki(String wikiId, Long version, int clearance) {
      return wiki(wikiId, version);
    }

    default Page<KnowledgeEntryView> knowledgeEntries(EntryQuery query, int clearance) {
      return new Page<>(List.of(), 0, query.normalizedPage(), query.normalizedPageSize());
    }

    default Optional<KnowledgeEntryView> knowledgeEntry(String entryId, int clearance) {
      return Optional.empty();
    }

    Optional<EvidenceDetail> evidence(String evidenceId);

    default Optional<EvidenceDetail> evidence(String evidenceId, int clearance) {
      return evidence(evidenceId);
    }

    Page<EvidenceListView> evidences(PageQuery query);

    default Page<EvidenceListView> evidences(PageQuery query, int clearance) {
      return evidences(query);
    }

    Page<TaskView> tasks(PageQuery query, String actorId, boolean admin, String source);

    default Page<TaskView> tasks(
        PageQuery query, String actorId, boolean admin, String source, int clearance) {
      return tasks(query, actorId, admin, source);
    }

    QualityView quality(int limit);

    default QualityView quality(int limit, int clearance) {
      return quality(limit);
    }

    Facets facets();

    default Facets facets(int clearance) {
      return facets();
    }

    Page<AuditEventView> audit(PageQuery query);

    default Page<AuditEventView> audit(PageQuery query, String actorId, int clearance) {
      return audit(query);
    }

    List<GraphRelationView> graph(
        String query, int depth, int limit, String relationType, Instant validAt);

    default List<GraphRelationView> graph(
        String query, int depth, int limit, String relationType, Instant validAt, int clearance) {
      return graph(query, depth, limit, relationType, validAt);
    }

    List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds);

    default List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds, int clearance) {
      return evidenceSources(evidenceIds);
    }
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
