package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.KnowledgeGenerationRecommendationService;
import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService.MetadataPatch;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.PageQuery;
import cn.lgs.knowledgebase.application.port.Ports.SourcePackageService;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.time.LocalDate;
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
  private final KnowledgeGenerationRecommendationService recommendations;
  private final RetrievalApplicationService retrieval;
  private final AskApplicationService ask;
  private final SourcePackageService packages;
  private final long maxSingleUploadBytes;

  public EnterpriseApiController(
      ManagedIngestionApplicationService ingestion,
      SafeBatchUploadStager stager,
      RawIngestionApplicationService raw,
      EnterpriseOperations.Repository operations,
      KnowledgeGenerationRecommendationService recommendations,
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      SourcePackageService packages,
      @Value("${ekb.upload.max-single-bytes:536870912}") long maxSingleUploadBytes) {
    this.ingestion = ingestion;
    this.stager = stager;
    this.raw = raw;
    this.operations = operations;
    this.recommendations = recommendations;
    this.retrieval = retrieval;
    this.ask = ask;
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
      HttpServletRequest request) {
    EnterpriseRequestContext.user(request);
    var relations = operations.graph(q, depth, limit);
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
        Map.entry("search", true),
        Map.entry("package_sources", true),
        Map.entry("generation_recommendation", true),
        Map.entry("promotion", true),
        Map.entry("artifact_evaluation", true),
        Map.entry("graph", true),
        Map.entry("web", true));
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
        ? new SearchFilters(null, null, null, null, List.of())
        : new SearchFilters(
            blank(f.sourceDepartment()),
            blank(f.materialCategory()),
            blank(f.company()),
            blank(f.documentType()),
            f.versionIds() == null ? List.of() : f.versionIds());
  }

  private static String blank(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static LocalDate parseDate(String v) {
    return blank(v) == null ? null : LocalDate.parse(v.trim());
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

  public record GenerationRecommendationRequest(String versionId, String reason) {}

  public record SearchFilterRequest(
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      List<String> versionIds) {}

  public record SearchRequest(String query, SearchFilterRequest filters, int limit) {}

  public record AskRequest(String question, SearchFilterRequest filters, int limit) {}

  public record PackageRequest(List<String> evidenceIds) {}

  public record CitationView(int index, String resultType, HttpViews.EvidenceView evidence) {}

  public record AskView(
      String searchId, String answer, List<CitationView> citations, boolean evidenceOnly) {}
}
