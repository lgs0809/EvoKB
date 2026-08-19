package cn.lgs.knowledgebase.application.raw;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RawVectorEncoderTest {
  static RawEncodingProfile profile(String revision) {
    return new RawEncodingProfile(
        "fixture-model",
        revision,
        3,
        RawTestFixtures.TOKENS.identity(),
        "complete-text-v1",
        "document prompt",
        "fixture-serving");
  }

  @Test
  void exactInputCacheSurvivesIndependentSessionsAndDeduplicatesBatchWithoutMergingSources() {
    var backend = new Backend();
    var cache = new Cache();
    var profiles = new AtomicReference<>(profile("r1"));
    var encoder = new RawVectorEncoder(backend, profiles::get, cache);
    var first = encoder.open("document-1", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT);
    assertEquals(2, first.encode(List.of("付款60日", "付款60日")).size());
    assertEquals(List.of("付款60日"), backend.inputs);
    encoder
        .open("document-1", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
        .encode(List.of("付款60日"));
    assertEquals(1, backend.inputs.size());
    encoder
        .open("other-document", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
        .encode(List.of("付款60日"));
    assertEquals(2, backend.inputs.size());
  }

  @Test
  void titleInternalWhitespaceAmountsAndPromptAffectActualInputKey() {
    var backend = new Backend();
    var cache = new Cache();
    var encoder = new RawVectorEncoder(backend, () -> profile("r1"), cache);
    encoder
        .open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
        .encode(List.of("标题A\n金额9 00", "标题B\n金额9 00", "标题A\n金额900", "标题A\n金额9\t00"));
    assertEquals(4, backend.inputs.size());
    assertEquals(4, cache.rows.size());
    assertNotEquals(
        profile("r1").key("doc", "60"),
        new RawEncodingProfile(
                "fixture-model",
                "r1",
                3,
                RawTestFixtures.TOKENS.identity(),
                "complete-text-v1",
                "different prompt",
                "fixture-serving")
            .key("doc", "60"));
  }

  @Test
  void everyServingIdentityDimensionChangesTheCacheFingerprint() {
    var original = profile("r1");
    List<RawEncodingProfile> changed =
        List.of(
            new RawEncodingProfile(
                "other-model",
                "r1",
                3,
                original.tokenizerIdentity(),
                original.preprocessingIdentity(),
                original.instruction(),
                original.endpointIdentity()),
            profile("r2"),
            new RawEncodingProfile(
                original.model(),
                "r1",
                4,
                original.tokenizerIdentity(),
                original.preprocessingIdentity(),
                original.instruction(),
                original.endpointIdentity()),
            new RawEncodingProfile(
                original.model(),
                "r1",
                3,
                "tokenizer-r2",
                original.preprocessingIdentity(),
                original.instruction(),
                original.endpointIdentity()),
            new RawEncodingProfile(
                original.model(),
                "r1",
                3,
                original.tokenizerIdentity(),
                "preprocess-r2",
                original.instruction(),
                original.endpointIdentity()),
            new RawEncodingProfile(
                original.model(),
                "r1",
                3,
                original.tokenizerIdentity(),
                original.preprocessingIdentity(),
                "changed instruction",
                original.endpointIdentity()),
            new RawEncodingProfile(
                original.model(),
                "r1",
                3,
                original.tokenizerIdentity(),
                original.preprocessingIdentity(),
                original.instruction(),
                "different-serving"));
    assertEquals(7, changed.stream().map(RawEncodingProfile::fingerprint).distinct().count());
    changed.forEach(value -> assertNotEquals(original.fingerprint(), value.fingerprint()));
  }

  @Test
  void revisionChangeReencodesAndInvalidatesCheckpointEvenIfBodyUnchanged() {
    var backend = new Backend();
    var cache = new Cache();
    var profiles = new AtomicReference<>(profile("r1"));
    var encoder = new RawVectorEncoder(backend, profiles::get, cache);
    var old = encoder.open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT);
    old.encode(List.of("条件不变"));
    profiles.set(profile("r2"));
    var next = encoder.open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT);
    next.encode(List.of("条件不变"));
    assertEquals(2, backend.inputs.size());
    assertNotEquals(old.checkpointStrategy(), next.checkpointStrategy());
    assertThrows(IllegalStateException.class, old::assertCurrent);
  }

  @Test
  void configurationChangeWhileHttpRunsNeverCommitsCache() {
    var backend = new Backend();
    var cache = new Cache();
    var profiles = new AtomicReference<>(profile("r1"));
    backend.onEncode = () -> profiles.set(profile("r2"));
    var encoder = new RawVectorEncoder(backend, profiles::get, cache);
    assertThrows(
        IllegalStateException.class,
        () ->
            encoder
                .open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
                .encode(List.of("原文")));
    assertTrue(cache.rows.isEmpty());
  }

  @Test
  void validatesEntireModelBatchBeforeRecordingAnyVector() {
    var backend = new Backend();
    backend.vectors = List.of(List.of(.1f, .2f, .3f), List.of(Float.NaN, .2f, .3f));
    var cache = new Cache();
    var encoder = new RawVectorEncoder(backend, () -> profile("r1"), cache);
    assertThrows(
        IllegalStateException.class,
        () ->
            encoder
                .open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
                .encode(List.of("正文A", "正文B")));
    assertTrue(cache.rows.isEmpty());
  }

  @Test
  void rejectsWrongDimensionsCountsAndMalformedCacheWithoutSilentFallback() {
    for (List<List<Float>> invalid :
        List.of(
            List.of(List.of(.1f)),
            List.of(List.of(.1f, .2f, Float.POSITIVE_INFINITY)),
            List.<List<Float>>of())) {
      var backend = new Backend();
      backend.vectors = invalid;
      var cache = new Cache();
      var encoder = new RawVectorEncoder(backend, () -> profile("r1"), cache);
      assertThrows(
          IllegalStateException.class,
          () ->
              encoder
                  .open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
                  .encode(List.of("原文")));
      assertTrue(cache.rows.isEmpty());
    }
    var backend = new Backend();
    var cache = new Cache();
    cache.rows.put(profile("r1").key("doc", "原文"), List.of(Float.NaN, .2f, .3f));
    var encoder = new RawVectorEncoder(backend, () -> profile("r1"), cache);
    assertThrows(
        IllegalStateException.class,
        () ->
            encoder
                .open("doc", RawTestFixtures.TOKENS, RawChunkProfile.DEFAULT)
                .encode(List.of("原文")));
    assertTrue(backend.inputs.isEmpty());
  }

  private static class Backend implements EmbeddingPort {
    final List<String> inputs = new ArrayList<>();
    List<List<Float>> vectors;
    Runnable onEncode = () -> {};

    public int dimensions() {
      return 3;
    }

    public List<List<Float>> embed(List<String> values) {
      inputs.addAll(values);
      onEncode.run();
      return vectors == null
          ? values.stream().map(value -> List.of(.1f, .2f, .3f)).toList()
          : vectors;
    }
  }

  private static class Cache implements RawVectorCache {
    final Map<String, List<Float>> rows = new HashMap<>();

    public Optional<List<Float>> find(String key, String profile, String input, int dimensions) {
      return Optional.ofNullable(rows.get(key));
    }

    public void put(String key, String profile, String input, int dimensions, List<Float> vector) {
      rows.put(key, vector);
    }
  }
}
