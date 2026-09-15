import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.search.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.vector.request.QueryReq;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL + Milvus + Qwen component acceptance; no fake model or HTTP server. */
public class MilvusIndexProbe {
  static final Map<String, Object> checks = new LinkedHashMap<>();
  static void check(String name, boolean pass) {
    checks.put(name, pass);
    if (!pass) throw new AssertionError(name);
  }
  static List<String> ids(List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> rows) {
    return rows.stream().map(c -> c.id()).sorted().toList();
  }
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    var report = new LinkedHashMap<String, Object>();
    MilvusClientV2 client = null;
    try {
      String database = args[1];
      var admin = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      if (!admin.listDatabases().getDatabaseNames().contains(database))
        admin.createDatabase(CreateDatabaseReq.builder().databaseName(database).build());
      admin.close();
      client = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535")
          .dbName(database).rpcDeadlineMs(60000).build());
      var data = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(data);
      report.put("networkDiagnostics", Map.of(
          "loopbackResolved", !new java.net.InetSocketAddress("127.0.0.1", 15465).isUnresolved(),
          "postgresShouldResolve", new org.postgresql.util.HostSpec("127.0.0.1", 15465).shouldResolve(),
          "socksProxyConfigured", System.getProperty("socksProxyHost") != null,
          "socksBypass", System.getProperty("socksNonProxyHosts", "<default>")));
      var authority = new JdbcProjectionAuthority(jdbc);
      var visited = new LinkedHashSet<String>();
      var index = new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", new MilvusKnowledgeIndexAdapter.ProjectionAuthority() {
        public int securityLevel(List<String> versions) { return authority.securityLevel(versions); }
        public boolean accessible(List<String> versions, SearchFilters filters) {
          visited.addAll(versions); return authority.accessible(versions, filters);
        }
      });
      index.ensureIndex(1024);
      index.ensureIndex(1024);
      check("oneCollectionAndRepeatInitialization", client.listCollections().getCollectionNames().equals(List.of("knowledge_chunks")));
      var models = new OpenAiCompatibleModelServingAdapter("http://127.0.0.1:8110", "", "Qwen/Qwen3-VL-Embedding-2B",
          "http://127.0.0.1:8110", "", "Qwen/Qwen3-VL-Reranker-2B", 1024, 1,
          Duration.ofSeconds(120), Duration.ofSeconds(120), mapper);
      String publicText = "星桥 HT-017 合同付款条款：验收通过后30日付款。";
      String secretText = "机密资料 HT-017 补充协议付款条款：验收通过后60日付款，金额900万元。";
      String unrelated = "物流仓库安全：叉车每日检修，消防通道不得堆放货物。";
      var vectors = models.embed(List.of(publicText, secretText, unrelated));
      check("realQwen1024Vectors", vectors.size() == 3 && vectors.stream().allMatch(v -> v.size() == 1024));
      var meta = new Metadata("法务", "合同", List.of(), "星桥", LocalDate.of(2026, 10, 2), "合同");
      var publicChunk = new RawIndexDocument("raw-public", publicText, publicText, List.of("付款条款"), List.of("block-public"), 1, 1, "hash-public", vectors.get(0), List.of("evidence-public"), meta);
      var secretChunk = new RawIndexDocument("raw-secret", secretText, secretText, List.of("付款条款"), List.of("block-secret"), 1, 1, "hash-secret", vectors.get(1), List.of("evidence-secret"), meta);
      var otherChunk = new RawIndexDocument("raw-unrelated", unrelated, unrelated, List.of("仓储"), List.of("block-unrelated"), 1, 1, "hash-other", vectors.get(2), List.of("evidence-other"), new Metadata("仓储", "操作手册", List.of(), "星桥", null, "手册"));
      index.indexRaw("doc-public", "version-public", List.of(publicChunk));
      index.indexRaw("doc-secret", "version-secret", List.of(secretChunk));
      index.indexRaw("doc-other", "version-other", List.of(otherChunk));
      check("stagedRowsAreNotVisible", index.searchBm25("付款条款", null, 50).isEmpty());
      index.activateVersion("doc-public", "version-public");
      index.activateVersion("doc-secret", "version-secret");
      index.activateVersion("doc-other", "version-other");
      var low = new SearchFilters(null, null, null, List.of());
      var high = low.withClearance(30);
      visited.clear();
      check("bm25PublicChineseMatch", ids(index.searchBm25("付款条款", low, 50)).equals(List.of("raw-public")));
      check("bm25SecretFilteredBeforeAuthorityReads", !visited.contains("version-secret"));
      var metadataCandidate = index.searchBm25("付款条款", low, 50).get(0);
      check("retrievedProjectionPreservesSourceMetadata",
          List.of("付款条款").equals(metadataCandidate.attributes().get("heading_path"))
          && List.of("block-public").equals(metadataCandidate.attributes().get("source_block_ids"))
          && "hash-public".equals(metadataCandidate.attributes().get("content_hash")));
      var queryVector = models.embed(List.of("HT-017验收后何时付款")).get(0);
      visited.clear();
      var lowDense = ids(index.searchVector(queryVector, low, 50));
      check("densePublicVisibleSecretExcluded", lowDense.contains("raw-public") && !lowDense.contains("raw-secret"));
      check("denseSecretFilteredBeforeAuthorityReads", !visited.contains("version-secret"));
      check("independentTrustedClearanceCanReadSecret", ids(index.searchBm25("付款条款", high, 50)).equals(List.of("raw-public", "raw-secret")));
      check("departmentFilterDoesNotGrantClearance", index.searchBm25("付款条款", new SearchFilters("法务", null, null, List.of()), 50).stream().noneMatch(c -> c.id().equals("raw-secret")));
      check("wrongDepartmentFilterEmpty", index.searchBm25("付款条款", new SearchFilters("财务", null, null, List.of()), 50).isEmpty());
      check("filterLiteralCannotInjectExpression", index.searchBm25("付款条款", new SearchFilters("法务\") or security_level >= 0 or (\"x", null, null, List.of()), 50).isEmpty());
      check("requestedSecretVersionDoesNotBypassClearance", index.searchBm25("付款条款", new SearchFilters(null, null, null, List.of("version-secret")), 50).isEmpty());
      var publicContext = new DerivedContext(List.of("doc-public"), List.of("version-public"), List.of("法务"), List.of("合同"), List.of("星桥"), List.of("合同"), List.of());
      var mixedContext = new DerivedContext(List.of("doc-public", "doc-secret"), List.of("version-public", "version-secret"), List.of("法务"), List.of("合同"), List.of("星桥"), List.of("合同"), List.of());
      index.indexSummary(new SummaryArtifact("summary-public", "doc-public", "version-public", publicText, List.of(), ArtifactStatus.ACTIVE, Instant.now()), publicContext, vectors.get(0));
      check("summaryUsesSameCollection", ids(index.searchSummaryBm25("付款条款", low, 50)).equals(List.of("summary-public")));
      index.indexConcept("asset-contract", "HT-017合同付款条款概念，范围包括原合同与补充协议。", List.of("evidence-public", "evidence-secret"), mixedContext, vectors.get(1));
      check("mixedSourceConceptRequiresAllPermissions", index.searchWikiBm25("付款条款", low, 50).isEmpty());
      check("conceptQueryableAtTrustedClearance", ids(index.searchWikiBm25("付款条款", high, 50)).equals(List.of("asset-contract")));
      index.indexWiki(new WikiPage("asset-contract", "HT-017付款专题", secretText, List.of(), List.of(), List.of(), ArtifactStatus.ACTIVE, Instant.now()), mixedContext, vectors.get(1));
      check("conceptPromotesWithoutDuplicateBusinessIdentity", ids(index.searchWikiBm25("付款条款", high, 50)).equals(List.of("asset-contract")));
      index.indexRawRebuild("doc-public", "version-public", List.of(publicChunk));
      index.indexRawRebuild("doc-public", "version-public", List.of(publicChunk));
      check("replayedRawWriteDoesNotDuplicate", index.indexedChunkCount("version-public") == 1);
      check("rawRebuildRetainsVisibility", ids(index.searchBm25("付款条款", low, 50)).equals(List.of("raw-public")));
      var before = client.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == \"raw-public\"").outputFields(List.of("dense")).build()).getQueryResults().get(0).getEntity().get("dense");
      jdbc.update("UPDATE document_metadata SET security_level=30,acl_version=acl_version+1 WHERE version_id='version-public'");
      check("authorityRejectsStaleLowSecurityProjection", index.searchBm25("付款条款", low, 50).isEmpty());
      index.updateMetadata("version-public", meta);
      var after = client.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == \"raw-public\"").outputFields(List.of("dense")).build()).getQueryResults().get(0).getEntity().get("dense");
      check("aclChangeDoesNotReencodeDenseVector", before.equals(after));
      check("aclUpdateInvalidatesDerivedOutputs", index.searchSummaryBm25("付款条款", high, 50).isEmpty() && index.searchWikiBm25("付款条款", high, 50).isEmpty());
      jdbc.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id='version-secret'");
      check("authoritativeStaleSourceBlocksProjection", index.searchBm25("付款条款", high, 50).stream().noneMatch(c -> c.id().equals("raw-secret")));
      index.deactivateVersion("version-secret");
      check("inactiveSourceHiddenAtMilvus", index.searchBm25("付款条款", high, 50).stream().noneMatch(c -> c.id().equals("raw-secret")));
      var schema = client.describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq.builder().collectionName("knowledge_chunks").build()).getCollectionSchema();
      var denseIndex = client.describeIndex(io.milvus.v2.service.index.request.DescribeIndexReq.builder().collectionName("knowledge_chunks").indexName("dense_hnsw").build()).getIndexDescByFieldName("dense");
      var securityIndex = client.describeIndex(io.milvus.v2.service.index.request.DescribeIndexReq.builder().collectionName("knowledge_chunks").indexName("security_bitmap").build()).getIndexDescByFieldName("security_level");
      check("schemaAndIndexesValidatedWithoutOverwrite",
          schema.getField("dense").getDimension() == 1024
          && schema.getField("security_level").getDataType() == io.milvus.v2.common.DataType.Int8
          && schema.getFieldSchemaList().stream().noneMatch(f -> Boolean.TRUE.equals(f.getIsPartitionKey()))
          && denseIndex.getIndexType() == io.milvus.v2.common.IndexParam.IndexType.HNSW
          && securityIndex.getIndexType() == io.milvus.v2.common.IndexParam.IndexType.BITMAP
          && index.indexedChunkCount("version-public") == 1);
      boolean wrongDimensionsRejected = false;
      try { index.ensureIndex(2048); }
      catch (IllegalArgumentException expected) { wrongDimensionsRejected = true; }
      check("wrongModelDimensionsRejectedWithoutChangingCollection",
          wrongDimensionsRejected && index.indexedChunkCount("version-public") == 1);
      new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", authority).ensureIndex(1024);
      check("freshAdapterValidatesExistingChineseCollection",
          schema.getField("content").getAnalyzerParams().equals(Map.of("type", "chinese")));

      // Deliberately incompatible component-only collection. The application must fail closed,
      // preserving it for inspection rather than silently dropping/recreating or accepting it.
      schema.getField("content").setAnalyzerParams(Map.of("type", "standard"));
      var badIndexes = List.of(
          io.milvus.v2.common.IndexParam.builder().fieldName("dense").indexName("dense_hnsw")
              .indexType(io.milvus.v2.common.IndexParam.IndexType.HNSW)
              .metricType(io.milvus.v2.common.IndexParam.MetricType.COSINE).build(),
          io.milvus.v2.common.IndexParam.builder().fieldName("sparse").indexName("sparse_bm25")
              .indexType(io.milvus.v2.common.IndexParam.IndexType.SPARSE_INVERTED_INDEX)
              .metricType(io.milvus.v2.common.IndexParam.MetricType.BM25).build(),
          io.milvus.v2.common.IndexParam.builder().fieldName("security_level").indexName("security_bitmap")
              .indexType(io.milvus.v2.common.IndexParam.IndexType.BITMAP).build());
      client.createCollection(io.milvus.v2.service.collection.request.CreateCollectionReq.builder()
          .collectionName("incompatible_analyzer").collectionSchema(schema).indexParams(badIndexes).build());
      boolean incompatibleRejected = false;
      try { new MilvusKnowledgeIndexAdapter(client, "incompatible_analyzer", authority).ensureIndex(1024); }
      catch (IllegalStateException expected) { incompatibleRejected = expected.getMessage().contains("content"); }
      var preserved = client.describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq.builder()
          .collectionName("incompatible_analyzer").build()).getCollectionSchema().getField("content");
      check("incompatibleAnalyzerRejectedAndPreserved",
          incompatibleRejected && preserved.getAnalyzerParams().equals(Map.of("type", "standard"))
          && index.indexedChunkCount("version-public") == 1);
      report.put("status", "PASS_REAL_MILVUS_POSTGRES_QWEN_COMPONENTS");
    } catch (Throwable failure) {
      failure.printStackTrace();
      report.put("status", "FAIL_REAL_MILVUS_POSTGRES_QWEN_COMPONENTS");
      report.put("failureType", failure.getClass().getName());
      report.put("failure", failure.getMessage());
      var causes = new ArrayList<String>();
      for (Throwable cause = failure; cause != null; cause = cause.getCause())
        causes.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
      report.put("failureCauses", causes);
    } finally {
      if (client != null) client.close();
      report.put("checks", checks);
      report.put("boundary", "Real components with isolated business-related fixtures. Does not certify browser QA, full ingestion, stream recovery or generation publication.");
      mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
    }
    if (!report.get("status").toString().startsWith("PASS")) System.exit(1);
  }
}
