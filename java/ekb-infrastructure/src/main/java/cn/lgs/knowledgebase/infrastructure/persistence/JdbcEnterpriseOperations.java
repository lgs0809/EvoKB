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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcEnterpriseOperations implements EnterpriseOperations.Repository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;

  public JdbcEnterpriseOperations(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.mapper = mapper;
  }

  @Override
  public OverviewView overview() {
    return jdbc.queryForObject(
        """
                SELECT
                  (SELECT COUNT(*) FROM document WHERE status='ACTIVE'),
                  (SELECT COUNT(*) FROM document_version),
                  (SELECT COUNT(*) FROM document_version WHERE lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM document_version WHERE lifecycle='PENDING'),
                  (SELECT COUNT(*) FROM document_version WHERE lifecycle='FAILED'),
                  (SELECT COUNT(*) FROM raw_block),
                  (SELECT COUNT(*) FROM evidence WHERE active=TRUE),
                  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM summary WHERE lifecycle='STALE'),
                  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE' AND grounding_status='PASS'),
                  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE' AND coverage_status='PASS'),
                  (SELECT COUNT(*) FROM wiki WHERE lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM wiki WHERE lifecycle='STALE'),
                  (SELECT COUNT(DISTINCT rf.relation_id) FROM relation_fact rf JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE WHERE rf.lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM relation_evidence_support WHERE active=TRUE),
                  (SELECT COUNT(*) FROM effective_version_hit),
                  (SELECT COUNT(DISTINCT version_id) FROM distill_recommendation WHERE status='RECOMMENDED'),
                  ((SELECT COUNT(*) FROM compile_task WHERE status='PENDING') + (SELECT COUNT(*) FROM ingestion_file WHERE status IN ('STAGED','QUEUED'))),
                  ((SELECT COUNT(*) FROM compile_task WHERE status='RUNNING') + (SELECT COUNT(*) FROM ingestion_file WHERE status='RUNNING')),
                  ((SELECT COUNT(*) FROM compile_task WHERE status='FAILED') + (SELECT COUNT(*) FROM ingestion_file WHERE status='FAILED'))
                """,
        (rs, n) ->
            new OverviewView(
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
                rs.getInt(15),
                rs.getInt(16),
                rs.getInt(17),
                rs.getInt(18),
                rs.getInt(19),
                rs.getInt(20)));
  }

  @Override
  public Page<DocumentVersionView> documents(PageQuery q) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where = new SqlWhere("d.status<>'DELETED'");
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
                       COALESCE((SELECT COUNT(*) FROM effective_version_hit evh WHERE evh.version_id=v.version_id),0)
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
                    rs.getInt(19)),
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
                      "UPDATE document_metadata SET source_department=?,material_category=?,manual_tags=?,manual_document_type_override=?,document_date=? WHERE version_id=?",
                      nullable(update.sourceDepartment()),
                      nullable(update.materialCategory()),
                      json(update.manualTags()),
                      nullable(update.documentType()),
                      update.documentDate(),
                      update.versionId());
              if (metadataRows == 0) {
                jdbc.update(
                    "INSERT INTO document_metadata(version_id,source_department,material_category,manual_tags,manual_document_type_override,document_date) VALUES(?,?,?,?,?,?)",
                    update.versionId(),
                    nullable(update.sourceDepartment()),
                    nullable(update.materialCategory()),
                    json(update.manualTags()),
                    nullable(update.documentType()),
                    update.documentDate());
              }
              return found.get();
            });
    if (documentId == null || documentId.isBlank()) return Optional.empty();
    return documentVersion(update.versionId());
  }

  @Override
  public Optional<DocumentDetailView> documentDetail(String versionId, int blockLimit) {
    String id = trim(versionId);
    if (id.isEmpty()) return Optional.empty();
    Optional<String[]> identity =
        jdbc
            .query(
                "SELECT document_id,version_id FROM document_version WHERE version_id=?",
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
                  (SELECT COUNT(*) FROM entity_mapping em JOIN source_entity se ON se.source_entity_id=em.source_entity_id WHERE se.version_id=? AND em.status='APPLIED'),
                  (SELECT COUNT(DISTINCT rf.relation_id) FROM relation_fact rf JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE JOIN summary s ON s.summary_id=rs.summary_id WHERE s.version_id=? AND rf.lifecycle='ACTIVE'),
                  (SELECT COUNT(*) FROM relation_evidence_support rs JOIN summary s ON s.summary_id=rs.summary_id WHERE s.version_id=? AND rs.active=TRUE),
                  (SELECT COUNT(DISTINCT wcf.claim_id) FROM wiki_claim_fact wcf JOIN summary_fact sf ON sf.fact_id=wcf.fact_id JOIN summary s ON s.summary_id=sf.summary_id WHERE s.version_id=?),
                  (SELECT COUNT(DISTINCT we.evidence_id) FROM wiki_evidence we JOIN wiki_claim_fact wcf ON wcf.claim_id=we.claim_id JOIN summary_fact sf ON sf.fact_id=wcf.fact_id JOIN summary s ON s.summary_id=sf.summary_id WHERE s.version_id=?),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status='PENDING'),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status='RUNNING'),
                  (SELECT COUNT(*) FROM compile_task WHERE version_id=? AND status='FAILED')
                """,
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
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where = new SqlWhere("1=1");
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
    String id = trim(wikiId);
    if (id.isEmpty()) return Optional.empty();
    String sql =
        "SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE wiki_id=?"
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
    return jdbc
        .query(
            "SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,rb.raw_content,e.content_hash,e.active FROM evidence e JOIN raw_block rb ON rb.block_id=e.block_id WHERE e.evidence_id=?",
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
                    rs.getBoolean(9)),
            evidenceId)
        .stream()
        .findFirst();
  }

  @Override
  public Page<EvidenceListView> evidences(PageQuery q) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where = new SqlWhere("d.status<>'DELETED'");
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
    PageQuery query = q == null ? emptyQuery() : q;
    String union;
    List<Object> unionArgs = new ArrayList<>();
    if (admin) {
      union =
          "(SELECT ct.task_id,'COMPILE' source,'' batch_id,'' file_id,COALESCE(ct.version_id,'') version_id,COALESCE(d.logical_name,'') logical_name,ct.task_type,ct.status,ct.status progress_stage,ct.attempt,COALESCE(ct.error_code,'') error_code,COALESCE(ct.error_message,'') error_message,ct.started_at,ct.finished_at,ct.created_at FROM compile_task ct LEFT JOIN document_version v ON v.version_id=ct.version_id LEFT JOIN document d ON d.document_id=v.document_id UNION ALL SELECT f.file_id,'INGEST',f.batch_id,f.file_id,COALESCE(f.version_id,''),f.logical_name,'INGEST',f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,''),f.started_at,f.finished_at,f.created_at FROM ingestion_file f) u";
    } else {
      union =
          "(SELECT f.file_id task_id,'INGEST' source,f.batch_id,f.file_id,COALESCE(f.version_id,'') version_id,f.logical_name,'INGEST' task_type,f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,'') error_message,f.started_at,f.finished_at,f.created_at FROM ingestion_file f JOIN ingestion_batch b ON b.batch_id=f.batch_id WHERE b.created_by=?) u";
      unionArgs.add(actorId);
    }
    SqlWhere where = new SqlWhere("1=1");
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
            "SELECT task_id,source,batch_id,file_id,version_id,logical_name,task_type,status,progress_stage,attempt,error_code,error_message,started_at,finished_at,created_at FROM "
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
                    instant(rs.getTimestamp(15))),
            args.toArray());
    return page(items, total, query);
  }

  @Override
  public QualityView quality(int limit) {
    int max = limit <= 0 ? 100 : Math.min(limit, 300);
    int[] counts =
        jdbc.queryForObject(
            """
                SELECT
                  (SELECT COUNT(*) FROM summary WHERE grounding_status='FAIL'),
                  (SELECT COUNT(*) FROM summary WHERE coverage_status='FAIL'),
                  (SELECT COUNT(*) FROM wiki_claim WHERE grounding_status='FAIL'),
                  (SELECT COUNT(*) FROM wiki_claim WHERE temporal_status='CONFLICT'),
                  (SELECT COUNT(*) FROM relation_fact WHERE lifecycle='ACTIVE' AND conflict_status<>'NONE'),
                  (SELECT COUNT(*) FROM source_entity se LEFT JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id WHERE em.source_entity_id IS NULL OR em.status<>'APPLIED'),
                  (SELECT COUNT(*) FROM compile_task WHERE status='FAILED' AND task_type IN ('SUMMARY','GRAPH','WIKI'))
                """,
            (rs, rowNum) ->
                new int[] {
                  rs.getInt(1),
                  rs.getInt(2),
                  rs.getInt(3),
                  rs.getInt(4),
                  rs.getInt(5),
                  rs.getInt(6),
                  rs.getInt(7)
                });
    List<QualityIssueView> issues = new ArrayList<>();
    issues.addAll(
        jdbc.query(
            """
                SELECT d.logical_name,s.version_id,s.summary_id,s.grounding_status,s.coverage_status,
                       GROUP_CONCAT(DISTINCT se.evidence_id ORDER BY se.evidence_id SEPARATOR ','),s.created_at
                FROM summary s
                JOIN document_version v ON v.version_id=s.version_id
                JOIN document d ON d.document_id=v.document_id
                LEFT JOIN summary_fact sf ON sf.summary_id=s.summary_id
                LEFT JOIN summary_evidence se ON se.fact_id=sf.fact_id
                WHERE s.grounding_status='FAIL' OR s.coverage_status='FAIL'
                GROUP BY d.logical_name,s.version_id,s.summary_id,s.grounding_status,s.coverage_status,s.created_at
                ORDER BY s.created_at DESC LIMIT ?
                """,
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
                         GROUP_CONCAT(DISTINCT we.evidence_id ORDER BY we.evidence_id SEPARATOR ','),wc.created_at
                  FROM wiki_claim wc
                  JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version
                  LEFT JOIN wiki_evidence we ON we.claim_id=wc.claim_id
                  LEFT JOIN wiki_claim_fact wcf ON wcf.claim_id=wc.claim_id
                  LEFT JOIN summary_fact sf ON sf.fact_id=wcf.fact_id
                  LEFT JOIN summary s ON s.summary_id=sf.summary_id
                  WHERE wc.grounding_status='FAIL' OR wc.temporal_status='CONFLICT'
                  GROUP BY w.title,wc.claim_id,wc.grounding_status,wc.temporal_status,wc.created_at
                  ORDER BY wc.created_at DESC LIMIT ?
                  """,
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
                         COALESCE(MIN(s.version_id),''),rf.relation_id,rf.conflict_status,
                         GROUP_CONCAT(DISTINCT rs.evidence_id ORDER BY rs.evidence_id SEPARATOR ','),rf.created_at
                  FROM relation_fact rf
                  JOIN canonical_entity se ON se.canonical_entity_id=rf.source_entity_id
                  JOIN canonical_entity te ON te.canonical_entity_id=rf.target_entity_id
                  LEFT JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE
                  LEFT JOIN summary s ON s.summary_id=rs.summary_id
                  WHERE rf.lifecycle='ACTIVE' AND rf.conflict_status<>'NONE'
                  GROUP BY se.canonical_name,rf.relation_type,te.canonical_name,rf.relation_id,rf.conflict_status,rf.created_at
                  ORDER BY rf.created_at DESC LIMIT ?
                  """,
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
                  WHERE em.source_entity_id IS NULL OR em.status<>'APPLIED'
                  ORDER BY se.created_at DESC LIMIT ?
                  """,
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
                  WHERE ct.status='FAILED' AND ct.task_type IN ('SUMMARY','GRAPH','WIKI')
                  ORDER BY ct.created_at DESC LIMIT ?
                  """,
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
    return new Facets(
        distinct(
            "SELECT DISTINCT company FROM document_metadata WHERE company<>'' ORDER BY company LIMIT 500"),
        distinct(
            "SELECT DISTINCT source_department FROM document_metadata WHERE source_department<>'' ORDER BY source_department LIMIT 500"),
        distinct(
            "SELECT DISTINCT COALESCE(manual_document_type_override,auto_document_type,'') value FROM document_metadata WHERE COALESCE(manual_document_type_override,auto_document_type,'')<>'' ORDER BY value LIMIT 500"),
        distinct(
            "SELECT DISTINCT material_category FROM document_metadata WHERE material_category<>'' ORDER BY material_category LIMIT 500"));
  }

  @Override
  public Page<AuditEventView> audit(PageQuery q) {
    PageQuery query = q == null ? emptyQuery() : q;
    SqlWhere where = new SqlWhere("1=1");
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
    if (!text(query)) return List.of();
    int max = Math.min(limit <= 0 ? 10 : limit, 100);
    int hops = Math.max(1, Math.min(depth <= 0 ? 2 : depth, 2));
    String like = "%" + query.trim() + "%";
    StringBuilder firstWhere =
        new StringBuilder(
            "rf.lifecycle='ACTIVE' AND (se.canonical_name LIKE ? OR te.canonical_name LIKE ? OR rf.relation_type LIKE ?)");
    List<Object> firstArgs = new ArrayList<>(List.of(like, like, like));
    if (text(relationType)) {
      firstWhere.append(" AND rf.relation_type=?");
      firstArgs.add(relationType.trim());
    }
    if (validAt != null) {
      firstWhere.append(
          " AND (rf.valid_from IS NULL OR rf.valid_from<=?) AND (rf.valid_to IS NULL OR rf.valid_to>?)");
      firstArgs.add(Timestamp.from(validAt));
      firstArgs.add(Timestamp.from(validAt));
    }
    firstArgs.add(max);
    List<GraphRelationView> first =
        jdbc.query(
            "SELECT rf.relation_id,rf.source_entity_id,se.canonical_name,se.entity_type,rf.target_entity_id,te.canonical_name,te.entity_type,rf.relation_type,rf.valid_from,rf.valid_to,rf.conflict_status,"
                + "GROUP_CONCAT(DISTINCT rs.evidence_id ORDER BY rs.evidence_id SEPARATOR ',') evidence_ids,MAX(rs.confidence) score "
                + "FROM relation_fact rf JOIN canonical_entity se ON se.canonical_entity_id=rf.source_entity_id JOIN canonical_entity te ON te.canonical_entity_id=rf.target_entity_id "
                + "JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE JOIN evidence ev ON ev.evidence_id=rs.evidence_id AND ev.active=TRUE WHERE "
                + firstWhere
                + " GROUP BY rf.relation_id,rf.source_entity_id,se.canonical_name,se.entity_type,rf.target_entity_id,te.canonical_name,te.entity_type,rf.relation_type,rf.valid_from,rf.valid_to,rf.conflict_status "
                + "ORDER BY score DESC,rf.relation_id LIMIT ?",
            (rs, n) -> graphRelation(rs),
            firstArgs.toArray());
    if (hops == 1 || first.size() >= max || first.isEmpty()) return List.copyOf(first);
    LinkedHashSet<String> entityIds = new LinkedHashSet<>();
    LinkedHashSet<String> relationIds = new LinkedHashSet<>();
    first.forEach(
        r -> {
          entityIds.add(r.sourceEntityId());
          entityIds.add(r.targetEntityId());
          relationIds.add(r.relationId());
        });
    String entityMarks = String.join(",", Collections.nCopies(entityIds.size(), "?"));
    String relationMarks = String.join(",", Collections.nCopies(relationIds.size(), "?"));
    StringBuilder secondWhere =
        new StringBuilder(
            "rf.lifecycle='ACTIVE' AND (rf.source_entity_id IN ("
                + entityMarks
                + ") OR rf.target_entity_id IN ("
                + entityMarks
                + ")) AND rf.relation_id NOT IN ("
                + relationMarks
                + ")");
    List<Object> secondArgs = new ArrayList<>();
    secondArgs.addAll(entityIds);
    secondArgs.addAll(entityIds);
    secondArgs.addAll(relationIds);
    if (text(relationType)) {
      secondWhere.append(" AND rf.relation_type=?");
      secondArgs.add(relationType.trim());
    }
    if (validAt != null) {
      secondWhere.append(
          " AND (rf.valid_from IS NULL OR rf.valid_from<=?) AND (rf.valid_to IS NULL OR rf.valid_to>?)");
      secondArgs.add(Timestamp.from(validAt));
      secondArgs.add(Timestamp.from(validAt));
    }
    secondArgs.add(max - first.size());
    List<GraphRelationView> second =
        jdbc.query(
            "SELECT rf.relation_id,rf.source_entity_id,se.canonical_name,se.entity_type,rf.target_entity_id,te.canonical_name,te.entity_type,rf.relation_type,rf.valid_from,rf.valid_to,rf.conflict_status,"
                + "GROUP_CONCAT(DISTINCT rs.evidence_id ORDER BY rs.evidence_id SEPARATOR ',') evidence_ids,MAX(rs.confidence) score "
                + "FROM relation_fact rf JOIN canonical_entity se ON se.canonical_entity_id=rf.source_entity_id JOIN canonical_entity te ON te.canonical_entity_id=rf.target_entity_id "
                + "JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE JOIN evidence ev ON ev.evidence_id=rs.evidence_id AND ev.active=TRUE WHERE "
                + secondWhere
                + " GROUP BY rf.relation_id,rf.source_entity_id,se.canonical_name,se.entity_type,rf.target_entity_id,te.canonical_name,te.entity_type,rf.relation_type,rf.valid_from,rf.valid_to,rf.conflict_status "
                + "ORDER BY score DESC,rf.relation_id LIMIT ?",
            (rs, n) -> graphRelation(rs),
            secondArgs.toArray());
    List<GraphRelationView> out = new ArrayList<>(first);
    out.addAll(second);
    return List.copyOf(out);
  }

  private GraphRelationView graphRelation(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new GraphRelationView(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        instant(rs.getTimestamp(9)),
        instant(rs.getTimestamp(10)),
        rs.getString(11),
        split(rs.getString(12)),
        rs.getDouble(13));
  }

  @Override
  public List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds) {
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
            "SELECT e.evidence_id,e.document_id,e.version_id,v.original_filename,e.object_key,e.raw_location FROM evidence e JOIN document_version v ON v.version_id=e.version_id WHERE e.active=TRUE AND e.evidence_id IN ("
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
                   COALESCE((SELECT COUNT(*) FROM effective_version_hit evh WHERE evh.version_id=v.version_id),0)
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
                    rs.getInt(19)),
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
