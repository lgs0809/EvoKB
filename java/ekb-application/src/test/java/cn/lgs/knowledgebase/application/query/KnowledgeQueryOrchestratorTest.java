package cn.lgs.knowledgebase.application.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalReservation;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import cn.lgs.knowledgebase.domain.query.RetrievalBudget;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class KnowledgeQueryOrchestratorTest {
  private static final SearchFilters NO_FILTERS = new SearchFilters(null, null, null, List.of());

  @Test
  void simpleQuestionUsesOriginalQueryOnceAndAnswersImmediately() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency("ANSWER", List.of(), List.of(), "", List.of());

    var response = scenario.orchestrator.start("user-1", "原始问题", NO_FILTERS, 8);

    assertEquals(SessionStatus.ANSWERED, response.session().status());
    assertEquals(List.of("原始问题"), scenario.lane.queries);
    assertEquals(1, response.rounds().size());
    assertEquals(1, response.citations().size());
    assertTrue(response.session().finalAnswer().contains("[1]"));
    assertEquals(Ports.ModelRole.SMALL, scenario.model.lastAnswerRole);
    assertTrue(scenario.model.lastAnswerPrompt.contains("Raw evidence: 可验证原文"));
    assertEquals(
        scenario.model.lastJudgePrompt.substring(
            scenario.model.lastJudgePrompt.indexOf("[SOURCE 1]")),
        scenario.model.lastAnswerPrompt.substring(
            scenario.model.lastAnswerPrompt.indexOf("[SOURCE 1]")));
    assertFalse(scenario.model.lastAnswerPrompt.contains("knowledge for 原始问题"));
  }

  @Test
  void missingEvidenceTriggersBoundedSupplementaryQueriesAndLargeSynthesis() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency(
        "CONTINUE_SEARCH", List.of("缺少补充事实"), List.of("补充A", "补充A", "补充B"), "", List.of());
    scenario.model.sufficiency("ANSWER", List.of(), List.of(), "", List.of());

    var response = scenario.orchestrator.start("user-1", "复杂问题", NO_FILTERS, 8);

    assertEquals(SessionStatus.ANSWERED, response.session().status());
    assertEquals(List.of("复杂问题", "补充A", "补充B"), scenario.lane.queries);
    assertEquals(2, response.rounds().size());
    assertEquals(List.of("补充A", "补充B"), response.rounds().get(0).nextQueries());
    assertTrue(response.evidence().size() >= 3);
    assertEquals(Ports.ModelRole.LARGE, scenario.model.lastAnswerRole);
  }

  @Test
  void ambiguityWaitsForUserAndClarificationContinuesSameSession() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("ASK_USER", "你指哪一个主体？", List.of("主体A", "主体B"));
    scenario.model.sufficiency("ANSWER", List.of(), List.of(), "", List.of());

    var waiting = scenario.orchestrator.start("user-1", "它最近有什么变化？", NO_FILTERS, 8);

    assertEquals(SessionStatus.WAITING_USER, waiting.session().status());
    assertEquals("你指哪一个主体？", waiting.session().clarificationQuestion());
    assertTrue(scenario.lane.queries.isEmpty());

    var answered =
        scenario.orchestrator.continueAfterClarification(
            "user-1", waiting.session().sessionId(), "主体A", 8);

    assertEquals(waiting.session().sessionId(), answered.session().sessionId());
    assertEquals(SessionStatus.ANSWERED, answered.session().status());
    assertEquals(1, scenario.lane.queries.size());
    assertTrue(scenario.lane.queries.get(0).contains("主体A"));
    assertEquals(4, answered.turns().size());
  }

  @Test
  void missingKnowledgeEndsAsInsufficientInsteadOfInventingClarification() {
    Scenario scenario = scenario(true);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency(
        "CONTINUE_SEARCH", List.of("缺少事实A"), List.of("补搜事实A"), "", List.of());
    scenario.model.sufficiency("CONTINUE_SEARCH", List.of("仍无可验证资料"), List.of(), "", List.of());

    var response = scenario.orchestrator.start("user-1", "知识库没有的问题", NO_FILTERS, 8);

    assertEquals(SessionStatus.INSUFFICIENT, response.session().status());
    assertTrue(response.session().clarificationQuestion().isBlank());
    assertTrue(response.session().finalAnswer().contains("证据不足"));
    assertEquals(List.of("知识库没有的问题", "补搜事实A"), scenario.lane.queries);
    assertFalse(response.turns().isEmpty());
  }

  @Test
  void failedDispatchHasAlreadyConsumedItsRound() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    var opened = scenario.orchestrator.open("user-1", "断线问题", NO_FILTERS);
    String id = opened.session().sessionId();
    scenario.lane.beforeSearch =
        query -> {
          assertEquals(
              List.of(query), scenario.repository.listReservations(id).get(0).inputQueries());
          throw new IllegalStateException("synthetic transport failure");
        };

    assertThrows(
        IllegalStateException.class, () -> scenario.orchestrator.executeNew("user-1", id, 8));

    assertEquals(1, scenario.repository.listReservations(id).size());
    assertTrue(scenario.repository.listRounds(id).isEmpty());
    assertEquals(SessionStatus.FAILED, scenario.repository.findSession(id).orElseThrow().status());
  }

  @Test
  void configurationCannotExpandTheEightRoundCeiling() {
    Scenario scenario = scenario(true, 999, 999);
    scenario.model.understanding("CLEAR", "", List.of());
    for (int i = 1; i <= 8; i++) {
      scenario.model.sufficiency(
          "CONTINUE_SEARCH", List.of("缺少合同付款依据"), List.of("补充线索" + i), "", List.of());
    }

    var result = scenario.orchestrator.start("user-1", "首轮问题", NO_FILTERS, 8);

    assertEquals(8, scenario.lane.queries.size());
    assertEquals(8, result.rounds().size());
    assertEquals(8, scenario.repository.listReservations(result.session().sessionId()).size());
    assertEquals(SessionStatus.INSUFFICIENT, result.session().status());
    assertTrue(result.session().finalAnswer().contains("缺少合同付款依据"));
    assertTrue(result.session().finalAnswer().contains("检索预算"));
    assertFalse(result.session().finalAnswer().contains("没有新的检索线索"));
  }

  @Test
  void interruptedReservationsRemainConsumedBeforeAnyNewDispatch() {
    Scenario scenario = scenario(true, 8, 8);
    var opened = scenario.orchestrator.open("user-1", "重启后的问题", NO_FILTERS);
    String id = opened.session().sessionId();
    for (int i = 1; i <= 8; i++)
      scenario.repository.reserveRound("user-1", id, List.of("旧线索" + i), 8, 8);
    scenario.model.understanding("CLEAR", "", List.of());

    var response = scenario.orchestrator.executeNew("user-1", id, 8);

    assertTrue(scenario.lane.queries.isEmpty());
    assertTrue(response.rounds().isEmpty());
    assertEquals(SessionStatus.INSUFFICIENT, response.session().status());
    assertEquals(8, scenario.repository.listReservations(id).size());
  }

  @Test
  void noNewCluesPreservesTheGapAndOriginalEvidenceWithoutClaimingAnAnswer() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency(
        "CONTINUE_SEARCH", List.of("缺少延期条款"), List.of("原始问题"), "", List.of());

    var result = scenario.orchestrator.start("user-1", "原始问题", NO_FILTERS, 8);

    assertEquals(SessionStatus.INSUFFICIENT, result.session().status());
    assertTrue(result.session().evidenceOnly());
    assertTrue(result.session().finalAnswer().contains("缺少延期条款"));
    assertTrue(result.session().finalAnswer().contains("没有新的检索线索"));
    assertTrue(result.session().finalAnswer().contains("可验证原文"));
    assertTrue(result.session().finalAnswer().contains("[1]"));
    assertEquals(1, result.citations().size());
    assertEquals(null, scenario.model.lastAnswerRole);
    assertEquals(1, scenario.lane.queries.size());
  }

  @Test
  void queryLimitCutsOffAdditionalSubqueriesWithoutLosingTheGap() {
    Scenario scenario = scenario(true, 8, 2);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency(
        "CONTINUE_SEARCH", List.of("缺少补充资料"), List.of("补搜A", "补搜B"), "", List.of());
    scenario.model.sufficiency("CONTINUE_SEARCH", List.of("仍缺付款资料"), List.of("补搜C"), "", List.of());
    var result = scenario.orchestrator.start("user-1", "原始问题", NO_FILTERS, 8);
    assertEquals(List.of("原始问题", "补搜A"), scenario.lane.queries);
    assertEquals(SessionStatus.INSUFFICIENT, result.session().status());
    assertTrue(result.session().finalAnswer().contains("仍缺付款资料"));
    assertTrue(result.session().finalAnswer().contains("检索预算"));
  }

  @Test
  void transientSecondRoundFailurePreservesFirstRoundEvidenceAndGap() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiency(
        "CONTINUE_SEARCH", List.of("缺少违约金上限"), List.of("补充违约条款"), "", List.of());
    scenario.lane.beforeSearch =
        query -> {
          if (query.equals("补充违约条款"))
            throw new IllegalStateException(
                "embedding wrapper",
                new Ports.TransientDependencyFailure("synthetic unavailable dependency", null));
        };
    var result = scenario.orchestrator.start("user-1", "违约金是多少", NO_FILTERS, 8);
    assertEquals(SessionStatus.FAILED, result.session().status());
    assertEquals(1, result.rounds().size());
    assertEquals(2, scenario.repository.listReservations(result.session().sessionId()).size());
    assertEquals(1, result.citations().size());
    assertTrue(result.session().finalAnswer().contains("缺少违约金上限"));
    assertTrue(result.session().finalAnswer().contains("暂时不可用"));
    assertTrue(result.session().finalAnswer().contains("不能据此认定所需资料不存在"));
    assertTrue(result.session().finalAnswer().contains("可验证原文"));
    assertFalse(result.session().finalAnswer().contains("证据不足"));
    assertTrue(result.turns().stream().anyMatch(t -> t.content().equals("DEPENDENCY_UNAVAILABLE")));
    var reopened = scenario.orchestrator.get("user-1", result.session().sessionId());
    assertEquals(result.session(), reopened.session());
    assertEquals(result.citations(), reopened.citations());
    assertThrows(
        IllegalStateException.class,
        () ->
            scenario.orchestrator.feedback(
                "user-1", result.session().sessionId(), "POSITIVE", "", ""));
    var sourceUnavailable =
        new KnowledgeQueryOrchestrator.Response(
            result.session(), result.turns(), result.rounds(), result.evidence(), List.of());
    assertTrue(sourceUnavailable.evidenceChanged());
    assertFalse(sourceUnavailable.displayAnswer().contains("可验证原文"));
  }

  @Test
  void transientFirstDispatchIsFailedWithConsumedBudgetAndNoInventedCitations() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.lane.beforeSearch =
        query -> {
          throw new Ports.TransientDependencyFailure("synthetic unavailable dependency", null);
        };
    var result = scenario.orchestrator.start("user-1", "断线后的验收查询", NO_FILTERS, 8);
    String session = result.session().sessionId();
    assertEquals(SessionStatus.FAILED, result.session().status());
    assertTrue(result.session().evidenceOnly());
    assertTrue(result.session().finalAnswer().contains("查询尚未完成"));
    assertFalse(result.session().finalAnswer().contains("证据不足"));
    assertTrue(result.citations().isEmpty());
    assertTrue(result.rounds().isEmpty());
    assertEquals(1, scenario.repository.listReservations(session).size());
    assertEquals(result.session(), scenario.orchestrator.get("user-1", session).session());
    assertThrows(
        cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied.class,
        () -> scenario.orchestrator.get("other-user", session));
    assertThrows(
        cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied.class,
        () -> scenario.orchestrator.get("user-1", "missing-session"));
    assertThrows(
        IllegalStateException.class, () -> scenario.orchestrator.executeNew("user-1", session, 8));
    assertEquals(1, scenario.repository.listReservations(session).size());
  }

  @Test
  void failedCoverageJudgeDoesNotTreatAnyRetrievedEvidenceAsACompleteAnswer() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    // Both structured attempts fail: the scripted queue is empty.
    var result = scenario.orchestrator.start("user-1", "付款期限和违约金上限", NO_FILTERS, 8);
    assertEquals(SessionStatus.INSUFFICIENT, result.session().status());
    assertTrue(result.session().finalAnswer().contains("证据覆盖判断未完成"));
    assertEquals(null, scenario.model.lastAnswerRole);
    assertEquals(1, result.citations().size());
  }

  @Test
  void coverageDependencyFailureRetainsEvidenceAndStopsAsFailedAfterTwoAttempts() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiencies.add(
        new Ports.TransientDependencyFailure("synthetic outage", null));
    scenario.model.sufficiencies.add(
        new Ports.TransientDependencyFailure("synthetic outage", null));

    var result = scenario.orchestrator.start("user-1", "验收原文覆盖", NO_FILTERS, 8);

    assertEquals(SessionStatus.FAILED, result.session().status());
    assertTrue(result.session().finalAnswer().contains("查询尚未完成"));
    assertFalse(result.session().finalAnswer().contains("证据不足"));
    assertEquals(2, scenario.model.judgeCalls);
    assertEquals(null, scenario.model.lastAnswerRole);
    assertEquals(1, result.citations().size());
    assertEquals(1, scenario.repository.listReservations(result.session().sessionId()).size());
    assertTrue(result.rounds().isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            scenario.orchestrator.feedback(
                "user-1", result.session().sessionId(), "POSITIVE", "", ""));
  }

  @Test
  void coverageRecoversFromOneDependencyFailureWithoutDiscardingTheRound() {
    Scenario scenario = scenario(false);
    scenario.model.understanding("CLEAR", "", List.of());
    scenario.model.sufficiencies.add(
        new Ports.TransientDependencyFailure("synthetic outage", null));
    scenario.model.sufficiency("ANSWER", List.of(), List.of(), "", List.of());

    var result = scenario.orchestrator.start("user-1", "验收原文覆盖", NO_FILTERS, 8);

    assertEquals(SessionStatus.ANSWERED, result.session().status());
    assertEquals(2, scenario.model.judgeCalls);
    assertEquals(1, result.rounds().size());
    assertEquals(1, scenario.repository.listReservations(result.session().sessionId()).size());
  }

  private static Scenario scenario(boolean emptyRetrieval) {
    return scenario(emptyRetrieval, 3, 8);
  }

  private static Scenario scenario(boolean emptyRetrieval, int maxRounds, int maxQueries) {
    RecordingLane lane = new RecordingLane(emptyRetrieval);
    Ports.EvidenceResolver resolver =
        ids -> ids.stream().map(KnowledgeQueryOrchestratorTest::evidence).toList();
    RetrievalApplicationService retrieval =
        new RetrievalApplicationService(
            List.of(lane),
            (query, documents) -> documents.stream().map(ignored -> 1.0d).toList(),
            resolver,
            Quotas.defaults(),
            20,
            400);
    ScriptedModel model = new ScriptedModel();
    AskApplicationService ask = new AskApplicationService(retrieval, model, 24000);
    InMemoryRepository repository = new InMemoryRepository();
    KnowledgeQueryOrchestrator orchestrator =
        new KnowledgeQueryOrchestrator(
            retrieval, ask, model, repository, resolver, maxRounds, maxQueries, 3, 12000);
    return new Scenario(orchestrator, model, lane, repository);
  }

  @Test
  void expiredEvidenceDoesNotRenumberOrDeliverTheOriginalAnswer() {
    InMemoryRepository repository = new InMemoryRepository();
    Ports.EvidenceResolver resolver =
        ids ->
            ids.stream()
                .filter(id -> id.equals("second"))
                .map(KnowledgeQueryOrchestratorTest::evidence)
                .toList();
    RecordingLane lane = new RecordingLane(true);
    RetrievalApplicationService retrieval =
        new RetrievalApplicationService(
            List.of(lane), (query, documents) -> List.of(), resolver, Quotas.defaults(), 20, 400);
    ScriptedModel model = new ScriptedModel();
    KnowledgeQueryOrchestrator orchestrator =
        new KnowledgeQueryOrchestrator(
            retrieval,
            new AskApplicationService(retrieval, model, 24000),
            model,
            repository,
            resolver,
            3,
            8,
            3,
            12000);
    Instant now = Instant.now();
    QuerySession recorded =
        new QuerySession(
            "session",
            "user",
            "原始问题",
            "原始问题",
            NO_FILTERS,
            SessionStatus.ANSWERED,
            "旧事实[1]与另一事实[2]",
            false,
            "",
            List.of(),
            List.of("first", "second"),
            now,
            now);
    repository.createSession(recorded);

    var replay = orchestrator.get("user", "session");

    assertTrue(replay.evidenceChanged());
    assertEquals(2, replay.citations().get(0).index());
    assertEquals("second", replay.citations().get(0).evidence().id());
    assertTrue(replay.displayAnswer().contains("重新查询"));
    assertFalse(replay.displayAnswer().contains("旧事实"));
    assertEquals(recorded, repository.findSession("session").orElseThrow());
  }

  private static Evidence evidence(String id) {
    return new Evidence(
        id,
        "doc-" + id,
        "version-" + id,
        "block-" + id,
        "original/" + id + ".pdf",
        Map.of("page_start", 1),
        "可验证原文 " + id,
        "hash-" + id);
  }

  private record Scenario(
      KnowledgeQueryOrchestrator orchestrator,
      ScriptedModel model,
      RecordingLane lane,
      InMemoryRepository repository) {}

  private static final class RecordingLane implements Ports.RetrievalLane {
    private final boolean empty;
    private final List<String> queries = new ArrayList<>();
    private Consumer<String> beforeSearch = ignored -> {};

    private RecordingLane(boolean empty) {
      this.empty = empty;
    }

    @Override
    public LaneType type() {
      return LaneType.RAW;
    }

    @Override
    public List<Candidate> search(String query, SearchFilters filters, int limit) {
      beforeSearch.accept(query);
      queries.add(query);
      if (empty) return List.of();
      String id = "ev-" + Integer.toUnsignedString(query.hashCode());
      return List.of(
          new Candidate(
              "candidate-" + id,
              "doc-" + id,
              "version-" + id,
              LaneType.RAW,
              "knowledge for " + query,
              1.0d,
              List.of(id),
              Map.of()));
    }
  }

  private static final class ScriptedModel implements Ports.KnowledgeModel {
    private final Queue<KnowledgeQueryOrchestrator.UnderstandingOutput> understandings =
        new ArrayDeque<>();
    private final Queue<Object> sufficiencies = new ArrayDeque<>();
    private Ports.ModelRole lastAnswerRole;
    private String lastAnswerPrompt = "";
    private String lastJudgePrompt = "";
    private int judgeCalls;

    void understanding(String decision, String question, List<String> choices) {
      understandings.add(
          new KnowledgeQueryOrchestrator.UnderstandingOutput(decision, question, choices));
    }

    void sufficiency(
        String decision,
        List<String> missing,
        List<String> nextQueries,
        String clarification,
        List<String> choices) {
      sufficiencies.add(
          new KnowledgeQueryOrchestrator.SufficiencyOutput(
              decision,
              "ANSWER".equals(decision),
              List.of(),
              missing,
              nextQueries,
              clarification,
              choices));
    }

    @Override
    public String identity(Ports.ModelRole role) {
      return role.name();
    }

    @Override
    public String generate(
        Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) {
      lastAnswerRole = role;
      lastAnswerPrompt = userPrompt;
      return "依据可验证原文回答。[1]";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T generateStructured(
        Ports.ModelRole role,
        Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      if (task == Ports.ModelTask.QUERY_UNDERSTANDING) return (T) understandings.remove();
      if (task == Ports.ModelTask.EVIDENCE_SUFFICIENCY) {
        lastJudgePrompt = userPrompt;
        judgeCalls++;
        Object output = sufficiencies.remove();
        if (output instanceof RuntimeException failure) throw failure;
        return (T) output;
      }
      throw new IllegalStateException("unexpected structured task: " + task);
    }
  }

  private static final class InMemoryRepository implements Ports.QuerySessionRepository {
    private final Map<String, QuerySession> sessions = new LinkedHashMap<>();
    private final Map<String, List<QueryTurn>> turns = new LinkedHashMap<>();
    private final Map<String, List<RetrievalRound>> rounds = new LinkedHashMap<>();
    private final Map<String, List<RetrievalReservation>> reservations = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, QueryEvidenceRef>> evidence =
        new LinkedHashMap<>();

    @Override
    public void createSession(QuerySession session) {
      sessions.put(session.sessionId(), session);
    }

    @Override
    public void updateSession(QuerySession session) {
      sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<QuerySession> findSession(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<QuerySession> listSessions(String userId, int limit) {
      return sessions.values().stream()
          .filter(session -> session.userId().equals(userId))
          .limit(limit <= 0 ? 20 : limit)
          .toList();
    }

    @Override
    public void appendTurn(QueryTurn turn) {
      turns.computeIfAbsent(turn.sessionId(), ignored -> new ArrayList<>()).add(turn);
    }

    @Override
    public List<QueryTurn> listTurns(String sessionId) {
      return List.copyOf(turns.getOrDefault(sessionId, List.of()));
    }

    @Override
    public synchronized Optional<RetrievalReservation> reserveRound(
        String userId,
        String sessionId,
        List<String> proposedQueries,
        int maxRounds,
        int maxQueries) {
      QuerySession session = findSession(sessionId).orElseThrow();
      if (!session.userId().equals(userId) || session.status() != SessionStatus.RUNNING)
        throw new IllegalStateException("query session is not runnable");
      RetrievalBudget budget =
          new RetrievalBudget(listReservations(sessionId), maxRounds, maxQueries);
      List<String> queries = budget.freshQueries(proposedQueries, proposedQueries.size());
      if (queries.isEmpty()) return Optional.empty();
      RetrievalReservation reservation =
          new RetrievalReservation(
              UUID.randomUUID().toString(),
              sessionId,
              budget.nextRoundNo(),
              queries,
              Instant.now());
      reservations.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(reservation);
      return Optional.of(reservation);
    }

    @Override
    public List<RetrievalReservation> listReservations(String sessionId) {
      return List.copyOf(reservations.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void saveRound(RetrievalRound round) {
      rounds.computeIfAbsent(round.sessionId(), ignored -> new ArrayList<>()).add(round);
    }

    @Override
    public List<RetrievalRound> listRounds(String sessionId) {
      return List.copyOf(rounds.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void addEvidence(QueryEvidenceRef ref) {
      evidence
          .computeIfAbsent(ref.sessionId(), ignored -> new LinkedHashMap<>())
          .putIfAbsent(ref.evidenceId(), ref);
    }

    @Override
    public List<QueryEvidenceRef> listEvidence(String sessionId) {
      return List.copyOf(evidence.getOrDefault(sessionId, new LinkedHashMap<>()).values());
    }

    @Override
    public void saveFeedback(
        String feedbackId,
        String sessionId,
        String userId,
        String rating,
        String category,
        String comment,
        Instant createdAt) {}
  }
}
