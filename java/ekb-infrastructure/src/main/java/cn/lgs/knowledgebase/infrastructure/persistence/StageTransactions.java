package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.ExternalCallBudget;
import java.time.Duration;
import org.springframework.transaction.support.TransactionTemplate;

/** Native Spring JDBC statement cancellation, without mutating a shared transaction definition. */
final class StageTransactions {
  private StageTransactions() {}

  static TransactionTemplate bounded(TransactionTemplate original) {
    if (!ExternalCallBudget.active()) return original;
    var remaining = ExternalCallBudget.remaining(Duration.ofMinutes(10));
    // JDBC timeout granularity is one second; exact database publication fences remain unchanged.
    int seconds = (int) Math.max(1, (remaining.toMillis() + 999) / 1000);
    if (original.getTimeout() > 0) seconds = Math.min(seconds, original.getTimeout());
    var bounded = new TransactionTemplate(original.getTransactionManager(), original);
    bounded.setTimeout(seconds);
    return bounded;
  }
}
