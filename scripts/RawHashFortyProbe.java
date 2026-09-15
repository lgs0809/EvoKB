package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.raw.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcIngestionRepository;
import cn.lgs.knowledgebase.infrastructure.search.*;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Forty independent frozen mechanisms. Vectors are fixtures, never claimed as model inference. */
public final class RawHashFortyProbe {
  static final ObjectMapper JSON = new ObjectMapper();
  static JdbcTemplate db;
  static JdbcIngestionRepository tasks;
  static MilvusKnowledgeIndexAdapter index;
  static Path fixtures;
  static RawTokenCounter tokens;
  static RawEncodingProfile baselineProfile;
  static TextDocumentParserAdapter parser;
  static JdbcRawVectorCache cache;

  static final class FixtureEncoder implements EmbeddingPort {
    final AtomicReference<RawEncodingProfile> active;
    String mode = "VALID";
    int encodedItems;
    final List<Map<String, Object>> calls = new ArrayList<>();
    FixtureEncoder(AtomicReference<RawEncodingProfile> active) { this.active = active; }
    public int dimensions() { return active.get().dimensions(); }
    public List<List<Float>> embed(List<String> inputs) {
      encodedItems += inputs.size();
      calls.add(Map.of("fullInputHashes", inputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList(),
          "inputCount", inputs.size(), "dimensions", dimensions(), "mode", mode));
      List<List<Float>> result = new ArrayList<>();
      for (String input : inputs) result.add(vector(input, dimensions()));
      if ("missing_vector_count".equals(mode)) return List.of();
      if ("wrong_dimensions".equals(mode)) return inputs.stream().map(v -> vector(v, dimensions() - 1)).toList();
      if ("null_vector".equals(mode)) result.set(0, null);
      if ("nan_vector".equals(mode)) result.get(0).set(0, Float.NaN);
      if ("infinite_vector".equals(mode)) result.get(0).set(0, Float.POSITIVE_INFINITY);
      if ("whole_batch_invalid_tail".equals(mode)) result.get(result.size() - 1).set(0, Float.NaN);
      return result;
    }
  }

  static List<Float> vector(String input, int dimensions) {
    var values = new ArrayList<Float>(Collections.nCopies(dimensions, 0f)); values.set(0, 1f);
    if (dimensions > 1) values.set(1, Integer.parseInt(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(input).substring(0, 4), 16) / 65535f);
    return values;
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]); fixtures = Path.of(args[1]);
    var manifest = JSON.readTree(fixtures.resolve("manifest.json").toFile());
    var tokenManifest = JSON.readTree(Path.of(args[2]).toFile());
    db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
    tasks = new JdbcIngestionRepository(db, new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())), JSON);
    cache = new JdbcRawVectorCache(db, JSON); parser = new TextDocumentParserAdapter(JSON);
    var effective = new EffectiveConfiguration("http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra", 60,
        "http://127.0.0.1:8110", "", tokenManifest.path("model").asText(), 1024, 8, 60,
        "http://127.0.0.1:8110", "", "Qwen3-VL-Reranker", 60, Instant.EPOCH);
    Store store = new Store() {
      public EffectiveConfiguration current() { return effective; }
      public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException(); }
    };
    var admin = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());
    if (!admin.listDatabases().getDatabaseNames().contains(args[3])) admin.createDatabase(CreateDatabaseReq.builder().databaseName(args[3]).build());
    admin.close();
    var client = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(args[3]).rpcDeadlineMs(30000).build());
    List<Map<String, Object>> results = new ArrayList<>();
    try (var frozen = new FrozenRawEncodingConfiguration(args[2], JSON, store,
        "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
      tokens = frozen.tokens(); baselineProfile = frozen.currentProfile();
      index = new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", new JdbcProjectionAuthority(db)); index.ensureIndex(1024);
      for (JsonNode gold : manifest.path("cases")) {
        var checks = new LinkedHashMap<String, Boolean>(); var result = new LinkedHashMap<String, Object>();
        String id = gold.path("id").asText(), scenario = gold.path("scenario").asText();
        result.put("caseId", id); result.put("split", gold.path("split").asText()); result.put("scenario", scenario);
        long start = System.nanoTime();
        try {
          var active = new AtomicReference<>(baselineProfile); var fixtureEncoder = new FixtureEncoder(active);
          var encoder = new RawVectorEncoder(fixtureEncoder, active::get, cache);
          var before = chunks(gold.path("before"), id, id + "-v1", checks);
          var after = chunks(gold.path("after"), id, id + "-v2", checks);
          List<String> firstInputs = before.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
          List<String> nextInputs = after.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
          checks.put("authoredBaselineHasThreeIndependentStructureBlocks", before.size() == gold.path("expectedBaselineChunks").asInt());
          checks.put("actualNativeRenderedTokensRemainWithin900", before.stream().allMatch(c -> c.tokenCount() <= 900 && c.tokenCount() == tokens.count(c.content()))
              && after.stream().allMatch(c -> c.tokenCount() <= 900 && c.tokenCount() == tokens.count(c.content())));
          checks.put("frozenRequiredTableConditionsAndNegativeMeaningPreserved", after.stream().anyMatch(c -> c.content().contains("合法发票且书面验收，缺一项不得付款")));
          long countBefore = cacheRows();
          String group = gold.path("group").asText(); int delta;
          if ("CACHE_VALIDATION".equals(group)) {
            validateCase(scenario, id, firstInputs, encoder, fixtureEncoder, active, checks, result);
            delta = 0;
          } else {
            var session = encoder.open(id, tokens, RawChunkProfile.DEFAULT);
            var vectors = session.encode(firstInputs); int baselineItems = fixtureEncoder.encodedItems;
            checks.put("coldBaselineActuallyEncodedAllThreeFixtureInputs", baselineItems == 3 && vectors.size() == 3);
            if ("IDENTITY".equals(group)) {
              active.set(changedProfile(scenario, baselineProfile));
              RawTokenCounter identityTokens = new RawTokenCounter() {
                public int count(String text) { return tokens.count(text); }
                public String identity() { return active.get().tokenizerIdentity(); }
              };
              String domain = "processing_domain".equals(scenario) ? id + "-separate-domain" : id;
              var next = encoder.open(domain, identityTokens, RawChunkProfile.DEFAULT); next.encode(nextInputs);
              checks.put("exactEncodingProfileOrDomainCannotReusePriorIdentity", !session.checkpointStrategy().equals(next.checkpointStrategy()));
            } else if ("SOURCE_VISIBILITY".equals(group)) {
              sourceCase(gold, before, after, vectors, encoder, fixtureEncoder, checks, result);
            } else {
              var actual = session.encode(nextInputs);
              checks.put("orderedVectorResultsResolveEveryActualInput", actual.size() == nextInputs.size()
                  && java.util.stream.IntStream.range(0, actual.size()).allMatch(i -> actual.get(i).equals(vector(nextInputs.get(i), 1024))));
              checks.put("newVersionKeepsDistinctSourceBlockIdentityDespiteCacheReuse", before.stream().flatMap(c -> c.sourceBlockIds().stream()).noneMatch(
                  source -> after.stream().flatMap(c -> c.sourceBlockIds().stream()).anyMatch(source::equals)));
            }
            delta = fixtureEncoder.encodedItems - baselineItems;
          }
          checks.put("afterEncodingCountMatchesFrozenIndependentCaseExpectation", delta == gold.path("expectedAfterEncodedInputs").asInt());
          result.put("actualAdditionalEncodedFixtureInputs", delta);
          result.put("additionalInputCountDefinition", "Validated accepted outputs; invalid response attempts are separately retained, never counted as success");
          result.put("attemptedFixtureBackendInputCount", fixtureEncoder.encodedItems);
          result.put("actualFixtureBackendCalls", fixtureEncoder.calls);
          result.put("cacheRowDelta", cacheRows() - countBefore); result.put("beforeInputHashes", firstInputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList());
          result.put("afterInputHashes", nextInputs.stream().map(cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain::sha256).toList());
          result.put("finalSourceChunkIds", after.stream().map(c -> c.id()).toList());
        } catch (Throwable error) {
          checks.put("nativeMechanismExecutionCompleted", false); result.put("failure", error.toString());
          try (var log = new java.io.PrintWriter(output + "." + id + ".failure.log")) { error.printStackTrace(log); }
        }
        result.put("checks", checks); result.put("status", checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS" : "FAIL");
        result.put("componentElapsedMillis", (System.nanoTime() - start) / 1_000_000d); results.add(result);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(output + ".checkpoint.json").toFile(), results);
        System.out.println(id + " " + result.get("status"));
      }
      Map<String, Object> report = new LinkedHashMap<>();
      int expectedCount = manifest.path("mechanismCaseCount").asInt(40);
      String prefix = expectedCount == 40 ? "HASH40_NATIVE_MECHANISMS" : "HASH_SUPPLEMENT_NATIVE_MECHANISMS";
      report.put("status", results.size() == expectedCount && results.stream().allMatch(r -> "PASS".equals(r.get("status"))) ? "PASS_" + prefix : "FAIL_" + prefix);
      report.put("cases", results); report.put("caseCount", results.size());
      report.put("passedCases", results.stream().filter(r -> "PASS".equals(r.get("status"))).count());
      report.put("tokenizerIdentity", tokens.identity()); report.put("nativeCacheRows", cacheRows());
      report.put("publicationRows", db.queryForList("SELECT document_id,version_id,producer_scope,execution_epoch,acl_version FROM raw_publication ORDER BY version_id"));
      report.put("retainedFailureRows", db.queryForList("SELECT file_id,attempt,error_code,outcome FROM raw_ingestion_failure"));
      report.put("boundary", "40 frozen PG/cache/native tokenizer/parser/chunk/Milvus publication mechanisms, 24 development and 16 held out. Explicit synthetic finite vectors and profile-change fixtures; no Qwen call, real model/tokenizer migration, model quality, whole API/browser, or efficiency benefit claimed.");
      JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
    } finally { client.close(); }
  }

  static List<RawKnowledgeBuilder.ChunkDraft> chunks(JsonNode original, String doc, String version, Map<String, Boolean> checks) throws Exception {
    Path path = fixtures.resolve(original.path("path").asText()); byte[] bytes = Files.readAllBytes(path);
    checks.merge("originalFilesStillMatchFrozenBeforeAndAfterGold", bytes.length == original.path("sizeBytes").asInt()
        && cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).equals(original.path("sha256").asText()), (a, b) -> a && b);
    var parsed = parser.parse(path.getFileName().toString(), new java.io.ByteArrayInputStream(bytes));
    return RawKnowledgeBuilder.smartChunks(doc, version, metadata(doc), RawKnowledgeBuilder.rawBlocks(version, parsed.blocks(), tokens), RawChunkProfile.DEFAULT, tokens);
  }
  static Metadata metadata(String id) { return new Metadata("hash40 " + id, "isolated mechanism", List.of(), "", null, "CONTRACT"); }
  static long cacheRows() { return db.queryForObject("SELECT count(*) FROM raw_vector_cache", Long.class); }

  static RawEncodingProfile changedProfile(String scenario, RawEncodingProfile p) {
    return new RawEncodingProfile("model_name".equals(scenario) ? "explicit-synthetic-other-model" : p.model(),
        "model_revision".equals(scenario) ? "explicit-synthetic-other-revision" : p.modelRevision(),
        "dimensions".equals(scenario) ? 512 : p.dimensions(),
        "tokenizer_identity".equals(scenario) ? "explicit-synthetic-other-tokenizer-identity" : p.tokenizerIdentity(),
        "preprocessing".equals(scenario) ? "explicit-synthetic-other-preprocess" : p.preprocessingIdentity(),
        "instruction".equals(scenario) ? "explicit-synthetic-other-instruction" : p.instruction(),
        "endpoint".equals(scenario) ? "http://127.0.0.1:1/explicit-synthetic-identity-only" : p.endpointIdentity());
  }

  static void validateCase(String scenario, String domain, List<String> inputs, RawVectorEncoder encoder,
      FixtureEncoder fixture, AtomicReference<RawEncodingProfile> active, Map<String, Boolean> checks, Map<String, Object> result) throws Exception {
    long before = cacheRows(); String input = inputs.get(0), hash = cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(input), key = active.get().key(domain, input);
    if ("concurrent_immutable_winner".equals(scenario)) {
      var winner = vector(input, 1024); cache.put(key, active.get().fingerprint(), hash, 1024, winner);
      ExecutorService pool = Executors.newFixedThreadPool(4); List<Future<?>> writes = new ArrayList<>();
      try {
        for (int n = 0; n < 16; n++) writes.add(pool.submit(() -> cache.put(key, active.get().fingerprint(), hash, 1024, Collections.nCopies(1024, 99f))));
        for (var write : writes) write.get(20, TimeUnit.SECONDS);
      } finally { pool.shutdownNow(); }
      checks.put("realPostgresConcurrentCacheWritersKeepOneImmutableWinner", cacheRows() == before + 1 && cache.find(key, active.get().fingerprint(), hash, 1024).orElseThrow().equals(winner));
      checks.put("fixtureEncoderNeverInvokedForDirectCacheRace", fixture.calls.isEmpty()); return;
    }
    if ("corrupt_persisted_vector".equals(scenario)) {
      db.update("INSERT INTO raw_vector_cache(cache_key,profile_hash,input_hash,dimensions,vector_json) VALUES(?,?,?,?,?)", key, active.get().fingerprint(), hash, 1024, "[1]");
    } else fixture.mode = scenario;
    String rejection = "";
    try { encoder.open(domain, tokens, RawChunkProfile.DEFAULT).encode(inputs); }
    catch (IllegalStateException expected) { rejection = Objects.toString(expected.getMessage(), ""); }
    boolean count = "missing_vector_count".equals(scenario);
    checks.put("expectedMalformedVectorContractRejected", count ? rejection.equals("embedding count mismatch") : rejection.startsWith("Invalid raw embedding vector") || rejection.startsWith("Invalid cached raw vector"));
    checks.put("noPartialSuccessCacheRowsOnMalformedBatch", cacheRows() == before + ("corrupt_persisted_vector".equals(scenario) ? 1 : 0));
    if ("corrupt_persisted_vector".equals(scenario)) checks.put("corruptPersistedVectorStopsBeforeAnyEncoding", fixture.calls.isEmpty());
    else checks.put("invalidFixtureResponseActuallyReceivedByProductionEncoder", fixture.calls.size() == 1 && fixture.encodedItems == 3);
    result.put("actualRejectedContract", rejection);
  }

  static void sourceCase(JsonNode gold, List<RawKnowledgeBuilder.ChunkDraft> before,
      List<RawKnowledgeBuilder.ChunkDraft> after, List<List<Float>> vectors, RawVectorEncoder encoder,
      FixtureEncoder fixture, Map<String, Boolean> checks, Map<String, Object> result) throws Exception {
    String id = gold.path("id").asText(), scenario = gold.path("scenario").asText(), doc = id + "-source", version = id + "-source-v1";
    String sha = gold.path("before").path("sha256").asText();
    int security = "acl_lower_republish".equals(scenario) ? 30 : 10;
    seedSource(doc, version, 1, sha, security, gold.path("before").path("path").asText());
    var claim = claim(doc, version, sha); var execution = tasks.rawExecution(claim); execution.bind(doc, version);
    var raw = rawDocuments(before, vectors, doc);
    var physical = index.stageRaw(doc, version, claim.scope(), raw); execution.phase(ProgressStage.ACTIVATING);
    boolean late = scenario.endsWith("before_publish");
    if (late) {
      if ("acl_changed_before_publish".equals(scenario)) db.update("UPDATE document_metadata SET acl_version=acl_version+1,security_level=30 WHERE version_id=?", version);
      else db.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id=?", version);
      String rejected = "";
      try { execution.publish(doc, version, physical, () -> index.activateRaw(doc, version, physical)); }
      catch (IllegalStateException expected) { rejected = Objects.toString(expected.getMessage(), ""); }
      checks.put("lateSourceOrAclChangeRejectsActualFencedPublication", rejected.startsWith("RAW_SOURCE_REVOKED:"));
      checks.put("stagedVectorsWithoutAuthoritativePublicationInvisible", current(version, 127).isEmpty()
          && db.queryForObject("SELECT count(*) FROM raw_publication WHERE version_id=?", Long.class, version) == 0);
      tasks.rawFailure(claim, "RAW_SOURCE_REVOKED", rejected, false);
      checks.put("rejectedSourceRecordedNormallyAsIsolatedTaskFailure", db.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='FAILED' AND isolated=true", Long.class, claim.file().fileId()) == 1);
      encoder.open(id, tokens, RawChunkProfile.DEFAULT).encode(after.stream().map(c -> c.content()).toList());
      result.put("publicationRejection", rejected); return;
    }
    execution.publish(doc, version, physical, () -> index.activateRaw(doc, version, physical)); tasks.rawComplete(claim, doc, version);
    checks.put("normalOwnerGuardedPublicationInitiallySearchable", current(version, security).equals(new TreeSet<>(physical)));
    encoder.open(id, tokens, RawChunkProfile.DEFAULT).encode(after.stream().map(c -> c.content()).toList());
    if ("acl_raise".equals(scenario)) {
      db.update("UPDATE document_metadata SET acl_version=acl_version+1,security_level=30 WHERE version_id=?", version);
      checks.put("raisedAclInvalidatesOldProjectionEvenWithCacheHit", current(version, 127).isEmpty());
    } else if ("acl_lower_republish".equals(scenario)) {
      db.update("UPDATE document_metadata SET acl_version=acl_version+1,security_level=10 WHERE version_id=?", version);
      checks.put("loweredAclDoesNotSkipNormalFreshPublication", current(version, 10).isEmpty());
      var next = publish(doc, version, sha, raw); checks.put("freshPermissionProjectionReplacesOnlyCurrentPhysicalMembers", current(version, 10).equals(new TreeSet<>(next)) && Collections.disjoint(next, physical));
    } else if ("deleted_source".equals(scenario)) {
      db.update("UPDATE document SET status='DELETED' WHERE document_id=?", doc);
      checks.put("deletedSourceIsInvisibleDespiteCachedCalculation", current(version, 127).isEmpty());
    } else if ("inactive_version".equals(scenario)) {
      db.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id=?", version);
      checks.put("inactiveVersionIsInvisibleDespiteCachedCalculation", current(version, 127).isEmpty());
    } else if ("new_version_same_body".equals(scenario)) {
      String newer = id + "-source-v2", newSha = gold.path("after").path("sha256").asText();
      db.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id=?", version);
      seedSource(doc, newer, 2, newSha, 10, gold.path("after").path("path").asText());
      var next = publish(doc, newer, newSha, rawDocuments(after, vectors, doc));
      checks.put("newSourceVersionReusesOnlyCalculationAndKeepsFreshSourceIdentity", current(version, 127).isEmpty() && current(newer, 10).equals(new TreeSet<>(next)) && Collections.disjoint(next, physical));
    } else if ("different_domains".equals(scenario)) {
      String privateDoc = id + "-private", privateVersion = id + "-private-v1";
      var second = encoder.open(id + "-private-domain", tokens, RawChunkProfile.DEFAULT).encode(after.stream().map(c -> c.content()).toList());
      seedSource(privateDoc, privateVersion, 1, sha, 30, gold.path("after").path("path").asText());
      var secret = publish(privateDoc, privateVersion, sha, rawDocuments(after, second, privateDoc));
      checks.put("identicalBodiesNeverMergePermissionSourcesOrCacheDomains", current(version, 10).equals(new TreeSet<>(physical)) && current(privateVersion, 10).isEmpty() && current(privateVersion, 30).equals(new TreeSet<>(secret)));
    }
    result.put("originalPhysicalMembers", physical); result.put("currentPhysicalMembers", current(version, 127));
  }

  static void seedSource(String doc, String version, long number, String sha, int security, String filename) throws Exception {
    db.update("INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE') ON CONFLICT(document_id) DO NOTHING", doc, doc);
    db.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,?,'ACTIVE',?,?,?,?)", version, doc, number, filename, Files.size(fixtures.resolve(filename)), sha, "fixture-local/" + filename);
    db.update("INSERT INTO document_metadata(version_id,security_level) VALUES(?,?)", version, security);
  }
  static RawTaskClaim claim(String doc, String version, String sha) {
    String batch = UUID.randomUUID().toString(), file = UUID.randomUUID().toString();
    db.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'hash40-native-fixture',1)", batch);
    db.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,'fixture','fixture.html','fixture-local',?,?)", file, batch, sha, doc);
    tasks.queueBatch(batch, Instant.now()); return tasks.claimRaw(file, UUID.randomUUID().toString()).orElseThrow();
  }
  static List<RawIndexDocument> rawDocuments(List<RawKnowledgeBuilder.ChunkDraft> chunks, List<List<Float>> vectors, String doc) {
    var result = new ArrayList<RawIndexDocument>();
    for (int n = 0; n < chunks.size(); n++) { var c = chunks.get(n);
      result.add(new RawIndexDocument(c.id(), c.content(), c.normalized(), c.headingPath(), c.sourceBlockIds(), c.pageStart(), c.pageEnd(), c.contentHash(), vectors.get(n), List.of(), metadata(doc)));
    }
    return result;
  }
  static List<String> publish(String doc, String version, String sha, List<RawIndexDocument> raw) {
    var claim = claim(doc, version, sha); var execution = tasks.rawExecution(claim); execution.bind(doc, version); execution.phase(ProgressStage.ACTIVATING);
    var physical = index.stageRaw(doc, version, claim.scope(), raw);
    execution.publish(doc, version, physical, () -> index.activateRaw(doc, version, physical)); tasks.rawComplete(claim, doc, version); return physical;
  }
  static Set<String> current(String version, int clearance) {
    var rows = index.searchVector(vector("fixture-query", 1024), new SearchFilters(null, null, null, List.of(version), clearance), 100);
    return new TreeSet<>(rows.stream().map(c -> c.id()).toList());
  }
}
