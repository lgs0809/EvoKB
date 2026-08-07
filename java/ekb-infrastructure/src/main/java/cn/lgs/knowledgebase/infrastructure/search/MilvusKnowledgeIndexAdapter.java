package cn.lgs.knowledgebase.infrastructure.search;

import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.RawIndexDocument;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.common.IndexParam.IndexType;
import io.milvus.v2.common.IndexParam.MetricType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One retrieval projection collection. PostgreSQL remains the source and authorization authority.
 */
public final class MilvusKnowledgeIndexAdapter implements RawSearchIndex, DerivedSearchIndex {
  public static final int DIMENSIONS = 1024;
  private static final List<String> OUTPUT =
      List.of(
          "id",
          "knowledge_type",
          "content",
          "document_ids",
          "version_ids",
          "evidence_ids",
          "attributes");
  private final MilvusClientV2 client;
  private final String collection;
  private final ProjectionAuthority authority;
  private final Gson gson = new Gson();
  private volatile boolean ready;

  public interface ProjectionAuthority {
    /**
     * Resolve every dependency from authoritative source rows; unknown sources must fail closed.
     */
    int securityLevel(List<String> versionIds);

    /**
     * Recheck current versions and current permission after search, before exposing any content.
     */
    boolean accessible(List<String> versionIds, SearchFilters filters);

    /** Legacy adapters without authoritative entry provenance never manufacture attribution. */
    default Map<String, Object> registerAsset(
        String id,
        String stage,
        String projection,
        List<String> evidenceIds,
        DerivedContext context) {
      return Map.of();
    }

    default java.util.Optional<List<Map<String, Object>>> assetSources(
        String id, long revision, SearchFilters filters) {
      return java.util.Optional.empty();
    }

    default Map<String, Object> prepareAsset(
        String id, String stage, String projection, List<String> proofs, DerivedContext context) {
      return registerAsset(id, stage, projection, proofs, context);
    }

    default void publishAsset(String id, long revision) {}

    default java.util.Optional<List<Map<String, Object>>> currentAssetSources(
        String id, long revision, SearchFilters filters) {
      return assetSources(id, revision, filters);
    }

    /** Existing legacy rows stop serving an asset once an authoritative revision is published. */
    default boolean legacyProjectionCurrent(String id) {
      return true;
    }

    default boolean rawProjectionCurrent(String physicalId, List<String> versions) {
      return true;
    }

    /** Optional authoritative raw scope predicate; absent in explicit legacy components. */
    default java.util.Optional<Map<String, List<String>>> rawVisibility(SearchFilters filters) {
      return java.util.Optional.empty();
    }
  }

  public MilvusKnowledgeIndexAdapter(
      MilvusClientV2 client, String collection, ProjectionAuthority authority) {
    this.client = Objects.requireNonNull(client);
    this.collection = Objects.requireNonNull(collection);
    if (!collection.matches("[A-Za-z_][A-Za-z0-9_]{0,254}"))
      throw new IllegalArgumentException("invalid Milvus collection name");
    this.authority = Objects.requireNonNull(authority);
  }

  @Override
  public synchronized void ensureIndex(int embeddingDimensions) {
    if (embeddingDimensions != DIMENSIONS)
      throw new IllegalArgumentException("the frozen embedding contract requires 1024 dimensions");
    if (ready) return;
    var name = HasCollectionReq.builder().collectionName(collection).build();
    if (!client.hasCollection(name)) {
      try {
        client.createCollection(
            CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema())
                .consistencyLevel(ConsistencyLevel.STRONG)
                .indexParams(indexes())
                .build());
      } catch (RuntimeException failure) {
        // Another instance may win initialization. Never drop or recreate an existing collection.
        if (!client.hasCollection(name)) throw failure;
      }
    }
    validateSchema();
    client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
    ready = true;
  }

  private CreateCollectionReq.CollectionSchema schema() {
    var schema = client.createSchema();
    schema.setEnableDynamicField(false);
    schema.addField(
        AddFieldReq.builder()
            .fieldName("id")
            .dataType(DataType.VarChar)
            .maxLength(512)
            .isPrimaryKey(true)
            .autoID(false)
            .build());
    schema.addField(
        AddFieldReq.builder()
            .fieldName("knowledge_type")
            .dataType(DataType.VarChar)
            .maxLength(16)
            .build());
    schema.addField(
        AddFieldReq.builder()
            .fieldName("content")
            .dataType(DataType.VarChar)
            .maxLength(65535)
            .enableAnalyzer(true)
            .analyzerParams(Map.of("type", "chinese"))
            .build());
    schema.addField(
        AddFieldReq.builder()
            .fieldName("dense")
            .dataType(DataType.FloatVector)
            .dimension(DIMENSIONS)
            .build());
    schema.addField(
        AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
    schema.addField(
        AddFieldReq.builder().fieldName("security_level").dataType(DataType.Int8).build());
    schema.addField(AddFieldReq.builder().fieldName("is_active").dataType(DataType.Bool).build());
    for (String name :
        List.of(
            "document_ids",
            "version_ids",
            "evidence_ids",
            "source_departments",
            "material_categories",
            "document_types")) {
      schema.addField(
          AddFieldReq.builder()
              .fieldName(name)
              .dataType(DataType.Array)
              .elementType(DataType.VarChar)
              .maxLength(512)
              .maxCapacity(1024)
              .build());
    }
    schema.addField(AddFieldReq.builder().fieldName("attributes").dataType(DataType.JSON).build());
    schema.addFunction(
        CreateCollectionReq.Function.builder()
            .name("content_bm25")
            .functionType(FunctionType.BM25)
            .inputFieldNames(List.of("content"))
            .outputFieldNames(List.of("sparse"))
            .build());
    return schema;
  }

  private static List<IndexParam> indexes() {
    return List.of(
        IndexParam.builder()
            .fieldName("dense")
            .indexName("dense_hnsw")
            .indexType(IndexType.HNSW)
            .metricType(MetricType.COSINE)
            .extraParams(Map.of("M", 16, "efConstruction", 128))
            .build(),
        IndexParam.builder()
            .fieldName("sparse")
            .indexName("sparse_bm25")
            .indexType(IndexType.SPARSE_INVERTED_INDEX)
            .metricType(MetricType.BM25)
            .build(),
        IndexParam.builder()
            .fieldName("security_level")
            .indexName("security_bitmap")
            .indexType(IndexType.BITMAP)
            .build());
  }

  private void validateSchema() {
    var description =
        client.describeCollection(
            DescribeCollectionReq.builder().collectionName(collection).build());
    var actual = description.getCollectionSchema();
    for (var expected : schema().getFieldSchemaList()) {
      var field = actual.getField(expected.getName());
      if (field == null
          || field.getDataType() != expected.getDataType()
          || Boolean.TRUE.equals(field.getIsPartitionKey())
          || Boolean.TRUE.equals(field.getIsNullable())
          || Boolean.TRUE.equals(field.getIsPrimaryKey())
              != Boolean.TRUE.equals(expected.getIsPrimaryKey())
          || (expected.getMaxLength() != null
              && !expected.getMaxLength().equals(field.getMaxLength()))
          || (expected.getElementType() != null
              && expected.getElementType() != field.getElementType())
          || (expected.getMaxCapacity() != null
              && !expected.getMaxCapacity().equals(field.getMaxCapacity()))
          || (expected.getDimension() != null
              && !expected.getDimension().equals(field.getDimension()))
          || (Boolean.TRUE.equals(expected.getEnableAnalyzer())
              && (!Boolean.TRUE.equals(field.getEnableAnalyzer())
                  || !expected.getAnalyzerParams().equals(field.getAnalyzerParams()))))
        throw new IllegalStateException("incompatible Milvus field: " + expected.getName());
    }
    if (Boolean.TRUE.equals(description.getAutoID())
        || Boolean.TRUE.equals(description.getEnableDynamicField()))
      throw new IllegalStateException("Milvus must use explicit projection IDs and a fixed schema");
    if (actual.getFunctionList().stream()
        .noneMatch(
            f ->
                f.getFunctionType() == FunctionType.BM25
                    && f.getInputFieldNames().equals(List.of("content"))
                    && f.getOutputFieldNames().equals(List.of("sparse"))))
      throw new IllegalStateException("Milvus BM25 function is missing");
    for (var expected : indexes()) {
      var descriptions =
          client.describeIndex(
              DescribeIndexReq.builder()
                  .collectionName(collection)
                  .indexName(expected.getIndexName())
                  .build());
      var index = descriptions.getIndexDescByFieldName(expected.getFieldName());
      if (index == null
          || index.getIndexType() != expected.getIndexType()
          || (expected.getMetricType() != null
              && index.getMetricType() != expected.getMetricType()))
        throw new IllegalStateException("incompatible Milvus index: " + expected.getIndexName());
    }
  }

  @Override
  public void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks) {
    indexRaw(documentId, versionId, chunks, false, "");
  }

  @Override
  public List<String> stageRaw(
      String document, String version, String scope, List<RawIndexDocument> chunks) {
    if (scope == null || scope.isBlank()) {
      indexRaw(document, version, chunks);
      return chunks.stream().map(RawIndexDocument::id).toList();
    }
    String suffix =
        ":e" + cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(scope).substring(0, 24);
    var physical =
        chunks.stream()
            .map(
                chunk ->
                    new RawIndexDocument(
                        chunk.id() + suffix,
                        chunk.content(),
                        chunk.normalized(),
                        chunk.headingPath(),
                        chunk.sourceBlockIds(),
                        chunk.pageStart(),
                        chunk.pageEnd(),
                        chunk.contentHash(),
                        chunk.embedding(),
                        chunk.evidenceIds(),
                        chunk.metadata()))
            .toList();
    indexRaw(document, version, physical, false, scope);
    return physical.stream().map(RawIndexDocument::id).toList();
  }

  @Override
  public void indexRawRebuild(String documentId, String versionId, List<RawIndexDocument> chunks) {
    indexRaw(documentId, versionId, chunks, true, "");
  }

  private void indexRaw(
      String documentId,
      String versionId,
      List<RawIndexDocument> chunks,
      boolean rebuild,
      String scope) {
    if (chunks == null || chunks.isEmpty()) return;
    chunks.forEach(chunk -> validateVector(chunk.embedding()));
    ensureIndex(DIMENSIONS);
    Set<String> active = new LinkedHashSet<>();
    if (rebuild) {
      scan(
          "knowledge_type == \"RAW\" and is_active == true and ARRAY_CONTAINS(version_ids, "
              + literal(versionId)
              + ")",
          List.of("id"),
          row -> {
            active.add(row.get("id").toString());
            return null;
          });
    }
    List<JsonObject> rows = new ArrayList<>();
    for (var chunk : chunks) {
      Metadata meta = chunk.metadata();
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("heading_path", safe(chunk.headingPath()));
      if (chunk.pageStart() != null) attrs.put("page_start", chunk.pageStart());
      if (chunk.pageEnd() != null) attrs.put("page_end", chunk.pageEnd());
      attrs.put("source_block_ids", safe(chunk.sourceBlockIds()));
      attrs.put("content_hash", text(chunk.contentHash()));
      if (scope != null && !scope.isBlank()) attrs.put("raw_scope", scope);
      var context =
          new DerivedContext(
              List.of(documentId),
              List.of(versionId),
              singleton(meta == null ? null : meta.sourceDepartment()),
              singleton(meta == null ? null : meta.materialCategory()),
              List.of(),
              singleton(meta == null ? null : meta.documentType()),
              List.of());
      rows.add(
          row(
              chunk.id(),
              "RAW",
              chunk.content(),
              chunk.evidenceIds(),
              context,
              chunk.embedding(),
              active.contains(chunk.id()) || scope != null && !scope.isBlank(),
              attrs));
    }
    upsert(rows, false);
  }

  @Override
  public void indexSummary(SummaryArtifact summary, DerivedContext context, List<Float> embedding) {
    prepareSummary(summary, context, embedding).run();
  }

  @Override
  public Runnable prepareSummary(
      SummaryArtifact summary, DerivedContext context, List<Float> embedding) {
    Objects.requireNonNull(summary);
    if (context == null)
      context =
          new DerivedContext(
              List.of(summary.documentId()),
              List.of(summary.versionId()),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of());
    return prepareDerived(
        summary.id(),
        "SUMMARY",
        summary.content(),
        evidenceIds(summary.claims()),
        context,
        embedding,
        published(summary.status().name()));
  }

  @Override
  public void indexWiki(WikiPage page, DerivedContext context, List<Float> embedding) {
    prepareWiki(page, context, embedding).run();
  }

  @Override
  public Runnable prepareWiki(WikiPage page, DerivedContext context, List<Float> embedding) {
    Objects.requireNonNull(page);
    return prepareDerived(
        page.id(),
        "WIKI",
        page.content(),
        evidenceIds(page.claims()),
        context,
        embedding,
        published(page.status().name()));
  }

  /** Concept and its compiled Wiki replace the same stable asset projection. */
  @Override
  public void indexConcept(
      String assetId,
      String projection,
      List<String> evidenceIds,
      DerivedContext context,
      List<Float> embedding) {
    prepareConcept(assetId, projection, evidenceIds, context, embedding).run();
  }

  @Override
  public Runnable prepareConcept(
      String assetId,
      String projection,
      List<String> evidenceIds,
      DerivedContext context,
      List<Float> embedding) {
    return prepareDerived(assetId, "CONCEPT", projection, evidenceIds, context, embedding, true);
  }

  private Runnable prepareDerived(
      String id,
      String type,
      String content,
      List<String> evidenceIds,
      DerivedContext context,
      List<Float> embedding,
      boolean active) {
    validateVector(embedding);
    Objects.requireNonNull(context, "derived context");
    ensureIndex(DIMENSIONS);
    Map<String, Object> attrs = authority.prepareAsset(id, type, content, evidenceIds, context);
    Object value = attrs.get("asset_version");
    long revision = value instanceof Number number ? number.longValue() : 0;
    String projectionId = revision > 0 ? id + ":r" + revision : id;
    // A delayed epoch writes its immutable physical row; it cannot overwrite the current row.
    upsert(
        List.of(row(projectionId, type, content, evidenceIds, context, embedding, active, attrs)),
        false);
    return revision > 0 ? () -> authority.publishAsset(id, revision) : () -> {};
  }

  private JsonObject row(
      String id,
      String type,
      String content,
      List<String> evidenceIds,
      DerivedContext context,
      List<Float> embedding,
      boolean active,
      Map<String, Object> attrs) {
    if (content == null
        || content.isBlank()
        || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
      throw new IllegalArgumentException(
          "projection content is empty or exceeds the Milvus field limit; split it first");
    int level = authority.securityLevel(context.versionIds());
    if (level < 10 || level > 127)
      throw new IllegalStateException("invalid authoritative security level");
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("knowledge_type", type);
    row.put("content", content);
    row.put("dense", embedding);
    row.put("security_level", level);
    row.put("is_active", active);
    row.put("document_ids", context.documentIds());
    row.put("version_ids", context.versionIds());
    row.put("evidence_ids", safe(evidenceIds));
    row.put("source_departments", context.sourceDepartments());
    row.put("material_categories", context.materialCategories());
    row.put("document_types", context.documentTypes());
    row.put("attributes", attrs);
    return gson.toJsonTree(row).getAsJsonObject();
  }

  private void upsert(List<JsonObject> rows, boolean partial) {
    if (rows.isEmpty()) return;
    var result =
        client.upsert(
            UpsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .partialUpdate(partial)
                .build());
    if (result.getUpsertCnt() != rows.size())
      throw new IllegalStateException("Milvus upsert count mismatch");
  }

  @Override
  public int indexedChunkCount(String versionId) {
    ensureIndex(DIMENSIONS);
    var rows =
        client.query(
            QueryReq.builder()
                .collectionName(collection)
                .filter(
                    "knowledge_type == \"RAW\" and ARRAY_CONTAINS(version_ids, "
                        + literal(versionId)
                        + ")")
                .outputFields(List.of("count(*)"))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
    return ((Number) rows.getQueryResults().get(0).getEntity().get("count(*)")).intValue();
  }

  @Override
  public void activateVersion(String documentId, String versionId) {
    ensureIndex(DIMENSIONS);
    patch(
        "knowledge_type == \"RAW\" and ARRAY_CONTAINS(document_ids, " + literal(documentId) + ")",
        row -> Map.of("is_active", strings(row.get("version_ids")).contains(versionId)));
  }

  @Override
  public void activateRaw(String document, String version, List<String> physicalIds) {
    // New attempts are immutable projections. PG's conditional publication pointer, checked in
    // the query predicate and after retrieval, owns visibility. No old attempt toggles another.
    if (physicalIds == null || physicalIds.isEmpty())
      throw new IllegalArgumentException("raw physical membership required");
  }

  @Override
  public void deactivateVersion(String versionId) {
    ensureIndex(DIMENSIONS);
    patch(
        "ARRAY_CONTAINS(version_ids, " + literal(versionId) + ")",
        row -> Map.of("is_active", false));
  }

  @Override
  public void deleteVersion(String versionId) {
    ensureIndex(DIMENSIONS);
    client.delete(
        DeleteReq.builder()
            .collectionName(collection)
            .filter(
                "knowledge_type == \"RAW\" and ARRAY_CONTAINS(version_ids, "
                    + literal(versionId)
                    + ")")
            .build());
  }

  @Override
  public void deleteVersionExcept(String versionId, Set<String> keepChunkIds) {
    ensureIndex(DIMENSIONS);
    String filter =
        "knowledge_type == \"RAW\" and ARRAY_CONTAINS(version_ids, " + literal(versionId) + ")";
    if (keepChunkIds != null && !keepChunkIds.isEmpty())
      filter += " and id not in " + gson.toJson(keepChunkIds);
    client.delete(DeleteReq.builder().collectionName(collection).filter(filter).build());
  }

  @Override
  public void updateMetadata(String versionId, Metadata metadata) {
    Objects.requireNonNull(metadata);
    ensureIndex(DIMENSIONS);
    patch(
        "ARRAY_CONTAINS(version_ids, " + literal(versionId) + ")",
        row -> {
          if (!"RAW".equals(row.get("knowledge_type"))) return Map.of("is_active", false);
          return Map.of(
              "source_departments",
              singleton(metadata.sourceDepartment()),
              "material_categories",
              singleton(metadata.materialCategory()),
              "document_types",
              singleton(metadata.documentType()),
              "security_level",
              authority.securityLevel(List.of(versionId)));
        });
  }

  private void patch(String expression, Function<Map<String, Object>, Map<String, Object>> change) {
    scan(
        expression,
        List.of("id", "knowledge_type", "version_ids"),
        row -> {
          var values = new LinkedHashMap<>(change.apply(row));
          values.put("id", row.get("id"));
          return gson.toJsonTree(values).getAsJsonObject();
        });
  }

  private void scan(
      String expression, List<String> fields, Function<Map<String, Object>, JsonObject> transform) {
    QueryIterator iterator =
        client.queryIterator(
            QueryIteratorReq.builder()
                .collectionName(collection)
                .expr(expression)
                .outputFields(fields)
                .batchSize(100)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
    try {
      while (true) {
        var batch = iterator.next();
        if (batch.isEmpty()) break;
        var changes =
            batch.stream()
                .map(row -> transform.apply(row.getFieldValues()))
                .filter(Objects::nonNull)
                .toList();
        // Milvus partial upsert requires an identical field set for every row in a batch.
        // Raw metadata updates and derived invalidations intentionally change different fields.
        Map<Set<String>, List<JsonObject>> groups = new LinkedHashMap<>();
        for (JsonObject change : changes)
          groups
              .computeIfAbsent(Set.copyOf(change.keySet()), ignored -> new ArrayList<>())
              .add(change);
        groups.values().forEach(group -> upsert(group, true));
      }
    } finally {
      iterator.close();
    }
  }

  @Override
  public List<Candidate> searchBm25(String query, SearchFilters filters, int limit) {
    return search(List.of("RAW"), LaneType.RAW, new EmbeddedText(query), "sparse", filters, limit);
  }

  public List<Candidate> searchAllBm25(String query, SearchFilters filters, int limit) {
    return search(
        List.of("RAW", "SUMMARY", "CONCEPT", "WIKI"),
        null,
        new EmbeddedText(query),
        "sparse",
        filters,
        limit);
  }

  public List<Candidate> searchAllVector(List<Float> vector, SearchFilters filters, int limit) {
    validateVector(vector);
    return search(
        List.of("RAW", "SUMMARY", "CONCEPT", "WIKI"),
        null,
        new FloatVec(vector),
        "dense",
        filters,
        limit);
  }

  @Override
  public List<Candidate> searchVector(List<Float> vector, SearchFilters filters, int limit) {
    validateVector(vector);
    return search(List.of("RAW"), LaneType.RAW, new FloatVec(vector), "dense", filters, limit);
  }

  @Override
  public List<Candidate> searchSummaryBm25(String query, SearchFilters filters, int limit) {
    return search(
        List.of("SUMMARY"), LaneType.SUMMARY, new EmbeddedText(query), "sparse", filters, limit);
  }

  @Override
  public List<Candidate> searchSummaryVector(List<Float> vector, SearchFilters filters, int limit) {
    validateVector(vector);
    return search(
        List.of("SUMMARY"), LaneType.SUMMARY, new FloatVec(vector), "dense", filters, limit);
  }

  @Override
  public List<Candidate> searchWikiBm25(String query, SearchFilters filters, int limit) {
    return search(
        List.of("CONCEPT", "WIKI"),
        LaneType.WIKI,
        new EmbeddedText(query),
        "sparse",
        filters,
        limit);
  }

  @Override
  public List<Candidate> searchWikiVector(List<Float> vector, SearchFilters filters, int limit) {
    validateVector(vector);
    return search(
        List.of("CONCEPT", "WIKI"), LaneType.WIKI, new FloatVec(vector), "dense", filters, limit);
  }

  private List<Candidate> search(
      List<String> types,
      LaneType lane,
      BaseVector data,
      String field,
      SearchFilters filters,
      int limit) {
    ensureIndex(DIMENSIONS);
    SearchFilters scope =
        filters == null ? new SearchFilters(null, null, null, List.of()) : filters;
    var result =
        client.search(
            SearchReq.builder()
                .collectionName(collection)
                .annsField(field)
                .data(List.of(data))
                .filter(searchFilter(types, scope))
                .limit(limit <= 0 ? 50 : Math.min(50, limit))
                .outputFields(OUTPUT)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .searchParams(field.equals("dense") ? Map.of("ef", 128) : Map.of())
                .build());
    List<Candidate> out = new ArrayList<>();
    for (var hit : result.getSearchResults().get(0)) {
      var row = hit.getEntity();
      var versions = strings(row.get("version_ids"));
      if (!authority.accessible(versions, scope)) continue;
      if ("RAW".equals(row.get("knowledge_type"))
          && !authority.rawProjectionCurrent(hit.getId().toString(), versions)) continue;
      var docs = strings(row.get("document_ids"));
      Map<String, Object> attributes = new LinkedHashMap<>();
      Object storedAttributes = row.get("attributes");
      if (storedAttributes != null) {
        if (!(storedAttributes instanceof Map<?, ?> || storedAttributes instanceof JsonObject))
          throw new IllegalStateException("invalid Milvus projection attributes");
        // The official SDK returns JSON fields as Gson objects, not always Java Maps.
        Map<String, Object> decoded =
            gson.fromJson(
                gson.toJsonTree(storedAttributes),
                new TypeToken<Map<String, Object>>() {}.getType());
        attributes.putAll(decoded);
      }
      attributes.put("source_document_ids", docs);
      attributes.put("source_version_ids", versions);
      attributes.put("knowledge_type", row.get("knowledge_type"));
      String logicalId = hit.getId().toString();
      if (attributes.containsKey("asset_version")) {
        Object revision = attributes.get("asset_version");
        Object assetId = attributes.get("asset_id");
        if (!(revision instanceof Number number)
            || number.longValue() <= 0
            || number.doubleValue() != number.longValue()
            || !(assetId instanceof String id)
            || !(logicalId.equals(id) || logicalId.equals(id + ":r" + number.longValue())))
          throw new IllegalStateException("invalid entry projection revision");
        logicalId = assetId.toString();
        var sources = authority.currentAssetSources(logicalId, number.longValue(), scope);
        if (sources.isEmpty()) continue;
        attributes.put("sourceRefs", sources.get());
      } else if (!"RAW".equals(row.get("knowledge_type"))
          && !authority.legacyProjectionCurrent(logicalId)) {
        continue;
      }
      out.add(
          new Candidate(
              logicalId,
              docs.isEmpty() ? null : docs.get(0),
              versions.isEmpty() ? null : versions.get(0),
              lane == null ? laneFor(row.get("knowledge_type").toString()) : lane,
              row.get("content").toString(),
              hit.getScore(),
              strings(row.get("evidence_ids")),
              attributes));
    }
    var unique = new LinkedHashMap<String, Candidate>();
    for (var candidate : out)
      unique.merge(
          candidate.id(), candidate, (left, right) -> left.score() >= right.score() ? left : right);
    return List.copyOf(unique.values());
  }

  private static LaneType laneFor(String type) {
    return switch (type) {
      case "RAW" -> LaneType.RAW;
      case "SUMMARY" -> LaneType.SUMMARY;
      case "CONCEPT", "WIKI" -> LaneType.WIKI;
      default -> throw new IllegalStateException("unknown Milvus knowledge type");
    };
  }

  private String searchFilter(List<String> types, SearchFilters filters) {
    List<String> terms = new ArrayList<>();
    terms.add("is_active == true");
    terms.add("security_level <= " + filters.securityClearance());
    terms.add("knowledge_type in " + gson.toJson(types));
    if (types.contains("RAW"))
      authority
          .rawVisibility(filters)
          .ifPresent(
              visibility -> {
                List<String> predicates = new ArrayList<>();
                if (!visibility.getOrDefault("scopes", List.of()).isEmpty())
                  predicates.add(
                      "attributes[\"raw_scope\"] in " + gson.toJson(visibility.get("scopes")));
                if (!visibility.getOrDefault("legacyVersions", List.of()).isEmpty())
                  predicates.add(
                      "(not exists attributes[\"raw_scope\"] and ARRAY_CONTAINS_ANY(version_ids, "
                          + gson.toJson(visibility.get("legacyVersions"))
                          + "))");
                terms.add(
                    "(knowledge_type != \"RAW\" or "
                        + (predicates.isEmpty()
                            ? "is_active == false"
                            : ("(" + String.join(" or ", predicates) + ")"))
                        + ")");
              });
    addFilter(terms, "source_departments", filters.sourceDepartment());
    addFilter(terms, "material_categories", filters.materialCategory());
    addFilter(terms, "document_types", filters.documentType());
    if (!filters.versionIds().isEmpty())
      terms.add("ARRAY_CONTAINS_ANY(version_ids, " + gson.toJson(filters.versionIds()) + ")");
    return String.join(" and ", terms);
  }

  private void addFilter(List<String> terms, String field, String value) {
    if (value != null && !value.isBlank())
      terms.add("ARRAY_CONTAINS(" + field + ", " + literal(value) + ")");
  }

  private String literal(String value) {
    return gson.toJson(Objects.requireNonNull(value));
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static List<String> singleton(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(value);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static List<String> strings(Object values) {
    return values instanceof List<?> list
        ? list.stream().map(Object::toString).toList()
        : List.of();
  }

  private static List<String> evidenceIds(List<Claim> claims) {
    return safe(claims).stream().flatMap(c -> c.evidenceIds().stream()).distinct().toList();
  }

  private static boolean published(String status) {
    return "ACTIVE".equals(status) || "GROUNDED".equals(status);
  }

  private static void validateVector(List<Float> vector) {
    if (vector == null
        || vector.size() != DIMENSIONS
        || vector.stream().anyMatch(v -> v == null || !Float.isFinite(v)))
      throw new IllegalArgumentException("a finite 1024-dimensional embedding is required");
  }
}
