package cn.lgs.knowledgebase.application.upload;

import static cn.lgs.knowledgebase.application.upload.MultipartUploadContracts.*;

import cn.lgs.knowledgebase.application.ingestion.ManagedIngestionApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native multipart transport, short PostgreSQL state transitions, no long transaction across S3.
 */
public final class MultipartUploadApplicationService {
  private static final Set<String> TYPES =
      Set.of(
          "pdf", "docx", "pptx", "xlsx", "csv", "md", "txt", "png", "jpg", "jpeg", "webp", "bmp",
          "tif", "tiff");
  private final Repository sessions;
  private final Store store;
  private final ManagedIngestionApplicationService permissions;
  private final String knowledgeBase;
  private final long maxBytes;
  private final long reservedBytes;

  public MultipartUploadApplicationService(
      Repository sessions,
      Store store,
      ManagedIngestionApplicationService permissions,
      String knowledgeBase,
      long maxBytes,
      long reservedBytes) {
    this.sessions = java.util.Objects.requireNonNull(sessions);
    this.store = java.util.Objects.requireNonNull(store);
    this.permissions = java.util.Objects.requireNonNull(permissions);
    if (knowledgeBase == null || !knowledgeBase.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("trusted knowledge base configuration is required");
    this.knowledgeBase = knowledgeBase;
    this.maxBytes = Math.min(256L * 1024 * 1024, maxBytes);
    if (this.maxBytes < 1 || reservedBytes < this.maxBytes)
      throw new IllegalArgumentException("multipart deployment quota is invalid");
    this.reservedBytes = reservedBytes;
  }

  public Policy policy(User user) {
    requireUpload(user);
    return new Policy(
        maxBytes,
        PART_BYTES,
        PARALLEL_PARTS,
        URL_SECONDS,
        24 * 3600,
        "signed-content-md5-per-part;whole-file-sha256-verified-by-raw-worker");
  }

  public Session create(User user, Create input) {
    requireUpload(user);
    if (input == null) throw new IllegalArgumentException("upload request is required");
    String name = input.filename();
    if (name == null
        || name.isBlank()
        || name.length() > 512
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("a safe filename is required");
    String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    if (!TYPES.contains(extension)) throw new IllegalArgumentException("unsupported upload type");
    if (input.sizeBytes() < 1 || input.sizeBytes() > maxBytes)
      throw new IllegalArgumentException("file exceeds effective upload/parser limit: " + maxBytes);
    String hash = input.clientSha256() == null ? "" : input.clientSha256();
    if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("client_sha256 must be a lowercase SHA-256 digest");
    String relative =
        input.relativePath() == null || input.relativePath().isBlank()
            ? name
            : input.relativePath();
    if (relative.length() > 1024
        || relative.startsWith("/")
        || relative.contains("\\")
        || java.util.Arrays.asList(relative.split("/")).contains(".."))
      throw new IllegalArgumentException("invalid relative path");
    var metadata =
        input.metadata() == null
            ? new FileMetadata(name, "", "", "", "", null, List.of())
            : input.metadata();
    cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy.requireUploadAllowed(
        user, metadata.securityLevel());
    if (metadata.logicalName().length() > 512
        || metadata.sourceDepartment().length() > 255
        || metadata.materialCategory().length() > 255
        || metadata.company().length() > 255
        || metadata.documentType().length() > 128
        || metadata.manualTags().size() > 100
        || metadata.manualTags().stream().anyMatch(tag -> tag.length() > 128))
      throw new IllegalArgumentException("upload metadata exceeds supported limits");
    var session =
        sessions.create(
            new Create(name, relative, input.sizeBytes(), hash, metadata),
            user.id(),
            user.username(),
            knowledgeBase,
            store.bucket(),
            reservedBytes);
    String uploadId = null;
    try {
      uploadId = store.create(session.objectKey(), name, session.sessionId());
      return sessions.attach(session.sessionId(), uploadId);
    } catch (RuntimeException failure) {
      // The initial durable row lets the hourly scanner find this unique key after a crash.
      if (uploadId != null) {
        try {
          var attached = sessions.attach(session.sessionId(), uploadId);
          var aborted =
              sessions.terminate(attached.sessionId(), attached.revision(), State.ABORTED);
          if (aborted.isPresent()) abort(aborted.get());
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  public List<Session> list(User user) {
    requireUpload(user);
    return sessions.list(user.id(), knowledgeBase, 100);
  }

  public View view(User user, String id) {
    Session session = owned(user, id);
    if (session.status() != State.UPLOADING || session.uploadId() == null)
      return new View(session, session.frozenParts());
    try {
      return new View(session, store.list(session));
    } catch (RuntimeException failure) {
      var current = owned(user, id);
      if (current.status() != State.UPLOADING) return new View(current, current.frozenParts());
      throw failure;
    }
  }

  public PartUrl sign(User user, String id, int number, String md5, String sha) {
    Session session = owned(user, id);
    requireLive(session);
    if (session.status() != State.UPLOADING || session.uploadId() == null)
      throw new ManagedIngestionApplicationService.Conflict("session is not accepting parts");
    if (number < 1 || number > session.partCount())
      throw new IllegalArgumentException("invalid part number");
    if (!session.clientSha256().isBlank() && !session.clientSha256().equals(sha))
      throw new ManagedIngestionApplicationService.Conflict(
          "file digest does not match this resumable session");
    try {
      if (Base64.getDecoder().decode(md5).length != 16) throw new IllegalArgumentException();
    } catch (RuntimeException bad) {
      throw new IllegalArgumentException("a part Content-MD5 is required");
    }
    return new PartUrl(
        number,
        store.presign(session, number, md5),
        Map.of("Content-MD5", md5),
        Instant.now().plusSeconds(URL_SECONDS));
  }

  public Session complete(User user, String id, long revision, List<Part> clientParts) {
    Session session = owned(user, id);
    if (session.status() == State.COMPLETED) return session;
    requireLive(session);
    if (session.status() == State.COMPLETING) return session;
    if (session.status() != State.UPLOADING)
      throw new ManagedIngestionApplicationService.Conflict("session is terminal");
    List<Part> actual;
    try {
      actual = store.list(session);
    } catch (RuntimeException failure) {
      // Another request may have merged and removed the upload between our DB read and ListParts.
      var current = owned(user, id);
      if (current.status() == State.COMPLETING || current.status() == State.COMPLETED)
        return current;
      throw failure;
    }
    var parts = validateParts(session, actual, clientParts);
    var claim = sessions.beginComplete(id, revision, UUID.randomUUID().toString(), parts);
    if (claim.isEmpty()) return owned(user, id);
    return merge(claim.get());
  }

  public Session cancel(User user, String id, long revision) {
    var session = owned(user, id);
    if (session.status() == State.COMPLETING)
      throw new ManagedIngestionApplicationService.Conflict(
          "completion is being reconciled; cancellation cannot race a merge");
    if (session.status() != State.UPLOADING) return session;
    var terminal = sessions.terminate(id, revision, State.ABORTED);
    if (terminal.isEmpty()) return owned(user, id);
    abort(terminal.get());
    return owned(user, id);
  }

  public void recover() {
    for (Session session : sessions.recoverable(100)) {
      try {
        if (session.status() == State.COMPLETING) {
          var claim = sessions.reclaimComplete(session.sessionId(), UUID.randomUUID().toString());
          if (claim.isPresent()) merge(claim.get());
        } else if (session.status() == State.UPLOADING) {
          // Reconcile interrupted CreateMultipartUpload before expiring the durable row.
          if (session.uploadId() == null) {
            var uploads = store.findUploads(session.objectKey());
            if (uploads.size() > 1)
              throw new IllegalStateException("multiple multipart uploads for one session key");
            if (!uploads.isEmpty()) session = sessions.attach(session.sessionId(), uploads.get(0));
          }
          var expired = sessions.terminate(session.sessionId(), session.revision(), State.EXPIRED);
          if (expired.isPresent()) abort(expired.get());
        } else if (!session.abortConfirmed()) abort(session);
      } catch (RuntimeException failure) {
        // Individual external failures remain durable/recoverable and cannot stop other sessions.
        System.getLogger(getClass().getName())
            .log(
                System.Logger.Level.WARNING,
                "multipart recovery failed for session {0}: {1}",
                session.sessionId(),
                failure.getClass().getSimpleName());
      }
    }
  }

  private Session merge(Session claim) {
    var already = store.head(claim.objectKey());
    var object = already.orElseGet(() -> store.complete(claim));
    if (object.sizeBytes() != claim.sizeBytes()
        || !claim.sessionId().equals(object.metadata().get("evokb-session-id")))
      throw new IllegalStateException(
          "completed object identity differs from the frozen upload session");
    // Complete/HEAD happen outside the final short transaction. An expired owner cannot publish.
    return sessions.finish(claim, object);
  }

  private void abort(Session session) {
    if (session.uploadId() != null) store.abort(session);
    else
      for (String id : store.findUploads(session.objectKey())) {
        // Recover only our immutable object key; never enumerate/delete unrelated objects.
        sessions.attach(session.sessionId(), id);
        store.abort(sessions.find(session.sessionId()).orElseThrow());
      }
    sessions.abortConfirmed(session.sessionId());
  }

  private Session owned(User user, String id) {
    requireUpload(user);
    var session =
        sessions
            .find(id)
            .orElseThrow(
                () -> new ManagedIngestionApplicationService.NotFound("upload session not found"));
    if (!session.userId().equals(user.id()) || !knowledgeBase.equals(session.knowledgeBaseId()))
      throw new ManagedIngestionApplicationService.Forbidden(
          "upload session belongs to another user or knowledge base");
    return session;
  }

  private void requireUpload(User user) {
    if (user == null || !user.active() || !permissions.access(user).canUpload())
      throw new ManagedIngestionApplicationService.Forbidden(
          "knowledge base write permission is required");
  }

  private static void requireLive(Session session) {
    if (!session.expiresAt().isAfter(Instant.now()))
      throw new ManagedIngestionApplicationService.Conflict("upload session has expired");
  }

  static List<Part> validateParts(Session session, List<Part> actual, List<Part> client) {
    if (actual == null
        || client == null
        || actual.size() != session.partCount()
        || client.size() != actual.size())
      throw new ManagedIngestionApplicationService.Conflict("uploaded parts are incomplete");
    List<Part> sorted = new ArrayList<>(actual);
    sorted.sort(java.util.Comparator.comparingInt(Part::number));
    var clientByNumber = new java.util.HashMap<Integer, Part>();
    for (Part part : client)
      if (clientByNumber.put(part.number(), part) != null)
        throw new IllegalArgumentException("duplicate part number");
    for (int i = 0; i < sorted.size(); i++) {
      Part part = sorted.get(i), supplied = clientByNumber.get(i + 1);
      long expected =
          i + 1 == session.partCount()
              ? session.sizeBytes() - (long) i * session.partSize()
              : session.partSize();
      if (part.number() != i + 1
          || part.sizeBytes() != expected
          || supplied == null
          || !part.etag().equals(supplied.etag())
          || supplied.sizeBytes() != expected)
        throw new ManagedIngestionApplicationService.Conflict(
            "part number, size or ETag differs from storage");
    }
    return List.copyOf(sorted);
  }
}
