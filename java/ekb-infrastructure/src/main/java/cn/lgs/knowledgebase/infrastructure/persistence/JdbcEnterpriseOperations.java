package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcEnterpriseOperations implements EnterpriseOperations.Repository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;
  private final JdbcPublishedGraphReader graphReader;
  private final String knowledgeBaseId;

  public JdbcEnterpriseOperations(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    this(jdbc, tx, mapper, "enterprise");
  }

  public JdbcEnterpriseOperations(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, String knowledgeBaseId) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.mapper = mapper;
    this.graphReader = new JdbcPublishedGraphReader(jdbc, knowledgeBaseId);
    this.knowledgeBaseId = knowledgeBaseId;
  }

  @Override
  public OverviewView overview() {
    return overview(10);
  }

  @Override
  public OverviewView overview(int clearance) {
    var row =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT a.document_id), COUNT(*), (SELECT COUNT(*) FROM raw_block b JOIN kb_source_authority a ON a.version_id=b.version_id WHERE a.security_level<=?), (SELECT COUNT(*) FROM evidence e JOIN kb_source_authority a ON a.version_id=e.version_id WHERE e.active=TRUE AND a.security_level<=?) FROM kb_source_authority a WHERE a.security_level<=?",
            (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)},
            clearance,
            clearance,
            clearance);
    int summaries =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM summary s JOIN kb_source_authority a ON a.version_id=s.version_id WHERE s.lifecycle='ACTIVE' AND a.security_level<=?",
            Integer.class,
            clearance);
    int wikis =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM wiki WHERE " + wikiAuthority("wiki", clearance), Integer.class);
    int[] summaryMetrics =
        jdbc.queryForObject(
            "SELECT COUNT(*) FILTER (WHERE s.lifecycle='STALE'),COUNT(*) FILTER (WHERE s.lifecycle='ACTIVE' AND s.grounding_status='PASS'),COUNT(*) FILTER (WHERE s.lifecycle='ACTIVE' AND s.coverage_status='PASS') FROM summary s JOIN kb_source_authority a ON a.version_id=s.version_id WHERE a.security_level<=?",
            (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
            clearance);
    int[] tasks =
        jdbc.queryForObject(
            "SELECT COUNT(*) FILTER (WHERE ct.status IN ('PENDING','RETRY_WAIT')),COUNT(*) FILTER (WHERE ct.status='RUNNING'),COUNT(*) FILTER (WHERE ct.status='FAILED') FROM compile_task ct JOIN kb_source_authority a ON a.version_id=ct.version_id WHERE a.security_level<=?",
            (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
            clearance);
    int downloads =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM source_usage_contribution c JOIN kb_source_authority a ON a.version_id=c.version_id WHERE a.security_level<=? AND c.created_at>=clock_timestamp()-INTERVAL '30 days'",
            Integer.class,
            clearance);
    return new OverviewView(
        row[0],
        row[1],
        row[1],
        0,
        0,
        row[2],
        row[3],
        summaries,
        summaryMetrics[0],
        summaryMetrics[1],
        summaryMetrics[2],
        wikis,
        0,
        0,
        0,
        downloads,
        0,
        tasks[0],
        tasks[1],
        tasks[2]);
  }

  @Override
  public Page<DocumentVersionView> documents(PageQuery q) {
    return documents(q, 10);
  }

  @Override
  public Page<DocumentVersionView> documents(PageQuery q, int clearance) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where =
        new SqlWhere(
            "EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=v.version_id AND a.security_level <= "
                + clearance
                + ")");
    if (text(query.query())) {
      String like = "%" + query.query().trim() + "%";
      where.add(
          "(d.logical_name LIKE ? OR v.original_filename LIKE ? OR COALESCE(m.company,'') LIKE ? OR COALESCE(m.source_department,'') LIKE ? OR COALESCE(m.manual_document_type_override,m.auto_document_type,'') LIKE ?)",
          like,
          like,
          like,
          like,
          like);
    }
    where.eq("v.lifecycle", upperExceptAll(query.lifecycle()));
    where.eq("m.company", exceptAll(query.company()));
    where.eq("m.source_department", exceptAll(query.sourceDepartment()));
    where.eq(
        "COALESCE(m.manual_document_type_override,m.auto_document_type,'')",
        exceptAll(query.documentType()));
    where.eq("m.material_category", exceptAll(query.materialCategory()));
    where.eq("d.document_id", trim(query.documentId()));
    String from =
        " FROM document d JOIN document_version v ON v.document_id=d.document_id LEFT JOIN document_metadata m ON m.version_id=v.version_id WHERE "
            + where.sql();
    long total = count("SELECT COUNT(*)" + from, where.args());
    String sql =
        """
                SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.original_filename,
                       COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,''),
                       COALESCE(m.manual_document_type_override,m.auto_document_type,''),m.document_date,m.manual_tags,v.parsed_at,
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id),
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.grounding_status='PASS'),
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.coverage_status='PASS'),
                       COALESCE((SELECT dr.status FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                       COALESCE((SELECT dr.reason FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                       COALESCE((SELECT COUNT(*) FROM source_usage_contribution evh WHERE evh.version_id=v.version_id AND evh.created_at>=clock_timestamp()-INTERVAL '30 days'),0),
                       m.security_level
                """
            + from
            + " ORDER BY d.updated_at DESC,v.version_no DESC LIMIT ? OFFSET ?";
    List<Object> args = withPage(where.args(), query);
    List<DocumentVersionView> items =
        jdbc.query(
            sql,
            (rs, n) ->
                new DocumentVersionView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(),
                    stringList(rs.getString(12)),
                    instant(rs.getTimestamp(13)),
                    rs.getInt(14),
                    rs.getInt(15),
                    rs.getInt(16),
                    rs.getString(17),
                    rs.getString(18),
                    rs.getInt(19),
                    rs.getObject(20) == null ? null : rs.getInt(20)),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public Optional<DocumentVersionView> updateDocumentMetadata(DocumentMetadataUpdate update) {
    if (update == null || trim(update.versionId()).isEmpty()) return Optional.empty();
    String documentId =
        tx.execute(
            status -> {
              Optional<String> found =
                  jdbc
                      .query(
                          "SELECT document_id FROM document_version WHERE version_id=?",
                          (rs, rowNum) -> rs.getString(1),
                          update.versionId())
                      .stream()
                      .findFirst();
              if (found.isEmpty()) return "";
              jdbc.update(
                  "UPDATE document SET logical_name=? WHERE document_id=?",
                  update.logicalName(),
                  found.get());
              int metadataRows =
                  jdbc.update(
                      "UPDATE document_metadata SET security_level=COALESCE(?::smallint,security_level),acl_version=acl_version+CASE WHEN ?::smallint IS NOT NULL AND ?::smallint<>security_level THEN 1 ELSE 0 END,source_department=?,material_category=?,manual_tags=?,manual_document_type_override=?,document_date=? WHERE version_id=?",
                      update.securityLevel(),
                      update.securityLevel(),
                      update.securityLevel(),
                      nullable(update.sourceDepartment()),
                      nullable(update.materialCategory()),
                      json(update.manualTags()),
                      nullable(update.documentType()),
                      update.documentDate(),
                      update.versionId());
              if (metadataRows == 0) {
                jdbc.update(
                    "INSERT INTO document_metadata(version_id,source_department,material_category,manual_tags,manual_document_type_override,document_date,security_level) VALUES(?,?,?,?,?,?,COALESCE(?::smallint,10))",
                    update.versionId(),
                    nullable(update.sourceDepartment()),
                    nullable(update.materialCategory()),
                    json(update.manualTags()),
                    nullable(update.documentType()),
                    update.documentDate(),
                    update.securityLevel());
              }
              return found.get();
            });
    if (documentId == null || documentId.isBlank()) return Optional.empty();
    return documentVersion(update.versionId());
  }

  @Override
  public Optional<DocumentDetailView> documentDetail(String versionId, int blockLimit) {
    return documentDetail(versionId, blockLimit, 10);
  }

  @Override
  public Optional<DocumentDetailView> documentDetail(
      String versionId, int blockLimit, int clearance) {
    String id = trim(versionId);
    if (id.isEmpty()) return Optional.empty();
    Optional<String[]> identity =
        jdbc
            .query(
                "SELECT document_id,version_id FROM kb_source_authority WHERE version_id=? AND security_level <= "
                    + clearance,
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)},
                id)
            .stream()
            .findFirst();
    if (identity.isEmpty()) return Optional.empty();
    DocumentKnowledgeStatus knowledge =
        jdbc.queryForObject(
            """
                SELECT
                  (SELECT COUNT(*) FROM raw_block WHERE version_id=?),
                  (SELECT COUNT(*) FROM evidence WHERE version_id=? AND active=TRUE),
                  (SELECT COUNT(*) FROM summary WHERE version_id=?),
                  (SELECT COUNT(*) FROM summary WHERE version_id=? AND lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM summary WHERE version_id=? AND grounding_status='PASS'),
                  (SELECT COUNT(*) FROM summary WHERE version_id=? AND coverage_status='PASS'),
                  (SELECT COUNT(*) FROM source_entity WHERE version_id=?),
                  (SELECT COUNT(*) FROM entity_mapping em JOIN source_entity se ON se.source_entity_id=em.source_entity_id WHERE se.version_id=? AND em.status='APPLIED' AND EXISTS(SELECT 1 FROM kb_graph_relation rf WHERE (rf.source_entity_id=em.canonical_entity_id OR rf.target_entity_id=em.canonical_entity_id) AND %s)),
                  (SELECT COUNT(DISTINCT rf.relation_id) FROM kb_graph_relation rf JOIN kb_graph_evidence rs ON rs.kb_id=rf.kb_id AND rs.relation_id=rf.relation_id AND rs.revision_id=rf.revision_id JOIN evidence e ON e.evidence_id=rs.evidence_id WHERE e.version_id=? AND %s),
                  (SELECT COUNT(*) FROM kb_graph_relation rf JOIN kb_graph_evidence rs ON rs.kb_id=rf.kb_id AND rs.relation_id=rf.relation_id AND rs.revision_id=rf.revision_id JOIN evidence e ON e.evidence_id=rs.evidence_id WHERE e.version_id=? AND %s),
                  (SELECT COUNT(DISTINCT wcf.claim_id) FROM wiki_claim_fact wcf JOIN summary_fact sf ON sf.fact_id=wcf.fact_id JOIN summary s ON s.summary_id=sf.summary_id WHERE s.version_id=?),
                  (SELECT COUNT(DISTINCT we.evidence_id) FROM wiki_evidence we JOIN wiki_claim_fact wcf ON wcf.claim_id=we.claim_id JOIN summary_fact sf ON sf.fact_id=wcf.fact_id JOIN summary s ON s.summary_id=sf.summary_id WHERE s.version_id=?),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status IN ('PENDING','RETRY_WAIT')),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status='RUNNING'),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status='FAILED')
                """
                .formatted(
                    SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId),
                    SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId),
                    SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId)),
            (rs, rowNum) ->
                new DocumentKnowledgeStatus(
                    rs.getInt(1),
                    rs.getInt(2),
                    rs.getInt(3),
                    rs.getInt(4),
                    rs.getInt(5),
                    rs.getInt(6),
                    rs.getInt(7),
                    rs.getInt(8),
                    rs.getInt(9),
                    rs.getInt(10),
                    rs.getInt(11),
                    rs.getInt(12),
                    rs.getInt(13),
                    rs.getInt(14),
                    rs.getInt(15)),
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id,
            id);
    int maxBlocks = blockLimit <= 0 ? 100 : Math.min(blockLimit, 500);
    List<RawBlockPreview> blocks =
        jdbc.query(
            "SELECT rb.block_id,rb.block_no,rb.block_type,rb.heading_path,rb.page_start,rb.page_end,COALESCE(ev.evidence_id,''),rb.raw_content,rb.content_hash FROM raw_block rb LEFT JOIN evidence ev ON ev.version_id=rb.version_id AND ev.block_id=rb.block_id AND ev.active=TRUE WHERE rb.version_id=? ORDER BY rb.block_no LIMIT ?",
            (rs, rowNum) ->
                new RawBlockPreview(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getString(3),
                    stringList(rs.getString(4)),
                    rs.getObject(5, Integer.class),
                    rs.getObject(6, Integer.class),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9)),
            id,
            maxBlocks);
    String[] key = identity.orElseThrow();
    return Optional.of(new DocumentDetailView(key[0], key[1], knowledge, blocks));
  }

  @Override
  public Page<WikiView> wikis(PageQuery q) {
    return wikis(q, 10);
  }

  @Override
  public Page<WikiView> wikis(PageQuery q, int clearance) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where = new SqlWhere(wikiAuthority("wiki", clearance));
    if (text(query.query())) {
      String like = "%" + query.query().trim() + "%";
      where.add("(title LIKE ? OR wiki_type LIKE ?)", like, like);
    }
    where.eq("lifecycle", upperExceptAll(query.lifecycle()));
    where.eq("wiki_type", exceptAll(query.wikiType()));
    where.eq("wiki_id", trim(query.wikiId()));
    where.eq("canonical_subject_id", trim(query.canonicalSubjectId()));
    long total = count("SELECT COUNT(*) FROM wiki WHERE " + where.sql(), where.args());
    List<Object> args = withPage(where.args(), query);
    List<WikiView> items =
        jdbc.query(
            "SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE "
                + where.sql()
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, n) ->
                new WikiView(
                    rs.getString(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    List.of()),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public Optional<WikiView> wiki(String wikiId, Long version) {
    return wiki(wikiId, version, 10);
  }

  @Override
  public Optional<WikiView> wiki(String wikiId, Long version, int clearance) {
    String id = trim(wikiId);
    if (id.isEmpty()) return Optional.empty();
    String sql =
        "SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE "
            + wikiAuthority("wiki", clearance)
            + " AND wiki_id=?"
            + (version == null || version <= 0
                ? " ORDER BY wiki_version DESC LIMIT 1"
                : " AND wiki_version=? LIMIT 1");
    Object[] args =
        version == null || version <= 0 ? new Object[] {id} : new Object[] {id, version};
    return jdbc
        .query(
            sql,
            (rs, n) -> {
              String currentId = rs.getString(1);
              long currentVersion = rs.getLong(2);
              return new WikiView(
                  currentId,
                  currentVersion,
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  claims(currentId, currentVersion));
            },
            args)
        .stream()
        .findFirst();
  }

  private static String wikiAuthority(String alias, int clearance) {
    return SourceAccessSql.wiki(alias, clearance);
  }

  @Override
  public Page<KnowledgeEntryView> knowledgeEntries(EntryQuery query, int clearance) {
    EntryQuery q = query == null ? new EntryQuery(1, 30, null, null) : query;
    String stage = upperExceptAll(q.stage());
    if (stage != null && !stage.isBlank() && !List.of("CONCEPT", "WIKI").contains(stage))
      throw new IllegalArgumentException("unknown knowledge entry stage");
    SqlWhere where = new SqlWhere(entryAuthority(clearance));
    where.add("e.knowledge_base_id=?", knowledgeBaseId);
    where.eq("e.stage", stage);
    if (text(q.query())) {
      String like = "%" + q.query().trim() + "%";
      where.add(
          "(e.preferred_label ILIKE ? OR e.entry_type ILIKE ? OR e.aliases::text ILIKE ?)",
          like,
          like,
          like);
    }
    long total =
        count("SELECT COUNT(*) FROM kb_knowledge_entry e WHERE " + where.sql(), where.args());
    List<Object> args = new ArrayList<>(where.args());
    args.add(q.normalizedPageSize());
    args.add(q.offset());
    List<KnowledgeEntryView> items =
        jdbc.query(
            entrySelect(false)
                + " WHERE "
                + where.sql()
                + " ORDER BY e.updated_at DESC,e.entry_id LIMIT ? OFFSET ?",
            (rs, n) -> entryView(rs),
            args.toArray());
    return new Page<>(items, total, q.normalizedPage(), q.normalizedPageSize());
  }

  @Override
  public Optional<KnowledgeEntryView> knowledgeEntry(String entryId, int clearance) {
    String id = trim(entryId);
    if (id.isEmpty()) return Optional.empty();
    // Prose and complete provenance are read under one statement snapshot. A second unguarded
    // source query could return old prose after an ACL change between the two reads.
    return jdbc
        .query(
            entrySelect(true)
                + " WHERE e.knowledge_base_id=? AND e.entry_id=? AND "
                + entryAuthority(clearance),
            (rs, n) -> entryView(rs),
            knowledgeBaseId,
            id)
        .stream()
        .findFirst();
  }

  private KnowledgeEntryView entryView(java.sql.ResultSet rs) throws java.sql.SQLException {
    try {
      return new KnowledgeEntryView(
          rs.getString(1),
          rs.getLong(2),
          rs.getString(3),
          rs.getString(4),
          stringList(rs.getString(5)),
          rs.getString(6),
          rs.getString(7),
          rs.getString(8),
          map(rs.getString(9)),
          rs.getTimestamp(10).toInstant(),
          rs.getInt(11),
          entrySources(rs.getString(12)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("cannot parse persisted entry provenance", e);
    }
  }

  private List<EntrySourceView> entrySources(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    var sources = new ArrayList<EntrySourceView>();
    for (var source : mapper.readTree(json)) {
      var facts = new ArrayList<EntryFactView>();
      for (var fact : source.path("facts")) {
        var evidence = new ArrayList<String>();
        fact.path("evidenceIds").forEach(id -> evidence.add(id.asText()));
        facts.add(
            new EntryFactView(
                fact.path("factId").asText(), fact.path("factText").asText(), evidence));
      }
      sources.add(
          new EntrySourceView(
              source.path("documentId").asText(),
              source.path("versionId").asText(),
              source.path("versionNo").asLong(),
              source.path("logicalName").asText(),
              source.path("summaryId").asText(),
              source.path("conceptId").asText(),
              facts));
    }
    return List.copyOf(sources);
  }

  private static String entrySelect(boolean detail) {
    String sources =
        detail
            ? """
      COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'documentId',s.document_id,'versionId',s.source_version_id,'versionNo',v.version_no,
        'logicalName',d.logical_name,'summaryId',s.summary_id,'conceptId',s.concept_id,
        'facts',COALESCE((SELECT jsonb_agg(jsonb_build_object('factId',f.fact_id,'factText',f.fact_text,
          'evidenceIds',COALESCE((SELECT jsonb_agg(DISTINCT ev.evidence_id ORDER BY ev.evidence_id)
            FROM summary_evidence se JOIN evidence ev ON ev.evidence_id=se.evidence_id
            WHERE se.fact_id=f.fact_id AND ev.active AND ev.version_id=s.source_version_id),'[]'::jsonb)) ORDER BY f.fact_id)
          FROM summary_fact f WHERE f.summary_id=s.summary_id AND f.grounding_status='PASS'
          AND jsonb_exists(s.fact_ids,f.fact_id)),'[]'::jsonb)) ORDER BY d.logical_name,s.source_version_id,s.summary_id,s.concept_id)
        FROM kb_entry_source s JOIN document d ON d.document_id=s.document_id
        JOIN document_version v ON v.version_id=s.source_version_id
        WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id),'[]'::jsonb)::text
      """
            : "'[]'::text";
    return "SELECT e.entry_id,e.revision,e.entry_type,e.preferred_label,e.aliases::text,e.description,e.stage,e.lifecycle,e.scope_json::text,e.updated_at,"
        + "(SELECT count(DISTINCT s.source_version_id) FROM kb_entry_source s WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id),"
        + sources
        + " FROM kb_knowledge_entry e";
  }

  private static String entryAuthority(int clearance) {
    if (clearance < 10 || clearance > 127) throw new IllegalArgumentException("invalid clearance");
    return """
      e.lifecycle='ACTIVE'
      AND EXISTS(SELECT 1 FROM kb_entry_source s WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id)
      AND EXISTS(SELECT 1 FROM kb_entry_build_input b WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision)
      AND NOT EXISTS(SELECT 1 FROM kb_entry_build_input b
        LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id
        LEFT JOIN summary sm ON sm.summary_id=b.summary_id AND sm.version_id=b.source_version_id
        WHERE b.knowledge_base_id=e.knowledge_base_id AND b.entry_id=e.entry_id AND b.entry_revision=e.revision
        AND (a.version_id IS NULL OR a.security_level>%1$d OR a.acl_version IS DISTINCT FROM b.acl_version
          OR sm.summary_id IS NULL OR sm.lifecycle IS DISTINCT FROM 'ACTIVE'
          OR sm.grounding_status IS DISTINCT FROM 'PASS' OR sm.coverage_status IS DISTINCT FROM 'PASS'))
      AND NOT EXISTS(SELECT 1 FROM kb_entry_source s
        LEFT JOIN kb_source_authority a ON a.version_id=s.source_version_id
        LEFT JOIN summary sm ON sm.summary_id=s.summary_id AND sm.version_id=s.source_version_id
        WHERE s.knowledge_base_id=e.knowledge_base_id AND s.entry_id=e.entry_id
        AND (a.version_id IS NULL OR a.document_id IS DISTINCT FROM s.document_id
          OR a.security_level>%1$d OR a.acl_version IS DISTINCT FROM s.acl_version
          OR sm.summary_id IS NULL OR sm.lifecycle IS DISTINCT FROM 'ACTIVE'
          OR sm.grounding_status IS DISTINCT FROM 'PASS' OR sm.coverage_status IS DISTINCT FROM 'PASS'
          OR jsonb_array_length(s.fact_ids)=0
          OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(s.fact_ids) fact_id
            WHERE NOT EXISTS(SELECT 1 FROM summary_fact f
              WHERE f.fact_id=fact_id.value AND f.summary_id=s.summary_id AND f.grounding_status='PASS'
              AND EXISTS(SELECT 1 FROM summary_evidence se JOIN evidence ev ON ev.evidence_id=se.evidence_id
                WHERE se.fact_id=f.fact_id AND ev.active AND ev.version_id=s.source_version_id)))))
      AND NOT EXISTS(SELECT 1 FROM kb_entry_pending_source p WHERE p.knowledge_base_id=e.knowledge_base_id
        AND p.entry_id=e.entry_id AND p.associated_at IS NULL)
      """
        .formatted(clearance);
  }

  private List<ClaimView> claims(String wikiId, long version) {
    List<ClaimView> base =
        jdbc.query(
            "SELECT claim_id,section,claim_text,grounding_status,temporal_status FROM wiki_claim WHERE wiki_id=? AND wiki_version=? ORDER BY created_at,claim_id",
            (rs, n) ->
                new ClaimView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    List.of(),
                    List.of()),
            wikiId,
            version);
    if (base.isEmpty()) return List.of();
    List<ClaimView> out = new ArrayList<>(base.size());
    for (ClaimView claim : base) {
      List<String> facts =
          jdbc.query(
              "SELECT fact_id FROM wiki_claim_fact WHERE claim_id=? ORDER BY fact_id",
              (rs, n) -> rs.getString(1),
              claim.claimId());
      List<String> evidence =
          jdbc.query(
              "SELECT evidence_id FROM wiki_evidence WHERE claim_id=? ORDER BY evidence_id",
              (rs, n) -> rs.getString(1),
              claim.claimId());
      out.add(
          new ClaimView(
              claim.claimId(),
              claim.section(),
              claim.claimText(),
              claim.groundingStatus(),
              claim.temporalStatus(),
              facts,
              evidence));
    }
    return List.copyOf(out);
  }

  @Override
  public Optional<EvidenceDetail> evidence(String evidenceId) {
    return evidence(evidenceId, 10);
  }

  @Override
  public Optional<EvidenceDetail> evidence(String evidenceId, int clearance) {
    return jdbc
        .query(
            "SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,rb.raw_content,e.content_hash,e.active,v.original_filename FROM evidence e JOIN raw_block rb ON rb.block_id=e.block_id JOIN document_version v ON v.version_id=e.version_id WHERE e.active=TRUE AND EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=e.version_id AND a.security_level <= "
                + clearance
                + ") AND e.evidence_id=?",
            (rs, n) ->
                new EvidenceDetail(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    map(rs.getString(6)),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getBoolean(9),
                    rs.getString(10)),
            evidenceId)
        .stream()
        .findFirst();
  }

  @Override
  public Page<EvidenceListView> evidences(PageQuery q) {
    return evidences(q, 10);
  }

  @Override
  public Page<EvidenceListView> evidences(PageQuery q, int clearance) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where =
        new SqlWhere(
            "e.active=TRUE AND EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=v.version_id AND a.security_level <= "
                + clearance
                + ")");
    if (query.active() != null) where.add("e.active=?", query.active());
    if (text(query.query())) {
      String like = "%" + query.query().trim() + "%";
      where.add(
          "(d.logical_name LIKE ? OR v.original_filename LIKE ? OR e.evidence_id LIKE ? OR e.version_id LIKE ? OR e.block_id LIKE ? OR rb.raw_content LIKE ?)",
          like,
          like,
          like,
          like,
          like,
          like);
    }
    String from =
        " FROM evidence e JOIN document d ON d.document_id=e.document_id JOIN document_version v ON v.version_id=e.version_id JOIN raw_block rb ON rb.block_id=e.block_id WHERE "
            + where.sql();
    long total = count("SELECT COUNT(*)" + from, where.args());
    List<Object> args = withPage(where.args(), query);
    List<EvidenceListView> items =
        jdbc.query(
            "SELECT e.evidence_id,e.document_id,d.logical_name,e.version_id,v.version_no,v.original_filename,e.block_id,rb.block_type,e.raw_location,LEFT(rb.raw_content,320),e.active,e.created_at"
                + from
                + " ORDER BY e.active DESC,e.created_at DESC LIMIT ? OFFSET ?",
            (rs, n) ->
                new EvidenceListView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getLong(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    map(rs.getString(9)),
                    rs.getString(10),
                    rs.getBoolean(11),
                    instant(rs.getTimestamp(12))),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public Page<TaskView> tasks(PageQuery q, String actorId, boolean admin, String source) {
    return tasks(q, actorId, admin, source, 10);
  }

  @Override
  public Page<TaskView> tasks(
      PageQuery q, String actorId, boolean admin, String source, int clearance) {
    PageQuery query = q == null ? emptyQuery() : q;
    String union;
    List<Object> unionArgs = new ArrayList<>();
    if (admin) {
      union =
          "(SELECT ct.task_id,'COMPILE' source,'' batch_id,'' file_id,COALESCE(ct.version_id,'') version_id,COALESCE(d.logical_name,'') logical_name,ct.task_type,ct.status,ct.status progress_stage,ct.attempt,COALESCE(ct.error_code,'') error_code,COALESCE(ct.error_message,'') error_message,ct.started_at,ct.finished_at,ct.created_at,ct.next_attempt_at FROM compile_task ct LEFT JOIN document_version v ON v.version_id=ct.version_id LEFT JOIN document d ON d.document_id=v.document_id WHERE ct.version_id IS NOT NULL UNION ALL SELECT f.file_id,'INGEST',f.batch_id,f.file_id,COALESCE(f.version_id,''),f.logical_name,'INGEST',f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,''),f.started_at,f.finished_at,f.created_at,NULL FROM ingestion_file f JOIN ingestion_batch b ON b.batch_id=f.batch_id WHERE f.version_id IS NOT NULL OR b.created_by=?) u";
      unionArgs.add(actorId);
    } else {
      union =
          "(SELECT f.file_id task_id,'INGEST' source,f.batch_id,f.file_id,COALESCE(f.version_id,'') version_id,f.logical_name,'INGEST' task_type,f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,'') error_message,f.started_at,f.finished_at,f.created_at,NULL next_attempt_at FROM ingestion_file f JOIN ingestion_batch b ON b.batch_id=f.batch_id WHERE b.created_by=?) u";
      unionArgs.add(actorId);
    }
    SqlWhere where =
        new SqlWhere(
            "(NULLIF(u.version_id,'') IS NULL OR "
                + SourceAccessSql.version("u.version_id", clearance)
                + ")");
    where.eq("u.status", upperExceptAll(query.status()));
    where.eq("u.source", upperExceptAll(source));
    if (text(query.query())) {
      String like = "%" + query.query().trim() + "%";
      where.add(
          "(u.logical_name LIKE ? OR u.task_id LIKE ? OR u.version_id LIKE ? OR u.task_type LIKE ? OR u.error_message LIKE ? OR u.progress_stage LIKE ?)",
          like,
          like,
          like,
          like,
          like,
          like);
    }
    List<Object> baseArgs = new ArrayList<>(unionArgs);
    baseArgs.addAll(where.args());
    long total = count("SELECT COUNT(*) FROM " + union + " WHERE " + where.sql(), baseArgs);
    List<Object> args = withPage(baseArgs, query);
    List<TaskView> items =
        jdbc.query(
            "SELECT task_id,source,batch_id,file_id,version_id,logical_name,task_type,status,progress_stage,attempt,error_code,error_message,started_at,finished_at,created_at,next_attempt_at FROM "
                + union
                + " WHERE "
                + where.sql()
                + " ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?",
            (rs, n) ->
                new TaskView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getInt(10),
                    rs.getString(11),
                    rs.getString(12),
                    instant(rs.getTimestamp(13)),
                    instant(rs.getTimestamp(14)),
                    instant(rs.getTimestamp(15)),
                    instant(rs.getTimestamp(16))),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public QualityView quality(int limit) {
    return quality(limit, 10);
  }

  @Override
  public QualityView quality(int limit, int clearance) {
    int max = limit <= 0 ? 100 : Math.min(limit, 300);
    String summaryAccess = SourceAccessSql.version("s.version_id", clearance);
    String wikiAccess = SourceAccessSql.wiki("w", clearance);
    String relationAccess = SourceAccessSql.publishedGraph("rf", clearance, knowledgeBaseId);
    String entityAccess = SourceAccessSql.version("se.version_id", clearance);
    String taskAccess = SourceAccessSql.version("ct.version_id", clearance);
    int[] counts =
        new int[] {
          (int)
              count(
                  "SELECT COUNT(*) FROM summary s WHERE s.grounding_status='FAIL' AND "
                      + summaryAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM summary s WHERE s.coverage_status='FAIL' AND "
                      + summaryAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM wiki_claim wc JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version WHERE wc.grounding_status='FAIL' AND "
                      + wikiAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM wiki_claim wc JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version WHERE wc.temporal_status='CONFLICT' AND "
                      + wikiAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM kb_graph_relation rf WHERE rf.conflict_status<>'NONE' AND "
                      + relationAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM source_entity se LEFT JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id WHERE (em.source_entity_id IS NULL OR em.status<>'APPLIED') AND "
                      + entityAccess,
                  List.of()),
          (int)
              count(
                  "SELECT COUNT(*) FROM compile_task ct WHERE ct.status='FAILED' AND ct.task_type IN ('SUMMARY','GRAPH','WIKI') AND "
                      + taskAccess,
                  List.of())
        };
    List<QualityIssueView> issues = new ArrayList<>();
    issues.addAll(
        jdbc.query(
            """
                SELECT d.logical_name,s.version_id,s.summary_id,s.grounding_status,s.coverage_status,
                       string_agg(DISTINCT se.evidence_id, ',' ORDER BY se.evidence_id),s.created_at
                FROM summary s
                JOIN document_version v ON v.version_id=s.version_id
                JOIN document d ON d.document_id=v.document_id
                LEFT JOIN summary_fact sf ON sf.summary_id=s.summary_id
                LEFT JOIN summary_evidence se ON se.fact_id=sf.fact_id
                WHERE (s.grounding_status='FAIL' OR s.coverage_status='FAIL') AND %s
                GROUP BY d.logical_name,s.version_id,s.summary_id,s.grounding_status,s.coverage_status,s.created_at
                ORDER BY s.created_at DESC LIMIT ?
                """
                .formatted(summaryAccess),
            (rs, rowNum) ->
                new QualityIssueView(
                    "SUMMARY_QUALITY",
                    "HIGH",
                    rs.getString(1),
                    "Grounding=" + rs.getString(4) + ", Coverage=" + rs.getString(5),
                    rs.getString(2),
                    rs.getString(3),
                    split(rs.getString(6)),
                    instant(rs.getTimestamp(7))),
            max));
    if (issues.size() < max) {
      issues.addAll(
          jdbc.query(
              """
                  SELECT w.title,COALESCE(MIN(s.version_id),''),wc.claim_id,wc.grounding_status,wc.temporal_status,
                         string_agg(DISTINCT we.evidence_id, ',' ORDER BY we.evidence_id),wc.created_at
                  FROM wiki_claim wc
                  JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version
                  LEFT JOIN wiki_evidence we ON we.claim_id=wc.claim_id
                  LEFT JOIN wiki_claim_fact wcf ON wcf.claim_id=wc.claim_id
                  LEFT JOIN summary_fact sf ON sf.fact_id=wcf.fact_id
                  LEFT JOIN summary s ON s.summary_id=sf.summary_id
                  WHERE (wc.grounding_status='FAIL' OR wc.temporal_status='CONFLICT') AND %s
                  GROUP BY w.title,wc.claim_id,wc.grounding_status,wc.temporal_status,wc.created_at
                  ORDER BY wc.created_at DESC LIMIT ?
                  """
                  .formatted(wikiAccess),
              (rs, rowNum) ->
                  new QualityIssueView(
                      "WIKI_CLAIM",
                      "HIGH",
                      rs.getString(1),
                      "Grounding=" + rs.getString(4) + ", Temporal=" + rs.getString(5),
                      rs.getString(2),
                      rs.getString(3),
                      split(rs.getString(6)),
                      instant(rs.getTimestamp(7))),
              max - issues.size()));
    }
    if (issues.size() < max) {
      issues.addAll(
          jdbc.query(
              """
                  SELECT CONCAT(se.canonical_name,' —',rf.relation_type,'→ ',te.canonical_name),
                         COALESCE(MIN(e.version_id),''),rf.relation_id,rf.conflict_status,
                         string_agg(DISTINCT rs.evidence_id, ',' ORDER BY rs.evidence_id),rf.created_at
                  FROM kb_graph_relation rf
                  JOIN kb_graph_entity se ON se.kb_id=rf.kb_id AND se.entity_id=rf.source_entity_id
                  JOIN kb_graph_entity te ON te.kb_id=rf.kb_id AND te.entity_id=rf.target_entity_id
                  JOIN kb_graph_evidence rs ON rs.kb_id=rf.kb_id AND rs.relation_id=rf.relation_id AND rs.revision_id=rf.revision_id
                  JOIN evidence e ON e.evidence_id=rs.evidence_id
                  WHERE rf.conflict_status<>'NONE' AND %s
                  GROUP BY se.canonical_name,rf.relation_type,te.canonical_name,rf.relation_id,rf.conflict_status,rf.created_at
                  ORDER BY rf.created_at DESC LIMIT ?
                  """
                  .formatted(relationAccess),
              (rs, rowNum) ->
                  new QualityIssueView(
                      "RELATION_CONFLICT",
                      "HIGH",
                      rs.getString(1),
                      "Conflict=" + rs.getString(4),
                      rs.getString(2),
                      rs.getString(3),
                      split(rs.getString(5)),
                      instant(rs.getTimestamp(6))),
              max - issues.size()));
    }
    if (issues.size() < max) {
      issues.addAll(
          jdbc.query(
              """
                  SELECT d.logical_name,se.version_id,se.source_entity_id,se.surface_form,se.entity_type,COALESCE(se.evidence_id,''),se.created_at
                  FROM source_entity se
                  JOIN document_version v ON v.version_id=se.version_id
                  JOIN document d ON d.document_id=v.document_id
                  LEFT JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id
                  WHERE (em.source_entity_id IS NULL OR em.status<>'APPLIED') AND %s
                  ORDER BY se.created_at DESC LIMIT ?
                  """
                  .formatted(entityAccess),
              (rs, rowNum) ->
                  new QualityIssueView(
                      "ENTITY_RESOLUTION",
                      "MEDIUM",
                      rs.getString(1),
                      "未完成实体归一: " + rs.getString(4) + " (" + rs.getString(5) + ")",
                      rs.getString(2),
                      rs.getString(3),
                      text(rs.getString(6)) ? List.of(rs.getString(6)) : List.of(),
                      instant(rs.getTimestamp(7))),
              max - issues.size()));
    }
    if (issues.size() < max) {
      issues.addAll(
          jdbc.query(
              """
                  SELECT COALESCE(d.logical_name,'知识生成'),COALESCE(ct.version_id,''),ct.task_id,ct.task_type,COALESCE(ct.error_code,''),COALESCE(ct.error_message,''),ct.created_at
                  FROM compile_task ct
                  LEFT JOIN document_version v ON v.version_id=ct.version_id
                  LEFT JOIN document d ON d.document_id=v.document_id
                  WHERE ct.status='FAILED' AND ct.task_type IN ('SUMMARY','GRAPH','WIKI') AND %s
                  ORDER BY ct.created_at DESC LIMIT ?
                  """
                  .formatted(taskAccess),
              (rs, rowNum) ->
                  new QualityIssueView(
                      "GENERATION_TASK",
                      "HIGH",
                      rs.getString(1),
                      rs.getString(4) + " / " + rs.getString(5) + ": " + rs.getString(6),
                      rs.getString(2),
                      rs.getString(3),
                      List.of(),
                      instant(rs.getTimestamp(7))),
              max - issues.size()));
    }
    issues.sort(
        java.util.Comparator.comparing(
                QualityIssueView::createdAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
            .thenComparing(QualityIssueView::issueType));
    if (issues.size() > max) issues = new ArrayList<>(issues.subList(0, max));
    return new QualityView(
        counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], issues);
  }

  @Override
  public Facets facets() {
    return facets(10);
  }

  @Override
  public Facets facets(int clearance) {
    return new Facets(
        distinct(
            "SELECT DISTINCT company FROM document_metadata m WHERE EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=m.version_id AND a.security_level <= "
                + clearance
                + ") AND company<>'' ORDER BY company LIMIT 500"),
        distinct(
            "SELECT DISTINCT source_department FROM document_metadata m WHERE EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=m.version_id AND a.security_level <= "
                + clearance
                + ") AND source_department<>'' ORDER BY source_department LIMIT 500"),
        distinct(
            "SELECT DISTINCT COALESCE(manual_document_type_override,auto_document_type,'') value FROM document_metadata m WHERE EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=m.version_id AND a.security_level <= "
                + clearance
                + ") AND COALESCE(manual_document_type_override,auto_document_type,'')<>'' ORDER BY value LIMIT 500"),
        distinct(
            "SELECT DISTINCT material_category FROM document_metadata m WHERE EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=m.version_id AND a.security_level <= "
                + clearance
                + ") AND material_category<>'' ORDER BY material_category LIMIT 500"));
  }

  @Override
  public Page<AuditEventView> audit(PageQuery q) {
    return audit(q, "", 10);
  }

  @Override
  public Page<AuditEventView> audit(PageQuery q, String actorId, int clearance) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where =
        new SqlWhere(
            "((actor_user_id=? AND COALESCE(target_id,'')='') OR target_type IN ('auth','user','settings','model_configuration') OR (target_type='document_version' AND "
                + SourceAccessSql.version("audit_event.target_id", clearance)
                + ") OR (target_type='evidence' AND EXISTS(SELECT 1 FROM evidence e WHERE e.evidence_id=audit_event.target_id AND e.active=TRUE AND "
                + SourceAccessSql.version("e.version_id", clearance)
                + ")))");
    where.add("1=1", actorId);
    if (text(query.query())) {
      String like = "%" + query.query().trim() + "%";
      where.add(
          "(actor_username LIKE ? OR action LIKE ? OR COALESCE(target_name,'') LIKE ? OR COALESCE(target_id,'') LIKE ?)",
          like,
          like,
          like,
          like);
    }
    if (text(query.actor())) {
      String like = "%" + query.actor().trim() + "%";
      where.add("(actor_username LIKE ? OR COALESCE(actor_user_id,'') LIKE ?)", like, like);
    }
    where.eq("action", exceptAll(query.action()));
    where.eq("outcome", upperExceptAll(query.outcome()));
    long total = count("SELECT COUNT(*) FROM audit_event WHERE " + where.sql(), where.args());
    List<Object> args = withPage(where.args(), query);
    List<AuditEventView> items =
        jdbc.query(
            "SELECT event_id,COALESCE(actor_user_id,''),actor_username,actor_role,action,COALESCE(target_type,''),COALESCE(target_id,''),COALESCE(target_name,''),outcome,COALESCE(request_id,''),COALESCE(ip_address,''),COALESCE(user_agent,''),details_json,created_at FROM audit_event WHERE "
                + where.sql()
                + " ORDER BY created_at DESC,event_id DESC LIMIT ? OFFSET ?",
            (rs, n) ->
                new AuditEventView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getString(11),
                    rs.getString(12),
                    map(rs.getString(13)),
                    instant(rs.getTimestamp(14))),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public List<GraphRelationView> graph(
      String query, int depth, int limit, String relationType, Instant validAt) {
    return graph(query, depth, limit, relationType, validAt, 10);
  }

  @Override
  public List<GraphRelationView> graph(
      String query, int depth, int limit, String relationType, Instant validAt, int clearance) {
    return graphReader.browse(query, depth, limit, relationType, validAt, clearance);
  }

  @Override
  public List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds) {
    return evidenceSources(evidenceIds, 10);
  }

  @Override
  public List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds, int clearance) {
    if (evidenceIds == null || evidenceIds.isEmpty()) return List.of();
    List<String> ids =
        evidenceIds.stream()
            .filter(JdbcEnterpriseOperations::text)
            .map(String::trim)
            .distinct()
            .toList();
    if (ids.isEmpty()) return List.of();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    Map<String, EvidenceSourceRef> found = new LinkedHashMap<>();
    List<EvidenceSourceRef> rows =
        jdbc.query(
            "SELECT e.evidence_id,e.document_id,e.version_id,v.original_filename,e.object_key,e.raw_location FROM evidence e JOIN document_version v ON v.version_id=e.version_id WHERE e.active=TRUE AND EXISTS (SELECT 1 FROM kb_source_authority a WHERE a.version_id=e.version_id AND a.security_level <= "
                + clearance
                + ") AND e.evidence_id IN ("
                + placeholders
                + ")",
            (rs, rowNum) ->
                new EvidenceSourceRef(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    map(rs.getString(6))),
            ids.toArray());
    rows.forEach(row -> found.put(row.evidenceId(), row));
    return ids.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
  }

  private Optional<DocumentVersionView> documentVersion(String versionId) {
    String sql =
        """
            SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.original_filename,
                   COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,''),
                   COALESCE(m.manual_document_type_override,m.auto_document_type,''),m.document_date,m.manual_tags,v.parsed_at,
                   (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id),
                   (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.grounding_status='PASS'),
                   (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.coverage_status='PASS'),
                   COALESCE((SELECT dr.status FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                   COALESCE((SELECT dr.reason FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                   COALESCE((SELECT COUNT(*) FROM source_usage_contribution evh WHERE evh.version_id=v.version_id AND evh.created_at>=clock_timestamp()-INTERVAL '30 days'),0),
                   m.security_level
            FROM document d JOIN document_version v ON v.document_id=d.document_id
            LEFT JOIN document_metadata m ON m.version_id=v.version_id
            WHERE v.version_id=? AND d.status<>'DELETED'
            """;
    return jdbc
        .query(
            sql,
            (rs, n) ->
                new DocumentVersionView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(),
                    stringList(rs.getString(12)),
                    instant(rs.getTimestamp(13)),
                    rs.getInt(14),
                    rs.getInt(15),
                    rs.getInt(16),
                    rs.getString(17),
                    rs.getString(18),
                    rs.getInt(19),
                    rs.getObject(20) == null ? null : rs.getInt(20)),
            versionId)
        .stream()
        .findFirst();
  }

  private String json(List<String> values) {
    try {
      return mapper.writeValueAsString(values == null ? List.of() : values);
    } catch (Exception e) {
      throw new IllegalStateException("cannot encode JSON", e);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private long count(String sql, List<Object> args) {
    Long v = jdbc.queryForObject(sql, Long.class, args.toArray());
    return v == null ? 0 : v;
  }

  private List<String> distinct(String sql) {
    return jdbc.query(sql, (rs, n) -> rs.getString(1));
  }

  private Map<String, Object> map(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse persisted JSON", e);
    }
  }

  private List<String> stringList(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse persisted string list", e);
    }
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static List<String> split(String value) {
    if (value == null || value.isBlank()) return List.of();
    return List.of(value.split(","));
  }

  private static boolean text(String v) {
    return v != null && !v.isBlank();
  }

  private static String trim(String v) {
    return v == null ? "" : v.trim();
  }

  private static String exceptAll(String v) {
    String x = trim(v);
    return x.isEmpty() || x.equalsIgnoreCase("ALL") ? "" : x;
  }

  private static String upperExceptAll(String v) {
    String x = exceptAll(v);
    return x.isEmpty() ? "" : x.toUpperCase();
  }

  private static List<Object> withPage(List<Object> args, PageQuery q) {
    List<Object> out = new ArrayList<>(args);
    out.add(q.normalizedPageSize());
    out.add(q.offset());
    return out;
  }

  private static <T> Page<T> page(List<T> items, long total, PageQuery q) {
    return new Page<>(items, total, q.normalizedPage(), q.normalizedPageSize());
  }

  private static PageQuery emptyQuery() {
    return new PageQuery(1, 30, "", "", "", null, "", "", "", "", "", "", "", "", "", "", "");
  }

  private static final class SqlWhere {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> args = new ArrayList<>();

    private SqlWhere(String initial) {
      clauses.add(initial);
    }

    void add(String clause, Object... values) {
      clauses.add(clause);
      Collections.addAll(args, values);
    }

    void eq(String column, String value) {
      if (text(value)) add(column + "=?", value);
    }

    String sql() {
      return String.join(" AND ", clauses);
    }

    List<Object> args() {
      return List.copyOf(args);
    }
  }
}
