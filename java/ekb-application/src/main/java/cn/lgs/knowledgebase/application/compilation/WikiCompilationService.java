package cn.lgs.knowledgebase.application.compilation;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

public final class WikiCompilationService {
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^]\\n]+)]]");
    private static final Map<String, Template> TEMPLATES = templates();

    private final CompilationRepository repository;
    private final KnowledgeModel model;
    private final ObjectStore objects;
    private final EmbeddingPort embedding;
    private final DerivedSearchIndex derived;
    private final Clock clock;

    public WikiCompilationService(CompilationRepository repository, KnowledgeModel model, ObjectStore objects,
                                  EmbeddingPort embedding, DerivedSearchIndex derived) {
        this(repository, model, objects, embedding, derived, Clock.systemUTC());
    }

    WikiCompilationService(CompilationRepository repository, KnowledgeModel model, ObjectStore objects,
                           EmbeddingPort embedding, DerivedSearchIndex derived, Clock clock) {
        this.repository = require(repository, "compilation repository");
        this.model = require(model, "knowledge model");
        this.objects = require(objects, "object store");
        this.embedding = require(embedding, "embedding");
        this.derived = require(derived, "derived search index");
        this.clock = require(clock, "clock");
        String writer = trim(model.identity(ModelRole.LARGE));
        String judge = trim(model.identity(ModelRole.SMALL));
        if (writer.isEmpty() || judge.isEmpty()) throw new IllegalArgumentException("wiki writer and judge identities are required");
        if (writer.equalsIgnoreCase(judge)) throw new IllegalArgumentException("wiki writer and judge must be independent");
    }

    public CompileResult compile(CompileRequest request) {
        if (request == null) throw new IllegalArgumentException("wiki compile request is required");
        String wikiType = normalizeType(request.wikiType());
        Template template = TEMPLATES.get(wikiType);
        if (template == null) throw new IllegalArgumentException("unsupported wiki type " + request.wikiType());
        String title = trim(request.title());
        if (title.isEmpty() || request.summaryIds() == null || request.summaryIds().isEmpty()) {
            throw new IllegalArgumentException("wiki title and source summaries are required");
        }
        List<String> summaryIds = request.summaryIds().stream().map(WikiCompilationService::trim).filter(v -> !v.isEmpty()).distinct().toList();
        List<SummaryRecord> summaries = repository.loadSummaries(summaryIds);
        Map<String, SummaryFact> facts = sourceFacts(summaries);
        if (facts.isEmpty()) throw new IllegalStateException("wiki source contains no grounded active facts");

        Proposal proposal = model.generateStructured(ModelRole.LARGE, ModelTask.WIKI_WRITE,
                writerPrompt(template), sourcePrompt(summaries, facts), Proposal.class);
        if (proposal == null) throw new IllegalStateException("wiki writer returned empty proposal");
        if (!trim(proposal.wikiType()).isEmpty() && !normalizeType(proposal.wikiType()).equals(wikiType)) {
            throw new IllegalStateException("wiki proposal type mismatch");
        }
        DecisionValidation decisionValidation = validateDecisions(facts, proposal.factDecisions());
        String wikiId = GraphCompilationService.stableId("wiki", wikiType, title.toLowerCase(Locale.ROOT), trim(request.canonicalSubjectId()));
        long version = repository.nextWikiVersion(wikiId);
        GroundingResult grounded = groundClaims(wikiId, version, template, safe(proposal.claims()), facts, decisionValidation.decisions());

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, FactDecision> entry : decisionValidation.decisions().entrySet()) {
            if (entry.getValue().wikiEligible() && !grounded.coveredFacts().contains(entry.getKey())) missing.add(entry.getKey());
        }
        missing.sort(String::compareTo);
        CoverageResult coverage = new CoverageResult(missing.isEmpty(), decisionValidation.eligibleFacts(),
                decisionValidation.eligibleFacts() - missing.size(), decisionValidation.skippedFacts(), List.copyOf(missing));
        if (!coverage.pass()) throw new IllegalStateException("wiki coverage failed: " + String.join(",", missing));

        List<String> keywords = extractWikiLinks(grounded.claims());
        String markdown = render(template, title, grounded.claims());
        String objectKey = "wiki/" + wikiId + "/v" + version + ".md";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        objects.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
        WikiRecord record = new WikiRecord(wikiId, version, wikiType, trim(request.canonicalSubjectId()), title,
                objectKey, sha256(markdown), "ACTIVE", markdown, grounded.claims(), keywords, keywords, clock.instant());
        repository.saveWiki(record);

        List<Claim> claims = grounded.claims().stream().map(c -> new Claim(c.claimText(), c.evidenceIds(), 1.0)).toList();
        WikiPage page = new WikiPage(wikiId, title, markdown, keywords, keywords, claims, ArtifactStatus.ACTIVE, clock.instant());
        List<List<Float>> vectors = embedding.embed(List.of(markdown));
        if (vectors.size() != 1) throw new IllegalStateException("wiki embedding response mismatch");
        derived.indexWiki(page, repository.derivedContextForSummaries(summaryIds), vectors.get(0));
        return new CompileResult(record, coverage, model.identity(ModelRole.LARGE), model.identity(ModelRole.SMALL));
    }

    private GroundingResult groundClaims(String wikiId, long version, Template template, List<ProposedClaim> proposed,
                                         Map<String, SummaryFact> facts, Map<String, FactDecision> decisions) {
        List<WikiClaim> claims = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (ProposedClaim raw : proposed) {
            if (raw == null) throw new IllegalStateException("wiki claim is null");
            String section = trim(raw.section());
            String text = trim(raw.claimText());
            List<String> factIds = raw.supportingFactIds() == null ? List.of()
                    : raw.supportingFactIds().stream().map(WikiCompilationService::trim).filter(v -> !v.isEmpty()).distinct().toList();
            if (text.isEmpty() || !template.sectionNames().contains(section) || factIds.isEmpty()) {
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
                if (fact.evidenceIds().isEmpty()) throw new IllegalStateException("wiki claim fact has no raw evidence " + factId);
                support.add(fact);
                evidenceIds.addAll(fact.evidenceIds());
            }
            SupportDecision decision = model.generateStructured(ModelRole.SMALL, ModelTask.WIKI_JUDGE,
                    "你是独立 Wiki Claim Grounding Judge。只判断 claim 是否被给定的 grounded summary facts 直接支持。禁止使用常识补全；输出 supported 和 reason。",
                    "CLAIM=" + text + "\nSUPPORTING FACTS:\n" + renderFactSupport(support), SupportDecision.class);
            if (decision == null || !decision.supported()) throw new IllegalStateException("wiki claim is not grounded: " + trim(decision == null ? "" : decision.reason()));
            String claimId = GraphCompilationService.stableId("wiki-claim", wikiId, Long.toString(version), section, text, String.join(",", factIds));
            claims.add(new WikiClaim(claimId, wikiId, version, section, text, factIds, List.copyOf(evidenceIds), "PASS", "PASS"));
            covered.addAll(factIds);
        }
        return new GroundingResult(List.copyOf(claims), Set.copyOf(covered));
    }

    private static DecisionValidation validateDecisions(Map<String, SummaryFact> facts, List<FactDecision> input) {
        Map<String, FactDecision> decisions = new LinkedHashMap<>();
        int eligible = 0, skipped = 0;
        for (FactDecision raw : safe(input)) {
            String factId = trim(raw.factId());
            if (!facts.containsKey(factId)) throw new IllegalStateException("wiki decision references unknown fact " + factId);
            if (decisions.containsKey(factId)) throw new IllegalStateException("wiki decision duplicated for fact " + factId);
            if (!raw.wikiEligible() && trim(raw.skipReason()).isEmpty()) throw new IllegalStateException("wiki skip reason required for fact " + factId);
            FactDecision normalized = new FactDecision(factId, raw.wikiEligible(), trim(raw.skipReason()));
            decisions.put(factId, normalized);
            if (normalized.wikiEligible()) eligible++; else skipped++;
        }
        for (String factId : facts.keySet()) {
            if (!decisions.containsKey(factId)) throw new IllegalStateException("wiki decision missing for fact " + factId);
        }
        return new DecisionValidation(Map.copyOf(decisions), eligible, skipped);
    }

    private static Map<String, SummaryFact> sourceFacts(List<SummaryRecord> summaries) {
        Map<String, SummaryFact> facts = new LinkedHashMap<>();
        for (SummaryRecord summary : summaries == null ? List.<SummaryRecord>of() : summaries) {
            if (!summary.activeAndGrounded()) throw new IllegalStateException("wiki source summary is not ACTIVE with grounding+coverage PASS: " + summary.id());
            for (SummaryFact fact : summary.facts()) {
                if ("PASS".equals(fact.groundingStatus()) && !trim(fact.id()).isEmpty()) facts.put(fact.id(), fact);
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

    private static String render(Template template, String title, List<WikiClaim> claims) {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        for (String section : template.sections()) {
            out.append("## ").append(section).append("\n\n");
            List<WikiClaim> sectionClaims = claims.stream().filter(c -> c.section().equals(section)).sorted(Comparator.comparing(WikiClaim::id)).toList();
            if (sectionClaims.isEmpty()) out.append("- 未从已验证事实中确认。\n\n");
            else {
                for (WikiClaim claim : sectionClaims) out.append("- ").append(claim.claimText()).append("\n");
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String writerPrompt(Template template) {
        return "你是企业 Wiki Writer。仅基于输入的 grounded facts 编写候选 claims。wiki_type 必须为 " + template.wikiType()
                + "，section 只能来自 " + template.sections() + "。每条 claim 必须引用 supporting_fact_ids。每个输入 fact 必须输出一个 fact_decision；"
                + "适合 Wiki 的 fact 标记 wiki_eligible=true 且最终必须至少被一个 claim 使用；不适合的必须给 skip_reason。"
                + "Claim 必须原子化：一条 claim 只表达一个可独立验证的事实，不要把多个关系或多个时间事件拼在同一句。"
                + "若 claim 表达两个实体之间的关系，应恰好使用两个 [[实体名]]，并把明确的关系词放在两个 wikilink 之间，例如 [[A]] 控制 [[B]]、[[A]] 投资 [[B]]、[[A]] supplies to [[B]]。"
                + "一个 grounded fact 若包含多个二元关系，可以拆成多条 claims，并复用同一个 supporting_fact_id。不得为了造链接或关系而改写事实。";
    }

    private static String sourcePrompt(List<SummaryRecord> summaries, Map<String, SummaryFact> facts) {
        StringBuilder out = new StringBuilder();
        for (SummaryRecord summary : summaries) out.append("<summary id=\"").append(summary.id()).append("\">\n").append(summary.markdown()).append("\n</summary>\n");
        out.append("<facts>\n");
        for (SummaryFact fact : facts.values()) out.append("<fact id=\"").append(fact.id()).append("\" section=\"").append(fact.section()).append("\">\n")
                .append(fact.factText()).append("\n</fact>\n");
        out.append("</facts>");
        return out.toString();
    }

    private static String renderFactSupport(List<SummaryFact> facts) {
        StringBuilder out = new StringBuilder();
        for (SummaryFact fact : facts) out.append('[').append(fact.id()).append("] ").append(fact.factText()).append('\n');
        return out.toString();
    }

    private static Map<String, Template> templates() {
        Map<String, Template> map = new LinkedHashMap<>();
        map.put("COMPANY", new Template("COMPANY", List.of("Profile", "Ownership and Control", "Governance", "Material Matters")));
        map.put("PERSON", new Template("PERSON", List.of("Identity and Roles", "Ownership and Control", "Material Matters")));
        map.put("CONTRACT", new Template("CONTRACT", List.of("Overview", "Parties", "Key Terms", "Performance and Risks")));
        map.put("LEGAL_CASE", new Template("LEGAL_CASE", List.of("Overview", "Parties", "Claims and Issues", "Decision and Progress")));
        map.put("PROJECT", new Template("PROJECT", List.of("Overview", "Key Relationships", "Material Matters", "Risks")));
        map.put("CONTROL_TOPIC", new Template("CONTROL_TOPIC", List.of("Overview", "Key Relationships", "Timeline")));
        map.put("EQUITY_TOPIC", new Template("EQUITY_TOPIC", List.of("Overview", "Key Relationships", "Timeline")));
        map.put("GENERIC_TOPIC", new Template("GENERIC_TOPIC", List.of("Overview", "Key Relationships", "Timeline")));
        map.put("GENERIC_ENTITY", new Template("GENERIC_ENTITY", List.of("Overview", "Relationships", "Material Matters")));
        return Map.copyOf(map);
    }

    private static String normalizeType(String value) { return trim(value).toUpperCase(Locale.ROOT); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }

    private record Template(String wikiType, List<String> sections) { Set<String> sectionNames() { return Set.copyOf(sections); } }
    private record DecisionValidation(Map<String, FactDecision> decisions, int eligibleFacts, int skippedFacts) {}
    private record GroundingResult(List<WikiClaim> claims, Set<String> coveredFacts) {}

    public record CompileRequest(String wikiType, String title, String canonicalSubjectId, List<String> summaryIds) {}
    public record ProposedClaim(String section, String claimText, List<String> supportingFactIds) {}
    public record FactDecision(String factId, boolean wikiEligible, String skipReason) {}
    public record Proposal(String wikiType, String title, List<ProposedClaim> claims, List<FactDecision> factDecisions) {}
    public record SupportDecision(boolean supported, String reason) {}
    public record CoverageResult(boolean pass, int eligibleFacts, int coveredFacts, int skippedFacts, List<String> missingFacts) {}
    public record CompileResult(WikiRecord wiki, CoverageResult coverage, String writer, String judge) {}
}
