package cn.lgs.knowledgebase.infrastructure.compilation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.GraphRelationRejection;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService;
import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DurableCompilationWorkerTest {
  private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

  @Test
  void summaryConceptFanOutQueuesGraphAndWikiIndependently() {
    Fixture f = new Fixture(false, false);
    f.tasks.queue("v1", GenerationTaskType.SUMMARY, "", NOW);

    runCycles(f, 4);

    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.SUMMARY).status());
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.CONCEPT).status());
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.GRAPH).status());
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.WIKI).status());
    assertEquals(1, f.tasks.count(GenerationTaskType.GRAPH));
    assertEquals(1, f.tasks.count(GenerationTaskType.WIKI));
    assertEquals(1, f.repository.conceptsBySummary.size());
    assertEquals(1, f.repository.wikis.size());
  }

  @Test
  void companyWikiAggregatesActiveGroundedSummariesAcrossDocuments() {
    Fixture f = new Fixture(false, false);
    SummaryFact legacyFact =
        new SummaryFact(
            "f-old",
            "s-old",
            "历史事实",
            "甲公司的既有跨文档事实。",
            List.of("rb-old"),
            List.of("ev-old"),
            "PASS",
            "direct");
    f.repository.summaries.put(
        "s-old",
        new SummaryRecord(
            "s-old",
            "u-old",
            "doc-old",
            "v-old",
            "summary/v-old/s-old.md",
            "old-hash",
            "PASS",
            "PASS",
            "ACTIVE",
            "large-writer",
            "# legacy",
            List.of(legacyFact),
            NOW.minusSeconds(3600)));
    f.tasks.queue("v1", GenerationTaskType.SUMMARY, "", NOW);

    runCycles(f, 4);

    WikiRecord wiki = f.repository.wikis.values().stream().findFirst().orElseThrow();
    assertEquals(2, wiki.claims().size());
    assertEquals(2, f.repository.lastDerivedSummaryIds.size());
    assertEquals(true, f.repository.lastDerivedSummaryIds.contains("s-old"));
    assertEquals(
        true,
        wiki.claims().stream()
            .flatMap(claim -> claim.factIds().stream())
            .anyMatch("f-old"::equals));
  }

  @Test
  void graphFailureDoesNotBlockWiki() {
    Fixture f = new Fixture(true, false);
    f.tasks.queue("v1", GenerationTaskType.SUMMARY, "", NOW);

    runCycles(f, 4);

    assertEquals(GenerationTaskStatus.FAILED, f.tasks.only(GenerationTaskType.GRAPH).status());
    assertEquals("GRAPH_GENERATION_FAILED", f.tasks.only(GenerationTaskType.GRAPH).errorCode());
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.WIKI).status());
    assertEquals(1, f.repository.wikis.size());
  }

  @Test
  void wikiFailureDoesNotAffectGraph() {
    Fixture f = new Fixture(false, true);
    f.tasks.queue("v1", GenerationTaskType.SUMMARY, "", NOW);

    runCycles(f, 4);

    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.GRAPH).status());
    assertEquals(GenerationTaskStatus.FAILED, f.tasks.only(GenerationTaskType.WIKI).status());
    assertEquals("WIKI_GENERATION_FAILED", f.tasks.only(GenerationTaskType.WIKI).errorCode());
    assertEquals(1, f.graphStore.relations.size());
  }

  @Test
  void interruptedConceptCanResumeWithoutDuplicatingDownstreamTasks() {
    Fixture f = new Fixture(false, false);
    f.tasks.queue("v1", GenerationTaskType.SUMMARY, "", NOW);
    assertEquals(1, f.worker.runOneCycle());

    List<GenerationTask> claimed = f.tasks.claimPending(1, NOW.plusSeconds(1));
    assertEquals(1, claimed.size());
    assertEquals(GenerationTaskType.CONCEPT, claimed.get(0).taskType());
    assertEquals(GenerationTaskStatus.RUNNING, f.tasks.only(GenerationTaskType.CONCEPT).status());

    f.tasks.resetInterrupted(NOW.plusSeconds(2));
    assertEquals(GenerationTaskStatus.PENDING, f.tasks.only(GenerationTaskType.CONCEPT).status());

    assertEquals(1, f.worker.runOneCycle());
    assertEquals(1, f.tasks.count(GenerationTaskType.GRAPH));
    assertEquals(1, f.tasks.count(GenerationTaskType.WIKI));

    f.tasks.resetInterrupted(NOW.plusSeconds(3));
    runCycles(f, 2);

    assertEquals(1, f.tasks.count(GenerationTaskType.GRAPH));
    assertEquals(1, f.tasks.count(GenerationTaskType.WIKI));
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.GRAPH).status());
    assertEquals(GenerationTaskStatus.SUCCEEDED, f.tasks.only(GenerationTaskType.WIKI).status());
  }

  private static void runCycles(Fixture fixture, int cycles) {
    for (int i = 0; i < cycles; i++) assertEquals(1, fixture.worker.runOneCycle());
  }

  private static final class Fixture {
    final InMemoryCompilationRepository repository = new InMemoryCompilationRepository();
    final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    final FakeObjects objects = new FakeObjects();
    final FakeEmbedding embedding = new FakeEmbedding();
    final FakeDerived derived = new FakeDerived();
    final FakeGraphStore graphStore = new FakeGraphStore();
    final FakeModel model;
    final DurableCompilationWorker worker;

    Fixture(boolean failGraph, boolean failWiki) {
      RawBlock block =
          new RawBlock(
              "rb1",
              "v1",
              0,
              "text",
              "甲公司向乙公司供货。",
              "甲公司向乙公司供货。",
              List.of(),
              1,
              1,
              List.of(),
              List.of(595d, 842d),
              List.of("rb1"),
              "hash",
              12);
      Evidence evidence =
          new Evidence(
              "ev1",
              "doc1",
              "v1",
              "rb1",
              "original/doc1/v1/a.pdf",
              Map.of("block_id", "rb1", "page_start", 1),
              block.content(),
              block.contentHash());
      repository.source =
          new VersionSource(
              "doc1",
              "v1",
              "尽调材料",
              "",
              new Metadata("投资部", "尽调", List.of("重点"), "甲公司", null, "项目/尽调/业务材料"),
              List.of(block),
              Map.of("rb1", evidence));

      model = new FakeModel(failGraph, failWiki);
      SummaryCompilationService summaries =
          new SummaryCompilationService(repository, model, objects, embedding, derived);
      ConceptCompilationService concepts = new ConceptCompilationService(repository, model);
      GraphCompilationService graph = new GraphCompilationService(repository, model, graphStore);
      WikiCompilationService wiki =
          new WikiCompilationService(repository, model, objects, embedding, derived);
      WikiKeywordProjectionService projection =
          new WikiKeywordProjectionService(new EmptyWikiProjectionRepository(), graphStore);
      worker =
          new DurableCompilationWorker(
              tasks,
              repository,
              summaries,
              concepts,
              graph,
              wiki,
              projection,
              Duration.ofHours(1),
              1,
              Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }

  private static final class FakeModel implements Ports.KnowledgeModel {
    private static final Pattern FACT_ID = Pattern.compile("<fact id=\\\"([^\\\"]+)\\\"");
    private static final Pattern CANDIDATE_ID = Pattern.compile("candidate_id=(C\\d+)");
    private final boolean failGraph;
    private final boolean failWiki;

    FakeModel(boolean failGraph, boolean failWiki) {
      this.failGraph = failGraph;
      this.failWiki = failWiki;
    }

    @Override
    public String identity(Ports.ModelRole role) {
      return role == Ports.ModelRole.LARGE ? "large-writer" : "small-judge";
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      return "unused";
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      Object result;
      if (outputType == SummaryCompilationService.Classification.class) {
        result = new SummaryCompilationService.Classification("项目/尽调/业务材料", "single", List.of());
      } else if (outputType == SummaryCompilationService.FactProposalSet.class) {
        result =
            new SummaryCompilationService.FactProposalSet(
                List.of(
                    new SummaryCompilationService.FactProposal(
                        "项目与主体", "甲公司向乙公司供货。", List.of("rb1"))));
      } else if (outputType == SummaryCompilationService.SupportDecision.class) {
        result = new SummaryCompilationService.SupportDecision(true, "directly supported");
      } else if (outputType == SummaryCompilationService.CoverageDecision.class) {
        result = new SummaryCompilationService.CoverageDecision(List.of());
      } else if (outputType == ConceptCompilationService.ConceptProposalSet.class) {
        String factId = firstFactId(userPrompt);
        result =
            new ConceptCompilationService.ConceptProposalSet(
                List.of(
                    new ConceptCompilationService.ConceptProposal(
                        "甲公司", List.of("甲企业"), "COMPANY", "供货关系中的企业主体", List.of(factId), 0.96)));
      } else if (outputType == GraphCompilationService.Extraction.class) {
        if (failGraph) throw new IllegalStateException("scripted graph failure");
        String factId = firstFactId(userPrompt);
        result =
            new GraphCompilationService.Extraction(
                List.of(
                    new GraphCompilationService.ProposedEntity("甲公司", "COMPANY"),
                    new GraphCompilationService.ProposedEntity("乙公司", "COMPANY")),
                List.of(
                    new GraphCompilationService.ProposedRelation(
                        "甲公司",
                        "COMPANY",
                        "SUPPLIES_TO",
                        "乙公司",
                        "COMPANY",
                        List.of(factId),
                        "",
                        "",
                        0.95)),
                List.of(new GraphCompilationService.FactDecision(factId, true, "")));
      } else if (outputType == WikiCompilationService.Proposal.class) {
        if (failWiki) throw new IllegalStateException("scripted wiki failure");
        List<String> factIds = allFactIds(userPrompt);
        result =
            new WikiCompilationService.Proposal(
                "COMPANY",
                "甲公司",
                factIds.stream()
                    .map(
                        factId ->
                            new WikiCompilationService.ProposedClaim(
                                "业务", "Grounded claim for " + factId, List.of(factId)))
                    .toList(),
                factIds.stream()
                    .map(factId -> new WikiCompilationService.FactDecision(factId, true, ""))
                    .toList());
      } else if (outputType == WikiCompilationService.SupportDecision.class) {
        result = new WikiCompilationService.SupportDecision(true, "directly supported");
      } else if (outputType == WikiCompilationService.SupportDecisionSet.class) {
        result =
            new WikiCompilationService.SupportDecisionSet(
                candidateIds(userPrompt).stream()
                    .map(
                        candidateId ->
                            new WikiCompilationService.SupportDecisionItem(
                                candidateId, true, "directly supported"))
                    .toList());
      } else {
        throw new AssertionError("unexpected output type " + outputType);
      }
      return (T) result;
    }

    private static String firstFactId(String prompt) {
      return allFactIds(prompt).get(0);
    }

    private static List<String> allFactIds(String prompt) {
      Matcher matcher = FACT_ID.matcher(prompt == null ? "" : prompt);
      List<String> ids = new ArrayList<>();
      while (matcher.find()) ids.add(matcher.group(1));
      if (ids.isEmpty()) throw new AssertionError("no fact id in prompt: " + prompt);
      return ids.stream().distinct().toList();
    }

    private static List<String> candidateIds(String prompt) {
      Matcher matcher = CANDIDATE_ID.matcher(prompt == null ? "" : prompt);
      List<String> ids = new ArrayList<>();
      while (matcher.find()) ids.add(matcher.group(1));
      if (ids.isEmpty()) throw new AssertionError("no candidate id in prompt: " + prompt);
      return ids.stream().distinct().toList();
    }
  }

  private static final class InMemoryTaskRepository implements Ports.GenerationTaskRepository {
    private final Map<String, GenerationTask> tasksByKey = new LinkedHashMap<>();
    private final Map<String, String> keyById = new LinkedHashMap<>();

    @Override
    public GenerationTask queue(
        String versionId, GenerationTaskType taskType, String subjectId, Instant now) {
      String subject = subjectId == null ? "" : subjectId.trim();
      String key = taskType + "|" + versionId + "|" + subject;
      GenerationTask existing = tasksByKey.get(key);
      if (existing != null) return existing;
      GenerationTask task =
          new GenerationTask(
              UUID.randomUUID().toString(),
              versionId,
              taskType,
              subject,
              GenerationTaskStatus.PENDING,
              0,
              "",
              "",
              now == null ? NOW : now);
      tasksByKey.put(key, task);
      keyById.put(task.taskId(), key);
      return task;
    }

    @Override
    public List<GenerationTask> claimPending(int limit, Instant now) {
      List<GenerationTask> claimed = new ArrayList<>();
      for (Map.Entry<String, GenerationTask> entry : tasksByKey.entrySet()) {
        if (claimed.size() >= Math.max(1, limit)) break;
        GenerationTask task = entry.getValue();
        if (task.status() != GenerationTaskStatus.PENDING) continue;
        GenerationTask running =
            copy(task, GenerationTaskStatus.RUNNING, task.attempt() + 1, "", "");
        entry.setValue(running);
        claimed.add(running);
      }
      return List.copyOf(claimed);
    }

    @Override
    public void complete(GenerationTask task, String outputHash, Instant now) {
      replaceRunning(task, GenerationTaskStatus.SUCCEEDED, "", "");
    }

    @Override
    public void completeConceptAndQueueDerived(
        GenerationTask task, String outputHash, Instant now) {
      complete(task, outputHash, now);
      queue(task.versionId(), GenerationTaskType.GRAPH, task.subjectId(), now);
      boolean conceptsComplete =
          tasksByKey.values().stream()
              .filter(
                  current ->
                      current.versionId().equals(task.versionId())
                          && current.taskType() == GenerationTaskType.CONCEPT)
              .allMatch(current -> current.status() == GenerationTaskStatus.SUCCEEDED);
      if (conceptsComplete) queue(task.versionId(), GenerationTaskType.WIKI, "", now);
    }

    @Override
    public void fail(String taskId, String errorCode, String errorMessage, Instant now) {
      GenerationTask task = byId(taskId);
      replaceRunning(task, GenerationTaskStatus.FAILED, errorCode, errorMessage);
    }

    @Override
    public void retry(String taskId, Instant now) {
      GenerationTask task = byId(taskId);
      if (task.status() != GenerationTaskStatus.FAILED) {
        throw new IllegalStateException("only failed tasks can be retried");
      }
      replace(task, GenerationTaskStatus.PENDING, task.attempt(), "", "");
    }

    @Override
    public void resetInterrupted(Instant now) {
      List<GenerationTask> running =
          tasksByKey.values().stream()
              .filter(task -> task.status() == GenerationTaskStatus.RUNNING)
              .toList();
      for (GenerationTask task : running) {
        replace(
            task,
            GenerationTaskStatus.PENDING,
            task.attempt(),
            "WORKER_INTERRUPTED",
            "generation worker restarted before task completion");
      }
    }

    GenerationTask only(GenerationTaskType type) {
      List<GenerationTask> found =
          tasksByKey.values().stream().filter(task -> task.taskType() == type).toList();
      assertEquals(1, found.size(), "expected exactly one " + type + " task");
      return found.get(0);
    }

    long count(GenerationTaskType type) {
      return tasksByKey.values().stream().filter(task -> task.taskType() == type).count();
    }

    private GenerationTask byId(String taskId) {
      String key = keyById.get(taskId);
      assertNotNull(key, "unknown task " + taskId);
      return tasksByKey.get(key);
    }

    private void replaceRunning(
        GenerationTask task, GenerationTaskStatus status, String errorCode, String errorMessage) {
      GenerationTask current = byId(task.taskId());
      if (current.status() != GenerationTaskStatus.RUNNING) {
        throw new IllegalStateException("task is not running: " + task.taskId());
      }
      replace(current, status, current.attempt(), errorCode, errorMessage);
    }

    private void replace(
        GenerationTask task,
        GenerationTaskStatus status,
        int attempt,
        String errorCode,
        String errorMessage) {
      String key = keyById.get(task.taskId());
      tasksByKey.put(key, copy(task, status, attempt, errorCode, errorMessage));
    }

    private static GenerationTask copy(
        GenerationTask task,
        GenerationTaskStatus status,
        int attempt,
        String errorCode,
        String errorMessage) {
      return new GenerationTask(
          task.taskId(),
          task.versionId(),
          task.taskType(),
          task.subjectId(),
          status,
          attempt,
          errorCode,
          errorMessage,
          task.createdAt());
    }
  }

  private static final class InMemoryCompilationRepository implements CompilationRepository {
    VersionSource source;
    final Map<String, SummaryRecord> summaries = new LinkedHashMap<>();
    final Map<String, List<ConceptRecord>> conceptsBySummary = new LinkedHashMap<>();
    final Map<String, WikiRecord> wikis = new LinkedHashMap<>();
    final Map<String, Entity> entities = new LinkedHashMap<>();
    List<String> lastDerivedSummaryIds = List.of();
    List<Relation> relations = List.of();
    List<EntityResolution> resolutions = List.of();

    @Override
    public VersionSource loadVersionSource(String versionId) {
      if (source == null || !source.versionId().equals(versionId)) {
        throw new IllegalStateException("version not found: " + versionId);
      }
      return source;
    }

    @Override
    public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {}

    @Override
    public void saveSummary(SummaryRecord summary) {
      summaries.put(summary.id(), summary);
    }

    @Override
    public Optional<SummaryRecord> findSummary(String summaryId) {
      return Optional.ofNullable(summaries.get(summaryId));
    }

    @Override
    public List<SummaryRecord> loadSummaries(List<String> summaryIds) {
      return summaryIds.stream().map(summaries::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForVersion(String versionId) {
      return summaries.values().stream()
          .filter(summary -> versionId.equals(summary.versionId()) && summary.activeAndGrounded())
          .toList();
    }

    @Override
    public List<SummaryRecord> loadActiveSummariesForCompany(String company) {
      return summaries.values().stream().filter(SummaryRecord::activeAndGrounded).toList();
    }

    @Override
    public void replaceConceptsForSummary(String summaryId, List<ConceptRecord> concepts) {
      conceptsBySummary.put(summaryId, List.copyOf(concepts));
    }

    @Override
    public List<ConceptRecord> loadActiveConceptsForSummary(String summaryId) {
      return conceptsBySummary.getOrDefault(summaryId, List.of()).stream()
          .filter(concept -> "ACTIVE".equals(concept.lifecycle()))
          .toList();
    }

    @Override
    public List<ConceptRecord> loadActiveConceptsForVersion(String versionId) {
      return conceptsBySummary.values().stream()
          .flatMap(List::stream)
          .filter(
              concept ->
                  versionId.equals(concept.versionId()) && "ACTIVE".equals(concept.lifecycle()))
          .toList();
    }

    @Override
    public void replaceGraphRejections(String summaryId, List<GraphRelationRejection> rejections) {}

    @Override
    public Entity resolveEntity(String name, String entityType) {
      String key = entityType + "|" + GraphCompilationService.canonicalKey(name);
      return entities.computeIfAbsent(
          key,
          ignored ->
              new Entity(
                  GraphCompilationService.stableId(
                      "entity", entityType, GraphCompilationService.canonicalKey(name)),
                  name,
                  entityType,
                  GraphCompilationService.canonicalKey(name),
                  List.of(),
                  Map.of()));
    }

    @Override
    public void saveGraph(
        String summaryId,
        List<Entity> entities,
        List<Relation> relations,
        List<EntityResolution> resolutions) {
      this.relations = List.copyOf(relations);
      this.resolutions = List.copyOf(resolutions);
    }

    @Override
    public long nextWikiVersion(String wikiId) {
      return wikis.containsKey(wikiId) ? wikis.get(wikiId).version() + 1 : 1;
    }

    @Override
    public void saveWiki(WikiRecord wiki) {
      wikis.put(wiki.id(), wiki);
    }

    @Override
    public Ports.DerivedContext derivedContextForSummaries(List<String> summaryIds) {
      lastDerivedSummaryIds = List.copyOf(summaryIds);
      return new Ports.DerivedContext(
          List.of("doc1"),
          List.of("v1"),
          List.of("投资部"),
          List.of("尽调"),
          List.of("甲公司"),
          List.of("项目/尽调/业务材料"),
          List.of("重点"));
    }
  }

  private static final class FakeObjects implements Ports.ObjectStore {
    final Map<String, byte[]> values = new LinkedHashMap<>();

    @Override
    public void put(String key, InputStream source, long size, String contentType) {
      try {
        values.put(key, source.readAllBytes());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Ports.StoredObject get(String key) {
      byte[] value = values.getOrDefault(key, new byte[0]);
      return new Ports.StoredObject(new ByteArrayInputStream(value), value.length, "text/markdown");
    }

    @Override
    public Ports.ObjectInfo stat(String key) {
      byte[] value = values.getOrDefault(key, new byte[0]);
      return new Ports.ObjectInfo(key, value.length, "text/markdown", "etag");
    }

    @Override
    public String presignGet(String key, Duration expiry) {
      return "https://example.invalid/" + key;
    }

    @Override
    public void delete(String key) {
      values.remove(key);
    }
  }

  private static final class FakeEmbedding implements Ports.EmbeddingPort {
    @Override
    public int dimensions() {
      return 4;
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
      return texts.stream().map(text -> List.of(1f, 0f, 0f, 0f)).toList();
    }
  }

  private static final class FakeDerived implements Ports.DerivedSearchIndex {
    @Override
    public void ensureIndex(int embeddingDimensions) {}

    @Override
    public void indexSummary(
        cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact summary,
        Ports.DerivedContext context,
        List<Float> embedding) {}

    @Override
    public void indexWiki(
        cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage page,
        Ports.DerivedContext context,
        List<Float> embedding) {}

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchSummaryBm25(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate>
        searchSummaryVector(
            List<Float> vector,
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
            int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiBm25(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiVector(
        List<Float> vector,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public void updateMetadata(String versionId, Metadata metadata) {}

    @Override
    public void deactivateVersion(String versionId) {}
  }

  private static final class FakeGraphStore implements Ports.GraphStore {
    List<Relation> relations = List.of();

    @Override
    public void upsertEntities(List<Entity> entities) {}

    @Override
    public void upsertRelations(List<Relation> relations) {
      this.relations = List.copyOf(relations);
    }

    @Override
    public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> search(
        String query,
        cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters,
        int limit) {
      return List.of();
    }

    @Override
    public void deactivateVersion(String versionId) {}
  }

  private static final class EmptyWikiProjectionRepository
      implements WikiKeywordProjectionService.Repository {
    @Override
    public List<WikiKeywordProjectionService.WikiKeywordClaim> loadClaims(
        String wikiId, long wikiVersion) {
      return List.of();
    }

    @Override
    public List<Entity> findCanonicalEntitiesByName(String canonicalName) {
      return List.of();
    }

    @Override
    public Relation saveRelation(
        Relation relation, String wikiId, long wikiVersion, String claimId) {
      return relation;
    }
  }
}
