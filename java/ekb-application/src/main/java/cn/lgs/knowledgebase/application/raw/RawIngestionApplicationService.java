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

  public RawIngestionApplicationService(
      FileSourcePort files,
      ObjectStore objects,
      KnowledgeRepository metadata,
      ParserPort parser,
      ParsedArtifactEncoder artifactEncoder,
      RawSearchIndex search,
      EmbeddingPort embedder,
      DerivedSearchIndex derivedSearch) {
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
    if (command == null) throw new IllegalArgumentException("ingest command is required");
    if (command.path() == null || command.path().isBlank())
      throw new IllegalArgumentException("path is required");
    Consumer<String> taskSink = parserTaskIdSink == null ? ignored -> {} : parserTaskIdSink;
    SourceFile source = files.inspect(command.path());
    String logicalName =
        command.logicalName() == null || command.logicalName().isBlank()
            ? source.filename()
            : command.logicalName().trim();
    Metadata requestedMetadata =
        command.metadata() == null
            ? new Metadata(null, null, List.of(), null, null, null)
            : command.metadata();

    var existingDocument = metadata.findDocumentByLogicalName(logicalName);
    Document document =
        existingDocument.orElseGet(
            () -> new Document(newUuid(), logicalName, command.sourcePath()));
    DocumentVersion retry = null;
    if (existingDocument.isPresent()) {
      DocumentVersion existing =
          metadata.findVersionBySha(document.id(), source.sha256()).orElse(null);
      if (existing != null) {
        if (existing.lifecycle() == VersionLifecycle.ACTIVE) {
          return new IngestResult(
              document.id(),
              existing.id(),
              existing.versionNo(),
              true,
              false,
              0,
              0,
              0,
              existing.originalObjectKey(),
              existing.markdownObjectKey(),
              existing.structuredObjectKey(),
              List.of());
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
    String markdownKey = "parsed/" + document.id() + "/" + versionId + "/markdown.md";
    String structuredKey = "parsed/" + document.id() + "/" + versionId + "/structured.json";

    emit(progress, ProgressStage.STORING);
    try (InputStream input = files.open(command.path())) {
      objects.put(originalKey, input, source.size(), source.mediaType());
    } catch (Exception e) {
      throw wrap("raw ingest store original failed", e);
    }

    if (retry != null) {
      search.ensureIndex(embedder.dimensions());
      // A parsed-but-failed retry may already have durable prefix chunks in the raw index.  Keep
      // those documents so a model/container restart can resume from the checkpoint instead of
      // throwing away hours of CPU work.  Parser retries without reusable blocks still start from
      // a clean version.
      if (!resumeParsed) search.deleteVersion(versionId);
      metadata.resetFailedVersion(versionId, requestedMetadata, resumeParsed);
    } else {
      metadata.createPendingVersion(document, version, requestedMetadata);
    }

    final String finalOriginalKey = originalKey;
    try {
      List<RawBlock> blocks;
      List<Evidence> evidence;
      if (resumeParsed) {
        emit(progress, ProgressStage.EVIDENCE);
        blocks = reusableBlocks;
        evidence = RawKnowledgeBuilder.evidence(document.id(), finalOriginalKey, blocks);
      } else {
        emit(progress, ProgressStage.PARSING);
        ParsedDocument parsed;
        var stored = objects.get(originalKey);
        try (InputStream input = stored.stream()) {
          parsed = parser.parse(source.filename(), input, resumeParserTaskId, taskSink);
        } catch (Exception e) {
          throw failure(versionId, "PARSER_FAILED", wrap("parser failed", e));
        }
        if (parsed.parserName() == null
            || parsed.parserName().isBlank()
            || parsed.parserVersion() == null
            || parsed.parserVersion().isBlank()) {
          throw failure(
              versionId,
              "PARSER_IDENTITY_MISSING",
              new IllegalStateException("parser name/version missing"));
        }
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

        emit(progress, ProgressStage.EVIDENCE);
        blocks = RawKnowledgeBuilder.rawBlocks(versionId, parsed.blocks());
        evidence = RawKnowledgeBuilder.evidence(document.id(), finalOriginalKey, blocks);
        metadata.saveParsed(
            versionId,
            markdownKey,
            structuredKey,
            parsed.parserName(),
            parsed.parserVersion(),
            blocks,
            evidence);
        taskSink.accept("");
      }

      emit(progress, ProgressStage.EMBEDDING);
      List<RawKnowledgeBuilder.ChunkDraft> chunks =
          RawKnowledgeBuilder.smartChunks(document.id(), versionId, requestedMetadata, blocks);
      search.ensureIndex(embedder.dimensions());
      // The embedding request must receive the complete chunk.  The raw chunk is the semantic
      // unit used for retrieval; do not truncate, summarize, or replace its content with a
      // head/tail approximation.  Index each completed batch before asking for the next one so a
      // model restart leaves a durable checkpoint that can be resumed on retry.
      int batchSize = Math.max(1, Math.min(embedder.preferredBatchSize(), 256));
      int from = 0;
      if (retry != null && resumeParsed && !chunks.isEmpty()) {
        int indexed = search.indexedChunkCount(versionId);
        if (indexed > 0 && indexed <= chunks.size()) from = indexed;
      }
      emit(progress, ProgressStage.INDEXING);
      while (from < chunks.size()) {
        int to = Math.min(chunks.size(), from + batchSize);
        List<RawKnowledgeBuilder.ChunkDraft> batchChunks = chunks.subList(from, to);
        List<String> texts =
            batchChunks.stream().map(RawKnowledgeBuilder.ChunkDraft::content).toList();
        List<List<Float>> vectors = embedder.embed(texts);
        if (vectors.size() != batchChunks.size()) {
          throw failure(
              versionId,
              "EMBEDDING_COUNT_MISMATCH",
              new IllegalStateException(
                  "embedding count " + vectors.size() + " != chunks " + batchChunks.size()));
        }
        search.indexRaw(
            document.id(),
            versionId,
            RawKnowledgeBuilder.toIndexDocuments(batchChunks, evidence, vectors));
        from = to;
      }

      emit(progress, ProgressStage.ACTIVATING);
      try {
        search.activateVersion(document.id(), versionId);
      } catch (RuntimeException e) {
        safeDeactivate(versionId);
        throw failure(versionId, "SEARCH_ACTIVATE_FAILED", e);
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
        throw failure(versionId, "METADATA_ACTIVATE_FAILED", e);
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
      throw failure(versionId, codeFor(e), e);
    }
  }

  private RawIngestFailure failure(String versionId, String code, RuntimeException cause) {
    safeDeactivate(versionId);
    try {
      metadata.markVersionFailed(versionId, code, cause.getMessage());
    } catch (RuntimeException ignored) {
    }
    return new RawIngestFailure(code, cause);
  }

  private void safeDeactivate(String versionId) {
    try {
      search.deactivateVersion(versionId);
    } catch (RuntimeException ignored) {
    }
  }

  private static String codeFor(RuntimeException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
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
      String path, String logicalName, String sourcePath, Metadata metadata) {}

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
