package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CompilationServicesParityTest {
    @Test
    void summaryGraphAndWikiRemainEvidenceGroundedAcrossTheWholeCompilationChain() {
        Fixture f = new Fixture(new FakeModel("large-writer", "small-judge"));

        var summaries = f.summary.compileVersion("v1");
        assertEquals(1, summaries.size());
        SummaryRecord summary = summaries.get(0).summary();
        assertTrue(summary.activeAndGrounded());
        assertEquals(1, summary.facts().size());
        assertEquals(List.of("ev1"), summary.facts().get(0).evidenceIds());
        assertEquals(1, f.derived.summaryIndexes);

        var graph = f.graphCompilation.compileSummary(summary.id());
        assertTrue(graph.coverage().pass());
        assertEquals(1, graph.relations().size());
        assertEquals(List.of("ev1"), graph.relations().get(0).evidenceIds());
        assertEquals(1, f.graphStore.relations.size());

        var wiki = f.wiki.compile(new WikiCompilationService.CompileRequest("COMPANY", "甲公司", graph.entities().get(0).canonicalId(), List.of(summary.id())));
        assertTrue(wiki.coverage().pass());
        assertEquals("ACTIVE", wiki.wiki().lifecycle());
        assertEquals(List.of("甲公司", "乙公司"), wiki.wiki().keywords());
        assertEquals(List.of("ev1"), wiki.wiki().claims().get(0).evidenceIds());
        assertEquals(1, f.derived.wikiIndexes);
    }

    @Test
    void writerAndJudgeCannotBeTheSameModelIdentity() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeObjects objects = new FakeObjects();
        FakeEmbedding embedding = new FakeEmbedding();
        FakeDerived derived = new FakeDerived();
        FakeModel same = new FakeModel("same-model", "same-model");

        assertThrows(IllegalArgumentException.class,
                () -> new SummaryCompilationService(repository, same, objects, embedding, derived));
        assertThrows(IllegalArgumentException.class,
                () -> new WikiCompilationService(repository, same, objects, embedding, derived));
    }

    private static final class Fixture {
        final InMemoryRepository repository = new InMemoryRepository();
        final FakeObjects objects = new FakeObjects();
        final FakeEmbedding embedding = new FakeEmbedding();
        final FakeDerived derived = new FakeDerived();
        final FakeGraphStore graphStore = new FakeGraphStore();
        final SummaryCompilationService summary;
        final GraphCompilationService graphCompilation;
        final WikiCompilationService wiki;

        Fixture(FakeModel model) {
            RawBlock block = new RawBlock("rb1", "v1", 0, "text", "甲公司向乙公司供货。", "甲公司向乙公司供货。",
                    List.of(), 1, 1, List.of(), List.of(595d, 842d), List.of("rb1"), "hash", 12);
            Evidence evidence = new Evidence("ev1", "doc1", "v1", "rb1", "original/doc1/v1/a.pdf",
                    Map.of("block_id", "rb1", "page_start", 1), block.content(), block.contentHash());
            repository.source = new VersionSource("doc1", "v1", "尽调材料", "",
                    new Metadata("投资部", "尽调", List.of("重点"), "甲公司", null, "项目/尽调/业务材料"),
                    List.of(block), Map.of("rb1", evidence));
            summary = new SummaryCompilationService(repository, model, objects, embedding, derived);
            graphCompilation = new GraphCompilationService(repository, model, graphStore);
            wiki = new WikiCompilationService(repository, model, objects, embedding, derived);
        }
    }

    private static final class FakeModel implements Ports.KnowledgeModel {
        private static final Pattern FACT_ID = Pattern.compile("<fact id=\\\"([^\\\"]+)\\\"");
        private final String large;
        private final String small;

        FakeModel(String large, String small) { this.large = large; this.small = small; }
        @Override public String identity(Ports.ModelRole role) { return role == Ports.ModelRole.LARGE ? large : small; }
        @Override public String generate(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt) { return "unused"; }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T generateStructured(Ports.ModelRole role, Ports.ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
            Object result;
            if (outputType == SummaryCompilationService.Classification.class) {
                result = new SummaryCompilationService.Classification("项目/尽调/业务材料", "single", List.of());
            } else if (outputType == SummaryCompilationService.FactProposalSet.class) {
                result = new SummaryCompilationService.FactProposalSet(List.of(
                        new SummaryCompilationService.FactProposal("项目与主体", "甲公司向乙公司供货。", List.of("rb1"))));
            } else if (outputType == SummaryCompilationService.SupportDecision.class) {
                result = new SummaryCompilationService.SupportDecision(true, "directly supported");
            } else if (outputType == SummaryCompilationService.CoverageDecision.class) {
                result = new SummaryCompilationService.CoverageDecision(List.of());
            } else if (outputType == GraphCompilationService.Extraction.class) {
                String factId = firstFactId(userPrompt);
                result = new GraphCompilationService.Extraction(
                        List.of(new GraphCompilationService.ProposedEntity("甲公司", "COMPANY"), new GraphCompilationService.ProposedEntity("乙公司", "COMPANY")),
                        List.of(new GraphCompilationService.ProposedRelation("甲公司", "COMPANY", "SUPPLIES_TO", "乙公司", "COMPANY",
                                List.of(factId), "", "", 0.95)),
                        List.of(new GraphCompilationService.FactDecision(factId, true, "")));
            } else if (outputType == WikiCompilationService.Proposal.class) {
                String factId = firstFactId(userPrompt);
                result = new WikiCompilationService.Proposal("COMPANY", "甲公司",
                        List.of(new WikiCompilationService.ProposedClaim("Profile", "[[甲公司]]向[[乙公司]]供货。", List.of(factId))),
                        List.of(new WikiCompilationService.FactDecision(factId, true, "")));
            } else if (outputType == WikiCompilationService.SupportDecision.class) {
                result = new WikiCompilationService.SupportDecision(true, "directly supported");
            } else {
                throw new AssertionError("unexpected output type " + outputType);
            }
            return (T) result;
        }

        private static String firstFactId(String prompt) {
            Matcher matcher = FACT_ID.matcher(prompt == null ? "" : prompt);
            if (!matcher.find()) throw new AssertionError("no fact id in prompt: " + prompt);
            return matcher.group(1);
        }
    }

    private static final class InMemoryRepository implements CompilationRepository {
        VersionSource source;
        final Map<String, SummaryRecord> summaries = new LinkedHashMap<>();
        final Map<String, WikiRecord> wikis = new LinkedHashMap<>();
        final Map<String, Entity> entities = new LinkedHashMap<>();
        List<DocumentUnit> units = List.of();
        List<Relation> relations = List.of();

        @Override public VersionSource loadVersionSource(String versionId) { return source; }
        @Override public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) { this.units = List.copyOf(units); }
        @Override public void saveSummary(SummaryRecord summary) { summaries.put(summary.id(), summary); }
        @Override public Optional<SummaryRecord> findSummary(String summaryId) { return Optional.ofNullable(summaries.get(summaryId)); }
        @Override public List<SummaryRecord> loadSummaries(List<String> summaryIds) { return summaryIds.stream().map(summaries::get).filter(java.util.Objects::nonNull).toList(); }
        @Override public Entity resolveEntity(String name, String entityType) {
            String key = entityType + "\u0000" + GraphCompilationService.canonicalKey(name);
            return entities.computeIfAbsent(key, ignored -> new Entity(GraphCompilationService.stableId("entity", entityType, GraphCompilationService.canonicalKey(name)),
                    name, entityType, GraphCompilationService.canonicalKey(name), List.of(), Map.of()));
        }
        @Override public void saveGraph(String summaryId, List<Entity> entities, List<Relation> relations) { this.relations = List.copyOf(relations); }
        @Override public long nextWikiVersion(String wikiId) { return wikis.containsKey(wikiId) ? wikis.get(wikiId).version() + 1 : 1; }
        @Override public void saveWiki(WikiRecord wiki) { wikis.put(wiki.id(), wiki); }
        @Override public Ports.DerivedContext derivedContextForSummaries(List<String> summaryIds) {
            return new Ports.DerivedContext(List.of("doc1"), List.of("v1"), List.of("投资部"), List.of("尽调"), List.of("甲公司"), List.of("项目/尽调/业务材料"), List.of("重点"));
        }
    }

    private static final class FakeObjects implements Ports.ObjectStore {
        final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public void put(String key, InputStream source, long size, String contentType) {
            try { values.put(key, source.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public Ports.StoredObject get(String key) { byte[] value = values.get(key); return new Ports.StoredObject(new ByteArrayInputStream(value), value.length, "text/markdown"); }
        @Override public Ports.ObjectInfo stat(String key) { byte[] value = values.get(key); return new Ports.ObjectInfo(key, value.length, "text/markdown", "etag"); }
        @Override public String presignGet(String key, java.time.Duration expiry) { return "https://example.invalid/" + key; }
    }

    private static final class FakeEmbedding implements Ports.EmbeddingPort {
        @Override public int dimensions() { return 4; }
        @Override public List<List<Float>> embed(List<String> texts) { return texts.stream().map(t -> List.of(1f, 0f, 0f, 0f)).toList(); }
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

    private static final class FakeGraphStore implements Ports.GraphStore {
        List<Entity> entities = List.of();
        List<Relation> relations = List.of();
        @Override public void upsertEntities(List<Entity> entities) { this.entities = List.copyOf(entities); }
        @Override public void upsertRelations(List<Relation> relations) { this.relations = List.copyOf(relations); }
        @Override public List<cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate> search(String query, cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters filters, int limit) { return List.of(); }
        @Override public void deactivateVersion(String versionId) {}
    }
}
