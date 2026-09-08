package cn.lgs.knowledgebase.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.raw.RawExecutionBudget;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class StageTransactionsTest {
  @Test
  void defaultDefinitionIsRetainedAndOnlyScopedCopiesReceiveTheShortestDeadline() {
    var original =
        new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource()));
    original.setReadOnly(true);
    assertSame(original, StageTransactions.bounded(original));
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    try (var raw =
            RawExecutionBudget.open(
                now.plusMillis(500), () -> fail("setup is not authority"), clock);
        var compilation =
            CompilationExecutionBudget.open(
                now.plusSeconds(3),
                CompilationPublication.direct(writes -> fail("setup cannot publish")),
                clock)) {
      var bounded = StageTransactions.bounded(original);
      assertEquals(1, bounded.getTimeout());
      assertTrue(bounded.isReadOnly());
      assertSame(original.getTransactionManager(), bounded.getTransactionManager());
      assertEquals(-1, original.getTimeout());
    }
    assertSame(original, StageTransactions.bounded(original));
  }

  @Test
  void existingShorterTimeoutCannotBeExpandedByTheStage() {
    var original =
        new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource()));
    original.setTimeout(2);
    var now = Instant.parse("2026-01-01T00:00:00Z");
    try (var raw =
        RawExecutionBudget.open(now.plusSeconds(30), () -> {}, Clock.fixed(now, ZoneOffset.UTC))) {
      assertEquals(2, StageTransactions.bounded(original).getTimeout());
      assertEquals(2, original.getTimeout());
    }
  }
}
