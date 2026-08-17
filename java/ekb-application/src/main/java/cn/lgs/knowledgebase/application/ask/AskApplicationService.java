package cn.lgs.knowledgebase.application.ask;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

  public AskApplicationService(
      RetrievalApplicationService retrieval, KnowledgeModel model, int maxContextChars) {
    if (retrieval == null) throw new IllegalArgumentException("retrieval is required");
    this.retrieval = retrieval;
    this.model = model;
    this.maxContextChars = maxContextChars <= 0 ? 24_000 : maxContextChars;
  }

  public Response ask(String question, SearchFilters filters, int requestedLimit) {
    if (question == null || question.isBlank())
      throw new IllegalArgumentException("question is required");
    int limit = requestedLimit <= 0 || requestedLimit > 20 ? 8 : requestedLimit;
    var found = retrieval.search(question.trim(), filters, limit);
    List<Source> sources = sources(found.results());
    if (sources.isEmpty()) return new Response(found.searchId(), NO_EVIDENCE, List.of(), true);
    if (model == null)
      return new Response(
          found.searchId(),
          "已找到可验证知识，但当前未配置 Ask 回答模型。请使用下方证据或高级知识检索继续查看。",
          citationsFor(sources, null),
          true);

    String answer =
        model.generate(
            ModelRole.SMALL,
            ModelTask.ASK,
            "你是企业知识库回答器。只能依据 SOURCE 中的 Raw evidence 回答；每个事实必须使用形如 [1] 的来源编号引用。不得编造，不得引用不存在的编号。",
            "Question: " + question.trim() + "\n\n" + groundedContext(sources));
    if (answer == null || answer.isBlank())
      throw new IllegalStateException("ask model returned empty answer");
    Set<Integer> used = validateCitations(answer, sources.size());
    if (used == null || used.isEmpty()) {
      return new Response(found.searchId(), INVALID_CITATION, citationsFor(sources, null), true);
    }
    return new Response(found.searchId(), answer.trim(), citationsFor(sources, used), false);
  }

  public record Citation(int index, String resultType, Evidence evidence) {}

  public record Response(
      String searchId, String answer, List<Citation> citations, boolean evidenceOnly) {}

  private record Source(int index, String resultType, String knowledge, Evidence evidence) {}

  private static List<Source> sources(List<Result> results) {
    Set<String> seen = new LinkedHashSet<>();
    List<Source> sources = new ArrayList<>();
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        if (seen.add(evidence.id())) {
          sources.add(
              new Source(
                  sources.size() + 1,
                  result.candidate().lane().name().toLowerCase(),
                  result.candidate().knowledge(),
                  evidence));
        }
      }
    }
    return List.copyOf(sources);
  }

  private String groundedContext(List<Source> sources) {
    StringBuilder out = new StringBuilder();
    for (Source source : sources) {
      String block =
          "[SOURCE "
              + source.index
              + "]\nKnowledge type: "
              + source.resultType
              + "\nKnowledge: "
              + source.knowledge
              + "\nRaw evidence: "
              + source.evidence.content()
              + "\nDocument version: "
              + source.evidence.versionId()
              + "\nRaw location: "
              + source.evidence.location()
              + "\n\n";
      if (out.length() + block.length() > maxContextChars) break;
      out.append(block);
    }
    return out.toString();
  }

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

  private static List<Citation> citationsFor(List<Source> sources, Set<Integer> used) {
    return sources.stream()
        .filter(source -> used == null || used.contains(source.index))
        .map(source -> new Citation(source.index, source.resultType, source.evidence))
        .toList();
  }
}
