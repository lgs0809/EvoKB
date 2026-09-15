import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcKnowledgeRepository;
import cn.lgs.knowledgebase.infrastructure.search.*;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only audit of the frozen formal source import; never imports/prewarms/queries a model. */
public final class FormalRawImportAuditProbe {
  public static void main(String[] args) throws Exception {
    var json = new ObjectMapper().findAndRegisterModules(); var manifest = json.readTree(Path.of(args[1]).toFile());
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
    var knowledge = new JdbcKnowledgeRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), json);
    var objects = new MinioObjectStoreAdapter("http://127.0.0.1:19400", System.getenv("PROBE_MINIO_USER"), System.getenv("PROBE_MINIO_PASSWORD"), "evokb-target");
    var client = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(30000).build());
    var index = new MilvusKnowledgeIndexAdapter(client, "knowledge_chunks", new JdbcProjectionAuthority(jdbc));
    var rows = new ArrayList<Map<String,Object>>();
    try {
      for (var gold : manifest.path("documents")) {
        if (args.length>2 && !Arrays.asList(Arrays.copyOfRange(args,2,args.length)).contains(gold.path("logicalName").asText())) continue;
        var row = new LinkedHashMap<String,Object>(); rows.add(row); String logical = gold.path("logicalName").asText();
        row.put("logicalName", logical); row.put("family", gold.path("family").asText()); row.put("split", gold.path("split").asText()); row.put("kind", gold.path("kind").asText()); row.put("frozenOriginalSha256", gold.path("sha256").asText());
        var checks = new LinkedHashMap<String,Boolean>(); row.put("checks", checks);
        var files = jdbc.queryForList("SELECT file_id,batch_id,status,progress_stage,attempt,execution_epoch,error_code,version_id,document_id,started_at,finished_at FROM ingestion_file WHERE logical_name=? AND sha256=? ORDER BY created_at", logical, gold.path("sha256").asText());
        var versions = jdbc.queryForList("SELECT d.document_id,d.status,v.version_id,v.lifecycle,v.sha256,v.original_filename,v.original_object_key,v.markdown_object_key,v.structured_object_key,v.parser_name,v.parser_version,m.security_level,m.acl_version FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id) WHERE d.logical_name=? ORDER BY v.version_no", logical);
        row.put("files", files); row.put("versions", versions);
        if (files.isEmpty() && versions.isEmpty()) { row.put("importStatus", "NOT_STARTED"); continue; }
        if (files.size()!=1 || versions.size()!=1) { row.put("importStatus", "AMBIGUOUS_SOURCE_IDENTITY"); checks.put("oneOwnedFileAndOneExpectedSourceVersion", false); continue; }
        var file = files.get(0); var version = versions.get(0); String id = version.get("version_id").toString();
        checks.put("sourceShaFilenameAndCurrentAclMatchFrozenCorpus", gold.path("sha256").asText().equals(version.get("sha256")) && gold.path("filename").asText().equals(version.get("original_filename")) && gold.path("securityLevel").asInt()==((Number)version.get("security_level")).intValue());
        row.put("rawFailures", jdbc.queryForList("SELECT execution_epoch,attempt,stage,outcome,error_code,occurred_at,next_attempt_at FROM raw_ingestion_failure WHERE file_id=? ORDER BY attempt,execution_epoch", file.get("file_id")));
        var publication = jdbc.queryForList("SELECT document_id,version_id,producer_scope,execution_epoch,acl_version,published_at FROM raw_publication WHERE version_id=?", id); row.put("publication", publication);
        long members = jdbc.queryForObject("SELECT count(*) FROM raw_publication_member WHERE version_id=?", Long.class, id);
        var blocks = knowledge.rawBlocks(id); long evidence = jdbc.queryForObject("SELECT count(*) FROM evidence WHERE version_id=? AND active", Long.class, id);
        row.put("storedRawBlocks", blocks.size()); row.put("activeRawEvidence", evidence); row.put("currentPublicationMembers", members);
        String key = Objects.toString(version.get("original_object_key"), "");
        if (!key.isBlank()) try {
          row.put("actualMinioOriginalSha256", objectHash(objects, key)); checks.put("actualMinioOriginalByteIdentity", gold.path("sha256").asText().equals(row.get("actualMinioOriginalSha256")));
        } catch (Exception missing) { row.put("originalReadFailure", missing.toString()); checks.put("actualMinioOriginalByteIdentity", false); }
        for (String column : List.of("markdown_object_key","structured_object_key")) {
          String artifact = Objects.toString(version.get(column), ""); if (!artifact.isBlank()) try { row.put(column + "ActualSha256", objectHash(objects, artifact)); } catch (Exception missing) { row.put(column + "ReadFailure", missing.toString()); }
        }
        if (!"SUCCEEDED".equals(file.get("status"))) { row.put("importStatus", "FAILED".equals(file.get("status")) ? "FAILED_ACTUAL_NORMAL_IMPORT" : "IN_PROGRESS_ACTUAL_NORMAL_IMPORT"); continue; }
        checks.put("succeededTaskAndActiveVersionSameSourceIdentity", "ACTIVE".equals(version.get("lifecycle")) && "ACTIVE".equals(version.get("status")) && id.equals(file.get("version_id")) && version.get("document_id").equals(file.get("document_id")));
        checks.put("realParsedArtifactsAndEvidenceExist", !Objects.toString(version.get("parser_name"), "").isBlank() && !Objects.toString(version.get("parser_version"), "").isBlank() && row.containsKey("markdown_object_keyActualSha256") && row.containsKey("structured_object_keyActualSha256") && !blocks.isEmpty() && evidence>0);
        var vector = new ArrayList<Float>(Collections.nCopies(1024,0f)); vector.set(0,1f);
        var current = index.searchVector(vector, new SearchFilters(null,null,null,List.of(id),127),50);
        var publicCurrent = index.searchVector(vector, new SearchFilters(null,null,null,List.of(id),10),50);
        row.put("actualMilvusCurrentPublicClearanceRows",publicCurrent.stream().map(c->Map.of("id",c.id(),"versionId",c.versionId())).toList());
        if (gold.path("securityLevel").asInt()>10) checks.put("nativePublicClearanceExcludesClassifiedCurrentSource",publicCurrent.isEmpty());
        row.put("actualMilvusCurrentRows", current.stream().map(c -> Map.of("id",c.id(),"versionId",c.versionId(),"knowledgeSha256",cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(c.knowledge()),"evidenceIds",c.evidenceIds())).toList());
        Set<String> memberIds = new TreeSet<>(jdbc.query("SELECT physical_id FROM raw_publication_member WHERE version_id=?",(rs,n)->rs.getString(1),id));
        checks.put("nativeAuthoritativePublicationFullyReadable", members>0 && current.size()==members && new TreeSet<>(current.stream().map(c -> c.id()).toList()).equals(memberIds));
        boolean staleProjection=publication.size()==1&&((Number)publication.get(0).get("acl_version")).longValue()!=((Number)version.get("acl_version")).longValue();
        row.put("importStatus", checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS_IMPORTED_SOURCE_READBACK" : staleProjection&&checks.get("sourceShaFilenameAndCurrentAclMatchFrozenCorpus")&&publicCurrent.isEmpty()?"AWAITING_NORMAL_RAW_PROJECTION_REPAIR":"FAIL_IMPORTED_SOURCE_READBACK");
      }
    } catch(Throwable failure) {
      try(var log=new PrintWriter(args[0]+".failure.log")) { failure.printStackTrace(log); }
      json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),Map.of("status","FAIL_READONLY_FORMAL_RAW_IMPORT_AUDIT","sources",rows,"failure",failure.toString())); System.exit(1);
    } finally { client.close(); }
    var counts = new TreeMap<String,Long>(); for(var row:rows) counts.merge(row.get("importStatus").toString(),1L,Long::sum);
    var report = new LinkedHashMap<String,Object>(); report.put("status","READONLY_FORMAL_RAW_IMPORT_GAP_AUDIT"); report.put("sources",rows); report.put("sourceCount",rows.size()); report.put("importStatusCounts",counts);
    report.put("boundary","Only source import readback, no formal query/model/heat/download/prewarming, no task mutation or new import. Native inspection uses privileged clearance127 to verify current storage, not an ordinary user's authority test. Reader artifact identity is separate from original producer artifacts. All 40 formal originals are Markdown, not scanned PDF quality coverage.");
    json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report); System.exit(0);
  }
  static String objectHash(MinioObjectStoreAdapter objects,String key)throws Exception {
    var digest=java.security.MessageDigest.getInstance("SHA-256"); var object=objects.get(key);
    try(var input=object.stream()) { byte[] bytes=new byte[65536]; int n; while((n=input.read(bytes))>=0)if(n>0)digest.update(bytes,0,n); }
    return HexFormat.of().formatHex(digest.digest());
  }
}
