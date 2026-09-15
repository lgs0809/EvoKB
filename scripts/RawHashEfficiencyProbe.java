package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.raw.*;
import cn.lgs.knowledgebase.infrastructure.search.*;
import cn.lgs.knowledgebase.infrastructure.storage.*;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native mechanism benchmark. Only --real uses inference; fixtures validate the runner only. */
public final class RawHashEfficiencyProbe {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final String CONTRACT = "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1";
  static final List<Map<String, Object>> runs = new ArrayList<>();
  static Path output, fixtures, context;
  static RawTokenCounter tokens;
  static RawEncodingProfile profile;
  static long benchmarkStarted;
  static boolean real;
  static int limit;
  static List<Integer> rates;
  static Map<String, JsonNode> frozenPlans = new HashMap<>();

  static final class CountedEmbedding implements EmbeddingPort {
    final EmbeddingPort delegate;
    final List<Map<String, Object>> calls = new ArrayList<>();
    CountedEmbedding(EmbeddingPort delegate) { this.delegate = delegate; }
    public int dimensions() { return delegate.dimensions(); }
    public int preferredBatchSize() { return delegate.preferredBatchSize(); }
    public List<List<Float>> embed(List<String> inputs) {
      long start = System.nanoTime();
      var call = new LinkedHashMap<String, Object>(); calls.add(call);
      call.put("fullInputSha256", inputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList());
      call.put("attemptedInputCount", inputs.size()); call.put("nativeInputTokens", inputs.stream().mapToLong(tokens::count).sum());
      call.put("acceptedVectorCount", 0); call.put("backend", real ? "ACTUAL_WHOLE_RAW_EMBEDDING_PORT" : "EXPLICIT_FIXTURE_VECTORS_RUNNER_ONLY");
      try {
        var vectors = delegate.embed(inputs);
        boolean valid = vectors != null && vectors.size() == inputs.size() && vectors.stream().allMatch(v -> v != null && v.size() == dimensions() && v.stream().allMatch(f -> f != null && Float.isFinite(f)));
        if (!valid) throw new IllegalStateException("Actual encoder response count/dimensions/finiteness invalid");
        call.put("acceptedVectorCount", vectors.size());
        call.put("actualReturnedVectorSha256", vectors.stream().map(v -> cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(v.toString())).toList());
        call.put("actualReturnedVectorFloat32Sha256", vectors.stream().map(RawHashEfficiencyProbe::float32Sha256).toList());
        return vectors;
      } catch (RuntimeException error) { call.put("failure", error.toString()); throw error; }
      finally { call.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000d); }
    }
  }

  static final class Scheme implements AutoCloseable {
    final String name;
    final JdbcTemplate db;
    final JdbcIngestionRepository tasks;
    final JdbcKnowledgeRepository knowledge;
    final MilvusClientV2 client;
    final MilvusKnowledgeIndexAdapter index;
    final RawVectorEncoder encoder;
    final CountedEmbedding embeddings;
    Scheme(String name, String namespace, EmbeddingPort embedding) {
      this.name = name;
      db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_" + name + "_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
      var transactions = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
      tasks = new JdbcIngestionRepository(db, transactions, JSON); knowledge = new JdbcKnowledgeRepository(db, transactions, JSON);
      client = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(namespace).rpcDeadlineMs(30000).build());
      index = new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", new JdbcProjectionAuthority(db)); index.ensureIndex(1024);
      embeddings = new CountedEmbedding(embedding);
      encoder = new RawVectorEncoder(embeddings, () -> profile, "FULL".equals(name) ? RawVectorCache.none() : new JdbcRawVectorCache(db, JSON));
    }
    public void close() { client.close(); }
  }

  public static void main(String[] args) throws Exception {
    output = Path.of(args[0]); fixtures = Path.of(args[1]); context = Path.of(args[4]);
    var manifest = JSON.readTree(fixtures.resolve("manifest.json").toFile());
    var tokenManifest = JSON.readTree(Path.of(args[2]).toFile());
    real = "real".equals(args[5]); limit = Integer.parseInt(args[6]);
    rates = Arrays.stream(args[7].split(",")).map(Integer::parseInt).toList();
    JSON.readTree(Path.of(args[9]).toFile()).path("documents").forEach(d -> frozenPlans.put(d.path("documentId").asText(), d));
    long maxSeconds = Long.parseLong(args[8]);
    var effective = new EffectiveConfiguration("http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra", 60,
        System.getenv("PROBE_EMBEDDING_BASE_URL"), Objects.toString(System.getenv("PROBE_EMBEDDING_API_KEY"), ""), tokenManifest.path("model").asText(), 1024,
        Integer.parseInt(System.getenv("PROBE_EMBEDDING_BATCH_SIZE")), Integer.parseInt(System.getenv("PROBE_EMBEDDING_TIMEOUT")),
        "http://127.0.0.1:8110", "", "Qwen3-VL-Reranker", 60, Instant.EPOCH);
    Store store = new Store() { public EffectiveConfiguration current() { return effective; } public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException(); } };
    EmbeddingPort backend = real ? new OpenAiCompatibleModelServingAdapter(store, JSON).rawEmbeddingPort() : new EmbeddingPort() {
      public int dimensions() { return 1024; }
      public List<List<Float>> embed(List<String> texts) { return texts.stream().map(text -> { var vector = new ArrayList<Float>(Collections.nCopies(1024, 0f)); vector.set(0, 1f); vector.set(1, Integer.parseInt(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(text).substring(0, 4), 16) / 65535f); return (List<Float>)vector; }).toList(); }
    };
    var admin = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());
    for (String suffix : List.of("full", "hash")) if (!admin.listDatabases().getDatabaseNames().contains(args[3] + "_" + suffix)) admin.createDatabase(CreateDatabaseReq.builder().databaseName(args[3] + "_" + suffix).build());
    admin.close();
    var objects = new MinioObjectStoreAdapter("http://127.0.0.1:19400", System.getenv("PROBE_MINIO_USER"), System.getenv("PROBE_MINIO_PASSWORD"), "evokb-target");
    benchmarkStarted = System.nanoTime();
    String atomicSha = Objects.toString(System.getenv("PROBE_ATOMIC_PROFILE_SHA256"), "");
    if (real && !atomicSha.matches("[0-9a-f]{64}")) throw new IllegalStateException("Actual atomic serving profile must be frozen before cache use");
    String servingContract = CONTRACT + (real ? ":benchmark-atomic-profile-sha256=" + atomicSha : ":explicit-fixture-runner-only");
    try (var frozen = new FrozenRawEncodingConfiguration(args[2], JSON, store, servingContract)) {
      tokens = frozen.tokens(); var localProfile = frozen.currentProfile();
      // The relay is transport only. Cache identity binds the actual serving endpoint
      // and the same-admission runtime certificate, rather than its ephemeral port.
      profile = new RawEncodingProfile(localProfile.model(), localProfile.modelRevision(), localProfile.dimensions(),
          localProfile.tokenizerIdentity(), localProfile.preprocessingIdentity(), localProfile.instruction(),
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(System.getenv("PROBE_ACTUAL_SERVING_ENDPOINT")));
      try (var full = new Scheme("FULL", args[3] + "_full", backend); var hash = new Scheme("HASH", args[3] + "_hash", backend)) {
        boolean interrupted = false;
        List<JsonNode> docs = new ArrayList<>(); manifest.path("efficiency").path("documents").forEach(docs::add);
        Set<String> selected = args.length>10 && !args[10].isBlank() ? new HashSet<>(Arrays.asList(args[10].split(","))) : Set.of();
        if (!selected.isEmpty()) docs.removeIf(d -> !selected.contains(d.path("id").asText()));
        if (docs.size()<limit || (!selected.isEmpty() && docs.size()!=limit)) throw new IllegalArgumentException("Explicit development subset differs from requested document count");
        outer: for (int version = -1; version < rates.size(); version++) {
          Integer rate = version < 0 ? null : rates.get(version);
          for (int n = 0; n < limit; n++) {
            var gold = docs.get(n); JsonNode original = gold.path("baseline"), expected = null;
            if (rate != null) {
              for (var candidate : gold.path("versions")) if (candidate.path("bodyUnitChangeRatePercent").asInt() == rate) expected = candidate;
              if (expected == null) throw new IllegalArgumentException("Rate missing from frozen corpus");
              original = expected.path("original");
            }
            List<Scheme> order = n % 2 == 0 ? List.of(full, hash) : List.of(hash, full);
            for (Scheme scheme : order) {
              if ((System.nanoTime() - benchmarkStarted) / 1_000_000_000L >= maxSeconds) { interrupted = true; break outer; }
              boolean success = run(scheme, gold, original, expected, rate, objects, output);
              JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(output + ".checkpoint.json").toFile(), runs);
              if (!success) { interrupted = true; break outer; }
            }
          }
        }
        var checks = new LinkedHashMap<String, Boolean>();
        checks.put("allRequestedDocumentsExecutedEachBaselineAndRateInBothSchemes", !interrupted && runs.size() == limit * (rates.size() + 1) * 2);
        checks.put("allSourceAndNativePublicationChecksPassed", runs.stream().allMatch(r -> "PASS".equals(r.get("status"))));
        checks.put("allCommonSnapshotsProduceIdenticalBusinessInputsAndBlocks", equalCommonInputs());
        var report = new LinkedHashMap<String, Object>(); report.put("checks", checks); report.put("runs", runs);
        report.put("documentLimit", limit); report.put("requestedChangeRates", rates); report.put("requestedNativeSourceRuns", limit * (rates.size() + 1) * 2);
        report.put("explicitDevelopmentSubset", selected);
        report.put("atomicServingProfileSha256", atomicSha);
        report.put("executedSourceRuns", runs.size()); report.put("encodingProfile", profile); report.put("tokenizerIdentity", tokens.identity()); report.put("chunkProfileIdentity", RawChunkProfile.DEFAULT.identity());
        report.put("fullRebuildCacheRows", full.db.queryForObject("SELECT count(*) FROM raw_vector_cache", Long.class));
        report.put("hashCacheRows", hash.db.queryForObject("SELECT count(*) FROM raw_vector_cache", Long.class));
        report.put("backendCallRecords", Map.of("FULL", full.embeddings.calls, "HASH", hash.embeddings.calls));
        report.put("coldBaselineCostIncludedSeparately", true);
        boolean passed = checks.values().stream().allMatch(Boolean.TRUE::equals);
        boolean official = real && limit == 100 && rates.equals(List.of(0, 1, 5, 20, 100));
        report.put("status", passed ? official ? "PASS_REAL_HASH100_NATIVE_COMPARISON" : real ? "PASS_REAL_SUBSET_NOT_HASH100_COMPARISON" : "PASS_FIXTURE_RUNNER_ONLY_NO_INFERENCE_OR_BENEFIT" : "FAIL_OR_INCOMPLETE_HASH_COMPARISON");
        report.put("realModelInference", real); report.put("officialHash100Complete", official && passed);
        report.put("boundary", "Two isolated native schemes use identical frozen originals and metadata, actual full read/parse/chunk/token/SHA/MinIO/PG/owner-fenced Milvus stage+publication+rawComplete+full readback. FULL uses production RawVectorCache.none; HASH uses production JdbcRawVectorCache. No parser cache; exact original reuses its version without violating uq_document_sha but is explicitly reparsed/reindexed for benchmark. Native component benchmark, not ordinary upload API/browser latency or model quality. Fixture mode cannot produce real efficiency claims. Cold baseline and previous rate caches retained; order alternates by document.");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.exit(passed ? 0 : 1);
      }
    }
  }

  static String float32Sha256(List<Float> vector) {
    var bytes = java.nio.ByteBuffer.allocate(vector.size() * Float.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    vector.forEach(bytes::putFloat);
    try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array())); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  static boolean equalCommonInputs() {
    Map<String, List<String>> seen = new HashMap<>();
    for (var run : runs) {
      if (!"PASS".equals(run.get("status"))) continue;
      String key = run.get("fixtureDocumentId") + ":" + run.get("changeRate");
      @SuppressWarnings("unchecked") var hashes = (List<String>) run.get("fullInputSha256");
      var prior = seen.putIfAbsent(key, hashes); if (prior != null && !prior.equals(hashes)) return false;
    }
    return true;
  }

  static boolean run(Scheme scheme, JsonNode gold, JsonNode original, JsonNode expected, Integer rate,
      MinioObjectStoreAdapter objects, Path output) throws Exception {
    var result = new LinkedHashMap<String, Object>(); runs.add(result);
    String fixtureId = gold.path("id").asText(); result.put("fixtureDocumentId", fixtureId); result.put("scheme", scheme.name); result.put("changeRate", rate == null ? "baseline" : rate);
    var checks = new LinkedHashMap<String, Boolean>(); result.put("checks", checks);
    JSON.writeValue(context.toFile(), Map.of("fixtureDocumentId", fixtureId, "scheme", scheme.name, "changeRate", rate == null ? "baseline" : rate));
    long start = System.nanoTime(), readsAt = 0, parseAt = 0, encodedAt = 0, publicationAt = 0;
    int callStart = scheme.embeddings.calls.size(); RawTaskClaim claim = null;
    try {
      Path sourcePath = fixtures.resolve(original.path("path").asText()); var local = new LocalFileSourceAdapter(); var source = local.inspect(sourcePath.toString());
      checks.put("frozenOriginalSizeAndExactBytesSha", source.size() == original.path("sizeBytes").asLong() && source.sha256().equals(original.path("sha256").asText()));
      String batch = UUID.randomUUID().toString(), file = UUID.randomUUID().toString(), logical = "hash100-native " + fixtureId;
      scheme.db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'hash100-native-benchmark',1)", batch);
      scheme.db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,?,?,?,?,?)", file, batch, source.filename(), source.filename(), source.path(), source.sha256(), logical);
      scheme.tasks.queueBatch(batch, Instant.now()); claim = scheme.tasks.claimRaw(file, UUID.randomUUID().toString()).orElseThrow();
      var execution = scheme.tasks.rawExecution(claim);
      var metadata = new Metadata("Hash efficiency development", "Development", List.of(), "", null, "CONTRACT");
      Document doc = scheme.knowledge.findDocumentByLogicalName(logical).orElseGet(() -> new Document(UUID.randomUUID().toString(), logical, source.path()));
      DocumentVersion prior = scheme.knowledge.findVersionBySha(doc.id(), source.sha256()).orElse(null);
      DocumentVersion version = prior == null ? new DocumentVersion(UUID.randomUUID().toString(), doc.id(), scheme.knowledge.nextVersionNo(doc.id()), VersionLifecycle.PENDING, source.filename(), source.mediaType(), source.size(), source.sha256(), "", "", "", "", "") : prior;
      String originalKey = prior == null ? "hash-experiment/" + doc.id() + "/" + version.id() + "/original/" + source.filename() : version.originalObjectKey();
      if (prior == null) {
        var pending = new DocumentVersion(version.id(), version.documentId(), version.versionNo(), version.lifecycle(), version.originalFilename(), version.mediaType(), version.sizeBytes(), version.sha256(), originalKey, "", "", "", ""); version = pending;
        execution.reserveSource(logical, () -> { scheme.knowledge.createPendingVersion(doc, pending, metadata); return pending; });
      } else if (prior.lifecycle() != VersionLifecycle.ACTIVE) throw new IllegalStateException("Frozen benchmark requested an inactive exact original; cannot silently reactivate it");
      execution.bind(doc.id(), version.id()); execution.phase(ProgressStage.STORING);
      if (prior == null) try (var input = local.open(source.path())) { objects.put(originalKey, input, source.size(), source.mediaType()); }
      var stored = objects.get(originalKey); ParsedDocument parsed;
      byte[] originalBytes;
      try (var input = stored.stream()) { originalBytes = input.readAllBytes(); }
      checks.put("minioOriginalShaMatchesFrozenInput", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes)).equals(source.sha256()));
      readsAt = System.nanoTime(); execution.phase(ProgressStage.PARSING);
      parsed = new TextDocumentParserAdapter(JSON).parse(source.filename(), new ByteArrayInputStream(originalBytes));
      var blocks = RawKnowledgeBuilder.rawBlocks(version.id(), parsed.blocks(), tokens);
      var evidence = RawKnowledgeBuilder.evidence(doc.id(), originalKey, blocks);
      var chunks = RawKnowledgeBuilder.smartChunks(doc.id(), version.id(), metadata, blocks, RawChunkProfile.DEFAULT, tokens);
      checks.put("sameThreeBusinessUnitsAllNativeInputsWithin900", chunks.size() == 3 && chunks.stream().allMatch(c -> c.tokenCount() <= 900 && c.tokenCount() == tokens.count(c.content())));
      JsonNode planned = frozenPlans.get(fixtureId).path("baseline");
      if (rate != null) for (var p : frozenPlans.get(fixtureId).path("versions")) if (p.path("bodyUnitChangeRatePercent").asInt() == rate) planned = p;
      List<String> frozenHashes = new ArrayList<>(); planned.path("inputs").forEach(i -> frozenHashes.add(i.path("fullInputSha256").asText()));
      checks.put("actualCompleteInputsMatchFrozenImmutableNativePlan", frozenHashes.equals(chunks.stream().map(c -> cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(c.content())).toList()));
      if (expected != null) checks.put("frozenAmountsConditionsNegationAndTableValuesComplete", chunks.get(0).content().contains(expected.path("gold").path("paymentAmount").asText() + "万元人民币") && chunks.get(0).content().contains("未取得发票前付款义务不发生") && chunks.get(0).content().contains("合法发票且完成书面验收") && chunks.get(1).content().contains(expected.path("gold").path("warrantyMonths").asText() + "个月") && chunks.get(2).content().contains(expected.path("gold").path("tableAmount").asText()) && chunks.get(2).content().contains("合法发票且书面验收，缺一项不得付款"));
      String parsedPrefix = "hash-experiment/" + doc.id() + "/" + version.id() + "/" + claim.scope();
      String markdownKey = parsedPrefix + "/markdown.md", structuredKey = parsedPrefix + "/structured.json";
      byte[] markdown = parsed.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8), structured = new JacksonParsedArtifactEncoder(JSON).structuredArtifact(parsed);
      objects.put(markdownKey, new ByteArrayInputStream(markdown), markdown.length, "text/markdown; charset=utf-8");
      objects.put(structuredKey, new ByteArrayInputStream(structured), structured.length, "application/json");
      String currentVersion = version.id(); execution.fenced(() -> scheme.knowledge.saveParsed(currentVersion, markdownKey, structuredKey, parsed.parserName(), parsed.parserVersion(), blocks, evidence));
      parseAt = System.nanoTime(); execution.phase(ProgressStage.EMBEDDING);
      var inputs = chunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
      var vectors = scheme.encoder.open(doc.id(), tokens, RawChunkProfile.DEFAULT).encode(inputs);
      execution.check(); encodedAt = System.nanoTime();
      var indexed = RawKnowledgeBuilder.toIndexDocuments(chunks, evidence, vectors);
      var physicalIds = scheme.index.stageRaw(doc.id(), version.id(), claim.scope(), indexed);
      execution.phase(ProgressStage.ACTIVATING);
      execution.publish(doc.id(), version.id(), physicalIds, () -> { scheme.index.activateRaw(doc.id(), currentVersion, physicalIds); scheme.knowledge.activateVersion(doc.id(), currentVersion); });
      scheme.tasks.rawComplete(claim, doc.id(), version.id()); publicationAt = System.nanoTime();
      var rows = scheme.index.searchVector(vectors.get(0), new SearchFilters(null, null, null, List.of(version.id())), 100);
      checks.put("nativeCurrentSearchContainsEveryNewPhysicalChunkOnly", rows.size() == chunks.size() && new TreeSet<>(rows.stream().map(c -> c.id()).toList()).equals(new TreeSet<>(physicalIds)));
      checks.put("actualCurrentBlocksAndInputBytesComplete", new TreeSet<>(rows.stream().map(c -> cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(c.knowledge())).toList()).equals(new TreeSet<>(inputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList())));
      checks.put("actualOwnerGuardedTaskSucceeded", scheme.db.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='SUCCEEDED' AND owner_id=? AND execution_epoch=? AND finished_at IS NOT NULL", Long.class, file, claim.ownerId(), claim.epoch()) == 1);
      checks.put("everyRawEvidenceIsActiveAndSameVersion", scheme.db.queryForObject("SELECT count(*) FROM evidence WHERE version_id=? AND active", Long.class, version.id()) == evidence.size());
      int accepted = scheme.embeddings.calls.subList(callStart, scheme.embeddings.calls.size()).stream().mapToInt(c -> ((Number)c.get("acceptedVectorCount")).intValue()).sum();
      int expectedItems = "FULL".equals(scheme.name) || rate == null ? chunks.size() : expected.path("changedBodyUnits").size();
      checks.put("acceptedEncoderInputCountMatchesFrozenSchemeExpectation", accepted == expectedItems);
      result.put("acceptedEncoderInputs", accepted); result.put("attemptedBackendInputs", scheme.embeddings.calls.subList(callStart, scheme.embeddings.calls.size()).stream().mapToInt(c -> ((Number)c.get("attemptedInputCount")).intValue()).sum());
      result.put("actualAcceptedInputTokens", scheme.embeddings.calls.subList(callStart, scheme.embeddings.calls.size()).stream().filter(c -> ((Number)c.get("acceptedVectorCount")).intValue() > 0).mapToLong(c -> ((Number)c.get("nativeInputTokens")).longValue()).sum());
      result.put("actualBackendCalls", List.copyOf(scheme.embeddings.calls.subList(callStart, scheme.embeddings.calls.size())));
      result.put("fullInputSha256", inputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList()); result.put("fullInputTokens", chunks.stream().mapToLong(RawKnowledgeBuilder.ChunkDraft::tokenCount).sum());
      result.put("inputCacheKeys", inputs.stream().map(text -> profile.key(doc.id(), text)).toList());
      result.put("sourceBlockIds", chunks.stream().map(RawKnowledgeBuilder.ChunkDraft::sourceBlockIds).toList()); result.put("physicalMilvusIds", physicalIds);
      result.put("originalSha256", source.sha256()); result.put("originalObjectKey", originalKey); result.put("documentId", doc.id()); result.put("versionId", version.id()); result.put("versionNo", version.versionNo()); result.put("reusedExactOriginalVersion", prior != null); result.put("fileId", file); result.put("claimScope", claim.scope());
      result.put("sourceReadMillis", (readsAt - start) / 1_000_000d); result.put("parseChunkAndArtifactMillis", (parseAt - readsAt) / 1_000_000d); result.put("encodingMillis", (encodedAt - parseAt) / 1_000_000d); result.put("indexAndPublishMillis", (publicationAt - encodedAt) / 1_000_000d);
      result.put("componentEndToEndSearchableMillis", (System.nanoTime() - start) / 1_000_000d);
      result.put("status", checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS" : "FAIL");
    } catch (Throwable error) {
      result.put("status", "FAIL"); result.put("failure", error.toString()); result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000d);
      try (var log = new PrintWriter(output + "." + fixtureId + "." + scheme.name + "." + rate + ".failure.log")) { error.printStackTrace(log); }
      if (claim != null) try { scheme.tasks.rawFailure(claim, "HASH_EXPERIMENT_FAILED", error.toString(), false); } catch (Exception stale) { result.put("failureRecordingRejected", stale.toString()); }
    }
    // Actual work before a later index/publication failure remains a cost, never silently zeroed.
    var caseCalls = List.copyOf(scheme.embeddings.calls.subList(callStart, scheme.embeddings.calls.size()));
    result.put("actualBackendCalls", caseCalls);
    result.put("acceptedEncoderInputs", caseCalls.stream().mapToInt(c -> ((Number)c.get("acceptedVectorCount")).intValue()).sum());
    result.put("attemptedBackendInputs", caseCalls.stream().mapToInt(c -> ((Number)c.get("attemptedInputCount")).intValue()).sum());
    result.put("actualAcceptedInputTokens", caseCalls.stream().filter(c -> ((Number)c.get("acceptedVectorCount")).intValue() > 0).mapToLong(c -> ((Number)c.get("nativeInputTokens")).longValue()).sum());
    System.out.println(fixtureId + " " + scheme.name + " " + rate + " " + result.get("status"));
    return "PASS".equals(result.get("status"));
  }
}
