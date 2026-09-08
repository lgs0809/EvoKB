package cn.lgs.knowledgebase.infrastructure.query;

import cn.lgs.knowledgebase.application.port.Ports.QueryExecutionRecoveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Conservative wall-clock guard, independent of inference retries and evidence sufficiency. The
 * two-hour default exceeds the bounded foreground retrieval/model call budget. It also reconciles
 * durable RUNNING rows left behind by host loss, without assuming missing evidence. Terminal writes
 * use a RUNNING-state fence, so a late caller cannot resurrect an expired query.
 */
public final class ExpiredQueryRecovery {
  private final QueryExecutionRecoveryRepository sessions;
  private final Clock clock;
  private final Duration maximumLifetime;

  public ExpiredQueryRecovery(
      QueryExecutionRecoveryRepository sessions, Clock clock, Duration maximumLifetime) {
    this.sessions = Objects.requireNonNull(sessions);
    this.clock = Objects.requireNonNull(clock);
    this.maximumLifetime = Objects.requireNonNull(maximumLifetime);
    if (maximumLifetime.compareTo(Duration.ofHours(2)) < 0)
      throw new IllegalArgumentException(
          "query recovery must preserve at least the two-hour runtime grace");
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 15000)
  public void reconcile() {
    var now = clock.instant();
    sessions.expireRunningSessions(
        now.minus(maximumLifetime),
        now,
        "本次查询执行已中断或超过运行时限，尚未完成回答。已保留检索记录、引用与已用额度；请重新查询。不能据此认定所需资料不存在。");
  }
}
