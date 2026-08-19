package cn.lgs.knowledgebase.application.raw;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.EmbeddingBatch;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Freezes serving identity per business execution and deduplicates exact encoder inputs. */
public final class RawVectorEncoder {
  private final EmbeddingPort embedder;
  private final Supplier<RawEncodingProfile> profiles;
  private final RawVectorCache cache;
  private final boolean requireAtomicIdentity;

  /** Compatibility for explicitly uncertified fixtures; production uses the strict constructor. */
  public RawVectorEncoder(
      EmbeddingPort embedder, Supplier<RawEncodingProfile> profiles, RawVectorCache cache) {
    this(embedder, profiles, cache, false);
  }

  public RawVectorEncoder(
      EmbeddingPort embedder,
      Supplier<RawEncodingProfile> profiles,
      RawVectorCache cache,
      boolean requireAtomicIdentity) {
    this.embedder = Objects.requireNonNull(embedder);
    this.profiles = Objects.requireNonNull(profiles);
    this.cache = Objects.requireNonNull(cache);
    this.requireAtomicIdentity = requireAtomicIdentity;
  }

  public Session open(String processingDomain, RawTokenCounter tokens, RawChunkProfile chunks) {
    RawEncodingProfile profile = Objects.requireNonNull(profiles.get());
    if (!profile.tokenizerIdentity().equals(tokens.identity()))
      throw new IllegalStateException("Embedding profile and tokenizer identity differ");
    if (profile.dimensions() != embedder.dimensions())
      throw new IllegalStateException("Embedding profile dimension differs from serving adapter");
    return new Session(processingDomain, profile, chunks.identity());
  }

  public final class Session {
    private final String domain;
    private final RawEncodingProfile profile;
    private final String checkpointStrategy;
    private EncodingIdentity pinnedIdentity;
    private boolean hasUsedCertifiedCache;
    private boolean hasUncertifiedOutput;

    private Session(String domain, RawEncodingProfile profile, String chunkIdentity) {
      if (domain == null || domain.isBlank())
        throw new IllegalArgumentException("processing domain required");
      this.domain = domain;
      this.profile = profile;
      if (requireAtomicIdentity) {
        pinnedIdentity = observeIdentity().orElse(null);
        // Unknown serving cannot authenticate old checkpoints. The nonce lives for this
        // execution only; retained rows and the normal retry epoch remain untouched.
        String identity =
            pinnedIdentity == null ? UUID.randomUUID().toString() : pinnedIdentity.profileSha256();
        checkpointStrategy =
            "raw-v3-atomic:"
                + sha256(
                    profile.fingerprint() + ":" + identity + ":" + chunkIdentity + ":" + domain);
      } else
        checkpointStrategy =
            "raw-v2:" + sha256(profile.fingerprint() + ":" + chunkIdentity + ":" + domain);
    }

    public String checkpointStrategy() {
      return checkpointStrategy;
    }

    public void assertCurrent() {
      if (!profile.equals(profiles.get()) || profile.dimensions() != embedder.dimensions())
        throw new IllegalStateException("Embedding identity changed during raw execution");
      if (requireAtomicIdentity) observeIdentity();
    }

    private Optional<EncodingIdentity> observeIdentity() {
      Optional<EncodingIdentity> current = embedder.currentEncodingIdentity();
      current.ifPresent(this::pin);
      return current;
    }

    private void pin(EncodingIdentity identity) {
      profile.certifiedFingerprint(identity);
      if (pinnedIdentity != null && !pinnedIdentity.equals(identity))
        throw new IllegalStateException(
            "ENCODING_PROFILE_MISMATCH: runtime changed during raw execution");
      pinnedIdentity = identity;
    }

    public List<List<Float>> encode(List<String> inputs) {
      assertCurrent();
      // An evicted/nonresident encoder has no proof; configuration cannot substitute for it.
      Optional<EncodingIdentity> current =
          requireAtomicIdentity ? observeIdentity() : Optional.empty();
      Map<String, List<Float>> vectors = new LinkedHashMap<>();
      Map<String, String> pending = new LinkedHashMap<>();
      for (String input : inputs) {
        if (input == null || input.isBlank())
          throw new IllegalArgumentException("Empty embedding input");
        String inputKey = profile.key(domain, input);
        if (vectors.containsKey(inputKey) || pending.containsKey(inputKey)) continue;
        Optional<List<Float>> existing = Optional.empty();
        if (!requireAtomicIdentity || current.isPresent()) {
          String key =
              requireAtomicIdentity ? profile.certifiedKey(domain, input, current.get()) : inputKey;
          String fingerprint =
              requireAtomicIdentity
                  ? profile.certifiedFingerprint(current.get())
                  : profile.fingerprint();
          existing = cache.find(key, fingerprint, sha256(input), profile.dimensions());
        }
        if (existing.isPresent()) {
          if (requireAtomicIdentity && hasUncertifiedOutput)
            throw new IllegalStateException(
                "ENCODING_PROFILE_MISMATCH: cached output cannot mix with an earlier uncertified batch");
          validate(existing.get());
          vectors.put(inputKey, List.copyOf(existing.get()));
          if (requireAtomicIdentity) hasUsedCertifiedCache = true;
        } else pending.put(inputKey, input);
      }
      if (!pending.isEmpty()) {
        EmbeddingBatch batch =
            requireAtomicIdentity
                ? embedder.embedWithIdentity(new ArrayList<>(pending.values()))
                : new EmbeddingBatch(
                    embedder.embed(new ArrayList<>(pending.values())), Optional.empty());
        assertCurrent();
        if (batch.vectors().size() != pending.size())
          throw new IllegalStateException("embedding count mismatch");
        batch.vectors().forEach(this::validate);
        if (requireAtomicIdentity) {
          batch.identity().ifPresent(this::pin);
          if (hasUsedCertifiedCache && batch.identity().isEmpty())
            throw new IllegalStateException(
                "ENCODING_PROFILE_MISMATCH: uncertified batch cannot mix with cached vectors");
          if (batch.identity().isEmpty()) hasUncertifiedOutput = true;
        }
        int i = 0;
        for (var entry : pending.entrySet()) {
          List<Float> vector = List.copyOf(batch.vectors().get(i++));
          // Only the certificate returned with these vectors authorizes a cache write.
          if (!requireAtomicIdentity || batch.identity().isPresent()) {
            String key =
                requireAtomicIdentity
                    ? profile.certifiedKey(domain, entry.getValue(), batch.identity().get())
                    : entry.getKey();
            String fingerprint =
                requireAtomicIdentity
                    ? profile.certifiedFingerprint(batch.identity().get())
                    : profile.fingerprint();
            cache.put(key, fingerprint, sha256(entry.getValue()), profile.dimensions(), vector);
          }
          vectors.put(entry.getKey(), vector);
        }
      }
      assertCurrent();
      return inputs.stream().map(input -> vectors.get(profile.key(domain, input))).toList();
    }

    private void validate(List<Float> vector) {
      if (vector == null
          || vector.size() != profile.dimensions()
          || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value)))
        throw new IllegalStateException(
            "Invalid raw embedding vector dimensions or non-finite values");
    }
  }
}
