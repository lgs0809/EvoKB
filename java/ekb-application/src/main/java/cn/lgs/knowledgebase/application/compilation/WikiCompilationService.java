package cn.lgs.knowledgebase.application.compilation;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiClaim;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ArtifactStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiCompilationService {
  private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^]\\n]+)]]");

  private final CompilationRepository repository;
  private final KnowledgeModel model;
  private final ObjectStore objects;
  private final EmbeddingPort embedding;
  private final DerivedSearchIndex derived;
  private final Clock clock;

  public WikiCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      ObjectStore objects,
      EmbeddingPort embedding,
      DerivedSearchIndex derived) {
    this(repository, model, objects, embedding, derived, Clock.systemUTC());
  }

  WikiCompilationService(
      CompilationRepository repository,
      KnowledgeModel model,
      ObjectStore objects,
      EmbeddingPort embedding,
      DerivedSearchIndex derived,
      Clock clock) {
    this.repository = require(repository, "compilation repository");
    this.model = require(model, "knowledge model");
    this.objects = require(objects, "object store");
    this.embedding = require(embedding, "embedding");
    this.derived = require(derived, "derived search index");
    this.clock = require(clock, "clock");
    String writer = trim(model.identity(ModelRole.LARGE));
    String judge = trim(model.identity(ModelRole.SMALL));
    if (writer.isEmpty() || judge.isEmpty())
      throw new IllegalArgumentException("wiki writer and judge identities are required");
    if (writer.equalsIgnoreCase(judge))
      throw new IllegalArgumentException("wiki writer and judge must be independent");
  }

  public CompileResult compile(CompileRequest request) {
    return compile(request, repository.newPublication());
  }

  public CompileResult compile(CompileRequest request, CompilationPublication publication) {
    return compile(request, publication, ignored -> {});
  }

  public CompileResult compileEntry(
      KnowledgeEntryCatalogue.Entry entry,
      CompilationPublication publication,
      Consumer<WikiRecord> databaseProjection) {
    if (entry == null || entry.sources().isEmpty())
      throw new IllegalArgumentException("grounded stable entry sources are required");
    return compile(
        new CompileRequest(entry.type(), entry.label(), entry.id(), entry.summaryIds()),
        publication,
        databaseProjection,
        entry.id(),
        entry.inputSummaryIds());
  }

  /** Include database-only projections in the same fenced publication as the Wiki itself. */
  public CompileResult compile(
      CompileRequest request,
      CompilationPublication publication,
      Consumer<WikiRecord> databaseProjection) {
    return compile(
        request,
        publication,
        databaseProjection,
        "",
        request == null ? List.of() : request.summaryIds());
  }

  private CompileResult compile(
      CompileRequest request,
      CompilationPublication publication,
      Consumer<WikiRecord> databaseProjection,
      String stableEntryId,
      List<String> inputSummaryIds) {
    require(databaseProjection, "wiki database projection");
    if (request == null) throw new IllegalArgumentException("wiki compile request is required");
    String wikiType = normalizeType(request.wikiType());
    if (wikiType.isEmpty()) wikiType = "GENERIC_TOPIC";
    String title = trim(request.title());
    if (title.isEmpty() || request.summaryIds() == null || request.summaryIds().isEmpty()) {
      throw new IllegalArgumentException("wiki title and source summaries are required");
    }
    List<String> summaryIds =
        request.summaryIds().stream()
            .map(WikiCompilationService::trim)
            .filter(v -> !v.isEmpty())
            .distinct()
            .toList();
    List<SummaryRecord> summaries = repository.loadSummaries(summaryIds);
    var context = repository.derivedContextForSummaries(inputSummaryIds);
    publication.captureInputs(context.versionIds());
    Map<String, SummaryFact> facts = sourceFacts(summaries);
    if (facts.isEmpty())
      throw new IllegalStateException("wiki source contains no grounded active facts");
    var references = new GroundedFactReferences(facts);

    Proposal proposal =
        model.generateStructured(
            ModelRole.LARGE,
            ModelTask.WIKI_WRITE,
            writerPrompt(wikiType),
            sourcePrompt(summaries, references),
            Proposal.class);
    if (proposal == null) throw new IllegalStateException("wiki writer returned empty proposal");
    if (!trim(proposal.wikiType()).isEmpty()
        && !normalizeType(proposal.wikiType()).equals(wikiType)) {
      throw new IllegalStateException("wiki proposal type mismatch");
    }
    DecisionValidation decisionValidation =
        validateDecisions(facts, references, proposal.factDecisions());
    String wikiId =
        !stableEntryId.isEmpty()
            ? stableEntryId
            : GraphCompilationService.stableId(
                "wiki",
                wikiType,
                title.toLowerCase(Locale.ROOT),
                trim(request.canonicalSubjectId()));
    long version = repository.nextWikiVersion(wikiId);
    GroundingResult grounded =
        groundClaims(
            wikiId,
            version,
            safe(proposal.claims()),
            facts,
            references,
            decisionValidation.decisions());

    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, FactDecision> entry : decisionValidation.decisions().entrySet()) {
      if (entry.getValue().wikiEligible() && !grounded.coveredFacts().contains(entry.getKey()))
        missing.add(entry.getKey());
    }
    missing.sort(String::compareTo);
    CoverageResult coverage =
        new CoverageResult(
            missing.isEmpty(),
            decisionValidation.eligibleFacts(),
            decisionValidation.eligibleFacts() - missing.size(),
            decisionValidation.skippedFacts(),
            List.copyOf(missing));
    if (!coverage.pass())
      throw new IllegalStateException("wiki coverage failed: " + String.join(",", missing));

    if (grounded.claims().isEmpty())
      throw new IllegalStateException("wiki writer produced no grounded claims");
    List<String> keywords = extractWikiLinks(grounded.claims());
    String markdown = render(title, grounded.claims());
    String objectKey = publication.objectKey("wiki/" + wikiId + "/v" + version + ".md");
    byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
    objects.put(
        objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
    WikiRecord record =
        new WikiRecord(
            wikiId,
            version,
            wikiType,
            trim(request.canonicalSubjectId()),
            title,
            objectKey,
            sha256(markdown),
            "ACTIVE",
            markdown,
            grounded.claims(),
            keywords,
            keywords,
            clock.instant());

    List<Claim> claims =
        grounded.claims().stream()
            .map(c -> new Claim(c.claimText(), c.evidenceIds(), 1.0))
            .toList();
    WikiPage page =
        new WikiPage(
            wikiId,
            title,
            markdown,
            keywords,
            keywords,
            claims,
            ArtifactStatus.ACTIVE,
            clock.instant());
    List<List<Float>> vectors = embedding.embed(List.of(markdown));
    if (vectors.size() != 1) throw new IllegalStateException("wiki embedding response mismatch");
    Runnable indexPublication = derived.prepareWiki(page, context, vectors.get(0));
    publication.publish(
        () -> {
          repository.saveWiki(record, summaryIds);
          indexPublication.run();
          databaseProjection.accept(record);
        });
    return new CompileResult(
        record, coverage, model.identity(ModelRole.LARGE), model.identity(ModelRole.SMALL));
  }

  private GroundingResult groundClaims(
      String wikiId,
      long version,
      List<ProposedClaim> proposed,
      Map<String, SummaryFact> facts,
      GroundedFactReferences references,
      Map<String, FactDecision> decisions) {
    List<ClaimCandidate> candidates = new ArrayList<>();
    int candidateNo = 0;
    for (ProposedClaim raw : proposed) {
      if (raw == null) throw new IllegalStateException("wiki claim is null");
      String section = trim(raw.section());
      String text = trim(raw.claimText());
      List<String> factIds =
          raw.supportingFactIds() == null
              ? List.of()
              : raw.supportingFactIds().stream()
                  .map(references::canonicalId)
                  .filter(v -> !v.isEmpty())
                  .distinct()
                  .toList();
      if (section.isEmpty() || text.isEmpty() || factIds.isEmpty()) {
        throw new IllegalStateException("wiki claim is incomplete");
      }
      List<SummaryFact> support = new ArrayList<>();
      LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
      for (String factId : factIds) {
        SummaryFact fact = facts.get(factId);
        FactDecision decision = decisions.get(factId);
        if (fact == null || decision == null || !decision.wikiEligible()) {
          throw new IllegalStateException("wiki claim references ineligible fact " + factId);
        }
        if (fact.evidenceIds().isEmpty())
          throw new IllegalStateException("wiki claim fact has no raw evidence " + factId);
        support.add(fact);
        evidenceIds.addAll(fact.evidenceIds());
      }
      candidates.add(
          new ClaimCandidate(
              "C" + (++candidateNo),
              section,
              text,
              factIds,
              List.copyOf(support),
              List.copyOf(evidenceIds)));
    }

    Map<String, SupportDecision> judged = judgeClaims(candidates);
    List<WikiClaim> claims = new ArrayList<>(candidates.size());
    Set<String> covered = new LinkedHashSet<>();
    for (ClaimCandidate candidate : candidates) {
      SupportDecision decision = judged.get(candidate.judgeId());
      if (decision == null || !decision.supported())
        throw new IllegalStateException(
            "wiki claim is not grounded: " + trim(decision == null ? "" : decision.reason()));
      String claimId =
          GraphCompilationService.stableId(
              "wiki-claim",
              wikiId,
              Long.toString(version),
              candidate.section(),
              candidate.claimText(),
              String.join(",", candidate.factIds()));
      claims.add(
          new WikiClaim(
              claimId,
              wikiId,
              version,
              candidate.section(),
              candidate.claimText(),
              candidate.factIds(),
              candidate.evidenceIds(),
              "PASS",
              "PASS"));
      covered.addAll(candidate.factIds());
    }
    return new GroundingResult(List.copyOf(claims), Set.copyOf(covered));
  }

  private Map<String, SupportDecision> judgeClaims(List<ClaimCandidate> candidates) {
    if (candidates.isEmpty()) return Map.of();
    if (candidates.size() == 1) {
      ClaimCandidate candidate = candidates.get(0);
      SupportDecision decision =
          model.generateStructured(
              ModelRole.SMALL,
              ModelTask.WIKI_JUDGE,
              "你是独立 Wiki Claim Grounding Judge。只判断 claim 是否被给定的 grounded summary facts 直接支持。禁止使用常识补全；输出 supported 和 reason。",
              "CLAIM="
                  + candidate.claimText()
                  + "\nSUPPORTING FACTS:\n"
                  + renderFactSupport(candidate.support()),
              SupportDecision.class);
      return Map.of(candidate.judgeId(), decision);
    }

    SupportDecisionSet result =
        model.generateStructured(
            ModelRole.SMALL,
            ModelTask.WIKI_JUDGE,
            "你是独立 Wiki Claim Grounding Judge。逐条判断每个 candidate claim 是否被它自己的 grounded summary facts 直接支持。"
                + "必须为每个 candidate_id 恰好返回一条 decision，不得遗漏、增加或改写 candidate_id；禁止使用常识补全。",
            renderClaimCandidates(candidates),
            SupportDecisionSet.class);
    if (result == null || result.decisions() == null)
      throw new IllegalStateException("wiki grounding batch returned empty decisions");
    Set<String> expected =
        candidates.stream()
            .map(ClaimCandidate::judgeId)
            .collect(java.util.stream.Collectors.toSet());
    Map<String, SupportDecision> decisions = new LinkedHashMap<>();
    for (SupportDecisionItem raw : result.decisions()) {
      if (raw == null)
        throw new IllegalStateException("wiki grounding batch contains null decision");
      String candidateId = trim(raw.candidateId());
      if (!expected.contains(candidateId))
        throw new IllegalStateException(
            "wiki grounding batch references unknown candidate " + candidateId);
      if (decisions.putIfAbsent(
              candidateId, new SupportDecision(raw.supported(), trim(raw.reason())))
          != null)
        throw new IllegalStateException("wiki grounding batch duplicated candidate " + candidateId);
    }
    for (String candidateId : expected)
      if (!decisions.containsKey(candidateId))
        throw new IllegalStateException("wiki grounding batch missing candidate " + candidateId);
    return Map.copyOf(decisions);
  }

  private static String renderClaimCandidates(List<ClaimCandidate> candidates) {
    StringBuilder out = new StringBuilder("CANDIDATES:\n");
    for (ClaimCandidate candidate : candidates) {
      out.append("candidate_id=")
          .append(candidate.judgeId())
          .append("\nCLAIM=")
          .append(candidate.claimText())
          .append("\nSUPPORTING FACTS:\n")
          .append(renderFactSupport(candidate.support()))
          .append("\n===\n");
    }
    return out.toString();
  }

  private static DecisionValidation validateDecisions(
      Map<String, SummaryFact> facts, GroundedFactReferences references, List<FactDecision> input) {
    Map<String, FactDecision> decisions = new LinkedHashMap<>();
    int eligible = 0, skipped = 0;
    for (FactDecision raw : safe(input)) {
      String factId = references.canonicalId(raw.factId());
      if (!facts.containsKey(factId))
        throw new IllegalStateException("wiki decision references unknown fact " + factId);
      if (decisions.containsKey(factId))
        throw new IllegalStateException("wiki decision duplicated for fact " + factId);
      if (!raw.wikiEligible() && trim(raw.skipReason()).isEmpty())
        throw new IllegalStateException("wiki skip reason required for fact " + factId);
      FactDecision normalized =
          new FactDecision(factId, raw.wikiEligible(), trim(raw.skipReason()));
      decisions.put(factId, normalized);
      if (normalized.wikiEligible()) eligible++;
      else skipped++;
    }
    for (String factId : facts.keySet()) {
      if (!decisions.containsKey(factId))
        throw new IllegalStateException("wiki decision missing for fact " + factId);
    }
    return new DecisionValidation(Map.copyOf(decisions), eligible, skipped);
  }

  private static Map<String, SummaryFact> sourceFacts(List<SummaryRecord> summaries) {
    Map<String, SummaryFact> facts = new LinkedHashMap<>();
    for (SummaryRecord summary : summaries == null ? List.<SummaryRecord>of() : summaries) {
      if (!summary.activeAndGrounded())
        throw new IllegalStateException(
            "wiki source summary is not ACTIVE with grounding+coverage PASS: " + summary.id());
      for (SummaryFact fact : summary.facts()) {
        if ("PASS".equals(fact.groundingStatus()) && !trim(fact.id()).isEmpty())
          facts.put(fact.id(), fact);
      }
    }
    return facts;
  }

  private static List<String> extractWikiLinks(List<WikiClaim> claims) {
    LinkedHashSet<String> links = new LinkedHashSet<>();
    for (WikiClaim claim : claims) {
      Matcher matcher = WIKILINK.matcher(claim.claimText());
      while (matcher.find()) {
        String link = trim(matcher.group(1));
        if (!link.isEmpty()) links.add(link);
      }
    }
    return List.copyOf(links);
  }

  private static String render(String title, List<WikiClaim> claims) {
    StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
    LinkedHashSet<String> sections = new LinkedHashSet<>();
    for (WikiClaim claim : claims) sections.add(trim(claim.section()));
    for (String section : sections) {
      out.append("## ").append(section).append("\n\n");
      for (WikiClaim claim : claims)
        if (section.equals(trim(claim.section())))
          out.append("- ").append(claim.claimText()).append("\n");
      out.append('\n');
    }
    return out.toString();
  }

  private static String writerPrompt(String wikiType) {
    return "你是企业 Wiki Writer。仅基于输入的 grounded facts 编写候选 claims。wiki_type 使用 "
        + wikiType
        + " 作为页面分类标签，但 section 不受固定模板限制，应根据事实自然组织为少量清晰、稳定的栏目。"
        + "每条 claim 必须引用 supporting_fact_ids。每个输入 fact 必须输出一个 fact_decision；"
        + "适合 Wiki 的 fact 标记 wiki_eligible=true 且最终必须至少被一个 claim 使用；不适合的必须给 skip_reason。"
        + "逐字复制每个输入 fact 的短引用键（例如 F001），fact_decisions 中每个引用恰好出现一次；不要输出 UUID，不得发明、截断或改写引用。"
        + "Claim 只能陈述 supporting facts 明确写出的内容，可忠实改写但不得补充因果、职责、审批要求、人数或时间等隐含信息；"
        + "若事实没有明确表达某个关系或义务，就不要在 claim 中添加该关系或义务。"
        + "为确保可验证，优先将 claim_text 直接复制为 supporting fact 的完整原文；只有在不增加任何信息的情况下才可做极短改写。"
        + "只把你能用这种原文式 claim 覆盖的事实标记为 wiki_eligible=true，其余事实标记为 false 并说明原因。"
        + "Claim 必须原子化：一条 claim 只表达一个可独立验证的事实，不要把多个关系或多个时间事件拼在同一句。"
        + "若 claim 表达两个实体之间的关系，应恰好使用两个 [[实体名]]，并把明确的关系词放在两个 wikilink 之间，例如 [[A]] 控制 [[B]]、[[A]] 投资 [[B]]。"
        + "一个 grounded fact 若包含多个二元关系，可以拆成多条 claims，并复用同一个 supporting_fact_id。不得为了造链接或关系而改写事实。";
  }

  private static String sourcePrompt(
      List<SummaryRecord> summaries, GroundedFactReferences references) {
    StringBuilder out = new StringBuilder();
    // Wiki writing is grounded on the normalized facts below.  Re-sending the full derived
    // Summary markdown is redundant and can make an otherwise valid provider request exceed its
    // transport budget; retaining the summary ids keeps provenance visible without dropping any
    // raw evidence or fact text.
    for (SummaryRecord summary : summaries)
      out.append("<summary id=\"").append(summary.id()).append("\"/>\n");
    out.append("<facts>\n");
    out.append(references.renderFacts());
    out.append("</facts>");
    return out.toString();
  }

  private static String renderFactSupport(List<SummaryFact> facts) {
    StringBuilder out = new StringBuilder();
    for (SummaryFact fact : facts)
      out.append('[').append(fact.id()).append("] ").append(fact.factText()).append('\n');
    return out.toString();
  }

  private static String normalizeType(String value) {
    return trim(value).toUpperCase(Locale.ROOT);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static <T> List<T> safe(List<T> value) {
    return value == null ? List.of() : value;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private record DecisionValidation(
      Map<String, FactDecision> decisions, int eligibleFacts, int skippedFacts) {}

  private record GroundingResult(List<WikiClaim> claims, Set<String> coveredFacts) {}

  public record CompileRequest(
      String wikiType, String title, String canonicalSubjectId, List<String> summaryIds) {}

  public record ProposedClaim(String section, String claimText, List<String> supportingFactIds) {}

  public record FactDecision(String factId, boolean wikiEligible, String skipReason) {}

  public record Proposal(
      String wikiType,
      String title,
      List<ProposedClaim> claims,
      List<FactDecision> factDecisions) {}

  public record SupportDecision(boolean supported, String reason) {}

  public record SupportDecisionItem(String candidateId, boolean supported, String reason) {}

  public record SupportDecisionSet(List<SupportDecisionItem> decisions) {}

  private record ClaimCandidate(
      String judgeId,
      String section,
      String claimText,
      List<String> factIds,
      List<SummaryFact> support,
      List<String> evidenceIds) {}

  public record CoverageResult(
      boolean pass,
      int eligibleFacts,
      int coveredFacts,
      int skippedFacts,
      List<String> missingFacts) {}

  public record CompileResult(
      WikiRecord wiki, CoverageResult coverage, String writer, String judge) {}
}
