package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex;
import cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ArtifactStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.deterministicUuid;
import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

public final class SummaryCompilationService {
    public static final String GENERAL = "General / Mixed";
    private static final Set<String> STRUCTURES = Set.of("single", "bundle", "mixed");
    private static final Map<String, Template> TEMPLATES = templates();

    private final CompilationRepository repository;
    private final KnowledgeModel model;
    private final ObjectStore objects;
    private final EmbeddingPort embedding;
    private final DerivedSearchIndex derived;
    private final Clock clock;

    public SummaryCompilationService(CompilationRepository repository, KnowledgeModel model, ObjectStore objects,
                                     EmbeddingPort embedding, DerivedSearchIndex derived) {
        this(repository, model, objects, embedding, derived, Clock.systemUTC());
    }

    SummaryCompilationService(CompilationRepository repository, KnowledgeModel model, ObjectStore objects,
                              EmbeddingPort embedding, DerivedSearchIndex derived, Clock clock) {
        this.repository = require(repository, "compilation repository");
        this.model = require(model, "knowledge model");
        this.objects = require(objects, "object store");
        this.embedding = require(embedding, "embedding");
        this.derived = require(derived, "derived search index");
        this.clock = require(clock, "clock");
        if (model.identity(ModelRole.LARGE).equalsIgnoreCase(model.identity(ModelRole.SMALL))) {
            throw new IllegalArgumentException("summary generator and independent judge must be different model identities");
        }
    }

    public List<PreparedSummary> compileVersion(String versionId) {
        VersionSource source = repository.loadVersionSource(versionId);
        if (source.blocks().isEmpty() || source.evidenceByBlock().isEmpty()) {
            throw new IllegalStateException("active version has incomplete raw evidence: " + versionId);
        }
        Classification classification = model.generateStructured(ModelRole.LARGE, ModelTask.CLASSIFY_UNIT,
                classificationSystemPrompt(), classificationUserPrompt(source), Classification.class);
        List<DocumentUnit> units = normalizeClassification(source, classification);
        repository.replaceDocumentUnits(versionId, units);

        List<PreparedSummary> results = new ArrayList<>();
        for (DocumentUnit unit : units) {
            results.add(compileUnit(source, unit));
        }
        return List.copyOf(results);
    }

    private PreparedSummary compileUnit(VersionSource source, DocumentUnit unit) {
        Template template = TEMPLATES.getOrDefault(unit.documentType(), TEMPLATES.get(GENERAL));
        List<RawBlock> blocks = source.blocks().stream()
                .filter(block -> block.blockNo() >= unit.startBlockNo() && block.blockNo() <= unit.endBlockNo()).toList();
        if (blocks.isEmpty()) throw new IllegalStateException("summary unit has no raw blocks: " + unit.id());

        FactProposalSet first = extractFacts(template, unit, blocks, List.of(), List.of());
        List<SummaryFact> facts = groundFacts(unit, template, blocks, source.evidenceByBlock(), first.facts());
        CoverageDecision coverage = checkCoverage(template, unit, blocks, passing(facts));
        int repairs = 0;
        if (coverage.missing() != null && !coverage.missing().isEmpty()) {
            List<MissingFact> normalizedMissing = normalizeMissing(template, blocks, coverage.missing());
            if (!normalizedMissing.isEmpty()) {
                List<String> focus = normalizedMissing.stream().map(MissingFact::section).distinct().toList();
                FactProposalSet repair = extractFacts(template, unit, blocks, focus, normalizedMissing);
                List<SummaryFact> repairFacts = groundFacts(unit, template, blocks, source.evidenceByBlock(), repair.facts());
                repairs = repairFacts.size();
                facts = mergeFacts(facts, repairFacts);
                coverage = checkCoverage(template, unit, blocks, passing(facts));
                coverage = new CoverageDecision(normalizeMissing(template, blocks, coverage.missing()));
            }
        }

        boolean groundingPass = !facts.isEmpty() && facts.stream().allMatch(f -> "PASS".equals(f.groundingStatus()));
        boolean coveragePass = coverage.missing() == null || coverage.missing().isEmpty();
        String markdown = render(template, source.logicalName(), unit, facts);
        String summaryId = deterministicUuid("summary:" + unit.id() + ":" + model.identity(ModelRole.LARGE) + ":" + sha256(markdown));
        List<SummaryFact> withSummary = new ArrayList<>(facts.size());
        for (SummaryFact fact : facts) {
            String storedFactId = deterministicUuid("summary_fact:" + summaryId + ":" + fact.section() + ":" + normalize(fact.factText()));
            withSummary.add(new SummaryFact(storedFactId, summaryId, fact.section(), fact.factText(), fact.evidenceBlockIds(), fact.evidenceIds(),
                    fact.groundingStatus(), fact.groundingReason()));
        }
        String objectKey = "summary/" + source.documentId() + "/" + source.versionId() + "/" + unit.id() + "/" + summaryId + ".md";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        objects.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
        String lifecycle = groundingPass && coveragePass ? "ACTIVE" : "INACTIVE";
        SummaryRecord stored = new SummaryRecord(summaryId, unit.id(), source.documentId(), source.versionId(), objectKey, sha256(markdown),
                groundingPass ? "PASS" : "FAIL", coveragePass ? "PASS" : "FAIL", lifecycle,
                model.identity(ModelRole.LARGE), markdown, withSummary, clock.instant());
        repository.saveSummary(stored);
        if (stored.activeAndGrounded()) {
            List<Claim> claims = withSummary.stream().map(f -> new Claim(f.factText(), f.evidenceIds(), 1.0)).toList();
            SummaryArtifact artifact = new SummaryArtifact(summaryId, source.documentId(), source.versionId(), markdown, claims, ArtifactStatus.ACTIVE, clock.instant());
            List<List<Float>> vectors = embedding.embed(List.of(markdown));
            if (vectors.size() != 1) throw new IllegalStateException("summary embedding response mismatch");
            derived.indexSummary(artifact, new DerivedContext(List.of(source.documentId()), List.of(source.versionId()),
                    values(source.metadata().sourceDepartment()), values(source.metadata().materialCategory()), values(source.metadata().company()),
                    values(source.metadata().documentType()), source.metadata().manualTags()), vectors.get(0));
        }
        return new PreparedSummary(unit, stored, repairs);
    }

    private FactProposalSet extractFacts(Template template, DocumentUnit unit, List<RawBlock> blocks,
                                         List<String> focusSections, List<MissingFact> missing) {
        return model.generateStructured(ModelRole.LARGE, ModelTask.SUMMARY,
                "你是企业知识摘要事实提取器。只能从给定 RAW BLOCKS 提取事实。每条事实必须给出真实 evidence_block_ids，section 必须来自模板。不得推断或补写。",
                "document_type=" + unit.documentType() + "\nsections=" + template.sections() + "\nfocus=" + focusSections
                        + "\nmissing=" + missing + "\nRAW BLOCKS:\n" + renderBlocks(blocks), FactProposalSet.class);
    }

    private List<SummaryFact> groundFacts(DocumentUnit unit, Template template, List<RawBlock> blocks,
                                          Map<String, Evidence> evidenceByBlock, List<FactProposal> proposals) {
        Map<String, RawBlock> blockById = new LinkedHashMap<>();
        blocks.forEach(block -> blockById.put(block.id(), block));
        List<SummaryFact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (proposals == null) return List.of();
        for (FactProposal proposed : proposals) {
            String section = trim(proposed.section());
            String text = trim(proposed.factText());
            if (text.isEmpty() || !template.sectionNames().contains(section)) continue;
            String dedupe = section + "\u0000" + normalize(text);
            if (!seen.add(dedupe)) continue;
            List<String> blockIds = proposed.evidenceBlockIds() == null ? List.of() : proposed.evidenceBlockIds().stream().map(SummaryCompilationService::trim).distinct().toList();
            List<Evidence> evidence = new ArrayList<>();
            boolean valid = !blockIds.isEmpty();
            for (String blockId : blockIds) {
                if (!blockById.containsKey(blockId) || !evidenceByBlock.containsKey(blockId)) { valid = false; break; }
                evidence.add(evidenceByBlock.get(blockId));
            }
            boolean supported = false;
            String reason = valid ? "" : "unknown or missing evidence block";
            if (valid) {
                SupportDecision decision = model.generateStructured(ModelRole.SMALL, ModelTask.SUMMARY_JUDGE,
                        "你是独立事实 Grounding Judge。仅判断 FACT 是否被 RAW EVIDENCE 直接支持。严格输出 supported/reason；不允许因为常识而判定支持。",
                        "FACT: " + text + "\nEVIDENCE:\n" + evidence.stream().map(Evidence::content).reduce("", (a,b) -> a + "\n---\n" + b), SupportDecision.class);
                supported = decision.supported();
                reason = trim(decision.reason());
            }
            String factId = deterministicUuid("summary_fact:" + unit.id() + ":" + section + ":" + normalize(text));
            facts.add(new SummaryFact(factId, "", section, text, blockIds,
                    evidence.stream().map(Evidence::id).distinct().toList(), supported ? "PASS" : "FAIL", reason));
        }
        return List.copyOf(facts);
    }

    private CoverageDecision checkCoverage(Template template, DocumentUnit unit, List<RawBlock> blocks, List<SummaryFact> facts) {
        return model.generateStructured(ModelRole.SMALL, ModelTask.SUMMARY_JUDGE,
                "你是独立 Coverage Judge。只找 RAW BLOCKS 中确实存在、属于模板 section、但当前 PASS FACTS 未覆盖的重要事实。每个 missing 必须给 source_block_ids。不要要求原文不存在的信息。",
                "document_type=" + unit.documentType() + "\nrequired_sections=" + template.requiredSections()
                        + "\nPASS FACTS=" + facts + "\nRAW BLOCKS:\n" + renderBlocks(blocks), CoverageDecision.class);
    }

    private List<DocumentUnit> normalizeClassification(VersionSource source, Classification classification) {
        if (classification == null) throw new IllegalStateException("summary classification returned empty output");
        String manual = trim(source.manualDocumentType());
        if (!manual.isEmpty() && !TEMPLATES.containsKey(manual)) throw new IllegalStateException("unsupported manual document type: " + manual);
        String structure = trim(classification.structureType()).toLowerCase();
        if (!STRUCTURES.contains(structure)) throw new IllegalStateException("unsupported structure type: " + structure);
        int min = source.blocks().stream().mapToInt(RawBlock::blockNo).min().orElseThrow();
        int max = source.blocks().stream().mapToInt(RawBlock::blockNo).max().orElseThrow();
        Set<Integer> available = new LinkedHashSet<>(); source.blocks().forEach(b -> available.add(b.blockNo()));
        if (structure.equals("mixed")) return List.of(unit(source.versionId(), 1, "", "mixed", GENERAL, min, max));
        if (structure.equals("single")) {
            String type = manual.isEmpty() ? supported(classification.documentType()) : manual;
            return List.of(unit(source.versionId(), 1, "", "single", type, min, max));
        }
        List<UnitProposal> proposals = classification.units() == null ? List.of() : new ArrayList<>(classification.units());
        if (proposals.isEmpty()) throw new IllegalStateException("bundle has no units");
        proposals.sort(Comparator.comparingInt(UnitProposal::startBlockNo).thenComparingInt(UnitProposal::endBlockNo));
        List<DocumentUnit> units = new ArrayList<>();
        int lastEnd = min - 1;
        for (int i = 0; i < proposals.size(); i++) {
            UnitProposal p = proposals.get(i);
            if (p.startBlockNo() > p.endBlockNo() || p.startBlockNo() < min || p.endBlockNo() > max || p.startBlockNo() <= lastEnd) {
                throw new IllegalStateException("invalid or overlapping bundle unit range " + p.startBlockNo() + "-" + p.endBlockNo());
            }
            for (int n = p.startBlockNo(); n <= p.endBlockNo(); n++) if (!available.contains(n)) throw new IllegalStateException("bundle references missing raw block " + n);
            String type = manual.isEmpty() ? supported(p.documentType()) : manual;
            units.add(unit(source.versionId(), i + 1, trim(p.title()), "bundle", type, p.startBlockNo(), p.endBlockNo()));
            lastEnd = p.endBlockNo();
        }
        return List.copyOf(units);
    }

    private static DocumentUnit unit(String versionId, int no, String title, String structure, String type, int start, int end) {
        return new DocumentUnit(deterministicUuid("unit:" + versionId + ":" + no + ":" + start + ":" + end + ":" + type),
                versionId, no, title, structure, type, start, end);
    }

    private static List<MissingFact> normalizeMissing(Template template, List<RawBlock> blocks, List<MissingFact> missing) {
        if (missing == null) return List.of();
        Set<String> blockIds = new LinkedHashSet<>(); blocks.forEach(b -> blockIds.add(b.id()));
        List<MissingFact> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (MissingFact m : missing) {
            String section = trim(m.section()), description = trim(m.missingFactCandidate());
            if (description.isEmpty() || !template.sectionNames().contains(section)) continue;
            List<String> sources = m.sourceBlockIds() == null ? List.of() : m.sourceBlockIds().stream().map(SummaryCompilationService::trim).filter(blockIds::contains).distinct().toList();
            if (sources.isEmpty()) continue;
            if (seen.add(section + "\u0000" + normalize(description))) out.add(new MissingFact(description, section, sources));
        }
        return List.copyOf(out);
    }

    private static List<SummaryFact> mergeFacts(List<SummaryFact> a, List<SummaryFact> b) {
        List<SummaryFact> out = new ArrayList<>(a); Set<String> seen = new LinkedHashSet<>();
        a.forEach(f -> seen.add(f.section() + "\u0000" + normalize(f.factText())));
        for (SummaryFact f : b) if (seen.add(f.section() + "\u0000" + normalize(f.factText()))) out.add(f);
        return List.copyOf(out);
    }

    private static List<SummaryFact> passing(List<SummaryFact> facts) { return facts.stream().filter(f -> "PASS".equals(f.groundingStatus())).toList(); }

    private static String render(Template template, String logicalName, DocumentUnit unit, List<SummaryFact> facts) {
        StringBuilder out = new StringBuilder("# ").append(unit.title().isBlank() ? logicalName : unit.title()).append("\n\n");
        for (Section section : template.sections()) {
            out.append("## ").append(section.name()).append("\n\n");
            List<SummaryFact> sectionFacts = facts.stream().filter(f -> "PASS".equals(f.groundingStatus()) && f.section().equals(section.name())).toList();
            if (sectionFacts.isEmpty()) out.append("- 未从原始证据中确认。\n\n");
            else for (SummaryFact fact : sectionFacts) out.append("- ").append(fact.factText()).append("\n");
            out.append('\n');
        }
        return out.toString();
    }

    private static String renderBlocks(List<RawBlock> blocks) {
        StringBuilder out = new StringBuilder();
        for (RawBlock block : blocks) out.append('[').append(block.id()).append(" | block_no=").append(block.blockNo()).append("] ").append(block.content()).append("\n");
        return out.toString();
    }

    private static String classificationSystemPrompt() {
        return "你是企业文档单元分类器。structure_type 只能是 single/bundle/mixed；document_type 必须来自允许列表。bundle 的 unit 边界必须使用现有 block_no，不能重叠。mixed 表示无法安全拆分的混合材料。";
    }

    private static String classificationUserPrompt(VersionSource source) {
        return "allowed_document_types=" + TEMPLATES.keySet() + "\nmanual_override=" + source.manualDocumentType() + "\nRAW BLOCKS:\n" + renderBlocks(source.blocks());
    }

    private static String supported(String value) { String type = trim(value); return TEMPLATES.containsKey(type) ? type : GENERAL; }
    private static String normalize(String value) { return trim(value).replaceAll("\\s+", " ").toLowerCase(); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static List<String> values(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.trim()); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }

    public record PreparedSummary(DocumentUnit unit, SummaryRecord summary, int coverageRepairCount) {}
    public record Classification(String documentType, String structureType, List<UnitProposal> units) {}
    public record UnitProposal(String title, String documentType, int startBlockNo, int endBlockNo) {}
    public record FactProposalSet(List<FactProposal> facts) {}
    public record FactProposal(String section, String factText, List<String> evidenceBlockIds) {}
    public record SupportDecision(boolean supported, String reason) {}
    public record CoverageDecision(List<MissingFact> missing) {}
    public record MissingFact(String missingFactCandidate, String section, List<String> sourceBlockIds) {}
    private record Section(String name, String description, boolean required) {}
    private record Template(String documentType, List<Section> sections) {
        Set<String> sectionNames() { LinkedHashSet<String> names = new LinkedHashSet<>(); sections.forEach(s -> names.add(s.name())); return Set.copyOf(names); }
        List<String> requiredSections() { return sections.stream().filter(Section::required).map(Section::name).toList(); }
    }

    private static Map<String, Template> templates() {
        Map<String, Template> map = new LinkedHashMap<>();
        add(map, "年度/半年度报告", req("主体与报告期"), req("财务摘要"), req("经营情况"), req("股权与控制"), opt("重大合同与债务"), opt("诉讼与合规"), req("风险事项"));
        add(map, "审计报告", req("审计主体与期间"), req("审计意见"), opt("形成意见的基础"), opt("关键审计事项"), req("关键财务数据"), opt("强调与持续经营"), req("审计机构"));
        add(map, "合同/协议", req("合同主体"), req("标的与范围"), opt("金额与付款"), req("期限与时间节点"), req("核心权利义务"), opt("担保与增信"), req("违约与解除"), req("争议解决"));
        add(map, "诉讼/仲裁/判决", req("案件主体"), req("案号与机构"), req("争议与请求"), req("事实与理由"), req("裁判或进展"), opt("涉案金额"), opt("执行与期限"), req("风险影响"));
        add(map, "章程/制度/政策", req("适用范围"), req("生效与版本"), req("核心规则"), req("职责与权限"), opt("流程与记录"), opt("限制与禁止"), opt("责任与处罚"));
        add(map, "项目/尽调/业务材料", req("项目与主体"), req("业务与资产"), opt("关键经营与财务数据"), opt("客户供应商与合同"), opt("组织与股权"), req("重要问题与风险"), opt("结论与建议"));
        add(map, "股权/控制权变更", req("变更主体"), req("变更前股权"), req("变更后股权"), req("控制权变化"), opt("交易对价"), req("时间节点"), opt("审批与条件"));
        add(map, "董事会/股东会决议", req("会议信息"), req("出席与表决"), req("议案"), req("决议"), opt("授权"), opt("生效与后续"));
        add(map, "融资/担保", req("融资主体与机构"), req("金额与币种"), req("期限与利率"), opt("资金用途"), req("担保安排"), opt("偿还与触发"), opt("审批与条件"));
        add(map, "资质/许可/知识产权", req("权利主体"), req("名称与编号"), req("权利范围"), req("有效期与状态"), opt("限制与条件"), opt("许可质押与处分"));
        add(map, GENERAL, req("主体"), req("时间与背景"), req("核心事实"), opt("金额与比例"), opt("权利义务与关系"), req("风险与异常"));
        return Map.copyOf(map);
    }
    private static void add(Map<String, Template> map, String type, Section... sections) { map.put(type, new Template(type, List.of(sections))); }
    private static Section req(String name) { return new Section(name, name, true); }
    private static Section opt(String name) { return new Section(name, name, false); }
}
