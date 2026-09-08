package cn.lgs.knowledgebase.application.query;

import cn.lgs.knowledgebase.application.raw.RawTokenCounter;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Whole retrieved chunks own the budget; their original paragraphs retain exact citations. */
public final class QueryEvidenceContext {
  public static final int MAX_PASSAGES = 8;

  /** Compatibility name for the original retrieval-passage limit, not a parser-paragraph cap. */
  @Deprecated public static final int MAX_SOURCES = MAX_PASSAGES;

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
    return select(question, List.of(), results, maxChars);
  }

  /** Supplementary retrieval must affect selection without replacing the original question. */
  public Selection select(
      String question, List<String> focusQueries, List<Result> results, int maxChars) {
    if (maxChars <= 0)
      throw new IllegalArgumentException("context character budget must be positive");
    List<RankedPassage> ranked = new ArrayList<>();
    // Exact mixed letter/digit references are a relevance signal, never an authority or a hard
    // scope filter. Share that signal only among evidence of the same immutable source version.
    // Generic clause vocabulary must not crowd the explicitly requested object's evidence out.
    Set<String> requestedReferences = objectReferences(question);
    Set<String> referencedVersions = new LinkedHashSet<>();
    Set<String> relatedReferences = new LinkedHashSet<>();
    Map<String, Set<String>> versionsByReference = new LinkedHashMap<>();
    boolean followup =
        focusQueries.stream().anyMatch(focus -> focus != null && !focus.equals(question));
    if (results != null && !requestedReferences.isEmpty())
      for (Result result : results)
        for (Evidence evidence : result.evidence()) {
          Set<String> references = objectReferences(evidence.content());
          if (followup)
            for (String reference : references)
              versionsByReference
                  .computeIfAbsent(reference, ignored -> new LinkedHashSet<>())
                  .add(evidence.versionId());
          if (references.stream().anyMatch(requestedReferences::contains)) {
            referencedVersions.add(evidence.versionId());
            // Follow a single pair in original evidence. A list of unrelated IDs cannot
            // make every listed source a prerequisite, and no second hop is inferred.
            if (followup && references.size() == 2) relatedReferences.addAll(references);
          }
        }
    relatedReferences.removeAll(requestedReferences);
    Set<String> coverageVersions = new LinkedHashSet<>(referencedVersions);
    for (String reference : relatedReferences)
      coverageVersions.addAll(versionsByReference.get(reference));
    for (PassageUnit unit : passages(results)) {
      String content =
          unit.evidence().stream()
              .map(Evidence::content)
              .collect(java.util.stream.Collectors.joining("\n"));
      int focusRelevance =
          focusQueries.stream()
              .filter(focus -> focus != null && !focus.equals(question))
              .mapToInt(focus -> lexicalRelevance(focus, content))
              .max()
              .orElse(0);
      int questionRelevance = lexicalRelevance(question, content);
      ranked.add(
          new RankedPassage(
              unit,
              ranked.size(),
              referencedVersions.contains(unit.evidence().get(0).versionId()),
              objectReferences(content).stream().anyMatch(requestedReferences::contains),
              questionRelevance + focusRelevance,
              focusRelevance,
              questionRelevance));
    }
    if (!referencedVersions.isEmpty()
        || ranked.stream().anyMatch(source -> source.relevance() > 0)) {
      ranked.sort(
          Comparator.comparing(RankedPassage::requestedVersion)
              .reversed()
              // A later round explicitly names a missing clause. Within the requested source
              // versions that focus must outrank repeated identity-only paragraphs, otherwise
              // the same eight anchors can exclude the newly retrieved answer indefinitely.
              .thenComparing(Comparator.comparingInt(RankedPassage::focusRelevance).reversed())
              .thenComparing(Comparator.comparing(RankedPassage::requestedReference).reversed())
              .thenComparing(Comparator.comparingInt(RankedPassage::relevance).reversed())
              .thenComparingInt(RankedPassage::order));
    }
    // Keep the strongest new focus first, then reserve at most half the passage slots for
    // distinct source versions that answer the original question. A later follow-up must not
    // evict every already-found prerequisite merely because its wording differs. This is a
    // fresh selection from current authorized results, never a cache of old model assertions.
    Map<String, RankedPassage> coverageOrder = new LinkedHashMap<>();
    if (!ranked.isEmpty()) coverageOrder.put(ranked.get(0).unit().key(), ranked.get(0));
    Set<String> coveredVersions = new LinkedHashSet<>();
    ranked.stream()
        .filter(item -> item.questionRelevance() > 0)
        .filter(
            item ->
                coverageVersions.isEmpty()
                    || coverageVersions.contains(item.unit().evidence().get(0).versionId()))
        .sorted(
            Comparator.comparing(RankedPassage::requestedVersion)
                .reversed()
                .thenComparing(Comparator.comparingInt(RankedPassage::questionRelevance).reversed())
                .thenComparingInt(RankedPassage::order))
        .filter(item -> coveredVersions.add(item.unit().evidence().get(0).versionId()))
        .limit(MAX_PASSAGES / 2)
        .forEach(item -> coverageOrder.putIfAbsent(item.unit().key(), item));
    ranked.forEach(item -> coverageOrder.putIfAbsent(item.unit().key(), item));
    List<Source> selected = new ArrayList<>();
    List<Passage> selectedPassages = new ArrayList<>();
    Set<String> selectedIds = new LinkedHashSet<>();
    StringBuilder rendered = new StringBuilder();
    int bodyTokens = 0;
    for (RankedPassage rankedPassage : coverageOrder.values()) {
      if (selectedPassages.size() == MAX_PASSAGES) break;
      PassageUnit unit = rankedPassage.unit();
      List<Source> additions = new ArrayList<>();
      int count = 0;
      for (Evidence evidence : unit.evidence()) {
        if (selectedIds.contains(evidence.id())) continue;
        int itemTokens = tokens.count(evidence.content());
        if (itemTokens < 0)
          throw new IllegalStateException("token counter returned a negative count");
        count = Math.addExact(count, itemTokens);
        additions.add(
            new Source(selected.size() + additions.size() + 1, unit.resultType(), evidence));
      }
      if (additions.isEmpty()) continue;
      if (count > MAX_BODY_TOKENS - bodyTokens) continue;
      String block = renderSources(additions);
      if (block.length() > maxChars - rendered.length()) continue;
      selected.addAll(additions);
      additions.forEach(source -> selectedIds.add(source.evidence().id()));
      selectedPassages.add(
          new Passage(
              unit.key(), unit.kind(), unit.evidence().stream().map(Evidence::id).toList()));
      rendered.append(block);
      bodyTokens += count;
    }
    return new Selection(
        List.copyOf(selected),
        rendered.toString(),
        bodyTokens,
        List.copyOf(selectedPassages),
        tokens.identity(),
        maxChars);
  }

  public record Source(int index, String resultType, Evidence evidence) {}

  public record Passage(String retrievalId, String kind, List<String> evidenceIds) {
    public Passage {
      evidenceIds = List.copyOf(evidenceIds);
    }
  }

  public record SourceBinding(
      int index,
      String evidenceId,
      String documentId,
      String versionId,
      String contentHash,
      String resultType) {
    public SourceBinding(
        int index, String evidenceId, String documentId, String versionId, String contentHash) {
      this(index, evidenceId, documentId, versionId, contentHash, "legacy-unknown");
    }
  }

  /** Metadata only: no document text, prompt, credential or answer belongs in this receipt. */
  public record Snapshot(
      String policy,
      String contextSha256,
      String tokenizerIdentity,
      int bodyTokens,
      int maxCharacters,
      int renderedCharacters,
      List<Passage> passages,
      List<SourceBinding> sources) {
    public Snapshot {
      passages = List.copyOf(passages);
      sources = List.copyOf(sources);
    }

    public void validate() {
      if (!"raw-chunk-context-v1".equals(policy)
          || contextSha256 == null
          || !contextSha256.matches("[a-f0-9]{64}")
          || tokenizerIdentity == null
          || tokenizerIdentity.isBlank()
          || bodyTokens < 0
          || bodyTokens > MAX_BODY_TOKENS
          || maxCharacters < 1
          || renderedCharacters < 0
          || renderedCharacters > maxCharacters
          || passages.size() > MAX_PASSAGES)
        throw new IllegalArgumentException("invalid evidence context receipt budget or policy");
      Map<String, SourceBinding> byId = new LinkedHashMap<>();
      for (int index = 0; index < sources.size(); index++) {
        SourceBinding source = sources.get(index);
        if (source.index() != index + 1 || byId.putIfAbsent(source.evidenceId(), source) != null)
          throw new IllegalArgumentException("duplicate or noncanonical context source");
      }
      Set<String> accounted = new LinkedHashSet<>();
      for (Passage passage : passages) {
        if (!Set.of("RAW_CHUNK", "RAW_FRAGMENT").contains(passage.kind())
            || passage.evidenceIds().isEmpty()
            || !byId.keySet().containsAll(passage.evidenceIds()))
          throw new IllegalArgumentException("context passage contains an unbound Raw source");
        SourceBinding first = byId.get(passage.evidenceIds().get(0));
        if (passage.evidenceIds().stream()
                .map(byId::get)
                .anyMatch(
                    s ->
                        !s.versionId().equals(first.versionId())
                            || !s.documentId().equals(first.documentId()))
            || (passage.kind().equals("RAW_FRAGMENT") && passage.evidenceIds().size() != 1))
          throw new IllegalArgumentException("context passage merged unrelated source versions");
        accounted.addAll(passage.evidenceIds());
      }
      if (!accounted.equals(byId.keySet()))
        throw new IllegalArgumentException("context has unaccounted Raw sources");
    }
  }

  public record Selection(
      List<Source> sources,
      String rendered,
      int bodyTokens,
      List<Passage> passages,
      String tokenizerIdentity,
      int maxCharacters) {
    public Selection(List<Source> sources, String rendered, int bodyTokens) {
      this(
          sources,
          rendered,
          bodyTokens,
          sources.stream()
              .map(
                  s ->
                      new Passage(
                          "fragment:" + s.evidence().id(),
                          "RAW_FRAGMENT",
                          List.of(s.evidence().id())))
              .toList(),
          "legacy-caller",
          Math.max(1, rendered.length()));
    }

    public Snapshot snapshot() {
      return new Snapshot(
          "raw-chunk-context-v1",
          KnowledgeDomain.sha256(rendered),
          tokenizerIdentity,
          bodyTokens,
          maxCharacters,
          rendered.length(),
          passages,
          sources.stream()
              .map(
                  s ->
                      new SourceBinding(
                          s.index(),
                          s.evidence().id(),
                          s.evidence().documentId(),
                          s.evidence().versionId(),
                          s.evidence().hash(),
                          s.resultType()))
              .toList());
    }
  }

  /** Shared citation protocol; verification may include only the actually cited subset. */
  public static String renderSources(List<Source> sources) {
    return sources.stream()
        .map(QueryEvidenceContext::render)
        .collect(java.util.stream.Collectors.joining());
  }

  private record PassageUnit(String key, String kind, List<Evidence> evidence, String resultType) {}

  private record RankedPassage(
      PassageUnit unit,
      int order,
      boolean requestedVersion,
      boolean requestedReference,
      int relevance,
      int focusRelevance,
      int questionRelevance) {}

  private static List<PassageUnit> passages(List<Result> results) {
    if (results == null) return List.of();
    Map<String, Evidence> identities = new LinkedHashMap<>();
    Map<String, List<Evidence>> rawChunks = new LinkedHashMap<>();
    Map<String, String> rawMembership = new LinkedHashMap<>();
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        Evidence prior = identities.putIfAbsent(evidence.id(), evidence);
        if (prior != null && !prior.equals(evidence))
          throw new IllegalStateException("conflicting Raw evidence identity");
      }
      if (result.candidate().lane() != LaneType.RAW || result.evidence().isEmpty()) continue;
      String key = "chunk:" + result.candidate().id();
      for (Evidence evidence : result.evidence()) {
        if (!result.candidate().versionId().equals(evidence.versionId())
            || !result.candidate().documentId().equals(evidence.documentId()))
          throw new IllegalStateException("Raw chunk contains another source version");
        rawMembership.putIfAbsent(evidence.id(), key);
      }
      rawChunks.putIfAbsent(key, result.evidence());
    }
    Map<String, PassageUnit> units = new LinkedHashMap<>();
    for (Result result : results) {
      for (Evidence evidence : result.evidence()) {
        String key =
            result.candidate().lane() == LaneType.RAW
                ? "chunk:" + result.candidate().id()
                : rawMembership.getOrDefault(evidence.id(), "fragment:" + evidence.id());
        List<Evidence> content =
            rawChunks.getOrDefault(key, List.of(evidence)).stream()
                .filter(e -> e.content() != null && !e.content().isBlank())
                .distinct()
                .toList();
        if (!content.isEmpty())
          units.putIfAbsent(
              key,
              new PassageUnit(
                  key,
                  rawChunks.containsKey(key) ? "RAW_CHUNK" : "RAW_FRAGMENT",
                  content,
                  result.candidate().lane().name().toLowerCase(Locale.ROOT)));
      }
    }
    return List.copyOf(units.values());
  }

  private static Set<String> objectReferences(String text) {
    Set<String> result = new LinkedHashSet<>();
    if (text == null) return result;
    Matcher token = ASCII_TOKEN.matcher(text);
    while (token.find()) {
      String value = token.group().toLowerCase(Locale.ROOT);
      if (value.chars().anyMatch(Character::isLetter) && value.chars().anyMatch(Character::isDigit))
        result.add(value);
    }
    return result;
  }

  public static boolean containsRequestedReference(String question, String evidence) {
    Set<String> requested = objectReferences(question);
    return objectReferences(evidence).stream().anyMatch(requested::contains);
  }

  private static String render(Source source) {
    return "[SOURCE "
        + source.index()
        + "]\nRetrieval route: "
        + source.resultType()
        + "\nEvidence kind: RAW_SOURCE\nRaw evidence: "
        + source.evidence().content()
        + "\nEvidence id: "
        + source.evidence().id()
        + "\nDocument version: "
        + source.evidence().versionId()
        + "\nRaw location: "
        + canonicalLocation(source.evidence().location())
        + "\n\n";
  }

  /** Immutable Map iteration order varies between JVMs; receipts must survive process restarts. */
  private static Object canonicalLocation(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new java.util.TreeMap<>();
      map.forEach((key, item) -> sorted.put(key.toString(), canonicalLocation(item)));
      return sorted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(QueryEvidenceContext::canonicalLocation).toList();
    return value;
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
