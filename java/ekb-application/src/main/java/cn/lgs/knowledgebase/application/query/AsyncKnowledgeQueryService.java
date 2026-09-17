package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.query.KnowledgeQueryOrchestrator.Response;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import java.util.concurrent.Executor;

/**
 * Returns a durable query session immediately and executes bounded retrieval on a managed executor.
 */
public final class AsyncKnowledgeQueryService {
  private final KnowledgeQueryOrchestrator orchestrator;
  private final Executor executor;

  public AsyncKnowledgeQueryService(KnowledgeQueryOrchestrator orchestrator, Executor executor) {
    if (orchestrator == null) throw new IllegalArgumentException("query orchestrator is required");
    if (executor == null) throw new IllegalArgumentException("query executor is required");
    this.orchestrator = orchestrator;
    this.executor = executor;
  }

  public Response start(String userId, String question, SearchFilters filters, int limit) {
    Response opened = orchestrator.open(userId, question, filters);
    executor.execute(
        () -> {
          try {
            orchestrator.executeNew(userId, opened.session().sessionId(), limit);
          } catch (RuntimeException ignored) {
            // The orchestrator persists FAILED before propagating. The async boundary must not
            // terminate the worker thread or turn a background failure into an HTTP failure.
          }
        });
    return opened;
  }

  public Response continueAfterClarification(
      String userId, String sessionId, String clarification, int limit) {
    Response running = orchestrator.acceptClarification(userId, sessionId, clarification);
    executor.execute(
        () -> {
          try {
            orchestrator.executeClarified(userId, sessionId, clarification, limit);
          } catch (RuntimeException ignored) {
            // FAILED is persisted by the orchestrator and can be recovered through session polling.
          }
        });
    return running;
  }

  public Response get(String userId, String sessionId) {
    return orchestrator.get(userId, sessionId);
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
