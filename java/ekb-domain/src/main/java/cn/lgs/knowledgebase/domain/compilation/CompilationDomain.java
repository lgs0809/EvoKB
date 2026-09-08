package cn.lgs.knowledgebase.domain.compilation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompilationDomain {
  private CompilationDomain() {}

  public enum ArtifactStatus {
    DRAFT,
    GROUNDED,
    REJECTED,
    ACTIVE,
    STALE
  }

  public enum UnitType {
    POLICY,
    CONTRACT,
    REPORT,
    PROCEDURE,
    DEFINITION,
    TABLE,
    GENERAL
  }

  public record Claim(String text, List<String> evidenceIds, double confidence) {
    public Claim {
      if (text == null || text.isBlank())
        throw new IllegalArgumentException("claim text is required");
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      if (confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("confidence must be in [0,1]");
    }

    public boolean grounded() {
      return !evidenceIds.isEmpty();
    }
  }

  public record GroundingGate(double minimumGroundedRatio, double minimumCoverageRatio) {
    public GroundingGate {
      if (minimumGroundedRatio < 0 || minimumGroundedRatio > 1)
        throw new IllegalArgumentException("invalid grounded ratio");
      if (minimumCoverageRatio < 0 || minimumCoverageRatio > 1)
        throw new IllegalArgumentException("invalid coverage ratio");
    }

    public GateDecision evaluate(List<Claim> claims, double coverageRatio) {
      if (claims == null || claims.isEmpty())
        return new GateDecision(false, 0, coverageRatio, "no claims");
      long grounded = claims.stream().filter(Claim::grounded).count();
      double groundedRatio = grounded / (double) claims.size();
      boolean passed =
          groundedRatio >= minimumGroundedRatio && coverageRatio >= minimumCoverageRatio;
      return new GateDecision(
          passed,
          groundedRatio,
          coverageRatio,
          passed ? "passed" : "grounding or coverage below threshold");
    }
  }

  public record GateDecision(
      boolean passed, double groundedRatio, double coverageRatio, String reason) {}

  public record SummaryArtifact(
      String id,
      String documentId,
      String versionId,
      String content,
      List<Claim> claims,
      ArtifactStatus status,
      Instant updatedAt) {
    public SummaryArtifact {
      require(id, "summary id");
      require(documentId, "document id");
      require(versionId, "version id");
      require(content, "summary content");
      claims = claims == null ? List.of() : List.copyOf(claims);
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  public record WikiPage(
      String id,
      String title,
      String content,
      List<String> keywords,
      List<String> links,
      List<Claim> claims,
      ArtifactStatus status,
      Instant updatedAt) {
    public WikiPage {
      require(id, "wiki id");
      require(title, "title");
      require(content, "wiki content");
      keywords = keywords == null ? List.of() : List.copyOf(keywords);
      links = links == null ? List.of() : List.copyOf(links);
      claims = claims == null ? List.of() : List.copyOf(claims);
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(updatedAt, "updatedAt");
    }
  }

  public enum ConflictStatus {
    NONE,
    POTENTIAL,
    CONFIRMED
  }

  public enum RelationLifecycle {
    ACTIVE,
    INACTIVE
  }

  public record Entity(
      String canonicalId,
      String canonicalName,
      String entityType,
      String canonicalKey,
      List<String> aliases,
      Map<String, Object> attributes) {
    public Entity {
      require(canonicalId, "canonical id");
      require(canonicalName, "canonical name");
      require(entityType, "entity type");
      require(canonicalKey, "canonical key");
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  public record Relation(
      String id,
      String subjectEntityId,
      String predicate,
      String objectEntityId,
      Instant validFrom,
      Instant validTo,
      ConflictStatus conflictStatus,
      RelationLifecycle lifecycle,
      List<String> evidenceIds,
      double confidence,
      Map<String, Object> qualifiers) {
    public Relation {
      require(id, "relation id");
      require(subjectEntityId, "subject entity");
      require(predicate, "predicate");
      require(objectEntityId, "object entity");
      if (subjectEntityId.equals(objectEntityId))
        throw new IllegalArgumentException("self relation is not allowed");
      if (validFrom != null && validTo != null && validTo.isBefore(validFrom))
        throw new IllegalArgumentException("validTo cannot be before validFrom");
      Objects.requireNonNull(conflictStatus, "conflictStatus");
      Objects.requireNonNull(lifecycle, "lifecycle");
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
      qualifiers = qualifiers == null ? Map.of() : Map.copyOf(qualifiers);
      if (confidence < 0 || confidence > 1)
        throw new IllegalArgumentException("confidence must be in [0,1]");
    }

    public boolean publishable() {
      return lifecycle == RelationLifecycle.ACTIVE && !evidenceIds.isEmpty();
    }
  }

  public record GenerationSignal(
      long accessCount, long queryHitCount, double evidenceUseRate, boolean manuallyPinned) {
    public boolean highValue(long minimumTraffic, double minimumEvidenceUseRate) {
      return manuallyPinned
          || (accessCount + queryHitCount >= minimumTraffic
              && evidenceUseRate >= minimumEvidenceUseRate);
    }
  }

  public enum GenerationTaskType {
    SOURCE_ASSOCIATION,
    SUMMARY,
    CONCEPT,
    GRAPH,
    WIKI,
    /** Rebuild raw vectors from the durable RawBlock/Evidence source without re-uploading files. */
    EMBEDDING_REBUILD
  }

  public enum GenerationTaskStatus {
    PENDING,
    RETRY_WAIT,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public record GenerationTask(
      String taskId,
      String versionId,
      GenerationTaskType taskType,
      String subjectId,
      GenerationTaskStatus status,
      int attempt,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      String ownerId,
      long executionEpoch,
      Instant stageDeadline,
      long sourceAclVersion) {
    public GenerationTask(
        String taskId,
        String versionId,
        GenerationTaskType taskType,
        String subjectId,
        GenerationTaskStatus status,
        int attempt,
        String errorCode,
        String errorMessage,
        Instant createdAt) {
      this(
          taskId,
          versionId,
          taskType,
          subjectId,
          status,
          attempt,
          errorCode,
          errorMessage,
          createdAt,
          "",
          0,
          null,
          0);
    }

    public GenerationTask {
      require(taskId, "generation task id");
      require(versionId, "generation task version id");
      Objects.requireNonNull(taskType, "taskType");
      subjectId = subjectId == null ? "" : subjectId.trim();
      Objects.requireNonNull(status, "status");
      if (attempt < 0) throw new IllegalArgumentException("generation task attempt must be >= 0");
      errorCode = errorCode == null ? "" : errorCode.trim();
      errorMessage = errorMessage == null ? "" : errorMessage;
      Objects.requireNonNull(createdAt, "createdAt");
      ownerId = ownerId == null ? "" : ownerId.trim();
      if (executionEpoch < 0 || sourceAclVersion < 0)
        throw new IllegalArgumentException("generation claim versions must be nonnegative");
    }
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }
}
