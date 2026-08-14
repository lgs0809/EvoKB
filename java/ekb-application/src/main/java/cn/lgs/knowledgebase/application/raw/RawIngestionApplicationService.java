package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.FileSourcePort;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeRepository;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.application.port.Ports.ParsedArtifactEncoder;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import cn.lgs.knowledgebase.application.port.Ports.ParserPort;
import cn.lgs.knowledgebase.application.port.Ports.RawSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.SourceFile;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Document;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class RawIngestionApplicationService {
  private final FileSourcePort files;
  private final ObjectStore objects;
  private final KnowledgeRepository metadata;
  private final ParserPort parser;
  private final ParsedArtifactEncoder artifactEncoder;
  private final RawSearchIndex search;
  private final EmbeddingPort embedder;
  private final DerivedSearchIndex derivedSearch;
  private final RawTokenCounter tokens;
  private final RawChunkProfile chunkProfile;
  private final RawVectorEncoder vectorEncoder;

  public RawIngestionApplicationService(
      FileSourcePort files,
      ObjectStore objects,
      KnowledgeRepository metadata,
      ParserPort parser,
      ParsedArtifactEncoder artifactEncoder,
      RawSearchIndex search,
      EmbeddingPort embedder,
      DerivedSearchIndex derivedSearch) {
    this(
        files,
        objects,
        metadata,
        parser,
        artifactEncoder,
        search,
        embedder,
        derivedSearch,
        RawTokenCounter.unconfigured(),
        RawChunkProfile.DEFAULT,
        null);
  }

  public RawIngestionApplicationService(
      FileSourcePort files,
      ObjectStore objects,
      KnowledgeRepository metadata,
      ParserPort parser,
      ParsedArtifactEncoder artifactEncoder,
      RawSearchIndex search,
      EmbeddingPort embedder,
      DerivedSearchIndex derivedSearch,
      RawTokenCounter tokens,
      RawChunkProfile chunkProfile,
      RawVectorEncoder vectorEncoder) {
    this.tokens = require(tokens, "raw tokenizer");
    this.chunkProfile = require(chunkProfile, "chunk profile");
    this.vectorEncoder = vectorEncoder;
    this.files = require(files, "file source");
    this.objects = require(objects, "object store");
    this.metadata = require(metadata, "knowledge repository");
    this.parser = require(parser, "parser");
    this.artifactEncoder = require(artifactEncoder, "artifact encoder");
    this.search = require(search, "raw search index");
    this.embedder = require(embedder, "embedder");
    this.derivedSearch = derivedSearch;
  }

  public IngestResult ingest(IngestCommand command, Consumer<ProgressStage> progress) {
    return ingest(command, progress, "", ignored -> {});
  }

  public IngestResult ingest(
      IngestCommand command,
      Consumer<ProgressStage> progress,
      String resumeParserTaskId,
      Consumer<String> parserTaskIdSink) {
    return ingest(command, progress, resumeParserTaskId, parserTaskIdSink, RawExecution.legacy());
  }

  public IngestResult ingest(
      IngestCommand command,
      Consumer<ProgressStage> progress,
      String resumeParserTaskId,
      Consumer<String> parserTaskIdSink,
      RawExecution execution) {
    java.util.Objects.requireNonNull(execution).check();
    Consumer<ProgressStage> guarded =
        stage -> {
          execution.phase(stage);
          emit(progress, stage);
        };
    if (command == null) throw new IllegalArgumentException("ingest command is required");
    if (command.path() == null || command.path().isBlank())
      throw new IllegalArgumentException("path is required");
    Consumer<String> taskSink =
        task -> {
          execution.fenced(
              () -> {
                if (parserTaskIdSink != null) parserTaskIdSink.accept(task);
              });
        };
    SourceFile source = files.inspect(command.path());
    String logicalName =
        command.logicalName() == null || command.logicalName().isBlank()
            ? source.filename()
            : command.logicalName().trim();
    Metadata requestedMetadata =
        command.metadata() == null
            ? new Metadata(null, null, List.of(), null, null, null)
            : command.metadata();
    cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy.requireValid(
        command.securityLevel());

    SourceReservation reserved =
        execution.reserveSource(
            logicalName,
            () -> prepareSource(command, source, logicalName, requestedMetadata, execution));
    Document document = reserved.document();
    DocumentVersion version = reserved.version();
    if (reserved.alreadyActive())
      return new IngestResult(
          document.id(),
          version.id(),
          version.versionNo(),
          true,
          false,
          0,
          0,
          0,
          version.originalObjectKey(),
          version.markdownObjectKey(),
          version.structuredObjectKey(),
          List.of());
    DocumentVersion retry = reserved.retry(), previousActive = reserved.previousActive();
    List<RawBlock> reusableBlocks = reserved.reusableBlocks();
    boolean resumeParsed = reserved.resumeParsed();
    String versionId = version.id(), originalKey = version.originalObjectKey();
    String artifactScope =
        execution.projectionScope().isBlank()
            ? versionId
            : versionId + "/" + execution.projectionScope();
    String markdownKey = "parsed/" + document.id() + "/" + artifactScope + "/markdown.md";
    String structuredKey = "parsed/" + document.id() + "/" + artifactScope + "/structured.json";

    emit(guarded, ProgressStage.STORING);
    // Multipart completion already stored this immutable original with its session metadata.
    // Re-putting the same key would destroy the frozen S3 ETag/version/metadata receipt.
    if (execution.reservedInput().isEmpty()) {
      try (InputStream input = files.open(command.path())) {
        objects.put(originalKey, input, source.size(), source.mediaType());
      } catch (Exception e) {
        throw wrap("raw ingest store original failed", e);
      }
    }

    if (retry != null) {
      search.ensureIndex(embedder.dimensions());
      // A parsed-but-failed retry may already have durable prefix chunks in the raw index.  Keep
      // those documents so a model/container restart can resume from the checkpoint instead of
      // throwing away hours of CPU work.  Parser retries without reusable blocks still start from
      // a clean version.
      if (!resumeParsed && execution.projectionScope().isBlank()) search.deleteVersion(versionId);
    }
    if (execution.projectionScope().isBlank())
      persistSource(reserved, requestedMetadata, command.securityLevel(), execution);
    execution.bind(document.id(), versionId);

    final String finalOriginalKey = originalKey;
    try {
      List<RawBlock> blocks;
      List<Evidence> evidence;
      if (resumeParsed) {
        emit(guarded, ProgressStage.EVIDENCE);
        blocks = reusableBlocks;
        evidence = RawKnowledgeBuilder.evidence(document.id(), finalOriginalKey, blocks);
      } else {
        emit(guarded, ProgressStage.PARSING);
        ParsedDocument parsed;
        var stored = objects.get(originalKey);
        try (InputStream input = stored.stream()) {
          parsed = parser.parse(source.filename(), input, resumeParserTaskId, taskSink);
        } catch (Exception e) {
          throw failure(
              versionId, codeFor(wrap("parser failed", e)), wrap("parser failed", e), execution);
        }
        if (parsed.parserName() == null
            || parsed.parserName().isBlank()
            || parsed.parserVersion() == null
            || parsed.parserVersion().isBlank()) {
          throw failure(
              versionId,
              "PARSER_IDENTITY_MISSING",
              new IllegalStateException("parser name/version missing"),
              execution);
        }
        execution.check();
        byte[] markdown = parsed.markdown().getBytes(StandardCharsets.UTF_8);
        objects.put(
            markdownKey,
            new ByteArrayInputStream(markdown),
            markdown.length,
            "text/markdown; charset=utf-8");
        byte[] structured = artifactEncoder.structuredArtifact(parsed);
        objects.put(
            structuredKey,
            new ByteArrayInputStream(structured),
            structured.length,
            "application/json");

        emit(guarded, ProgressStage.EVIDENCE);
        blocks = RawKnowledgeBuilder.rawBlocks(versionId, parsed.blocks(), tokens);
        evidence = RawKnowledgeBuilder.evidence(document.id(), finalOriginalKey, blocks);
        final List<RawBlock> parsedBlocks = blocks;
        final List<Evidence> parsedEvidence = evidence;
        execution.fenced(
            () ->
                metadata.saveParsed(
                    versionId,
                    markdownKey,
                    structuredKey,
                    parsed.parserName(),
                    parsed.parserVersion(),
                    parsedBlocks,
                    parsedEvidence));
        taskSink.accept("");
      }

      emit(guarded, ProgressStage.EMBEDDING);
      List<RawKnowledgeBuilder.ChunkDraft> chunks =
          RawKnowledgeBuilder.smartChunks(
              document.id(), versionId, requestedMetadata, blocks, chunkProfile, tokens);
      if (vectorEncoder == null)
        throw new IllegalStateException("Raw vector identity/cache is not configured");
      RawVectorEncoder.Session encoding = vectorEncoder.open(document.id(), tokens, chunkProfile);
      search.ensureIndex(embedder.dimensions());
      // The embedding request must receive the complete chunk.  The raw chunk is the semantic
      // unit used for retrieval; do not truncate, summarize, or replace its content with a
      // head/tail approximation.  Index each completed batch before asking for the next one so a
      // model restart leaves a durable checkpoint that can be resumed on retry.
      int batchSize = Math.max(1, Math.min(embedder.preferredBatchSize(), 256));
      // Rebuild exact chunk identities on retry. A count cannot prove that the indexed rows are
      // this prefix or were encoded with the same model. Exact vector-cache keys resume work;
      // index upserts remain grounded in the current evidence and metadata.
      int from = 0;
      List<String> physicalIds = new ArrayList<>();
      while (from < chunks.size()) {
        emit(guarded, ProgressStage.EMBEDDING);
        int to = Math.min(chunks.size(), from + batchSize);
        List<RawKnowledgeBuilder.ChunkDraft> batchChunks = chunks.subList(from, to);
        List<String> texts =
            batchChunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
        List<List<Float>> vectors = encoding.encode(texts);
        execution.check();
        if (vectors.size() != batchChunks.size()) {
          throw failure(
              versionId,
              "EMBEDDING_COUNT_MISMATCH",
              new IllegalStateException(
                  "embedding count " + vectors.size() + " != chunks " + batchChunks.size()),
              execution);
        }
        emit(guarded, ProgressStage.INDEXING);
        physicalIds.addAll(
            search.stageRaw(
                document.id(),
                versionId,
                execution.projectionScope(),
                RawKnowledgeBuilder.toIndexDocuments(batchChunks, evidence, vectors)));
        from = to;
      }

      encoding.assertCurrent();
      emit(guarded, ProgressStage.ACTIVATING);
      if (!execution.projectionScope().isBlank()) {
        execution.publish(
            document.id(),
            versionId,
            List.copyOf(physicalIds),
            () -> {
              search.activateRaw(document.id(), versionId, physicalIds);
              metadata.activateVersion(document.id(), versionId);
            });
      } else {
        try {
          search.activateVersion(document.id(), versionId);
        } catch (RuntimeException e) {
          safeDeactivate(versionId);
          throw failure(versionId, "SEARCH_ACTIVATE_FAILED", e, execution);
        }
        try {
          metadata.activateVersion(document.id(), versionId);
        } catch (RuntimeException e) {
          safeDeactivate(versionId);
          if (previousActive != null) {
            try {
              search.activateVersion(document.id(), previousActive.id());
            } catch (RuntimeException ignored) {
            }
          }
          throw failure(versionId, "METADATA_ACTIVATE_FAILED", e, execution);
        }
      }
      List<String> warnings = new ArrayList<>();
      if (previousActive != null && derivedSearch != null) {
        try {
          derivedSearch.deactivateVersion(previousActive.id());
        } catch (RuntimeException e) {
          warnings.add("derived search cleanup failed: " + e.getMessage());
        }
      }
      return new IngestResult(
          document.id(),
          versionId,
          version.versionNo(),
          false,
          retry != null,
          blocks.size(),
          chunks.size(),
          evidence.size(),
          originalKey,
          markdownKey,
          structuredKey,
          List.copyOf(warnings));
    } catch (RawIngestFailure e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(versionId, codeFor(e), e, execution);
    }
  }

  private RawIngestFailure failure(
      String versionId, String code, RuntimeException cause, RawExecution execution) {
    execution.check();
    execution.fenced(
        () -> {
          if (execution.projectionScope().isBlank()) safeDeactivate(versionId);
          metadata.markVersionFailed(versionId, code, cause.getMessage());
        });
    return new RawIngestFailure(code, cause);
  }

  private record SourceReservation(
      Document document,
      DocumentVersion version,
      DocumentVersion previousActive,
      DocumentVersion retry,
      List<RawBlock> reusableBlocks,
      boolean resumeParsed,
      boolean alreadyActive) {}

  private SourceReservation prepareSource(
      IngestCommand command,
      SourceFile source,
      String logicalName,
      Metadata requestedMetadata,
      RawExecution execution) {
    var existingDocument = metadata.findDocumentByLogicalName(logicalName);
    Document document =
        existingDocument.orElseGet(
            () -> new Document(newUuid(), logicalName, command.sourcePath()));
    DocumentVersion retry = execution.reservedInput().orElse(null);
    if (retry != null
        && (!document.id().equals(retry.documentId())
            || !source.sha256().equals(retry.sha256())
            || source.size() != retry.sizeBytes()
            || !(retry.lifecycle() == VersionLifecycle.PENDING
                || retry.lifecycle() == VersionLifecycle.FAILED
                || retry.lifecycle() == VersionLifecycle.ACTIVE)))
      throw new IllegalStateException(
          "INPUT_ERROR: reserved multipart version differs from verified source");
    if (retry != null && retry.lifecycle() == VersionLifecycle.ACTIVE) {
      requireRequestedClassification(command.securityLevel(), retry.id());
      if (!execution.completedInputCurrent(document.id(), retry.id()))
        throw new IllegalStateException(
            "RAW_SOURCE_REVOKED: reserved active input no longer has a current projection");
      execution.bind(document.id(), retry.id());
      return new SourceReservation(document, retry, null, null, List.of(), false, true);
    }
    if (retry == null && existingDocument.isPresent()) {
      DocumentVersion existing =
          metadata.findVersionBySha(document.id(), source.sha256()).orElse(null);
      if (existing != null) {
        if (existing.lifecycle() == VersionLifecycle.ACTIVE) {
          requireRequestedClassification(command.securityLevel(), existing.id());
          execution.bind(document.id(), existing.id());
          return new SourceReservation(document, existing, null, null, List.of(), false, true);
        }
        if (existing.lifecycle() == VersionLifecycle.FAILED
            || existing.lifecycle() == VersionLifecycle.PENDING) {
          retry = existing;
        } else
          throw new IllegalStateException(
              "identical content already exists as version "
                  + existing.id()
                  + " with lifecycle "
                  + existing.lifecycle());
      }
    }
    List<RawBlock> reusableBlocks = List.of();
    boolean resumeParsed = false;
    if (retry != null) {
      reusableBlocks = metadata.rawBlocks(retry.id());
      resumeParsed = !reusableBlocks.isEmpty();
    }

    DocumentVersion previousActive = metadata.currentActiveVersion(document.id()).orElse(null);
    DocumentVersion version =
        retry == null
            ? new DocumentVersion(
                newUuid(),
                document.id(),
                metadata.nextVersionNo(document.id()),
                VersionLifecycle.PENDING,
                source.filename(),
                source.mediaType(),
                source.size(),
                source.sha256(),
                "original/" + document.id() + "/PENDING/" + source.filename(),
                "",
                "",
                "",
                "")
            : new DocumentVersion(
                retry.id(),
                retry.documentId(),
                retry.versionNo(),
                VersionLifecycle.PENDING,
                source.filename(),
                source.mediaType(),
                source.size(),
                source.sha256(),
                retry.originalObjectKey(),
                retry.markdownObjectKey(),
                retry.structuredObjectKey(),
                retry.parserName(),
                retry.parserVersion());
    String versionId = version.id();
    String originalKey =
        retry != null && retry.originalObjectKey() != null && !retry.originalObjectKey().isBlank()
            ? retry.originalObjectKey()
            : "original/" + document.id() + "/" + versionId + "/" + source.filename();
    version =
        new DocumentVersion(
            version.id(),
            version.documentId(),
            version.versionNo(),
            version.lifecycle(),
            version.originalFilename(),
            version.mediaType(),
            version.sizeBytes(),
            version.sha256(),
            originalKey,
            version.markdownObjectKey(),
            version.structuredObjectKey(),
            version.parserName(),
            version.parserVersion());
    SourceReservation reserved =
        new SourceReservation(
            document, version, previousActive, retry, reusableBlocks, resumeParsed, false);
    if (!execution.projectionScope().isBlank()) {
      persistSource(reserved, requestedMetadata, command.securityLevel(), execution);
      execution.bind(document.id(), version.id());
    }
    return reserved;
  }

  private void requireRequestedClassification(Integer requested, String versionId) {
    if (requested != null
        && !metadata.sourceSecurityLevel(versionId).map(requested::equals).orElse(false))
      throw new IllegalStateException(
          "INPUT_ERROR: identical active source has another classification; update its document metadata explicitly");
  }

  private void persistSource(
      SourceReservation source,
      Metadata requestedMetadata,
      Integer securityLevel,
      RawExecution execution) {
    execution.fenced(
        () -> {
          if (source.retry() != null)
            metadata.resetFailedVersion(
                source.version().id(), requestedMetadata, source.resumeParsed(), securityLevel);
          else
            metadata.createPendingVersion(
                source.document(), source.version(), requestedMetadata, securityLevel);
        });
  }

  private void safeDeactivate(String versionId) {
    try {
      search.deactivateVersion(versionId);
    } catch (RuntimeException ignored) {
    }
  }

  private static String codeFor(RuntimeException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      String text = cause.getMessage() == null ? "" : cause.getMessage();
      for (String deterministic :
          List.of(
              "INPUT_ERROR",
              "QUALITY_REVIEW_REQUIRED",
              "TOKENIZER_UNAVAILABLE",
              "ENCODING_PROFILE_MISMATCH",
              "PARSER_PROFILE_INVALID",
              "PARSER_VERSION_MISMATCH",
              "RAW_SOURCE_REVOKED",
              "RAW_STALE_CLAIM")) if (text.contains(deterministic)) return deterministic;
    }
    if (message.contains("parser")) return "PARSER_FAILED";
    if (message.contains("embedding")) return "EMBEDDING_FAILED";
    if (message.contains("opensearch") || message.contains("search")) return "SEARCH_INDEX_FAILED";
    if (message.contains("structured")) return "PARSED_STRUCTURED_BUILD_FAILED";
    return "RAW_INGEST_FAILED";
  }

  private static void emit(Consumer<ProgressStage> progress, ProgressStage stage) {
    if (progress != null) progress.accept(stage);
  }

  private static String newUuid() {
    return UUID.randomUUID().toString();
  }

  private static RuntimeException wrap(String message, Exception e) {
    return e instanceof RuntimeException r ? r : new IllegalStateException(message, e);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  public record IngestCommand(
      String path,
      String logicalName,
      String sourcePath,
      Metadata metadata,
      Integer securityLevel) {
    public IngestCommand(String path, String logicalName, String sourcePath, Metadata metadata) {
      this(path, logicalName, sourcePath, metadata, null);
    }
  }

  public record IngestResult(
      String documentId,
      String versionId,
      long versionNo,
      boolean reused,
      boolean retried,
      int blockCount,
      int chunkCount,
      int evidenceCount,
      String originalObjectKey,
      String markdownObjectKey,
      String structuredObjectKey,
      List<String> warnings) {}

  public static final class RawIngestFailure extends RuntimeException {
    private final String code;

    RawIngestFailure(String code, RuntimeException cause) {
      super(cause.getMessage(), cause);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
