package cn.lgs.knowledgebase.application.ingestion;

import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.RuntimeConfigRepository;
import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import cn.lgs.knowledgebase.application.port.Ports.UploadRepository;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.Batch;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileItem;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileMetadata;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.FileStatus;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ManagedIngestionApplicationService {
  private final UploadRepository uploads;
  private final RuntimeConfigRepository config;
  private final FileSourcePort files;
  private final Clock clock;

  public ManagedIngestionApplicationService(
      UploadRepository uploads, RuntimeConfigRepository config, FileSourcePort files) {
    this(uploads, config, files, Clock.systemUTC());
  }

  ManagedIngestionApplicationService(
      UploadRepository uploads, RuntimeConfigRepository config, FileSourcePort files, Clock clock) {
    this.uploads = require(uploads, "upload repository");
    this.config = require(config, "runtime config");
    this.files = require(files, "file source");
    this.clock = require(clock, "clock");
  }

  public Access access(User actor) {
    requireActor(actor);
    var policy = config.accessPolicy();
    return new Access(policy.userUploadEnabled(), policy.canUpload(actor));
  }

  public Access setUserUploadEnabled(User actor, boolean enabled) {
    requireAdmin(actor);
    config.saveAccessPolicy(
        new cn.lgs.knowledgebase.domain.identity.IdentityDomain.AccessPolicy(enabled));
    return new Access(enabled, true);
  }

  public Batch createBatch(User actor, int sourceCount, List<StagedDraft> drafts) {
    requireUpload(actor);
    if (drafts == null || drafts.isEmpty())
      throw new Validation("ingestion batch requires at least one supported file");
    Instant now = clock.instant();
    String batchId = UUID.randomUUID().toString();
    List<FileItem> items = new ArrayList<>(drafts.size());
    for (StagedDraft draft : drafts) {
      if (draft == null) continue;
      SourceFile source = files.inspect(draft.stagingPath());
      String logicalName =
          draft.logicalName() == null || draft.logicalName().isBlank()
              ? draft.originalFilename()
              : draft.logicalName().trim();
      FileMetadata metadata =
          new FileMetadata(
              logicalName,
              draft.sourceDepartment(),
              draft.materialCategory(),
              draft.company(),
              draft.documentType(),
              draft.documentDate(),
              draft.manualTags());
      items.add(
          new FileItem(
              UUID.randomUUID().toString(),
              batchId,
              cleanRelative(draft.relativePath()),
              requiredText(draft.originalFilename(), "originalFilename"),
              source.path(),
              source.size(),
              source.sha256(),
              metadata,
              FileStatus.STAGED,
              ProgressStage.STAGED,
              0,
              "",
              "",
              null,
              "",
              "",
              now,
              now,
              null,
              null));
    }
    if (items.isEmpty())
      throw new Validation("ingestion batch requires at least one supported file");
    Batch batch =
        new Batch(
            batchId,
            actor.id(),
            actor.username(),
            Math.max(sourceCount, items.size()),
            items,
            now,
            now,
            null,
            null);
    return uploads.create(batch);
  }

  public Batch getBatch(User actor, String batchId) {
    Batch batch =
        uploads
            .findBatch(requiredText(batchId, "batchId"))
            .orElseThrow(() -> new NotFound("batch not found"));
    authorizeBatch(actor, batch);
    return batch;
  }

  public List<Batch> listBatches(User actor, int page, int pageSize, String status, String query) {
    requireActor(actor);
    int size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    int offset = Math.max(0, (Math.max(1, page) - 1) * size);
    return uploads.listBatches(actor.id(), actor.administrator(), offset, size, status, query);
  }

  public long countBatches(User actor, String status, String query) {
    requireActor(actor);
    return uploads.countBatches(actor.id(), actor.administrator(), status, query);
  }

  public FileItem patchMetadata(User actor, String fileId, MetadataPatch patch) {
    requireUpload(actor);
    FileItem file =
        uploads
            .findFile(requiredText(fileId, "fileId"))
            .orElseThrow(() -> new NotFound("file not found"));
    Batch batch =
        uploads.findBatch(file.batchId()).orElseThrow(() -> new NotFound("batch not found"));
    authorizeBatch(actor, batch);
    if (!file.metadataEditable())
      throw new Conflict("file metadata can only be edited while STAGED");
    MetadataPatch value =
        patch == null
            ? new MetadataPatch(null, null, null, null, null, null, false, null, false)
            : patch;
    FileMetadata next =
        file.metadata()
            .patch(
                value.logicalName(),
                value.sourceDepartment(),
                value.materialCategory(),
                value.company(),
                value.documentType(),
                value.documentDate(),
                value.clearDocumentDate(),
                value.manualTags(),
                value.replaceTags());
    FileItem updated = file.withMetadata(next, clock.instant());
    uploads.saveFile(updated);
    return uploads.findFile(file.fileId()).orElse(updated);
  }

  public Batch queueBatch(User actor, String batchId) {
    requireUpload(actor);
    Batch batch = getBatch(actor, batchId);
    uploads.queueBatch(batch.batchId(), clock.instant());
    return uploads.findBatch(batch.batchId()).orElseThrow(() -> new NotFound("batch not found"));
  }

  public FileItem retryFile(User actor, String fileId) {
    requireUpload(actor);
    FileItem file =
        uploads
            .findFile(requiredText(fileId, "fileId"))
            .orElseThrow(() -> new NotFound("file not found"));
    Batch batch =
        uploads.findBatch(file.batchId()).orElseThrow(() -> new NotFound("batch not found"));
    authorizeBatch(actor, batch);
    if (!file.retryable()) throw new Conflict("only FAILED ingestion files can be retried");
    uploads.retryFile(file.fileId(), clock.instant());
    return uploads.findFile(file.fileId()).orElseThrow(() -> new NotFound("file not found"));
  }

  private void requireUpload(User actor) {
    requireActor(actor);
    if (!config.accessPolicy().canUpload(actor))
      throw new Forbidden("document upload is disabled for USER accounts");
  }

  private static void authorizeBatch(User actor, Batch batch) {
    requireActor(actor);
    if (!actor.administrator() && !actor.id().equals(batch.createdBy()))
      throw new Forbidden("batch is not owned by current user");
  }

  private static void requireAdmin(User actor) {
    requireActor(actor);
    if (!actor.administrator()) throw new Forbidden("administrator role is required");
  }

  private static void requireActor(User actor) {
    if (actor == null || !actor.active()) throw new Forbidden("active user is required");
  }

  private static String cleanRelative(String path) {
    if (path == null) return "";
    String value = path.replace('\\', '/').trim();
    while (value.startsWith("/")) value = value.substring(1);
    if (value.equals("..") || value.startsWith("../") || value.contains("/../"))
      throw new Validation("invalid relative path");
    return value;
  }

  private static String requiredText(String value, String name) {
    if (value == null || value.isBlank()) throw new Validation(name + " is required");
    return value.trim();
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  public record Access(boolean userUploadEnabled, boolean canUpload) {}

  public record StagedDraft(
      String relativePath,
      String originalFilename,
      String stagingPath,
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      List<String> manualTags) {}

  public record MetadataPatch(
      String logicalName,
      String sourceDepartment,
      String materialCategory,
      String company,
      String documentType,
      LocalDate documentDate,
      boolean clearDocumentDate,
      List<String> manualTags,
      boolean replaceTags) {}

  public static class IngestionException extends RuntimeException {
    public IngestionException(String message) {
      super(message);
    }
  }

  public static final class Validation extends IngestionException {
    public Validation(String message) {
      super(message);
    }
  }

  public static final class Forbidden extends IngestionException {
    public Forbidden(String message) {
      super(message);
    }
  }

  public static final class Conflict extends IngestionException {
    public Conflict(String message) {
      super(message);
    }
  }

  public static final class NotFound extends IngestionException {
    public NotFound(String message) {
      super(message);
    }
  }
}
