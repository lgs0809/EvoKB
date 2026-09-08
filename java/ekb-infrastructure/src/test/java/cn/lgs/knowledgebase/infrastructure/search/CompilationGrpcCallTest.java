package cn.lgs.knowledgebase.infrastructure.search;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationExecutionBudget;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import io.milvus.grpc.BoolResponse;
import io.milvus.grpc.HasCollectionRequest;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.shaded.io.grpc.Context;
import io.milvus.shaded.io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.milvus.shaded.io.grpc.stub.StreamObserver;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompilationGrpcCallTest {
  @Test
  void rawClaimCancelsTheActualSdkRpcAndRestoresItsOriginalContext() throws Exception {
    try (var endpoint = new Endpoint()) {
      assertTrue(endpoint.client.hasCollection(request("fast")));
      long started = System.nanoTime();
      try (var scope =
          cn.lgs.knowledgebase.application.raw.RawExecutionBudget.open(
              Instant.now().plusMillis(200), () -> {}, Clock.systemUTC())) {
        assertTrue(
            assertThrows(
                    IllegalStateException.class,
                    () ->
                        CompilationGrpcCall.call(
                            () -> endpoint.client.hasCollection(request("slow"))))
                .getMessage()
                .contains("RAW_STAGE_EXPIRED"));
      }
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
      assertTrue(endpoint.cancelled.await(2, TimeUnit.SECONDS));
      assertNull(Context.current().getDeadline());
      assertEquals(60000, endpoint.config.getRpcDeadlineMs());
      assertTrue(endpoint.client.hasCollection(request("fast")));
    }
  }

  @Test
  void aRealMilvusSdkRpcIsCancelledAtTheRemainingStageDeadline() throws Exception {
    try (var endpoint = new Endpoint()) {
      assertTrue(endpoint.client.hasCollection(request("fast")));
      long started = System.nanoTime();
      try (var scope =
          CompilationExecutionBudget.open(
              Instant.now().plusMillis(200), gate(), Clock.systemUTC())) {
        assertThrows(
            CompilationExecutionBudget.StageDeadlineExpired.class,
            () -> CompilationGrpcCall.call(() -> endpoint.client.hasCollection(request("slow"))));
      }
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
      assertTrue(
          endpoint.cancelled.await(2, TimeUnit.SECONDS),
          "real gRPC call must be cancelled server-side");
      assertNull(Context.current().getDeadline(), "caller context must be restored");
      assertEquals(
          60000,
          endpoint.config.getRpcDeadlineMs(),
          "shared SDK configuration must not be changed");
      assertTrue(endpoint.client.hasCollection(request("fast")));
    }
  }

  @Test
  void expiredAttemptPreventsTheAdapterFromStartingAnSdkCall() throws Exception {
    try (var endpoint = new Endpoint()) {
      var index =
          new MilvusKnowledgeIndexAdapter(
              endpoint.client,
              "deadline_test",
              new MilvusKnowledgeIndexAdapter.ProjectionAuthority() {
                public int securityLevel(java.util.List<String> versions) {
                  return 0;
                }

                public boolean accessible(
                    java.util.List<String> versions,
                    cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filter) {
                  return true;
                }
              });
      int before = endpoint.calls.get();
      try (var scope =
          CompilationExecutionBudget.open(
              Instant.now().minusSeconds(1), gate(), Clock.systemUTC())) {
        assertThrows(
            CompilationExecutionBudget.StageDeadlineExpired.class, () -> index.ensureIndex(1024));
      }
      assertEquals(before, endpoint.calls.get());
      assertFalse(CompilationExecutionBudget.active());
    }
  }

  @Test
  void successfulAndConcurrentCallsKeepIndependentContextsAndNormalConfiguration()
      throws Exception {
    try (var endpoint = new Endpoint()) {
      assertTrue(endpoint.client.hasCollection(request("fast")));
      var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
      try {
        var timedOut =
            worker.submit(
                () -> {
                  try (var scope =
                      CompilationExecutionBudget.open(
                          Instant.now().plusMillis(200), gate(), Clock.systemUTC())) {
                    try {
                      CompilationGrpcCall.call(
                          () -> endpoint.client.hasCollection(request("slow")));
                      return false;
                    } catch (CompilationExecutionBudget.StageDeadlineExpired expected) {
                      return true;
                    }
                  }
                });
        assertTrue(endpoint.started.await(2, TimeUnit.SECONDS));
        try (var scope =
            CompilationExecutionBudget.open(
                Instant.now().plusSeconds(2), gate(), Clock.systemUTC())) {
          assertTrue(
              CompilationGrpcCall.call(() -> endpoint.client.hasCollection(request("fast"))));
        }
        assertTrue(timedOut.get(2, TimeUnit.SECONDS));
        assertNull(Context.current().getDeadline());
        assertEquals(60000, endpoint.config.getRpcDeadlineMs());
      } finally {
        worker.shutdownNow();
      }
    }
  }

  private static HasCollectionReq request(String collection) {
    return HasCollectionReq.builder().collectionName(collection).build();
  }

  private static CompilationPublication gate() {
    return CompilationPublication.direct(Runnable::run);
  }

  private static final class Endpoint implements AutoCloseable {
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
    final io.milvus.shaded.io.grpc.Server server;
    final ConnectConfig config;
    final MilvusClientV2 client;

    Endpoint() throws Exception {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addService(
                  new MilvusServiceGrpc.MilvusServiceImplBase() {
                    @Override
                    public void connect(
                        io.milvus.grpc.ConnectRequest request,
                        StreamObserver<io.milvus.grpc.ConnectResponse> response) {
                      response.onNext(
                          io.milvus.grpc.ConnectResponse.newBuilder()
                              .setIdentifier(1)
                              .setStatus(
                                  io.milvus.grpc.Status.newBuilder()
                                      .setErrorCode(io.milvus.grpc.ErrorCode.Success))
                              .build());
                      response.onCompleted();
                    }

                    @Override
                    public void hasCollection(
                        HasCollectionRequest request, StreamObserver<BoolResponse> response) {
                      calls.incrementAndGet();
                      if (request.getCollectionName().equals("slow")) {
                        Context.current()
                            .addListener(ignored -> cancelled.countDown(), Runnable::run);
                        started.countDown();
                        return;
                      }
                      response.onNext(
                          BoolResponse.newBuilder()
                              .setValue(true)
                              .setStatus(
                                  io.milvus.grpc.Status.newBuilder()
                                      .setErrorCode(io.milvus.grpc.ErrorCode.Success))
                              .build());
                      response.onCompleted();
                    }
                  })
              .build()
              .start();
      config =
          ConnectConfig.builder()
              .uri("http://127.0.0.1:" + server.getPort())
              .rpcDeadlineMs(60000)
              .build();
      client = new MilvusClientV2(config);
    }

    public void close() throws Exception {
      client.close();
      server.shutdownNow();
      assertTrue(server.awaitTermination(3, TimeUnit.SECONDS));
    }
  }
}
