package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ConflictStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.RelationLifecycle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically projects grounded Wiki claims with two wikilinks into the canonical relation
 * graph.
 */
public final class WikiKeywordProjectionService {
  private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\[\\]\\r\\n]{1,200})]]");
  private static final List<PatternRule> RULES = rules();
  private static final Set<String> NEGATIONS =
      Set.of("not", "no", "never", "不是", "未", "没有", "不存在", "不属于", "不控制", "非");

  public interface Repository {
    List<WikiKeywordClaim> loadClaims(String wikiId, long wikiVersion);

    List<Entity> findCanonicalEntitiesByName(String canonicalName);

    Relation saveRelation(Relation relation, String wikiId, long wikiVersion, String claimId);
  }

  public record WikiKeywordClaim(
      String claimId, String wikiId, long wikiVersion, String claimText, List<String> evidenceIds) {
    public WikiKeywordClaim {
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  public record ProjectionResult(
      String wikiId,
      long wikiVersion,
      int claimsExamined,
      List<Relation> relations,
      Map<String, String> skippedClaims) {}

  private final Repository repository;
  private final GraphStore graph;
  private final double confidence;

  public WikiKeywordProjectionService(Repository repository, GraphStore graph) {
    this(repository, graph, 0.85);
  }

  WikiKeywordProjectionService(Repository repository, GraphStore graph, double confidence) {
    if (repository == null)
      throw new IllegalArgumentException("wiki keyword repository is required");
    if (graph == null) throw new IllegalArgumentException("graph store is required");
    this.repository = repository;
    this.graph = graph;
    this.confidence = confidence > 0 && confidence <= 1 ? confidence : 0.85;
  }

  public ProjectionResult project(String wikiId, long wikiVersion) {
    String normalizedWikiId = trim(wikiId);
    if (normalizedWikiId.isEmpty() || wikiVersion <= 0)
      throw new IllegalArgumentException("wiki ID and version are required");
    List<WikiKeywordClaim> claims = repository.loadClaims(normalizedWikiId, wikiVersion);
    Map<String, String> skipped = new LinkedHashMap<>();
    Map<String, Entity> entities = new LinkedHashMap<>();
    Map<String, Relation> relations = new LinkedHashMap<>();

    for (WikiKeywordClaim claim : claims) {
      Parsed parsed = parse(claim.claimText());
      if (parsed.reason() != null) {
        skipped.put(claim.claimId(), parsed.reason());
        continue;
      }
      List<Entity> sources = repository.findCanonicalEntitiesByName(parsed.sourceName());
      List<Entity> targets = repository.findCanonicalEntitiesByName(parsed.targetName());
      if (sources.size() != 1) {
        skipped.put(claim.claimId(), "source entity is not uniquely resolved");
        continue;
      }
      if (targets.size() != 1) {
        skipped.put(claim.claimId(), "target entity is not uniquely resolved");
        continue;
      }
      Entity source = sources.get(0), target = targets.get(0);
      if (!GraphCompilationService.relationAllowed(
          source.entityType(), parsed.relationType(), target.entityType())) {
        skipped.put(claim.claimId(), "schema rejected relation");
        continue;
      }
      List<String> evidenceIds =
          claim.evidenceIds().stream()
              .map(WikiKeywordProjectionService::trim)
              .filter(v -> !v.isEmpty())
              .distinct()
              .toList();
      if (evidenceIds.isEmpty()) {
        skipped.put(claim.claimId(), "claim has no active raw evidence");
        continue;
      }
      String relationId =
          GraphCompilationService.stableId(
              "relation", source.canonicalId(), parsed.relationType(), target.canonicalId(), "");
      Relation relation =
          new Relation(
              relationId,
              source.canonicalId(),
              parsed.relationType(),
              target.canonicalId(),
              null,
              null,
              ConflictStatus.NONE,
              RelationLifecycle.ACTIVE,
              evidenceIds,
              confidence,
              Map.of(
                  "wiki_id",
                  normalizedWikiId,
                  "wiki_version",
                  wikiVersion,
                  "claim_id",
                  claim.claimId(),
                  "origin",
                  "wiki_keyword"));
      Relation stored =
          repository.saveRelation(relation, normalizedWikiId, wikiVersion, claim.claimId());
      entities.put(source.canonicalId(), source);
      entities.put(target.canonicalId(), target);
      relations.put(stored.id(), stored);
    }

    List<Entity> entityList = new ArrayList<>(entities.values());
    entityList.sort(Comparator.comparing(Entity::canonicalId));
    List<Relation> relationList = new ArrayList<>(relations.values());
    relationList.sort(Comparator.comparing(Relation::id));
    if (!entityList.isEmpty()) graph.upsertEntities(entityList);
    if (!relationList.isEmpty()) graph.upsertRelations(relationList);
    return new ProjectionResult(
        normalizedWikiId,
        wikiVersion,
        claims.size(),
        List.copyOf(relationList),
        Map.copyOf(skipped));
  }

  static Parsed parse(String text) {
    String value = trim(text);
    if (value.isEmpty()) return new Parsed("", "", "", "empty claim");
    String normalized = normalizePhrase(value);
    for (String negation : NEGATIONS) {
      if (normalized.contains(normalizePhrase(negation)))
        return new Parsed("", "", "", "negated claim");
    }
    Matcher matcher = WIKILINK.matcher(value);
    List<Link> links = new ArrayList<>(2);
    while (matcher.find())
      links.add(new Link(matcher.start(), matcher.end(), wikiName(matcher.group(1))));
    if (links.size() != 2)
      return new Parsed("", "", "", "claim must contain exactly two wikilinks");
    Link first = links.get(0), second = links.get(1);
    String between = normalizePhrase(value.substring(first.end(), second.start()));
    for (PatternRule rule : RULES) {
      if (!between.contains(rule.phrase())) continue;
      return rule.reverse()
          ? new Parsed(second.name(), first.name(), rule.relationType(), null)
          : new Parsed(first.name(), second.name(), rule.relationType(), null);
    }
    return new Parsed("", "", "", "no supported relation keyword");
  }

  private static List<PatternRule> rules() {
    List<PatternRule> rules = new ArrayList<>();
    add(rules, "is actual controller of", "ACTUAL_CONTROLLER_OF", false);
    add(rules, "actual controller of", "ACTUAL_CONTROLLER_OF", false);
    add(rules, "is controlled by", "CONTROLS", true);
    add(rules, "controls", "CONTROLS", false);
    add(rules, "is shareholder of", "SHAREHOLDER_OF", false);
    add(rules, "is subsidiary of", "SUBSIDIARY_OF", false);
    add(rules, "is parent of", "PARENT_OF", false);
    add(rules, "is director of", "DIRECTOR_OF", false);
    add(rules, "is legal representative of", "LEGAL_REPRESENTATIVE_OF", false);
    add(rules, "is party to", "PARTY_TO_CONTRACT", false);
    add(rules, "signed with", "SIGNS_WITH", false);
    add(rules, "signs with", "SIGNS_WITH", false);
    add(rules, "guarantees", "GUARANTEES", false);
    add(rules, "borrows from", "BORROWS_FROM", false);
    add(rules, "invests in", "INVESTS_IN", false);
    add(rules, "is involved in", "INVOLVED_IN_CASE", false);
    add(rules, "owns intellectual property", "OWNS_IP", false);
    add(rules, "holds license", "HOLDS_LICENSE", false);
    add(rules, "participates in", "PARTICIPATES_IN_PROJECT", false);
    add(rules, "approves", "APPROVES", false);
    add(rules, "supplies to", "SUPPLIES_TO", false);
    add(rules, "is customer of", "CUSTOMER_OF", false);
    add(rules, "is related to", "RELATED_TO", false);
    add(rules, "实际控制", "ACTUAL_CONTROLLER_OF", false);
    add(rules, "控制", "CONTROLS", false);
    add(rules, "股东", "SHAREHOLDER_OF", false);
    add(rules, "子公司", "SUBSIDIARY_OF", false);
    add(rules, "母公司", "PARENT_OF", false);
    add(rules, "董事", "DIRECTOR_OF", false);
    add(rules, "法定代表人", "LEGAL_REPRESENTATIVE_OF", false);
    add(rules, "签署", "SIGNS_WITH", false);
    add(rules, "签订", "SIGNS_WITH", false);
    add(rules, "担保", "GUARANTEES", false);
    add(rules, "投资", "INVESTS_IN", false);
    add(rules, "供应", "SUPPLIES_TO", false);
    return List.copyOf(rules);
  }

  private static void add(
      List<PatternRule> rules, String phrase, String relationType, boolean reverse) {
    rules.add(new PatternRule(normalizePhrase(phrase), relationType, reverse));
  }

  private static String wikiName(String value) {
    int pipe = value.indexOf('|');
    return trim(pipe >= 0 ? value.substring(0, pipe) : value);
  }

  private static String normalizePhrase(String value) {
    return trim(value).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", " ").trim();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private record PatternRule(String phrase, String relationType, boolean reverse) {}

  private record Link(int start, int end, String name) {}

  record Parsed(String sourceName, String targetName, String relationType, String reason) {}
}
