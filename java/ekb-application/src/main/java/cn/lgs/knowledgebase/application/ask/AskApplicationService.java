package cn.lgs.knowledgebase.application.ask;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
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
  private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
  private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,}");
  private static final String NO_EVIDENCE = "当前知识库中没有找到足够的可验证证据来回答这个问题。";
  private static final String INVALID_CITATION =
      "已找到可验证知识，但回答模型没有返回有效的 Evidence Citation，因此本次不交付未引用的自然语言答案。请查看下方证据或使用高级知识检索。";
  private static final int MAX_ANSWER_SOURCES = 16;

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
    return answerFromResults(question.trim(), found.searchId(), found.results(), ModelRole.SMALL);
  }

  public Response answerFromResults(
      String question, String searchId, List<Result> results, ModelRole role) {
    if (question == null || question.isBlank())
      throw new IllegalArgumentException("question is required");
    String effectiveSearchId = searchId == null ? "" : searchId;
    List<Source> sources = sources(question.trim(), results == null ? List.of() : results);
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
            "Question: " + question.trim() + "\n\n" + groundedContext(sources));
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

  private record Source(int index, String resultType, Evidence evidence) {}

  private record RankedSource(String resultType, Evidence evidence, int order, int relevance) {}

  private record CanonicalAnswer(String answer, List<Citation> citations) {}

  private static List<Source> sources(String question, List<Result> results) {
    Set<String> seen = new LinkedHashSet<>();
    List<RankedSource> ranked = new ArrayList<>();
    int order = 0;
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        if (seen.add(evidence.id())) {
          ranked.add(
              new RankedSource(
                  result.candidate().lane().name().toLowerCase(),
                  evidence,
                  order++,
                  lexicalRelevance(question, evidence.content())));
        }
      }
    }
    if (ranked.stream().anyMatch(source -> source.relevance() > 0)) {
      ranked.sort(
          java.util.Comparator.comparingInt(RankedSource::relevance)
              .reversed()
              .thenComparingInt(RankedSource::order));
    }
    int size = Math.min(MAX_ANSWER_SOURCES, ranked.size());
    List<Source> sources = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      RankedSource source = ranked.get(i);
      sources.add(new Source(i + 1, source.resultType(), source.evidence()));
    }
    return List.copyOf(sources);
  }

  private static int lexicalRelevance(String question, String content) {
    if (question == null || question.isBlank() || content == null || content.isBlank()) return 0;
    String haystack = content.toLowerCase(java.util.Locale.ROOT);
    Set<String> features = new LinkedHashSet<>();
    Matcher han = HAN_SEQUENCE.matcher(question);
    while (han.find()) {
      String sequence = han.group();
      for (int width = 2; width <= Math.min(3, sequence.length()); width++) {
        for (int i = 0; i + width <= sequence.length(); i++) {
          features.add(sequence.substring(i, i + width));
        }
      }
    }
    Matcher token = ASCII_TOKEN.matcher(question);
    while (token.find()) features.add(token.group().toLowerCase(java.util.Locale.ROOT));
    int score = 0;
    for (String feature : features) {
      if (haystack.contains(feature)) score += feature.length() >= 3 ? 3 : 1;
    }
    return score;
  }

  private String groundedContext(List<Source> sources) {
    StringBuilder out = new StringBuilder();
    for (Source source : sources) {
      String block =
          "[SOURCE "
              + source.index
              + "]\nKnowledge type: "
              + source.resultType
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
        .filter(source -> used == null || used.contains(source.index))
        .map(source -> new Citation(source.index, source.resultType, source.evidence))
        .toList();
  }
}
