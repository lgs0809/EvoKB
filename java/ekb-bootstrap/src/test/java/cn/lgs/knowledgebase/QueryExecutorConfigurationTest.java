package cn.lgs.knowledgebase;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class QueryExecutorConfigurationTest {
  static final class DefaultExecutorConsumer {
    final java.util.concurrent.Executor executor;

    DefaultExecutorConsumer(java.util.concurrent.Executor executor) {
      this.executor = executor;
    }
  }

  static final class QueryExecutorConsumer {
    final java.util.concurrent.Executor executor;

    QueryExecutorConsumer(
        @org.springframework.beans.factory.annotation.Qualifier("knowledgeQueryExecutor")
            java.util.concurrent.Executor executor) {
      this.executor = executor;
    }
  }

  @Test
  void queryPoolRemainsSeparateFromDefaultAsyncWorkWhileExplicitInjectionStillWorks()
      throws Exception {
    var factory =
        EnterpriseConfiguration.class.getDeclaredMethod(
            "knowledgeQueryExecutor", org.springframework.core.env.Environment.class);
    var bean = factory.getAnnotation(org.springframework.context.annotation.Bean.class);
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean(
          "knowledgeQueryExecutor",
          org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class,
          () -> new EnterpriseConfiguration().knowledgeQueryExecutor(environment(1, 1)),
          definition ->
              ((org.springframework.beans.factory.support.AbstractBeanDefinition) definition)
                  .setDefaultCandidate(bean.defaultCandidate()));
      context.registerBean(
          "defaultExecutor",
          org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class,
          org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor::new);
      context.registerBean(DefaultExecutorConsumer.class);
      context.registerBean(QueryExecutorConsumer.class);
      context.refresh();
      assertSame(
          context.getBean("defaultExecutor"),
          context.getBean(DefaultExecutorConsumer.class).executor);
      assertSame(
          context.getBean("knowledgeQueryExecutor"),
          context.getBean(QueryExecutorConsumer.class).executor);
    }
  }

  private StandardEnvironment environment(int workers, int capacity) {
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "query-test",
                Map.of("EKB_QUERY_WORKER_THREADS", workers, "EKB_QUERY_QUEUE_CAPACITY", capacity)));
    return env;
  }

  @Test
  void saturatedQueryPoolRejectsInsteadOfRunningModelWorkOnTheRequestThread() throws Exception {
    var executor = new EnterpriseConfiguration().knowledgeQueryExecutor(environment(1, 1));
    executor.initialize(); // The application delegates this step to Spring's bean lifecycle.
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queuedFinished = new CountDownLatch(1);
    var rejectedWork = new AtomicInteger();
    try {
      executor.execute(
          () -> {
            entered.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      executor.execute(queuedFinished::countDown);
      assertEquals(1, executor.getThreadPoolExecutor().getQueue().size());
      assertThrows(
          RejectedExecutionException.class, () -> executor.execute(rejectedWork::incrementAndGet));
      assertEquals(0, rejectedWork.get());
      assertEquals(1, executor.getMaxPoolSize());
      release.countDown();
      assertTrue(queuedFinished.await(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdown();
    }
  }

  @Test
  void invalidCapacityConfigurationFailsAtInitialization() {
    var configuration = new EnterpriseConfiguration();
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration.knowledgeQueryExecutor(environment(0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration.knowledgeQueryExecutor(environment(1, -1)));
  }
}
