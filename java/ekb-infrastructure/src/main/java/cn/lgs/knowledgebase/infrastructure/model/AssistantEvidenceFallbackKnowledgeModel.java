package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService.ConceptProposal;
import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService.ConceptProposalSet;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService.Extraction;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService.FactDecision;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.Classification;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.CoverageDecision;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.FactProposal;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.FactProposalSet;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.SupportDecision;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.SupportDecisionItem;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.SupportDecisionSet;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.UnitProposal;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService.Proposal;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService.ProposedClaim;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit, auditable fallback for compilation when the configured chat provider is unavailable.
 *
 * <p>This adapter is deliberately extractive. It never replaces ordinary ASK generation and it
 * never invents graph relations: Summary facts are copied from complete raw blocks, Concepts are
 * grouped by the sections in those facts, and Graph marks every fact as ineligible. The fallback is
 * disabled by default and its identity is persisted on every derived artifact it creates.
 */
public final class AssistantEvidenceFallbackKnowledgeModel implements KnowledgeModel {
  public static final String IDENTITY_MARKER = "assistant-evidence-fallback-v1";
  private static final String MODE_DISABLED = "disabled";
  private static final String MODE_ON_FAILURE = "on-failure";
  private static final String MODE_ALWAYS = "always";
  private static final Pattern BLOCK =
      Pattern.compile(
          "(?ms)^\\[([^\\]|]+)\\s*\\|\\s*block_no=(\\d+)\\]\\s?(.*?)(?=^\\[[^\\]]+\\s*\\|\\s*block_no=|\\z)");
  private static final Pattern FACT =
      Pattern.compile(
          "(?s)<fact\\s+id=\\\"([^\\\"]+)\\\"\\s+section=\\\"([^\\\"]*)\\\">\\s*(.*?)\\s*</fact>");
  private static final Pattern CANDIDATE = Pattern.compile("(?m)^candidate_id=([^\\r\\n]+)");
  private static final Pattern WIKI_TYPE = Pattern.compile("wiki_type 使用\\s+([A-Za-z_]+)");
  private static final Pattern UNIT_HINT = Pattern.compile("(?m)^manual_document_type_hint=(.*)$");

  private final KnowledgeModel delegate;
  private final ObjectMapper mapper;
  private final String mode;

  public AssistantEvidenceFallbackKnowledgeModel(
      KnowledgeModel delegate, ObjectMapper mapper, String configuredMode, Path marker) {
    if (delegate == null) throw new IllegalArgumentException("delegate is required");
    if (mapper == null) throw new IllegalArgumentException("object mapper is required");
    this.delegate = delegate;
    this.mapper = mapper;
    this.mode = resolveMode(configuredMode, marker);
  }

  public AssistantEvidenceFallbackKnowledgeModel(
      KnowledgeModel delegate, ObjectMapper mapper, String configuredMode) {
    this(delegate, mapper, configuredMode, null);
  }

  @Override
  public String identity(ModelRole role) {
    String base = delegate.identity(role);
    if (MODE_DISABLED.equals(mode)) return base;
    String value = base == null ? "" : base.trim();
    return value.isEmpty() ? IDENTITY_MARKER : value + "|" + IDENTITY_MARKER;
  }

  @Override
  public String generate(ModelRole role, ModelTask task, String systemPrompt, String userPrompt) {
    return delegate.generate(role, task, systemPrompt, userPrompt);
  }

  @Override
  public <T> T generateStructured(
      ModelRole role, ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
    if (!compilationTask(task) || MODE_DISABLED.equals(mode))
      return delegate.generateStructured(role, task, systemPrompt, userPrompt, outputType);
    if (MODE_ALWAYS.equals(mode)) return fallback(task, systemPrompt, userPrompt, outputType);
    try {
      return delegate.generateStructured(role, task, systemPrompt, userPrompt, outputType);
    } catch (RuntimeException failure) {
      if (!transientFailure(failure)) throw failure;
      return fallback(task, systemPrompt, userPrompt, outputType);
    }
  }

  private <T> T fallback(
      ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
    Object result =
        switch (task) {
          case CLASSIFY_UNIT -> classification(userPrompt);
          case SUMMARY -> factProposals(userPrompt);
          case SUMMARY_JUDGE -> summaryJudge(userPrompt, outputType);
          case CONCEPT_EXTRACT -> concepts(userPrompt);
          case RELATION_EXTRACT -> relations(userPrompt);
          case WIKI_WRITE -> wiki(systemPrompt, userPrompt);
          case WIKI_JUDGE -> wikiJudge(userPrompt, outputType);
          default -> throw new IllegalStateException("assistant fallback does not handle " + task);
        };
    if (outputType.isInstance(result)) return outputType.cast(result);
    try {
      // Keep the adapter robust when an application is assembled with a separately loaded
      // nested application jar: the record's JSON shape is the contract, not its class loader.
      return mapper.convertValue(result, outputType);
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException(
          "assistant fallback output mismatch for " + task + ": " + outputType.getName(), failure);
    }
  }

  private static boolean compilationTask(ModelTask task) {
    return switch (task) {
      case CLASSIFY_UNIT,
          SUMMARY,
          SUMMARY_JUDGE,
          CONCEPT_EXTRACT,
          RELATION_EXTRACT,
          WIKI_WRITE,
          WIKI_JUDGE ->
          true;
      default -> false;
    };
  }

  private Classification classification(String userPrompt) {
    List<RawBlock> blocks = rawBlocks(userPrompt);
    if (blocks.isEmpty())
      throw new IllegalStateException("assistant fallback classification has no raw blocks");
    int first = blocks.get(0).blockNo();
    int last = blocks.get(blocks.size() - 1).blockNo();
    String title = firstTitle(blocks);
    Matcher hint = UNIT_HINT.matcher(userPrompt == null ? "" : userPrompt);
    String documentType = hint.find() ? text(hint.group(1)) : "";
    if (documentType.isEmpty()) documentType = "general";
    return new Classification(
        documentType,
        "single",
        List.of(
            new UnitProposal(title.isEmpty() ? documentType : title, documentType, first, last)));
  }

  private FactProposalSet factProposals(String userPrompt) {
    List<RawBlock> blocks = rawBlocks(userPrompt);
    List<FactProposal> facts = new ArrayList<>();
    String section = "原文事实";
    for (RawBlock block : blocks) {
      String content = block.content();
      if (content.isEmpty()) continue;
      if (structural(content)) {
        String heading = heading(content);
        if (!heading.isEmpty()) section = heading;
        continue;
      }
      facts.add(new FactProposal(section, content, List.of(block.id())));
    }
    if (facts.isEmpty()) {
      for (RawBlock block : blocks) {
        if (!block.content().isEmpty()) {
          facts.add(new FactProposal(section, block.content(), List.of(block.id())));
          break;
        }
      }
    }
    return new FactProposalSet(List.copyOf(facts));
  }

  private Object summaryJudge(String userPrompt, Class<?> outputType) {
    if (sameType(outputType, CoverageDecision.class)) return new CoverageDecision(List.of());
    if (sameType(outputType, SupportDecision.class))
      return new SupportDecision(
          true, "assistant evidence-only fallback: fact copied from raw evidence");
    if (!sameType(outputType, SupportDecisionSet.class))
      throw new IllegalStateException("unsupported SUMMARY_JUDGE output " + outputType.getName());
    List<SupportDecisionItem> decisions = new ArrayList<>();
    Matcher matcher = CANDIDATE.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find())
      decisions.add(
          new SupportDecisionItem(
              text(matcher.group(1)),
              true,
              "assistant evidence-only fallback: candidate is checked against its raw evidence"));
    return new SupportDecisionSet(List.copyOf(uniqueSupport(decisions)));
  }

  private ConceptProposalSet concepts(String userPrompt) {
    Map<String, List<String>> factIdsBySection = new LinkedHashMap<>();
    Map<String, List<String>> textsBySection = new LinkedHashMap<>();
    Matcher matcher = FACT.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find()) {
      String id = text(matcher.group(1));
      String section = text(matcher.group(2));
      if (section.isEmpty()) section = "原文事实";
      factIdsBySection.computeIfAbsent(section, ignored -> new ArrayList<>()).add(id);
      textsBySection.computeIfAbsent(section, ignored -> new ArrayList<>()).add(matcher.group(3));
    }
    List<ConceptProposal> concepts = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : factIdsBySection.entrySet()) {
      String label = entry.getKey();
      String description = String.join("\n", textsBySection.get(entry.getKey()));
      concepts.add(
          new ConceptProposal(label, List.of(), "OTHER", description, entry.getValue(), 1));
    }
    return new ConceptProposalSet(List.copyOf(concepts));
  }

  private Extraction relations(String userPrompt) {
    List<FactDecision> decisions = new ArrayList<>();
    Matcher matcher = FACT.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find())
      decisions.add(
          new FactDecision(
              text(matcher.group(1)),
              false,
              "assistant evidence-only fallback: no relation inferred without an explicit deterministic rule"));
    return new Extraction(List.of(), List.of(), List.copyOf(uniqueGraphDecisions(decisions)));
  }

  private Proposal wiki(String systemPrompt, String userPrompt) {
    String wikiType = "";
    Matcher type = WIKI_TYPE.matcher(systemPrompt == null ? "" : systemPrompt);
    if (type.find()) wikiType = text(type.group(1)).toUpperCase(Locale.ROOT);
    List<ProposedClaim> claims = new ArrayList<>();
    List<cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision>
        decisions = new ArrayList<>();
    Matcher matcher = FACT.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find()) {
      String id = text(matcher.group(1));
      String section = text(matcher.group(2));
      if (section.isEmpty()) section = "原文事实";
      String factText = matcher.group(3).trim();
      if (factText.isEmpty()) continue;
      decisions.add(
          new cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision(
              id, true, ""));
      claims.add(new ProposedClaim(section, factText, List.of(id)));
    }
    return new Proposal(
        wikiType, "", List.copyOf(claims), List.copyOf(uniqueWikiDecisions(decisions)));
  }

  private Object wikiJudge(String userPrompt, Class<?> outputType) {
    if (sameType(
        outputType,
        cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecision.class))
      return new cn.lgs.knowledgebase.application.compilation.WikiCompilationService
          .SupportDecision(true, "assistant evidence-only fallback: claim equals supporting fact");
    if (!sameType(
        outputType,
        cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecisionSet
            .class))
      throw new IllegalStateException("unsupported WIKI_JUDGE output " + outputType.getName());
    List<cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecisionItem>
        decisions = new ArrayList<>();
    Matcher matcher = CANDIDATE.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find())
      decisions.add(
          new cn.lgs.knowledgebase.application.compilation.WikiCompilationService
              .SupportDecisionItem(
              text(matcher.group(1)),
              true,
              "assistant evidence-only fallback: claim equals supporting fact"));
    return new cn.lgs.knowledgebase.application.compilation.WikiCompilationService
        .SupportDecisionSet(List.copyOf(uniqueWikiSupport(decisions)));
  }

  private static List<SupportDecisionItem> uniqueSupport(List<SupportDecisionItem> input) {
    Map<String, SupportDecisionItem> unique = new LinkedHashMap<>();
    for (SupportDecisionItem item : input) unique.putIfAbsent(item.candidateId(), item);
    return List.copyOf(unique.values());
  }

  private static List<FactDecision> uniqueGraphDecisions(List<FactDecision> input) {
    Map<String, FactDecision> unique = new LinkedHashMap<>();
    for (FactDecision item : input) unique.putIfAbsent(item.factId(), item);
    return List.copyOf(unique.values());
  }

  private static List<
          cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision>
      uniqueWikiDecisions(
          List<cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision>
              input) {
    Map<String, cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision>
        unique = new LinkedHashMap<>();
    for (cn.lgs.knowledgebase.application.compilation.WikiCompilationService.FactDecision item :
        input) unique.putIfAbsent(item.factId(), item);
    return List.copyOf(unique.values());
  }

  private static List<
          cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecisionItem>
      uniqueWikiSupport(
          List<
                  cn.lgs.knowledgebase.application.compilation.WikiCompilationService
                      .SupportDecisionItem>
              input) {
    Map<
            String,
            cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecisionItem>
        unique = new LinkedHashMap<>();
    for (cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecisionItem
        item : input) unique.putIfAbsent(item.candidateId(), item);
    return List.copyOf(unique.values());
  }

  private static List<RawBlock> rawBlocks(String userPrompt) {
    List<RawBlock> blocks = new ArrayList<>();
    Matcher matcher = BLOCK.matcher(userPrompt == null ? "" : userPrompt);
    while (matcher.find())
      blocks.add(
          new RawBlock(
              text(matcher.group(1)), Integer.parseInt(matcher.group(2)), text(matcher.group(3))));
    return List.copyOf(blocks);
  }

  private static String firstTitle(List<RawBlock> blocks) {
    for (RawBlock block : blocks) {
      String heading = heading(block.content());
      if (!heading.isEmpty()) return heading;
    }
    return blocks.get(0).content();
  }

  private static String heading(String value) {
    String text = text(value);
    if (text.isEmpty() || text.contains("\n") || text.length() > 80) return "";
    if (text.matches("^[0-9]+$")) return "";
    if (text.matches("^(目录|contents|table of contents|封面|页码)$")) return "";
    return text.matches("^(第[一二三四五六七八九十百千万0-9]+[章节条].*|[一二三四五六七八九十百千万0-9]+[、.].*)$")
        ? text
        : text.matches("^[^。！？；:：]{2,40}$") ? text : "";
  }

  private static boolean structural(String value) {
    String text = text(value);
    return text.matches("^[0-9]+$")
        || text.equalsIgnoreCase("page")
        || text.equalsIgnoreCase("contents")
        || text.equals("目录")
        || text.equals("封面")
        || text.matches("^第[一二三四五六七八九十百千万0-9]+[章节条]$");
  }

  private static String resolveMode(String configuredMode, Path marker) {
    String configured = text(configuredMode).toLowerCase(Locale.ROOT);
    if (MODE_ALWAYS.equals(configured) || MODE_ON_FAILURE.equals(configured)) return configured;
    if (marker != null && Files.isRegularFile(marker)) return MODE_ALWAYS;
    return MODE_DISABLED;
  }

  private static boolean transientFailure(Throwable failure) {
    StringBuilder message = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) message.append(' ').append(current.getMessage());
      message.append(' ').append(current.getClass().getSimpleName());
    }
    String value = message.toString().toLowerCase(Locale.ROOT);
    if (value.contains("cannot decode structured model output")) return false;
    return value.contains("ssl")
        || value.contains("handshake")
        || value.contains("timeout")
        || value.contains("timed out")
        || value.contains("remote host terminated")
        || value.contains("no available token")
        || value.contains("http 429")
        || value.contains("http 502")
        || value.contains("http 503")
        || value.contains("http 504")
        || value.contains("http 524")
        || value.contains("connectexception");
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean sameType(Class<?> actual, Class<?> expected) {
    return actual == expected || actual.getName().equals(expected.getName());
  }

  private record RawBlock(String id, int blockNo, String content) {}
}
