package cn.lgs.knowledgebase.domain.compilation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompilationDomain {
    private CompilationDomain() {}

    public enum ArtifactStatus { DRAFT, GROUNDED, REJECTED, ACTIVE, STALE }
    public enum UnitType { POLICY, CONTRACT, REPORT, PROCEDURE, DEFINITION, TABLE, GENERAL }

    public record Claim(String text, List<String> evidenceIds, double confidence) {
        public Claim {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("claim text is required");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        public boolean grounded() { return !evidenceIds.isEmpty(); }
    }

    public record GroundingGate(double minimumGroundedRatio, double minimumCoverageRatio) {
        public GroundingGate {
            if (minimumGroundedRatio < 0 || minimumGroundedRatio > 1) throw new IllegalArgumentException("invalid grounded ratio");
            if (minimumCoverageRatio < 0 || minimumCoverageRatio > 1) throw new IllegalArgumentException("invalid coverage ratio");
        }

        public GateDecision evaluate(List<Claim> claims, double coverageRatio) {
            if (claims == null || claims.isEmpty()) return new GateDecision(false, 0, coverageRatio, "no claims");
            long grounded = claims.stream().filter(Claim::grounded).count();
            double groundedRatio = grounded / (double) claims.size();
            boolean passed = groundedRatio >= minimumGroundedRatio && coverageRatio >= minimumCoverageRatio;
            return new GateDecision(passed, groundedRatio, coverageRatio,
                    passed ? "passed" : "grounding or coverage below threshold");
        }
    }

    public record GateDecision(boolean passed, double groundedRatio, double coverageRatio, String reason) {}

    public record SummaryArtifact(String id, String documentId, String versionId, String content,
                                  List<Claim> claims, ArtifactStatus status, Instant updatedAt) {
        public SummaryArtifact {
            require(id, "summary id"); require(documentId, "document id"); require(versionId, "version id"); require(content, "summary content");
            claims = claims == null ? List.of() : List.copyOf(claims);
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record WikiPage(String id, String title, String content, List<String> keywords,
                           List<String> links, List<Claim> claims, ArtifactStatus status, Instant updatedAt) {
        public WikiPage {
            require(id, "wiki id"); require(title, "title"); require(content, "wiki content");
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            links = links == null ? List.of() : List.copyOf(links);
            claims = claims == null ? List.of() : List.copyOf(claims);
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public enum ConflictStatus { NONE, POTENTIAL, CONFIRMED }
    public enum RelationLifecycle { ACTIVE, INACTIVE }

    public record Entity(String canonicalId, String canonicalName, String entityType, String canonicalKey,
                         List<String> aliases, Map<String, Object> attributes) {
        public Entity {
            require(canonicalId, "canonical id"); require(canonicalName, "canonical name");
            require(entityType, "entity type"); require(canonicalKey, "canonical key");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Relation(String id, String subjectEntityId, String predicate, String objectEntityId,
                           Instant validFrom, Instant validTo, ConflictStatus conflictStatus, RelationLifecycle lifecycle,
                           List<String> evidenceIds, double confidence, Map<String, Object> qualifiers) {
        public Relation {
            require(id, "relation id"); require(subjectEntityId, "subject entity"); require(predicate, "predicate"); require(objectEntityId, "object entity");
            if (subjectEntityId.equals(objectEntityId)) throw new IllegalArgumentException("self relation is not allowed");
            if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) throw new IllegalArgumentException("validTo cannot be before validFrom");
            Objects.requireNonNull(conflictStatus, "conflictStatus");
            Objects.requireNonNull(lifecycle, "lifecycle");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            qualifiers = qualifiers == null ? Map.of() : Map.copyOf(qualifiers);
            if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        public boolean publishable() { return lifecycle == RelationLifecycle.ACTIVE && !evidenceIds.isEmpty(); }
    }

    public record DistillationSignal(long accessCount, long queryHitCount, double evidenceUseRate, boolean manuallyPinned) {
        public boolean highValue(long minimumTraffic, double minimumEvidenceUseRate) {
            return manuallyPinned || (accessCount + queryHitCount >= minimumTraffic && evidenceUseRate >= minimumEvidenceUseRate);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
