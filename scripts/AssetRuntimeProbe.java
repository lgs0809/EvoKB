import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only proof of actual generated assets in the deployed business acceptance environment. */
public class AssetRuntimeProbe {
  public static void main(String[] args) throws Exception {
    var report=new LinkedHashMap<String,Object>();var checks=new LinkedHashMap<String,Boolean>();MilvusClientV2 milvus=null;
    try {
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      String summary=args[1];var s=jdbc.queryForMap("SELECT summary_id,version_id,grounding_status,coverage_status,lifecycle FROM summary WHERE summary_id=?",summary);
      checks.put("actualGroundedCoveredActiveSummary","ACTIVE".equals(s.get("lifecycle"))&&"PASS".equals(s.get("grounding_status"))&&"PASS".equals(s.get("coverage_status")));
      var concepts=jdbc.queryForList("SELECT concept_id,preferred_label,concept_type_hint FROM concept_candidate WHERE summary_id=? AND lifecycle='ACTIVE' ORDER BY concept_id",summary);
      checks.put("actualExtractedConcepts",concepts.size()==5);
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      var projections=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type in [\"CONCEPT\",\"WIKI\"] and ARRAY_CONTAINS(version_ids, \""+s.get("version_id")+"\")").outputFields(List.of("id","knowledge_type","content","attributes","evidence_ids","dense","is_active")).build()).getQueryResults();
      var projectionIds=projections.stream().map(p->p.getEntity().get("id").toString()).toList();
      checks.put("allActualConceptsPublishedToMilvus",concepts.stream().allMatch(c->projectionIds.contains(c.get("concept_id"))));
      checks.put("actual1024NonzeroVectors",projections.size()==6&&projections.stream().allMatch(p->p.getEntity().get("dense") instanceof List<?> v&&v.size()==1024&&v.stream().anyMatch(n->Math.abs(((Number)n).doubleValue())>0.00001)));
      checks.put("publishedProjectionIdentityHasAuthoritativeCatalogue",projections.stream().allMatch(p->jdbc.queryForObject("SELECT count(*) FROM kb_derived_asset WHERE asset_id=?",Integer.class,p.getEntity().get("id").toString())>0));
      checks.put("catalogueProofsAreActualSourceEvidence",jdbc.queryForObject("SELECT count(*) FROM kb_asset_source c CROSS JOIN LATERAL jsonb_array_elements_text(c.evidence_ids) refs LEFT JOIN evidence e ON e.evidence_id=refs.value AND e.document_id=c.document_id AND e.version_id=c.source_version_id WHERE e.evidence_id IS NULL",Integer.class)==0);
      var wikis=jdbc.queryForList("SELECT DISTINCT w.wiki_id,w.wiki_version,w.object_key,w.content_hash,w.lifecycle FROM wiki w JOIN wiki_claim c ON c.wiki_id=w.wiki_id AND c.wiki_version=w.wiki_version JOIN wiki_evidence we ON we.claim_id=c.claim_id JOIN evidence e ON e.evidence_id=we.evidence_id WHERE e.version_id=? AND w.lifecycle='ACTIVE'",s.get("version_id"));
      checks.put("actualActiveWiki",wikis.size()==1);
      var minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      var objects=new ArrayList<Map<String,Object>>();
      for(var wiki:wikis){try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(wiki.get("object_key").toString()).build())){
        byte[] bytes=stream.readAllBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        checks.put("wikiObjectBytesMatchStoredHash",hash.equals(wiki.get("content_hash"))&&bytes.length>0);
        objects.add(Map.of("wiki_id",wiki.get("wiki_id"),"object_key",wiki.get("object_key"),"sha256",hash,"bytes",bytes.length));
      }}
      checks.put("allTwentyWikiClaimsGrounded",jdbc.queryForObject("SELECT count(*)=20 AND bool_and(c.grounding_status='PASS') FROM wiki_claim c JOIN wiki w ON w.wiki_id=c.wiki_id AND w.wiki_version=c.wiki_version WHERE w.lifecycle='ACTIVE'",Boolean.class));
      var facts=jdbc.queryForList("SELECT asset_id,asset_version,stage FROM kb_derived_asset ORDER BY asset_id,asset_version");
      report.put("summary",s);report.put("concepts",concepts);report.put("assetRevisions",facts);report.put("wikiObjects",objects);
      report.put("tasks",jdbc.queryForList("SELECT task_id,task_type,status,attempt,error_code,error_message FROM compile_task WHERE version_id=? ORDER BY created_at",s.get("version_id")));
      report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_ACTUAL_GENERATED_ASSET_RUNTIME":"FAIL_ACTUAL_GENERATED_ASSET_RUNTIME");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_ACTUAL_GENERATED_ASSET_RUNTIME");report.put("failure",failure.toString());}
    finally{if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Read-only current PG/Milvus/MinIO checks. SUMMARY/CONCEPT/WIKI are actual model outputs; GRAPH failure retained. Does not certify canonical entry matching, automatic promotion, graph publication or daily quota.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
