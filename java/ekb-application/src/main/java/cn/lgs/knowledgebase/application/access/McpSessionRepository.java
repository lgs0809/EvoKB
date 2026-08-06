package cn.lgs.knowledgebase.application.access;

/** Protocol session ownership is independent of the user's login session. */
public interface McpSessionRepository {
  void bind(String sessionId, String userId);

  boolean owned(String sessionId, String userId);

  void remove(String sessionId, String userId);
}
