package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.infrastructure.parser.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.raw.*;
import cn.lgs.knowledgebase.infrastructure.search.*;
import cn.lgs.knowledgebase.infrastructure.storage.*;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Disclosed development supplement: real normal Raw version flow; fixture vectors, no model. */
public final class RawSourceFormatVersionProbe {
  public static void main(String[] args) throws Exception {
    ObjectMapper json = new ObjectMapper(); Path root = Path.of(args[1]), output = Path.of(args[0]);
    var gold = json.readTree(root.resolve("manifest.json").toFile()).path("cases").get(0);
    var tokenManifest = json.readTree(Path.of(args[2]).toFile());
    var effective = new EffectiveConfiguration("http://127.0.0.1", "", "gpt-5.6-luna", "gpt-5.6-terra", 60,
        "http://127.0.0.1:8110", "", tokenManifest.path("model").asText(), 1024, 8, 60,
        "http://127.0.0.1:8110", "", "Qwen3-VL-Reranker", 60, Instant.EPOCH);
    Store configuration = new Store() {
      public EffectiveConfiguration current() { return effective; }
      public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException(); }
    };
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var tasks = new JdbcIngestionRepository(jdbc, transactions, json);
    var metadata = new JdbcKnowledgeRepository(jdbc, transactions, json);
    var local = new LocalFileSourceAdapter();
    var objects = new MinioObjectStoreAdapter("http://127.0.0.1:19400", System.getenv("PROBE_MINIO_USER"), System.getenv("PROBE_MINIO_PASSWORD"), "evokb-target");
    var admin = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());
    if (!admin.listDatabases().getDatabaseNames().contains(args[3])) admin.createDatabase(CreateDatabaseReq.builder().databaseName(args[3]).build());
    admin.close();
    var client = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(args[3]).rpcDeadlineMs(30000).build());
    var checks = new LinkedHashMap<String, Boolean>(); var report = new LinkedHashMap<String, Object>();
    var actual = new ArrayList<Map<String, Object>>(); AtomicInteger items = new AtomicInteger();
    EmbeddingPort fixture = new EmbeddingPort() {
      public int dimensions() { return 1024; }
      public List<List<Float>> embed(List<String> texts) {
        items.addAndGet(texts.size());
        return texts.stream().map(text -> { var vector = new ArrayList<Float>(Collections.nCopies(1024, 0f)); vector.set(0, 1f); return (List<Float>) vector; }).toList();
      }
    };
    try (var frozen = new FrozenRawEncodingConfiguration(args[2], json, configuration, "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
      var search = new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", new JdbcProjectionAuthority(jdbc));
      var encoder = new RawVectorEncoder(fixture, frozen.profiles(), new JdbcRawVectorCache(jdbc, json));
      var service = new RawIngestionApplicationService(local, objects, metadata, new TextDocumentParserAdapter(json),
          new JacksonParsedArtifactEncoder(json), search, fixture, null, frozen.tokens(), RawChunkProfile.DEFAULT, encoder);
      List<RawIngestionApplicationService.IngestResult> results = new ArrayList<>();
      String logical = "Disclosed Hash format development supplement " + UUID.randomUUID();
      List<Path> originals = List.of(root.resolve(gold.path("before").path("path").asText()),
          root.resolve(gold.path("after").path("path").asText()), root.resolve(gold.path("after").path("path").asText()));
      for (int n = 0; n < originals.size(); n++) {
        Path source = originals.get(n); var file = local.inspect(source.toString());
        String batch = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ingestion_batch(batch_id,created_by_username,source_count) VALUES(?,'hash-development-supplement',1)", batch);
        jdbc.update("INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,sha256,logical_name) VALUES(?,?,?,?,?,?,?)", id, batch, source.getFileName().toString(), file.filename(), file.path(), file.sha256(), logical);
        tasks.queueBatch(batch, Instant.now()); var claim = tasks.claimRaw(id, UUID.randomUUID().toString()).orElseThrow();
        int before = items.get();
        var result = service.ingest(new RawIngestionApplicationService.IngestCommand(file.path(), logical, file.path(),
            new Metadata("Development supplement", "Synthetic contract", List.of("HASH-ERRATUM"), "", null, "CONTRACT")), ignored -> {}, "", ignored -> {}, tasks.rawExecution(claim));
        tasks.rawComplete(claim, result.documentId(), result.versionId()); results.add(result);
        var stored = objects.get(result.originalObjectKey()); String actualSha;
        try (var input = stored.stream()) { actualSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())); }
        checks.put("original" + n + "MinioBytesMatchExactInputAndFrozenGold", actualSha.equals(file.sha256())
            && actualSha.equals(gold.path(n == 0 ? "before" : "after").path("sha256").asText()));
        int additional = items.get() - before;
        checks.put("normalRun" + n + "ExpectedFixtureEncodingCount", additional == (n == 0 ? 3 : 0));
        checks.put("normalRun" + n + "TaskCompletedThroughOwnerGuard", jdbc.queryForObject("SELECT count(*) FROM ingestion_file WHERE file_id=? AND status='SUCCEEDED' AND progress_stage='SUCCEEDED' AND attempt=1 AND owner_id=? AND execution_epoch=? AND finished_at IS NOT NULL AND document_id=? AND version_id=?", Long.class, id, claim.ownerId(), claim.epoch(), result.documentId(), result.versionId()) == 1);
        boolean lateClaimRejected = false;
        try { tasks.rawExecution(claim).check(); }
        catch (IllegalStateException stale) { lateClaimRejected = stale.getMessage().contains("RAW_STALE_CLAIM"); }
        checks.put("normalRun" + n + "CompletedTaskRejectsLateClaim", lateClaimRejected);
        actual.add(Map.of("fileId", id, "claimScope", claim.scope(), "documentId", result.documentId(), "versionId", result.versionId(), "versionNo", result.versionNo(),
            "reused", result.reused(), "additionalFixtureEncodedInputs", additional, "originalObjectKey", result.originalObjectKey(), "actualOriginalSha256", actualSha));
      }
      var first = results.get(0); var second = results.get(1); var repeated = results.get(2);
      checks.put("normalVersionCreationKeepsOneDocumentWithTwoDifferentOriginalHashes", first.documentId().equals(second.documentId()) && !first.versionId().equals(second.versionId())
          && first.versionNo() == 1 && second.versionNo() == 2 && jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?", Long.class, first.documentId()) == 2);
      checks.put("exactRepeatedOriginalUsesExistingActiveVersionWithoutDuplicate", repeated.reused() && repeated.versionId().equals(second.versionId()) && repeated.versionNo() == 2);
      checks.put("identicalNormalizedInputsUseOnlyThreeCacheEntries", jdbc.queryForObject("SELECT count(*) FROM raw_vector_cache", Long.class) == 3 && items.get() == 3);
      checks.put("freshSourceBlocksAndEvidencePreserveBothVersionIdentities", metadata.rawBlocks(first.versionId()).size() == 7 && metadata.rawBlocks(second.versionId()).size() == 7
          && metadata.rawBlocks(first.versionId()).stream().noneMatch(a -> metadata.rawBlocks(second.versionId()).stream().anyMatch(b -> a.id().equals(b.id())))
          && jdbc.queryForObject("SELECT count(*) FROM evidence WHERE version_id IN (?,?)", Long.class, first.versionId(), second.versionId()) == 6);
      checks.put("normalActivationMakesOnlyNewVersionSearchable", search.searchVector(Collections.nCopies(1024, 0.01f), new SearchFilters(null, null, null, List.of(first.versionId())), 100).isEmpty()
          && search.searchVector(Collections.nCopies(1024, 0.01f), new SearchFilters(null, null, null, List.of(second.versionId())), 100).size() == 3);
      checks.put("oldVersionInactiveAndCurrentVersionActive", "INACTIVE".equals(jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id=?", String.class, first.versionId()))
          && "ACTIVE".equals(jdbc.queryForObject("SELECT lifecycle FROM document_version WHERE version_id=?", String.class, second.versionId())));
      report.put("normalRuns", actual); report.put("versions", jdbc.queryForList("SELECT version_id,document_id,version_no,lifecycle,sha256,original_object_key FROM document_version"));
      report.put("currentPublications", jdbc.queryForList("SELECT version_id,producer_scope,execution_epoch,acl_version FROM raw_publication"));
      report.put("completedTasks", jdbc.queryForList("SELECT file_id,status,progress_stage,attempt,owner_id,execution_epoch,document_id,version_id,stage_started_at,stage_deadline,started_at,finished_at FROM ingestion_file ORDER BY created_at"));
    } catch (Throwable error) {
      checks.put("normalProductionRawVersionFlowCompleted", false); report.put("failure", error.toString());
      try (var log = new java.io.PrintWriter(output + ".failure.log")) { error.printStackTrace(log); }
    } finally { client.close(); }
    boolean passed = checks.values().stream().allMatch(Boolean.TRUE::equals);
    report.put("status", passed ? "PASS_DISCLOSED_RAW_VERSION_FORMAT_SUPPLEMENT" : "FAIL_DISCLOSED_RAW_VERSION_FORMAT_SUPPLEMENT");
    report.put("caseCount", 1); report.put("passedCases", passed ? 1 : 0);
    report.put("cases", List.of(Map.of("caseId", gold.path("id").asText(), "split", "development", "status", passed ? "PASS" : "FAIL", "checks", checks)));
    report.put("boundary", "One disclosed development erratum, separate from main Hash40/held16. Actual immutable normal RawIngestionApplicationService creates pending versions, parses originals, saves artifacts/evidence, uses PG cache and owner-fenced Milvus publication/activation. MinIO original SHA read back. Fixture vectors explicitly replace inference; zero real model calls, not whole HTTP/browser or real encoding benefit.");
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); System.exit(passed ? 0 : 1);
  }
}
