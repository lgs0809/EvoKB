package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.GraphRelationRejection;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConceptCompilationServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

  @Test
  void shortModelReferencesPersistCanonicalFactsAndOnlyTheirEvidence() {
    Fixture f =
        new Fixture(
            summary(groundedFact("uuid-b", "第二个对象", "e2"), groundedFact("uuid-a", "第一个对象", "e1")));
    f.model.output =
        proposals(proposal("第一个对象", List.of(), "CONTRACT", "资料锚点", List.of("F001", "uuid-a"), 0.9));
    var calls = new java.util.ArrayList<List<Object>>();
    var result =
        new ConceptCompilationService(
                f.repo,
                f.model,
                recordingEmbedding(input -> List.of(List.of(1f))),
                recordingIndex(calls))
            .compileSummary("s1");
    assertEquals(List.of("uuid-a"), result.concepts().get(0).factIds());
    assertEquals(List.of("e1"), calls.get(0).get(2));
    assertTrue(f.model.prompt.contains("<fact id=\"F001\""));
    assertFalse(f.model.prompt.contains("<fact id=\"uuid-a\""));
  }

  @Test
  void inventedShortReferenceAndNonFiniteConfidenceFailBeforePublishing() {
    for (var candidate :
        List.of(
            proposal("对象", List.of(), "CONTRACT", "资料", List.of("F999"), 0.9),
            proposal("对象", List.of(), "CONTRACT", "资料", List.of("F001"), Double.NaN),
            proposal(
                "对象", List.of(), "CONTRACT", "资料", List.of("F001"), Double.POSITIVE_INFINITY))) {
      Fixture f = new Fixture(summary(groundedFact("uuid", "对象", "e1")));
      f.model.output = proposals(candidate);
      assertThrows(IllegalStateException.class, () -> f.service.compileSummary("s1"));
      assertTrue(f.repo.conceptsBySummary.isEmpty());
    }
  }

  @Test
  void groundedConceptPublishesCompactProjectionWithOnlyItsOwnEvidence() {
    Fixture f =
        new Fixture(
            summary(
                groundedFact("f1", "很长的合同原文不得作为概念向量内容", "e1"),
                groundedFact("f2", "另外一个不参与本条目的事实", "e2")));
    f.model.output =
        proposals(
            proposal("采购合同", List.of("设备合同"), "CONTRACT", "覆盖设备采购与验收付款资料", List.of("f1"), 0.95));
    var encoded = new java.util.ArrayList<String>();
    Ports.EmbeddingPort embedding =
        recordingEmbedding(
            input -> {
              encoded.addAll(input);
              return List.of(List.of(1f));
            });
    var calls = new java.util.ArrayList<List<Object>>();
    var derived = recordingIndex(calls);
    var result =
        new ConceptCompilationService(f.repo, f.model, embedding, derived).compileSummary("s1");
    assertTrue(encoded.get(0).contains("采购合同"));
    assertTrue(encoded.get(0).contains("设备合同"));
    assertFalse(encoded.get(0).contains("很长的合同原文"));
    assertEquals(1, calls.size());
    assertEquals(result.concepts().get(0).id(), calls.get(0).get(0));
    assertEquals(encoded.get(0), calls.get(0).get(1));
    assertEquals(List.of("e1"), calls.get(0).get(2));
    assertEquals(List.of(1f), calls.get(0).get(4));
  }

  @Test
  void failedConceptEmbeddingDoesNotPublishASuccessfulProjection() {
    Fixture f = new Fixture(summary(groundedFact("f1", "合同事实", "e1")));
    f.model.output =
        proposals(proposal("采购合同", List.of(), "CONTRACT", "采购合同资料", List.of("f1"), 0.95));
    Ports.EmbeddingPort embedding =
        recordingEmbedding(
            input -> {
              throw new IllegalStateException("unavailable");
            });
    var calls = new java.util.ArrayList<List<Object>>();
    assertThrows(
        IllegalStateException.class,
        () ->
            new ConceptCompilationService(f.repo, f.model, embedding, recordingIndex(calls))
                .compileSummary("s1"));
    assertTrue(calls.isEmpty());
    assertTrue(f.repo.conceptsBySummary.isEmpty());
  }

  private static Ports.EmbeddingPort recordingEmbedding(
      java.util.function.Function<List<String>, List<List<Float>>> embed) {
    return new Ports.EmbeddingPort() {
      public int dimensions() {
        return 1024;
      }

      public List<List<Float>> embed(List<String> values) {
        return embed.apply(values);
      }
    };
  }

  private static Ports.DerivedSearchIndex recordingIndex(List<List<Object>> calls) {
    return (Ports.DerivedSearchIndex)
        java.lang.reflect.Proxy.newProxyInstance(
            Ports.DerivedSearchIndex.class.getClassLoader(),
            new Class<?>[] {Ports.DerivedSearchIndex.class},
            (proxy, method, args) -> {
              if (method.isDefault() && !method.getName().equals("indexConcept"))
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
              if (!method.getName().equals("indexConcept"))
                throw new AssertionError("unexpected index operation: " + method.getName());
              calls.add(java.util.Arrays.asList(args));
              return null;
            });
  }

  @Test
  void conceptRequiresAtLeastOneSupportingFact() {
    Fixture f = new Fixture(summary(groundedFact("f1", "万科是一家公司。", "e1")));
    f.model.output =
        proposals(proposal("万科企业股份有限公司", List.of("万科"), "COMPANY", "企业主体", List.of(), 0.95));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> f.service.compileSummary("s1"));

    assertTrue(failure.getMessage().contains("at least one supporting fact"));
    assertTrue(f.repo.conceptsBySummary.isEmpty());
  }

  @Test
  void supportingFactMustBeGrounded() {
    SummaryFact ungrounded =
        new SummaryFact(
            "f1", "s1", "主体", "万科是一家公司。", List.of("b1"), List.of("e1"), "FAIL", "not supported");
    Fixture f = new Fixture(summary(ungrounded));
    f.model.output =
        proposals(proposal("万科企业股份有限公司", List.of("万科"), "COMPANY", "企业主体", List.of("f1"), 0.95));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> f.service.compileSummary("s1"));

    assertTrue(failure.getMessage().contains("no grounded summary facts"));
    assertEquals(0, f.model.calls);
  }

  @Test
  void supportingFactMustHaveRawEvidence() {
    SummaryFact withoutEvidence =
        new SummaryFact("f1", "s1", "主体", "万科是一家公司。", List.of("b1"), List.of(), "PASS", "direct");
    Fixture f = new Fixture(summary(withoutEvidence));
    f.model.output =
        proposals(proposal("万科企业股份有限公司", List.of("万科"), "COMPANY", "企业主体", List.of("f1"), 0.95));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> f.service.compileSummary("s1"));

    assertTrue(failure.getMessage().contains("no grounded summary facts"));
    assertEquals(0, f.model.calls);
  }

  @Test
  void aliasesAreNormalizedAndUnknownTypeHintFallsBackToOther() {
    Fixture f = new Fixture(summary(groundedFact("f1", "万科是一家公司。", "e1")));
    f.model.output =
        proposals(
            proposal(
                "万科企业股份有限公司",
                List.of(" 万科 ", "万科", " 万科企业股份有限公司 ", "", "万科"),
                "made_up_type",
                "企业主体",
                List.of("f1"),
                0.95));

    ConceptCompilationService.CompileResult result = f.service.compileSummary("s1");

    assertEquals(1, result.concepts().size());
    ConceptRecord concept = result.concepts().get(0);
    assertEquals("OTHER", concept.conceptTypeHint());
    assertEquals(List.of("万科"), concept.aliases());
    assertEquals(List.of("f1"), concept.factIds());
  }

  @Test
  void similarButDistinctCommitteesAreNotMerged() {
    Fixture f =
        new Fixture(
            summary(
                groundedFact("f1", "审计委员会由三名董事组成。", "e1"),
                groundedFact("f2", "投资与决策委员会由三名董事组成。", "e2")));
    f.model.output =
        proposals(
            proposal("审计委员会", List.of("董事会审计委员会"), "COMMITTEE", "审计相关委员会", List.of("f1"), 0.96),
            proposal(
                "投资与决策委员会", List.of("董事会投资与决策委员会"), "COMMITTEE", "投资决策相关委员会", List.of("f2"), 0.96));

    ConceptCompilationService.CompileResult result = f.service.compileSummary("s1");

    assertEquals(2, result.concepts().size());
    assertEquals(
        List.of("审计委员会", "投资与决策委员会"),
        result.concepts().stream().map(ConceptRecord::preferredLabel).sorted().toList());
    assertEquals(2, result.concepts().stream().map(ConceptRecord::id).distinct().count());
  }

  @Test
  void unknownSupportingFactIdFailsClosed() {
    Fixture f = new Fixture(summary(groundedFact("f1", "万科是一家公司。", "e1")));
    f.model.output =
        proposals(
            proposal(
                "万科企业股份有限公司", List.of("万科"), "COMPANY", "企业主体", List.of("missing-fact"), 0.95));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> f.service.compileSummary("s1"));

    assertTrue(failure.getMessage().contains("unknown or ungrounded fact"));
    assertTrue(f.repo.conceptsBySummary.isEmpty());
  }

  private static SummaryFact groundedFact(String id, String text, String evidenceId) {
    return new SummaryFact(
        id, "s1", "事实", text, List.of("block-" + id), List.of(evidenceId), "PASS", "direct");
  }

  private static SummaryRecord summary(SummaryFact... facts) {
    return new SummaryRecord(
        "s1",
        "u1",
        "d1",
        "v1",
        "summary/v1/s1.md",
        "hash",
        "PASS",
        "PASS",
        "ACTIVE",
        "writer-model",
        "# Summary",
        List.of(facts),
        NOW);
  }

  private static ConceptCompilationService.ConceptProposal proposal(
      String preferredLabel,
      List<String> aliases,
      String typeHint,
      String description,
      List<String> factIds,
      double confidence) {
    return new ConceptCompilationService.ConceptProposal(
        preferredLabel, aliases, typeHint, description, factIds, confidence);
  }

  private static ConceptCompilationService.ConceptProposalSet proposals(
      ConceptCompilationService.ConceptProposal... proposals) {
    return new ConceptCompilationService.ConceptProposalSet(List.of(proposals));
  }

  private static final class Fixture {
    final FakeRepository repo;
    final FakeModel model = new FakeModel();
    final ConceptCompilationService service;

    Fixture(SummaryRecord summary) {
      repo = new FakeRepository(summary);
      service = new ConceptCompilationService(repo, model, Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }

  private static final class FakeModel implements Ports.KnowledgeModel {
    Object output;
    int calls;
    String prompt;

    @Override
    public String identity(Ports.ModelRole role) {
      return "concept-model";
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      throw new UnsupportedOperationException("structured output required");
    }

    @Override
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      assertEquals(Ports.ModelRole.LARGE, role);
      assertEquals(Ports.ModelTask.CONCEPT_EXTRACT, task);
      calls++;
      prompt = userPrompt;
      return outputType.cast(output);
    }
  }

  private static final class FakeRepository implements CompilationRepository {
    final Map<String, SummaryRecord> summaries = new LinkedHashMap<>();
    final Map<String, List<ConceptRecord>> conceptsBySummary = new LinkedHashMap<>();

    FakeRepository(SummaryRecord summary) {
      summaries.put(summary.id(), summary);
    }

    @Override
    public VersionSource loadVersionSource(String versionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {
      throw new UnsupportedOperationException();
    }

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
      return conceptsBySummary.getOrDefault(summaryId, List.of());
    }

    @Override
    public List<ConceptRecord> loadActiveConceptsForVersion(String versionId) {
      return conceptsBySummary.values().stream()
          .flatMap(List::stream)
          .filter(concept -> versionId.equals(concept.versionId()))
          .toList();
    }

    @Override
    public void replaceGraphRejections(String summaryId, List<GraphRelationRejection> rejections) {}

    @Override
    public Entity resolveEntity(String name, String entityType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void saveGraph(
        String summaryId,
        List<Entity> entities,
        List<Relation> relations,
        List<EntityResolution> resolutions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long nextWikiVersion(String wikiId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void saveWiki(WikiRecord wiki) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Ports.DerivedContext derivedContextForSummaries(List<String> summaryIds) {
      return new Ports.DerivedContext(
          List.of("d1"), List.of("v1"), List.of(), List.of(), List.of(), List.of(), List.of());
    }
  }
}
