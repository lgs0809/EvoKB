package cn.lgs.knowledgebase;

import cn.lgs.knowledgebase.application.ask.AskApplicationService;
import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.promotion.PromotionApplicationService;
import cn.lgs.knowledgebase.application.raw.RawIngestionApplicationService;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import cn.lgs.knowledgebase.infrastructure.evidence.ObjectStoreSourcePackageService;
import cn.lgs.knowledgebase.infrastructure.graph.GraphRetrievalLane;
import cn.lgs.knowledgebase.infrastructure.graph.Neo4jGraphStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.ingestion.DurableIngestionWorker;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import cn.lgs.knowledgebase.infrastructure.parser.JacksonParsedArtifactEncoder;
import cn.lgs.knowledgebase.infrastructure.parser.MinerUPipelineAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.HybridRetrievalLanes;
import cn.lgs.knowledgebase.infrastructure.search.OpenSearchDerivedIndexAdapter;
import cn.lgs.knowledgebase.infrastructure.search.OpenSearchRawIndexAdapter;
import cn.lgs.knowledgebase.infrastructure.security.BCryptPasswordHasher;
import cn.lgs.knowledgebase.infrastructure.storage.LocalFileSourceAdapter;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseAuditInterceptor;
import cn.lgs.knowledgebase.interfaces.http.EnterpriseSessionInterceptor;
import cn.lgs.knowledgebase.interfaces.http.SafeBatchUploadStager;
import cn.lgs.knowledgebase.interfaces.mcp.EnterpriseMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Configuration
public class EnterpriseConfiguration {
    @Bean
    JdbcIdentityRepository identityRepository(JdbcTemplate jdbc) { return new JdbcIdentityRepository(jdbc); }

    @Bean
    BCryptPasswordHasher passwordHasher() { return new BCryptPasswordHasher(); }

    @Bean
    SpringTransactionAdapter transactionPort(TransactionTemplate tx) { return new SpringTransactionAdapter(tx); }

    @Bean
    IdentityApplicationService identityApplicationService(JdbcIdentityRepository repository, BCryptPasswordHasher hasher,
                                                          SpringTransactionAdapter tx, Environment env) {
        return new IdentityApplicationService(repository, repository, hasher, tx,
                Duration.ofHours(integer(env,"EKB_SESSION_TTL_HOURS",12)));
    }

    @Bean
    LocalFileSourceAdapter fileSource() { return new LocalFileSourceAdapter(); }

    @Bean
    MinioObjectStoreAdapter objectStore(Environment env) {
        return new MinioObjectStoreAdapter(value(env,"EKB_MINIO_ENDPOINT","127.0.0.1:19000"),
                required(env,"EKB_MINIO_ACCESS_KEY"), required(env,"EKB_MINIO_SECRET_KEY"),
                value(env,"EKB_MINIO_BUCKET","enterprise-kb"));
    }

    @Bean
    MinerUPipelineAdapter parser(ObjectMapper mapper, Environment env) {
        return new MinerUPipelineAdapter(value(env,"EKB_MINERU_URL","http://127.0.0.1:8000"), mapper,
                Duration.ofMillis(integer(env,"EKB_MINERU_POLL_MS",500)), value(env,"EKB_MINERU_PARSE_METHOD","auto"),
                value(env,"EKB_MINERU_LANGUAGE","ch"));
    }

    @Bean
    JacksonParsedArtifactEncoder parsedArtifactEncoder(ObjectMapper mapper) { return new JacksonParsedArtifactEncoder(mapper); }

    @Bean
    OpenAiCompatibleModelServingAdapter modelServing(Environment env) {
        return new OpenAiCompatibleModelServingAdapter(value(env,"EKB_MODELS_URL","http://127.0.0.1:18081"),
                value(env,"EKB_MODELS_API_KEY",""), value(env,"EKB_EMBED_MODEL","Qwen/Qwen3-Embedding-0.6B"),
                value(env,"EKB_RERANK_MODEL","Qwen/Qwen3-Reranker-0.6B"), integer(env,"EKB_EMBED_DIMS",1024),
                integer(env,"EKB_EMBED_BATCH_SIZE",16));
    }

    @Bean
    JdbcKnowledgeRepository knowledgeRepository(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        return new JdbcKnowledgeRepository(jdbc,tx,mapper);
    }

    @Bean
    OpenSearchRawIndexAdapter rawIndex(ObjectMapper mapper, Environment env) {
        return new OpenSearchRawIndexAdapter(value(env,"EKB_OPENSEARCH_URL","http://127.0.0.1:19200"),
                value(env,"EKB_RAW_INDEX","ekb_raw_v1"),mapper);
    }

    @Bean
    OpenSearchDerivedIndexAdapter derivedIndex(ObjectMapper mapper, Environment env) {
        return new OpenSearchDerivedIndexAdapter(value(env,"EKB_OPENSEARCH_URL","http://127.0.0.1:19200"),
                value(env,"EKB_DERIVED_INDEX","ekb_derived_v1"),mapper);
    }

    @Bean
    JdbcEnterpriseOperations operations(JdbcTemplate jdbc,ObjectMapper mapper) { return new JdbcEnterpriseOperations(jdbc,mapper); }

    @Bean
    Neo4jGraphStoreAdapter graphStore(JdbcTemplate jdbc,ObjectMapper mapper,Environment env) {
        String[] auth = value(env,"EKB_NEO4J_AUTH","neo4j/password").split("/",2);
        return new Neo4jGraphStoreAdapter(value(env,"EKB_NEO4J_URL","http://127.0.0.1:17474"),auth[0],auth.length>1?auth[1]:"",
                value(env,"EKB_NEO4J_DATABASE","neo4j"),mapper,jdbc);
    }

    @Bean
    SpringAiAlibabaKnowledgeModel knowledgeModel(ChatClient.Builder builder, Environment env) {
        return new SpringAiAlibabaKnowledgeModel(builder,value(env,"EKB_SMALL_MODEL","gpt-5.4-mini"),value(env,"EKB_LARGE_MODEL","gpt-5.6-luna"));
    }

    @Bean
    JdbcPromotionRepository promotionRepository(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper mapper,Environment env) {
        return new JdbcPromotionRepository(jdbc,tx,mapper,integer(env,"EKB_DISTILL_EFFECTIVE_HIT_THRESHOLD",3));
    }

    @Bean
    PromotionApplicationService promotionApplicationService(JdbcPromotionRepository repository) { return new PromotionApplicationService(repository); }

    @Bean
    JdbcAuditAdapter auditPort(JdbcTemplate jdbc,ObjectMapper mapper) { return new JdbcAuditAdapter(jdbc,mapper); }

    @Bean
    ObjectStoreSourcePackageService sourcePackages(EnterpriseOperations.Repository operations,ObjectStore objects,ObjectMapper mapper) {
        return new ObjectStoreSourcePackageService(operations,objects,mapper,Duration.ofMinutes(10));
    }

    @Bean
    RetrievalApplicationService retrievalApplicationService(OpenSearchRawIndexAdapter rawIndex,
                                                            OpenSearchDerivedIndexAdapter derivedIndex,
                                                            OpenAiCompatibleModelServingAdapter models,
                                                            Neo4jGraphStoreAdapter graph,
                                                            JdbcKnowledgeRepository knowledge,
                                                            Environment env) {
        List<RetrievalLane> lanes=List.of(new HybridRetrievalLanes.Raw(rawIndex,models),
                new HybridRetrievalLanes.Summary(derivedIndex,models),new HybridRetrievalLanes.Wiki(derivedIndex,models),
                new GraphRetrievalLane(graph));
        return new RetrievalApplicationService(lanes,models,knowledge::resolveEvidence,Quotas.defaults(),
                integer(env,"EKB_RERANK_MAX_CANDIDATES",20),integer(env,"EKB_RERANK_MAX_CHARS",400));
    }

    @Bean
    AskApplicationService askApplicationService(RetrievalApplicationService retrieval,KnowledgeModel model,Environment env) {
        return new AskApplicationService(retrieval,model,integer(env,"EKB_ASK_CONTEXT_CHARS",24000));
    }

    @Bean
    RawIngestionApplicationService rawIngestionApplicationService(LocalFileSourceAdapter files,ObjectStore objects,
                                                                  JdbcKnowledgeRepository knowledge,MinerUPipelineAdapter parser,
                                                                  JacksonParsedArtifactEncoder encoder,OpenSearchRawIndexAdapter rawIndex,
                                                                  OpenAiCompatibleModelServingAdapter models,OpenSearchDerivedIndexAdapter derived) {
        return new RawIngestionApplicationService(files,objects,knowledge,parser,encoder,rawIndex,models,derived);
    }

    @Bean
    JdbcIngestionRepository ingestionRepository(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper mapper) {
        return new JdbcIngestionRepository(jdbc,tx,mapper);
    }

    @Bean
    ManagedIngestionApplicationService managedIngestion(JdbcIngestionRepository uploads,JdbcIdentityRepository identity,LocalFileSourceAdapter files) {
        return new ManagedIngestionApplicationService(uploads,identity,files);
    }

    @Bean
    DurableIngestionWorker ingestionWorker(JdbcIngestionRepository uploads,RawIngestionApplicationService raw,
                                            LocalFileSourceAdapter files,Environment env) {
        return new DurableIngestionWorker(uploads,raw,files,Duration.ofMillis(integer(env,"EKB_INGEST_POLL_MS",1000)),integer(env,"EKB_INGEST_CLAIM_LIMIT",1));
    }

    @Bean
    JdbcCompilationRepository compilationRepository(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper mapper,ObjectStore objects) {
        return new JdbcCompilationRepository(jdbc,tx,mapper,objects);
    }

    @Bean
    SummaryCompilationService summaryCompilation(CompilationContracts.CompilationRepository repo,KnowledgeModel model,ObjectStore objects,
                                                 OpenAiCompatibleModelServingAdapter embeddings,OpenSearchDerivedIndexAdapter derived) {
        return new SummaryCompilationService(repo,model,objects,embeddings,derived);
    }

    @Bean
    GraphCompilationService graphCompilation(CompilationContracts.CompilationRepository repo,KnowledgeModel model,GraphStore graph) {
        return new GraphCompilationService(repo,model,graph);
    }

    @Bean
    WikiCompilationService wikiCompilation(CompilationContracts.CompilationRepository repo,KnowledgeModel model,ObjectStore objects,
                                           OpenAiCompatibleModelServingAdapter embeddings,OpenSearchDerivedIndexAdapter derived) {
        return new WikiCompilationService(repo,model,objects,embeddings,derived);
    }

    @Bean
    SafeBatchUploadStager batchUploadStager(Environment env) {
        return new SafeBatchUploadStager(Path.of(value(env,"EKB_STAGING_ROOT",System.getProperty("java.io.tmpdir")+"/enterprise-kb/staging")),
                mib(env,"EKB_MAX_BATCH_UPLOAD_MIB",4096),mib(env,"EKB_MAX_BATCH_EXPANDED_MIB",16384),integer(env,"EKB_MAX_BATCH_FILES",2000));
    }

    @Bean
    EnterpriseSessionInterceptor sessionInterceptor(IdentityApplicationService identity) { return new EnterpriseSessionInterceptor(identity); }

    @Bean
    EnterpriseAuditInterceptor auditInterceptor(AuditPort audit, Environment env) {
        return new EnterpriseAuditInterceptor(audit, Boolean.parseBoolean(value(env,"EKB_TRUST_PROXY_HEADERS","false")));
    }

    @Bean
    WebMvcConfigurer enterpriseWebMvcConfigurer(EnterpriseSessionInterceptor session, EnterpriseAuditInterceptor audit) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(audit).addPathPatterns("/api/enterprise/**");
                registry.addInterceptor(session).addPathPatterns("/api/enterprise/**")
                        .excludePathPatterns("/api/enterprise/auth/status","/api/enterprise/auth/setup","/api/enterprise/auth/login");
            }
        };
    }

    @Bean
    EnterpriseMcpTools enterpriseMcpTools(RetrievalApplicationService retrieval,PromotionApplicationService promotion,SourcePackageService packages) {
        return new EnterpriseMcpTools(retrieval,promotion,packages);
    }

    @Bean
    ToolCallbackProvider enterpriseToolCallbackProvider(EnterpriseMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    private static String required(Environment env,String name){String v=env.getProperty(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" is required");return v.trim();}
    private static String value(Environment env,String name,String fallback){String v=env.getProperty(name);return v==null||v.isBlank()?fallback:v.trim();}
    private static int integer(Environment env,String name,int fallback){try{return Integer.parseInt(value(env,name,Integer.toString(fallback)));}catch(Exception e){return fallback;}}
    private static long mib(Environment env,String name,long fallbackMiB){String v=value(env,name,Long.toString(fallbackMiB));try{return Math.multiplyExact(Long.parseLong(v),1024L*1024L);}catch(Exception e){return fallbackMiB*1024L*1024L;}}
}
