package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.access.KnowledgeAccessService.Denied;
import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.port.Ports.EvidenceResolver;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.port.Ports.QuerySessionRepository;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.query.QueryDomain.Decision;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalReservation;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.query.QueryDomain.SessionStatus;
import cn.lgs.knowledgebase.domain.query.QueryDomain.TurnRole;
import cn.lgs.knowledgebase.domain.query.RetrievalBudget;
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
  private static final System.Logger LOGGER =
      System.getLogger(KnowledgeQueryOrchestrator.class.getName());
  private static final int MAX_MODEL_ATTEMPTS = 2;
  private static final int DEFAULT_CONTEXT_CHARS = 12_000;
  private static final String INSUFFICIENT = "当前知识库证据不足以完整回答这个问题。";

  private final RetrievalApplicationService retrieval;
  private final AskApplicationService ask;
  private final KnowledgeModel model;
  private final QuerySessionRepository repository;
  private final EvidenceResolver evidenceResolver;
  private final cn.lgs.knowledgebase.application.access.KnowledgeAccessService access;
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
    this(
        retrieval,
        ask,
        model,
        repository,
        evidenceResolver,
        maxRetrievalRounds,
        maxTotalQueries,
        maxQueriesPerRound,
        maxEvidenceContextChars,
        null);
  }

  public KnowledgeQueryOrchestrator(
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      KnowledgeModel model,
      QuerySessionRepository repository,
      EvidenceResolver evidenceResolver,
      int maxRetrievalRounds,
      int maxTotalQueries,
      int maxQueriesPerRound,
      int maxEvidenceContextChars,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access) {
    this.access = access;
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
    this.maxRetrievalRounds = RetrievalBudget.roundLimit(maxRetrievalRounds);
    this.maxTotalQueries = maxTotalQueries <= 0 ? 8 : maxTotalQueries;
    this.maxQueriesPerRound = maxQueriesPerRound <= 0 ? 3 : maxQueriesPerRound;
    this.maxEvidenceContextChars =
        maxEvidenceContextChars <= 0 ? DEFAULT_CONTEXT_CHARS : maxEvidenceContextChars;
  }

  private SearchFilters currentFilters(QuerySession session) {
    return access == null ? session.filters() : access.filters(session.userId(), session.filters());
  }

  private void requireCurrentResults(QuerySession session, List<Result> results) {
    if (access != null) access.requireResults(session.userId(), results);
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
      if (TransientDependencyFailure.causedBy(failure))
        return dependencyUnavailable(session, failure);
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
      if (TransientDependencyFailure.causedBy(failure))
        return dependencyUnavailable(session, failure);
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
    return repository.listSessions(requireText(userId, "user id"), limit).stream()
        .map(s -> access == null ? s : access.visibleSession(s))
        .toList();
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
    List<Result> accumulated = restoreEvidence(session);
    List<String> pendingQueries = List.of(effectiveQuestion);
    List<String> missingInformation = List.of();
    if (!previousRounds.isEmpty()) {
      RetrievalRound previous = previousRounds.get(previousRounds.size() - 1);
      missingInformation = previous.missingInformation();
      if (previous.decision() == Decision.CONTINUE_SEARCH) pendingQueries = previous.nextQueries();
    }
    String lastSearchId = "";

    while (true) {
      RetrievalBudget budget =
          new RetrievalBudget(
              repository.listReservations(session.sessionId()),
              maxRetrievalRounds,
              maxTotalQueries);
      if (budget.exhausted()) return stopped(session, accumulated, missingInformation, true);
      List<String> proposed = budget.freshQueries(pendingQueries, maxQueriesPerRound);
      if (proposed.isEmpty()) return stopped(session, accumulated, missingInformation, false);
      // The repository commits this reservation independently before any external retrieval I/O.
      var slot =
          repository.reserveRound(
              session.userId(), session.sessionId(), proposed, maxRetrievalRounds, maxTotalQueries);
      if (slot.isEmpty()) {
        RetrievalBudget current =
            new RetrievalBudget(
                repository.listReservations(session.sessionId()),
                maxRetrievalRounds,
                maxTotalQueries);
        return stopped(session, accumulated, missingInformation, current.exhausted());
      }
      RetrievalReservation reserved = slot.orElseThrow();
      int nextRoundNo = reserved.roundNo();
      List<String> roundQueries = reserved.inputQueries();
      for (String query : roundQueries) {
        var found = retrieval.searchCurrent(query, () -> currentFilters(session), limit);
        lastSearchId = found.searchId();
        mergeResults(accumulated, found.results());
        persistEvidence(session.sessionId(), nextRoundNo, query, found.results());
      }
      RetrievalBudget used =
          new RetrievalBudget(
              repository.listReservations(session.sessionId()),
              maxRetrievalRounds,
              maxTotalQueries);
      int totalQueries = used.usedQueries();
      if (access != null)
        accumulated = new ArrayList<>(access.revalidate(session.userId(), accumulated));
      requireCurrentResults(session, accumulated);
      Sufficiency sufficiency =
          judge(effectiveQuestion, used.previousQueries(), accumulated, nextRoundNo, totalQueries);
      Decision decision = sufficiency.decision();
      List<String> nextQueries =
          decision == Decision.CONTINUE_SEARCH
              ? used.freshQueries(sufficiency.nextQueries(), maxQueriesPerRound)
              : List.of();
      RetrievalRound round =
          new RetrievalRound(
              reserved.reservationId(),
              session.sessionId(),
              nextRoundNo,
              roundQueries,
              decision,
              sufficiency.missingInformation(),
              nextQueries,
              reserved.startedAt(),
              Instant.now());
      repository.saveRound(round);

      if (decision == Decision.ASK_USER) {
        if (sufficiency.clarificationQuestion().isBlank()) {
          return insufficient(session, sufficiency.missingInformation(), accumulated);
        }
        return waitForUser(session, sufficiency.clarificationQuestion(), sufficiency.choices());
      }
      if (decision == Decision.ANSWER) {
        if (accumulated.isEmpty())
          return insufficient(session, sufficiency.missingInformation(), accumulated);
        ModelRole answerRole =
            nextRoundNo > 1 || totalQueries > 1 ? ModelRole.LARGE : ModelRole.SMALL;
        requireCurrentResults(session, accumulated);
        AskApplicationService.Response answered =
            ask.answerFromResults(
                effectiveQuestion, lastSearchId, accumulated, answerRole, maxEvidenceContextChars);
        requireCurrentResults(session, accumulated);
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

      missingInformation = sufficiency.missingInformation();
      pendingQueries = nextQueries;
    }
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
            + ask.evidenceContext(question, results, maxEvidenceContextChars);
    RuntimeException dependencyFailure = null;
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
      } catch (RuntimeException failure) {
        if (TransientDependencyFailure.causedBy(failure)) dependencyFailure = failure;
        // Invalid structured output may fall back to insufficient evidence; a dependency outage
        // must retain its operational failure classification after the same bounded retry.
      }
    }
    if (dependencyFailure != null) throw dependencyFailure;
    return new Sufficiency(
        Decision.CONTINUE_SEARCH,
        List.of("证据覆盖判断未完成，无法确认现有原文是否足以回答全部问题。"),
        List.of(),
        "",
        List.of());
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

  private Response stopped(
      QuerySession session,
      List<Result> results,
      List<String> missingInformation,
      boolean budgetExhausted) {
    List<String> missing = new ArrayList<>(strings(missingInformation));
    missing.add(budgetExhausted ? "已达到本次知识查询的检索预算，现有证据仍不足。" : "没有新的检索线索，现有证据仍不足。");
    return insufficient(session, missing, results);
  }

  private Response dependencyUnavailable(QuerySession session, RuntimeException failure) {
    // Keep operational diagnostics without recording provider bodies, prompts or credentials.
    LOGGER.log(
        System.Logger.Level.WARNING,
        "query session {0} dependency unavailable: {1}; cause: {2}",
        session.sessionId(),
        failure.getClass().getSimpleName(),
        failure.getCause() == null ? "none" : failure.getCause().getClass().getSimpleName());
    List<RetrievalRound> completed = repository.listRounds(session.sessionId());
    List<String> missing = new ArrayList<>();
    if (!completed.isEmpty())
      missing.addAll(completed.get(completed.size() - 1).missingInformation());
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(),
            session.sessionId(),
            TurnRole.SYSTEM_EVENT,
            "DEPENDENCY_UNAVAILABLE",
            Instant.now()));
    String detail = "检索或回答依赖暂时不可用，本次查询尚未完成。已保留现有原文与已用额度，请稍后重新查询。不能据此认定所需资料不存在。";
    if (!missing.isEmpty()) detail += " 已识别的信息缺口：" + String.join("；", missing);
    return evidenceOnlyOutcome(session, SessionStatus.FAILED, detail, restoreEvidence(session));
  }

  private Response insufficient(
      QuerySession session, List<String> missingInformation, List<Result> results) {
    String detail =
        missingInformation == null || missingInformation.isEmpty()
            ? INSUFFICIENT
            : INSUFFICIENT + " 缺少的信息：" + String.join("；", missingInformation);
    return evidenceOnlyOutcome(session, SessionStatus.INSUFFICIENT, detail, results);
  }

  private Response evidenceOnlyOutcome(
      QuerySession session, SessionStatus status, String detail, List<Result> results) {
    requireCurrentResults(session, results);
    List<Evidence> sources = uniqueEvidence(results).values().stream().limit(8).toList();
    List<String> evidenceIds = sources.stream().map(Evidence::id).toList();
    if (!sources.isEmpty()) {
      StringBuilder partial = new StringBuilder(detail);
      partial.append("\n\n已检索到以下可验证原文片段，尚不能据此得出完整结论：\n");
      int remaining = maxEvidenceContextChars;
      for (int i = 0; i < sources.size() && remaining > 0; i++) {
        String content = sources.get(i).content();
        int take = Math.min(content.length(), Math.min(1500, remaining));
        String excerpt = content.substring(0, take);
        if (take < content.length()) excerpt += "（片段已截断）";
        partial
            .append("\n> ")
            .append(excerpt.replace("\n", "\n> "))
            .append(" [")
            .append(i + 1)
            .append("]\n");
        remaining -= take;
      }
      detail = partial.toString();
    }
    QuerySession completed =
        session.withStatus(status, detail, true, "", List.of(), evidenceIds, Instant.now());
    repository.updateSession(completed);
    repository.appendTurn(
        new QueryTurn(
            UUID.randomUUID().toString(),
            session.sessionId(),
            TurnRole.ASSISTANT,
            detail,
            Instant.now()));
    return response(completed);
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
        repository.findSession(requireText(sessionId, "query session id")).orElseThrow(Denied::new);
    if (!session.userId().equals(actor)) throw new Denied();
    return session;
  }

  private void persistEvidence(
      String sessionId, int roundNo, String supportingQuery, List<Result> results) {
    Set<String> seen = new LinkedHashSet<>();
    if (results == null) return;
    repository.addSourceVersions(
        sessionId,
        results.stream()
            .flatMap(
                r ->
                    cn.lgs.knowledgebase.application.access.KnowledgeAccessService.sourceVersions(
                        r.candidate())
                        .stream())
            .distinct()
            .toList());
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        if (seen.add(evidence.id()))
          repository.addEvidence(
              new QueryEvidenceRef(sessionId, evidence.id(), roundNo, supportingQuery));
      }
    }
    repository.addAssetExpansions(sessionId, roundNo, results);
  }

  private List<Result> restoreEvidence(QuerySession session) {
    List<QueryEvidenceRef> refs = repository.listEvidence(session.sessionId());
    if (refs.isEmpty()) return new ArrayList<>();
    List<Evidence> evidence =
        evidenceResolver.resolve(
            refs.stream().map(QueryEvidenceRef::evidenceId).distinct().toList(),
            currentFilters(session));
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

  private static Map<String, Evidence> uniqueEvidence(List<Result> results) {
    Map<String, Evidence> evidence = new LinkedHashMap<>();
    if (results != null)
      for (Result result : results)
        for (Evidence item : result.evidence()) evidence.putIfAbsent(item.id(), item);
    return evidence;
  }

  private Response response(QuerySession session) {
    return response(session, restoreCitations(session));
  }

  private Response response(
      QuerySession session, List<AskApplicationService.Citation> liveCitations) {
    if (access != null && !access.permitsSession(session)) {
      return new Response(
          access.visibleSession(session), List.of(), List.of(), List.of(), List.of(), true);
    }
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
    List<Evidence> evidence =
        evidenceResolver.resolve(session.citationEvidenceIds(), currentFilters(session));
    if (evidence != null) evidence.forEach(item -> resolved.put(item.id(), item));
    List<AskApplicationService.Citation> citations = new ArrayList<>();
    int index = 1;
    for (String id : session.citationEvidenceIds()) {
      Evidence item = resolved.get(id);
      if (item != null) citations.add(new AskApplicationService.Citation(index, "raw", item));
      index++;
    }
    return List.copyOf(citations);
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
      List<AskApplicationService.Citation> citations,
      boolean accessChanged) {
    public Response(
        QuerySession session,
        List<QueryTurn> turns,
        List<RetrievalRound> rounds,
        List<QueryEvidenceRef> evidence,
        List<AskApplicationService.Citation> citations) {
      this(session, turns, rounds, evidence, citations, false);
    }

    public boolean evidenceChanged() {
      if (accessChanged) return true;
      if (session.status() != SessionStatus.ANSWERED
          && session.status() != SessionStatus.INSUFFICIENT
          && session.status() != SessionStatus.FAILED) return false;
      Set<String> expected = new LinkedHashSet<>(session.citationEvidenceIds());
      Set<String> available = new LinkedHashSet<>();
      citations.forEach(citation -> available.add(citation.evidence().id()));
      return !expected.equals(available);
    }

    public String displayAnswer() {
      if (accessChanged)
        return cn.lgs.knowledgebase.application.access.KnowledgeAccessService.UNAVAILABLE;
      return evidenceChanged() ? "本次回答引用的部分资料已更新或失效。请重新查询，以获取当前资料支持的答案。" : session.finalAnswer();
    }
  }

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
