package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService;
import cn.lgs.knowledgebase.application.port.Ports.GenerationTaskRepository;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/** Durable, bounded worker for Summary -> Concept -> (Graph | Wiki) knowledge generation. */
public final class DurableCompilationWorker implements SmartLifecycle {
  private final GenerationTaskRepository tasks;
  private final CompilationRepository compilation;
  private final SummaryCompilationService summaries;
  private final ConceptCompilationService concepts;
  private final GraphCompilationService graph;
  private final WikiCompilationService wiki;
  private final WikiKeywordProjectionService wikiProjection;
  private final Duration pollInterval;
  private final int claimLimit;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ExecutorService executor;

  public DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit) {
    this(
        tasks,
        compilation,
        summaries,
        concepts,
        graph,
        wiki,
        wikiProjection,
        pollInterval,
        claimLimit,
        Clock.systemUTC());
  }

  DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      Clock clock) {
    this.tasks = require(tasks, "generation task repository");
    this.compilation = require(compilation, "compilation repository");
    this.summaries = require(summaries, "summary compiler");
    this.concepts = require(concepts, "concept compiler");
    this.graph = require(graph, "graph compiler");
    this.wiki = require(wiki, "wiki compiler");
    this.wikiProjection = require(wikiProjection, "wiki keyword projection");
    this.pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
    this.claimLimit = claimLimit <= 0 ? 1 : Math.min(claimLimit, 8);
    this.clock = require(clock, "clock");
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    tasks.resetInterrupted(clock.instant());
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "ekb-compilation-worker");
              thread.setDaemon(true);
              return thread;
            });
    executor.submit(this::loop);
  }

  @Override
  public synchronized void stop() {
    if (!running.compareAndSet(true, false)) return;
    if (executor != null) executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return 110;
  }

  public int runOneCycle() {
    List<GenerationTask> claimed = tasks.claimPending(claimLimit, clock.instant());
    for (GenerationTask task : claimed) process(task);
    return claimed.size();
  }

  private void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        if (runOneCycle() == 0) Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException ignored) {
        try {
          Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void process(GenerationTask task) {
    try {
      switch (task.taskType()) {
        case SUMMARY -> processSummary(task);
        case CONCEPT -> processConcept(task);
        case GRAPH -> processGraph(task);
        case WIKI -> processWiki(task);
      }
    } catch (RuntimeException failure) {
      tasks.fail(task.taskId(), errorCode(task.taskType()), failure.getMessage(), clock.instant());
    }
  }

  private void processSummary(GenerationTask task) {
    List<SummaryCompilationService.PreparedSummary> prepared =
        summaries.compileVersion(task.versionId());
    StringBuilder fingerprint = new StringBuilder();
    int downstream = 0;
    for (SummaryCompilationService.PreparedSummary item : prepared) {
      SummaryRecord summary = item.summary();
      fingerprint.append(summary.id()).append(':').append(summary.contentHash()).append(';');
      if (summary.activeAndGrounded()) {
        tasks.queue(task.versionId(), GenerationTaskType.CONCEPT, summary.id(), clock.instant());
        downstream++;
      }
    }
    if (prepared.isEmpty())
      throw new IllegalStateException("summary compilation produced no units");
    tasks.complete(
        task,
        KnowledgeDomain.sha256(fingerprint + "active_concept_tasks=" + downstream),
        clock.instant());
  }

  private void processConcept(GenerationTask task) {
    if (task.subjectId().isBlank())
      throw new IllegalStateException("CONCEPT task summary ID is missing");
    ConceptCompilationService.CompileResult result = concepts.compileSummary(task.subjectId());
    String fingerprint =
        result.summaryId()
            + "|concepts="
            + result.concepts().stream()
                .map(
                    concept ->
                        concept.id()
                            + ":"
                            + concept.conceptTypeHint()
                            + ":"
                            + concept.preferredLabel())
                .sorted()
                .toList();
    tasks.completeConceptAndQueueDerived(
        task, KnowledgeDomain.sha256(fingerprint), clock.instant());
  }

  private void processGraph(GenerationTask task) {
    if (task.subjectId().isBlank())
      throw new IllegalStateException("GRAPH task summary ID is missing");
    GraphCompilationService.CompileResult result = graph.compileSummary(task.subjectId());
    String fingerprint =
        result.summaryId()
            + "|entities="
            + result.entities().stream().map(entity -> entity.canonicalId()).sorted().toList()
            + "|relations="
            + result.relations().stream().map(relation -> relation.id()).sorted().toList()
            + "|coverage="
            + result.coverage().pass();
    tasks.complete(task, KnowledgeDomain.sha256(fingerprint), clock.instant());
  }

  private void processWiki(GenerationTask task) {
    VersionSource source = compilation.loadVersionSource(task.versionId());
    String company = text(source.metadata().company());
    List<SummaryRecord> active =
        company.isEmpty()
            ? compilation.loadActiveSummariesForVersion(task.versionId())
            : compilation.loadActiveSummariesForCompany(company);
    if (active.isEmpty()) {
      throw new IllegalStateException(
          "no ACTIVE grounded and covered summaries are available for Wiki");
    }
    String documentType =
        !text(source.manualDocumentType()).isEmpty()
            ? text(source.manualDocumentType())
            : text(source.metadata().documentType());
    String wikiType = wikiType(documentType, company);
    String title = company.isEmpty() ? source.logicalName() : company;
    String canonicalSubjectId =
        company.isEmpty() ? "" : compilation.resolveEntity(company, "COMPANY").canonicalId();
    WikiCompilationService.CompileResult result =
        wiki.compile(
            new WikiCompilationService.CompileRequest(
                wikiType,
                title,
                canonicalSubjectId,
                active.stream().map(SummaryRecord::id).toList()));
    wikiProjection.project(result.wiki().id(), result.wiki().version());
    tasks.complete(task, result.wiki().contentHash(), clock.instant());
  }

  private static String wikiType(String documentType, String company) {
    if (!company.isEmpty()) return "COMPANY";
    String type = text(documentType);
    if (type.contains("合同")) return "CONTRACT";
    if (type.contains("诉讼") || type.contains("仲裁")) return "LEGAL_CASE";
    if (type.contains("项目") || type.contains("投资") || type.contains("并购")) return "PROJECT";
    if (type.contains("控制") || type.contains("实控")) return "CONTROL_TOPIC";
    if (type.contains("股权") || type.contains("股东")) return "EQUITY_TOPIC";
    return "GENERIC_TOPIC";
  }

  private static String errorCode(GenerationTaskType type) {
    return switch (type) {
      case SUMMARY -> "SUMMARY_GENERATION_FAILED";
      case CONCEPT -> "CONCEPT_GENERATION_FAILED";
      case GRAPH -> "GRAPH_GENERATION_FAILED";
      case WIKI -> "WIKI_GENERATION_FAILED";
      case EMBEDDING_REBUILD -> "EMBEDDING_REBUILD_FAILED";
    };
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
