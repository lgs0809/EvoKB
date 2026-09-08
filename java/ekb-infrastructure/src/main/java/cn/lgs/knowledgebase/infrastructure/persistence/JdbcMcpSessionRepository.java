package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.access.McpSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcMcpSessionRepository implements McpSessionRepository {
  private final JdbcTemplate jdbc;

  public JdbcMcpSessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void bind(String sessionId, String userId) {
    jdbc.update(
        "INSERT INTO mcp_session_access(session_id,user_id,expires_at) VALUES(?,?,CURRENT_TIMESTAMP+INTERVAL '24 hours')",
        sessionId,
        userId);
  }

  public boolean owned(String sessionId, String userId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM mcp_session_access WHERE session_id=? AND user_id=? AND expires_at>CURRENT_TIMESTAMP)",
            Boolean.class,
            sessionId,
            userId));
  }

  public void remove(String sessionId, String userId) {
    jdbc.update(
        "DELETE FROM mcp_session_access WHERE session_id=? AND user_id=?", sessionId, userId);
  }
}
