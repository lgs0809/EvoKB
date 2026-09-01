package cn.lgs.knowledgebase;

import cn.lgs.knowledgebase.application.KnowledgeGenerationRecommendationService;
import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.evaluation.ArtifactEvaluationApplicationService;
import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.EffectiveConfiguration;
import cn.lgs.knowledgebase.application.operations.DocumentMetadataApplicationService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.query.AsyncKnowledgeQueryService;
import cn.lgs.knowledgebase.application.query.KnowledgeQueryOrchestrator;
import cn.lgs.knowledgebase.application.query.QueryHistoryApplicationService;
import cn.lgs.knowledgebase.application.raw.RawEmbeddingRebuildService;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.application.upload.MultipartUploadApplicationService;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import cn.lgs.knowledgebase.infrastructure.compilation.DurableCompilationWorker;
import cn.lgs.knowledgebase.infrastructure.compilation.DurableEmbeddingRebuildWorker;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.evidence.ObjectStoreSourcePackageService;
import cn.lgs.knowledgebase.infrastructure.graph.PostgresGraphStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.ingestion.DurableIngestionWorker;
import cn.lgs.knowledgebase.infrastructure.ingestion.RedisRawDeliveryQueue;
import cn.lgs.knowledgebase.infrastructure.model.AssistantEvidenceFallbackKnowledgeModel;
import cn.lgs.knowledgebase.infrastructure.model.JdbcModelConfigurationStore;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import cn.lgs.knowledgebase.infrastructure.parser.JacksonParsedArtifactEncoder;
import cn.lgs.knowledgebase.infrastructure.parser.MinerUPipelineAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.RoutingParserAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawPublicationWriter;
import cn.lgs.knowledgebase.infrastructure.search.CachedQueryEmbeddingPort;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import cn.lgs.knowledgebase.infrastructure.search.MilvusHybridRetrievalLane;
import cn.lgs.knowledgebase.infrastructure.search.MilvusKnowledgeIndexAdapter;
import cn.lgs.knowledgebase.infrastructure.security.BCryptPasswordHasher;
import cn.lgs.knowledgebase.infrastructure.storage.LocalFileSourceAdapter;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.upload.JdbcMultipartUploadRepository;
import cn.lgs.knowledgebase.infrastructure.upload.MinioMultipartUploadAdapter;
import cn.lgs.knowledgebase.infrastructure.upload.MultipartUploadRecovery;
import cn.lgs.knowledgebase.interfaces.evaluation.GoldenJsonlLoader;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseAuditInterceptor;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseSessionInterceptor;
import cn.lgs.knowledgebase.interfaces.http.SafeBatchUploadStager;
import cn.lgs.knowledgebase.interfaces.mcp.EnterpriseMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
public class EnterpriseConfiguration {
  @Bean
  JdbcIdentityRepository identityRepository(JdbcTemplate jdbc) {
    return new JdbcIdentityRepository(jdbc);
  }

  @Bean
  BCryptPasswordHasher passwordHasher() {
    return new BCryptPasswordHasher();
  }

  @Bean
  SpringTransactionAdapter transactionPort(TransactionTemplate tx) {
    return new SpringTransactionAdapter(tx);
  }

  @Bean
  IdentityApplicationService identityApplicationService(
      JdbcIdentityRepository repository,
      BCryptPasswordHasher hasher,
      SpringTransactionAdapter tx,
      Environment env) {
    return new IdentityApplicationService(
        repository,
        repository,
        hasher,
        tx,
        Duration.ofHours(integer(env, "EKB_SESSION_TTL_HOURS", 12)));
  }

  @Bean
  LocalFileSourceAdapter fileSource() {
    return new LocalFileSourceAdapter();
  }

  @Bean
  MinioObjectStoreAdapter objectStore(Environment env) {
    String endpoint = value(env, "EKB_MINIO_ENDPOINT", "127.0.0.1:19000");
    return new MinioObjectStoreAdapter(
        endpoint,
        value(env, "EKB_MINIO_PUBLIC_ENDPOINT", endpoint),
        required(env, "EKB_MINIO_ACCESS_KEY"),
        required(env, "EKB_MINIO_SECRET_KEY"),
        value(env, "EKB_MINIO_BUCKET", "enterprise-kb"),
        value(env, "EKB_MINIO_REGION", "us-east-1"));
  }

  @Bean
  TextDocumentParserAdapter textParser(ObjectMapper mapper) {
    return new TextDocumentParserAdapter(mapper);
  }

  @Bean
  RoutingParserAdapter parser(
      TextDocumentParserAdapter textParser,
      ObjectMapper mapper,
      Environment env,
      ObjectStore objects) {
    String endpoint = value(env, "EKB_MINERU_URL", "");
    ParserPort minerUParser =
        endpoint.isBlank()
            ? null
            : new MinerUPipelineAdapter(
                endpoint,
                mapper,
                Duration.ofMillis(integer(env, "EKB_MINERU_POLL_MS", 500)),
                Duration.ofSeconds(integer(env, "EKB_MINERU_TASK_TIMEOUT_SECONDS", 600)),
                value(env, "EKB_MINERU_PARSE_METHOD", "auto"),
                value(env, "EKB_MINERU_LANGUAGE", "ch"),
                value(env, "EKB_MINERU_BACKEND", "hybrid-engine"),
                value(env, "EKB_MINERU_EFFORT", "medium"),
                value(env, "EKB_MINERU_VERSION", "3.4.5"),
                objects);
    return new RoutingParserAdapter(textParser, minerUParser);
  }

  @Bean
  JacksonParsedArtifactEncoder parsedArtifactEncoder(ObjectMapper mapper) {
    return new JacksonParsedArtifactEncoder(mapper);
  }

  @Bean
  JdbcModelConfigurationStore modelConfigurationStore(
      JdbcTemplate jdbc, TransactionTemplate tx, Environment env) {
    return new JdbcModelConfigurationStore(
        jdbc,
        tx,
        new EffectiveConfiguration(
            firstValue(env, "", "EKB_CHAT_BASE_URL", "EKB_ASK_BASE_URL", "EKB_LLM_BASE_URL"),
            firstValue(env, "", "EKB_CHAT_API_KEY", "EKB_ASK_API_KEY", "EKB_LLM_API_KEY"),
            firstValue(env, "", "EKB_SMALL_MODEL", "EKB_LLM_JUDGE_MODEL"),
            firstValue(env, "", "EKB_LARGE_MODEL", "EKB_LLM_MODEL", "EKB_ASK_MODEL"),
            integer(env, "EKB_CHAT_TIMEOUT_SECONDS", 120),
            firstValue(env, "", "EKB_EMBEDDING_BASE_URL", "EKB_MODELS_URL"),
            firstValue(env, "", "EKB_EMBEDDING_API_KEY", "EKB_MODELS_API_KEY"),
            firstValue(env, "", "EKB_EMBEDDING_MODEL", "EKB_EMBED_MODEL"),
            integer(env, "EKB_EMBED_DIMS", 1024),
            integer(env, "EKB_EMBED_BATCH_SIZE", 16),
            integer(env, "EKB_EMBED_TIMEOUT_SECONDS", 120),
            firstValue(env, "", "EKB_RERANK_BASE_URL", "EKB_MODELS_URL"),
            firstValue(env, "", "EKB_RERANK_API_KEY", "EKB_MODELS_API_KEY"),
            value(env, "EKB_RERANK_MODEL", ""),
            integer(env, "EKB_RERANK_TIMEOUT_SECONDS", 30),
            Instant.EPOCH));
  }

  @Bean
  ModelConfigurationApplicationService modelConfigurationApplicationService(
      JdbcModelConfigurationStore store) {
    return new ModelConfigurationApplicationService(store);
  }

  @Bean
  OpenAiCompatibleModelServingAdapter modelServing(
      JdbcModelConfigurationStore store, ObjectMapper mapper, Environment env) {
    return new OpenAiCompatibleModelServingAdapter(
        store,
        mapper,
        integer(env, "EKB_EMBED_MAX_CONCURRENCY", 1),
        integer(env, "EKB_RERANK_MAX_CONCURRENCY", 1));
  }

  @Bean
  JdbcKnowledgeRepository knowledgeRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcKnowledgeRepository(jdbc, tx, mapper);
  }

  @Bean(destroyMethod = "close")
  MilvusClientV2 milvusClient(Environment env) {
    return new MilvusClientV2(
        ConnectConfig.builder()
            .uri(value(env, "EKB_MILVUS_URL", "http://127.0.0.1:19535"))
            .token(value(env, "EKB_MILVUS_TOKEN", ""))
            .rpcDeadlineMs(60000)
            .build());
  }

  @Bean
  MilvusKnowledgeIndexAdapter knowledgeIndex(
      MilvusClientV2 client, JdbcTemplate jdbc, Environment env) {
    var index =
        new MilvusKnowledgeIndexAdapter(
            client,
            value(env, "EKB_MILVUS_COLLECTION", "knowledge_chunks"),
            new JdbcProjectionAuthority(jdbc));
    index.ensureIndex(1024);
    return index;
  }

  @Bean
  JdbcEnterpriseOperations operations(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Environment env) {
    return new JdbcEnterpriseOperations(
        jdbc, tx, mapper, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  DocumentMetadataApplicationService documentMetadataApplicationService(
      JdbcEnterpriseOperations operations,
      JdbcIdentityRepository identity,
      RawSearchIndex rawIndex,
      DerivedSearchIndex derivedIndex) {
    return new DocumentMetadataApplicationService(operations, identity, rawIndex, derivedIndex);
  }

  @Bean
  JdbcArtifactEvaluationRepository artifactEvaluationRepository(
      JdbcTemplate jdbc, Environment env) {
    return new JdbcArtifactEvaluationRepository(
        jdbc, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  ArtifactEvaluationApplicationService artifactEvaluationApplicationService(
      JdbcArtifactEvaluationRepository repository) {
    return new ArtifactEvaluationApplicationService(repository);
  }

  @Bean
  GoldenJsonlLoader goldenJsonlLoader(ObjectMapper mapper) {
    return new GoldenJsonlLoader(mapper);
  }

  @Bean
  PostgresGraphStoreAdapter graphStore(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Environment env) {
    return new PostgresGraphStoreAdapter(
        jdbc, tx, mapper, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  KnowledgeModel knowledgeModel(
      JdbcModelConfigurationStore store, ObjectMapper mapper, Environment env) {
    KnowledgeModel remote = new SpringAiAlibabaKnowledgeModel(store, mapper);
    String stagingRoot = value(env, "EKB_STAGING_ROOT", "/var/lib/enterprise-kb/staging");
    Path fallbackMarker = Path.of(stagingRoot).resolve(".assistant-evidence-fallback");
    return new AssistantEvidenceFallbackKnowledgeModel(
        remote, mapper, value(env, "EKB_ASSISTANT_FALLBACK", "disabled"), fallbackMarker);
  }

  @Bean
  JdbcKnowledgeGenerationRecommendationRepository knowledgeGenerationRecommendationRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Environment env) {
    return new JdbcKnowledgeGenerationRecommendationRepository(jdbc, tx, mapper);
  }

  @Bean
  KnowledgeGenerationRecommendationService knowledgeGenerationRecommendationService(
      JdbcKnowledgeGenerationRecommendationRepository repository) {
    return new KnowledgeGenerationRecommendationService(repository);
  }

  @Bean
  JdbcAuditAdapter auditPort(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new JdbcAuditAdapter(jdbc, mapper);
  }

  @Bean
  ObjectStoreSourcePackageService sourcePackages(
      EnterpriseOperations.Repository operations,
      ObjectStore objects,
      ObjectMapper mapper,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access,
      JdbcTemplate jdbc,
      org.springframework.transaction.support.TransactionTemplate transactions,
      JdbcQuerySessionRepository sessions) {
    return new ObjectStoreSourcePackageService(
        operations,
        objects,
        mapper,
        Duration.ofMinutes(10),
        access,
        new cn.lgs.knowledgebase.infrastructure.persistence.JdbcPackageAuthorizationRepository(
            jdbc, mapper),
        new cn.lgs.knowledgebase.application.usage.SourcePackageUsageService(
            sessions, access, new JdbcDownloadUsageRepository(jdbc, transactions)));
  }

  @Bean
  EvidenceResolver authoritativeEvidence(JdbcKnowledgeRepository knowledge) {
    return new EvidenceResolver() {
      public List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> resolve(
          List<String> ids) {
        return knowledge.resolveEvidence(ids);
      }

      public List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> resolve(
          List<String> ids,
          cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters) {
        return knowledge.resolveEvidence(ids, filters);
      }
    };
  }

  @Bean
  cn.lgs.knowledgebase.application.access.KnowledgeAccessService knowledgeAccess(
      JdbcIdentityRepository users,
      EvidenceResolver evidence,
      JdbcQuerySessionRepository sessions,
      JdbcTemplate jdbc) {
    return new cn.lgs.knowledgebase.application.access.KnowledgeAccessService(
        users, evidence, sessions, new JdbcProjectionAuthority(jdbc));
  }

  @Bean
  CachedQueryEmbeddingPort retrievalQueryEmbedding(OpenAiCompatibleModelServingAdapter models) {
    return new CachedQueryEmbeddingPort(models, 512);
  }

  @Bean
  RetrievalApplicationService retrievalApplicationService(
      RawSearchIndex rawIndex,
      DerivedSearchIndex derivedIndex,
      OpenAiCompatibleModelServingAdapter models,
      CachedQueryEmbeddingPort queryEmbedding,
      PostgresGraphStoreAdapter graph,
      JdbcKnowledgeRepository knowledge,
      JdbcTemplate jdbc,
      Environment env) {
    return new RetrievalApplicationService(
        List.of(
            new MilvusHybridRetrievalLane((MilvusKnowledgeIndexAdapter) rawIndex, queryEmbedding)),
        models,
        new EvidenceResolver() {
          public List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> resolve(
              List<String> ids) {
            return knowledge.resolveEvidence(ids);
          }

          public List<cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence> resolve(
              List<String> ids,
              cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters) {
            return knowledge.resolveEvidence(ids, filters);
          }
        },
        new Quotas(50, 0, 0, 10),
        20,
        24000,
        graph,
        new JdbcProjectionAuthority(jdbc));
  }

  @Bean
  AskApplicationService askApplicationService(
      RetrievalApplicationService retrieval,
      KnowledgeModel model,
      Environment env,
      cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration encoding) {
    return new AskApplicationService(
        retrieval, model, integer(env, "EKB_QUERY_CONTEXT_CHARS", 24000), encoding.tokens());
  }

  @Bean
  JdbcQuerySessionRepository querySessionRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcQuerySessionRepository(jdbc, tx, mapper);
  }

  @Bean
  QueryHistoryApplicationService queryHistoryApplicationService(
      JdbcQuerySessionRepository sessions,
      ObjectStoreSourcePackageService packages,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access) {
    return new QueryHistoryApplicationService(sessions, sessions, packages, access);
  }

  @Bean
  KnowledgeQueryOrchestrator knowledgeQueryOrchestrator(
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      KnowledgeModel model,
      JdbcQuerySessionRepository sessions,
      JdbcKnowledgeRepository knowledge,
      EvidenceResolver evidence,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access,
      Environment env) {
    return new KnowledgeQueryOrchestrator(
        retrieval,
        ask,
        model,
        sessions,
        evidence,
        integer(env, "EKB_QUERY_MAX_RETRIEVAL_ROUNDS", 8),
        integer(env, "EKB_QUERY_MAX_TOTAL_QUERIES", 8),
        integer(env, "EKB_QUERY_MAX_QUERIES_PER_ROUND", 3),
        integer(env, "EKB_QUERY_EVIDENCE_CONTEXT_CHARS", 12000),
        access);
  }

  @Bean
  JdbcMultipartUploadRepository multipartUploadRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Environment env) {
    return new JdbcMultipartUploadRepository(
        jdbc,
        tx,
        mapper,
        Path.of(value(env, "EKB_STAGING_ROOT", "/var/lib/enterprise-kb/staging")));
  }

  @Bean(destroyMethod = "close")
  MinioMultipartUploadAdapter multipartUploadStore(Environment env) {
    String endpoint = value(env, "EKB_MINIO_ENDPOINT", "http://127.0.0.1:19000");
    String publicEndpoint = value(env, "EKB_MINIO_PUBLIC_ENDPOINT", endpoint);
    return new MinioMultipartUploadAdapter(
        endpoint.contains("://") ? endpoint : "http://" + endpoint,
        publicEndpoint.contains("://") ? publicEndpoint : "http://" + publicEndpoint,
        required(env, "EKB_MINIO_ACCESS_KEY"),
        required(env, "EKB_MINIO_SECRET_KEY"),
        value(env, "EKB_MINIO_BUCKET", "enterprise-kb"),
        value(env, "EKB_MINIO_REGION", "us-east-1"));
  }

  @Bean
  MultipartUploadApplicationService multipartUploads(
      JdbcMultipartUploadRepository sessions,
      MinioMultipartUploadAdapter store,
      ManagedIngestionApplicationService permissions,
      Environment env) {
    long effectiveLimit =
        Math.min(
            env.getProperty("EKB_MAX_UPLOAD_BYTES", Long.class, 536870912L),
            env.getProperty("EKB_MINERU_MAX_FILE_BYTES", Long.class, 268435456L));
    return new MultipartUploadApplicationService(
        sessions,
        store,
        permissions,
        value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"),
        effectiveLimit,
        env.getProperty("EKB_MULTIPART_RESERVED_BYTES", Long.class, 1073741824L));
  }

  @Bean
  MultipartUploadRecovery multipartUploadRecovery(MultipartUploadApplicationService uploads) {
    return new MultipartUploadRecovery(uploads);
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService knowledgeQueryExecutor(Environment env) {
    return Executors.newFixedThreadPool(integer(env, "EKB_QUERY_WORKER_THREADS", 2));
  }

  @Bean
  AsyncKnowledgeQueryService asyncKnowledgeQueryService(
      KnowledgeQueryOrchestrator orchestrator, ExecutorService knowledgeQueryExecutor) {
    return new AsyncKnowledgeQueryService(orchestrator, knowledgeQueryExecutor);
  }

  @Bean
  cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration rawEncodingConfiguration(
      JdbcModelConfigurationStore store, ObjectMapper mapper, Environment env) {
    return new cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration(
        value(env, "EKB_EMBED_TOKENIZER_MANIFEST", ""),
        mapper,
        store,
        "whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1");
  }

  @Bean
  cn.lgs.knowledgebase.application.raw.RawVectorEncoder rawVectorEncoder(
      OpenAiCompatibleModelServingAdapter models,
      cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration encoding,
      JdbcTemplate jdbc,
      ObjectMapper mapper) {
    return new cn.lgs.knowledgebase.application.raw.RawVectorEncoder(
        models.rawEmbeddingPort(),
        encoding.profiles(),
        new cn.lgs.knowledgebase.infrastructure.raw.JdbcRawVectorCache(jdbc, mapper),
        true);
  }

  @Bean
  RawIngestionApplicationService rawIngestionApplicationService(
      LocalFileSourceAdapter files,
      ObjectStore objects,
      JdbcKnowledgeRepository knowledge,
      RoutingParserAdapter parser,
      JacksonParsedArtifactEncoder encoder,
      RawSearchIndex rawIndex,
      OpenAiCompatibleModelServingAdapter models,
      DerivedSearchIndex derived,
      cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration encoding,
      cn.lgs.knowledgebase.application.raw.RawVectorEncoder vectors) {
    return new RawIngestionApplicationService(
        files,
        objects,
        knowledge,
        parser,
        encoder,
        rawIndex,
        models,
        derived,
        encoding.tokens(),
        cn.lgs.knowledgebase.application.raw.RawChunkProfile.DEFAULT,
        vectors);
  }

  @Bean
  JdbcIngestionRepository ingestionRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcIngestionRepository(jdbc, tx, mapper);
  }

  @Bean
  ManagedIngestionApplicationService managedIngestion(
      JdbcIngestionRepository uploads,
      JdbcIdentityRepository identity,
      LocalFileSourceAdapter files,
      JdbcGenerationTaskRepository generationTasks) {
    return new ManagedIngestionApplicationService(
        uploads,
        identity,
        files,
        versionId ->
            generationTasks.queue(
                versionId,
                cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType
                    .SOURCE_ASSOCIATION,
                "",
                java.time.Instant.now()));
  }

  @Bean(destroyMethod = "shutdown")
  org.redisson.api.RedissonClient rawRecoveryLocks(Environment env) {
    var config = new org.redisson.config.Config();
    config.setThreads(2).setNettyThreads(2);
    config
        .useSingleServer()
        .setAddress(
            "redis://"
                + value(env, "EKB_REDIS_HOST", "127.0.0.1")
                + ":"
                + integer(env, "EKB_REDIS_PORT", 16385))
        .setDatabase(integer(env, "EKB_REDIS_DATABASE", 0))
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(4);
    return org.redisson.Redisson.create(config);
  }

  @Bean
  RedisRawDeliveryQueue rawDeliveryQueue(
      StringRedisTemplate redis,
      JdbcIngestionRepository uploads,
      org.redisson.api.RedissonClient rawRecoveryLocks,
      Environment env) {
    return new RedisRawDeliveryQueue(
        redis,
        uploads,
        value(env, "EKB_RAW_STREAM", "evokb:target:raw:v1"),
        value(env, "EKB_RAW_STREAM_GROUP", "raw-ingestion"),
        java.util.UUID.randomUUID().toString(),
        rawRecoveryLocks);
  }

  @Bean
  DurableIngestionWorker ingestionWorker(
      JdbcIngestionRepository uploads,
      RawIngestionApplicationService raw,
      LocalFileSourceAdapter files,
      JdbcGenerationTaskRepository generationTasks,
      RedisRawDeliveryQueue deliveries,
      ObjectStore originalObjects,
      Environment env) {
    return new DurableIngestionWorker(
        uploads,
        raw,
        files,
        generationTasks,
        Duration.ofMillis(integer(env, "EKB_INGEST_POLL_MS", 1000)),
        integer(env, "EKB_INGEST_CLAIM_LIMIT", 1),
        integer(env, "EKB_INGEST_WORKERS", 3),
        deliveries,
        originalObjects);
  }

  @Bean
  JdbcCompilationRepository compilationRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, ObjectStore objects) {
    return new JdbcCompilationRepository(jdbc, tx, mapper, objects);
  }

  @Bean
  SummaryCompilationService summaryCompilation(
      CompilationContracts.CompilationRepository repo,
      KnowledgeModel model,
      ObjectStore objects,
      OpenAiCompatibleModelServingAdapter embeddings,
      DerivedSearchIndex derived) {
    return new SummaryCompilationService(repo, model, objects, embeddings, derived);
  }

  @Bean
  ConceptCompilationService conceptCompilation(
      CompilationContracts.CompilationRepository repo,
      KnowledgeModel model,
      OpenAiCompatibleModelServingAdapter embeddings,
      DerivedSearchIndex derived,
      KnowledgeEntryCatalogue entries) {
    return new ConceptCompilationService(repo, model, embeddings, derived, entries);
  }

  @Bean
  KnowledgeEntryCatalogue knowledgeEntries(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Environment env) {
    return new JdbcKnowledgeEntryCatalogue(
        jdbc, tx, mapper, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  GraphCompilationService graphCompilation(
      CompilationContracts.CompilationRepository repo, KnowledgeModel model, GraphStore graph) {
    return new GraphCompilationService(repo, model, graph);
  }

  @Bean
  JdbcWikiKeywordProjectionRepository wikiKeywordProjectionRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcWikiKeywordProjectionRepository(jdbc, tx, mapper);
  }

  @Bean
  WikiKeywordProjectionService wikiKeywordProjection(
      JdbcWikiKeywordProjectionRepository repository, GraphStore graph) {
    return new WikiKeywordProjectionService(repository, graph);
  }

  @Bean
  WikiCompilationService wikiCompilation(
      CompilationContracts.CompilationRepository repo,
      KnowledgeModel model,
      ObjectStore objects,
      OpenAiCompatibleModelServingAdapter embeddings,
      DerivedSearchIndex derived) {
    return new WikiCompilationService(repo, model, objects, embeddings, derived);
  }

  @Bean
  JdbcGenerationTaskRepository generationTaskRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, Environment env) {
    return new JdbcGenerationTaskRepository(
        jdbc, tx, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  KnowledgeGenerationApplicationService knowledgeGenerationApplicationService(
      CompilationContracts.CompilationRepository compilation, JdbcGenerationTaskRepository tasks) {
    return new KnowledgeGenerationApplicationService(compilation, tasks);
  }

  @Bean
  EmbeddingRebuildCheckpointRepository embeddingRebuildCheckpointRepository(JdbcTemplate jdbc) {
    return new JdbcEmbeddingRebuildCheckpointRepository(jdbc);
  }

  @Bean
  RawEmbeddingRebuildService rawEmbeddingRebuildService(
      CompilationContracts.CompilationRepository compilation,
      RawSearchIndex rawIndex,
      OpenAiCompatibleModelServingAdapter models,
      EmbeddingRebuildCheckpointRepository checkpoints,
      cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration encoding,
      cn.lgs.knowledgebase.application.raw.RawVectorEncoder vectors) {
    return new RawEmbeddingRebuildService(
        compilation,
        rawIndex,
        models,
        checkpoints,
        encoding.tokens(),
        cn.lgs.knowledgebase.application.raw.RawChunkProfile.DEFAULT,
        vectors);
  }

  @Bean
  SelectiveGenerationScheduler selectiveGenerationScheduler(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      JdbcGenerationTaskRepository tasks,
      JdbcKnowledgeEntryCatalogue entries,
      Environment env) {
    return new SelectiveGenerationScheduler(
        jdbc, tx, tasks, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"), entries);
  }

  @Bean
  JdbcRawPublicationWriter rawPublicationWriter(JdbcTemplate jdbc) {
    return new JdbcRawPublicationWriter(jdbc);
  }

  @Bean
  DurableEmbeddingRebuildWorker embeddingRebuildWorker(
      JdbcGenerationTaskRepository tasks,
      RawEmbeddingRebuildService rebuild,
      JdbcRawPublicationWriter writer,
      Environment env) {
    return new DurableEmbeddingRebuildWorker(
        tasks,
        rebuild,
        Duration.ofMillis(integer(env, "EKB_EMBEDDING_REBUILD_POLL_MS", 1000)),
        writer);
  }

  @Bean
  EntryAssociationService entryAssociationService(
      CompilationContracts.CompilationRepository compilation,
      KnowledgeEntryCatalogue entries,
      KnowledgeModel model,
      JdbcGenerationTaskRepository tasks) {
    return new EntryAssociationService(compilation, entries, model, tasks);
  }

  @Bean
  JdbcCompilationCommandRepository compilationCommands(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ObjectMapper mapper,
      JdbcGenerationTaskRepository tasks,
      JdbcIdentityRepository users,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access,
      Environment env) {
    return new JdbcCompilationCommandRepository(
        jdbc, tx, mapper, tasks, users, access, value(env, "EKB_KNOWLEDGE_BASE_ID", "enterprise"));
  }

  @Bean
  DurableCompilationWorker compilationWorker(
      JdbcGenerationTaskRepository tasks,
      CompilationContracts.CompilationRepository compilation,
      SummaryCompilationService summaries,
      ConceptCompilationService concepts,
      GraphCompilationService graph,
      WikiCompilationService wiki,
      WikiKeywordProjectionService projection,
      KnowledgeEntryCatalogue entries,
      EntryAssociationService association,
      JdbcCompilationCommandRepository commands,
      Environment env) {
    return new DurableCompilationWorker(
        tasks,
        compilation,
        summaries,
        concepts,
        graph,
        wiki,
        projection,
        Duration.ofMillis(integer(env, "EKB_COMPILATION_POLL_MS", 1000)),
        integer(env, "EKB_COMPILATION_CLAIM_LIMIT", 1),
        entries,
        association,
        commands);
  }

  @Bean
  SafeBatchUploadStager batchUploadStager(Environment env) {
    return new SafeBatchUploadStager(
        Path.of(
            value(
                env,
                "EKB_STAGING_ROOT",
                System.getProperty("java.io.tmpdir") + "/enterprise-kb/staging")),
        mib(env, "EKB_MAX_BATCH_UPLOAD_MIB", 4096),
        mib(env, "EKB_MAX_BATCH_EXPANDED_MIB", 16384),
        integer(env, "EKB_MAX_BATCH_FILES", 2000));
  }

  @Bean
  EnterpriseSessionInterceptor sessionInterceptor(
      IdentityApplicationService identity, Environment env) {
    return new EnterpriseSessionInterceptor(
        identity, value(env, "EKB_SESSION_COOKIE_NAME", "ekb_session"));
  }

  @Bean
  EnterpriseAuditInterceptor auditInterceptor(AuditPort audit, Environment env) {
    return new EnterpriseAuditInterceptor(
        audit, Boolean.parseBoolean(value(env, "EKB_TRUST_PROXY_HEADERS", "false")));
  }

  @Bean
  WebMvcConfigurer enterpriseWebMvcConfigurer(
      EnterpriseSessionInterceptor session, EnterpriseAuditInterceptor audit, Environment env) {
    String webDir = value(env, "EKB_WEB_DIR", Path.of("web", "dist").toString());
    String webLocation = Path.of(webDir).toAbsolutePath().normalize().toUri().toString();
    if (!webLocation.endsWith("/")) webLocation += "/";
    String finalWebLocation = webLocation;
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit).addPathPatterns("/api/enterprise/**");
        registry
            .addInterceptor(session)
            .addPathPatterns("/api/enterprise/**")
            .excludePathPatterns(
                "/api/enterprise/auth/status",
                "/api/enterprise/auth/setup",
                "/api/enterprise/auth/login");
      }

      @Override
      public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler("/index.html", "/favicon.svg", "/icons.svg")
            .addResourceLocations(finalWebLocation);
        registry
            .addResourceHandler("/assets/**")
            .addResourceLocations(finalWebLocation + "assets/");
      }
    };
  }

  @Bean
  EnterpriseMcpTools enterpriseMcpTools(
      RetrievalApplicationService retrieval,
      KnowledgeGenerationRecommendationService recommendations,
      SourcePackageService packages,
      cn.lgs.knowledgebase.application.access.KnowledgeAccessService access) {
    return new EnterpriseMcpTools(retrieval, recommendations, packages, access);
  }

  @Bean
  io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider
      authenticatedMcpTransport(
          @org.springframework.beans.factory.annotation.Qualifier("mcpServerObjectMapper")
              ObjectMapper mapper,
          org.springframework.ai.mcp.server.common.autoconfigure.properties
                  .McpServerStreamableHttpProperties
              properties) {
    return io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider
        .builder()
        .jsonMapper(new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(mapper))
        .mcpEndpoint(properties.getMcpEndpoint())
        .disallowDelete(properties.isDisallowDelete())
        .keepAliveInterval(properties.getKeepAliveInterval())
        .contextExtractor(cn.lgs.knowledgebase.interfaces.mcp.McpIdentityContext::extract)
        .build();
  }

  @Bean
  cn.lgs.knowledgebase.interfaces.http.EnterpriseMcpAuthenticationFilter mcpAuthenticationFilter(
      IdentityApplicationService identity,
      JdbcTemplate jdbc,
      Environment env,
      org.springframework.ai.mcp.server.common.autoconfigure.properties
              .McpServerStreamableHttpProperties
          properties) {
    return new cn.lgs.knowledgebase.interfaces.http.EnterpriseMcpAuthenticationFilter(
        identity,
        new JdbcMcpSessionRepository(jdbc),
        value(env, "EKB_SESSION_COOKIE_NAME", EnterpriseSessionInterceptor.SESSION_COOKIE),
        properties.getMcpEndpoint());
  }

  @Bean
  ToolCallbackProvider enterpriseToolCallbackProvider(EnterpriseMcpTools tools) {
    return MethodToolCallbackProvider.builder().toolObjects(tools).build();
  }

  private static String required(Environment env, String name) {
    String v = env.getProperty(name);
    if (v == null || v.isBlank()) throw new IllegalStateException(name + " is required");
    return v.trim();
  }

  private static String value(Environment env, String name, String fallback) {
    String v = env.getProperty(name);
    return v == null || v.isBlank() ? fallback : v.trim();
  }

  private static String firstValue(Environment env, String fallback, String... names) {
    for (String name : names) {
      String value = env.getProperty(name);
      if (value != null && !value.isBlank()) return value.trim();
    }
    return fallback;
  }

  private static int integer(Environment env, String name, int fallback) {
    try {
      return Integer.parseInt(value(env, name, Integer.toString(fallback)));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static long mib(Environment env, String name, long fallbackMiB) {
    String v = value(env, name, Long.toString(fallbackMiB));
    try {
      return Math.multiplyExact(Long.parseLong(v), 1024L * 1024L);
    } catch (Exception e) {
      return fallbackMiB * 1024L * 1024L;
    }
  }
}
