package cn.lgs.knowledgebase.interfaces.http;

import static cn.lgs.knowledgebase.interfaces.http.QueryHttpContracts.*;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.query.AsyncKnowledgeQueryService;
import cn.lgs.knowledgebase.application.query.KnowledgeQueryOrchestrator;
import cn.lgs.knowledgebase.application.query.QueryHistoryApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Owns durable query, clarification, feedback, history and grouping HTTP boundaries. */
@RestController
@RequestMapping("/api/enterprise")
public final class EnterpriseQueryController {
  private final AsyncKnowledgeQueryService queries;
  private final QueryHistoryApplicationService queryHistory;
  private final KnowledgeAccessService access;
  private final EnterpriseEvidenceViewAssembler evidenceAssembler;

  public EnterpriseQueryController(
      AsyncKnowledgeQueryService queries,
      QueryHistoryApplicationService queryHistory,
      KnowledgeAccessService access,
      EnterpriseEvidenceViewAssembler evidenceAssembler) {
    this.queries = queries;
    this.queryHistory = queryHistory;
    this.access = access;
    this.evidenceAssembler = evidenceAssembler;
  }

  @PostMapping("/query")
  public QueryView query(@RequestBody AskRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(
        queries.start(
            actor.id(),
            body.question(),
            access.filters(actor.id(), QueryHttpContracts.filters(body.filters())),
            body.limit()),
        actor.id());
  }

  @PostMapping("/query/continue")
  public QueryView continueQuery(
      @RequestBody QueryContinueRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(
        queries.continueAfterClarification(
            actor.id(), body.sessionId(), body.clarification(), body.limit()),
        actor.id());
  }

  @GetMapping("/query/session")
  public QueryView querySession(@RequestParam("id") String sessionId, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(queries.get(actor.id(), sessionId), actor.id());
  }

  @GetMapping("/query/sessions")
  public Map<String, Object> querySessions(
      @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    var sessions =
        queries.listSessions(actor.id(), limit).stream()
            .map(
                session ->
                    new QuerySessionSummaryView(
                        session.sessionId(),
                        session.originalQuestion(),
                        session.status().name(),
                        session.finalAnswer(),
                        session.createdAt(),
                        session.updatedAt()))
            .toList();
    return Map.of("sessions", sessions, "total", sessions.size());
  }

  @PostMapping("/query/feedback")
  public Map<String, Object> queryFeedback(
      @RequestBody QueryFeedbackRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    queries.feedback(actor.id(), body.sessionId(), body.rating(), body.category(), body.comment());
    return Map.of("saved", true, "session_id", body.sessionId());
  }

  @GetMapping("/query/history")
  public Map<String, Object> queryHistory(
      @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    var records =
        queryHistory.listRecords(actor.id(), limit).stream()
            .map(EnterpriseQueryController::historyRecordView)
            .toList();
    return Map.of("records", records, "total", records.size());
  }

  @PatchMapping("/query/history/record")
  public QueryHistoryRecordView renameQueryRecord(
      @RequestParam("id") String sessionId,
      @RequestBody QueryRenameRequest body,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyRecordView(queryHistory.renameRecord(actor.id(), sessionId, body.name()));
  }

  @PatchMapping("/query/history/record/group")
  public QueryHistoryRecordView moveQueryRecord(
      @RequestParam("id") String sessionId,
      @RequestBody QueryRecordGroupRequest body,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyRecordView(
        body.groupId() == null || body.groupId().isBlank()
            ? queryHistory.removeRecordFromGroup(actor.id(), sessionId)
            : queryHistory.moveRecordToGroup(actor.id(), sessionId, body.groupId()));
  }

  @DeleteMapping("/query/history/record")
  public Map<String, Object> deleteQueryRecord(
      @RequestParam("id") String sessionId, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    queryHistory.deleteRecord(actor.id(), sessionId);
    return Map.of("deleted", true, "session_id", sessionId);
  }

  @PostMapping("/query/history/record/package")
  public QueryPackageView packageQueryRecord(
      @RequestBody QueryRecordPackageRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyPackageView(
        queryHistory.recordPackage(actor.id(), body.sessionId(), body.regenerate()));
  }

  @GetMapping("/query/groups")
  public Map<String, Object> queryGroups(HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    var groups =
        queryHistory.listGroups(actor.id()).stream()
            .map(EnterpriseQueryController::historyGroupView)
            .toList();
    return Map.of("groups", groups, "total", groups.size());
  }

  @PostMapping("/query/groups")
  public QueryHistoryGroupView createQueryGroup(
      @RequestBody QueryGroupCreateRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyGroupView(queryHistory.createGroup(actor.id(), body.name(), body.sessionIds()));
  }

  @PatchMapping("/query/group")
  public QueryHistoryGroupView renameQueryGroup(
      @RequestParam("id") String groupId,
      @RequestBody QueryRenameRequest body,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyGroupView(queryHistory.renameGroup(actor.id(), groupId, body.name()));
  }

  @PostMapping("/query/group/dissolve")
  public Map<String, Object> dissolveQueryGroup(
      @RequestBody QueryGroupIdRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    queryHistory.dissolveGroup(actor.id(), body.groupId());
    return Map.of("dissolved", true, "group_id", body.groupId());
  }

  @DeleteMapping("/query/group")
  public Map<String, Object> deleteQueryGroup(
      @RequestParam("id") String groupId, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    queryHistory.deleteGroup(actor.id(), groupId);
    return Map.of("deleted", true, "group_id", groupId);
  }

  @PostMapping("/query/group/package")
  public QueryPackageView packageQueryGroup(
      @RequestBody QueryGroupPackageRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return historyPackageView(
        queryHistory.groupPackage(actor.id(), body.groupId(), body.regenerate()));
  }

  private static QueryHistoryRecordView historyRecordView(
      QueryHistoryApplicationService.RecordView record) {
    var session = record.session();
    return new QueryHistoryRecordView(
        session.sessionId(),
        session.name(),
        session.originalQuestion(),
        session.status().name(),
        session.finalAnswer(),
        session.evidenceOnly(),
        session.citationEvidenceIds().size(),
        record.group() == null ? null : record.group().groupId(),
        record.group() == null ? null : record.group().name(),
        session.createdAt(),
        session.updatedAt(),
        record.latestPackage() == null ? null : historyPackageView(record.latestPackage()),
        record.evidenceChanged());
  }

  private static QueryHistoryGroupView historyGroupView(
      QueryHistoryApplicationService.GroupView view) {
    return new QueryHistoryGroupView(
        view.group().groupId(),
        view.group().name(),
        view.records().stream().map(EnterpriseQueryController::historyRecordView).toList(),
        view.group().createdAt(),
        view.group().updatedAt(),
        view.latestPackage() == null ? null : historyPackageView(view.latestPackage()));
  }

  private static QueryPackageView historyPackageView(
      QueryHistoryApplicationService.PackageView view) {
    return new QueryPackageView(
        view.packageId(), view.status(), view.downloadUrl(), view.expiresAt(), view.createdAt());
  }

  private QueryView queryView(KnowledgeQueryOrchestrator.Response response, String userId) {
    var session = response.session();
    var evidenceViews = evidenceAssembler.forUser(userId);
    var citations =
        response.citations().stream()
            .map(
                c -> new CitationView(c.index(), c.resultType(), evidenceViews.apply(c.evidence())))
            .toList();
    return new QueryView(
        session.sessionId(),
        session.status().name(),
        response.displayAnswer(),
        session.evidenceOnly(),
        session.clarificationQuestion(),
        session.choices(),
        citations,
        session.originalQuestion(),
        response.evidenceChanged(),
        new SearchFilterRequest(
            session.filters().sourceDepartment(),
            session.filters().materialCategory(),
            session.filters().documentType(),
            session.filters().versionIds()));
  }
}
