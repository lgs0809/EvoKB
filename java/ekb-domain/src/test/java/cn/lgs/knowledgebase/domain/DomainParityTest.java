package cn.lgs.knowledgebase.domain;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainParityTest {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void adminCanUploadWhileUserIsControlledByRuntimePolicy() {
        var admin = new IdentityDomain.User("u1", "admin", IdentityDomain.Role.ADMIN, IdentityDomain.UserStatus.ACTIVE, NOW);
        var user = new IdentityDomain.User("u2", "reader", IdentityDomain.Role.USER, IdentityDomain.UserStatus.ACTIVE, NOW);
        assertTrue(new IdentityDomain.AccessPolicy(false).canUpload(admin));
        assertFalse(new IdentityDomain.AccessPolicy(false).canUpload(user));
        assertTrue(new IdentityDomain.AccessPolicy(true).canUpload(user));
    }

    @Test
    void interruptedIngestionIsRecoverableAndFailedFileRetryIncrementsAttempt() {
        var metadata = new IngestionDomain.FileMetadata("report", null, null, null, null, null, List.of("a", "a"));
        var running = new IngestionDomain.FileItem("f1", "b1", "a.pdf", "a.pdf", 1, "sha", metadata,
                IngestionDomain.FileStatus.RUNNING, IngestionDomain.ProgressStage.PARSE, 1,
                null, null, null, null, NOW, NOW);
        var recovered = running.recoverInterrupted(NOW.plusSeconds(1));
        assertEquals(IngestionDomain.FileStatus.QUEUED, recovered.status());
        assertEquals("INTERRUPTED", recovered.errorCode());

        var failed = new IngestionDomain.FileItem("f2", "b1", "b.pdf", "b.pdf", 1, "sha2", metadata,
                IngestionDomain.FileStatus.FAILED, IngestionDomain.ProgressStage.FAILED, 2,
                null, null, "X", "bad", NOW, NOW);
        assertEquals(3, failed.retry(NOW.plusSeconds(1)).attempt());
    }

    @Test
    void activatingNewVersionOnlyChangesDocumentVersionLifecycle() {
        var current = version("v1", 1, KnowledgeDomain.VersionLifecycle.ACTIVE);
        var target = version("v2", 2, KnowledgeDomain.VersionLifecycle.PENDING);
        var transition = KnowledgeDomain.activate(current, target);
        assertEquals(KnowledgeDomain.VersionLifecycle.INACTIVE, transition.previous().lifecycle());
        assertEquals(KnowledgeDomain.VersionLifecycle.ACTIVE, transition.activated().lifecycle());
    }

    @Test
    void rerankPoolIsBalancedAcrossRawSummaryWikiAndGraph() {
        var candidates = List.of(
                candidate("r1", RetrievalDomain.LaneType.RAW), candidate("r2", RetrievalDomain.LaneType.RAW),
                candidate("s1", RetrievalDomain.LaneType.SUMMARY), candidate("s2", RetrievalDomain.LaneType.SUMMARY),
                candidate("w1", RetrievalDomain.LaneType.WIKI), candidate("w2", RetrievalDomain.LaneType.WIKI),
                candidate("g1", RetrievalDomain.LaneType.GRAPH), candidate("g2", RetrievalDomain.LaneType.GRAPH));
        var pool = RetrievalDomain.balancedRerankPool(candidates, 4);
        assertEquals(List.of(RetrievalDomain.LaneType.RAW, RetrievalDomain.LaneType.SUMMARY,
                RetrievalDomain.LaneType.WIKI, RetrievalDomain.LaneType.GRAPH), pool.stream().map(RetrievalDomain.Candidate::lane).toList());
    }

    @Test
    void finalRetrievalResultFailsClosedWithoutRawEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new RetrievalDomain.Result(candidate("r", RetrievalDomain.LaneType.RAW), List.of()));
    }

    @Test
    void summaryAndWikiGroundingGateRequiresBothGroundedClaimsAndCoverage() {
        var gate = new CompilationDomain.GroundingGate(0.8, 0.7);
        var grounded = new CompilationDomain.Claim("verified", List.of("ev1"), 1);
        var ungrounded = new CompilationDomain.Claim("guess", List.of(), 0.3);
        assertTrue(gate.evaluate(List.of(grounded, grounded), 0.9).passed());
        assertFalse(gate.evaluate(List.of(grounded, ungrounded), 0.9).passed());
        assertFalse(gate.evaluate(List.of(grounded, grounded), 0.5).passed());
    }

    @Test
    void graphRelationCannotBePublishedWithoutEvidence() {
        assertFalse(new CompilationDomain.Relation("rel", "a", "OWNS_IP", "b", null, null,
                CompilationDomain.ConflictStatus.NONE, CompilationDomain.RelationLifecycle.ACTIVE,
                List.of(), 0.9, Map.of()).publishable());
        assertTrue(new CompilationDomain.Relation("rel", "a", "OWNS_IP", "b", null, null,
                CompilationDomain.ConflictStatus.NONE, CompilationDomain.RelationLifecycle.ACTIVE,
                List.of("ev1"), 0.9, Map.of()).publishable());
    }

    private static KnowledgeDomain.DocumentVersion version(String id, long no, KnowledgeDomain.VersionLifecycle lifecycle) {
        return new KnowledgeDomain.DocumentVersion(id, "doc", no, lifecycle, id + ".pdf", "application/pdf", 1,
                "sha" + no, "original/" + id, null, null, null, null);
    }

    private static RetrievalDomain.Candidate candidate(String id, RetrievalDomain.LaneType lane) {
        return new RetrievalDomain.Candidate(id, "doc", "v1", lane, "knowledge " + id, 0, List.of("ev1"), Map.of());
    }
}
