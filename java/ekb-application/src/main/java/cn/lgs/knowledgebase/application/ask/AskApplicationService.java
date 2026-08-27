package cn.lgs.knowledgebase.application.ask;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.query.QueryEvidenceContext;
import cn.lgs.knowledgebase.application.query.QueryEvidenceContext.Source;
import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AskApplicationService {
  private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
  private static final String NO_EVIDENCE = "当前知识库中没有找到足够的可验证证据来回答这个问题。";
  private static final String INVALID_CITATION =
      "已找到可验证知识，但回答模型没有返回有效的 Evidence Citation，因此本次不交付未引用的自然语言答案。请查看下方证据或使用高级知识检索。";

  private final RetrievalApplicationService retrieval;
  private final KnowledgeModel model;
  private final int maxContextChars;
  private final QueryEvidenceContext context;

  public AskApplicationService(
      RetrievalApplicationService retrieval, KnowledgeModel model, int maxContextChars) {
    this(retrieval, model, maxContextChars, QueryEvidenceContext.conservativeStandalone());
  }

  public AskApplicationService(
      RetrievalApplicationService retrieval,
      KnowledgeModel model,
      int maxContextChars,
      RawTokenCounter tokens) {
    this(retrieval, model, maxContextChars, new QueryEvidenceContext(tokens));
  }

  private AskApplicationService(
      RetrievalApplicationService retrieval,
      KnowledgeModel model,
      int maxContextChars,
      QueryEvidenceContext context) {
    if (retrieval == null) throw new IllegalArgumentException("retrieval is required");
    this.retrieval = retrieval;
    this.model = model;
    this.maxContextChars = maxContextChars <= 0 ? 24_000 : maxContextChars;
    this.context = context;
  }

  public String evidenceContext(String question, List<Result> results, int maxChars) {
    return context.select(question, results, Math.min(maxContextChars, maxChars)).rendered();
  }

  public Response ask(String question, SearchFilters filters, int requestedLimit) {
    if (question == null || question.isBlank())
      throw new IllegalArgumentException("question is required");
    int limit = requestedLimit <= 0 || requestedLimit > 20 ? 8 : requestedLimit;
    var found = retrieval.search(question.trim(), filters, limit);
    return answerFromResults(question.trim(), found.searchId(), found.results(), ModelRole.SMALL);
  }

  public Response answerFromResults(
      String question, String searchId, List<Result> results, ModelRole role) {
    return answerFromResults(question, searchId, results, role, maxContextChars);
  }

  public Response answerFromResults(
      String question, String searchId, List<Result> results, ModelRole role, int maxChars) {
    if (question == null || question.isBlank())
      throw new IllegalArgumentException("question is required");
    String effectiveSearchId = searchId == null ? "" : searchId;
    QueryEvidenceContext.Selection selection =
        context.select(question.trim(), results, Math.min(maxContextChars, maxChars));
    List<Source> sources = selection.sources();
    if (sources.isEmpty()) return new Response(effectiveSearchId, NO_EVIDENCE, List.of(), true);
    if (model == null)
      return new Response(
          effectiveSearchId,
          "已找到可验证知识，但当前未配置 Ask 回答模型。请使用下方证据或高级知识检索继续查看。",
          citationsFor(sources, null),
          true);

    String answer =
        model.generate(
            role == null ? ModelRole.SMALL : role,
            ModelTask.ASK,
            "你是企业知识库回答器。只能依据 SOURCE 中的 Raw evidence 回答；每个事实必须使用形如 [1] 的来源编号引用。"
                + "每个引用必须直接支持紧邻事实，只引用最小必要来源；不得为凑引用添加弱相关来源。不得编造，不得引用不存在的编号。",
            "Question: " + question.trim() + "\n\n" + selection.rendered());
    if (answer == null || answer.isBlank())
      throw new IllegalStateException("ask model returned empty answer");
    Set<Integer> used = validateCitations(answer, sources.size());
    if (used == null || used.isEmpty()) {
      return new Response(effectiveSearchId, INVALID_CITATION, citationsFor(sources, null), true);
    }
    CanonicalAnswer canonical = canonicalizeCitations(answer.trim(), sources, used);
    return new Response(effectiveSearchId, canonical.answer(), canonical.citations(), false);
  }

  public record Citation(int index, String resultType, Evidence evidence) {}

  public record Response(
      String searchId, String answer, List<Citation> citations, boolean evidenceOnly) {}

  private record CanonicalAnswer(String answer, List<Citation> citations) {}

  /** Null means invalid citation syntax/range; empty means no citation at all. */
  static Set<Integer> validateCitations(String answer, int max) {
    Matcher matcher = CITATION.matcher(answer);
    Set<Integer> used = new LinkedHashSet<>();
    boolean matched = false;
    while (matcher.find()) {
      matched = true;
      int index;
      try {
        index = Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ex) {
        return null;
      }
      if (index < 1 || index > max) return null;
      used.add(index);
    }
    return matched ? used : Set.of();
  }

  private static CanonicalAnswer canonicalizeCitations(
      String answer, List<Source> sources, Set<Integer> used) {
    Map<Integer, Integer> canonicalIndexes = new LinkedHashMap<>();
    for (Integer sourceIndex : used) canonicalIndexes.put(sourceIndex, canonicalIndexes.size() + 1);

    Matcher matcher = CITATION.matcher(answer);
    StringBuffer normalized = new StringBuffer(answer.length());
    while (matcher.find()) {
      int sourceIndex = Integer.parseInt(matcher.group(1));
      Integer canonicalIndex = canonicalIndexes.get(sourceIndex);
      matcher.appendReplacement(normalized, Matcher.quoteReplacement("[" + canonicalIndex + "]"));
    }
    matcher.appendTail(normalized);

    List<Citation> citations = new ArrayList<>(canonicalIndexes.size());
    for (Map.Entry<Integer, Integer> entry : canonicalIndexes.entrySet()) {
      Source source = sources.get(entry.getKey() - 1);
      citations.add(new Citation(entry.getValue(), source.resultType(), source.evidence()));
    }
    return new CanonicalAnswer(normalized.toString(), List.copyOf(citations));
  }

  private static List<Citation> citationsFor(List<Source> sources, Set<Integer> used) {
    return sources.stream()
        .filter(source -> used == null || used.contains(source.index()))
        .map(source -> new Citation(source.index(), source.resultType(), source.evidence()))
        .toList();
  }
}
