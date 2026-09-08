package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WikiKeywordProjectionServiceTest {
  @Test
  void parsesDirectReverseAndNegatedClaims() {
    var direct = WikiKeywordProjectionService.parse("[[Acme Holdings]] controls [[Acme Tech]]");
    assertEquals("Acme Holdings", direct.sourceName());
    assertEquals("Acme Tech", direct.targetName());
    assertEquals("CONTROLS", direct.relationType());
    assertNull(direct.reason());

    var reverse =
        WikiKeywordProjectionService.parse("[[Acme Tech]] is controlled by [[Acme Holdings]]");
    assertEquals("Acme Holdings", reverse.sourceName());
    assertEquals("Acme Tech", reverse.targetName());
    assertEquals("CONTROLS", reverse.relationType());

    var negated =
        WikiKeywordProjectionService.parse("[[Acme Holdings]] does not control [[Acme Tech]]");
    assertNotNull(negated.reason());
  }

  @Test
  void projectionRequiresEvidenceAndProjectsSchemaValidRelation() {
    Entity holding = entity("holding", "Acme Holdings", "COMPANY");
    Entity tech = entity("tech", "Acme Tech", "COMPANY");
    FakeRepository repo =
        new FakeRepository(
            List.of(
                new WikiKeywordProjectionService.WikiKeywordClaim(
                    "c1",
                    "w1",
                    1,
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    List.of("ev1"),
                    fixtureAudit(),
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    true),
                new WikiKeywordProjectionService.WikiKeywordClaim(
                    "c2",
                    "w1",
                    1,
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    List.of(),
                    fixtureAudit(),
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    true)),
            Map.of("Acme Holdings", List.of(holding), "Acme Tech", List.of(tech)));
    FakeGraph graph = new FakeGraph();

    var result = new WikiKeywordProjectionService(repo, graph).project("w1", 1);

    assertEquals(2, result.claimsExamined());
    assertEquals(1, result.relations().size());
    assertEquals("CONTROLS", result.relations().get(0).predicate());
    assertEquals(List.of("ev1"), result.relations().get(0).evidenceIds());
    assertEquals("claim has no active raw evidence", result.skippedClaims().get("c2"));
    assertEquals(2, graph.entities.size());
    assertEquals(1, graph.relations.size());
    assertEquals(List.of("public-input", "uncited-restricted-input"), graph.inputs);
  }

  @Test
  void legacyPassClaimsCannotCreateFreshGraphRelations() {
    FakeRepository repo =
        new FakeRepository(
            List.of(
                new WikiKeywordProjectionService.WikiKeywordClaim(
                    "legacy", "w1", 1, "[[Acme Holdings]] controls [[Acme Tech]]", List.of("ev1"))),
            Map.of(
                "Acme Holdings",
                List.of(entity("holding", "Acme Holdings", "COMPANY")),
                "Acme Tech",
                List.of(entity("tech", "Acme Tech", "COMPANY"))));
    FakeGraph graph = new FakeGraph();
    var result = new WikiKeywordProjectionService(repo, graph).project("w1", 1);
    assertTrue(result.relations().isEmpty());
    assertTrue(graph.entities.isEmpty());
    assertTrue(graph.relations.isEmpty());
    assertTrue(result.skippedClaims().get("legacy").contains("current grounding audit"));
  }

  @Test
  void currentClaimWithoutCompleteWikiInputProofCannotCreateAnyProjection() {
    FakeRepository repo =
        new FakeRepository(
            List.of(
                new WikiKeywordProjectionService.WikiKeywordClaim(
                    "c1",
                    "w1",
                    1,
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    List.of("ev1"),
                    fixtureAudit(),
                    "[[Acme Holdings]] controls [[Acme Tech]]",
                    true)),
            Map.of(
                "Acme Holdings",
                List.of(entity("holding", "Acme Holdings", "COMPANY")),
                "Acme Tech",
                List.of(entity("tech", "Acme Tech", "COMPANY"))));
    repo.inputs = List.of();
    FakeGraph graph = new FakeGraph();
    var result = new WikiKeywordProjectionService(repo, graph).project("w1", 1);
    assertTrue(result.relations().isEmpty());
    assertTrue(graph.entities.isEmpty());
    assertTrue(graph.relations.isEmpty());
    assertEquals(0, repo.writes);
    assertTrue(result.skippedClaims().get("c1").contains("complete Wiki build inputs"));
  }

  private static GroundingConstraintAudit.Audit fixtureAudit() {
    String source = "[[Acme Holdings]] controls [[Acme Tech]]";
    return GroundingConstraintAudit.accepted(
        source, GroundingConstraintAudit.exactCopyChecks(source, List.of(source)));
  }

  private static Entity entity(String id, String name, String type) {
    return new Entity(id, name, type, name.toLowerCase(), List.of(), Map.of());
  }

  private static final class FakeRepository implements WikiKeywordProjectionService.Repository {
    private final List<WikiKeywordProjectionService.WikiKeywordClaim> claims;
    private final Map<String, List<Entity>> entities;
    List<String> inputs = List.of("public-input", "uncited-restricted-input");
    int writes;

    FakeRepository(
        List<WikiKeywordProjectionService.WikiKeywordClaim> claims,
        Map<String, List<Entity>> entities) {
      this.claims = claims;
      this.entities = entities;
    }

    @Override
    public List<WikiKeywordProjectionService.WikiKeywordClaim> loadClaims(
        String wikiId, long wikiVersion) {
      return claims;
    }

    @Override
    public List<String> loadBuildInputVersions(String wikiId, long wikiVersion) {
      return inputs;
    }

    @Override
    public List<Entity> findCanonicalEntitiesByName(String canonicalName) {
      return entities.getOrDefault(canonicalName, List.of());
    }

    @Override
    public Relation saveRelation(
        Relation relation, String wikiId, long wikiVersion, String claimId) {
      writes++;
      return relation;
    }
  }

  private static final class FakeGraph implements Ports.GraphStore {
    final List<Entity> entities = new ArrayList<>();
    final List<Relation> relations = new ArrayList<>();
    List<String> inputs = List.of();

    @Override
    public void publish(
        List<Entity> entities, List<Relation> relations, List<String> inputVersions) {
      inputs = List.copyOf(inputVersions);
      Ports.GraphStore.super.publish(entities, relations, inputVersions);
    }

    @Override
    public void upsertEntities(List<Entity> values) {
      entities.addAll(values);
    }

    @Override
    public void upsertRelations(List<Relation> values) {
      relations.addAll(values);
    }

    @Override
    public List<Candidate> search(String query, SearchFilters filters, int limit) {
      return List.of();
    }

    @Override
    public void deactivateVersion(String versionId) {}
  }
}
