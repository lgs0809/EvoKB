package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcEnterpriseOperations implements EnterpriseOperations.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEnterpriseOperations(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public OverviewView overview() {
        return jdbc.queryForObject("""
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
                """, (rs, n) -> new OverviewView(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16), rs.getInt(17), rs.getInt(18), rs.getInt(19), rs.getInt(20)));
    }

    @Override
    public Page<DocumentVersionView> documents(PageQuery q) {
        PageQuery query = q == null ? emptyQuery() : q;
        SqlWhere where = new SqlWhere("d.status<>'DELETED'");
        if (text(query.query())) {
            String like = "%" + query.query().trim() + "%";
            where.add("(d.logical_name LIKE ? OR v.original_filename LIKE ? OR COALESCE(m.company,'') LIKE ? OR COALESCE(m.source_department,'') LIKE ? OR COALESCE(m.manual_document_type_override,m.auto_document_type,'') LIKE ?)", like, like, like, like, like);
        }
        where.eq("v.lifecycle", upperExceptAll(query.lifecycle()));
        where.eq("m.company", exceptAll(query.company()));
        where.eq("m.source_department", exceptAll(query.sourceDepartment()));
        where.eq("COALESCE(m.manual_document_type_override,m.auto_document_type,'')", exceptAll(query.documentType()));
        where.eq("m.material_category", exceptAll(query.materialCategory()));
        where.eq("d.document_id", trim(query.documentId()));
        String from = " FROM document d JOIN document_version v ON v.document_id=d.document_id LEFT JOIN document_metadata m ON m.version_id=v.version_id WHERE " + where.sql();
        long total = count("SELECT COUNT(*)" + from, where.args());
        String sql = """
                SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.original_filename,
                       COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,''),
                       COALESCE(m.manual_document_type_override,m.auto_document_type,''),m.document_date,v.parsed_at,
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id),
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.grounding_status='PASS'),
                       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.coverage_status='PASS'),
                       COALESCE((SELECT dr.status FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                       COALESCE((SELECT dr.reason FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
                       COALESCE((SELECT COUNT(*) FROM effective_version_hit evh WHERE evh.version_id=v.version_id),0)
                """ + from + " ORDER BY d.updated_at DESC,v.version_no DESC LIMIT ? OFFSET ?";
        List<Object> args = withPage(where.args(), query);
        List<DocumentVersionView> items = jdbc.query(sql, (rs, n) -> new DocumentVersionView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(), instant(rs.getTimestamp(12)),
                rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getString(16), rs.getString(17), rs.getInt(18)), args.toArray());
        return page(items, total, query);
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
        List<WikiView> items = jdbc.query("SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE " + where.sql() + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> new WikiView(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), List.of()), args.toArray());
        return page(items, total, query);
    }

    @Override
    public Optional<WikiView> wiki(String wikiId, Long version) {
        String id = trim(wikiId);
        if (id.isEmpty()) return Optional.empty();
        String sql = "SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE wiki_id=?" +
                (version == null || version <= 0 ? " ORDER BY wiki_version DESC LIMIT 1" : " AND wiki_version=? LIMIT 1");
        Object[] args = version == null || version <= 0 ? new Object[]{id} : new Object[]{id, version};
        return jdbc.query(sql, (rs, n) -> {
            String currentId = rs.getString(1); long currentVersion = rs.getLong(2);
            return new WikiView(currentId, currentVersion, rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), claims(currentId, currentVersion));
        }, args).stream().findFirst();
    }

    private List<ClaimView> claims(String wikiId, long version) {
        List<ClaimView> base = jdbc.query("SELECT claim_id,section,claim_text,grounding_status,temporal_status FROM wiki_claim WHERE wiki_id=? AND wiki_version=? ORDER BY created_at,claim_id",
                (rs, n) -> new ClaimView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), List.of(), List.of()), wikiId, version);
        if (base.isEmpty()) return List.of();
        List<ClaimView> out = new ArrayList<>(base.size());
        for (ClaimView claim : base) {
            List<String> facts = jdbc.query("SELECT fact_id FROM wiki_claim_fact WHERE claim_id=? ORDER BY fact_id", (rs,n)->rs.getString(1), claim.claimId());
            List<String> evidence = jdbc.query("SELECT evidence_id FROM wiki_evidence WHERE claim_id=? ORDER BY evidence_id", (rs,n)->rs.getString(1), claim.claimId());
            out.add(new ClaimView(claim.claimId(), claim.section(), claim.claimText(), claim.groundingStatus(), claim.temporalStatus(), facts, evidence));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<EvidenceDetail> evidence(String evidenceId) {
        return jdbc.query("SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,rb.raw_content,e.content_hash,e.active FROM evidence e JOIN raw_block rb ON rb.block_id=e.block_id WHERE e.evidence_id=?",
                (rs,n)->new EvidenceDetail(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),map(rs.getString(6)),rs.getString(7),rs.getString(8),rs.getBoolean(9)), evidenceId).stream().findFirst();
    }

    @Override
    public Page<EvidenceListView> evidences(PageQuery q) {
        PageQuery query = q == null ? emptyQuery() : q;
        SqlWhere where = new SqlWhere("d.status<>'DELETED'");
        if (query.active() != null) where.add("e.active=?", query.active());
        if (text(query.query())) {
            String like = "%" + query.query().trim() + "%";
            where.add("(d.logical_name LIKE ? OR v.original_filename LIKE ? OR e.evidence_id LIKE ? OR e.version_id LIKE ? OR e.block_id LIKE ? OR rb.raw_content LIKE ?)", like,like,like,like,like,like);
        }
        String from = " FROM evidence e JOIN document d ON d.document_id=e.document_id JOIN document_version v ON v.version_id=e.version_id JOIN raw_block rb ON rb.block_id=e.block_id WHERE " + where.sql();
        long total = count("SELECT COUNT(*)" + from, where.args());
        List<Object> args = withPage(where.args(), query);
        List<EvidenceListView> items = jdbc.query("SELECT e.evidence_id,e.document_id,d.logical_name,e.version_id,v.version_no,v.original_filename,e.block_id,rb.block_type,e.raw_location,LEFT(rb.raw_content,320),e.active,e.created_at" + from + " ORDER BY e.active DESC,e.created_at DESC LIMIT ? OFFSET ?",
                (rs,n)->new EvidenceListView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),map(rs.getString(9)),rs.getString(10),rs.getBoolean(11),instant(rs.getTimestamp(12))), args.toArray());
        return page(items,total,query);
    }

    @Override
    public Page<TaskView> tasks(PageQuery q, String actorId, boolean admin, String source) {
        PageQuery query = q == null ? emptyQuery() : q;
        String union;
        List<Object> unionArgs = new ArrayList<>();
        if (admin) {
            union = "(SELECT ct.task_id,'COMPILE' source,'' batch_id,'' file_id,COALESCE(ct.version_id,'') version_id,COALESCE(d.logical_name,'') logical_name,ct.task_type,ct.status,ct.status progress_stage,ct.attempt,COALESCE(ct.error_code,'') error_code,COALESCE(ct.error_message,'') error_message,ct.started_at,ct.finished_at,ct.created_at FROM compile_task ct LEFT JOIN document_version v ON v.version_id=ct.version_id LEFT JOIN document d ON d.document_id=v.document_id UNION ALL SELECT f.file_id,'INGEST',f.batch_id,f.file_id,COALESCE(f.version_id,''),f.logical_name,'INGEST',f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,''),f.started_at,f.finished_at,f.created_at FROM ingestion_file f) u";
        } else {
            union = "(SELECT f.file_id task_id,'INGEST' source,f.batch_id,f.file_id,COALESCE(f.version_id,'') version_id,f.logical_name,'INGEST' task_type,f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,'') error_message,f.started_at,f.finished_at,f.created_at FROM ingestion_file f JOIN ingestion_batch b ON b.batch_id=f.batch_id WHERE b.created_by=?) u";
            unionArgs.add(actorId);
        }
        SqlWhere where = new SqlWhere("1=1");
        where.eq("u.status", upperExceptAll(query.status()));
        where.eq("u.source", upperExceptAll(source));
        if (text(query.query())) {
            String like="%"+query.query().trim()+"%";
            where.add("(u.logical_name LIKE ? OR u.task_id LIKE ? OR u.version_id LIKE ? OR u.task_type LIKE ? OR u.error_message LIKE ? OR u.progress_stage LIKE ?)",like,like,like,like,like,like);
        }
        List<Object> baseArgs = new ArrayList<>(unionArgs); baseArgs.addAll(where.args());
        long total=count("SELECT COUNT(*) FROM "+union+" WHERE "+where.sql(),baseArgs);
        List<Object> args=withPage(baseArgs,query);
        List<TaskView> items=jdbc.query("SELECT task_id,source,batch_id,file_id,version_id,logical_name,task_type,status,progress_stage,attempt,error_code,error_message,started_at,finished_at,created_at FROM "+union+" WHERE "+where.sql()+" ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?",
                (rs,n)->new TaskView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getInt(10),rs.getString(11),rs.getString(12),instant(rs.getTimestamp(13)),instant(rs.getTimestamp(14)),instant(rs.getTimestamp(15))),args.toArray());
        return page(items,total,query);
    }

    @Override
    public Facets facets() {
        return new Facets(distinct("SELECT DISTINCT company FROM document_metadata WHERE company<>'' ORDER BY company LIMIT 500"),
                distinct("SELECT DISTINCT source_department FROM document_metadata WHERE source_department<>'' ORDER BY source_department LIMIT 500"),
                distinct("SELECT DISTINCT COALESCE(manual_document_type_override,auto_document_type,'') value FROM document_metadata WHERE COALESCE(manual_document_type_override,auto_document_type,'')<>'' ORDER BY value LIMIT 500"),
                distinct("SELECT DISTINCT material_category FROM document_metadata WHERE material_category<>'' ORDER BY material_category LIMIT 500"));
    }

    @Override
    public Page<AuditEventView> audit(PageQuery q) {
        PageQuery query=q==null?emptyQuery():q;
        SqlWhere where=new SqlWhere("1=1");
        if(text(query.query())){String like="%"+query.query().trim()+"%";where.add("(actor_username LIKE ? OR action LIKE ? OR COALESCE(target_name,'') LIKE ? OR COALESCE(target_id,'') LIKE ?)",like,like,like,like);}
        if(text(query.actor())){String like="%"+query.actor().trim()+"%";where.add("(actor_username LIKE ? OR COALESCE(actor_user_id,'') LIKE ?)",like,like);}
        where.eq("action",exceptAll(query.action()));
        where.eq("outcome",upperExceptAll(query.outcome()));
        long total=count("SELECT COUNT(*) FROM audit_event WHERE "+where.sql(),where.args());
        List<Object> args=withPage(where.args(),query);
        List<AuditEventView> items=jdbc.query("SELECT event_id,COALESCE(actor_user_id,''),actor_username,actor_role,action,COALESCE(target_type,''),COALESCE(target_id,''),COALESCE(target_name,''),outcome,COALESCE(request_id,''),COALESCE(ip_address,''),COALESCE(user_agent,''),details_json,created_at FROM audit_event WHERE "+where.sql()+" ORDER BY created_at DESC,event_id DESC LIMIT ? OFFSET ?",
                (rs,n)->new AuditEventView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),map(rs.getString(13)),instant(rs.getTimestamp(14))),args.toArray());
        return page(items,total,query);
    }

    @Override
    public List<GraphRelationView> graph(String query, int depth, int limit) {
        if (!text(query)) return List.of();
        int max=Math.min(limit<=0?10:limit,100);
        int hops=Math.max(1,Math.min(depth<=0?2:depth,2));
        String like="%"+query.trim()+"%";
        List<GraphRelationView> first=jdbc.query("""
                SELECT rf.relation_id,rf.source_entity_id,se.canonical_name,rf.target_entity_id,te.canonical_name,rf.relation_type,
                       GROUP_CONCAT(DISTINCT rs.evidence_id ORDER BY rs.evidence_id SEPARATOR ',') evidence_ids,
                       MAX(rs.confidence) score
                FROM relation_fact rf JOIN canonical_entity se ON se.canonical_entity_id=rf.source_entity_id
                JOIN canonical_entity te ON te.canonical_entity_id=rf.target_entity_id
                JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE
                JOIN evidence ev ON ev.evidence_id=rs.evidence_id AND ev.active=TRUE
                WHERE rf.lifecycle='ACTIVE' AND (se.canonical_name LIKE ? OR te.canonical_name LIKE ? OR rf.relation_type LIKE ?)
                GROUP BY rf.relation_id,rf.source_entity_id,se.canonical_name,rf.target_entity_id,te.canonical_name,rf.relation_type
                ORDER BY score DESC,rf.relation_id LIMIT ?
                """,(rs,n)->new GraphRelationView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),split(rs.getString(7)),rs.getDouble(8)),like,like,like,max);
        if(hops==1||first.size()>=max||first.isEmpty())return List.copyOf(first);
        LinkedHashSet<String> entityIds=new LinkedHashSet<>();
        LinkedHashSet<String> relationIds=new LinkedHashSet<>();
        first.forEach(r->{entityIds.add(r.sourceEntityId());entityIds.add(r.targetEntityId());relationIds.add(r.relationId());});
        String entityMarks=String.join(",",Collections.nCopies(entityIds.size(),"?"));
        String relationMarks=String.join(",",Collections.nCopies(relationIds.size(),"?"));
        List<Object> args=new ArrayList<>();args.addAll(entityIds);args.addAll(entityIds);args.addAll(relationIds);args.add(max-first.size());
        List<GraphRelationView> second=jdbc.query("SELECT rf.relation_id,rf.source_entity_id,se.canonical_name,rf.target_entity_id,te.canonical_name,rf.relation_type,"+
                        "GROUP_CONCAT(DISTINCT rs.evidence_id ORDER BY rs.evidence_id SEPARATOR ',') evidence_ids,MAX(rs.confidence) score "+
                        "FROM relation_fact rf JOIN canonical_entity se ON se.canonical_entity_id=rf.source_entity_id JOIN canonical_entity te ON te.canonical_entity_id=rf.target_entity_id "+
                        "JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE JOIN evidence ev ON ev.evidence_id=rs.evidence_id AND ev.active=TRUE "+
                        "WHERE rf.lifecycle='ACTIVE' AND (rf.source_entity_id IN ("+entityMarks+") OR rf.target_entity_id IN ("+entityMarks+")) AND rf.relation_id NOT IN ("+relationMarks+") "+
                        "GROUP BY rf.relation_id,rf.source_entity_id,se.canonical_name,rf.target_entity_id,te.canonical_name,rf.relation_type ORDER BY score DESC,rf.relation_id LIMIT ?",
                (rs,n)->new GraphRelationView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),split(rs.getString(7)),rs.getDouble(8)),args.toArray());
        List<GraphRelationView> out=new ArrayList<>(first);out.addAll(second);return List.copyOf(out);
    }

    @Override
    public List<EvidenceSourceRef> evidenceSources(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) return List.of();
        List<String> ids = evidenceIds.stream().filter(JdbcEnterpriseOperations::text).map(String::trim).distinct().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String,EvidenceSourceRef> found = new LinkedHashMap<>();
        List<EvidenceSourceRef> rows = jdbc.query(
                "SELECT e.evidence_id,e.document_id,e.version_id,v.original_filename,e.object_key,e.raw_location FROM evidence e JOIN document_version v ON v.version_id=e.version_id WHERE e.active=TRUE AND e.evidence_id IN ("+placeholders+")",
                (rs, rowNum) -> new EvidenceSourceRef(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),map(rs.getString(6))),
                ids.toArray());
        rows.forEach(row -> found.put(row.evidenceId(), row));
        return ids.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
    }

    private long count(String sql,List<Object> args){Long v=jdbc.queryForObject(sql,Long.class,args.toArray());return v==null?0:v;}
    private List<String> distinct(String sql){return jdbc.query(sql,(rs,n)->rs.getString(1));}
    private Map<String,Object> map(String json){if(json==null||json.isBlank())return Map.of();try{return mapper.readValue(json,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("cannot parse persisted JSON",e);}}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static List<String> split(String value){if(value==null||value.isBlank())return List.of();return List.of(value.split(","));}
    private static boolean text(String v){return v!=null&&!v.isBlank();}
    private static String trim(String v){return v==null?"":v.trim();}
    private static String exceptAll(String v){String x=trim(v);return x.isEmpty()||x.equalsIgnoreCase("ALL")?"":x;}
    private static String upperExceptAll(String v){String x=exceptAll(v);return x.isEmpty()?"":x.toUpperCase();}
    private static List<Object> withPage(List<Object> args,PageQuery q){List<Object> out=new ArrayList<>(args);out.add(q.normalizedPageSize());out.add(q.offset());return out;}
    private static <T> Page<T> page(List<T> items,long total,PageQuery q){return new Page<>(items,total,q.normalizedPage(),q.normalizedPageSize());}
    private static PageQuery emptyQuery(){return new PageQuery(1,30,"","","",null,"","","","","","","","","","","");}

    private static final class SqlWhere {
        private final List<String> clauses=new ArrayList<>(); private final List<Object> args=new ArrayList<>();
        private SqlWhere(String initial){clauses.add(initial);} 
        void add(String clause,Object...values){clauses.add(clause);Collections.addAll(args,values);} 
        void eq(String column,String value){if(text(value))add(column+"=?",value);} 
        String sql(){return String.join(" AND ",clauses);} List<Object> args(){return List.copyOf(args);} 
    }
}
