package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CompilationContracts {
  private CompilationContracts() {}

  public record VersionSource(
      String documentId,
      String versionId,
      String logicalName,
      String manualDocumentType,
      Metadata metadata,
      List<RawBlock> blocks,
      Map<String, Evidence> evidenceByBlock) {
    public VersionSource {
      blocks = blocks == null ? List.of() : List.copyOf(blocks);
      evidenceByBlock = evidenceByBlock == null ? Map.of() : Map.copyOf(evidenceByBlock);
    }
  }

  public record DocumentUnit(
      String id,
      String versionId,
      int unitNo,
      String title,
      String structureType,
      String documentType,
      int startBlockNo,
      int endBlockNo) {}

  public record SummaryFact(
      String id,
      String summaryId,
      String section,
      String factText,
      List<String> evidenceBlockIds,
      List<String> evidenceIds,
      String groundingStatus,
      String groundingReason) {
    public SummaryFact {
      evidenceBlockIds = evidenceBlockIds == null ? List.of() : List.copyOf(evidenceBlockIds);
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record SummaryRecord(
      String id,
      String unitId,
      String documentId,
      String versionId,
      String objectKey,
      String contentHash,
      String groundingStatus,
      String coverageStatus,
      String lifecycle,
      String model,
      String markdown,
      List<SummaryFact> facts,
      Instant createdAt) {
    public SummaryRecord {
      facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public boolean activeAndGrounded() {
      return "ACTIVE".equals(lifecycle)
          && "PASS".equals(groundingStatus)
          && "PASS".equals(coverageStatus);
    }
  }

  public record ConceptRecord(
      String id,
      String summaryId,
      String versionId,
      String preferredLabel,
      List<String> aliases,
      String conceptTypeHint,
      String description,
      List<String> factIds,
      String lifecycle,
      String model,
      Instant createdAt) {
    public ConceptRecord {
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      factIds = factIds == null ? List.of() : List.copyOf(factIds);
    }
  }

  public record EntityResolution(
      String sourceEntityId,
      String versionId,
      String surfaceForm,
      String entityType,
      String evidenceId,
      String canonicalEntityId,
      double confidence,
      String decidedBy) {}

  public record GraphRelationRejection(
      int candidateIndex,
      String sourceName,
      String sourceType,
      String relationType,
      String targetName,
      String targetType,
      List<String> supportingFactIds,
      String reason) {
    public GraphRelationRejection {
      supportingFactIds = supportingFactIds == null ? List.of() : List.copyOf(supportingFactIds);
    }
  }

  public record WikiClaim(
      String id,
      String wikiId,
      long wikiVersion,
      String section,
      String claimText,
      List<String> factIds,
      List<String> evidenceIds,
      String groundingStatus,
      String temporalStatus) {
    public WikiClaim {
      factIds = factIds == null ? List.of() : List.copyOf(factIds);
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record WikiRecord(
      String id,
      long version,
      String wikiType,
      String canonicalSubjectId,
      String title,
      String objectKey,
      String contentHash,
      String lifecycle,
      String markdown,
      List<WikiClaim> claims,
      List<String> keywords,
      List<String> links,
      Instant createdAt) {
    public WikiRecord {
      claims = claims == null ? List.of() : List.copyOf(claims);
      keywords = keywords == null ? List.of() : List.copyOf(keywords);
      links = links == null ? List.of() : List.copyOf(links);
    }
  }

  public interface CompilationRepository {
    VersionSource loadVersionSource(String versionId);

    void replaceDocumentUnits(String versionId, List<DocumentUnit> units);

    /**
     * Return the units currently persisted for a version. Implementations may return an empty list
     * when the repository does not expose persisted unit metadata (for example, lightweight test
     * fakes); callers must then retain their deterministic proposals.
     */
    default List<DocumentUnit> loadDocumentUnits(String versionId) {
      return List.of();
    }

    void saveSummary(SummaryRecord summary);

    Optional<SummaryRecord> findSummary(String summaryId);

    List<SummaryRecord> loadSummaries(List<String> summaryIds);

    List<SummaryRecord> loadActiveSummariesForVersion(String versionId);

    List<SummaryRecord> loadActiveSummariesForCompany(String company);

    void replaceConceptsForSummary(String summaryId, List<ConceptRecord> concepts);

    List<ConceptRecord> loadActiveConceptsForSummary(String summaryId);

    List<ConceptRecord> loadActiveConceptsForVersion(String versionId);

    Entity resolveEntity(String name, String entityType);

    void replaceGraphRejections(String summaryId, List<GraphRelationRejection> rejections);

    void saveGraph(
        String summaryId,
        List<Entity> entities,
        List<Relation> relations,
        List<EntityResolution> resolutions);

    long nextWikiVersion(String wikiId);

    void saveWiki(WikiRecord wiki);

    DerivedContext derivedContextForSummaries(List<String> summaryIds);
  }
}
