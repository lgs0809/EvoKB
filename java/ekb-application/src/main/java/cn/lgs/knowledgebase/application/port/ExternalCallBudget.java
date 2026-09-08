package cn.lgs.knowledgebase.application.port;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.raw.RawExecutionBudget;
import java.time.Duration;

/** Shared clients honour either caller's original stage, without changing their normal defaults. */
public final class ExternalCallBudget {
  private ExternalCallBudget() {}

  public static boolean active() {
    return CompilationExecutionBudget.active() || RawExecutionBudget.active();
  }

  public static Duration limit(Duration configured) {
    return RawExecutionBudget.limit(CompilationExecutionBudget.limit(configured));
  }

  /** Remaining time only: callers still retain their original database authority checks. */
  public static Duration remaining(Duration configured) {
    return RawExecutionBudget.remaining(CompilationExecutionBudget.remaining(configured));
  }

  public static void check() {
    CompilationExecutionBudget.check();
    RawExecutionBudget.check();
  }

  public static Runnable capturedCheck() {
    var compilation = CompilationExecutionBudget.capturedCheck();
    var raw = RawExecutionBudget.capturedCheck();
    return () -> {
      compilation.run();
      raw.run();
    };
  }
}
