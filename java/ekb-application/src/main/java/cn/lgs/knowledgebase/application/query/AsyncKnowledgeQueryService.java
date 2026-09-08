package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.query.KnowledgeQueryOrchestrator.Response;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Returns a durable query session immediately and executes bounded retrieval on a managed executor.
 */
public final class AsyncKnowledgeQueryService {
  private static final System.Logger LOGGER =
      System.getLogger(AsyncKnowledgeQueryService.class.getName());
  private final KnowledgeQueryOrchestrator orchestrator;
  private final Executor executor;
  private final AtomicInteger pending = new AtomicInteger();

  public AsyncKnowledgeQueryService(KnowledgeQueryOrchestrator orchestrator, Executor executor) {
    if (orchestrator == null) throw new IllegalArgumentException("query orchestrator is required");
    if (executor == null) throw new IllegalArgumentException("query executor is required");
    this.orchestrator = orchestrator;
    this.executor = executor;
  }

  public Response start(String userId, String question, SearchFilters filters, int limit) {
    Response opened = orchestrator.open(userId, question, filters);
    return dispatch(
        userId, opened, () -> orchestrator.executeNew(userId, opened.session().sessionId(), limit));
  }

  public Response continueAfterClarification(
      String userId, String sessionId, String clarification, int limit) {
    Response running = orchestrator.acceptClarification(userId, sessionId, clarification);
    return dispatch(
        userId,
        running,
        () -> orchestrator.executeClarified(userId, sessionId, clarification, limit));
  }

  /** Includes accepted queued queries, without treating history polling as active work. */
  public boolean hasPendingWork() {
    return pending.get() > 0;
  }

  private Response dispatch(String userId, Response opened, Runnable work) {
    pending.incrementAndGet();
    var released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) pending.decrementAndGet();
        };
    try {
      executor.execute(
          () -> {
            try {
              work.run();
            } catch (RuntimeException failure) {
              logFailure(opened.session().sessionId(), failure);
            } finally {
              release.run();
            }
          });
    } catch (RuntimeException rejected) {
      release.run();
      return orchestrator.dispatchUnavailable(userId, opened.session().sessionId());
    }
    return opened;
  }

  public Response get(String userId, String sessionId) {
    return orchestrator.get(userId, sessionId);
  }

  private static void logFailure(String sessionId, RuntimeException failure) {
    // Do not log provider bodies, questions, endpoint query strings or credentials.
    LOGGER.log(
        System.Logger.Level.WARNING,
        "query session {0} failed: {1}; cause: {2}",
        sessionId,
        failure.getClass().getSimpleName(),
        failure.getCause() == null ? "none" : failure.getCause().getClass().getSimpleName());
  }

  public java.util.List<cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession> listSessions(
      String userId, int limit) {
    return orchestrator.listSessions(userId, limit);
  }

  public void feedback(
      String userId, String sessionId, String rating, String category, String comment) {
    orchestrator.feedback(userId, sessionId, rating, category, comment);
  }
}
