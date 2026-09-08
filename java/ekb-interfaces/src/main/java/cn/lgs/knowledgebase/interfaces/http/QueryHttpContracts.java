package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.time.Instant;
import java.util.List;

/** Public query DTOs deliberately exclude trusted authorization fields. */
public final class QueryHttpContracts {
  private QueryHttpContracts() {}

  public record SearchFilterRequest(
      String sourceDepartment,
      String materialCategory,
      String documentType,
      List<String> versionIds) {}

  public record AskRequest(String question, SearchFilterRequest filters, int limit) {}

  public record QueryContinueRequest(String sessionId, String clarification, int limit) {}

  public record QueryFeedbackRequest(
      String sessionId, String rating, String category, String comment) {}

  public record QueryRenameRequest(String name) {}

  public record QueryRecordGroupRequest(String groupId) {}

  public record QueryRecordPackageRequest(String sessionId, boolean regenerate) {}

  public record QueryGroupCreateRequest(String name, List<String> sessionIds) {}

  public record QueryGroupIdRequest(String groupId) {}

  public record QueryGroupPackageRequest(String groupId, boolean regenerate) {}

  public record QueryPackageView(
      String packageId, String status, String downloadUrl, Instant expiresAt, Instant createdAt) {}

  public record QueryHistoryRecordView(
      String sessionId,
      String name,
      String question,
      String status,
      String answer,
      boolean evidenceOnly,
      int citationCount,
      String groupId,
      String groupName,
      Instant createdAt,
      Instant updatedAt,
      QueryPackageView latestPackage,
      boolean evidenceChanged) {}

  public record QueryHistoryGroupView(
      String groupId,
      String name,
      List<QueryHistoryRecordView> records,
      Instant createdAt,
      Instant updatedAt,
      QueryPackageView latestPackage) {}

  public record QuerySessionSummaryView(
      String sessionId,
      String question,
      String status,
      String answer,
      Instant createdAt,
      Instant updatedAt) {}

  public record CitationView(int index, String resultType, HttpViews.EvidenceView evidence) {}

  public record QueryView(
      String sessionId,
      String status,
      String answer,
      boolean evidenceOnly,
      String clarificationQuestion,
      List<String> choices,
      List<CitationView> citations,
      String question,
      boolean evidenceChanged,
      SearchFilterRequest filters) {}

  static SearchFilters filters(SearchFilterRequest f) {
    return f == null
        ? new SearchFilters(null, null, null, List.of())
        : new SearchFilters(
            blank(f.sourceDepartment()),
            blank(f.materialCategory()),
            blank(f.documentType()),
            f.versionIds() == null ? List.of() : f.versionIds());
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
