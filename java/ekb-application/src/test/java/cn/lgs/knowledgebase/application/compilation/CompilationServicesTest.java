package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CompilationServicesTest {
    @Test
    void summaryOnlyActivatesAfterIndependentGroundingAndCoveragePass() {
        Fixture f = new Fixture();
        f.model.enqueue(Ports.ModelTask.CLASSIFY_UNIT,
                new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
        f.model.enqueue(Ports.ModelTask.SUMMARY,
                new SummaryCompilationService.FactProposalSet(List.of(
                        new SummaryCompilationService.FactProposal("合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.SupportDecision(true, "direct"));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of()));

        var service = new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived);
        var result = service.compileVersion("v1");

        assertEquals(1, result.size());
        assertTrue(result.get(0).summary().activeAndGrounded());
        assertEquals(1, f.derived.summaryIndexes);
        assertTrue(f.repo.summaries.containsKey(result.get(0).summary().id()));
    }

    @Test
    void summaryFailsClosedWhenCoverageStillHasGroundedMissingFactAfterRepair() {
        Fixture f = new Fixture();
        f.model.enqueue(Ports.ModelTask.CLASSIFY_UNIT,
                new SummaryCompilationService.Classification("合同/协议", "single", List.of()));
        f.model.enqueue(Ports.ModelTask.SUMMARY,
                new SummaryCompilationService.FactProposalSet(List.of(
                        new SummaryCompilationService.FactProposal("合同主体", "甲公司与乙公司签署协议。", List.of("b1")))));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.SupportDecision(true, "direct"));
        var missing = new SummaryCompilationService.MissingFact("协议期限为一年", "期限与时间节点", List.of("b2"));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of(missing)));
        f.model.enqueue(Ports.ModelTask.SUMMARY,
                new SummaryCompilationService.FactProposalSet(List.of(
                        new SummaryCompilationService.FactProposal("期限与时间节点", "协议期限为一年。", List.of("b2")))));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.SupportDecision(true, "direct"));
        f.model.enqueue(Ports.ModelTask.SUMMARY_JUDGE, new SummaryCompilationService.CoverageDecision(List.of(missing)));

        var result = new SummaryCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived).compileVersion("v1");

        assertFalse(result.get(0).summary().activeAndGrounded());
        assertEquals("FAIL", result.get(0).summary().coverageStatus());
        assertEquals(0, f.derived.summaryIndexes);
    }

    @Test
    void graphRequiresDecisionForEveryFactAndEveryEligibleFactMustBeCovered() {
        Fixture f = new Fixture();
        SummaryRecord summary = activeSummary("s1");
        f.repo.summaries.put(summary.id(), summary);
        f.model.enqueue(Ports.ModelTask.RELATION_EXTRACT, new GraphCompilationService.Extraction(List.of(), List.of(),
                List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1"));
        assertTrue(error.getMessage().contains("uncovered eligible"));
    }

    @Test
    void graphPersistsCanonicalEntitiesAndRawEvidenceBackedRelation() {
        Fixture f = new Fixture();
        SummaryRecord summary = activeSummary("s1");
        f.repo.summaries.put(summary.id(), summary);
        f.model.enqueue(Ports.ModelTask.RELATION_EXTRACT,
                new GraphCompilationService.Extraction(List.of(),
                        List.of(new GraphCompilationService.ProposedRelation("张三", "PERSON", "DIRECTOR_OF", "甲公司", "COMPANY",
                                List.of("f1"), "2026-01-01", "", 0.95)),
                        List.of(new GraphCompilationService.FactDecision("f1", true, ""))));

        var result = new GraphCompilationService(f.repo, f.model, f.graph).compileSummary("s1");

        assertEquals(2, result.entities().size());
        assertEquals(1, result.relations().size());
        assertEquals(List.of("ev1"), result.relations().get(0).evidenceIds());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), result.relations().get(0).validFrom());
        assertEquals(1, f.graph.relations.size());
    }

    @Test
    void wikiRejectsClaimWhenIndependentJudgeDoesNotSupportIt() {
        Fixture f = new Fixture();
        SummaryRecord summary = activeSummary("s1");
        f.repo.summaries.put(summary.id(), summary);
        f.model.enqueue(Ports.ModelTask.WIKI_WRITE,
                new WikiCompilationService.Proposal("COMPANY", "甲公司", List.of(
                        new WikiCompilationService.ProposedClaim("Governance", "[[张三]]担任[[甲公司]]董事。", List.of("f1"))),
                        List.of(new WikiCompilationService.FactDecision("f1", true, ""))));
        f.model.enqueue(Ports.ModelTask.WIKI_JUDGE, new WikiCompilationService.SupportDecision(false, "unsupported"));

        assertThrows(IllegalStateException.class, () -> new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
                .compile(new WikiCompilationService.CompileRequest("COMPANY", "甲公司", "entity-company", List.of("s1"))));
        assertEquals(0, f.derived.wikiIndexes);
    }

    @Test
    void wikiPersistsOnlyGroundedCoverageCompletePageAndPreservesWikilinks() {
        Fixture f = new Fixture();
        SummaryRecord summary = activeSummary("s1");
        f.repo.summaries.put(summary.id(), summary);
        f.model.enqueue(Ports.ModelTask.WIKI_WRITE,
                new WikiCompilationService.Proposal("COMPANY", "甲公司", List.of(
                        new WikiCompilationService.ProposedClaim("Governance", "[[张三]]担任[[甲公司]]董事。", List.of("f1"))),
                        List.of(new WikiCompilationService.FactDecision("f1", true, ""))));
        f.model.enqueue(Ports.ModelTask.WIKI_JUDGE, new WikiCompilationService.SupportDecision(true, "direct"));

        var result = new WikiCompilationService(f.repo, f.model, f.objects, f.embedding, f.derived)
                .compile(new WikiCompilationService.CompileRequest("COMPANY", "甲公司", "entity-company", List.of("s1")));

        assertTrue(result.coverage().pass());
        assertEquals(List.of("张三", "甲公司"), result.wiki().links());
        assertEquals(1, f.derived.wikiIndexes);
        assertTrue(f.repo.wikis.containsKey(result.wiki().id()));
    }

    private static SummaryRecord activeSummary(String id) {
        SummaryFact fact = new SummaryFact("f1", id, "Governance", "张三担任甲公司董事。", List.of("b1"), List.of("ev1"), "PASS", "direct");
        return new SummaryRecord(id, "u1", "d1", "v1", "summary/key", "hash", "PASS", "PASS", "ACTIVE", "large-model",
                "# summary", List.of(fact), Instant.parse("2026-08-14T00:00:00Z"));
    }

    private static final class Fixture {
        final FakeRepository repo = new FakeRepository();
        final ScriptedModel model = new ScriptedModel();
        final InMemoryObjects objects = new InMemoryObjects();
        final FakeEmbedding embedding = new FakeEmbedding();
        final FakeDerived derived = new FakeDerived();
        final FakeGraph graph = new FakeGraph();

        Fixture() {
            RawBlock b1 = block("b1", 0, "甲公司与乙公司签署协议。张三担任甲公司董事。");
            RawBlock b2 = block("b2", 1, "协议期限为一年。");
            Map<String, Evidence> evidence = Map.of(
                    "b1", new Evidence("ev1", "d1", "v1", "b1", "original", Map.of(), b1.content(), b1.contentHash()),
                    "b2", new Evidence("ev2", "d1", "v1", "b2", "original", Map.of(), b2.content(), b2.contentHash()));
            repo.source = new VersionSource("d1", "v1", "合同A", "", new Metadata("法务", "合同", List.of(), "甲公司", null, "合同/协议"), List.of(b1, b2), evidence);
        }
    }

    private static RawBlock block(String id, int no, String content) {
        return new RawBlock(id, "v1", no, "text", content, content, List.of(), 1, 1, List.of(), List.of(), List.of(id),
                cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(content), 10);
    }

    private static final class ScriptedModel implements Ports.KnowledgeModel {
        final Map<Ports.ModelTask, Deque<Object>> scripted = new LinkedHashMap<>();
        void enqueue(Ports.ModelTask task, Object value) { scripted.computeIfAbsent(task, k -> new ArrayDeque<>()).add(value); }
        @Override public String identity(Ports.ModelRole role) { return role == Ports.ModelRole.LARGE ? "large-model" : "small-judge"; }
        @Override public String generate(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) { throw new UnsupportedOperationException(); }
        @Override public <T> T generateStructured(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
            Object next = Optional.ofNullable(scripted.get(task)).map(Deque::pollFirst).orElseThrow(() -> new AssertionError("no scripted output for " + task));
            return outputType.cast(next);
        }
    }

    private static final class FakeRepository implements CompilationRepository {
        VersionSource source;
        final Map<String, SummaryRecord> summaries = new LinkedHashMap<>();
        final Map<String, WikiRecord> wikis = new LinkedHashMap<>();
        long wikiVersion = 0;
        @Override public VersionSource loadVersionSource(String versionId) { return source; }
        @Override public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {}
        @Override public void saveSummary(SummaryRecord summary) { summaries.put(summary.id(), summary); }
        @Override public Optional<SummaryRecord> findSummary(String summaryId) { return Optional.ofNullable(summaries.get(summaryId)); }
        @Override public List<SummaryRecord> loadSummaries(List<String> summaryIds) { return summaryIds.stream().map(summaries::get).filter(java.util.Objects::nonNull).toList(); }
        @Override public Entity resolveEntity(String name, String entityType) {
            String key = GraphCompilationService.canonicalKey(name);
            return new Entity(GraphCompilationService.stableId("entity", entityType, key), name, entityType, key, List.of(), Map.of());
        }
        @Override public void saveGraph(String summaryId, List<Entity> entities, List<Relation> relations) {}
        @Override public long nextWikiVersion(String wikiId) { return ++wikiVersion; }
        @Override public void saveWiki(WikiRecord wiki) { wikis.put(wiki.id(), wiki); }
        @Override public Ports.DerivedContext derivedContextForSummaries(List<String> summaryIds) {
            return new Ports.DerivedContext(List.of("d1"), List.of("v1"), List.of("法务"), List.of("合同"), List.of("甲公司"), List.of("合同/协议"), List.of());
        }
    }

    private static final class InMemoryObjects implements Ports.ObjectStore {
        final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public void put(String key, InputStream source, long size, String contentType) {
            try { values.put(key, source.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public Ports.StoredObject get(String key) { byte[] value = values.getOrDefault(key, new byte[0]); return new Ports.StoredObject(new ByteArrayInputStream(value), value.length, "text/plain"); }
        @Override public Ports.ObjectInfo stat(String key) { byte[] value = values.getOrDefault(key, new byte[0]); return new Ports.ObjectInfo(key, value.length, "text/plain", "etag"); }
        @Override public String presignGet(String key, java.time.Duration expiry) { return key; }
    }

    private static final class FakeEmbedding implements Ports.EmbeddingPort {
        @Override public int dimensions() { return 3; }
        @Override public List<List<Float>> embed(List<String> texts) { return texts.stream().map(t -> List.of(1f, 0f, 0f)).toList(); }
    }

    private static final class FakeDerived implements Ports.DerivedSearchIndex {
        int summaryIndexes;
        int wikiIndexes;
        @Override public void ensureIndex(int embeddingDimensions) {}
        @Override public void indexSummary(cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact summary, Ports.DerivedContext context, List<Float> embedding) { summaryIndexes++; }
        @Override public void indexWiki(cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage page, Ports.DerivedContext context, List<Float> embedding) { wikiIndexes++; }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchSummaryBm25(String query, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchSummaryVector(List<Float> vector, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiBm25(String query, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> searchWikiVector(List<Float> vector, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public void deactivateVersion(String versionId) {}
    }

    private static final class FakeGraph implements Ports.GraphStore {
        final List<Relation> relations = new ArrayList<>();
        @Override public void upsertEntities(List<Entity> entities) {}
        @Override public void upsertRelations(List<Relation> relations) { this.relations.addAll(relations); }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> search(String query, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public void deactivateVersion(String versionId) {}
    }
}
