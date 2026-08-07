package cn.lgs.knowledgebase.infrastructure.persistence;

import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.contextualEvidenceContent;
import static cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeRepository;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Document;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.DocumentVersion;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Metadata;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.RawBlock;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.VersionLifecycle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcKnowledgeRepository implements KnowledgeRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

  public JdbcKnowledgeRepository(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mapper = mapper;
  }

  @Override
  public Optional<Document> findDocumentByLogicalName(String logicalName) {
    return jdbc
        .query(
            "SELECT document_id,logical_name,COALESCE(source_path,'') FROM document WHERE logical_name=? AND status='ACTIVE' ORDER BY created_at LIMIT 1",
            (rs, n) -> new Document(rs.getString(1), rs.getString(2), rs.getString(3)),
            logicalName)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<DocumentVersion> findVersionBySha(String documentId, String sha256) {
    return findVersion(
        "WHERE document_id=? AND sha256=? ORDER BY version_no DESC LIMIT 1", documentId, sha256);
  }

  @Override
  public Optional<DocumentVersion> currentActiveVersion(String documentId) {
    return findVersion(
        "WHERE document_id=? AND lifecycle='ACTIVE' ORDER BY version_no DESC LIMIT 1", documentId);
  }

  private Optional<DocumentVersion> findVersion(String suffix, Object... args) {
    String sql =
        "SELECT version_id,document_id,version_no,lifecycle,original_filename,COALESCE(media_type,''),size_bytes,sha256,original_object_key,"
            + "COALESCE(markdown_object_key,''),COALESCE(structured_object_key,''),COALESCE(parser_name,''),COALESCE(parser_version,'') FROM document_version "
            + suffix;
    return jdbc.query(sql, versionMapper(), args).stream().findFirst();
  }

  @Override
  public long nextVersionNo(String documentId) {
    Long value =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_no),0)+1 FROM document_version WHERE document_id=?",
            Long.class,
            documentId);
    return value == null ? 1 : value;
  }

  @Override
  public void createPendingVersion(Document document, DocumentVersion version, Metadata metadata) {
    createPendingVersion(document, version, metadata, null);
  }

  @Override
  public void createPendingVersion(
      Document document, DocumentVersion version, Metadata metadata, Integer securityLevel) {
    cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy.requireValid(
        securityLevel);
    transactions.executeWithoutResult(
        status -> {
          int inherited =
              jdbc.queryForObject(
                  "SELECT COALESCE(max(m.security_level),10) FROM document_metadata m JOIN document_version v USING(version_id) WHERE v.document_id=?",
                  Integer.class,
                  document.id());
          int classification = securityLevel == null ? inherited : securityLevel;
          jdbc.update(
              "INSERT INTO document(document_id,logical_name,source_path,status) VALUES(?,?,?,'ACTIVE') ON CONFLICT (document_id) DO UPDATE SET logical_name=EXCLUDED.logical_name,source_path=EXCLUDED.source_path,status='ACTIVE'",
              document.id(),
              document.logicalName(),
              nullable(document.sourcePath()));
          jdbc.update(
              "INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,media_type,size_bytes,sha256,original_object_key) "
                  + "VALUES(?,?,?,'PENDING',?,?,?,?,?)",
              version.id(),
              version.documentId(),
              version.versionNo(),
              version.originalFilename(),
              nullable(version.mediaType()),
              version.sizeBytes(),
              version.sha256(),
              version.originalObjectKey());
          jdbc.update(
              "INSERT INTO document_metadata(version_id,source_department,material_category,manual_tags,company,document_date,auto_document_type,security_level) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT (version_id) DO UPDATE SET source_department=EXCLUDED.source_department,material_category=EXCLUDED.material_category,manual_tags=EXCLUDED.manual_tags,company=EXCLUDED.company,document_date=EXCLUDED.document_date,auto_document_type=EXCLUDED.auto_document_type",
              version.id(),
              nullable(metadata.sourceDepartment()),
              nullable(metadata.materialCategory()),
              json(metadata.manualTags()),
              nullable(metadata.company()),
              metadata.documentDate(),
              nullable(metadata.documentType()),
              classification);
        });
  }

  @Override
  public void resetFailedVersion(String versionId, Metadata metadata, boolean preserveParsed) {
    resetFailedVersion(versionId, metadata, preserveParsed, null);
  }

  @Override
  public Optional<Integer> sourceSecurityLevel(String versionId) {
    return jdbc
        .query(
            "SELECT security_level FROM document_metadata WHERE version_id=?",
            (rs, n) -> rs.getInt(1),
            versionId)
        .stream()
        .findFirst();
  }

  @Override
  public void resetFailedVersion(
      String versionId, Metadata metadata, boolean preserveParsed, Integer securityLevel) {
    cn.lgs.knowledgebase.application.operations.DocumentClassificationPolicy.requireValid(
        securityLevel);
    transactions.executeWithoutResult(
        status -> {
          String sql =
              preserveParsed
                  ? "UPDATE document_version SET lifecycle='PENDING',failure_code=NULL,failure_message=NULL,activated_at=NULL WHERE version_id=? AND lifecycle IN ('FAILED','PENDING')"
                  : "UPDATE document_version SET lifecycle='PENDING',failure_code=NULL,failure_message=NULL,"
                      + "markdown_object_key=NULL,structured_object_key=NULL,parser_name=NULL,parser_version=NULL,parsed_at=NULL,activated_at=NULL "
                      + "WHERE version_id=? AND lifecycle IN ('FAILED','PENDING')";
          int rows = jdbc.update(sql, versionId);
          if (rows != 1) throw new IllegalStateException("version is not FAILED: " + versionId);
          jdbc.update("UPDATE evidence SET active=FALSE WHERE version_id=?", versionId);
          jdbc.update(
              "UPDATE document_metadata SET source_department=?,material_category=?,manual_tags=?,company=?,document_date=?,auto_document_type=?,security_level=COALESCE(?::smallint,security_level),acl_version=acl_version+CASE WHEN ?::smallint IS NOT NULL AND ?::smallint<>security_level THEN 1 ELSE 0 END WHERE version_id=?",
              nullable(metadata.sourceDepartment()),
              nullable(metadata.materialCategory()),
              json(metadata.manualTags()),
              nullable(metadata.company()),
              metadata.documentDate(),
              nullable(metadata.documentType()),
              securityLevel,
              securityLevel,
              securityLevel,
              versionId);
        });
  }

  @Override
  public List<RawBlock> rawBlocks(String versionId) {
    return jdbc.query(
        "SELECT block_id,version_id,block_no,block_type,raw_content,normalized_content,heading_path,page_start,page_end,bbox_json,source_block_ids,content_hash,token_count "
            + "FROM raw_block WHERE version_id=? ORDER BY block_no",
        (rs, rowNum) ->
            new RawBlock(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                strings(rs.getString(7)),
                (Integer) rs.getObject(8),
                (Integer) rs.getObject(9),
                doubles(rs.getString(10)),
                List.of(),
                strings(rs.getString(11)),
                rs.getString(12),
                rs.getInt(13)),
        versionId);
  }

  @Override
  public void saveParsed(
      String versionId,
      String markdownKey,
      String structuredKey,
      String parserName,
      String parserVersion,
      List<RawBlock> blocks,
      List<Evidence> evidence) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM evidence WHERE version_id=?", versionId);
          jdbc.update("DELETE FROM raw_block WHERE version_id=?", versionId);
          for (RawBlock block : blocks) {
            jdbc.update(
                "INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,normalized_content,heading_path,page_start,page_end,bbox_json,source_block_ids,content_hash,token_count) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                block.id(),
                block.versionId(),
                block.blockNo(),
                block.blockType(),
                block.content(),
                block.normalized(),
                json(block.headingPath()),
                block.pageStart(),
                block.pageEnd(),
                json(block.bbox()),
                json(block.sourceBlockIds()),
                block.contentHash(),
                block.tokenCount());
          }
          for (Evidence item : evidence) {
            jdbc.update(
                "INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash,active) VALUES(?,?,?,?,?,?,?,FALSE)",
                item.id(),
                item.documentId(),
                item.versionId(),
                item.blockId(),
                item.objectKey(),
                json(item.location()),
                item.hash());
          }
          jdbc.update(
              "UPDATE document_version SET markdown_object_key=?,structured_object_key=?,parser_name=?,parser_version=?,parsed_at=CURRENT_TIMESTAMP(6),failure_code=NULL,failure_message=NULL WHERE version_id=?",
              markdownKey,
              structuredKey,
              parserName,
              parserVersion,
              versionId);
        });
  }

  @Override
  public void activateVersion(String documentId, String versionId) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE summary s SET lifecycle='STALE' FROM document_version v "
                  + "WHERE v.version_id=s.version_id AND v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND s.lifecycle='ACTIVE'",
              documentId,
              versionId);
          jdbc.update(
              "UPDATE relation_evidence_support rs SET active=FALSE FROM evidence e JOIN document_version v ON v.version_id=e.version_id "
                  + "WHERE e.evidence_id=rs.evidence_id AND v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND rs.active=TRUE",
              documentId,
              versionId);
          jdbc.update(
              "UPDATE wiki w SET lifecycle='STALE' FROM wiki_claim c "
                  + "JOIN wiki_evidence we ON we.claim_id=c.claim_id JOIN evidence e ON e.evidence_id=we.evidence_id "
                  + "JOIN document_version v ON v.version_id=e.version_id "
                  + "WHERE c.wiki_id=w.wiki_id AND c.wiki_version=w.wiki_version AND v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND w.lifecycle='ACTIVE'",
              documentId,
              versionId);
          // Version lifecycle invalidates evidence support only. Relation valid_from/valid_to is
          // business-time truth and is intentionally untouched.
          jdbc.update(
              "UPDATE evidence e SET active=FALSE FROM document_version v "
                  + "WHERE v.version_id=e.version_id AND v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE'",
              documentId,
              versionId);
          jdbc.update(
              "UPDATE document_version SET lifecycle='INACTIVE' WHERE document_id=? AND version_id<>? AND lifecycle='ACTIVE'",
              documentId,
              versionId);
          jdbc.update(
              "UPDATE document_version SET lifecycle='ACTIVE',activated_at=CURRENT_TIMESTAMP(6) WHERE document_id=? AND version_id=?",
              documentId,
              versionId);
          jdbc.update("UPDATE evidence SET active=TRUE WHERE version_id=?", versionId);
        });
  }

  @Override
  public void markVersionFailed(String versionId, String code, String message) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE document_version SET lifecycle='FAILED',failure_code=?,failure_message=? WHERE version_id=?",
              code,
              message,
              versionId);
          jdbc.update("UPDATE evidence SET active=FALSE WHERE version_id=?", versionId);
        });
  }

  @Override
  public List<Evidence> resolveEvidence(List<String> evidenceIds) {
    return resolveEvidence(evidenceIds, new SearchFilters(null, null, null, List.of()));
  }

  public List<Evidence> resolveEvidence(List<String> evidenceIds, SearchFilters filters) {
    if (evidenceIds == null || evidenceIds.isEmpty()) return List.of();
    String placeholders = String.join(",", Collections.nCopies(evidenceIds.size(), "?"));
    String sql =
        "SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,e.content_hash,b.raw_content,b.heading_path "
            + "FROM evidence e JOIN raw_block b ON b.block_id=e.block_id "
            + "JOIN document_version v ON v.version_id=e.version_id JOIN document d ON d.document_id=e.document_id "
            + "JOIN kb_source_authority m ON m.version_id=e.version_id "
            + "WHERE e.active=TRUE AND v.lifecycle='ACTIVE' AND d.status='ACTIVE' AND m.security_level <= "
            + (filters == null ? 10 : filters.securityClearance())
            + " AND e.evidence_id IN ("
            + placeholders
            + ")";
    Map<String, Evidence> byId = new LinkedHashMap<>();
    jdbc.query(
        sql,
        rs -> {
          String content = contextualEvidenceContent(strings(rs.getString(9)), rs.getString(8));
          Evidence item =
              new Evidence(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  map(rs.getString(6)),
                  content,
                  sha256(content));
          byId.put(item.id(), item);
        },
        evidenceIds.toArray());
    List<Evidence> ordered = new ArrayList<>();
    for (String id : evidenceIds) if (byId.containsKey(id)) ordered.add(byId.get(id));
    return List.copyOf(ordered);
  }

  private RowMapper<DocumentVersion> versionMapper() {
    return (rs, rowNum) ->
        new DocumentVersion(
            rs.getString(1),
            rs.getString(2),
            rs.getLong(3),
            VersionLifecycle.valueOf(rs.getString(4)),
            rs.getString(5),
            rs.getString(6),
            rs.getLong(7),
            rs.getString(8),
            rs.getString(9),
            rs.getString(10),
            rs.getString(11),
            rs.getString(12),
            rs.getString(13));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON serialization failed", e);
    }
  }

  private Map<String, Object> map(String json) {
    if (json == null || json.isBlank() || "null".equals(json)) return Map.of();
    try {
      return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON parse failed", e);
    }
  }

  private List<String> strings(String json) {
    if (json == null || json.isBlank() || "null".equals(json)) return List.of();
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON parse failed", e);
    }
  }

  private List<Double> doubles(String json) {
    if (json == null || json.isBlank() || "null".equals(json)) return List.of();
    try {
      return mapper.readValue(json, new TypeReference<List<Double>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON parse failed", e);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
