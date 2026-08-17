package cn.lgs.knowledgebase.application.compilation;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.port.Ports.GraphStore;
import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ConflictStatus;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.RelationLifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GraphCompilationService {
    private static final Set<String> ENTITY_TYPES = Set.of(
            "COMPANY", "PERSON", "CONTRACT", "LEGAL_CASE", "PROJECT", "ORGANIZATION", "GOVERNMENT_AGENCY",
            "PRODUCT", "LOCATION", "LICENSE", "INTELLECTUAL_PROPERTY", "POLICY", "EVENT", "TOPIC", "GENERIC");
    private static final Map<String, RelationRule> RELATION_RULES = relationRules();

    private final CompilationRepository repository;
    private final KnowledgeModel model;
    private final GraphStore graph;

    public GraphCompilationService(CompilationRepository repository, KnowledgeModel model, GraphStore graph) {
        this.repository = require(repository, "compilation repository");
        this.model = require(model, "knowledge model");
        this.graph = require(graph, "graph store");
    }

    public CompileResult compileSummary(String summaryId) {
        SummaryRecord source = repository.findSummary(trim(summaryId))
                .orElseThrow(() -> new IllegalStateException("summary not found: " + summaryId));
        if (!source.activeAndGrounded()) {
            throw new IllegalStateException("summary is not ACTIVE with grounding+coverage PASS: " + summaryId);
        }
        Map<String, SummaryFact> facts = groundedFacts(source.facts());
        if (facts.isEmpty()) throw new IllegalStateException("graph compiler: no grounded summary facts");

        Extraction extraction = model.generateStructured(ModelRole.LARGE, ModelTask.RELATION_EXTRACT,
                "Extract a strict enterprise knowledge graph from grounded summary facts. Return structured data only. " +
                        "Every relation must cite exact supporting fact IDs. Use only allowed entity/relation types. " +
                        "Emit one fact_decision for every input fact. Eligible facts must be cited by at least one relation; " +
                        "non-eligible facts require skip_reason. Business valid_from/valid_to must remain empty unless explicitly stated.",
                "Entity types:\n- " + String.join("\n- ", ENTITY_TYPES.stream().sorted().toList()) +
                        "\nRelation types:\n- " + String.join("\n- ", RELATION_RULES.keySet().stream().sorted().toList()) +
                        "\nFacts:\n" + renderFacts(facts.values()), Extraction.class);

        Validation validation = validateExtraction(facts, extraction);
        if (!validation.coverage().pass()) {
            throw new IllegalStateException("graph coverage failed: uncovered eligible facts: " + String.join(",", validation.coverage().missingFacts()));
        }

        Map<String, Entity> cache = new LinkedHashMap<>();
        List<Entity> entities = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        Map<String, EntityResolution> resolutions = new LinkedHashMap<>();
        String decidedBy = "graph_compiler:" + model.identity(ModelRole.LARGE);
        for (ProposedRelation proposed : validation.relations()) {
            String sourceType = normalizeType(proposed.sourceType());
            String targetType = normalizeType(proposed.targetType());
            String sourceSurface = trim(proposed.sourceName());
            String targetSurface = trim(proposed.targetName());
            Entity sourceEntity = resolve(sourceSurface, sourceType, cache, entities);
            Entity targetEntity = resolve(targetSurface, targetType, cache, entities);
            Instant validFrom = parseOptionalTime(proposed.validFrom());
            Instant validTo = parseOptionalTime(proposed.validTo());
            LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
            for (String factId : proposed.supportingFactIds()) evidenceIds.addAll(facts.get(factId).evidenceIds());
            for (String evidenceId : evidenceIds) {
                addResolution(resolutions, source.versionId(), sourceSurface, sourceType, evidenceId,
                        sourceEntity.canonicalId(), proposed.confidence(), decidedBy);
                addResolution(resolutions, source.versionId(), targetSurface, targetType, evidenceId,
                        targetEntity.canonicalId(), proposed.confidence(), decidedBy);
            }
            String relationId = relationId(sourceEntity.canonicalId(), proposed.relationType(), targetEntity.canonicalId(), validFrom);
            relations.add(new Relation(relationId, sourceEntity.canonicalId(), proposed.relationType(), targetEntity.canonicalId(),
                    validFrom, validTo, ConflictStatus.NONE, RelationLifecycle.ACTIVE, List.copyOf(evidenceIds), proposed.confidence(),
                    Map.of("summary_id", source.id(), "supporting_fact_ids", proposed.supportingFactIds(), "origin", "llm_triple")));
        }

        entities.sort(Comparator.comparing(Entity::canonicalId));
        relations.sort(Comparator.comparing(Relation::id));
        List<EntityResolution> resolutionList = resolutions.values().stream().sorted(Comparator.comparing(EntityResolution::sourceEntityId)).toList();
        repository.saveGraph(source.id(), entities, relations, resolutionList);
        graph.upsertEntities(entities);
        graph.upsertRelations(relations);
        return new CompileResult(source.id(), List.copyOf(entities), List.copyOf(relations), validation.coverage(), model.identity(ModelRole.LARGE));
    }

    private static void addResolution(Map<String, EntityResolution> target, String versionId, String surfaceForm,
                                      String entityType, String evidenceId, String canonicalEntityId,
                                      double confidence, String decidedBy) {
        String sourceEntityId = stableId("source_entity", versionId, surfaceForm, entityType, evidenceId);
        EntityResolution next = new EntityResolution(sourceEntityId, versionId, surfaceForm, entityType,
                evidenceId, canonicalEntityId, confidence, decidedBy);
        EntityResolution current = target.get(sourceEntityId);
        if (current != null && !current.canonicalEntityId().equals(next.canonicalEntityId())) {
            throw new IllegalStateException("entity resolution conflict for surface form " + surfaceForm + " and evidence " + evidenceId);
        }
        if (current == null || next.confidence() > current.confidence()) target.put(sourceEntityId, next);
    }

    private Entity resolve(String name, String type, Map<String, Entity> cache, List<Entity> entities) {
        String normalizedType = normalizeType(type);
        String key = normalizedType + "\u0000" + canonicalKey(name);
        Entity cached = cache.get(key);
        if (cached != null) return cached;
        Entity resolved = repository.resolveEntity(trim(name), normalizedType);
        cache.put(key, resolved);
        entities.add(resolved);
        return resolved;
    }

    private static Validation validateExtraction(Map<String, SummaryFact> facts, Extraction extraction) {
        if (extraction == null) throw new IllegalStateException("graph extraction returned empty output");
        Map<String, FactDecision> decisions = new LinkedHashMap<>();
        for (FactDecision raw : safe(extraction.factDecisions())) {
            String factId = trim(raw.factId());
            if (!facts.containsKey(factId)) throw new IllegalStateException("graph coverage: decision references unknown fact " + factId);
            if (decisions.containsKey(factId)) throw new IllegalStateException("graph coverage: duplicate decision for fact " + factId);
            if (!raw.graphEligible() && trim(raw.skipReason()).isEmpty()) {
                throw new IllegalStateException("graph coverage: non-eligible fact " + factId + " requires skip_reason");
            }
            decisions.put(factId, new FactDecision(factId, raw.graphEligible(), trim(raw.skipReason())));
        }
        for (String factId : facts.keySet()) {
            if (!decisions.containsKey(factId)) throw new IllegalStateException("graph coverage: missing decision for fact " + factId);
        }

        Set<String> covered = new LinkedHashSet<>();
        List<ProposedRelation> valid = new ArrayList<>();
        for (ProposedRelation raw : safe(extraction.relations())) {
            ProposedRelation relation = normalize(raw);
            validateRelation(relation);
            LinkedHashSet<String> uniqueFacts = new LinkedHashSet<>();
            for (String factId : relation.supportingFactIds()) {
                String id = trim(factId);
                SummaryFact fact = facts.get(id);
                if (fact == null) throw new IllegalStateException("graph relation " + relation.relationType() + " references unknown fact " + id);
                FactDecision decision = decisions.get(id);
                if (!decision.graphEligible()) throw new IllegalStateException("graph relation " + relation.relationType() + " cites fact " + id + " marked non-eligible");
                if (fact.evidenceIds().isEmpty()) throw new IllegalStateException("graph relation " + relation.relationType() + " cites fact " + id + " without raw evidence");
                if (uniqueFacts.add(id)) covered.add(id);
            }
            valid.add(relation);
        }

        int eligible = 0, skipped = 0;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, FactDecision> entry : decisions.entrySet()) {
            if (entry.getValue().graphEligible()) {
                eligible++;
                if (!covered.contains(entry.getKey())) missing.add(entry.getKey());
            } else skipped++;
        }
        missing.sort(String::compareTo);
        return new Validation(new CoverageResult(missing.isEmpty(), eligible, eligible - missing.size(), skipped, List.copyOf(missing)), List.copyOf(valid));
    }

    private static void validateRelation(ProposedRelation relation) {
        if (relation.sourceName().isBlank() || relation.targetName().isBlank()) throw new IllegalStateException("graph relation: source and target names are required");
        if (relation.sourceName().equals(relation.targetName()) && relation.sourceType().equals(relation.targetType())) throw new IllegalStateException("graph relation: self relation is not allowed");
        if (!ENTITY_TYPES.contains(relation.sourceType()) || !ENTITY_TYPES.contains(relation.targetType())) throw new IllegalStateException("graph relation uses unsupported entity type");
        RelationRule rule = RELATION_RULES.get(relation.relationType());
        if (rule == null) throw new IllegalStateException("graph relation: unsupported relation type " + relation.relationType());
        if (!rule.sourceTypes().contains(relation.sourceType())) throw new IllegalStateException("graph relation " + relation.relationType() + ": source type " + relation.sourceType() + " is not allowed");
        if (!rule.targetTypes().contains(relation.targetType())) throw new IllegalStateException("graph relation " + relation.relationType() + ": target type " + relation.targetType() + " is not allowed");
        if (relation.supportingFactIds().isEmpty()) throw new IllegalStateException("graph relation requires at least one supporting fact");
        if (relation.confidence() < 0 || relation.confidence() > 1) throw new IllegalStateException("graph relation confidence must be within [0,1]");
        Instant from = parseOptionalTime(relation.validFrom());
        Instant to = parseOptionalTime(relation.validTo());
        if (from != null && to != null && to.isBefore(from)) throw new IllegalStateException("graph relation valid_to cannot be before valid_from");
    }

    private static ProposedRelation normalize(ProposedRelation relation) {
        if (relation == null) throw new IllegalStateException("graph relation is null");
        List<String> facts = relation.supportingFactIds() == null ? List.of() : relation.supportingFactIds().stream().map(GraphCompilationService::trim).filter(v -> !v.isEmpty()).distinct().toList();
        return new ProposedRelation(trim(relation.sourceName()), normalizeType(relation.sourceType()), normalizeType(relation.relationType()),
                trim(relation.targetName()), normalizeType(relation.targetType()), facts, trim(relation.validFrom()), trim(relation.validTo()), relation.confidence());
    }

    private static Map<String, SummaryFact> groundedFacts(List<SummaryFact> input) {
        Map<String, SummaryFact> facts = new LinkedHashMap<>();
        for (SummaryFact fact : safe(input)) {
            if ("PASS".equals(fact.groundingStatus()) && !trim(fact.id()).isEmpty()) facts.put(fact.id(), fact);
        }
        return facts;
    }

    public static String canonicalKey(String name) {
        String normalized = Normalizer.normalize(trim(name), Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Instant parseOptionalTime(String value) {
        String text = trim(value);
        if (text.isEmpty()) return null;
        try { return Instant.parse(text); } catch (DateTimeParseException ignored) {}
        try { return java.time.OffsetDateTime.parse(text).toInstant(); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException e) { throw new IllegalStateException("unsupported date/time " + text); }
    }

    private static String relationId(String sourceId, String relationType, String targetId, Instant validFrom) {
        String valid = validFrom == null ? "" : validFrom.toString();
        return stableId("relation", sourceId, relationType, targetId, valid);
    }

    public static String stableId(String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest, 0, 16);
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String renderFacts(java.util.Collection<SummaryFact> facts) {
        StringBuilder out = new StringBuilder();
        for (SummaryFact fact : facts) out.append("<fact id=\"").append(fact.id()).append("\" section=\"").append(fact.section()).append("\">\n")
                .append(fact.factText()).append("\n</fact>\n");
        return out.toString();
    }

    public static boolean relationAllowed(String sourceType, String relationType, String targetType) {
        RelationRule rule = RELATION_RULES.get(normalizeType(relationType));
        return rule != null && rule.sourceTypes().contains(normalizeType(sourceType)) && rule.targetTypes().contains(normalizeType(targetType));
    }

    private static Map<String, RelationRule> relationRules() {
        Map<String, RelationRule> m = new LinkedHashMap<>();
        put(m, "ACTUAL_CONTROLLER_OF", set("PERSON","COMPANY","ORGANIZATION"), set("COMPANY"));
        put(m, "CONTROLS", set("PERSON","COMPANY","ORGANIZATION"), set("COMPANY","ORGANIZATION"));
        put(m, "SHAREHOLDER_OF", set("PERSON","COMPANY","ORGANIZATION"), set("COMPANY"));
        put(m, "SUBSIDIARY_OF", set("COMPANY","ORGANIZATION"), set("COMPANY","ORGANIZATION"));
        put(m, "PARENT_OF", set("COMPANY","ORGANIZATION"), set("COMPANY","ORGANIZATION"));
        put(m, "DIRECTOR_OF", set("PERSON"), set("COMPANY","ORGANIZATION"));
        put(m, "LEGAL_REPRESENTATIVE_OF", set("PERSON"), set("COMPANY","ORGANIZATION"));
        Set<String> party = set("PERSON","COMPANY","ORGANIZATION","GOVERNMENT_AGENCY");
        put(m, "PARTY_TO_CONTRACT", party, set("CONTRACT"));
        put(m, "SIGNS_WITH", party, party);
        Set<String> cpo = set("COMPANY","ORGANIZATION","PERSON");
        put(m, "GUARANTEES", cpo, cpo);
        put(m, "BORROWS_FROM", cpo, set("COMPANY","ORGANIZATION","GOVERNMENT_AGENCY"));
        put(m, "INVESTS_IN", cpo, set("COMPANY","ORGANIZATION","PROJECT"));
        put(m, "INVOLVED_IN_CASE", party, set("LEGAL_CASE"));
        put(m, "OWNS_IP", cpo, set("INTELLECTUAL_PROPERTY"));
        put(m, "HOLDS_LICENSE", cpo, set("LICENSE"));
        put(m, "PARTICIPATES_IN_PROJECT", party, set("PROJECT"));
        put(m, "APPROVES", party, set("PROJECT","CONTRACT","POLICY","EVENT"));
        put(m, "SUPPLIES_TO", set("COMPANY","ORGANIZATION"), set("COMPANY","ORGANIZATION"));
        put(m, "CUSTOMER_OF", cpo, set("COMPANY","ORGANIZATION"));
        put(m, "RELATED_TO", ENTITY_TYPES, ENTITY_TYPES);
        return Map.copyOf(m);
    }

    private static void put(Map<String, RelationRule> map, String type, Set<String> source, Set<String> target) { map.put(type, new RelationRule(source, target)); }
    private static Set<String> set(String... values) { return Set.of(values); }
    private static String normalizeType(String value) { return trim(value).toUpperCase(Locale.ROOT); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }

    private record RelationRule(Set<String> sourceTypes, Set<String> targetTypes) {}
    private record Validation(CoverageResult coverage, List<ProposedRelation> relations) {}
    public record ProposedEntity(String name, String type) {}
    public record ProposedRelation(String sourceName, String sourceType, String relationType, String targetName, String targetType,
                                   List<String> supportingFactIds, String validFrom, String validTo, double confidence) {}
    public record FactDecision(String factId, boolean graphEligible, String skipReason) {}
    public record Extraction(List<ProposedEntity> entities, List<ProposedRelation> relations, List<FactDecision> factDecisions) {}
    public record CoverageResult(boolean pass, int eligibleFacts, int coveredFacts, int skippedFacts, List<String> missingFacts) {}
    public record CompileResult(String summaryId, List<Entity> entities, List<Relation> relations, CoverageResult coverage, String extractor) {}
}
