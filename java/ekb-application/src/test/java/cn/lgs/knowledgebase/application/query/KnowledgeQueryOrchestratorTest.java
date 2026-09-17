package cn.lgs.knowledgebase.application.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
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

  private static Scenario scenario(boolean emptyRetrieval) {
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
        new KnowledgeQueryOrchestrator(retrieval, ask, model, repository, resolver, 3, 8, 3, 12000);
    return new Scenario(orchestrator, model, lane);
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
      KnowledgeQueryOrchestrator orchestrator, ScriptedModel model, RecordingLane lane) {}

  private static final class RecordingLane implements Ports.RetrievalLane {
    private final boolean empty;
    private final List<String> queries = new ArrayList<>();

    private RecordingLane(boolean empty) {
      this.empty = empty;
    }

    @Override
    public LaneType type() {
      return LaneType.RAW;
    }

    @Override
    public List<Candidate> search(String query, SearchFilters filters, int limit) {
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
    private final Queue<KnowledgeQueryOrchestrator.SufficiencyOutput> sufficiencies =
        new ArrayDeque<>();
    private Ports.ModelRole lastAnswerRole;
    private String lastAnswerPrompt = "";

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
      if (task == Ports.ModelTask.EVIDENCE_SUFFICIENCY) return (T) sufficiencies.remove();
      throw new IllegalStateException("unexpected structured task: " + task);
    }
  }

  private static final class InMemoryRepository implements Ports.QuerySessionRepository {
    private final Map<String, QuerySession> sessions = new LinkedHashMap<>();
    private final Map<String, List<QueryTurn>> turns = new LinkedHashMap<>();
    private final Map<String, List<RetrievalRound>> rounds = new LinkedHashMap<>();
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
