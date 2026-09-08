package cn.lgs.knowledgebase.application.ask;

/** Shared reasoning contract for evidence sufficiency, generation, and delivery verification. */
public final class GroundedDerivationPolicy {
  private GroundedDerivationPolicy() {}

  public static String instructions() {
    return "区分原文事实与基于原文的推导。用户要求汇总、比较、算术或日期计算时，"
        + "原文不必逐字写出计算结果；但输入数值、单位、日期、适用条件、对象关系和有效版本必须有引用依据，"
        + "并核对运算及先后条件，不能省略其中一项前提，也不能跨对象拼接。"
        + "回答须展示必要的计算过程、起算点和所用口径。原文或用户未明确的计日、舍入等约定，"
        + "只能明示为条件假设并给出该假设下的结果，不能冒充合同明确约定或无条件确定结论；"
        + "假设与已知原文冲突时不得采用。工作日缺少日历等无法可靠计算的条件，应具体说明限制。"
        + "已引用的前提足以给出透明的条件式计算时，不要仅因原文没有最终结果或未规定该假设而反复补搜。"
        + "日期口径未定义时先列出已知事实，再明确给出若按自然日、次日起算的条件结果，并指出原文未定义；"
        + "仅当用户需要唯一确定日期且缺少约定时再追问，不能把条件结果说成确定事实。"
        + "计算得到的应付期限不证明实际付款、到账或其他现实履行已经发生；未知事实必须保留未知。";
  }
}
