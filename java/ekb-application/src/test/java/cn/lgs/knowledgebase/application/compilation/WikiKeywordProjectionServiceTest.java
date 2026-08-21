package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiKeywordProjectionServiceTest {
    @Test
    void parsesDirectReverseAndNegatedClaims() {
        var direct = WikiKeywordProjectionService.parse("[[Acme Holdings]] controls [[Acme Tech]]");
        assertEquals("Acme Holdings", direct.sourceName());
        assertEquals("Acme Tech", direct.targetName());
        assertEquals("CONTROLS", direct.relationType());
        assertNull(direct.reason());

        var reverse = WikiKeywordProjectionService.parse("[[Acme Tech]] is controlled by [[Acme Holdings]]");
        assertEquals("Acme Holdings", reverse.sourceName());
        assertEquals("Acme Tech", reverse.targetName());
        assertEquals("CONTROLS", reverse.relationType());

        var negated = WikiKeywordProjectionService.parse("[[Acme Holdings]] does not control [[Acme Tech]]");
        assertNotNull(negated.reason());
    }

    @Test
    void projectionRequiresEvidenceAndProjectsSchemaValidRelation() {
        Entity holding = entity("holding", "Acme Holdings", "COMPANY");
        Entity tech = entity("tech", "Acme Tech", "COMPANY");
        FakeRepository repo = new FakeRepository(List.of(
                new WikiKeywordProjectionService.WikiKeywordClaim("c1", "w1", 1,
                        "[[Acme Holdings]] controls [[Acme Tech]]", List.of("ev1")),
                new WikiKeywordProjectionService.WikiKeywordClaim("c2", "w1", 1,
                        "[[Acme Holdings]] controls [[Acme Tech]]", List.of())
        ), Map.of("Acme Holdings", List.of(holding), "Acme Tech", List.of(tech)));
        FakeGraph graph = new FakeGraph();

        var result = new WikiKeywordProjectionService(repo, graph).project("w1", 1);

        assertEquals(2, result.claimsExamined());
        assertEquals(1, result.relations().size());
        assertEquals("CONTROLS", result.relations().get(0).predicate());
        assertEquals(List.of("ev1"), result.relations().get(0).evidenceIds());
        assertEquals("claim has no active raw evidence", result.skippedClaims().get("c2"));
        assertEquals(2, graph.entities.size());
        assertEquals(1, graph.relations.size());
    }

    private static Entity entity(String id, String name, String type) {
        return new Entity(id, name, type, name.toLowerCase(), List.of(), Map.of());
    }

    private static final class FakeRepository implements WikiKeywordProjectionService.Repository {
        private final List<WikiKeywordProjectionService.WikiKeywordClaim> claims;
        private final Map<String,List<Entity>> entities;
        FakeRepository(List<WikiKeywordProjectionService.WikiKeywordClaim> claims, Map<String,List<Entity>> entities) {
            this.claims = claims; this.entities = entities;
        }
        @Override public List<WikiKeywordProjectionService.WikiKeywordClaim> loadClaims(String wikiId, long wikiVersion) { return claims; }
        @Override public List<Entity> findCanonicalEntitiesByName(String canonicalName) { return entities.getOrDefault(canonicalName, List.of()); }
        @Override public Relation saveRelation(Relation relation, String wikiId, long wikiVersion, String claimId) { return relation; }
    }

    private static final class FakeGraph implements Ports.GraphStore {
        final List<Entity> entities = new ArrayList<>();
        final List<Relation> relations = new ArrayList<>();
        @Override public void upsertEntities(List<Entity> values) { entities.addAll(values); }
        @Override public void upsertRelations(List<Relation> values) { relations.addAll(values); }
        @Override public List<Candidate> search(String query, SearchFilters filters, int limit) { return List.of(); }
        @Override public void deactivateVersion(String versionId) {}
    }
}
