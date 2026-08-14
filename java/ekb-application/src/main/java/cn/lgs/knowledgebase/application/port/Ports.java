package cn.lgs.knowledgebase.application.port;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.AccessPolicy;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Session;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.Batch;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Document;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Ports {
    private Ports() {}

    public interface UserRepository {
        long count();
        long countLocked();
        long countActiveAdmins();
        long countActiveAdminsLocked();
        Optional<User> findById(String id);
        Optional<User> findByIdLocked(String id);
        Optional<User> findByUsername(String username);
        List<User> list(int offset, int limit);
        void save(User user, String passwordHash);
        void update(User user);
        void updatePasswordHash(String userId, String passwordHash);
        Optional<String> passwordHash(String userId);
    }

    public interface SessionRepository {
        void save(Session session);
        Optional<Session> findByTokenHash(String tokenHash);
        void touch(String sessionId, Instant now);
        void delete(String sessionId);
        void deleteByUser(String userId);
        int deleteExpired(Instant now);
    }

    public interface TransactionPort {
        <T> T required(java.util.function.Supplier<T> work);
        default void required(Runnable work) {
            required(() -> { work.run(); return null; });
        }
    }

    public interface PasswordHasher {
        String hash(char[] password);
        boolean matches(char[] password, String encodedHash);
    }

    public interface RuntimeConfigRepository {
        AccessPolicy accessPolicy();
        void saveAccessPolicy(AccessPolicy policy);
    }

    public interface UploadRepository {
        Batch create(Batch batch);
        Optional<Batch> findBatch(String batchId);
        Optional<FileItem> findFile(String fileId);
        void saveBatch(Batch batch);
        void saveFile(FileItem file);
        List<Batch> listBatches(String actorId, boolean admin, int offset, int limit, String status, String query);
        List<FileItem> claimQueued(int limit, Instant now);
    }

    public interface ObjectStore {
        void put(String key, InputStream source, long size, String contentType);
        StoredObject get(String key);
        ObjectInfo stat(String key);
        String presignGet(String key, Duration expiry);
    }

    public record StoredObject(InputStream stream, long size, String contentType) {}
    public record ObjectInfo(String key, long size, String contentType, String etag) {}

    public interface ParserPort {
        ParsedDocument parse(String filename, InputStream source);
    }

    public record ParsedDocument(String markdown, String middleJson, String contentList,
                                 String parserName, String parserVersion, List<ParsedBlock> blocks) {}
    public record ParsedBlock(String type, String text, int textLevel, Integer page,
                              List<Double> bbox, List<Double> pageSize, String imagePath,
                              String tableHtml, String tableBody, Map<String, Object> raw) {}

    public interface EmbeddingPort {
        int dimensions();
        List<List<Float>> embed(List<String> texts);
    }

    public interface RerankerPort {
        List<Double> rerank(String query, List<String> documents);
    }

    public interface KnowledgeRepository {
        Optional<Document> findDocumentByLogicalName(String logicalName);
        Optional<DocumentVersion> findVersionBySha(String documentId, String sha256);
        Optional<DocumentVersion> currentActiveVersion(String documentId);
        long nextVersionNo(String documentId);
        void createPendingVersion(Document document, DocumentVersion version, Metadata metadata);
        void saveParsed(String versionId, String markdownKey, String structuredKey,
                        String parserName, String parserVersion, List<RawBlock> blocks, List<Evidence> evidence);
        void activateVersion(String documentId, String versionId);
        void markVersionFailed(String versionId, String code, String message);
        List<Evidence> resolveEvidence(List<String> evidenceIds);
    }

    public interface RawSearchIndex {
        void ensureIndex(int embeddingDimensions);
        void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks);
        void activateVersion(String documentId, String versionId);
        void deactivateVersion(String versionId);
        void deleteVersion(String versionId);
        List<Candidate> searchBm25(String query, SearchFilters filters, int limit);
        List<Candidate> searchVector(List<Float> vector, SearchFilters filters, int limit);
    }

    public record RawIndexDocument(String id, String content, String normalized, List<String> headingPath,
                                   List<String> sourceBlockIds, Integer pageStart, Integer pageEnd,
                                   String contentHash, List<Float> embedding,
                                   List<String> evidenceIds, Metadata metadata) {}

    public interface RetrievalLane {
        LaneType type();
        List<Candidate> search(String query, SearchFilters filters, int limit);
    }

    public interface EvidenceResolver {
        List<Evidence> resolve(List<String> evidenceIds);
    }

    public interface DerivedArtifactRepository {
        void saveSummary(SummaryArtifact summary);
        void saveWiki(WikiPage page);
        Optional<SummaryArtifact> findSummary(String versionId);
        Optional<WikiPage> findWiki(String id);
        void markVersionArtifactsStale(String versionId);
    }

    public interface GraphStore {
        void upsertEntities(List<Entity> entities);
        void upsertRelations(List<Relation> relations);
        List<Candidate> search(String query, SearchFilters filters, int limit);
        void deactivateVersion(String versionId);
    }

    public interface DerivedSearchIndex {
        void ensureIndex(int embeddingDimensions);
        void indexSummary(SummaryArtifact summary, DerivedContext context, List<Float> embedding);
        void indexWiki(WikiPage page, DerivedContext context, List<Float> embedding);
        List<Candidate> searchSummaryBm25(String query, SearchFilters filters, int limit);
        List<Candidate> searchSummaryVector(List<Float> vector, SearchFilters filters, int limit);
        List<Candidate> searchWikiBm25(String query, SearchFilters filters, int limit);
        List<Candidate> searchWikiVector(List<Float> vector, SearchFilters filters, int limit);
        void deactivateVersion(String versionId);
    }

    public record DerivedContext(List<String> documentIds, List<String> versionIds,
                                 List<String> sourceDepartments, List<String> materialCategories,
                                 List<String> companies, List<String> documentTypes, List<String> manualTags) {
        public DerivedContext {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            versionIds = versionIds == null ? List.of() : List.copyOf(versionIds);
            sourceDepartments = sourceDepartments == null ? List.of() : List.copyOf(sourceDepartments);
            materialCategories = materialCategories == null ? List.of() : List.copyOf(materialCategories);
            companies = companies == null ? List.of() : List.copyOf(companies);
            documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
            manualTags = manualTags == null ? List.of() : List.copyOf(manualTags);
        }
    }

    public enum ModelRole { SMALL, LARGE }
    public enum ModelTask { ASK, CLASSIFY_UNIT, SUMMARY, SUMMARY_JUDGE, RELATION_EXTRACT, RELATION_REPAIR, WIKI_WRITE, WIKI_JUDGE }

    /** Framework-neutral LLM port. Spring AI Alibaba is an infrastructure adapter, never a domain dependency. */
    public interface KnowledgeModel {
        String generate(ModelRole role, ModelTask task, String systemPrompt, String userPrompt);
        <T> T generateStructured(ModelRole role, ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType);
    }

    public interface AuditPort {
        void record(AuditEvent event);
    }

    public record AuditEvent(String actorId, String action, String targetType, String targetId,
                             String outcome, Map<String, Object> details, Instant occurredAt) {}
}
