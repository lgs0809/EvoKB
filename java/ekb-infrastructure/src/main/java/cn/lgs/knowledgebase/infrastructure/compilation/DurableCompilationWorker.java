package cn.lgs.knowledgebase.infrastructure.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationCommands;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService;
import cn.lgs.knowledgebase.application.compilation.ConceptTaskBinding;
import cn.lgs.knowledgebase.application.compilation.EntryAssociationService;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.KnowledgeEntryCatalogue;
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
  private final KnowledgeEntryCatalogue entries;
  private final EntryAssociationService association;
  private final CompilationCommands.Store commands;
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
        clock,
        null,
        null);
  }

  public DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      KnowledgeEntryCatalogue entries) {
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
        Clock.systemUTC(),
        require(entries, "entry catalogue"),
        null);
  }

  public DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      KnowledgeEntryCatalogue entries,
      EntryAssociationService association) {
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
        Clock.systemUTC(),
        require(entries, "entry catalogue"),
        require(association, "source association"));
  }

  private DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      Clock clock,
      KnowledgeEntryCatalogue entries,
      EntryAssociationService association) {
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
        clock,
        entries,
        association,
        null);
  }

  public DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      KnowledgeEntryCatalogue entries,
      EntryAssociationService association,
      CompilationCommands.Store commands) {
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
        Clock.systemUTC(),
        require(entries, "entry catalogue"),
        require(association, "source association"),
        require(commands, "command store"));
  }

  private DurableCompilationWorker(
      GenerationTaskRepository tasks,
      CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService wikiProjection,
      Duration pollInterval,
      int claimLimit,
      Clock clock,
      KnowledgeEntryCatalogue entries,
      EntryAssociationService association,
      CompilationCommands.Store commands) {
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
    // One executor can start only one stage at a time; queued stages must not spend their deadline
    // waiting.
    this.claimLimit = 1;
    this.clock = require(clock, "clock");
    this.entries = entries;
    this.association = association;
    this.commands = commands;
  }

  @Override
  public synchronized void start() {
    if (!running.compareAndSet(false, true)) return;
    tasks.resetInterrupted(
        List.of(
            GenerationTaskType.SOURCE_ASSOCIATION,
            GenerationTaskType.SUMMARY,
            GenerationTaskType.CONCEPT,
            GenerationTaskType.GRAPH,
            GenerationTaskType.WIKI),
        clock.instant());
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
      var command =
          commands == null
              ? java.util.Optional.<CompilationCommands.Command>empty()
              : commands.find(task);
      if (command.isPresent()) {
        processCommand(task, command.orElseThrow());
        return;
      }
      if (task.subjectId().startsWith("command/"))
        throw new IllegalStateException("compilation command store is unavailable");
      switch (task.taskType()) {
        case SOURCE_ASSOCIATION -> {
          if (association == null)
            throw new IllegalStateException("background source association is unavailable");
          int count = association.associate(task.versionId(), tasks.publication(task));
          tasks.complete(task, KnowledgeDomain.sha256("related_entries=" + count), clock.instant());
        }
        case SUMMARY -> processSummary(task);
        case CONCEPT -> processConcept(task);
        case GRAPH -> processGraph(task);
        case WIKI -> processWiki(task);
      }
    } catch (RuntimeException failure) {
      if (Thread.currentThread().isInterrupted()) return;
      GenerationTaskFailureHandler.persist(
          tasks, task, errorCode(task.taskType()), failure, clock.instant());
    }
  }

  private void processSummary(GenerationTask task) {
    processSummary(task, tasks.publication(task));
  }

  private void processSummary(GenerationTask task, CompilationPublication publication) {
    List<SummaryCompilationService.PreparedSummary> prepared =
        summaries.compileVersion(task.versionId(), publication);
    StringBuilder fingerprint = new StringBuilder();
    int downstream = 0;
    for (SummaryCompilationService.PreparedSummary item : prepared) {
      SummaryRecord summary = item.summary();
      fingerprint.append(summary.id()).append(':').append(summary.contentHash()).append(';');
      if (summary.activeAndGrounded()) {
        String subject =
            entries == null ? summary.id() : ConceptTaskBinding.from(summary, task).subject();
        if (task.subjectId().startsWith("repair/"))
          tasks.queueRepair(task.versionId(), GenerationTaskType.CONCEPT, subject, clock.instant());
        else tasks.queue(task.versionId(), GenerationTaskType.CONCEPT, subject, clock.instant());
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

  private void processCommand(GenerationTask task, CompilationCommands.Command command) {
    var request = command.request();
    var gate = commands.publication(task, command, tasks.publication(task));
    String hash;
    switch (request.kind()) {
      case SUMMARY -> {
        processSummary(task, gate);
        return;
      }
      case GRAPH -> {
        var result = graph.compileSummary(request.summaryIds().get(0), gate);
        hash =
            KnowledgeDomain.sha256(
                result.summaryId() + "|" + result.entities() + "|" + result.relations());
      }
      case CONCEPT_PROJECTION -> {
        var result = concepts.rebuildProjections(request.summaryIds().get(0), gate);
        hash = KnowledgeDomain.sha256(result.toString());
      }
      case WIKI -> {
        var result =
            wiki.compile(
                new WikiCompilationService.CompileRequest(
                    request.wikiType(),
                    request.title(),
                    request.canonicalSubjectId(),
                    request.summaryIds()),
                gate,
                published -> wikiProjection.project(published.id(), published.version()));
        hash = result.wiki().contentHash();
      }
      case WIKI_KEYWORDS -> {
        // The target graph is PostgreSQL: all projection work is a short native DB commit,
        // with no model or remote index request hidden in this ownership transaction.
        var result =
            new java.util.concurrent.atomic.AtomicReference<
                WikiKeywordProjectionService.ProjectionResult>();
        gate.captureInputs(command.sourceVersions());
        gate.check();
        gate.publish(
            () -> result.set(wikiProjection.project(request.wikiId(), request.wikiVersion())));
        hash = KnowledgeDomain.sha256(result.get().toString());
      }
      default -> throw new IllegalStateException("unsupported compilation command");
    }
    tasks.complete(task, hash, clock.instant());
  }

  private void processConcept(GenerationTask task) {
    if (task.subjectId().isBlank())
      throw new IllegalStateException("CONCEPT task summary ID is missing");
    ConceptCompilationService.CompileResult result =
        concepts.compileSummary(
            ConceptTaskBinding.verifiedSummaryId(task, compilation),
            tasks.publication(task),
            task.errorCode().equals("CONCEPT_GENERATION_FAILED") ? task.errorMessage() : "");
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
    if (entries == null)
      tasks.completeConceptAndQueueDerived(
          task, KnowledgeDomain.sha256(fingerprint), clock.instant());
    else tasks.complete(task, KnowledgeDomain.sha256(fingerprint), clock.instant());
  }

  private void processGraph(GenerationTask task) {
    if (entries != null && task.subjectId().startsWith("entry/")) {
      processEntryGraph(task);
      return;
    }
    if (task.subjectId().isBlank())
      throw new IllegalStateException("GRAPH task summary ID is missing");
    GraphCompilationService.CompileResult result =
        graph.compileSummary(task.subjectId(), tasks.publication(task));
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
    if (entries != null && task.subjectId().startsWith("entry/")) {
      var entry = frozenEntry(task);
      var result =
          wiki.compileEntry(
              entry,
              tasks.publication(task),
              published -> {
                entries.wikiPublished(entry);
                wikiProjection.project(published.id(), published.version());
              });
      tasks.complete(task, result.wiki().contentHash(), clock.instant());
      return;
    }
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
                active.stream().map(SummaryRecord::id).toList()),
            tasks.publication(task),
            published -> wikiProjection.project(published.id(), published.version()));
    tasks.complete(task, result.wiki().contentHash(), clock.instant());
  }

  private KnowledgeEntryCatalogue.Entry frozenEntry(GenerationTask task) {
    String[] key = task.subjectId().split("/", -1);
    if (key.length != 4 || !key[0].equals("entry") || !key[2].equals("revision"))
      throw new IllegalStateException("entry compilation subject is invalid");
    var entry =
        entries.find(key[1]).orElseThrow(() -> new IllegalStateException("entry is missing"));
    if (entry.revision() != Long.parseLong(key[3])
        || !entry.versionIds().contains(task.versionId()))
      throw new IllegalStateException("entry compilation inputs changed");
    return entry;
  }

  private void processEntryGraph(GenerationTask task) {
    var entry = frozenEntry(task);
    var gate = tasks.publication(task);
    gate.captureInputs(entry.versionIds());
    var writes = new java.util.ArrayList<Runnable>();
    var fingerprints = new java.util.ArrayList<String>();
    CompilationPublication preparation =
        new CompilationPublication() {
          public void captureInputs(List<String> versions) {
            if (versions.isEmpty() || !entry.versionIds().containsAll(versions))
              throw new IllegalStateException("graph attempted to add an unfrozen entry source");
          }

          public void publish(Runnable callback) {
            writes.add(callback);
          }

          public String objectKey(String key) {
            return gate.objectKey(key);
          }
        };
    for (String summaryId : entry.summaryIds()) {
      gate.check();
      var result = graph.compileSummary(summaryId, preparation);
      fingerprints.add(
          result.summaryId()
              + ":"
              + result.relations().stream().map(r -> r.id()).sorted().toList());
    }
    gate.publish(
        () -> {
          writes.forEach(Runnable::run);
          entries.graphPublished(entry);
        });
    tasks.complete(task, KnowledgeDomain.sha256(fingerprints.toString()), clock.instant());
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
      case SOURCE_ASSOCIATION -> "SOURCE_ASSOCIATION_FAILED";
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
