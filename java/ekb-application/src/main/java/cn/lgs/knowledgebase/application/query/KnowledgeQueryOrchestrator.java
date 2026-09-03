package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports.EvidenceResolver;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.port.Ports.QuerySessionRepository;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.Decision;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import cn.lgs.knowledgebase.domain.query.QueryDomain.TurnRole;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded adaptive retrieval orchestration above the stable single-round retrieval executor. */
public final class KnowledgeQueryOrchestrator {
  private static final int MAX_MODEL_ATTEMPTS = 2;
  private static final int DEFAULT_CONTEXT_CHARS = 12_000;
  private static final String INSUFFICIENT = "当前知识库证据不足以完整回答这个问题。";

  private final RetrievalApplicationService retrieval;
  private final AskApplicationService ask;
  private final KnowledgeModel model;
  private final QuerySessionRepository repository;
  private final EvidenceResolver evidenceResolver;
  private final int maxRetrievalRounds;
  private final int maxTotalQueries;
  private final int maxQueriesPerRound;
  private final int maxEvidenceContextChars;

  public KnowledgeQueryOrchestrator(
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      KnowledgeModel model,
      QuerySessionRepository repository,
      EvidenceResolver evidenceResolver,
      int maxRetrievalRounds,
      int maxTotalQueries,
      int maxQueriesPerRound,
      int maxEvidenceContextChars) {
    if (retrieval == null) throw new IllegalArgumentException("retrieval is required");
    if (ask == null) throw new IllegalArgumentException("ask service is required");
    if (model == null) throw new IllegalArgumentException("knowledge model is required");
    if (repository == null) throw new IllegalArgumentException("query repository is required");
    if (evidenceResolver == null)
      throw new IllegalArgumentException("evidence resolver is required");
    this.retrieval = retrieval;
    this.ask = ask;
    this.model = model;
    this.repository = repository;
    this.evidenceResolver = evidenceResolver;
    this.maxRetrievalRounds = maxRetrievalRounds <= 0 ? 3 : maxRetrievalRounds;
    this.maxTotalQueries = maxTotalQueries <= 0 ? 8 : maxTotalQueries;
    this.maxQueriesPerRound = maxQueriesPerRound <= 0 ? 3 : maxQueriesPerRound;
    this.maxEvidenceContextChars =
        maxEvidenceContextChars <= 0 ? DEFAULT_CONTEXT_CHARS : maxEvidenceContextChars;
  }

  public Response open(String userId, String question, SearchFilters filters) {
    String actor = requireText(userId, "user id");
    String original = requireText(question, "question");
    Instant now = Instant.now();
    QuerySession session =
        new QuerySession(
            UUID.randomUUID().toString(),
            actor,
            original,
            original,
            filters,
            SessionStatus.RUNNING,
            "",
            false,
            "",
            List.of(),
            List.of(),
            now,
            now);
    repository.createSession(session);
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(), session.sessionId(), TurnRole.USER, original, now));
    return response(session);
  }

  public Response executeNew(String userId, String sessionId, int requestedLimit) {
    QuerySession session = ownedSession(userId, sessionId);
    if (session.status() != SessionStatus.RUNNING)
      throw new IllegalStateException("query session is not runnable");
    try {
      Understanding understanding = understand(session.originalQuestion());
      if (understanding.askUser()) {
        return waitForUser(session, understanding.clarificationQuestion(), understanding.choices());
      }
      return run(session, session.originalQuestion(), requestedLimit);
    } catch (RuntimeException failure) {
      markFailed(session, failure);
      throw failure;
    }
  }

  public Response start(String userId, String question, SearchFilters filters, int requestedLimit) {
    Response opened = open(userId, question, filters);
    return executeNew(userId, opened.session().sessionId(), requestedLimit);
  }

  public Response acceptClarification(String userId, String sessionId, String clarification) {
    QuerySession session = ownedSession(userId, sessionId);
    if (session.status() != SessionStatus.WAITING_USER) {
      throw new IllegalStateException("query session is not waiting for user clarification");
    }
    String answer = requireText(clarification, "clarification");
    Instant now = Instant.now();
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(), session.sessionId(), TurnRole.USER, answer, now));
    QuerySession running =
        session.withStatus(SessionStatus.RUNNING, "", false, "", List.of(), List.of(), now);
    repository.updateSession(running);
    return response(running);
  }

  public Response executeClarified(
      String userId, String sessionId, String clarification, int requestedLimit) {
    QuerySession session = ownedSession(userId, sessionId);
    if (session.status() != SessionStatus.RUNNING)
      throw new IllegalStateException("query session is not runnable");
    String answer = requireText(clarification, "clarification");
    String clarifiedQuestion = session.originalQuestion() + "\nUser clarification: " + answer;
    try {
      return run(session, clarifiedQuestion, requestedLimit);
    } catch (RuntimeException failure) {
      markFailed(session, failure);
      throw failure;
    }
  }

  public Response continueAfterClarification(
      String userId, String sessionId, String clarification, int requestedLimit) {
    acceptClarification(userId, sessionId, clarification);
    return executeClarified(userId, sessionId, clarification, requestedLimit);
  }

  public Response get(String userId, String sessionId) {
    return response(ownedSession(userId, sessionId));
  }

  public List<QuerySession> listSessions(String userId, int limit) {
    return repository.listSessions(requireText(userId, "user id"), limit);
  }

  public void feedback(
      String userId, String sessionId, String rating, String category, String comment) {
    QuerySession session = ownedSession(userId, sessionId);
    if (session.status() != SessionStatus.ANSWERED
        && session.status() != SessionStatus.INSUFFICIENT) {
      throw new IllegalStateException("query feedback is only available for completed sessions");
    }
    repository.saveFeedback(
        UUID.randomUUID().toString(),
        session.sessionId(),
        session.userId(),
        requireText(rating, "feedback rating"),
        text(category),
        text(comment),
        Instant.now());
  }

  private Response run(QuerySession session, String effectiveQuestion, int requestedLimit) {
    int limit = requestedLimit <= 0 || requestedLimit > 20 ? 8 : requestedLimit;
    List<RetrievalRound> previousRounds = repository.listRounds(session.sessionId());
    Set<String> executed = new LinkedHashSet<>();
    for (RetrievalRound previous : previousRounds) {
      for (String query : previous.inputQueries()) executed.add(normalizeQuery(query));
    }
    List<Result> accumulated = restoreEvidence(session.sessionId());
    int totalQueries = executed.size();
    int nextRoundNo = previousRounds.size() + 1;
    List<String> pendingQueries =
        previousRounds.isEmpty()
            ? List.of(effectiveQuestion)
            : freshQueries(List.of(effectiveQuestion), executed, maxQueriesPerRound);
    if (pendingQueries.isEmpty() && accumulated.isEmpty())
      pendingQueries = List.of(effectiveQuestion);
    String lastSearchId = "";

    while (nextRoundNo <= maxRetrievalRounds && totalQueries < maxTotalQueries) {
      List<String> roundQueries =
          freshQueries(
              pendingQueries,
              executed,
              Math.min(maxQueriesPerRound, maxTotalQueries - totalQueries));
      if (roundQueries.isEmpty()) break;
      Instant started = Instant.now();
      for (String query : roundQueries) {
        executed.add(normalizeQuery(query));
        totalQueries++;
        var found = retrieval.search(query, session.filters(), limit);
        lastSearchId = found.searchId();
        mergeResults(accumulated, found.results());
        persistEvidence(session.sessionId(), nextRoundNo, query, found.results());
      }

      Sufficiency sufficiency =
          judge(effectiveQuestion, List.copyOf(executed), accumulated, nextRoundNo, totalQueries);
      Decision decision = sufficiency.decision();
      List<String> nextQueries =
          decision == Decision.CONTINUE_SEARCH
              ? freshQueries(
                  sufficiency.nextQueries(),
                  executed,
                  Math.min(maxQueriesPerRound, maxTotalQueries - totalQueries))
              : List.of();
      if (decision == Decision.CONTINUE_SEARCH && nextQueries.isEmpty()) {
        decision = accumulated.isEmpty() ? Decision.CONTINUE_SEARCH : Decision.ANSWER;
      }
      RetrievalRound round =
          new RetrievalRound(
              UUID.randomUUID().toString(),
              session.sessionId(),
              nextRoundNo,
              roundQueries,
              decision,
              sufficiency.missingInformation(),
              nextQueries,
              started,
              Instant.now());
      repository.saveRound(round);

      if (decision == Decision.ASK_USER) {
        if (sufficiency.clarificationQuestion().isBlank()) {
          return insufficient(session, sufficiency.missingInformation());
        }
        return waitForUser(session, sufficiency.clarificationQuestion(), sufficiency.choices());
      }
      if (decision == Decision.ANSWER) {
        if (accumulated.isEmpty()) return insufficient(session, sufficiency.missingInformation());
        ModelRole answerRole =
            nextRoundNo > 1 || totalQueries > 1 ? ModelRole.LARGE : ModelRole.SMALL;
        AskApplicationService.Response answered =
            ask.answerFromResults(effectiveQuestion, lastSearchId, accumulated, answerRole);
        List<String> citationIds =
            answered.citations().stream()
                .map(citation -> citation.evidence().id())
                .distinct()
                .toList();
        QuerySession done =
            session.withStatus(
                SessionStatus.ANSWERED,
                answered.answer(),
                answered.evidenceOnly(),
                "",
                List.of(),
                citationIds,
                Instant.now());
        repository.updateSession(done);
        repository.appendTurn(
            new QueryTurn(
                UUID.randomUUID().toString(),
                session.sessionId(),
                TurnRole.ASSISTANT,
                answered.answer(),
                Instant.now()));
        return response(done, answered.citations());
      }

      pendingQueries = nextQueries;
      nextRoundNo++;
    }
    return insufficient(session, List.of("已达到本次知识查询的检索预算，现有证据仍不足。"));
  }

  private Understanding understand(String question) {
    String system =
        "你是企业知识库的问题理解器。只判断用户意图是否缺少关键用户信息。"
            + "实体、时间范围或分析维度确实无法确定时才 ASK_USER；知识库是否有资料不是澄清理由。"
            + "不要输出思维过程。返回结构化字段 decision(CLEAR|ASK_USER)、clarificationQuestion、choices。";
    String user = "User question: " + question;
    for (int attempt = 0; attempt < MAX_MODEL_ATTEMPTS; attempt++) {
      try {
        UnderstandingOutput output =
            model.generateStructured(
                ModelRole.SMALL,
                ModelTask.QUERY_UNDERSTANDING,
                system,
                user,
                UnderstandingOutput.class);
        if (output != null && "ASK_USER".equalsIgnoreCase(text(output.decision()))) {
          String clarification = text(output.clarificationQuestion());
          if (!clarification.isBlank())
            return new Understanding(true, clarification, strings(output.choices()));
        }
        if (output != null && "CLEAR".equalsIgnoreCase(text(output.decision()))) {
          return new Understanding(false, "", List.of());
        }
      } catch (RuntimeException ignored) {
        // Retry structured output once. If both attempts fail, preserve product availability by
        // starting retrieval with the original question instead of inventing a clarification.
      }
    }
    return new Understanding(false, "", List.of());
  }

  private Sufficiency judge(
      String question,
      List<String> executedQueries,
      List<Result> results,
      int roundNo,
      int totalQueries) {
    String system =
        "你是企业知识库 Evidence Sufficiency Judge。只基于问题和已检索 Raw evidence 判断。"
            + "若证据足够回答，decision=ANSWER；若问题明确但缺少可具体补搜的信息，decision=CONTINUE_SEARCH，"
            + "给出 missingInformation 和最多少量 nextQueries；只有用户意图本身缺信息才 decision=ASK_USER。"
            + "搜不到资料不能伪装成 ASK_USER。不要输出思维过程。";
    String user =
        "Question: "
            + question
            + "\nRound: "
            + roundNo
            + "/"
            + maxRetrievalRounds
            + "\nQueries used: "
            + totalQueries
            + "/"
            + maxTotalQueries
            + "\nExecuted queries: "
            + executedQueries
            + "\n\nEvidence:\n"
            + evidenceContext(results);
    for (int attempt = 0; attempt < MAX_MODEL_ATTEMPTS; attempt++) {
      try {
        SufficiencyOutput output =
            model.generateStructured(
                ModelRole.SMALL,
                ModelTask.EVIDENCE_SUFFICIENCY,
                system,
                user,
                SufficiencyOutput.class);
        Sufficiency parsed = parseSufficiency(output);
        if (parsed != null) return parsed;
      } catch (RuntimeException ignored) {
        // One structured retry; bounded fallback below avoids an unbounded model loop.
      }
    }
    if (!uniqueEvidence(results).isEmpty()) {
      return new Sufficiency(Decision.ANSWER, List.of(), List.of(), "", List.of());
    }
    return new Sufficiency(
        Decision.CONTINUE_SEARCH, List.of("当前轮次未找到可验证 Raw evidence"), List.of(), "", List.of());
  }

  private Sufficiency parseSufficiency(SufficiencyOutput output) {
    if (output == null) return null;
    String value = text(output.decision()).toUpperCase(Locale.ROOT);
    if ("ANSWER".equals(value))
      return new Sufficiency(
          Decision.ANSWER, strings(output.missingInformation()), List.of(), "", List.of());
    if ("CONTINUE_SEARCH".equals(value))
      return new Sufficiency(
          Decision.CONTINUE_SEARCH,
          strings(output.missingInformation()),
          strings(output.nextQueries()),
          "",
          List.of());
    if ("ASK_USER".equals(value))
      return new Sufficiency(
          Decision.ASK_USER,
          strings(output.missingInformation()),
          List.of(),
          text(output.clarificationQuestion()),
          strings(output.choices()));
    return null;
  }

  private Response waitForUser(QuerySession session, String clarification, List<String> choices) {
    QuerySession waiting =
        session.withStatus(
            SessionStatus.WAITING_USER,
            "",
            false,
            clarification,
            choices,
            List.of(),
            Instant.now());
    repository.updateSession(waiting);
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(),
            waiting.sessionId(),
            TurnRole.ASSISTANT,
            clarification,
            Instant.now()));
    return response(waiting);
  }

  private Response insufficient(QuerySession session, List<String> missingInformation) {
    String detail =
        missingInformation == null || missingInformation.isEmpty()
            ? INSUFFICIENT
            : INSUFFICIENT + " 缺少的信息：" + String.join("；", missingInformation);
    List<String> evidenceIds =
        repository.listEvidence(session.sessionId()).stream()
            .map(QueryEvidenceRef::evidenceId)
            .distinct()
            .toList();
    QuerySession insufficient =
        session.withStatus(
            SessionStatus.INSUFFICIENT, detail, true, "", List.of(), evidenceIds, Instant.now());
    repository.updateSession(insufficient);
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(),
            session.sessionId(),
            TurnRole.ASSISTANT,
            detail,
            Instant.now()));
    return response(insufficient);
  }

  private void markFailed(QuerySession session, RuntimeException failure) {
    QuerySession failed =
        session.withStatus(
            SessionStatus.FAILED, "知识查询执行失败。", true, "", List.of(), List.of(), Instant.now());
    repository.updateSession(failed);
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(),
            session.sessionId(),
            TurnRole.SYSTEM_EVENT,
            failure.getClass().getSimpleName(),
            Instant.now()));
  }

  private QuerySession ownedSession(String userId, String sessionId) {
    String actor = requireText(userId, "user id");
    QuerySession session =
        repository
            .findSession(requireText(sessionId, "query session id"))
            .orElseThrow(() -> new IllegalStateException("query session not found"));
    if (!session.userId().equals(actor)) throw new IllegalStateException("query session not found");
    return session;
  }

  private void persistEvidence(
      String sessionId, int roundNo, String supportingQuery, List<Result> results) {
    Set<String> seen = new LinkedHashSet<>();
    if (results == null) return;
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        if (seen.add(evidence.id()))
          repository.addEvidence(
              new QueryEvidenceRef(sessionId, evidence.id(), roundNo, supportingQuery));
      }
    }
  }

  private List<Result> restoreEvidence(String sessionId) {
    List<QueryEvidenceRef> refs = repository.listEvidence(sessionId);
    if (refs.isEmpty()) return new ArrayList<>();
    List<Evidence> evidence =
        evidenceResolver.resolve(
            refs.stream().map(QueryEvidenceRef::evidenceId).distinct().toList());
    List<Result> restored = new ArrayList<>();
    if (evidence == null) return restored;
    for (Evidence item : evidence) {
      Candidate candidate =
          new Candidate(
              "session-evidence-" + item.id(),
              item.documentId(),
              item.versionId(),
              LaneType.RAW,
              item.content(),
              0d,
              List.of(item.id()),
              Map.of("restored_query_session", true));
      restored.add(new Result(candidate, List.of(item)));
    }
    return restored;
  }

  private static void mergeResults(List<Result> accumulated, List<Result> incoming) {
    Set<String> keys = new LinkedHashSet<>();
    for (Result result : accumulated)
      keys.add(result.candidate().lane() + "\u0000" + result.candidate().id());
    if (incoming == null) return;
    for (Result result : incoming) {
      String key = result.candidate().lane() + "\u0000" + result.candidate().id();
      if (keys.add(key)) accumulated.add(result);
    }
  }

  private String evidenceContext(List<Result> results) {
    StringBuilder out = new StringBuilder();
    for (Evidence evidence : uniqueEvidence(results).values()) {
      String block =
          "[EVIDENCE "
              + evidence.id()
              + "] version="
              + evidence.versionId()
              + " location="
              + evidence.location()
              + "\n"
              + evidence.content()
              + "\n\n";
      if (out.length() + block.length() > maxEvidenceContextChars) break;
      out.append(block);
    }
    return out.length() == 0 ? "(none)" : out.toString();
  }

  private static Map<String, Evidence> uniqueEvidence(List<Result> results) {
    Map<String, Evidence> evidence = new LinkedHashMap<>();
    if (results != null)
      for (Result result : results)
        for (Evidence item : result.evidence()) evidence.putIfAbsent(item.id(), item);
    return evidence;
  }

  private static List<String> freshQueries(
      List<String> queries, Set<String> executed, int maximum) {
    if (maximum <= 0 || queries == null || queries.isEmpty()) return List.of();
    List<String> fresh = new ArrayList<>();
    Set<String> local = new LinkedHashSet<>();
    for (String query : queries) {
      String value = text(query);
      if (value.isBlank()) continue;
      String normalized = normalizeQuery(value);
      if (executed.contains(normalized) || !local.add(normalized)) continue;
      fresh.add(value);
      if (fresh.size() >= maximum) break;
    }
    return List.copyOf(fresh);
  }

  private Response response(QuerySession session) {
    return response(session, restoreCitations(session));
  }

  private Response response(
      QuerySession session, List<AskApplicationService.Citation> liveCitations) {
    return new Response(
        session,
        repository.listTurns(session.sessionId()),
        repository.listRounds(session.sessionId()),
        repository.listEvidence(session.sessionId()),
        liveCitations == null ? List.of() : List.copyOf(liveCitations));
  }

  private List<AskApplicationService.Citation> restoreCitations(QuerySession session) {
    if (session.citationEvidenceIds().isEmpty()) return List.of();
    Map<String, Evidence> resolved = new LinkedHashMap<>();
    List<Evidence> evidence = evidenceResolver.resolve(session.citationEvidenceIds());
    if (evidence != null) evidence.forEach(item -> resolved.put(item.id(), item));
    List<AskApplicationService.Citation> citations = new ArrayList<>();
    int index = 1;
    for (String id : session.citationEvidenceIds()) {
      Evidence item = resolved.get(id);
      if (item != null) citations.add(new AskApplicationService.Citation(index++, "raw", item));
    }
    return List.copyOf(citations);
  }

  private static String normalizeQuery(String query) {
    return text(query).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static String requireText(String value, String name) {
    String text = text(value);
    if (text.isBlank()) throw new IllegalArgumentException(name + " is required");
    return text;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static List<String> strings(List<String> values) {
    if (values == null) return List.of();
    return values.stream()
        .map(KnowledgeQueryOrchestrator::text)
        .filter(v -> !v.isBlank())
        .distinct()
        .toList();
  }

  public record Response(
      QuerySession session,
      List<QueryTurn> turns,
      List<RetrievalRound> rounds,
      List<QueryEvidenceRef> evidence,
      List<AskApplicationService.Citation> citations) {}

  public record UnderstandingOutput(
      String decision, String clarificationQuestion, List<String> choices) {}

  public record SufficiencyOutput(
      String decision,
      Boolean answerable,
      List<String> coveredFacts,
      List<String> missingInformation,
      List<String> nextQueries,
      String clarificationQuestion,
      List<String> choices) {}

  private record Understanding(
      boolean askUser, String clarificationQuestion, List<String> choices) {}

  private record Sufficiency(
      Decision decision,
      List<String> missingInformation,
      List<String> nextQueries,
      String clarificationQuestion,
      List<String> choices) {}
}
