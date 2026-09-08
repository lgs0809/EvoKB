package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.AuditEvent;
import cn.lgs.knowledgebase.application.port.Ports.AuditPort;
import cn.lgs.knowledgebase.application.port.Ports.TransientDependencyFailure;
import java.util.LinkedHashMap;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing audit ledger, independently committed receipts, not an artifact publication. */
public final class JdbcCompilationModelAudit implements AuditPort {
  private final JdbcTemplate jdbc;
  private final AuditPort audit;
  private final TransactionTemplate transactions;

  public JdbcCompilationModelAudit(
      JdbcTemplate jdbc, TransactionTemplate original, AuditPort audit) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
    this.audit = java.util.Objects.requireNonNull(audit);
    transactions = new TransactionTemplate(original.getTransactionManager(), original);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setTimeout(5);
  }

  @Override
  public void record(AuditEvent event) {
    if (event == null
        || !"compile_task".equals(event.targetType())
        || !event.targetId().equals(event.details().get("taskId"))
        || !(event.details().get("executionEpoch") instanceof Number epoch)
        || epoch.longValue() < 1)
      throw new IllegalArgumentException("original compilation epoch required for model receipt");
    try {
      transactions.executeWithoutResult(
          ignored -> {
            Integer count =
                jdbc.queryForObject(
                    "SELECT count(*) FROM compile_task_input WHERE task_id=? AND execution_epoch=?",
                    Integer.class,
                    event.targetId(),
                    epoch.longValue());
            if (count == null || count < 1)
              throw new IllegalStateException("model receipt lacks captured source inputs");
            var details = new LinkedHashMap<String, Object>(event.details());
            details.put("inputSourceCount", count);
            audit.record(
                new AuditEvent(
                    event.actorId(),
                    event.actorUsername(),
                    event.actorRole(),
                    event.action(),
                    event.targetType(),
                    event.targetId(),
                    event.targetName(),
                    event.outcome(),
                    event.requestId(),
                    event.ipAddress(),
                    event.userAgent(),
                    details,
                    event.occurredAt()));
          });
    } catch (TransientDataAccessException failure) {
      throw new TransientDependencyFailure(
          "compilation model receipt storage temporarily unavailable", failure);
    }
  }
}
