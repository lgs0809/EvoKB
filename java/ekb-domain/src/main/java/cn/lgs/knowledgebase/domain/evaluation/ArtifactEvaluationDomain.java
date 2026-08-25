package cn.lgs.knowledgebase.domain.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic artifact-quality evaluation. The evaluator itself never calls an LLM. */
public final class ArtifactEvaluationDomain {
  private ArtifactEvaluationDomain() {}

  public record TextExpectation(String text, List<String> alternatives) {
    public TextExpectation {
      alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
  }

  public record RelationExpectation(
      String source,
      String relationType,
      String target,
      List<String> sourceAliases,
      List<String> relationTypeAliases,
      List<String> targetAliases) {
    public RelationExpectation {
      sourceAliases = sourceAliases == null ? List.of() : List.copyOf(sourceAliases);
      relationTypeAliases =
          relationTypeAliases == null ? List.of() : List.copyOf(relationTypeAliases);
      targetAliases = targetAliases == null ? List.of() : List.copyOf(targetAliases);
    }
  }

  public record EntityMappingExpectation(
      String surfaceForm,
      String entityType,
      String canonicalName,
      List<String> surfaceAliases,
      List<String> canonicalAliases) {
    public EntityMappingExpectation {
      surfaceAliases = surfaceAliases == null ? List.of() : List.copyOf(surfaceAliases);
      canonicalAliases = canonicalAliases == null ? List.of() : List.copyOf(canonicalAliases);
    }
  }

  public record ArtifactGoldenCase(
      String id,
      List<String> versionIds,
      List<TextExpectation> expectedSummaryFacts,
      List<RelationExpectation> expectedRelations,
      List<EntityMappingExpectation> expectedEntityMappings,
      List<TextExpectation> expectedWikiClaims) {
    public ArtifactGoldenCase {
      versionIds = versionIds == null ? List.of() : List.copyOf(versionIds);
      expectedSummaryFacts =
          expectedSummaryFacts == null ? List.of() : List.copyOf(expectedSummaryFacts);
      expectedRelations = expectedRelations == null ? List.of() : List.copyOf(expectedRelations);
      expectedEntityMappings =
          expectedEntityMappings == null ? List.of() : List.copyOf(expectedEntityMappings);
      expectedWikiClaims = expectedWikiClaims == null ? List.of() : List.copyOf(expectedWikiClaims);
    }
  }

  public record RelationActual(String source, String relationType, String target) {}

  public record EntityMappingActual(String surfaceForm, String entityType, String canonicalName) {}

  public record WikiClaimActual(String text, boolean grounded) {}

  public record ActualArtifacts(
      List<String> summaryFacts,
      List<RelationActual> relations,
      List<EntityMappingActual> entityMappings,
      List<WikiClaimActual> wikiClaims) {
    public ActualArtifacts {
      summaryFacts = summaryFacts == null ? List.of() : List.copyOf(summaryFacts);
      relations = relations == null ? List.of() : List.copyOf(relations);
      entityMappings = entityMappings == null ? List.of() : List.copyOf(entityMappings);
      wikiClaims = wikiClaims == null ? List.of() : List.copyOf(wikiClaims);
    }
  }

  public record PrecisionRecall(
      boolean available, Double precision, Double recall, int matched, int actual, int expected) {}

  public record Accuracy(boolean available, Double accuracy, int matched, int expected) {}

  public record Rate(boolean available, Double rate, int passing, int population) {}

  public record ArtifactCaseResult(
      String id,
      List<String> versionIds,
      PrecisionRecall summaryFact,
      PrecisionRecall graphRelation,
      Accuracy entityResolution,
      PrecisionRecall wikiClaim,
      Rate wikiClaimGrounding) {}

  public record ArtifactEvalReport(
      int cases,
      PrecisionRecall summaryFact,
      PrecisionRecall graphRelation,
      Accuracy entityResolution,
      PrecisionRecall wikiClaim,
      Rate wikiClaimGrounding,
      boolean taskSuccessAvailable,
      boolean retrievalRoundsAvailable,
      List<ArtifactCaseResult> results) {}

  public static void validateGoldenCase(ArtifactGoldenCase item) {
    if (item == null) throw new IllegalArgumentException("golden case is required");
    if (blank(item.id())) throw new IllegalArgumentException("id is required");
    if (item.versionIds().isEmpty()) throw new IllegalArgumentException("version_ids is required");
    for (String versionId : item.versionIds())
      if (blank(versionId))
        throw new IllegalArgumentException("version_ids contains an empty value");
    for (int i = 0; i < item.expectedSummaryFacts().size(); i++) {
      if (blank(item.expectedSummaryFacts().get(i).text()))
        throw new IllegalArgumentException("expected_summary_facts[" + i + "].text is required");
    }
    for (int i = 0; i < item.expectedRelations().size(); i++) {
      RelationExpectation expected = item.expectedRelations().get(i);
      if (blank(expected.source()) || blank(expected.relationType()) || blank(expected.target())) {
        throw new IllegalArgumentException(
            "expected_relations[" + i + "] requires source, relation_type, and target");
      }
    }
    for (int i = 0; i < item.expectedEntityMappings().size(); i++) {
      EntityMappingExpectation expected = item.expectedEntityMappings().get(i);
      if (blank(expected.surfaceForm())
          || blank(expected.entityType())
          || blank(expected.canonicalName())) {
        throw new IllegalArgumentException(
            "expected_entity_mappings["
                + i
                + "] requires surface_form, entity_type, and canonical_name");
      }
    }
    for (int i = 0; i < item.expectedWikiClaims().size(); i++) {
      if (blank(item.expectedWikiClaims().get(i).text()))
        throw new IllegalArgumentException("expected_wiki_claims[" + i + "].text is required");
    }
  }

  public static ArtifactCaseResult evaluateCase(ArtifactGoldenCase golden, ActualArtifacts actual) {
    validateGoldenCase(golden);
    actual =
        actual == null ? new ActualArtifacts(List.of(), List.of(), List.of(), List.of()) : actual;

    List<String> summaryActual = uniqueTexts(actual.summaryFacts());
    int summaryMatched = matchTexts(summaryActual, golden.expectedSummaryFacts());
    PrecisionRecall summary =
        precisionRecall(summaryMatched, summaryActual.size(), golden.expectedSummaryFacts().size());

    List<RelationActual> relationActual = uniqueRelations(actual.relations());
    int relationMatched = matchRelations(relationActual, golden.expectedRelations());
    PrecisionRecall graph =
        precisionRecall(relationMatched, relationActual.size(), golden.expectedRelations().size());

    List<EntityMappingActual> mappingActual = uniqueMappings(actual.entityMappings());
    int mappingMatched = matchMappings(mappingActual, golden.expectedEntityMappings());
    Accuracy entity = accuracy(mappingMatched, golden.expectedEntityMappings().size());

    List<String> wikiTexts = new ArrayList<>();
    int grounded = 0;
    for (WikiClaimActual claim : actual.wikiClaims()) {
      wikiTexts.add(claim.text());
      if (claim.grounded()) grounded++;
    }
    List<String> wikiActual = uniqueTexts(wikiTexts);
    int wikiMatched = matchTexts(wikiActual, golden.expectedWikiClaims());
    PrecisionRecall wiki =
        precisionRecall(wikiMatched, wikiActual.size(), golden.expectedWikiClaims().size());
    Rate grounding = rate(grounded, actual.wikiClaims().size());

    return new ArtifactCaseResult(
        golden.id(), golden.versionIds(), summary, graph, entity, wiki, grounding);
  }

  public static ArtifactEvalReport buildReport(List<ArtifactCaseResult> results) {
    results = results == null ? List.of() : List.copyOf(results);
    int summaryMatched = 0, summaryActual = 0, summaryExpected = 0;
    int relationMatched = 0, relationActual = 0, relationExpected = 0;
    int entityMatched = 0, entityExpected = 0;
    int wikiMatched = 0, wikiActual = 0, wikiExpected = 0;
    int groundingPassing = 0, groundingPopulation = 0;
    boolean summaryAvailable = false,
        relationAvailable = false,
        entityAvailable = false,
        wikiAvailable = false,
        groundingAvailable = false;

    for (ArtifactCaseResult result : results) {
      if (result.summaryFact().available()) {
        summaryAvailable = true;
        summaryMatched += result.summaryFact().matched();
        summaryActual += result.summaryFact().actual();
        summaryExpected += result.summaryFact().expected();
      }
      if (result.graphRelation().available()) {
        relationAvailable = true;
        relationMatched += result.graphRelation().matched();
        relationActual += result.graphRelation().actual();
        relationExpected += result.graphRelation().expected();
      }
      if (result.entityResolution().available()) {
        entityAvailable = true;
        entityMatched += result.entityResolution().matched();
        entityExpected += result.entityResolution().expected();
      }
      if (result.wikiClaim().available()) {
        wikiAvailable = true;
        wikiMatched += result.wikiClaim().matched();
        wikiActual += result.wikiClaim().actual();
        wikiExpected += result.wikiClaim().expected();
      }
      if (result.wikiClaimGrounding().available()) {
        groundingAvailable = true;
        groundingPassing += result.wikiClaimGrounding().passing();
        groundingPopulation += result.wikiClaimGrounding().population();
      }
    }

    PrecisionRecall summary =
        summaryAvailable
            ? precisionRecall(summaryMatched, summaryActual, summaryExpected)
            : unavailablePrecisionRecall();
    PrecisionRecall graph =
        relationAvailable
            ? precisionRecall(relationMatched, relationActual, relationExpected)
            : unavailablePrecisionRecall();
    Accuracy entity =
        entityAvailable ? accuracy(entityMatched, entityExpected) : unavailableAccuracy();
    PrecisionRecall wiki =
        wikiAvailable
            ? precisionRecall(wikiMatched, wikiActual, wikiExpected)
            : unavailablePrecisionRecall();
    Rate grounding =
        groundingAvailable ? rate(groundingPassing, groundingPopulation) : unavailableRate();
    return new ArtifactEvalReport(
        results.size(), summary, graph, entity, wiki, grounding, false, false, results);
  }

  private static PrecisionRecall precisionRecall(int matched, int actual, int expected) {
    if (expected <= 0) return new PrecisionRecall(false, null, null, matched, actual, expected);
    double precision = actual > 0 ? (double) matched / actual : 0.0;
    double recall = (double) matched / expected;
    return new PrecisionRecall(true, precision, recall, matched, actual, expected);
  }

  private static Accuracy accuracy(int matched, int expected) {
    return expected <= 0
        ? new Accuracy(false, null, matched, expected)
        : new Accuracy(true, (double) matched / expected, matched, expected);
  }

  private static Rate rate(int passing, int population) {
    return population <= 0
        ? new Rate(false, null, passing, population)
        : new Rate(true, (double) passing / population, passing, population);
  }

  private static PrecisionRecall unavailablePrecisionRecall() {
    return new PrecisionRecall(false, null, null, 0, 0, 0);
  }

  private static Accuracy unavailableAccuracy() {
    return new Accuracy(false, null, 0, 0);
  }

  private static Rate unavailableRate() {
    return new Rate(false, null, 0, 0);
  }

  private static int matchTexts(List<String> actual, List<TextExpectation> expected) {
    boolean[] used = new boolean[expected.size()];
    int matched = 0;
    for (String value : actual) {
      for (int i = 0; i < expected.size(); i++) {
        if (used[i] || !textExpectationMatches(value, expected.get(i))) continue;
        used[i] = true;
        matched++;
        break;
      }
    }
    return matched;
  }

  private static boolean textExpectationMatches(String actual, TextExpectation expected) {
    String normalized = normalize(actual);
    if (normalized.isEmpty()) return false;
    if (normalized.equals(normalize(expected.text()))) return true;
    for (String alias : expected.alternatives())
      if (normalized.equals(normalize(alias))) return true;
    return false;
  }

  private static int matchRelations(
      List<RelationActual> actual, List<RelationExpectation> expected) {
    boolean[] used = new boolean[expected.size()];
    int matched = 0;
    for (RelationActual value : actual) {
      for (int i = 0; i < expected.size(); i++) {
        if (used[i] || !relationMatches(value, expected.get(i))) continue;
        used[i] = true;
        matched++;
        break;
      }
    }
    return matched;
  }

  private static boolean relationMatches(RelationActual actual, RelationExpectation expected) {
    return matchesOne(actual.source(), expected.source(), expected.sourceAliases())
        && matchesOne(
            actual.relationType(), expected.relationType(), expected.relationTypeAliases())
        && matchesOne(actual.target(), expected.target(), expected.targetAliases());
  }

  private static int matchMappings(
      List<EntityMappingActual> actual, List<EntityMappingExpectation> expected) {
    boolean[] used = new boolean[expected.size()];
    int matched = 0;
    for (EntityMappingActual value : actual) {
      for (int i = 0; i < expected.size(); i++) {
        if (used[i] || !mappingMatches(value, expected.get(i))) continue;
        used[i] = true;
        matched++;
        break;
      }
    }
    return matched;
  }

  private static boolean mappingMatches(
      EntityMappingActual actual, EntityMappingExpectation expected) {
    return matchesOne(actual.surfaceForm(), expected.surfaceForm(), expected.surfaceAliases())
        && normalize(actual.entityType()).equals(normalize(expected.entityType()))
        && matchesOne(
            actual.canonicalName(), expected.canonicalName(), expected.canonicalAliases());
  }

  private static boolean matchesOne(String actual, String primary, List<String> aliases) {
    String normalized = normalize(actual);
    if (normalized.isEmpty()) return false;
    if (normalized.equals(normalize(primary))) return true;
    for (String alias : aliases == null ? List.<String>of() : aliases)
      if (normalized.equals(normalize(alias))) return true;
    return false;
  }

  private static List<String> uniqueTexts(List<String> values) {
    Map<String, String> unique = new LinkedHashMap<>();
    for (String value : values == null ? List.<String>of() : values) {
      String key = normalize(value);
      if (!key.isEmpty()) unique.putIfAbsent(key, value);
    }
    return List.copyOf(unique.values());
  }

  private static List<RelationActual> uniqueRelations(List<RelationActual> values) {
    Map<String, RelationActual> unique = new LinkedHashMap<>();
    for (RelationActual value : values == null ? List.<RelationActual>of() : values) {
      String key =
          normalize(value.source())
              + "\u001f"
              + normalize(value.relationType())
              + "\u001f"
              + normalize(value.target());
      unique.putIfAbsent(key, value);
    }
    return List.copyOf(unique.values());
  }

  private static List<EntityMappingActual> uniqueMappings(List<EntityMappingActual> values) {
    Map<String, EntityMappingActual> unique = new LinkedHashMap<>();
    for (EntityMappingActual value : values == null ? List.<EntityMappingActual>of() : values) {
      String key =
          normalize(value.surfaceForm())
              + "\u001f"
              + normalize(value.entityType())
              + "\u001f"
              + normalize(value.canonicalName());
      unique.putIfAbsent(key, value);
    }
    return List.copyOf(unique.values());
  }

  static String normalize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
