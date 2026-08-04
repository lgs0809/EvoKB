package cn.lgs.knowledgebase.application.port;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
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
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryEvidenceRef;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryGroup;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryPackage;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QuerySession;
import cn.lgs.knowledgebase.domain.query.QueryDomain.QueryTurn;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalReservation;
import cn.lgs.knowledgebase.domain.query.QueryDomain.RetrievalRound;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
      required(
          () -> {
            work.run();
            return null;
          });
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

    void saveFile(FileItem file);

    List<Batch> listBatches(
        String actorId, boolean admin, int offset, int limit, String status, String query);

    long countBatches(String actorId, boolean admin, String status, String query);

    void queueBatch(String batchId, Instant now);

    void retryFile(String fileId, Instant now);

    void resetInterrupted(Instant now);

    List<FileItem> claimQueued(int limit, Instant now);

    void updateProgress(
        String fileId,
        cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage stage,
        Instant now);

    String parserTaskId(String fileId);

    void updateParserTaskId(String fileId, String parserTaskId, Instant now);

    void complete(String fileId, String documentId, String versionId, Instant now);

    void fail(String fileId, String errorCode, String errorMessage, Instant now);

    record RawOutbox(String fileId, long deliveryEpoch) {}

    default List<RawOutbox> rawDispatchable(int limit) {
      throw new UnsupportedOperationException("durable raw outbox");
    }

    default void rawDispatched(RawOutbox row, String messageId) {
      throw new UnsupportedOperationException("durable raw outbox");
    }

    default Optional<cn.lgs.knowledgebase.application.raw.RawTaskClaim> claimRaw(
        String fileId, String ownerId) {
      throw new UnsupportedOperationException("durable raw ownership");
    }

    default cn.lgs.knowledgebase.application.raw.RawExecution rawExecution(
        cn.lgs.knowledgebase.application.raw.RawTaskClaim claim) {
      throw new UnsupportedOperationException("durable raw fencing");
    }

    default boolean rawRecoverable(String fileId) {
      throw new UnsupportedOperationException("durable raw recovery");
    }

    default boolean rawAcknowledgeable(String fileId) {
      throw new UnsupportedOperationException("durable raw acknowledgement");
    }

    default void rawFailure(
        cn.lgs.knowledgebase.application.raw.RawTaskClaim claim,
        String code,
        String message,
        boolean retryable) {
      throw new UnsupportedOperationException("durable raw failure");
    }

    default void rawComplete(
        cn.lgs.knowledgebase.application.raw.RawTaskClaim claim,
        String documentId,
        String versionId) {
      throw new UnsupportedOperationException("durable raw completion");
    }

    default void rawRejectDelivery(String messageId, String fileReference) {
      throw new UnsupportedOperationException("durable raw poison isolation");
    }

    /** Commit a missing-body fact and any required redispatch before its PEL tombstone is ACKed. */
    default void rawMissingDelivery(String messageId) {
      throw new UnsupportedOperationException("durable raw missing-body recovery");
    }

    default Optional<String> rawOriginalObject(String fileId) {
      return Optional.empty();
    }

    /** Complete original bytes were streamed and hashed by the worker before any indexing. */
    default void rawVerifyOriginal(
        cn.lgs.knowledgebase.application.raw.RawTaskClaim claim, SourceFile source) {}
  }

  public interface ObjectStore {
    void put(String key, InputStream source, long size, String contentType);

    StoredObject get(String key);

    ObjectInfo stat(String key);

    String presignGet(String key, Duration expiry);

    void delete(String key);
  }

  public record StoredObject(InputStream stream, long size, String contentType) {}

  public record ObjectInfo(String key, long size, String contentType, String etag) {}

  public interface ParserPort {
    ParsedDocument parse(String filename, InputStream source);

    default ParsedDocument parse(
        String filename, InputStream source, String resumeTaskId, Consumer<String> taskIdSink) {
      return parse(filename, source);
    }
  }

  public record ParsedDocument(
      String markdown,
      String middleJson,
      String contentList,
      String parserName,
      String parserVersion,
      List<ParsedBlock> blocks) {}

  public record ParsedBlock(
      String type,
      String text,
      int textLevel,
      Integer page,
      List<Double> bbox,
      List<Double> pageSize,
      String imagePath,
      String tableHtml,
      String tableBody,
      Map<String, Object> raw) {}

  public interface EmbeddingPort {
    int dimensions();

    List<List<Float>> embed(List<String> texts);

    /** A currently resident, actually certified encoder. This must not load a model. */
    default Optional<EncodingIdentity> currentEncodingIdentity() {
      return Optional.empty();
    }

    /** Identity authenticated against every complete input and returned float32 vector. */
    default EmbeddingBatch embedWithIdentity(List<String> texts) {
      return new EmbeddingBatch(embed(texts), Optional.empty());
    }

    /**
     * Preferred durable ingestion batch size. Implementations may expose the runtime model
     * configuration; test and lightweight implementations can keep the conservative default.
     */
    default int preferredBatchSize() {
      return 16;
    }
  }

  public record EncodingIdentity(
      String profileSha256, String model, String modelRevision, int dimensions) {
    public EncodingIdentity {
      if (profileSha256 == null || !profileSha256.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("encoding profile SHA-256 required");
      if (model == null || model.isBlank() || modelRevision == null || modelRevision.isBlank())
        throw new IllegalArgumentException("encoding model and actual revision required");
      if (dimensions <= 0)
        throw new IllegalArgumentException("encoding dimensions must be positive");
    }
  }

  public record EmbeddingBatch(
      List<List<Float>> vectors, List<Optional<EncodingIdentity>> identities) {
    public EmbeddingBatch(List<List<Float>> vectors, Optional<EncodingIdentity> identity) {
      this(vectors, java.util.Collections.nCopies(vectors.size(), identity));
    }

    public EmbeddingBatch {
      if (vectors == null
          || identities == null
          || vectors.size() != identities.size()
          || identities.stream().anyMatch(java.util.Objects::isNull))
        throw new IllegalArgumentException("embedding vectors and explicit identity required");
      vectors = List.copyOf(vectors);
      identities = List.copyOf(identities);
      if (identities.stream().flatMap(Optional::stream).distinct().count() > 1)
        throw new IllegalStateException(
            "ENCODING_PROFILE_MISMATCH: split batches used different runtimes");
    }

    public Optional<EncodingIdentity> identity() {
      return identities.isEmpty() || identities.stream().anyMatch(Optional::isEmpty)
          ? Optional.empty()
          : identities.get(0);
    }
  }

  /**
   * Durable evidence-bound checkpoints for raw embedding rebuilds.
   *
   * <p>A checkpoint is only reusable when the deterministic chunk content and the complete set of
   * source evidence IDs still match. This intentionally avoids treating a file offset or a chunk
   * count as proof that a vector is safe to reuse.
   */
  public interface EmbeddingRebuildCheckpointRepository {
    List<EmbeddingRebuildCheckpoint> find(String versionId);

    void markSucceeded(List<EmbeddingRebuildCheckpoint> checkpoints);
  }

  public record EmbeddingRebuildCheckpoint(
      String versionId,
      String chunkId,
      String contentHash,
      String evidenceFingerprint,
      String evidenceIds,
      String strategyKey) {
    public EmbeddingRebuildCheckpoint {
      versionId = requireText(versionId, "version id");
      chunkId = requireText(chunkId, "chunk id");
      contentHash = requireText(contentHash, "content hash");
      evidenceFingerprint = requireText(evidenceFingerprint, "evidence fingerprint");
      evidenceIds = requireText(evidenceIds, "evidence ids");
      strategyKey = requireText(strategyKey, "strategy key");
    }

    private static String requireText(String value, String name) {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException(name + " is required");
      return value.trim();
    }
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

    default void createPendingVersion(
        Document document, DocumentVersion version, Metadata metadata, Integer securityLevel) {
      if (securityLevel != null)
        throw new UnsupportedOperationException("initial document classification is unavailable");
      createPendingVersion(document, version, metadata);
    }

    void resetFailedVersion(String versionId, Metadata metadata, boolean preserveParsed);

    default void resetFailedVersion(
        String versionId, Metadata metadata, boolean preserveParsed, Integer securityLevel) {
      if (securityLevel != null)
        throw new UnsupportedOperationException("initial document classification is unavailable");
      resetFailedVersion(versionId, metadata, preserveParsed);
    }

    default Optional<Integer> sourceSecurityLevel(String versionId) {
      return Optional.empty();
    }

    List<RawBlock> rawBlocks(String versionId);

    void saveParsed(
        String versionId,
        String markdownKey,
        String structuredKey,
        String parserName,
        String parserVersion,
        List<RawBlock> blocks,
        List<Evidence> evidence);

    void activateVersion(String documentId, String versionId);

    void markVersionFailed(String versionId, String code, String message);

    List<Evidence> resolveEvidence(List<String> evidenceIds);
  }

  public interface FileSourcePort {
    SourceFile inspect(String path);

    InputStream open(String path);

    boolean exists(String path);

    void delete(String path);
  }

  public record SourceFile(
      String path, String filename, long size, String sha256, String mediaType) {}

  public interface ParsedArtifactEncoder {
    byte[] structuredArtifact(ParsedDocument parsed);
  }

  public interface RawSearchIndex {
    void ensureIndex(int embeddingDimensions);

    void indexRaw(String documentId, String versionId, List<RawIndexDocument> chunks);

    /** Each attempt writes immutable physical IDs; current PG membership decides visibility. */
    default List<String> stageRaw(
        String documentId, String versionId, String scope, List<RawIndexDocument> chunks) {
      if (scope != null && !scope.isBlank())
        throw new UnsupportedOperationException("immutable raw projections");
      indexRaw(documentId, versionId, chunks);
      return chunks.stream().map(RawIndexDocument::id).toList();
    }

    default void activateRaw(String documentId, String versionId, List<String> physicalIds) {
      activateVersion(documentId, versionId);
    }

    /**
     * Index rebuilt vectors while retaining the active flag of an existing document. This keeps an
     * active version searchable until the complete rebuild has succeeded; adapters may fall back to
     * the ordinary inactive write when they cannot preserve that flag.
     */
    default void indexRawRebuild(
        String documentId, String versionId, List<RawIndexDocument> chunks) {
      indexRaw(documentId, versionId, chunks);
    }

    /**
     * Return the number of chunks already durably indexed for a non-active version. This is used
     * only to resume a parsed-but-failed embedding run; adapters that cannot inspect their index
     * safely return zero and the caller will re-index from the beginning.
     */
    default int indexedChunkCount(String versionId) {
      return 0;
    }

    /**
     * Remove stale chunks for a version after a successful rebuild while retaining the newly
     * indexed chunk IDs. Implementations that cannot do this safely may leave the default no-op;
     * callers still keep the previous active index until the rebuild succeeds.
     */
    default void deleteVersionExcept(String versionId, java.util.Set<String> keepChunkIds) {}

    void activateVersion(String documentId, String versionId);

    void deactivateVersion(String versionId);

    void deleteVersion(String versionId);

    void updateMetadata(String versionId, Metadata metadata);

    List<Candidate> searchBm25(String query, SearchFilters filters, int limit);

    List<Candidate> searchVector(List<Float> vector, SearchFilters filters, int limit);
  }

  public record RawIndexDocument(
      String id,
      String content,
      String normalized,
      List<String> headingPath,
      List<String> sourceBlockIds,
      Integer pageStart,
      Integer pageEnd,
      String contentHash,
      List<Float> embedding,
      List<String> evidenceIds,
      Metadata metadata) {}

  public interface RetrievalLane {
    LaneType type();

    List<Candidate> search(String query, SearchFilters filters, int limit);
  }

  public interface SourceVersionAuthority {
    boolean accessible(List<String> versionIds, SearchFilters filters);
  }

  public interface EvidenceResolver {
    List<Evidence> resolve(List<String> evidenceIds);

    default List<Evidence> resolve(List<String> evidenceIds, SearchFilters filters) {
      return resolve(evidenceIds);
    }
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

    default void publish(List<Entity> entities, List<Relation> relations) {
      upsertEntities(entities);
      upsertRelations(relations);
    }

    List<Candidate> search(String query, SearchFilters filters, int limit);

    default GraphExpansion expand(List<Candidate> retrievedSeeds, SearchFilters filters) {
      return new GraphExpansion(List.of(), false, 0);
    }

    void deactivateVersion(String versionId);
  }

  public record GraphExpansion(List<Candidate> candidates, boolean truncated, int hops) {
    public GraphExpansion {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      if (hops < 0 || hops > 2)
        throw new IllegalArgumentException("graph expansion is capped at two hops");
    }
  }

  public interface DerivedSearchIndex {
    void ensureIndex(int embeddingDimensions);

    void indexSummary(SummaryArtifact summary, DerivedContext context, List<Float> embedding);

    void indexWiki(WikiPage page, DerivedContext context, List<Float> embedding);

    /** Prepare external projections before the authoritative publication transaction. */
    default Runnable prepareSummary(
        SummaryArtifact summary, DerivedContext context, List<Float> embedding) {
      return () -> indexSummary(summary, context, embedding);
    }

    default Runnable prepareWiki(WikiPage page, DerivedContext context, List<Float> embedding) {
      return () -> indexWiki(page, context, embedding);
    }

    default Runnable prepareConcept(
        String id,
        String text,
        List<String> evidenceIds,
        DerivedContext context,
        List<Float> embedding) {
      return () -> indexConcept(id, text, evidenceIds, context, embedding);
    }

    default void indexConcept(
        String assetId,
        String projection,
        List<String> evidenceIds,
        DerivedContext context,
        List<Float> embedding) {
      throw new UnsupportedOperationException("concept projection is unsupported by this adapter");
    }

    List<Candidate> searchSummaryBm25(String query, SearchFilters filters, int limit);

    List<Candidate> searchSummaryVector(List<Float> vector, SearchFilters filters, int limit);

    List<Candidate> searchWikiBm25(String query, SearchFilters filters, int limit);

    List<Candidate> searchWikiVector(List<Float> vector, SearchFilters filters, int limit);

    void updateMetadata(String versionId, Metadata metadata);

    void deactivateVersion(String versionId);
  }

  public record DerivedContext(
      List<String> documentIds,
      List<String> versionIds,
      List<String> sourceDepartments,
      List<String> materialCategories,
      List<String> companies,
      List<String> documentTypes,
      List<String> manualTags) {
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

  public enum ModelRole {
    SMALL,
    LARGE
  }

  public enum ModelTask {
    ASK,
    QUERY_UNDERSTANDING,
    EVIDENCE_SUFFICIENCY,
    CLASSIFY_UNIT,
    SUMMARY,
    SUMMARY_JUDGE,
    CONCEPT_EXTRACT,
    SOURCE_ASSOCIATION,
    RELATION_EXTRACT,
    RELATION_REPAIR,
    WIKI_WRITE,
    WIKI_JUDGE
  }

  /**
   * Framework-neutral LLM port. Spring AI Alibaba is an infrastructure adapter, never a domain
   * dependency.
   */
  public interface KnowledgeModel {
    String identity(ModelRole role);

    String generate(ModelRole role, ModelTask task, String systemPrompt, String userPrompt);

    <T> T generateStructured(
        ModelRole role,
        ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType);
  }

  public interface QuerySessionRepository {
    void createSession(QuerySession session);

    void updateSession(QuerySession session);

    Optional<QuerySession> findSession(String sessionId);

    List<QuerySession> listSessions(String userId, int limit);

    void appendTurn(QueryTurn turn);

    List<QueryTurn> listTurns(String sessionId);

    /** Atomically reserve and commit before external I/O; unfinished reservations stay consumed. */
    Optional<RetrievalReservation> reserveRound(
        String userId,
        String sessionId,
        List<String> proposedQueries,
        int maxRounds,
        int maxQueries);

    List<RetrievalReservation> listReservations(String sessionId);

    /** Complete only the matching reserved round; a different completion cannot overwrite it. */
    void saveRound(RetrievalRound round);

    List<RetrievalRound> listRounds(String sessionId);

    void addEvidence(QueryEvidenceRef evidence);

    List<QueryEvidenceRef> listEvidence(String sessionId);

    void saveFeedback(
        String feedbackId,
        String sessionId,
        String userId,
        String rating,
        String category,
        String comment,
        Instant createdAt);

    default void addSourceVersions(String sessionId, List<String> versionIds) {}

    default List<String> listSourceVersions(String sessionId) {
      return List.of();
    }

    /** Only results whose raw evidence is actually passed to the answer model may be recorded. */
    default void addAssetExpansions(
        String sessionId,
        int roundNo,
        List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result> results) {}
  }

  public interface QueryHistoryRepository {
    void renameSession(String userId, String sessionId, String name, Instant now);

    void softDeleteSession(String userId, String sessionId, Instant now);

    QueryGroup createGroup(QueryGroup group);

    Optional<QueryGroup> findGroup(String userId, String groupId);

    List<QueryGroup> listGroups(String userId);

    Optional<QueryGroup> groupForSession(String userId, String sessionId);

    void moveSessionToGroup(String userId, String sessionId, String groupId, Instant now);

    void removeSessionFromGroup(String userId, String sessionId, Instant now);

    QueryGroup renameGroup(String userId, String groupId, String name, Instant now);

    void dissolveGroup(String userId, String groupId, Instant now);

    List<String> softDeleteGroupAndSessions(String userId, String groupId, Instant now);

    void savePackage(QueryPackage queryPackage);

    void markPackageStatus(String packageId, String status, Instant deletedAt);

    Optional<QueryPackage> latestPackage(String userId, String ownerType, String ownerId);

    List<QueryPackage> packagesForOwner(String userId, String ownerType, String ownerId);
  }

  public interface GenerationTaskRepository {
    default cn.lgs.knowledgebase.application.compilation.CompilationPublication publication(
        GenerationTask task) {
      return cn.lgs.knowledgebase.application.compilation.CompilationPublication.direct(
          writes -> withinPublication(task, writes));
    }

    GenerationTask queue(
        String versionId, GenerationTaskType taskType, String subjectId, Instant now);

    /** Repair work bypasses new-generation daily quotas and precedes ordinary pending work. */
    default GenerationTask queueRepair(
        String versionId, GenerationTaskType type, String subjectId, Instant now) {
      return queue(versionId, type, subjectId, now);
    }

    List<GenerationTask> claimPending(int limit, Instant now);

    /** Claim only durable raw-embedding rebuild tasks so they cannot starve compilation tasks. */
    default List<GenerationTask> claimPendingEmbeddingRebuild(int limit, Instant now) {
      return List.of();
    }

    void complete(GenerationTask task, String outputHash, Instant now);

    /** Short authoritative publication transaction; never enclose model or embedding calls. */
    default void withinPublication(GenerationTask task, Runnable publication) {
      publication.run();
    }

    void completeConceptAndQueueDerived(GenerationTask task, String outputHash, Instant now);

    /** A failure belongs to the same claimed attempt, never just to a task ID. */
    void fail(GenerationTask task, String errorCode, String errorMessage, Instant now);

    /** Persist a transient dependency failure and its deadline for this exact claimed attempt. */
    void defer(
        GenerationTask task,
        String errorCode,
        String errorMessage,
        Instant nextAttemptAt,
        Instant now);

    void retry(String taskId, Instant now);

    /** Recover only expired stages of these types; startup is never evidence of expiry. */
    void resetInterrupted(List<GenerationTaskType> taskTypes, Instant now);
  }

  /** Typed dependency transport failure; never use this for JSON decoding or business rejection. */
  public static final class TransientDependencyFailure extends IllegalStateException {
    public TransientDependencyFailure(String message, Throwable cause) {
      super(message, cause);
    }

    public static boolean causedBy(Throwable failure) {
      for (Throwable current = failure; current != null; current = current.getCause()) {
        if (current instanceof TransientDependencyFailure) return true;
      }
      return false;
    }
  }

  public interface KnowledgeGenerationRecommendationRepository {
    void recordDeliveredSearch(DeliveredSearch search);

    void recommend(String versionId, String reason, int distinctEffectiveHits);
  }

  public record DeliveredSearch(
      String searchId, String query, SearchFilters filters, List<DeliveredResult> results) {
    public DeliveredSearch {
      results = results == null ? List.of() : List.copyOf(results);
    }
  }

  public record DeliveredResult(
      int rank,
      LaneType resultType,
      String resultId,
      double score,
      List<String> evidenceIds,
      List<String> versionIds) {
    public DeliveredResult {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      versionIds = versionIds == null ? List.of() : List.copyOf(versionIds);
    }
  }

  public interface SourcePackageService {
    PublishedPackage packageAnswerSources(
        String userId, String sessionId, List<String> evidenceIds);

    void recordCompletedTransfer(String userId, String packageId, long transferredBytes);

    default EvidenceSource source(String userId, String evidenceId) {
      return source(evidenceId);
    }

    default StoredObject openOriginal(String userId, String evidenceId) {
      return openOriginal(evidenceId);
    }

    default PublishedPackage packageSources(String userId, List<String> evidenceIds) {
      return packageSources(evidenceIds);
    }

    default PublishedPackage packageGroupedSources(String userId, List<SourcePackageGroup> groups) {
      return packageGroupedSources(groups);
    }

    default PackageAccess accessPackage(String userId, String objectKey) {
      return accessPackage(objectKey);
    }

    default StoredObject openPackage(String userId, String packageId) {
      throw new UnsupportedOperationException("authenticated package download is unavailable");
    }

    EvidenceSource source(String evidenceId);

    PublishedPackage packageSources(List<String> evidenceIds);

    PublishedPackage packageGroupedSources(List<SourcePackageGroup> groups);

    PackageAccess accessPackage(String objectKey);

    void deletePackage(String objectKey);

    StoredObject openOriginal(String evidenceId);
  }

  public record PackageAuthorization(
      String packageId,
      String userId,
      String objectKey,
      List<String> evidenceIds,
      Instant expiresAt) {}

  public interface PackageAuthorizationRepository {
    void save(PackageAuthorization authorization);

    java.util.Optional<PackageAuthorization> find(String packageId, String userId);

    java.util.Optional<PackageAuthorization> findByObjectKey(String objectKey, String userId);

    void markDeleted(String objectKey);
  }

  public record SourcePackageGroup(String folderName, List<String> evidenceIds, String sessionId) {
    public SourcePackageGroup(String folderName, List<String> evidenceIds) {
      this(folderName, evidenceIds, null);
    }

    public SourcePackageGroup {
      if (folderName == null || folderName.isBlank())
        throw new IllegalArgumentException("source package folder name is required");
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record PackageAccess(String downloadUrl, Instant expiresAt) {}

  public record EvidenceSource(
      String evidenceId,
      String documentId,
      String versionId,
      String objectKey,
      String sourceFilename,
      String contentType,
      long sizeBytes,
      Map<String, Object> location,
      String viewUrl,
      String downloadUrl,
      Instant expiresAt) {
    public EvidenceSource {
      location = location == null ? Map.of() : Map.copyOf(location);
    }
  }

  public record PackageVersion(
      String versionId,
      String documentId,
      String originalFilename,
      String objectKey,
      String archivePath,
      List<String> evidenceIds) {
    public PackageVersion {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record PackageManifest(
      Instant generatedAt,
      List<String> evidenceIds,
      List<PackageVersion> versions,
      List<PackageAnswer> answers) {
    public PackageManifest(
        Instant generatedAt, List<String> evidenceIds, List<PackageVersion> versions) {
      this(generatedAt, evidenceIds, versions, List.of());
    }

    public PackageManifest {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      versions = versions == null ? List.of() : List.copyOf(versions);
      answers = answers == null ? List.of() : List.copyOf(answers);
    }
  }

  public record PackageAnswer(String sessionId, List<String> evidenceIds) {
    public PackageAnswer {
      if (sessionId == null || sessionId.isBlank())
        throw new IllegalArgumentException("answer id is required");
      evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
    }
  }

  public interface DownloadUsageRepository {
    void saveManifest(String userId, String packageId, PackageManifest manifest, long sizeBytes);

    void recordCompletedTransfer(String userId, String packageId, long transferredBytes);
  }

  public record PublishedPackage(
      String packageId,
      String objectKey,
      String downloadUrl,
      Instant expiresAt,
      PackageManifest manifest) {}

  public interface AuditPort {
    void record(AuditEvent event);
  }

  public record AuditEvent(
      String actorId,
      String actorUsername,
      String actorRole,
      String action,
      String targetType,
      String targetId,
      String targetName,
      String outcome,
      String requestId,
      String ipAddress,
      String userAgent,
      Map<String, Object> details,
      Instant occurredAt) {
    public AuditEvent {
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }
}
