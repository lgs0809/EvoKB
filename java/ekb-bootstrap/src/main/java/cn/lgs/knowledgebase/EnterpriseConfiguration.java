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
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import cn.lgs.knowledgebase.infrastructure.compilation.DurableCompilationWorker;
import cn.lgs.knowledgebase.infrastructure.compilation.DurableEmbeddingRebuildWorker;
import cn.lgs.knowledgebase.infrastructure.evidence.ObjectStoreSourcePackageService;
import cn.lgs.knowledgebase.infrastructure.graph.GraphRetrievalLane;
import cn.lgs.knowledgebase.infrastructure.graph.Neo4jGraphStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.ingestion.DurableIngestionWorker;
import cn.lgs.knowledgebase.infrastructure.model.JdbcModelConfigurationStore;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.model.AssistantEvidenceFallbackKnowledgeModel;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import cn.lgs.knowledgebase.infrastructure.parser.JacksonParsedArtifactEncoder;
import cn.lgs.knowledgebase.infrastructure.parser.MinerUPipelineAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.RoutingParserAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.CachedQueryEmbeddingPort;
import cn.lgs.knowledgebase.infrastructure.search.HybridRetrievalLanes;
import cn.lgs.knowledgebase.infrastructure.search.OpenSearchDerivedIndexAdapter;
import cn.lgs.knowledgebase.infrastructure.search.OpenSearchRawIndexAdapter;
import cn.lgs.knowledgebase.infrastructure.security.BCryptPasswordHasher;
import cn.lgs.knowledgebase.infrastructure.storage.LocalFileSourceAdapter;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import cn.lgs.knowledgebase.interfaces.evaluation.GoldenJsonlLoader;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseAuditInterceptor;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseSessionInterceptor;
import cn.lgs.knowledgebase.interfaces.http.SafeBatchUploadStager;
import cn.lgs.knowledgebase.interfaces.mcp.EnterpriseMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
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
  MinerUPipelineAdapter minerUParser(ObjectMapper mapper, Environment env) {
    return new MinerUPipelineAdapter(
        required(env, "EKB_MINERU_URL"),
        mapper,
        Duration.ofMillis(integer(env, "EKB_MINERU_POLL_MS", 500)),
        Duration.ofSeconds(integer(env, "EKB_MINERU_TASK_TIMEOUT_SECONDS", 14400)),
        value(env, "EKB_MINERU_PARSE_METHOD", "auto"),
        value(env, "EKB_MINERU_LANGUAGE", "ch"));
  }

  @Bean
  TextDocumentParserAdapter textParser(ObjectMapper mapper) {
    return new TextDocumentParserAdapter(mapper);
  }

  @Bean
  RoutingParserAdapter parser(
      TextDocumentParserAdapter textParser, MinerUPipelineAdapter minerUParser) {
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

  @Bean
  OpenSearchRawIndexAdapter rawIndex(ObjectMapper mapper, Environment env) {
    return new OpenSearchRawIndexAdapter(
        value(env, "EKB_OPENSEARCH_URL", "http://127.0.0.1:19200"),
        value(env, "EKB_RAW_INDEX", "ekb_raw_v1"),
        mapper);
  }

  @Bean
  OpenSearchDerivedIndexAdapter derivedIndex(ObjectMapper mapper, Environment env) {
    return new OpenSearchDerivedIndexAdapter(
        value(env, "EKB_OPENSEARCH_URL", "http://127.0.0.1:19200"),
        value(env, "EKB_DERIVED_INDEX", "ekb_derived_v1"),
        mapper);
  }

  @Bean
  JdbcEnterpriseOperations operations(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcEnterpriseOperations(jdbc, tx, mapper);
  }

  @Bean
  DocumentMetadataApplicationService documentMetadataApplicationService(
      JdbcEnterpriseOperations operations,
      JdbcIdentityRepository identity,
      OpenSearchRawIndexAdapter rawIndex,
      OpenSearchDerivedIndexAdapter derivedIndex) {
    return new DocumentMetadataApplicationService(operations, identity, rawIndex, derivedIndex);
  }

  @Bean
  JdbcArtifactEvaluationRepository artifactEvaluationRepository(JdbcTemplate jdbc) {
    return new JdbcArtifactEvaluationRepository(jdbc);
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
  Neo4jGraphStoreAdapter graphStore(JdbcTemplate jdbc, ObjectMapper mapper, Environment env) {
    String[] auth = required(env, "EKB_NEO4J_AUTH").split("/", 2);
    if (auth.length != 2 || auth[0].isBlank() || auth[1].isBlank()) {
      throw new IllegalStateException("EKB_NEO4J_AUTH must use username/password format");
    }
    return new Neo4jGraphStoreAdapter(
        value(env, "EKB_NEO4J_URL", "http://127.0.0.1:17474"),
        auth[0],
        auth[1],
        value(env, "EKB_NEO4J_DATABASE", "neo4j"),
        mapper,
        jdbc);
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
    return new JdbcKnowledgeGenerationRecommendationRepository(
        jdbc, tx, mapper, integer(env, "EKB_GENERATION_EFFECTIVE_HIT_THRESHOLD", 3));
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
      EnterpriseOperations.Repository operations, ObjectStore objects, ObjectMapper mapper) {
    return new ObjectStoreSourcePackageService(operations, objects, mapper, Duration.ofMinutes(10));
  }

  @Bean
  CachedQueryEmbeddingPort retrievalQueryEmbedding(OpenAiCompatibleModelServingAdapter models) {
    return new CachedQueryEmbeddingPort(models, 512);
  }

  @Bean
  RetrievalApplicationService retrievalApplicationService(
      OpenSearchRawIndexAdapter rawIndex,
      OpenSearchDerivedIndexAdapter derivedIndex,
      OpenAiCompatibleModelServingAdapter models,
      CachedQueryEmbeddingPort queryEmbedding,
      Neo4jGraphStoreAdapter graph,
      JdbcKnowledgeRepository knowledge,
      Environment env) {
    List<RetrievalLane> lanes =
        List.of(
            new HybridRetrievalLanes.Raw(rawIndex, queryEmbedding),
            new HybridRetrievalLanes.Summary(derivedIndex, queryEmbedding),
            new HybridRetrievalLanes.Wiki(derivedIndex, queryEmbedding),
            new GraphRetrievalLane(graph));
    return new RetrievalApplicationService(
        lanes,
        models,
        knowledge::resolveEvidence,
        Quotas.defaults(),
        integer(env, "EKB_RERANK_MAX_CANDIDATES", 20),
        integer(env, "EKB_RERANK_MAX_CHARS", 400));
  }

  @Bean
  AskApplicationService askApplicationService(
      RetrievalApplicationService retrieval, KnowledgeModel model, Environment env) {
    return new AskApplicationService(
        retrieval, model, integer(env, "EKB_QUERY_CONTEXT_CHARS", 24000));
  }

  @Bean
  JdbcQuerySessionRepository querySessionRepository(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    return new JdbcQuerySessionRepository(jdbc, tx, mapper);
  }

  @Bean
  QueryHistoryApplicationService queryHistoryApplicationService(
      JdbcQuerySessionRepository sessions, ObjectStoreSourcePackageService packages) {
    return new QueryHistoryApplicationService(sessions, sessions, packages);
  }

  @Bean
  KnowledgeQueryOrchestrator knowledgeQueryOrchestrator(
      RetrievalApplicationService retrieval,
      AskApplicationService ask,
      KnowledgeModel model,
      JdbcQuerySessionRepository sessions,
      JdbcKnowledgeRepository knowledge,
      Environment env) {
    return new KnowledgeQueryOrchestrator(
        retrieval,
        ask,
        model,
        sessions,
        knowledge::resolveEvidence,
        integer(env, "EKB_QUERY_MAX_RETRIEVAL_ROUNDS", 3),
        integer(env, "EKB_QUERY_MAX_TOTAL_QUERIES", 8),
        integer(env, "EKB_QUERY_MAX_QUERIES_PER_ROUND", 3),
        integer(env, "EKB_QUERY_EVIDENCE_CONTEXT_CHARS", 12000));
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
  RawIngestionApplicationService rawIngestionApplicationService(
      LocalFileSourceAdapter files,
      ObjectStore objects,
      JdbcKnowledgeRepository knowledge,
      RoutingParserAdapter parser,
      JacksonParsedArtifactEncoder encoder,
      OpenSearchRawIndexAdapter rawIndex,
      OpenAiCompatibleModelServingAdapter models,
      OpenSearchDerivedIndexAdapter derived) {
    return new RawIngestionApplicationService(
        files, objects, knowledge, parser, encoder, rawIndex, models, derived);
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
      LocalFileSourceAdapter files) {
    return new ManagedIngestionApplicationService(uploads, identity, files);
  }

  @Bean
  DurableIngestionWorker ingestionWorker(
      JdbcIngestionRepository uploads,
      RawIngestionApplicationService raw,
      LocalFileSourceAdapter files,
      JdbcGenerationTaskRepository generationTasks,
      Environment env) {
    return new DurableIngestionWorker(
        uploads,
        raw,
        files,
        generationTasks,
        Duration.ofMillis(integer(env, "EKB_INGEST_POLL_MS", 1000)),
        integer(env, "EKB_INGEST_CLAIM_LIMIT", 1),
        integer(env, "EKB_INGEST_WORKERS", 3));
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
      OpenSearchDerivedIndexAdapter derived) {
    return new SummaryCompilationService(repo, model, objects, embeddings, derived);
  }

  @Bean
  ConceptCompilationService conceptCompilation(
      CompilationContracts.CompilationRepository repo, KnowledgeModel model) {
    return new ConceptCompilationService(repo, model);
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
      OpenSearchDerivedIndexAdapter derived) {
    return new WikiCompilationService(repo, model, objects, embeddings, derived);
  }

  @Bean
  JdbcGenerationTaskRepository generationTaskRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
    return new JdbcGenerationTaskRepository(jdbc, tx);
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
      OpenSearchRawIndexAdapter rawIndex,
      OpenAiCompatibleModelServingAdapter models,
      EmbeddingRebuildCheckpointRepository checkpoints) {
    return new RawEmbeddingRebuildService(compilation, rawIndex, models, checkpoints);
  }

  @Bean
  DurableEmbeddingRebuildWorker embeddingRebuildWorker(
      JdbcGenerationTaskRepository tasks, RawEmbeddingRebuildService rebuild, Environment env) {
    return new DurableEmbeddingRebuildWorker(
        tasks, rebuild, Duration.ofMillis(integer(env, "EKB_EMBEDDING_REBUILD_POLL_MS", 1000)));
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
        integer(env, "EKB_COMPILATION_CLAIM_LIMIT", 1));
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
  EnterpriseSessionInterceptor sessionInterceptor(IdentityApplicationService identity) {
    return new EnterpriseSessionInterceptor(identity);
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
      SourcePackageService packages) {
    return new EnterpriseMcpTools(retrieval, recommendations, packages);
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
