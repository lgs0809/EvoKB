package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.ConceptRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
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
 * Shared concept extraction between grounded summaries and Graph/Wiki projections. Concepts are
 * lightweight semantic anchors, not final canonical graph entities.
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
  private final EmbeddingPort embedding;
  private final DerivedSearchIndex derived;
  private final KnowledgeEntryCatalogue entries;

  public ConceptCompilationService(CompilationRepository repository, KnowledgeModel model) {
    this(repository, model, Clock.systemUTC());
  }

  public ConceptCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      EmbeddingPort embedding,
      DerivedSearchIndex derived) {
    this(
        repository,
        model,
        Clock.systemUTC(),
        require(embedding, "embedding port"),
        require(derived, "derived index"),
        null);
  }

  public ConceptCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      EmbeddingPort embedding,
      DerivedSearchIndex derived,
      KnowledgeEntryCatalogue entries) {
    this(
        repository,
        model,
        Clock.systemUTC(),
        require(embedding, "embedding port"),
        require(derived, "derived index"),
        require(entries, "entry catalogue"));
  }

  ConceptCompilationService(CompilationRepository repository, KnowledgeModel model, Clock clock) {
    this(repository, model, clock, null, null, null);
  }

  private ConceptCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      Clock clock,
      EmbeddingPort embedding,
      DerivedSearchIndex derived,
      KnowledgeEntryCatalogue entries) {
    this.repository = require(repository, "compilation repository");
    this.model = require(model, "knowledge model");
    this.clock = require(clock, "clock");
    this.embedding = embedding;
    this.derived = derived;
    this.entries = entries;
  }

  public CompileResult compileSummary(String summaryId) {
    return compileSummary(summaryId, repository.newPublication());
  }

  public CompileResult compileSummary(String summaryId, CompilationPublication publication) {
    return compileSummary(summaryId, publication, "");
  }

  public CompileResult compileSummary(
      String summaryId, CompilationPublication publication, String previousRejection) {
    SummaryRecord summary =
        repository
            .findSummary(trim(summaryId))
            .orElseThrow(() -> new IllegalStateException("summary not found: " + summaryId));
    if (!summary.activeAndGrounded()) {
      throw new IllegalStateException(
          "summary is not ACTIVE with grounding+coverage PASS: " + summaryId);
    }
    var context = repository.derivedContextForSummaries(List.of(summary.id()));
    List<KnowledgeEntryCatalogue.Candidate> candidates =
        entries == null ? List.of() : entries.candidates(summary.id(), 24);
    var actualInputs = new java.util.TreeSet<>(context.versionIds());
    candidates.stream()
        .flatMap(candidate -> candidate.versionIds().stream())
        .forEach(actualInputs::add);
    publication.captureInputs(List.copyOf(actualInputs));

    Map<String, SummaryFact> facts = groundedFacts(summary.facts());
    if (facts.isEmpty())
      throw new IllegalStateException("concept compiler: no grounded summary facts");
    var references = new GroundedFactReferences(facts);

    publication.check();
    ConceptProposalSet extracted =
        model.generateStructured(
            ModelRole.LARGE,
            ModelTask.CONCEPT_EXTRACT,
            conceptPrompt(),
            "GROUNDED FACTS:\n"
                + references.renderFacts()
                + candidatePrompt(candidates)
                + (trim(previousRejection).isEmpty()
                    ? ""
                    : "\nPREVIOUS ATTEMPT WAS REJECTED (correct the proposal; grounding rules stay unchanged):\n"
                        + xml(previousRejection)),
            ConceptProposalSet.class);
    List<ConceptRecord> concepts = normalize(summary, facts, references, extracted);
    if (concepts.isEmpty()) {
      throw new IllegalStateException("concept compiler produced no supported concepts");
    }
    List<KnowledgeEntryCatalogue.Publication> entryPublications =
        entries == null
            ? List.of()
            : prepareEntries(summary, concepts, extracted, facts, references, candidates);
    List<Runnable> indexPublications =
        derived == null
            ? List.of()
            : entries == null
                ? prepareProjections(summary, concepts, facts, context)
                : prepareEntryProjections(entryPublications, publication);
    publication.publish(
        () -> {
          repository.replaceConceptsForSummary(summary.id(), concepts);
          if (entries != null) entryPublications.forEach(entries::publish);
          indexPublications.forEach(Runnable::run);
        });
    return new CompileResult(
        summary.id(), summary.versionId(), concepts, model.identity(ModelRole.LARGE));
  }

  private List<KnowledgeEntryCatalogue.Publication> prepareEntries(
      SummaryRecord summary,
      List<ConceptRecord> concepts,
      ConceptProposalSet proposal,
      Map<String, SummaryFact> facts,
      GroundedFactReferences references,
      List<KnowledgeEntryCatalogue.Candidate> candidates) {
    var options = new LinkedHashMap<String, ConceptProposal>();
    for (var raw : proposal.concepts())
      if (raw != null) {
        String key =
            normalizeTypeHint(raw.conceptTypeHint())
                + "\u0000"
                + GraphCompilationService.canonicalKey(raw.preferredLabel());
        var prior = options.putIfAbsent(key, raw);
        if (prior != null
            && (!trim(prior.matchedEntryId()).equals(trim(raw.matchedEntryId()))
                || !java.util.Objects.equals(prior.scopeAnchors(), raw.scopeAnchors())))
          throw new IllegalStateException(
              "duplicate concept proposals disagree on stable identity");
      }
    var results = new java.util.ArrayList<KnowledgeEntryCatalogue.Publication>();
    for (var concept : concepts) {
      var raw =
          options.get(
              concept.conceptTypeHint()
                  + "\u0000"
                  + GraphCompilationService.canonicalKey(concept.preferredLabel()));
      var anchors =
          (raw.scopeAnchors() == null ? List.<GraphScopeIdentity.Anchor>of() : raw.scopeAnchors())
              .stream()
                  .map(
                      a ->
                          new GraphScopeIdentity.Anchor(
                              a.type(),
                              a.value(),
                              a.supportingFactIds().stream().map(references::canonicalId).toList()))
                  .toList();
      final GraphScopeIdentity.Scope scope;
      try {
        scope = GraphScopeIdentity.scope(summary.documentId(), anchors, facts);
      } catch (IllegalArgumentException unsupportedAnchor) {
        throw new ModelOutputRejectedException(unsupportedAnchor.getMessage(), unsupportedAnchor);
      }
      results.add(entries.prepare(concept, scope, raw.matchedEntryId(), candidates));
    }
    if (results.stream().map(p -> p.entry().id()).distinct().count() != results.size())
      throw new IllegalStateException(
          "multiple concept outputs target one entry; combine its aliases in one proposal");
    return List.copyOf(results);
  }

  private List<Runnable> prepareEntryProjections(
      List<KnowledgeEntryCatalogue.Publication> plans, CompilationPublication gate) {
    var publications = new java.util.ArrayList<Runnable>();
    for (var plan : plans) {
      var entry = plan.entry();
      // Updating an already promoted entry must rebuild its Wiki; never replace it with a second
      // Concept search result or silently make its old page current again.
      if (entry.stage().equals("WIKI")) continue;
      var summaries = repository.loadSummaries(entry.summaryIds());
      var evidence =
          entry.sources().stream()
              .flatMap(
                  source ->
                      summaries.stream()
                          .filter(s -> s.id().equals(source.summaryId()))
                          .flatMap(s -> s.facts().stream())
                          .filter(f -> source.factIds().contains(f.id()))
                          .flatMap(f -> f.evidenceIds().stream()))
              .distinct()
              .sorted()
              .toList();
      String text =
          entry.label()
              + "\n别名："
              + String.join("、", entry.aliases())
              + "\n类型："
              + entry.type()
              + "\n"
              + entry.description();
      gate.check();
      var vector = embedding.embed(List.of(text));
      if (vector.size() != 1) throw new IllegalStateException("entry embedding count mismatch");
      publications.add(
          derived.prepareConcept(
              entry.id(),
              text,
              evidence,
              repository.derivedContextForSummaries(entry.inputSummaryIds()),
              vector.get(0)));
    }
    return List.copyOf(publications);
  }

  private static String candidatePrompt(List<KnowledgeEntryCatalogue.Candidate> candidates) {
    if (candidates.isEmpty()) return "\nEXISTING ENTRIES: none. Do not invent a matchedEntryId.";
    var prompt =
        new StringBuilder(
            "\nEXISTING ENTRY CANDIDATES (identity metadata, not new factual evidence):\n");
    for (var entry : candidates)
      prompt
          .append("<entry id=\"")
          .append(entry.id())
          .append("\" type=\"")
          .append(entry.snapshot().type())
          .append("\" scope=\"")
          .append(entry.snapshot().scopeKey())
          .append("\"><label>")
          .append(xml(entry.label()))
          .append("</label><verified-scope>")
          .append(xml(entry.snapshot().scopeAttributes().toString()))
          .append("</verified-scope><aliases>")
          .append(xml(String.join("、", entry.aliases())))
          .append("</aliases><description>")
          .append(xml(entry.description()))
          .append("</description></entry>\n");
    return prompt.toString();
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /**
   * Repair a missing search projection from already grounded facts without rerunning extraction.
   */
  public List<ConceptRecord> rebuildProjections(String summaryId) {
    return rebuildProjections(summaryId, repository.newPublication());
  }

  public List<ConceptRecord> rebuildProjections(
      String summaryId, CompilationPublication publication) {
    if (derived == null)
      throw new IllegalStateException("concept search publishing is unavailable");
    var summary =
        repository
            .findSummary(trim(summaryId))
            .orElseThrow(() -> new IllegalStateException("summary not found"));
    if (!summary.activeAndGrounded())
      throw new IllegalStateException("summary is not active and grounded");
    var concepts = repository.loadActiveConceptsForSummary(summary.id());
    if (concepts.isEmpty()) throw new IllegalStateException("no grounded concepts to reindex");
    var facts = groundedFacts(summary.facts());
    var context = repository.derivedContextForSummaries(List.of(summary.id()));
    var currentEntries =
        entries == null
            ? List.<KnowledgeEntryCatalogue.Entry>of()
            : entries.conceptEntriesForSummary(summary.id());
    if (entries != null && currentEntries.isEmpty())
      throw new IllegalStateException("no current Concept-stage entries to reindex");
    var inputs = new java.util.TreeSet<>(context.versionIds());
    currentEntries.forEach(entry -> inputs.addAll(entry.versionIds()));
    publication.captureInputs(List.copyOf(inputs));
    for (var concept : concepts) {
      if (!summary.id().equals(concept.summaryId())
          || !summary.versionId().equals(concept.versionId())
          || concept.factIds().isEmpty()
          || concept.factIds().stream()
              .anyMatch(id -> !facts.containsKey(id) || facts.get(id).evidenceIds().isEmpty()))
        throw new IllegalStateException("stored concept is missing its grounded supporting facts");
    }
    var publications =
        entries == null
            ? prepareProjections(summary, concepts, facts, context)
            : prepareEntryProjections(
                currentEntries.stream()
                    .map(entry -> new KnowledgeEntryCatalogue.Publication(entry, entry.revision()))
                    .toList(),
                publication);
    publication.publish(() -> publications.forEach(Runnable::run));
    return concepts;
  }

  private List<Runnable> prepareProjections(
      SummaryRecord summary,
      List<ConceptRecord> concepts,
      Map<String, SummaryFact> facts,
      cn.lgs.knowledgebase.application.port.Ports.DerivedContext context) {
    var projections = concepts.stream().map(ConceptCompilationService::projection).toList();
    var vectors = embedding.embed(projections);
    if (vectors == null || vectors.size() != concepts.size())
      throw new IllegalStateException("concept projection embedding count mismatch");
    var publications = new java.util.ArrayList<Runnable>();
    for (int i = 0; i < concepts.size(); i++) {
      var concept = concepts.get(i);
      var proofs =
          concept.factIds().stream()
              .flatMap(id -> facts.get(id).evidenceIds().stream())
              .distinct()
              .toList();
      publications.add(
          derived.prepareConcept(
              concept.id(), projections.get(i), proofs, context, vectors.get(i)));
    }
    return List.copyOf(publications);
  }

  private static String projection(ConceptRecord concept) {
    return concept.preferredLabel()
        + "\n别名："
        + String.join("、", concept.aliases())
        + "\n类型："
        + concept.conceptTypeHint()
        + "\n"
        + concept.description();
  }

  private List<ConceptRecord> normalize(
      SummaryRecord summary,
      Map<String, SummaryFact> facts,
      GroundedFactReferences references,
      ConceptProposalSet extracted) {
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
                  .map(references::canonicalId)
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
      if (!Double.isFinite(raw.confidence()) || raw.confidence() < 0 || raw.confidence() > 1) {
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
        + "supporting_fact_ids 必须逐字复制输入 <fact id=...> 的短引用键（例如 F001）；不要输出 UUID 或自行编造引用，不得使用常识补充。"
        + "同一真实对象的别名应放在同一 concept 的 aliases；名字相似但语义不同的对象必须分开，例如审计委员会与投资与决策委员会不得合并。"
        + "concept_type_hint 只使用 COMPANY/PERSON/COMMITTEE/ORGANIZATION/CONTRACT/LEGAL_CASE/PROJECT/POLICY/EVENT/LOCATION/PRODUCT/LICENSE/INTELLECTUAL_PROPERTY/TOPIC/OTHER；"
        + "它只是轻量语义类型，不代表最终 Graph ontology 判定。"
        + "提供 scopeAnchors：type、value、supportingFactIds；只用当前事实逐字支持的 COMPANY/ORGANIZATION/PERSON/CONTRACT/PROJECT/LEGAL_CASE/LICENSE 锚点确定业务范围。"
        + "每个 scopeAnchor 的 supportingFactIds 仅列出逐字出现其 value 的事实；不要把该概念的全部 supporting_fact_ids 直接复制给每个锚点。公司名称与合同编号通常由不同事实分别支持。"
        + "缺少可信业务范围就留空，系统会保留来源文档范围，不按名字跨资料合并。"
        + "根据 EXISTING ENTRY CANDIDATES 判断是否属于同一实际条目；同一范围且语义类型一致才填 matchedEntryId，逐字复制候选ID，否则留空。"
        + "匹配时 description 应概括已有候选及当前来源，不用当前单份资料的描述抹掉旧条目范围；每个条目仅返回一次，多名称放 aliases。"
        + "候选描述不是新的事实依据，不得把其中内容添加到当前 supportingFactIds。";
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
      double confidence,
      String matchedEntryId,
      List<GraphScopeIdentity.Anchor> scopeAnchors) {
    public ConceptProposal(
        String preferredLabel,
        List<String> aliases,
        String conceptTypeHint,
        String description,
        List<String> supportingFactIds,
        double confidence) {
      this(
          preferredLabel,
          aliases,
          conceptTypeHint,
          description,
          supportingFactIds,
          confidence,
          "",
          List.of());
    }
  }

  public record ConceptProposalSet(List<ConceptProposal> concepts) {}

  public record CompileResult(
      String summaryId, String versionId, List<ConceptRecord> concepts, String extractor) {
    public CompileResult {
      concepts = concepts == null ? List.of() : List.copyOf(concepts);
    }
  }
}
