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

    public record VersionSource(String documentId, String versionId, String logicalName, String manualDocumentType,
                                Metadata metadata, List<RawBlock> blocks, Map<String, Evidence> evidenceByBlock) {
        public VersionSource {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            evidenceByBlock = evidenceByBlock == null ? Map.of() : Map.copyOf(evidenceByBlock);
        }
    }

    public record DocumentUnit(String id, String versionId, int unitNo, String title, String structureType,
                               String documentType, int startBlockNo, int endBlockNo) {}

    public record SummaryFact(String id, String summaryId, String section, String factText,
                              List<String> evidenceBlockIds, List<String> evidenceIds,
                              String groundingStatus, String groundingReason) {
        public SummaryFact {
            evidenceBlockIds = evidenceBlockIds == null ? List.of() : List.copyOf(evidenceBlockIds);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record SummaryRecord(String id, String unitId, String documentId, String versionId,
                                String objectKey, String contentHash, String groundingStatus,
                                String coverageStatus, String lifecycle, String model,
                                String markdown, List<SummaryFact> facts, Instant createdAt) {
        public SummaryRecord { facts = facts == null ? List.of() : List.copyOf(facts); }
        public boolean activeAndGrounded() {
            return "ACTIVE".equals(lifecycle) && "PASS".equals(groundingStatus) && "PASS".equals(coverageStatus);
        }
    }

    public record EntityResolution(String sourceEntityId, String versionId, String surfaceForm, String entityType,
                                   String evidenceId, String canonicalEntityId, double confidence, String decidedBy) {}

    public record WikiClaim(String id, String wikiId, long wikiVersion, String section, String claimText,
                            List<String> factIds, List<String> evidenceIds,
                            String groundingStatus, String temporalStatus) {
        public WikiClaim {
            factIds = factIds == null ? List.of() : List.copyOf(factIds);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record WikiRecord(String id, long version, String wikiType, String canonicalSubjectId, String title,
                             String objectKey, String contentHash, String lifecycle, String markdown,
                             List<WikiClaim> claims, List<String> keywords, List<String> links, Instant createdAt) {
        public WikiRecord {
            claims = claims == null ? List.of() : List.copyOf(claims);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    public interface CompilationRepository {
        VersionSource loadVersionSource(String versionId);
        void replaceDocumentUnits(String versionId, List<DocumentUnit> units);
        void saveSummary(SummaryRecord summary);
        Optional<SummaryRecord> findSummary(String summaryId);
        List<SummaryRecord> loadSummaries(List<String> summaryIds);
        Entity resolveEntity(String name, String entityType);
        void saveGraph(String summaryId, List<Entity> entities, List<Relation> relations, List<EntityResolution> resolutions);
        long nextWikiVersion(String wikiId);
        void saveWiki(WikiRecord wiki);
        DerivedContext derivedContextForSummaries(List<String> summaryIds);
    }
}
