package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.AuditEvent;
import cn.lgs.knowledgebase.application.port.Ports.AuditPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAuditAdapter implements AuditPort {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcAuditAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void record(AuditEvent event) {
    if (event == null || event.action() == null || event.action().isBlank()) {
      throw new IllegalArgumentException("audit action is required");
    }
    String outcome =
        event.outcome() == null || event.outcome().isBlank()
            ? "SUCCESS"
            : event.outcome().trim().toUpperCase();
    if (!outcome.equals("SUCCESS") && !outcome.equals("FAILURE"))
      throw new IllegalArgumentException("invalid audit outcome");
    jdbc.update(
        "INSERT INTO audit_event(event_id,actor_user_id,actor_username,actor_role,action,target_type,target_id,target_name,outcome,request_id,ip_address,user_agent,details_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        nullable(event.actorId()),
        clean(event.actorUsername(), 64),
        clean(event.actorRole(), 32),
        clean(event.action(), 128),
        clean(event.targetType(), 64),
        clean(event.targetId(), 255),
        clean(event.targetName(), 512),
        outcome,
        clean(event.requestId(), 64),
        clean(event.ipAddress(), 64),
        clean(event.userAgent(), 512),
        json(event.details()),
        event.occurredAt() == null
            ? java.sql.Timestamp.from(java.time.Instant.now())
            : java.sql.Timestamp.from(event.occurredAt()));
  }

  private String json(Object value) {
    if (value == null) return null;
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("audit details serialization failed", e);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String clean(String value, int max) {
    String v = value == null ? "" : value.trim();
    return v.length() <= max ? v : v.substring(0, max);
  }
}
