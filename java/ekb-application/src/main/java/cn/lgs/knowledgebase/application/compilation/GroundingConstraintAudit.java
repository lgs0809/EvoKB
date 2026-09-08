package cn.lgs.knowledgebase.application.compilation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Shared publication boundary for Summary facts and Wiki claims, independent of model verdicts. */
public final class GroundingConstraintAudit {
  public static final String POLICY_VERSION = "grounding-constraints-v1";

  private GroundingConstraintAudit() {}

  public enum Dimension {
    SUBJECT,
    ACTION,
    OBJECT,
    POLARITY,
    CONDITIONS,
    SCOPE,
    TIME
  }

  public enum Finding {
    SUPPORTED,
    CONTRADICTED,
    UNKNOWN,
    NOT_APPLICABLE
  }

  /** Quotes are complete source spans, not reconstructed text or a model-generated paraphrase. */
  public record Check(
      Dimension dimension,
      Finding finding,
      String claimPart,
      List<String> evidenceQuotes,
      String reason) {}

  /** Structured audit is the reusable proof; free-form model reasoning is not a cache token. */
  public enum ClaimBinding {
    MODEL_QUOTED_SPAN_V1,
    FULL_CANDIDATE_V2
  }

  public record Audit(
      String policyVersion,
      String claim,
      boolean supported,
      List<Check> checks,
      ClaimBinding claimBinding) {
    public Audit(String policyVersion, String claim, boolean supported, List<Check> checks) {
      this(policyVersion, claim, supported, checks, ClaimBinding.MODEL_QUOTED_SPAN_V1);
    }

    public Audit {
      checks = checks == null ? null : List.copyOf(checks);
      claimBinding = claimBinding == null ? ClaimBinding.MODEL_QUOTED_SPAN_V1 : claimBinding;
    }
  }

  public static Audit accepted(String claim, List<Check> checks) {
    return new Audit(POLICY_VERSION, text(claim), true, checks);
  }

  public static Audit acceptedFullCandidate(String claim, List<Check> checks) {
    if (!fullCandidateBinding(claim, checks))
      throw new IllegalArgumentException("full-candidate proof must bind the complete proposition");
    return new Audit(POLICY_VERSION, text(claim), true, checks, ClaimBinding.FULL_CANDIDATE_V2);
  }

  /** Only fresh model records enter the bounded proposal retry; cached proof never does. */
  public static boolean modelSupported(
      String claim, String evidence, boolean modelSupported, List<Check> checks) {
    try {
      return supported(claim, evidence, modelSupported, checks);
    } catch (MalformedAudit invalidModelRecord) {
      throw new ModelOutputRejectedException(invalidModelRecord.getMessage(), invalidModelRecord);
    }
  }

  public static <T> T requireModelDecision(T decision, String context) {
    if (decision == null) throw new ModelOutputRejectedException(context + " returned no decision");
    return decision;
  }

  /** A model batch must cover precisely the supplied identities, without inventing or skipping. */
  public static <T> Map<String, T> modelDecisions(
      List<T> records,
      java.util.Collection<String> expectedIds,
      java.util.function.Function<T, String> identity,
      String context) {
    var expected = new java.util.LinkedHashSet<>(expectedIds);
    if (expected.isEmpty()
        || expected.size() != expectedIds.size()
        || expected.stream().anyMatch(id -> text(id).isEmpty()))
      throw new IllegalArgumentException("invalid expected grounding candidate identities");
    if (records == null)
      throw new ModelOutputRejectedException(context + " returned empty decisions");
    var indexed = new java.util.LinkedHashMap<String, T>();
    for (T record : records) {
      requireModelDecision(record, context);
      String id = text(identity.apply(record));
      if (!expected.contains(id))
        throw new ModelOutputRejectedException(context + " references unknown candidate " + id);
      if (indexed.putIfAbsent(id, record) != null)
        throw new ModelOutputRejectedException(context + " duplicated candidate " + id);
    }
    for (String id : expected)
      if (!indexed.containsKey(id))
        throw new ModelOutputRejectedException(context + " missing candidate " + id);
    return java.util.Collections.unmodifiableMap(indexed);
  }

  public static boolean current(String claim, String evidence, Audit audit) {
    if (audit == null
        || !POLICY_VERSION.equals(audit.policyVersion())
        || !text(claim).equals(audit.claim())
        || !audit.supported()
        || (audit.claimBinding() == ClaimBinding.FULL_CANDIDATE_V2
            && !fullCandidateBinding(claim, audit.checks()))) return false;
    try {
      return supported(claim, evidence, audit.supported(), audit.checks());
    } catch (IllegalStateException invalid) {
      return false;
    }
  }

  public static boolean currentSummary(
      CompilationContracts.SummaryRecord summary, CompilationContracts.VersionSource source) {
    if (!summary.activeAndGrounded()
        || summary.facts().isEmpty()
        || !summary.versionId().equals(source.versionId())) return false;
    for (var fact : summary.facts()) {
      if (!"PASS".equals(fact.groundingStatus())
          || fact.evidenceBlockIds().isEmpty()
          || fact.evidenceIds().isEmpty()) return false;
      StringBuilder evidence = new StringBuilder();
      java.util.Set<String> actualIds = new java.util.HashSet<>();
      for (String blockId : fact.evidenceBlockIds()) {
        var original = source.evidenceByBlock().get(blockId);
        if (original == null || !source.versionId().equals(original.versionId())) return false;
        actualIds.add(original.id());
        evidence.append("\n---\n").append(original.content());
      }
      if (!actualIds.equals(new java.util.HashSet<>(fact.evidenceIds()))
          || !current(fact.factText(), evidence.toString(), fact.groundingAudit())) return false;
    }
    return true;
  }

  public static void requireCurrentSummary(
      CompilationContracts.SummaryRecord summary,
      CompilationContracts.CompilationRepository repository) {
    if (!currentSummary(summary, repository.loadVersionSource(summary.versionId())))
      throw new IllegalStateException(
          "source Summary requires current grounding audit; regenerate Summary through normal compilation");
  }

  /** Every retained source/model input must exist and pass the same policy before reuse. */
  public static List<CompilationContracts.SummaryRecord> requireCurrentSummaries(
      List<String> summaryIds, CompilationContracts.CompilationRepository repository) {
    var requested = new java.util.LinkedHashSet<String>();
    for (String id : summaryIds) {
      String value = text(id);
      if (value.isEmpty())
        throw new IllegalStateException("source Summary input identity is missing");
      requested.add(value);
    }
    if (requested.isEmpty()) return List.of();
    // Input planning consumes authoritative facts/metadata only. Remote markdown cannot be read
    // before captureInputs establishes the complete authority for those external operations.
    var summaries =
        requested.stream()
            .map(repository::findSummaryForAudit)
            .flatMap(java.util.Optional::stream)
            .toList();
    var actual = new java.util.LinkedHashSet<String>();
    for (var summary : summaries) {
      if (summary == null || !actual.add(summary.id()))
        throw new IllegalStateException("source Summary inputs contain null or duplicate records");
    }
    if (!actual.equals(requested))
      throw new IllegalStateException(
          "source Summary input is missing; regenerate through normal compilation");
    for (var summary : summaries) requireCurrentSummary(summary, repository);
    return List.copyOf(summaries);
  }

  public static List<CompilationContracts.SummaryRecord> requireCurrentEntry(
      KnowledgeEntryCatalogue.Entry entry, CompilationContracts.CompilationRepository repository) {
    var inputs = new java.util.LinkedHashSet<>(entry.inputSummaryIds());
    inputs.addAll(entry.summaryIds());
    return requireCurrentSummaries(List.copyOf(inputs), repository);
  }

  public static String criteria() {
    return semanticCriteria()
        + "每项另外返回候选原文连续片段 claimPart，claimPart 不得改写。"
        + "NOT_APPLICABLE 的 claimPart 必须为空。";
  }

  static String semanticCriteria() {
    return "先独立解析候选完整命题和证据完整命题，再逐项对照，而不是按词语重合给结论。"
        + "候选含多条分句时逐一核对全部分句的角色与限制，不能只用某个正确分句代表整条候选。checks 必须恰好包含 SUBJECT/ACTION/OBJECT/POLARITY/CONDITIONS/SCOPE/TIME 七项。"
        + "每项返回 finding、证据原文连续片段 evidenceQuotes 和具体 reason。"
        + "SUBJECT 核对谁做动作或承担权利义务；OBJECT 核对谁被作用，二者不得互换。"
        + "先解析主动/被动和代词的指向，证据未指明动作方时不得把对象补成动作方。"
        + "POLARITY 核对肯定、否定和许可/禁止；CONDITIONS 保留所有前提及且/或关系；"
        + "SCOPE/TIME 核对适用范围、时间和数量单位。仅存在同名主体、术语或数字不等于支持。"
        + "SUPPORTED 必须提供原文引文；引文应含足以判断该约束的上下文。"
        + "明确不一致为 CONTRADICTED；原文未说、代词不明、必要上下文缺失为 UNKNOWN。"
        + "仅原命题与候选均不含该维度时才用 NOT_APPLICABLE，evidenceQuotes 为空。"
        + "SUBJECT、ACTION、POLARITY 必须核对，不能用 NOT_APPLICABLE。"
        + "reason 要分别说明候选和证据中的角色/约束，不能只说“相同”。"
        + "先完成七项检查，只有所有适用项 SUPPORTED 才能 supported=true。禁止常识补全。";
  }

  public static boolean supported(
      String claim, String evidence, boolean modelSupported, List<Check> checks) {
    if (checks == null) throw malformed("missing checks");
    Map<Dimension, Check> byDimension = new EnumMap<>(Dimension.class);
    for (Check check : checks) {
      if (check == null || check.dimension() == null || check.finding() == null)
        throw malformed("null constraint check");
      if (byDimension.putIfAbsent(check.dimension(), check) != null)
        throw malformed("duplicate constraint " + check.dimension());
      if (text(check.reason()).isEmpty()) throw malformed("missing constraint reason");
      if (check.finding() == Finding.NOT_APPLICABLE) {
        if (check.dimension() == Dimension.SUBJECT
            || check.dimension() == Dimension.ACTION
            || check.dimension() == Dimension.POLARITY
            || !text(check.claimPart()).isEmpty()
            || (check.evidenceQuotes() != null && !check.evidenceQuotes().isEmpty()))
          throw malformed("invalid not-applicable constraint " + check.dimension());
      } else {
        if (text(check.claimPart()).isEmpty() || !fold(claim).contains(fold(check.claimPart())))
          throw malformed("constraint claim span is not in candidate " + check.dimension());
        if (check.finding() == Finding.SUPPORTED || check.finding() == Finding.CONTRADICTED) {
          if (check.evidenceQuotes() == null || check.evidenceQuotes().isEmpty())
            throw malformed("constraint has no evidence quote " + check.dimension());
          for (String quote : check.evidenceQuotes())
            if (text(quote).isEmpty() || !fold(evidence).contains(fold(quote)))
              throw malformed("constraint quote is not in supplied evidence " + check.dimension());
        }
      }
    }
    if (byDimension.size() != Dimension.values().length)
      throw malformed("incomplete constraint checks");
    return modelSupported
        && byDimension.values().stream()
            .allMatch(
                c -> c.finding() == Finding.SUPPORTED || c.finding() == Finding.NOT_APPLICABLE);
  }

  /** An explicitly enabled extractive fallback can certify only a complete, identical source. */
  public static List<Check> exactCopyChecks(String claim, List<String> completeSources) {
    String candidate = text(claim);
    String source =
        completeSources.stream()
            .map(GroundingConstraintAudit::text)
            .filter(s -> !s.isEmpty() && fold(s).equals(fold(candidate)))
            .findFirst()
            .orElse("");
    if (source.isEmpty()) return List.of();
    return java.util.Arrays.stream(Dimension.values())
        .map(
            dimension ->
                new Check(
                    dimension,
                    Finding.SUPPORTED,
                    candidate,
                    List.of(source),
                    "Complete source copied identically; no semantic rewriting or inference"))
        .toList();
  }

  /** Persist the checked proposition and located proof with the fact, inside its publication. */
  public static String describe(String reason, List<Check> checks) {
    StringBuilder out = new StringBuilder(POLICY_VERSION).append('\n').append(text(reason));
    for (Check check : checks) {
      out.append('\n')
          .append(check.dimension())
          .append('=')
          .append(check.finding())
          .append("; claim=")
          .append(text(check.claimPart()))
          .append("; evidence=")
          .append(check.evidenceQuotes())
          .append("; reason=")
          .append(text(check.reason()));
    }
    return out.toString();
  }

  private static String fold(String value) {
    return text(value).replaceAll("\\s+", " ");
  }

  private static boolean fullCandidateBinding(String claim, List<Check> checks) {
    return checks != null
        && checks.stream()
            .allMatch(
                check ->
                    check != null
                        && (check.finding() == Finding.NOT_APPLICABLE
                            ? text(check.claimPart()).isEmpty()
                            : text(claim).equals(check.claimPart())));
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static MalformedAudit malformed(String reason) {
    return new MalformedAudit("grounding constraint audit: " + reason);
  }

  private static final class MalformedAudit extends IllegalStateException {
    private MalformedAudit(String message) {
      super(message);
    }
  }
}
