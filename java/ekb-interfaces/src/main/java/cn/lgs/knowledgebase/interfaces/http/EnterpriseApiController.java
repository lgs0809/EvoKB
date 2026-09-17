package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.KnowledgeGenerationRecommendationService;
import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.compilation.KnowledgeGenerationApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService.MetadataPatch;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.ProfileUpdateCommand;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.UpdateCommand;
import cn.lgs.knowledgebase.application.operations.DocumentMetadataApplicationService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.DocumentMetadataUpdate;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.PageQuery;
import cn.lgs.knowledgebase.application.port.Ports.SourcePackageService;
import cn.lgs.knowledgebase.application.query.AsyncKnowledgeQueryService;
import cn.lgs.knowledgebase.application.query.KnowledgeQueryOrchestrator;
import cn.lgs.knowledgebase.application.query.QueryHistoryApplicationService;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/enterprise")
public final class EnterpriseApiController {
  private final ManagedIngestionApplicationService ingestion;
  private final SafeBatchUploadStager stager;
  private final RawIngestionApplicationService raw;
  private final EnterpriseOperations.Repository operations;
  private final DocumentMetadataApplicationService documentMetadata;
  private final KnowledgeGenerationRecommendationService recommendations;
  private final KnowledgeGenerationApplicationService generation;
  private final ModelConfigurationApplicationService modelConfiguration;
  private final RetrievalApplicationService retrieval;
  private final AskApplicationService ask;
  private final AsyncKnowledgeQueryService queries;
  private final QueryHistoryApplicationService queryHistory;
  private final SourcePackageService packages;
  private final long maxSingleUploadBytes;

  public EnterpriseApiController(
      ManagedIngestionApplicationService ingestion,
      SafeBatchUploadStager stager,
      RawIngestionApplicationService raw,
      EnterpriseOperations.Repository operations,
      DocumentMetadataApplicationService documentMetadata,
      KnowledgeGenerationRecommendationService recommendations,
      KnowledgeGenerationApplicationService generation,
      ModelConfigurationApplicationService modelConfiguration,
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      AsyncKnowledgeQueryService queries,
      QueryHistoryApplicationService queryHistory,
      SourcePackageService packages,
      @Value("${ekb.upload.max-single-bytes:536870912}") long maxSingleUploadBytes) {
    this.ingestion = ingestion;
    this.stager = stager;
    this.raw = raw;
    this.operations = operations;
    this.documentMetadata = documentMetadata;
    this.recommendations = recommendations;
    this.generation = generation;
    this.modelConfiguration = modelConfiguration;
    this.retrieval = retrieval;
    this.ask = ask;
    this.queries = queries;
    this.queryHistory = queryHistory;
    this.packages = packages;
    this.maxSingleUploadBytes = maxSingleUploadBytes;
  }

  @GetMapping("/access")
  public ManagedIngestionApplicationService.Access access(HttpServletRequest request) {
    return ingestion.access(EnterpriseRequestContext.user(request));
  }

  @PatchMapping("/access")
  public ManagedIngestionApplicationService.Access updateAccess(
      @RequestBody AccessRequest body, HttpServletRequest request) {
    return ingestion.setUserUploadEnabled(
        EnterpriseRequestContext.admin(request), body.userUploadEnabled());
  }

  @GetMapping("/model-configuration")
  public ModelConfigurationApplicationService.View modelConfiguration(HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.current();
  }

  @PutMapping("/model-configuration")
  public ModelConfigurationApplicationService.View updateModelConfiguration(
      @RequestBody ModelConfigurationRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.update(
        new UpdateCommand(
            body.chatBaseUrl(),
            body.chatApiKey(),
            body.clearChatApiKey(),
            body.smallModel(),
            body.largeModel(),
            body.chatTimeoutSeconds(),
            body.embeddingBaseUrl(),
            body.embeddingApiKey(),
            body.clearEmbeddingApiKey(),
            body.embeddingModel(),
            body.embeddingDimensions(),
            body.embeddingBatchSize(),
            body.embeddingTimeoutSeconds(),
            body.rerankBaseUrl(),
            body.rerankApiKey(),
            body.clearRerankApiKey(),
            body.rerankModel(),
            body.rerankTimeoutSeconds()));
  }

  @GetMapping("/model-configurations")
  public ModelConfigurationApplicationService.ProfilesView modelConfigurations(
      HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.profiles();
  }

  @PostMapping("/model-configurations")
  public ModelConfigurationApplicationService.ProfileView createModelConfiguration(
      @RequestBody ModelProfileCreateRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.create(body.name());
  }

  @PutMapping("/model-configurations/{profileId}")
  public ModelConfigurationApplicationService.ProfileView updateModelConfigurationProfile(
      @PathVariable String profileId,
      @RequestBody ModelProfileRequest body,
      HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.updateProfile(profileId, body.toCommand());
  }

  @PostMapping("/model-configurations/{profileId}/copy")
  public ModelConfigurationApplicationService.ProfileView copyModelConfigurationProfile(
      @PathVariable String profileId, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.copy(profileId);
  }

  @PostMapping("/model-configurations/{profileId}/activate")
  public ModelConfigurationApplicationService.ProfileView activateModelConfigurationProfile(
      @PathVariable String profileId, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return modelConfiguration.activate(profileId);
  }

  @DeleteMapping("/model-configurations/{profileId}")
  public Map<String, Object> deleteModelConfigurationProfile(
      @PathVariable String profileId, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    modelConfiguration.delete(profileId);
    return Map.of("deleted", true, "profile_id", profileId);
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "logical_name", required = false) String logicalName,
      @RequestParam(value = "source_department", required = false) String sourceDepartment,
      @RequestParam(value = "material_category", required = false) String materialCategory,
      @RequestParam(value = "company", required = false) String company,
      @RequestParam(value = "document_type", required = false) String documentType,
      @RequestParam(value = "document_date", required = false) String documentDate,
      @RequestParam(value = "tags", required = false) String tags,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    if (!ingestion.access(actor).canUpload())
      throw new ManagedIngestionApplicationService.Forbidden(
          "document upload is disabled for USER accounts");
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("file is required");
    if (file.getSize() > maxSingleUploadBytes)
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
          .body(Map.of("error", "file exceeds upload limit"));
    var staged = stager.stage(List.of(file), List.of(file.getOriginalFilename()), null);
    try {
      var draft = staged.drafts().get(0);
      Metadata metadata =
          new Metadata(
              blank(sourceDepartment),
              blank(materialCategory),
              splitTags(tags),
              blank(company),
              parseDate(documentDate),
              blank(documentType));
      var result =
          raw.ingest(
              new RawIngestionApplicationService.IngestCommand(
                  draft.stagingPath(),
                  blank(logicalName),
                  "web-upload:" + draft.originalFilename(),
                  metadata),
              null);
      return ResponseEntity.status(HttpStatus.CREATED).body(result);
    } finally {
      stager.cleanup(staged.batchDirectory());
    }
  }

  @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> createBatch(
      @RequestPart("files") List<MultipartFile> files,
      @RequestParam(value = "relative_paths", required = false) List<String> relativePaths,
      @RequestParam(value = "source_department", required = false) String sourceDepartment,
      @RequestParam(value = "material_category", required = false) String materialCategory,
      @RequestParam(value = "company", required = false) String company,
      @RequestParam(value = "document_type", required = false) String documentType,
      @RequestParam(value = "document_date", required = false) String documentDate,
      @RequestParam(value = "tags", required = false) String tags,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    if (!ingestion.access(actor).canUpload())
      throw new ManagedIngestionApplicationService.Forbidden(
          "document upload is disabled for USER accounts");
    var defaults =
        new SafeBatchUploadStager.Defaults(
            blank(sourceDepartment),
            blank(materialCategory),
            blank(company),
            blank(documentType),
            parseDate(documentDate),
            splitTags(tags));
    var staged = stager.stage(files, relativePaths, defaults);
    try {
      var batch = ingestion.createBatch(actor, staged.sourceCount(), staged.drafts());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(Map.of("batch", HttpViews.batch(batch, true), "rejected", staged.rejected()));
    } catch (RuntimeException e) {
      stager.cleanup(staged.batchDirectory());
      throw e;
    }
  }

  @GetMapping("/batches")
  public Map<String, Object> batches(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(required = false) String status,
      @RequestParam(name = "q", required = false) String query,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    var items =
        ingestion.listBatches(actor, page, pageSize, status, query).stream()
            .map(b -> HttpViews.batch(b, false))
            .toList();
    long total = ingestion.countBatches(actor, status, query);
    return Map.of(
        "batches",
        items,
        "total",
        total,
        "page",
        Math.max(1, page),
        "page_size",
        pageSize <= 0 ? 30 : Math.min(pageSize, 100));
  }

  @GetMapping("/batch")
  public HttpViews.BatchView batch(@RequestParam("id") String id, HttpServletRequest request) {
    return HttpViews.batch(ingestion.getBatch(EnterpriseRequestContext.user(request), id), true);
  }

  @PatchMapping("/batch/file")
  public Map<String, Object> patchFile(
      @RequestParam("id") String id, @RequestBody BatchFilePatch body, HttpServletRequest request) {
    var patch =
        new MetadataPatch(
            body.logicalName(),
            body.sourceDepartment(),
            body.materialCategory(),
            body.company(),
            body.documentType(),
            body.documentDate(),
            body.clearDocumentDate(),
            body.manualTags(),
            true);
    return Map.of(
        "file",
        HttpViews.file(ingestion.patchMetadata(EnterpriseRequestContext.user(request), id, patch)));
  }

  @PostMapping("/batch/start")
  public Map<String, Object> startBatch(@RequestBody BatchId body, HttpServletRequest request) {
    return Map.of(
        "batch",
        HttpViews.batch(
            ingestion.queueBatch(EnterpriseRequestContext.user(request), body.batchId()), true));
  }

  @PostMapping("/batch/file/retry")
  public Map<String, Object> retryFile(@RequestBody FileId body, HttpServletRequest request) {
    ingestion.retryFile(EnterpriseRequestContext.user(request), body.fileId());
    return Map.of("status", "queued");
  }

  @GetMapping("/overview")
  public EnterpriseOperations.OverviewView overview(HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return operations.overview();
  }

  @GetMapping("/quality")
  public EnterpriseOperations.QualityView quality(
      @RequestParam(defaultValue = "120") int limit, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    return operations.quality(limit);
  }

  @GetMapping("/facets")
  public EnterpriseOperations.Facets facets(HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return operations.facets();
  }

  @GetMapping("/documents")
  public Map<String, Object> documents(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(required = false) String lifecycle,
      @RequestParam(required = false) String company,
      @RequestParam(name = "source_department", required = false) String department,
      @RequestParam(name = "document_type", required = false) String documentType,
      @RequestParam(name = "material_category", required = false) String category,
      @RequestParam(name = "document_id", required = false) String documentId,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var result =
        operations.documents(
            query(
                page,
                pageSize,
                q,
                lifecycle,
                null,
                null,
                company,
                department,
                documentType,
                category,
                documentId,
                null,
                null,
                null,
                null,
                null,
                null));
    return Map.of(
        "documents",
        result.items(),
        "total",
        result.total(),
        "page",
        result.page(),
        "page_size",
        result.pageSize());
  }

  @GetMapping("/document/detail")
  public EnterpriseOperations.DocumentDetailView documentDetail(
      @RequestParam(name = "version_id") String versionId,
      @RequestParam(name = "block_limit", defaultValue = "120") int blockLimit,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return operations
        .documentDetail(versionId, blockLimit)
        .orElseThrow(() -> new IllegalStateException("document version not found"));
  }

  @PatchMapping("/document")
  public EnterpriseOperations.DocumentVersionView updateDocumentMetadata(
      @RequestParam(name = "version_id") String versionId,
      @RequestBody DocumentMetadataRequest body,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return documentMetadata.update(
        actor,
        new DocumentMetadataUpdate(
            versionId,
            body.logicalName(),
            body.sourceDepartment(),
            body.materialCategory(),
            body.documentType(),
            parseDate(body.documentDate()),
            body.manualTags()));
  }

  @PostMapping("/generation/start")
  public Map<String, Object> startGeneration(
      @RequestBody GenerationStartRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    var task = generation.queueVersion(body.versionId());
    return Map.of("task", task, "status", task.status().name());
  }

  @PostMapping("/generation/rebuild-embedding")
  public Map<String, Object> rebuildEmbedding(
      @RequestBody GenerationStartRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    var task = generation.queueEmbeddingRebuild(body.versionId());
    return Map.of("task", task, "status", task.status().name());
  }

  @PostMapping("/generation/task/retry")
  public Map<String, Object> retryGenerationTask(
      @RequestBody GenerationTaskRetryRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    generation.retry(body.taskId());
    return Map.of("task_id", body.taskId(), "status", "PENDING");
  }

  @PostMapping({"/generation/recommend", "/distill"})
  public Map<String, Object> recommendGeneration(
      @RequestBody GenerationRecommendationRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    String reason = blank(body.reason());
    if (reason == null
        || reason.equalsIgnoreCase("user")
        || reason.equalsIgnoreCase("manual")
        || reason.equalsIgnoreCase("user-marked"))
      recommendations.recommendManual(body.versionId());
    else if (reason.equalsIgnoreCase("existing") || reason.equalsIgnoreCase("existing-question"))
      recommendations.recommendExistingQuestion(body.versionId());
    else throw new IllegalArgumentException("unsupported generation recommendation reason");
    return Map.of("version_id", body.versionId(), "status", "RECOMMENDED");
  }

  @GetMapping("/wikis")
  public Map<String, Object> wikis(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(required = false) String lifecycle,
      @RequestParam(name = "wiki_type", required = false) String wikiType,
      @RequestParam(name = "wiki_id", required = false) String wikiId,
      @RequestParam(name = "canonical_subject_id", required = false) String subject,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var result =
        operations.wikis(
            query(
                page, pageSize, q, lifecycle, null, null, null, null, null, null, null, wikiType,
                wikiId, subject, null, null, null));
    return Map.of(
        "wikis",
        result.items(),
        "total",
        result.total(),
        "page",
        result.page(),
        "page_size",
        result.pageSize());
  }

  @GetMapping("/wiki")
  public EnterpriseOperations.WikiView wiki(
      @RequestParam("id") String id,
      @RequestParam(required = false) Long version,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return operations
        .wiki(id, version)
        .orElseThrow(() -> new IllegalStateException("wiki not found"));
  }

  @GetMapping("/evidence")
  public EnterpriseOperations.EvidenceDetail evidence(
      @RequestParam("id") String id, HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return operations
        .evidence(id)
        .orElseThrow(() -> new IllegalStateException("evidence not found"));
  }

  @GetMapping("/evidences")
  public Map<String, Object> evidences(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(required = false) Boolean active,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var result =
        operations.evidences(
            query(
                page, pageSize, q, null, null, active, null, null, null, null, null, null, null,
                null, null, null, null));
    return Map.of(
        "evidences",
        result.items(),
        "total",
        result.total(),
        "page",
        result.page(),
        "page_size",
        result.pageSize());
  }

  @GetMapping("/tasks")
  public Map<String, Object> tasks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    var result =
        operations.tasks(
            query(
                page, pageSize, q, null, status, null, null, null, null, null, null, null, null,
                null, null, null, null),
            actor.id(),
            actor.administrator(),
            source);
    return Map.of(
        "tasks",
        result.items(),
        "total",
        result.total(),
        "page",
        result.page(),
        "page_size",
        result.pageSize());
  }

  @GetMapping("/audit")
  public Map<String, Object> audit(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String outcome,
      HttpServletRequest request) {
    EnterpriseRequestContext.admin(request);
    var result =
        operations.audit(
            query(
                page, pageSize, q, null, null, null, null, null, null, null, null, null, null, null,
                actor, action, outcome));
    return Map.of(
        "events",
        result.items(),
        "total",
        result.total(),
        "page",
        result.page(),
        "page_size",
        result.pageSize());
  }

  @GetMapping("/graph")
  public Map<String, Object> graph(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(defaultValue = "2") int depth,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(name = "relation_type", required = false) String relationType,
      @RequestParam(name = "valid_at", required = false) String validAt,
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var relations = operations.graph(q, depth, limit, blank(relationType), parseInstant(validAt));
    return Map.of("relations", relations, "total", relations.size());
  }

  @PostMapping("/search")
  public Map<String, Object> search(@RequestBody SearchRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    SearchFilters filters = filters(body.filters());
    var response = retrieval.search(body.query(), filters, body.limit());
    recommendations.recordDeliveredSearch(body.query(), filters, response);
    var results =
        response.results().stream()
            .map(
                r ->
                    Map.of(
                        "result_type",
                        r.candidate().lane().name(),
                        "knowledge",
                        r.candidate().knowledge(),
                        "score",
                        r.candidate().score(),
                        "evidence",
                        r.evidence().stream().map(HttpViews::evidence).toList()))
            .toList();
    return Map.of("search_id", response.searchId(), "results", results);
  }

  @PostMapping("/ask")
  public AskView ask(@RequestBody AskRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var result = ask.ask(body.question(), filters(body.filters()), body.limit());
    return new AskView(
        result.searchId(),
        result.answer(),
        result.citations().stream()
            .map(c -> new CitationView(c.index(), c.resultType(), HttpViews.evidence(c.evidence())))
            .toList(),
        result.evidenceOnly());
  }

  @PostMapping("/query")
  public QueryView query(@RequestBody AskRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(
        queries.start(actor.id(), body.question(), filters(body.filters()), body.limit()));
  }

  @PostMapping("/query/continue")
  public QueryView continueQuery(
      @RequestBody QueryContinueRequest body, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(
        queries.continueAfterClarification(
            actor.id(), body.sessionId(), body.clarification(), body.limit()));
  }

  @GetMapping("/query/session")
  public QueryView querySession(@RequestParam("id") String sessionId, HttpServletRequest request) {
    User actor = EnterpriseRequestContext.user(request);
    return queryView(queries.get(actor.id(), sessionId));
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
            .map(EnterpriseApiController::historyRecordView)
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
            .map(EnterpriseApiController::historyGroupView)
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

  @GetMapping("/evidence/source")
  public Object source(@RequestParam("id") String id, HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return packages.source(id);
  }

  @GetMapping("/evidence/source/file")
  public void sourceFile(
      @RequestParam("id") String id, HttpServletRequest request, HttpServletResponse response) {
    EnterpriseRequestContext.user(request);
    var stored = packages.openOriginal(id);
    response.setContentType(
        stored.contentType() == null
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : stored.contentType());
    response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(stored.size()));
    try (InputStream in = stored.stream()) {
      in.transferTo(response.getOutputStream());
    } catch (Exception e) {
      throw new IllegalStateException("source streaming failed", e);
    }
  }

  @PostMapping("/package")
  public Object packageSources(@RequestBody PackageRequest body, HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return packages.packageSources(body.evidenceIds());
  }

  @GetMapping("/capabilities")
  public Map<String, Boolean> capabilities(HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    return Map.ofEntries(
        Map.entry("auth", true),
        Map.entry("users", true),
        Map.entry("documents", true),
        Map.entry("upload", true),
        Map.entry("batch_ingestion", true),
        Map.entry("tasks", true),
        Map.entry("audit", true),
        Map.entry("evidence", true),
        Map.entry("source_viewer", true),
        Map.entry("ask", true),
        Map.entry("ask_answering", true),
        Map.entry("adaptive_query", true),
        Map.entry("query_feedback", true),
        Map.entry("query_history", true),
        Map.entry("query_groups", true),
        Map.entry("search", true),
        Map.entry("package_sources", true),
        Map.entry("generation_recommendation", true),
        Map.entry("generation_async", true),
        Map.entry("knowledge_quality", true),
        Map.entry("promotion", true),
        Map.entry("artifact_evaluation", true),
        Map.entry("graph", true),
        Map.entry("web", true));
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
        record.latestPackage() == null ? null : historyPackageView(record.latestPackage()));
  }

  private static QueryHistoryGroupView historyGroupView(
      QueryHistoryApplicationService.GroupView view) {
    return new QueryHistoryGroupView(
        view.group().groupId(),
        view.group().name(),
        view.records().stream().map(EnterpriseApiController::historyRecordView).toList(),
        view.group().createdAt(),
        view.group().updatedAt(),
        view.latestPackage() == null ? null : historyPackageView(view.latestPackage()));
  }

  private static QueryPackageView historyPackageView(
      QueryHistoryApplicationService.PackageView view) {
    return new QueryPackageView(
        view.packageId(), view.status(), view.downloadUrl(), view.expiresAt(), view.createdAt());
  }

  private static QueryView queryView(KnowledgeQueryOrchestrator.Response response) {
    var session = response.session();
    var citations =
        response.citations().stream()
            .map(c -> new CitationView(c.index(), c.resultType(), HttpViews.evidence(c.evidence())))
            .toList();
    return new QueryView(
        session.sessionId(),
        session.status().name(),
        session.finalAnswer(),
        session.evidenceOnly(),
        session.clarificationQuestion(),
        session.choices(),
        citations);
  }

  private static PageQuery query(
      int page,
      int pageSize,
      String q,
      String lifecycle,
      String status,
      Boolean active,
      String company,
      String dept,
      String docType,
      String category,
      String docId,
      String wikiType,
      String wikiId,
      String subject,
      String actor,
      String action,
      String outcome) {
    return new PageQuery(
        page, pageSize, q, lifecycle, status, active, company, dept, docType, category, docId,
        wikiType, wikiId, subject, actor, action, outcome);
  }

  private static SearchFilters filters(SearchFilterRequest f) {
    return f == null
        ? new SearchFilters(null, null, null, List.of())
        : new SearchFilters(
            blank(f.sourceDepartment()),
            blank(f.materialCategory()),
            blank(f.documentType()),
            f.versionIds() == null ? List.of() : f.versionIds());
  }

  private static String blank(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static LocalDate parseDate(String v) {
    return blank(v) == null ? null : LocalDate.parse(v.trim());
  }

  private static Instant parseInstant(String v) {
    String value = blank(v);
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
  }

  private static List<String> splitTags(String v) {
    if (blank(v) == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String item : v.split(",")) {
      String t = blank(item);
      if (t != null && !out.contains(t)) out.add(t);
    }
    return List.copyOf(out);
  }

  public record AccessRequest(boolean userUploadEnabled) {}

  public record ModelConfigurationRequest(
      String chatBaseUrl,
      String chatApiKey,
      Boolean clearChatApiKey,
      String smallModel,
      String largeModel,
      Integer chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      Boolean clearEmbeddingApiKey,
      String embeddingModel,
      Integer embeddingDimensions,
      Integer embeddingBatchSize,
      Integer embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      Boolean clearRerankApiKey,
      String rerankModel,
      Integer rerankTimeoutSeconds) {}

  public record ModelProfileCreateRequest(String name) {}

  public record ModelProfileRequest(
      String name,
      String chatBaseUrl,
      String chatApiKey,
      String smallModel,
      String largeModel,
      Integer chatTimeoutSeconds,
      String embeddingBaseUrl,
      String embeddingApiKey,
      String embeddingModel,
      Integer embeddingDimensions,
      Integer embeddingBatchSize,
      Integer embeddingTimeoutSeconds,
      String rerankBaseUrl,
      String rerankApiKey,
      String rerankModel,
      Integer rerankTimeoutSeconds) {
    ProfileUpdateCommand toCommand() {
      return new ProfileUpdateCommand(
          name,
          chatBaseUrl,
          chatApiKey,
          smallModel,
          largeModel,
          chatTimeoutSeconds,
          embeddingBaseUrl,
          embeddingApiKey,
          embeddingModel,
          embeddingDimensions,
          embeddingBatchSize,
          embeddingTimeoutSeconds,
          rerankBaseUrl,
          rerankApiKey,
          rerankModel,
          rerankTimeoutSeconds);
    }
  }

  public record BatchFilePatch(
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      boolean clearDocumentDate,
      List<String> manualTags) {}

  public record BatchId(String batchId) {}

  public record FileId(String fileId) {}

  public record DocumentMetadataRequest(
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String documentType,
      String documentDate,
      List<String> manualTags) {}

  public record GenerationStartRequest(String versionId) {}

  public record GenerationTaskRetryRequest(String taskId) {}

  public record GenerationRecommendationRequest(String versionId, String reason) {}

  public record SearchFilterRequest(
      String sourceDepartment,
      String materialCategory,
      String documentType,
      List<String> versionIds) {}

  public record SearchRequest(String query, SearchFilterRequest filters, int limit) {}

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
      QueryPackageView latestPackage) {}

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

  public record PackageRequest(List<String> evidenceIds) {}

  public record CitationView(int index, String resultType, HttpViews.EvidenceView evidence) {}

  public record AskView(
      String searchId, String answer, List<CitationView> citations, boolean evidenceOnly) {}

  public record QueryView(
      String sessionId,
      String status,
      String answer,
      boolean evidenceOnly,
      String clarificationQuestion,
      List<String> choices,
      List<CitationView> citations) {}
}
