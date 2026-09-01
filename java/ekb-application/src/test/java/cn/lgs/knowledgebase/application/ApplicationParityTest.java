package cn.lgs.knowledgebase.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApplicationParityTest {
  @Test
  void retrievalCallsOneUnifiedRerankerAfterFourLanesAndDropsUnresolvedCandidates() {
    List<Ports.RetrievalLane> lanes = new ArrayList<>();
    for (RetrievalDomain.LaneType type : RetrievalDomain.LaneType.values()) {
      lanes.add(new FakeLane(type));
    }
    AtomicInteger rerankCalls = new AtomicInteger();
    AtomicInteger resolverCalls = new AtomicInteger();
    Ports.RerankerPort reranker =
        (query, docs) -> {
          rerankCalls.incrementAndGet();
          return java.util.stream.IntStream.range(0, docs.size())
              .mapToObj(i -> 1.0 - i * 0.01)
              .toList();
        };
    Ports.EvidenceResolver resolver =
        ids -> {
          resolverCalls.incrementAndGet();
          return ids.stream()
              .filter(id -> !"missing".equals(id))
              .map(ApplicationParityTest::evidence)
              .toList();
        };
    var service =
        new RetrievalApplicationService(
            lanes, reranker, resolver, RetrievalDomain.Quotas.defaults(), 20, 400);

    var response =
        service.search(
            "question", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 10);

    assertEquals(1, rerankCalls.get());
    assertEquals(1, resolverCalls.get());
    assertEquals(36, response.searchId().length());
    assertDoesNotThrow(() -> UUID.fromString(response.searchId()));
    assertEquals(4, response.results().size());
    assertTrue(response.results().stream().allMatch(r -> !r.evidence().isEmpty()));
  }

  @Test
  void retrievalFallsBackToFusedCandidatesWhenRerankerIsUnavailable() {
    Ports.RetrievalLane raw = new FakeLane(RetrievalDomain.LaneType.RAW);
    AtomicInteger resolverCalls = new AtomicInteger();
    var service =
        new RetrievalApplicationService(
            List.of(raw),
            (query, docs) -> {
              throw new IllegalStateException("reranker unavailable");
            },
            ids -> {
              resolverCalls.incrementAndGet();
              return ids.stream().map(ApplicationParityTest::evidence).toList();
            },
            RetrievalDomain.Quotas.defaults(),
            20,
            400);

    var response =
        service.search(
            "question", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 8);

    assertEquals(1, resolverCalls.get());
    assertEquals(1, response.results().size());
    assertEquals(RetrievalDomain.LaneType.RAW, response.results().get(0).candidate().lane());
    assertFalse(response.results().get(0).evidence().isEmpty());
  }

  @Test
  void retrievalBoundsRerankPoolToTwiceTheRequestedResultCount() {
    Ports.RetrievalLane raw =
        new Ports.RetrievalLane() {
          @Override
          public RetrievalDomain.LaneType type() {
            return RetrievalDomain.LaneType.RAW;
          }

          @Override
          public List<RetrievalDomain.Candidate> search(
              String query, KnowledgeDomain.SearchFilters filters, int limit) {
            return java.util.stream.IntStream.range(0, 20)
                .mapToObj(
                    i ->
                        new RetrievalDomain.Candidate(
                            "raw-" + i,
                            "doc",
                            "v1",
                            RetrievalDomain.LaneType.RAW,
                            "knowledge " + i,
                            20 - i,
                            List.of("ev-" + i),
                            Map.of()))
                .toList();
          }
        };
    AtomicInteger rerankDocuments = new AtomicInteger();
    var service =
        new RetrievalApplicationService(
            List.of(raw),
            (query, documents) -> {
              rerankDocuments.set(documents.size());
              return java.util.Collections.nCopies(documents.size(), 1.0);
            },
            ids -> ids.stream().map(ApplicationParityTest::evidence).toList(),
            RetrievalDomain.Quotas.defaults(),
            20,
            400);

    var response =
        service.search(
            "question", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 3);

    assertEquals(6, rerankDocuments.get());
    assertEquals(3, response.results().size());
  }

  @Test
  void retrievalKeepsRawResultsWhenDerivedLaneIsUnavailable() {
    Ports.RetrievalLane raw = new FakeLane(RetrievalDomain.LaneType.RAW);
    Ports.RetrievalLane graph = new FailingLane(RetrievalDomain.LaneType.GRAPH);
    var service =
        new RetrievalApplicationService(
            List.of(raw, graph),
            (query, docs) -> java.util.Collections.nCopies(docs.size(), 1.0),
            ids -> ids.stream().map(ApplicationParityTest::evidence).toList(),
            RetrievalDomain.Quotas.defaults(),
            20,
            400);

    var response =
        service.search(
            "question", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 8);

    assertEquals(1, response.results().size());
    assertEquals(RetrievalDomain.LaneType.RAW, response.results().get(0).candidate().lane());
  }

  @Test
  void retrievalDoesNotHideRawLaneFailure() {
    Ports.RetrievalLane raw = new FailingLane(RetrievalDomain.LaneType.RAW);
    var service =
        new RetrievalApplicationService(
            List.of(raw),
            (query, docs) -> List.of(),
            ids -> List.of(),
            RetrievalDomain.Quotas.defaults(),
            20,
            400);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.search(
                "question", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 8));
  }

  @Test
  void askRejectsNaturalLanguageAnswerWithoutValidEvidenceCitation() {
    var retrieval = retrievalWithSingleSource();
    Ports.KnowledgeModel invalidModel = new StaticModel("这是一个没有引用的答案");
    var service = new AskApplicationService(retrieval, invalidModel, 24000);
    var response =
        service.ask("问题", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 8);
    assertTrue(response.evidenceOnly());
    assertFalse(response.citations().isEmpty());
  }

  @Test
  void askDeliversAnswerWhenCitationIsValid() {
    var retrieval = retrievalWithSingleSource();
    var service = new AskApplicationService(retrieval, new StaticModel("依据材料可确认该事实。[1]"), 24000);
    var response =
        service.ask("问题", new KnowledgeDomain.SearchFilters(null, null, null, List.of()), 8);
    assertFalse(response.evidenceOnly());
    assertEquals(1, response.citations().size());
    assertTrue(response.answer().contains("[1]"));
  }

  @Test
  void askPrioritizesQuestionRelevantEvidenceAndBoundsTheAnswerContext() {
    List<KnowledgeDomain.Evidence> evidence = new ArrayList<>();
    for (int i = 0; i < 20; i++) evidence.add(evidence("noise-" + i, "其他无关条款编号" + i));
    KnowledgeDomain.Evidence target = evidence("target", "第三条投资与决策委员会成员由三名董事组成，其中包括一名独立董事。");
    evidence.add(target);
    CapturingModel model = new CapturingModel("依据原文可确认。[1]");
    var service = new AskApplicationService(retrievalWithSingleSource(), model, 24000);

    var response =
        service.answerFromResults(
            "投资与决策委员会由几名董事组成，其中独立董事几名？",
            "search",
            List.of(resultWithEvidence(evidence)),
            Ports.ModelRole.SMALL);

    assertFalse(response.evidenceOnly());
    assertEquals("target", response.citations().get(0).evidence().id());
    assertTrue(model.userPrompt.contains(target.content()));
    assertFalse(model.userPrompt.contains("其他无关条款编号19"));
  }

  @Test
  void askCanonicalizesSparseModelCitationIndexesBeforeDelivery() {
    List<KnowledgeDomain.Evidence> evidence =
        java.util.stream.IntStream.rangeClosed(1, 6)
            .mapToObj(i -> evidence("ev-" + i, "source material " + i))
            .toList();
    var service =
        new AskApplicationService(
            retrievalWithSingleSource(), new StaticModel("结论来自第六条。[6] 再次引用。[6]"), 24000);

    var response =
        service.answerFromResults(
            "xyz", "search", List.of(resultWithEvidence(evidence)), Ports.ModelRole.SMALL);

    assertEquals("结论来自第六条。[1] 再次引用。[1]", response.answer());
    assertEquals(1, response.citations().size());
    assertEquals(1, response.citations().get(0).index());
    assertEquals("ev-6", response.citations().get(0).evidence().id());
  }

  @Test
  void askRejectsCitationForOriginalExcludedByTheRenderedContextBudget() {
    var model = new CapturingModel("第二份资料证明了结论。[2]");
    var service = new AskApplicationService(retrievalWithSingleSource(), model, 1400);
    var response =
        service.answerFromResults(
            "xyz",
            "search",
            List.of(
                resultWithEvidence(
                    List.of(
                        evidence("first", "A".repeat(1000)),
                        evidence("second", "B".repeat(1000))))),
            Ports.ModelRole.SMALL);

    assertTrue(response.evidenceOnly());
    assertFalse(model.userPrompt.contains("[SOURCE 2]"));
    assertEquals(
        List.of("first"), response.citations().stream().map(c -> c.evidence().id()).toList());
  }

  @Test
  void askNeverAcceptsNinthSourceOutsideTheEightSegmentBudget() {
    var model = new CapturingModel("结论由第九份资料证明。[9]");
    var evidence =
        java.util.stream.IntStream.rangeClosed(1, 12)
            .mapToObj(i -> evidence("ev-" + i, "完整业务原文 " + i))
            .toList();
    var service = new AskApplicationService(retrievalWithSingleSource(), model, 24000);
    var response =
        service.answerFromResults(
            "xyz", "search", List.of(resultWithEvidence(evidence)), Ports.ModelRole.SMALL);

    assertTrue(response.evidenceOnly());
    assertFalse(model.userPrompt.contains("[SOURCE 9]"));
    assertEquals(8, response.citations().size());
  }

  @Test
  void askKeepsWholeChineseOriginalsWithinTheTokenBudgetAndSkipsAnOversizedSource() {
    var model = new CapturingModel("两份完整资料支持结论。[1][2]");
    var counter =
        new cn.lgs.knowledgebase.application.raw.RawTokenCounter() {
          public int count(String text) {
            return text.codePointCount(0, text.length());
          }

          public String identity() {
            return "test-codepoint-counter";
          }
        };
    var service = new AskApplicationService(retrievalWithSingleSource(), model, 24000, counter);
    String first = "甲".repeat(3000);
    String second = "乙".repeat(3000);
    var response =
        service.answerFromResults(
            "xyz",
            "search",
            List.of(
                resultWithEvidence(
                    List.of(
                        evidence("too-large", "丙".repeat(6001)), evidence("first", first),
                        evidence("second", second), evidence("over-budget", "丁")))),
            Ports.ModelRole.SMALL);

    assertFalse(response.evidenceOnly());
    assertEquals(
        List.of("first", "second"),
        response.citations().stream().map(c -> c.evidence().id()).toList());
    assertTrue(model.userPrompt.contains("Raw evidence: " + first + "\n"));
    assertTrue(model.userPrompt.contains("Raw evidence: " + second + "\n"));
    assertFalse(model.userPrompt.contains("丙"));
    assertFalse(model.userPrompt.contains("丁"));
    assertEquals(
        service.evidenceContext(
            "xyz",
            List.of(
                resultWithEvidence(
                    List.of(
                        evidence("first", first),
                        evidence("first", first),
                        evidence("second", second)))),
            24000),
        model.userPrompt.substring(model.userPrompt.indexOf("[SOURCE 1]")));
  }

  private static RetrievalDomain.Result resultWithEvidence(
      List<KnowledgeDomain.Evidence> evidence) {
    var candidate =
        new RetrievalDomain.Candidate(
            "raw-many",
            "doc",
            "v1",
            RetrievalDomain.LaneType.RAW,
            "whole document",
            1.0,
            evidence.stream().map(KnowledgeDomain.Evidence::id).toList(),
            Map.of());
    return new RetrievalDomain.Result(candidate, evidence);
  }

  private static RetrievalApplicationService retrievalWithSingleSource() {
    Ports.RetrievalLane raw = new FakeLane(RetrievalDomain.LaneType.RAW);
    return new RetrievalApplicationService(
        List.of(raw),
        (q, docs) -> List.of(1.0),
        ids -> List.of(evidence(ids.get(0))),
        RetrievalDomain.Quotas.defaults(),
        20,
        400);
  }

  private static KnowledgeDomain.Evidence evidence(String id) {
    return evidence(id, "raw verified content");
  }

  private static KnowledgeDomain.Evidence evidence(String id, String content) {
    return new KnowledgeDomain.Evidence(
        id, "doc", "v1", "block", "original/doc/v1.pdf", Map.of("page_start", 1), content, "hash");
  }

  private record FakeLane(RetrievalDomain.LaneType type) implements Ports.RetrievalLane {
    @Override
    public List<RetrievalDomain.Candidate> search(
        String query, KnowledgeDomain.SearchFilters filters, int limit) {
      return List.of(
          new RetrievalDomain.Candidate(
              type.name().toLowerCase(),
              "doc",
              "v1",
              type,
              "knowledge " + type,
              0,
              List.of("ev-" + type.name().toLowerCase()),
              Map.of()));
    }
  }

  private record FailingLane(RetrievalDomain.LaneType type) implements Ports.RetrievalLane {
    @Override
    public List<RetrievalDomain.Candidate> search(
        String query, KnowledgeDomain.SearchFilters filters, int limit) {
      throw new IllegalStateException(type + " unavailable");
    }
  }

  private static final class CapturingModel implements Ports.KnowledgeModel {
    private final String answer;
    private String userPrompt = "";

    private CapturingModel(String answer) {
      this.answer = answer;
    }

    @Override
    public String identity(Ports.ModelRole role) {
      return role.name().toLowerCase();
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      this.userPrompt = userPrompt;
      return answer;
    }

    @Override
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      throw new UnsupportedOperationException();
    }
  }

  private record StaticModel(String answer) implements Ports.KnowledgeModel {
    @Override
    public String identity(Ports.ModelRole role) {
      return role.name().toLowerCase();
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      return answer;
    }

    @Override
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      throw new UnsupportedOperationException();
    }
  }
}
