package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sage-Wiki-aligned shared concept pass between grounded summaries and the Graph/Wiki projections.
 * Concepts are lightweight semantic anchors, not final canonical graph entities.
 */
public final class ConceptCompilationService {
  private static final Set<String> TYPE_HINTS =
      Set.of(
          "COMPANY",
          "PERSON",
          "COMMITTEE",
          "ORGANIZATION",
          "CONTRACT",
          "LEGAL_CASE",
          "PROJECT",
          "POLICY",
          "EVENT",
          "LOCATION",
          "PRODUCT",
          "LICENSE",
          "INTELLECTUAL_PROPERTY",
          "TOPIC",
          "OTHER");

  private final CompilationRepository repository;
  private final KnowledgeModel model;
  private final Clock clock;

  public ConceptCompilationService(CompilationRepository repository, KnowledgeModel model) {
    this(repository, model, Clock.systemUTC());
  }

  ConceptCompilationService(CompilationRepository repository, KnowledgeModel model, Clock clock) {
    this.repository = require(repository, "compilation repository");
    this.model = require(model, "knowledge model");
    this.clock = require(clock, "clock");
  }

  public CompileResult compileSummary(String summaryId) {
    SummaryRecord summary =
        repository
            .findSummary(trim(summaryId))
            .orElseThrow(() -> new IllegalStateException("summary not found: " + summaryId));
    if (!summary.activeAndGrounded()) {
      throw new IllegalStateException(
          "summary is not ACTIVE with grounding+coverage PASS: " + summaryId);
    }

    Map<String, SummaryFact> facts = groundedFacts(summary.facts());
    if (facts.isEmpty())
      throw new IllegalStateException("concept compiler: no grounded summary facts");

    ConceptProposalSet extracted =
        model.generateStructured(
            ModelRole.LARGE,
            ModelTask.CONCEPT_EXTRACT,
            conceptPrompt(),
            renderFacts(facts.values()),
            ConceptProposalSet.class);
    List<ConceptRecord> concepts = normalize(summary, facts, extracted);
    if (concepts.isEmpty()) {
      throw new IllegalStateException("concept compiler produced no supported concepts");
    }
    repository.replaceConceptsForSummary(summary.id(), concepts);
    return new CompileResult(
        summary.id(), summary.versionId(), concepts, model.identity(ModelRole.LARGE));
  }

  private List<ConceptRecord> normalize(
      SummaryRecord summary, Map<String, SummaryFact> facts, ConceptProposalSet extracted) {
    if (extracted == null || extracted.concepts() == null) {
      throw new IllegalStateException("concept extraction returned empty output");
    }
    Map<String, ConceptRecord> unique = new LinkedHashMap<>();
    for (ConceptProposal raw : extracted.concepts()) {
      if (raw == null) continue;
      String label = trim(raw.preferredLabel());
      if (label.isEmpty()) continue;
      List<String> factIds =
          raw.supportingFactIds() == null
              ? List.of()
              : raw.supportingFactIds().stream()
                  .map(ConceptCompilationService::trim)
                  .filter(v -> !v.isEmpty())
                  .distinct()
                  .toList();
      if (factIds.isEmpty()) {
        throw new IllegalStateException("concept requires at least one supporting fact: " + label);
      }
      for (String factId : factIds) {
        SummaryFact fact = facts.get(factId);
        if (fact == null) {
          throw new IllegalStateException(
              "concept " + label + " references unknown or ungrounded fact " + factId);
        }
        if (fact.evidenceIds().isEmpty()) {
          throw new IllegalStateException(
              "concept " + label + " references fact without raw evidence " + factId);
        }
      }
      if (raw.confidence() < 0 || raw.confidence() > 1) {
        throw new IllegalStateException("concept confidence must be within [0,1]");
      }

      String typeHint = normalizeTypeHint(raw.conceptTypeHint());
      List<String> aliases = normalizeAliases(label, raw.aliases());
      String dedupe = typeHint + "\u0000" + GraphCompilationService.canonicalKey(label);
      String conceptId =
          GraphCompilationService.stableId(
              "concept",
              summary.versionId(),
              summary.id(),
              typeHint,
              GraphCompilationService.canonicalKey(label));
      ConceptRecord concept =
          new ConceptRecord(
              conceptId,
              summary.id(),
              summary.versionId(),
              label,
              aliases,
              typeHint,
              trim(raw.description()),
              factIds,
              "ACTIVE",
              model.identity(ModelRole.LARGE),
              clock.instant());
      ConceptRecord previous = unique.putIfAbsent(dedupe, concept);
      if (previous != null && !previous.factIds().equals(concept.factIds())) {
        LinkedHashSet<String> mergedFacts = new LinkedHashSet<>(previous.factIds());
        mergedFacts.addAll(concept.factIds());
        LinkedHashSet<String> mergedAliases = new LinkedHashSet<>(previous.aliases());
        mergedAliases.addAll(concept.aliases());
        unique.put(
            dedupe,
            new ConceptRecord(
                previous.id(),
                previous.summaryId(),
                previous.versionId(),
                previous.preferredLabel(),
                List.copyOf(mergedAliases),
                previous.conceptTypeHint(),
                previous.description().isBlank() ? concept.description() : previous.description(),
                List.copyOf(mergedFacts),
                previous.lifecycle(),
                previous.model(),
                previous.createdAt()));
      }
    }
    return List.copyOf(unique.values());
  }

  private static String conceptPrompt() {
    return "你是企业知识 Concept Extractor。输入全部是已经通过 Raw Evidence Grounding 的事实。"
        + "请提取后续 Graph 与 Wiki 可以共享的稳定概念/实体锚点，而不是给每个普通名词都造 Concept。"
        + "每个 concept 必须给 preferred_label、aliases、concept_type_hint、description、supporting_fact_ids、confidence。"
        + "supporting_fact_ids 必须严格来自输入事实；不得使用常识补充。"
        + "同一真实对象的别名应放在同一 concept 的 aliases；名字相似但语义不同的对象必须分开，例如审计委员会与投资与决策委员会不得合并。"
        + "concept_type_hint 只使用 COMPANY/PERSON/COMMITTEE/ORGANIZATION/CONTRACT/LEGAL_CASE/PROJECT/POLICY/EVENT/LOCATION/PRODUCT/LICENSE/INTELLECTUAL_PROPERTY/TOPIC/OTHER；"
        + "它只是轻量语义类型，不代表最终 Graph ontology 判定。";
  }

  private static String renderFacts(java.util.Collection<SummaryFact> facts) {
    StringBuilder out = new StringBuilder("GROUNDED FACTS:\n");
    for (SummaryFact fact : facts) {
      out.append("<fact id=\"")
          .append(fact.id())
          .append("\" section=\"")
          .append(fact.section())
          .append("\">\n")
          .append(fact.factText())
          .append("\n</fact>\n");
    }
    return out.toString();
  }

  private static Map<String, SummaryFact> groundedFacts(List<SummaryFact> input) {
    Map<String, SummaryFact> facts = new LinkedHashMap<>();
    for (SummaryFact fact : input == null ? List.<SummaryFact>of() : input) {
      if (fact != null
          && "PASS".equals(fact.groundingStatus())
          && !trim(fact.id()).isEmpty()
          && !fact.evidenceIds().isEmpty()) {
        facts.put(fact.id(), fact);
      }
    }
    return facts;
  }

  private static List<String> normalizeAliases(String label, List<String> input) {
    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    String labelKey = GraphCompilationService.canonicalKey(label);
    for (String raw : input == null ? List.<String>of() : input) {
      String alias = trim(raw);
      if (!alias.isEmpty() && !GraphCompilationService.canonicalKey(alias).equals(labelKey)) {
        aliases.add(alias);
      }
    }
    return List.copyOf(aliases);
  }

  private static String normalizeTypeHint(String value) {
    String type = trim(value).toUpperCase(Locale.ROOT);
    return TYPE_HINTS.contains(type) ? type : "OTHER";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  public record ConceptProposal(
      String preferredLabel,
      List<String> aliases,
      String conceptTypeHint,
      String description,
      List<String> supportingFactIds,
      double confidence) {}

  public record ConceptProposalSet(List<ConceptProposal> concepts) {}

  public record CompileResult(
      String summaryId, String versionId, List<ConceptRecord> concepts, String extractor) {
    public CompileResult {
      concepts = concepts == null ? List.of() : List.copyOf(concepts);
    }
  }
}
