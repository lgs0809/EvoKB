package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.CompilationRepository;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.DocumentUnit;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.EntityResolution;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryFact;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.SummaryRecord;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.VersionSource;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiClaim;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.WikiRecord;
import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.application.port.Ports.ObjectStore;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Entity;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Relation;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcCompilationRepository implements CompilationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ObjectStore objects;

    public JdbcCompilationRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, ObjectStore objects) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.objects = objects;
    }

    @Override
    public VersionSource loadVersionSource(String versionId) {
        Header header = jdbc.query("SELECT d.document_id,d.logical_name,v.version_id,m.source_department,m.material_category,m.manual_tags,m.company,m.document_date," +
                        "COALESCE(m.manual_document_type_override,m.auto_document_type,''),COALESCE(m.manual_document_type_override,'') " +
                        "FROM document_version v JOIN document d ON d.document_id=v.document_id " +
                        "LEFT JOIN document_metadata m ON m.version_id=v.version_id WHERE v.version_id=? AND v.lifecycle='ACTIVE' LIMIT 1",
                (rs, rowNum) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3),
                        new Metadata(rs.getString(4), rs.getString(5), tags(rs.getString(6)), rs.getString(7), localDate(rs.getDate(8)), rs.getString(9)),
                        rs.getString(10)), versionId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("active version not found: " + versionId));

        Map<String, Evidence> evidenceByBlock = new LinkedHashMap<>();
        jdbc.query("SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,e.content_hash,b.raw_content " +
                        "FROM evidence e JOIN raw_block b ON b.block_id=e.block_id WHERE e.version_id=? AND e.active=TRUE ORDER BY b.block_no",
                rs -> {
                    Evidence evidence = new Evidence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            objectMap(rs.getString(6)), rs.getString(8), rs.getString(7));
                    evidenceByBlock.put(evidence.blockId(), evidence);
                }, versionId);

        List<RawBlock> blocks = jdbc.query("SELECT block_id,version_id,block_no,block_type,raw_content,COALESCE(normalized_content,''),heading_path,page_start,page_end,bbox_json,source_block_ids,content_hash,COALESCE(token_count,0) " +
                        "FROM raw_block WHERE version_id=? ORDER BY block_no",
                (rs, rowNum) -> rawBlock(rs, evidenceByBlock.get(rs.getString(1))), versionId);
        return new VersionSource(header.documentId(), header.versionId(), header.logicalName(), header.manualDocumentType(), header.metadata(), blocks, evidenceByBlock);
    }

    @Override
    public void replaceDocumentUnits(String versionId, List<DocumentUnit> units) {
        transactions.executeWithoutResult(status -> {
            for (DocumentUnit unit : units) {
                jdbc.update("INSERT INTO document_unit(unit_id,version_id,unit_no,title,structure_type,document_type,boundary_json) VALUES(?,?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE title=VALUES(title),structure_type=VALUES(structure_type),document_type=VALUES(document_type),boundary_json=VALUES(boundary_json)",
                        unit.id(), unit.versionId(), unit.unitNo(), nullable(unit.title()), unit.structureType(), unit.documentType(),
                        json(Map.of("start_block_no", unit.startBlockNo(), "end_block_no", unit.endBlockNo())));
            }
            List<String> ids = units.stream().map(DocumentUnit::id).toList();
            if (ids.isEmpty()) return;
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(versionId); args.addAll(ids);
            jdbc.update("DELETE FROM document_unit WHERE version_id=? AND unit_id NOT IN (" + placeholders + ") AND unit_id NOT IN (SELECT unit_id FROM summary)", args.toArray());
        });
    }

    @Override
    public void saveSummary(SummaryRecord summary) {
        transactions.executeWithoutResult(status -> {
            if ("ACTIVE".equals(summary.lifecycle())) {
                jdbc.update("UPDATE summary SET lifecycle='STALE' WHERE unit_id=? AND summary_id<>? AND lifecycle='ACTIVE'", summary.unitId(), summary.id());
            }
            jdbc.update("INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES(?,?,?,?,?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE object_key=VALUES(object_key),content_hash=VALUES(content_hash),grounding_status=VALUES(grounding_status),coverage_status=VALUES(coverage_status),lifecycle=VALUES(lifecycle),model=VALUES(model)",
                    summary.id(), summary.unitId(), summary.versionId(), summary.objectKey(), summary.contentHash(), summary.groundingStatus(), summary.coverageStatus(), summary.lifecycle(), nullable(summary.model()));
            for (SummaryFact fact : summary.facts()) {
                jdbc.update("INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status) VALUES(?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE section=VALUES(section),fact_text=VALUES(fact_text),grounding_status=VALUES(grounding_status)",
                        fact.id(), summary.id(), fact.section(), fact.factText(), fact.groundingStatus());
                for (String evidenceId : unique(fact.evidenceIds())) {
                    jdbc.update("INSERT IGNORE INTO summary_evidence(fact_id,evidence_id) VALUES(?,?)", fact.id(), evidenceId);
                }
            }
        });
    }

    @Override
    public Optional<SummaryRecord> findSummary(String summaryId) {
        return readSummaries(List.of(summaryId)).stream().findFirst();
    }

    @Override
    public List<SummaryRecord> loadSummaries(List<String> summaryIds) {
        return readSummaries(unique(summaryIds));
    }

    private List<SummaryRecord> readSummaries(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<SummaryHeader> headers = jdbc.query("SELECT s.summary_id,s.unit_id,d.document_id,s.version_id,s.object_key,s.content_hash,s.grounding_status,s.coverage_status,s.lifecycle,COALESCE(s.model,''),s.created_at " +
                        "FROM summary s JOIN document_version v ON v.version_id=s.version_id JOIN document d ON d.document_id=v.document_id " +
                        "WHERE s.summary_id IN (" + placeholders + ")",
                (rs, rowNum) -> new SummaryHeader(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), instant(rs.getTimestamp(11))), ids.toArray());
        Map<String, SummaryRecord> byId = new LinkedHashMap<>();
        for (SummaryHeader h : headers) {
            List<SummaryFact> facts = readSummaryFacts(h.id());
            byId.put(h.id(), new SummaryRecord(h.id(), h.unitId(), h.documentId(), h.versionId(), h.objectKey(), h.contentHash(),
                    h.grounding(), h.coverage(), h.lifecycle(), h.model(), readObjectText(h.objectKey()), facts, h.createdAt()));
        }
        List<SummaryRecord> ordered = new ArrayList<>();
        for (String id : ids) if (byId.containsKey(id)) ordered.add(byId.get(id));
        return List.copyOf(ordered);
    }

    private List<SummaryFact> readSummaryFacts(String summaryId) {
        List<FactHeader> headers = jdbc.query("SELECT fact_id,section,fact_text,grounding_status FROM summary_fact WHERE summary_id=? ORDER BY created_at,fact_id",
                (rs, rowNum) -> new FactHeader(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), summaryId);
        if (headers.isEmpty()) return List.of();
        List<String> factIds = headers.stream().map(FactHeader::id).toList();
        String placeholders = String.join(",", Collections.nCopies(factIds.size(), "?"));
        Map<String, List<EvidenceRef>> refs = new LinkedHashMap<>();
        jdbc.query("SELECT se.fact_id,e.evidence_id,e.block_id FROM summary_evidence se JOIN evidence e ON e.evidence_id=se.evidence_id WHERE se.fact_id IN (" + placeholders + ") ORDER BY se.fact_id,e.evidence_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    refs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new EvidenceRef(rs.getString(2), rs.getString(3)));
                }, factIds.toArray());
        List<SummaryFact> out = new ArrayList<>();
        for (FactHeader h : headers) {
            List<EvidenceRef> factRefs = refs.getOrDefault(h.id(), List.of());
            out.add(new SummaryFact(h.id(), summaryId, h.section(), h.text(),
                    factRefs.stream().map(EvidenceRef::blockId).distinct().toList(), factRefs.stream().map(EvidenceRef::evidenceId).distinct().toList(), h.grounding(), ""));
        }
        return List.copyOf(out);
    }

    @Override
    public Entity resolveEntity(String name, String entityType) {
        String key = cn.lgs.knowledgebase.application.compilation.GraphCompilationService.canonicalKey(name);
        String candidateId = cn.lgs.knowledgebase.application.compilation.GraphCompilationService.stableId("entity", entityType, key);
        jdbc.update("INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key) VALUES(?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE canonical_name=VALUES(canonical_name),updated_at=CURRENT_TIMESTAMP(6)",
                candidateId, name, entityType, key);
        return jdbc.query("SELECT canonical_entity_id,canonical_name,entity_type,canonical_key,COALESCE(attributes_json,'{}') FROM canonical_entity WHERE entity_type=? AND canonical_key=? LIMIT 1",
                (rs, rowNum) -> new Entity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), List.of(), objectMap(rs.getString(5))),
                entityType, key).stream().findFirst().orElseThrow(() -> new IllegalStateException("canonical entity resolve failed"));
    }

    @Override
    public void saveGraph(String summaryId, List<Entity> entities, List<Relation> relations, List<EntityResolution> resolutions) {
        transactions.executeWithoutResult(status -> {
            for (Entity entity : entities) {
                jdbc.update("INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key,attributes_json) VALUES(?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE canonical_name=VALUES(canonical_name),attributes_json=VALUES(attributes_json),updated_at=CURRENT_TIMESTAMP(6)",
                        entity.canonicalId(), entity.canonicalName(), entity.entityType(), entity.canonicalKey(), json(entity.attributes()));
            }
            for (EntityResolution resolution : resolutions == null ? List.<EntityResolution>of() : resolutions) {
                jdbc.update("INSERT INTO source_entity(source_entity_id,version_id,surface_form,entity_type,evidence_id) VALUES(?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE surface_form=VALUES(surface_form),entity_type=VALUES(entity_type),evidence_id=VALUES(evidence_id)",
                        resolution.sourceEntityId(), resolution.versionId(), resolution.surfaceForm(), resolution.entityType(), resolution.evidenceId());
                jdbc.update("INSERT INTO entity_mapping(source_entity_id,canonical_entity_id,confidence,status,decided_by) VALUES(?,?,?,'APPLIED',?) " +
                                "ON DUPLICATE KEY UPDATE canonical_entity_id=VALUES(canonical_entity_id),confidence=VALUES(confidence),status='APPLIED',decided_by=VALUES(decided_by)",
                        resolution.sourceEntityId(), resolution.canonicalEntityId(), resolution.confidence(), nullable(resolution.decidedBy()));
            }
            for (Relation relation : relations) {
                jdbc.update("INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle) VALUES(?,?,?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE valid_to=VALUES(valid_to),conflict_status=VALUES(conflict_status),lifecycle=VALUES(lifecycle)",
                        relation.id(), relation.subjectEntityId(), relation.predicate(), relation.objectEntityId(), timestamp(relation.validFrom()), timestamp(relation.validTo()),
                        relation.conflictStatus().name(), relation.lifecycle().name());
                for (String evidenceId : unique(relation.evidenceIds())) {
                    String supportId = cn.lgs.knowledgebase.application.compilation.GraphCompilationService.stableId("support", relation.id(), evidenceId, summaryId);
                    jdbc.update("INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,summary_id,confidence,active) VALUES(?,?,'llm_triple',?,?,?,TRUE) " +
                                    "ON DUPLICATE KEY UPDATE confidence=VALUES(confidence),active=TRUE",
                            supportId, relation.id(), evidenceId, summaryId, relation.confidence());
                }
            }
        });
    }

    @Override
    public long nextWikiVersion(String wikiId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(wiki_version),0)+1 FROM wiki WHERE wiki_id=?", Long.class, wikiId);
        return value == null ? 1 : value;
    }

    @Override
    public void saveWiki(WikiRecord wiki) {
        transactions.executeWithoutResult(status -> {
            if ("ACTIVE".equals(wiki.lifecycle())) {
                jdbc.update("UPDATE wiki SET lifecycle='STALE' WHERE wiki_id=? AND wiki_version<>? AND lifecycle='ACTIVE'", wiki.id(), wiki.version());
            }
            jdbc.update("INSERT INTO wiki(wiki_id,wiki_version,wiki_type,canonical_subject_id,title,object_key,content_hash,lifecycle) VALUES(?,?,?,?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE wiki_type=VALUES(wiki_type),canonical_subject_id=VALUES(canonical_subject_id),title=VALUES(title),object_key=VALUES(object_key),content_hash=VALUES(content_hash),lifecycle=VALUES(lifecycle)",
                    wiki.id(), wiki.version(), wiki.wikiType(), nullable(wiki.canonicalSubjectId()), wiki.title(), wiki.objectKey(), wiki.contentHash(), wiki.lifecycle());
            for (WikiClaim claim : wiki.claims()) {
                jdbc.update("INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status) VALUES(?,?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE section=VALUES(section),claim_text=VALUES(claim_text),grounding_status=VALUES(grounding_status),temporal_status=VALUES(temporal_status)",
                        claim.id(), claim.wikiId(), claim.wikiVersion(), claim.section(), claim.claimText(), claim.groundingStatus(), claim.temporalStatus());
                for (String factId : unique(claim.factIds())) jdbc.update("INSERT IGNORE INTO wiki_claim_fact(claim_id,fact_id) VALUES(?,?)", claim.id(), factId);
                for (String evidenceId : unique(claim.evidenceIds())) jdbc.update("INSERT IGNORE INTO wiki_evidence(claim_id,evidence_id) VALUES(?,?)", claim.id(), evidenceId);
            }
        });
    }

    @Override
    public DerivedContext derivedContextForSummaries(List<String> summaryIds) {
        List<String> ids = unique(summaryIds);
        if (ids.isEmpty()) return new DerivedContext(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<ContextRow> rows = jdbc.query("SELECT DISTINCT d.document_id,v.version_id,COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,'')," +
                        "COALESCE(m.manual_document_type_override,m.auto_document_type,''),COALESCE(m.manual_tags,'[]') " +
                        "FROM summary s JOIN document_version v ON v.version_id=s.version_id JOIN document d ON d.document_id=v.document_id " +
                        "LEFT JOIN document_metadata m ON m.version_id=v.version_id WHERE s.summary_id IN (" + placeholders + ")",
                (rs, rowNum) -> new ContextRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), tags(rs.getString(7))), ids.toArray());
        LinkedHashSet<String> docs = new LinkedHashSet<>(), versions = new LinkedHashSet<>(), departments = new LinkedHashSet<>(), categories = new LinkedHashSet<>(),
                companies = new LinkedHashSet<>(), types = new LinkedHashSet<>(), tags = new LinkedHashSet<>();
        for (ContextRow row : rows) {
            add(docs, row.documentId()); add(versions, row.versionId()); add(departments, row.department()); add(categories, row.category());
            add(companies, row.company()); add(types, row.documentType()); tags.addAll(row.tags());
        }
        return new DerivedContext(List.copyOf(docs), List.copyOf(versions), List.copyOf(departments), List.copyOf(categories), List.copyOf(companies), List.copyOf(types), List.copyOf(tags));
    }

    private RawBlock rawBlock(ResultSet rs, Evidence evidence) throws SQLException {
        List<Double> pageSize = List.of();
        if (evidence != null) pageSize = doubles(evidence.location().get("page_size"));
        return new RawBlock(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                strings(rs.getString(7)), integer(rs, 8), integer(rs, 9), doubles(rs.getString(10)), pageSize, strings(rs.getString(11)), rs.getString(12), rs.getInt(13));
    }

    private String readObjectText(String key) {
        var object = objects.get(key);
        try (InputStream input = object.stream()) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("cannot read compiled object " + key, e); }
    }

    private List<String> tags(String json) { return strings(json); }
    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot parse JSON string list", e); }
    }
    private List<Double> doubles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<Double>>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }
    private List<Double> doubles(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Double> values = new ArrayList<>();
        for (Object value : list) if (value instanceof Number number) values.add(number.doubleValue());
        return List.copyOf(values);
    }
    private Map<String, Object> objectMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot parse JSON object", e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot encode JSON", e); }
    }

    private static Integer integer(ResultSet rs, int index) throws SQLException { int value = rs.getInt(index); return rs.wasNull() ? null : value; }
    private static LocalDate localDate(java.sql.Date value) { return value == null ? null : value.toLocalDate(); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static <T> List<T> unique(List<T> values) { return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values)); }
    private static void add(LinkedHashSet<String> target, String value) { if (value != null && !value.isBlank()) target.add(value.trim()); }

    private record Header(String documentId, String logicalName, String versionId, Metadata metadata, String manualDocumentType) {}
    private record SummaryHeader(String id, String unitId, String documentId, String versionId, String objectKey, String contentHash,
                                 String grounding, String coverage, String lifecycle, String model, Instant createdAt) {}
    private record FactHeader(String id, String section, String text, String grounding) {}
    private record EvidenceRef(String evidenceId, String blockId) {}
    private record ContextRow(String documentId, String versionId, String department, String category, String company, String documentType, List<String> tags) {}
}
