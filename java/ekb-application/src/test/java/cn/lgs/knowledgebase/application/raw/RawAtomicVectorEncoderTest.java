package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingBatch;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RawAtomicVectorEncoderTest {
  static final EncodingIdentity A = new EncodingIdentity("a".repeat(64), "fixture-model", "r1", 3);
  static final EncodingIdentity B = new EncodingIdentity("b".repeat(64), "fixture-model", "r1", 3);

  @Test
  void coldCertifiesNewVectorsThenUsesOnlyCertifiedKeyInIndependentSession() {
    Backend backend = new Backend();
    Cache cache = new Cache();
    cache.rows.put(RawVectorEncoderTest.profile("r1").key("doc", "原文"), List.of(.1f, .2f, .3f));
    var encoder = encoder(backend, cache);
    var first = open(encoder);
    backend.response = Optional.of(A);
    first.encode(List.of("原文", "原文"));
    assertEquals(1, backend.calls.get());
    assertEquals(2, cache.rows.size());
    assertTrue(
        cache.rows.containsKey(RawVectorEncoderTest.profile("r1").certifiedKey("doc", "原文", A)));
    backend.current = Optional.of(A);
    var next = open(encoder);
    next.encode(List.of("原文"));
    assertEquals(1, backend.calls.get());
    assertNotEquals(first.checkpointStrategy(), next.checkpointStrategy());
    assertTrue(next.checkpointStrategy().startsWith("raw-v3-atomic:"));
  }

  @Test
  void evictedOrUnknownIdentityReencodesWithoutRecertifyingOldCache() {
    Backend backend = new Backend();
    backend.current = backend.response = Optional.of(A);
    Cache cache = new Cache();
    var encoder = encoder(backend, cache);
    open(encoder).encode(List.of("原文"));
    backend.current = backend.response = Optional.empty();
    open(encoder).encode(List.of("原文"));
    open(encoder).encode(List.of("原文"));
    assertEquals(3, backend.calls.get());
    assertEquals(1, cache.rows.size());
    assertNotEquals(open(encoder).checkpointStrategy(), open(encoder).checkpointStrategy());
  }

  @Test
  void knownRuntimeChangeRejectsWholeResponseBeforeAnyCacheWrite() {
    Backend backend = new Backend();
    backend.current = Optional.of(A);
    backend.response = Optional.of(B);
    Cache cache = new Cache();
    assertThrows(
        IllegalStateException.class, () -> open(encoder(backend, cache)).encode(List.of("原文")));
    assertTrue(cache.rows.isEmpty());
  }

  @Test
  void runtimeChangesDuringCachedReadRejectsResultAndNeverCallsModel() {
    Backend backend = new Backend();
    backend.current = Optional.of(A);
    Cache cache = new Cache();
    cache.rows.put(
        RawVectorEncoderTest.profile("r1").certifiedKey("doc", "原文", A), List.of(1f, 0f, 0f));
    cache.onFind = () -> backend.current = Optional.of(B);
    assertThrows(
        IllegalStateException.class, () -> open(encoder(backend, cache)).encode(List.of("原文")));
    assertEquals(0, backend.calls.get());
  }

  @Test
  void changedRevisionCannotReuseCertifiedCacheOrOldCheckpoint() {
    Backend backend = new Backend();
    backend.current = Optional.of(new EncodingIdentity("a".repeat(64), "fixture-model", "r2", 3));
    assertThrows(IllegalStateException.class, () -> open(encoder(backend, new Cache())));
    backend.current = Optional.of(A);
    var strict = open(encoder(backend, new Cache()));
    var legacy =
        new RawVectorEncoder(
            backend, () -> RawVectorEncoderTest.profile("r1"), RawVectorCache.none());
    assertNotEquals(strict.checkpointStrategy(), open(legacy).checkpointStrategy());
  }

  @Test
  void uncertifiedModelOutputCannotBeMixedWithCertifiedCachedOutput() {
    Backend backend = new Backend();
    backend.current = Optional.of(A);
    Cache cache = new Cache();
    cache.rows.put(
        RawVectorEncoderTest.profile("r1").certifiedKey("doc", "原文", A), List.of(1f, 0f, 0f));
    assertThrows(
        IllegalStateException.class,
        () -> open(encoder(backend, cache)).encode(List.of("原文", "新内容")));
    assertEquals(1, cache.rows.size());
  }

  @Test
  void separateBatchesCannotMixCachedAndUncertifiedOutputAfterEviction() {
    Backend backend = new Backend();
    backend.current = Optional.of(A);
    Cache cache = new Cache();
    cache.rows.put(
        RawVectorEncoderTest.profile("r1").certifiedKey("doc", "旧正文", A), List.of(1f, 0f, 0f));
    var session = open(encoder(backend, cache));
    session.encode(List.of("旧正文"));
    backend.current = Optional.empty();
    assertThrows(IllegalStateException.class, () -> session.encode(List.of("新正文")));
    assertEquals(1, cache.rows.size());
    assertEquals(1, backend.calls.get());
  }

  @Test
  void separateColdUncertifiedBatchesRemainAllowedButCannotLaterUseCache() {
    Backend backend = new Backend();
    Cache cache = new Cache();
    var session = open(encoder(backend, cache));
    session.encode(List.of("首批"));
    session.encode(List.of("次批"));
    assertTrue(cache.rows.isEmpty());
    assertEquals(2, backend.calls.get());
    backend.current = Optional.of(A);
    cache.rows.put(
        RawVectorEncoderTest.profile("r1").certifiedKey("doc", "第三批", A), List.of(1f, 0f, 0f));
    assertThrows(IllegalStateException.class, () -> session.encode(List.of("第三批")));
    assertEquals(2, backend.calls.get());
  }

  @Test
  void validatesAllCertifiedVectorsBeforeCachingAnyMember() {
    Backend backend = new Backend();
    backend.response = Optional.of(A);
    backend.vectors = List.of(List.of(1f, 0f, 0f), List.of(1f, Float.NaN, 0f));
    Cache cache = new Cache();
    assertThrows(
        IllegalStateException.class, () -> open(encoder(backend, cache)).encode(List.of("一", "二")));
    assertTrue(cache.rows.isEmpty());
  }

  @Test
  void splitBatchRetainsPerInputIdentityAndRejectsChangesAcrossUnknownMembers() {
    var mixed =
        new EmbeddingBatch(
            List.of(List.of(1f), List.of(1f)), List.of(Optional.of(A), Optional.empty()));
    assertTrue(mixed.identity().isEmpty());
    assertEquals(Optional.of(A), mixed.identities().get(0));
    assertThrows(
        IllegalStateException.class,
        () ->
            new EmbeddingBatch(
                List.of(List.of(1f), List.of(1f), List.of(1f)),
                List.of(Optional.of(A), Optional.empty(), Optional.of(B))));
  }

  private static RawVectorEncoder encoder(Backend backend, Cache cache) {
    return new RawVectorEncoder(backend, () -> RawVectorEncoderTest.profile("r1"), cache, true);
  }

  private static RawVectorEncoder.Session open(RawVectorEncoder encoder) {
    return encoder.open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT);
  }

  static class Backend implements EmbeddingPort {
    Optional<EncodingIdentity> current = Optional.empty(), response = Optional.empty();
    AtomicInteger calls = new AtomicInteger();
    List<List<Float>> vectors;

    public int dimensions() {
      return 3;
    }

    public Optional<EncodingIdentity> currentEncodingIdentity() {
      return current;
    }

    public List<List<Float>> embed(List<String> texts) {
      calls.incrementAndGet();
      return vectors != null ? vectors : texts.stream().map(text -> List.of(1f, 0f, 0f)).toList();
    }

    public EmbeddingBatch embedWithIdentity(List<String> texts) {
      return new EmbeddingBatch(embed(texts), response);
    }
  }

  static class Cache implements RawVectorCache {
    Map<String, List<Float>> rows = new HashMap<>();
    Runnable onFind = () -> {};

    public Optional<List<Float>> find(
        String key, String fingerprint, String input, int dimensions) {
      onFind.run();
      return Optional.ofNullable(rows.get(key));
    }

    public void put(
        String key, String fingerprint, String input, int dimensions, List<Float> vector) {
      rows.put(key, vector);
    }
  }
}
