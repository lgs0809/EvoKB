package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.ExternalCallBudget;
import io.milvus.shaded.io.grpc.Context;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Milvus' own shaded gRPC context, isolated from the reusable SDK client's mutable config. */
final class CompilationGrpcCall {
  private static final ScheduledThreadPoolExecutor TIMER = timer();

  private static ScheduledThreadPoolExecutor timer() {
    var timer =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              var thread = new Thread(task, "compilation-grpc-deadline");
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }

  static <T> T call(Supplier<T> action) {
    if (!ExternalCallBudget.active()) return action.get();
    Duration budget = ExternalCallBudget.limit(Duration.ofSeconds(60));
    var context =
        Context.current().withDeadlineAfter(budget.toNanos(), TimeUnit.NANOSECONDS, TIMER);
    Context previous = context.attach();
    try {
      T result = action.get();
      ExternalCallBudget.check();
      return result;
    } catch (RuntimeException failure) {
      ExternalCallBudget.check();
      throw failure;
    } finally {
      context.detach(previous);
      context.cancel(null);
    }
  }

  static void run(Runnable action) {
    call(
        () -> {
          action.run();
          return null;
        });
  }
}
