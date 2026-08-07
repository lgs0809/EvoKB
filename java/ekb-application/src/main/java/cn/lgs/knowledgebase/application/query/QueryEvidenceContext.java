package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One whole-evidence budget and numbering policy for coverage judging and answer generation. */
public final class QueryEvidenceContext {
  public static final int MAX_SOURCES = 8;
  public static final int MAX_BODY_TOKENS = 6_000;
  private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
  private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,}");
  private final RawTokenCounter tokens;

  public QueryEvidenceContext(RawTokenCounter tokens) {
    if (tokens == null) throw new IllegalArgumentException("token counter is required");
    this.tokens = tokens;
  }

  /** Compatibility for standalone callers; production supplies the frozen offline tokenizer. */
  public static QueryEvidenceContext conservativeStandalone() {
    return new QueryEvidenceContext(
        new RawTokenCounter() {
          public int count(String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
          }

          public String identity() {
            return "utf8-byte-upper-bound-v1";
          }
        });
  }

  public Selection select(String question, List<Result> results, int maxChars) {
    if (maxChars <= 0)
      throw new IllegalArgumentException("context character budget must be positive");
    Set<String> seen = new LinkedHashSet<>();
    List<RankedSource> ranked = new ArrayList<>();
    if (results != null)
      for (Result result : results) {
        for (Evidence evidence : result.evidence()) {
          if (seen.add(evidence.id())) {
            ranked.add(
                new RankedSource(
                    result.candidate().lane().name().toLowerCase(Locale.ROOT),
                    evidence,
                    ranked.size(),
                    lexicalRelevance(question, evidence.content())));
          }
        }
      }
    if (ranked.stream().anyMatch(source -> source.relevance() > 0)) {
      ranked.sort(
          Comparator.comparingInt(RankedSource::relevance)
              .reversed()
              .thenComparingInt(RankedSource::order));
    }
    List<Source> selected = new ArrayList<>();
    StringBuilder rendered = new StringBuilder();
    int bodyTokens = 0;
    for (RankedSource source : ranked) {
      if (selected.size() == MAX_SOURCES) break;
      String content = source.evidence().content();
      if (content == null || content.isBlank()) continue;
      int count = tokens.count(content);
      if (count < 0) throw new IllegalStateException("token counter returned a negative count");
      if (count > MAX_BODY_TOKENS - bodyTokens) continue;
      Source candidate = new Source(selected.size() + 1, source.resultType(), source.evidence());
      String block = render(candidate);
      if (block.length() > maxChars - rendered.length()) continue;
      selected.add(candidate);
      rendered.append(block);
      bodyTokens += count;
    }
    return new Selection(List.copyOf(selected), rendered.toString(), bodyTokens);
  }

  public record Source(int index, String resultType, Evidence evidence) {}

  public record Selection(List<Source> sources, String rendered, int bodyTokens) {}

  private record RankedSource(String resultType, Evidence evidence, int order, int relevance) {}

  private static String render(Source source) {
    return "[SOURCE "
        + source.index()
        + "]\nKnowledge type: "
        + source.resultType()
        + "\nRaw evidence: "
        + source.evidence().content()
        + "\nEvidence id: "
        + source.evidence().id()
        + "\nDocument version: "
        + source.evidence().versionId()
        + "\nRaw location: "
        + source.evidence().location()
        + "\n\n";
  }

  private static int lexicalRelevance(String question, String content) {
    if (question == null || question.isBlank() || content == null || content.isBlank()) return 0;
    String haystack = content.toLowerCase(Locale.ROOT);
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
    while (token.find()) features.add(token.group().toLowerCase(Locale.ROOT));
    int score = 0;
    for (String feature : features) {
      if (haystack.contains(feature)) score += feature.length() >= 3 ? 3 : 1;
    }
    return score;
  }
}
