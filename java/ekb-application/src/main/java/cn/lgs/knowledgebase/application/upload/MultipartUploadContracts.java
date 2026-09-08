package cn.lgs.knowledgebase.application.upload;

import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Upload transport has its own durable identity; it is not a parsing chunk or a Raw owner. */
public final class MultipartUploadContracts {
  private MultipartUploadContracts() {}

  public static final int PART_BYTES = 16 * 1024 * 1024;
  public static final int PARALLEL_PARTS = 4;
  public static final int URL_SECONDS = 600;
  public static final String UNVERIFIED = "UNVERIFIED";

  public enum State {
    UPLOADING,
    COMPLETING,
    COMPLETED,
    ABORTED,
    EXPIRED
  }

  public record Policy(
      long maxFileBytes,
      int partSize,
      int parallelParts,
      int urlSeconds,
      int sessionSeconds,
      String transportChecksum) {}

  public record Create(
      String filename,
      String relativePath,
      long sizeBytes,
      String clientSha256,
      FileMetadata metadata) {}

  public record Part(int number, long sizeBytes, String etag) {}

  public record PartUrl(int number, String url, Map<String, String> headers, Instant expiresAt) {}

  public record ObjectIdentity(
      long sizeBytes, String etag, String storageVersion, Map<String, String> metadata) {}

  public record Session(
      String sessionId,
      String userId,
      String username,
      String knowledgeBaseId,
      String bucket,
      String objectKey,
      String uploadId,
      String filename,
      String relativePath,
      long sizeBytes,
      int partSize,
      int partCount,
      String clientSha256,
      FileMetadata metadata,
      State status,
      long revision,
      Instant createdAt,
      Instant expiresAt,
      String completionOwner,
      Instant completionDeadline,
      List<Part> frozenParts,
      String objectEtag,
      String objectVersion,
      String documentId,
      String documentVersionId,
      String batchId,
      String fileId,
      boolean abortConfirmed,
      String integrityStatus,
      String actualSha256) {}

  public record View(Session session, List<Part> uploadedParts) {}

  public interface Store {
    String bucket();

    String create(String objectKey, String filename, String sessionId);

    List<Part> list(Session session);

    String presign(Session session, int number, String contentMd5);

    ObjectIdentity complete(Session session);

    Optional<ObjectIdentity> head(String objectKey);

    void abort(Session session);

    /** Reconcile an interrupted creation by its unique server-generated object key only. */
    List<String> findUploads(String objectKey);
  }

  public interface Repository {
    Session create(
        Create request,
        String userId,
        String username,
        String knowledgeBaseId,
        String bucket,
        long reservedByteLimit);

    Session attach(String sessionId, String uploadId);

    Optional<Session> find(String sessionId);

    List<Session> list(String userId, String knowledgeBaseId, int limit);

    Optional<Session> beginComplete(
        String sessionId, long revision, String owner, List<Part> parts);

    Optional<Session> reclaimComplete(String sessionId, String owner);

    Session finish(Session claim, ObjectIdentity object);

    Optional<Session> terminate(String sessionId, long revision, State target);

    void abortConfirmed(String sessionId);

    List<Session> recoverable(int limit);
  }
}
