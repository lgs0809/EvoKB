package cn.lgs.knowledgebase.application.compilation;

import java.util.List;

/** The judge evaluates a fixed complete proposition; it does not generate its input identity. */
public final class GroundingJudgeProtocol {
  // Each candidate requires a complete seven-dimension decision. Bound the response envelope
  // independently of the number of facts/claims in the document; never truncate candidates.
  public static final int MAX_BATCH_CANDIDATES = 4;

  private GroundingJudgeProtocol() {}

  public enum Version {
    FULL_CANDIDATE_V2
  }

  public record Constraint(
      GroundingConstraintAudit.Dimension dimension,
      GroundingConstraintAudit.Finding finding,
      List<String> evidenceQuotes,
      String reason) {}

  public record Decision(
      Version protocolVersion, boolean supported, String reason, List<Constraint> checks) {
    public Decision(boolean supported, String reason) {
      this(supported, reason, null);
    }

    public Decision(boolean supported, String reason, List<Constraint> checks) {
      this(Version.FULL_CANDIDATE_V2, supported, reason, checks);
    }
  }

  public record Item(
      String candidateId, boolean supported, String reason, List<Constraint> checks) {
    public Item(String candidateId, boolean supported, String reason) {
      this(candidateId, supported, reason, null);
    }
  }

  public record Batch(Version protocolVersion, List<Item> decisions) {
    public Batch(List<Item> decisions) {
      this(Version.FULL_CANDIDATE_V2, decisions);
    }
  }

  public static void requireVersion(Version version) {
    if (version != Version.FULL_CANDIDATE_V2)
      throw new ModelOutputRejectedException(
          "grounding judge protocol version is missing or unknown");
  }

  /** Full-input binding, never a claim of exact subject/action/object span localization. */
  public static List<GroundingConstraintAudit.Check> bind(
      String claim, List<Constraint> constraints) {
    if (claim == null || claim.isBlank()) throw new IllegalArgumentException("candidate required");
    if (constraints == null)
      throw new ModelOutputRejectedException("grounding judge missing checks");
    return constraints.stream()
        .map(
            constraint -> {
              GroundingConstraintAudit.requireModelDecision(constraint, "grounding constraint");
              return new GroundingConstraintAudit.Check(
                  constraint.dimension(),
                  constraint.finding(),
                  constraint.finding() == GroundingConstraintAudit.Finding.NOT_APPLICABLE
                      ? ""
                      : claim,
                  constraint.evidenceQuotes(),
                  constraint.reason());
            })
        .toList();
  }

  public static String criteria() {
    return GroundingConstraintAudit.semanticCriteria()
        + "本协议 protocolVersion 必须为 FULL_CANDIDATE_V2。候选全文及 candidate_id 由服务端固定，禁止改写或替换。"
        + "每个维度都判断整个候选命题是否直接成立，不是只找一个正确词语或分句。"
        + "checks 只返回 dimension、finding、证据连续原文 evidenceQuotes 和 reason，不生成 claimPart。"
        + "服务端把完整候选绑定到各适用维度；这不是对角色在文本中的精确局部跨度定位。";
  }
}
