import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read actual stable entry revisions, full dependencies, physical vectors and original bytes. */
public final class StableEntryReadbackProbe {
  public static void main(String[] args) throws Exception {
    UUID.fromString(args[1]);
    var mapper=new ObjectMapper();var report=new LinkedHashMap<String,Object>();
    var checks=new LinkedHashMap<String,Boolean>();MilvusClientV2 milvus=null;MinioClient minio=null;
    try {
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var entries=jdbc.queryForList("SELECT DISTINCT e.* FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id) WHERE s.source_version_id=? AND e.lifecycle='ACTIVE' ORDER BY e.entry_id",args[1]);
      checks.put("normallyPublishedActiveStableEntriesExist",!entries.isEmpty());
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      boolean inputs=true,sources=true,identity=true,vectors=true,membership=true,sourceObjects=true,summaryObjects=true,scope=true;
      var observed=new ArrayList<Map<String,Object>>();var checkedSources=new HashSet<String>();var checkedSummaries=new HashSet<String>();
      for(var entry:entries) {
        String id=entry.get("entry_id").toString(),kb=entry.get("knowledge_base_id").toString();
        long revision=((Number)entry.get("revision")).longValue();
        var build=jdbc.queryForList("SELECT b.*,a.acl_version AS current_acl,s.lifecycle,s.grounding_status,s.coverage_status FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id LEFT JOIN summary s ON s.summary_id=b.summary_id WHERE b.knowledge_base_id=? AND b.entry_id=? AND b.entry_revision=?",kb,id,revision);
        inputs &= !build.isEmpty()&&build.stream().allMatch(b->Objects.equals(b.get("acl_version"),b.get("current_acl"))&&"ACTIVE".equals(b.get("lifecycle"))&&"PASS".equals(b.get("grounding_status"))&&"PASS".equals(b.get("coverage_status")));
        var supported=jdbc.queryForList("SELECT s.*,c.lifecycle AS concept_lifecycle,c.concept_type_hint,c.preferred_label AS source_label,a.acl_version AS current_acl FROM kb_entry_source s JOIN concept_candidate c ON c.concept_id=s.concept_id LEFT JOIN kb_source_authority a ON a.version_id=s.source_version_id WHERE s.knowledge_base_id=? AND s.entry_id=?",kb,id);
        sources &= !supported.isEmpty()&&supported.stream().allMatch(s->Objects.equals(s.get("acl_version"),s.get("current_acl"))&&"ACTIVE".equals(s.get("concept_lifecycle"))&&Objects.equals(entry.get("entry_type"),s.get("concept_type_hint")));
        var facts=new ArrayList<String>();
        for(var source:supported) {
          List<?> ids=mapper.readValue(source.get("fact_ids").toString(),List.class);
          for(Object fact:ids) {
            var found=jdbc.queryForList("SELECT f.fact_text FROM summary_fact f WHERE f.fact_id=? AND f.summary_id=? AND f.grounding_status='PASS' AND EXISTS(SELECT 1 FROM summary_evidence p JOIN evidence e USING(evidence_id) JOIN kb_source_authority a ON a.version_id=e.version_id WHERE p.fact_id=f.fact_id AND e.active AND e.version_id=?)",fact,source.get("summary_id"),source.get("source_version_id"));
            sources &= found.size()==1;found.forEach(f->facts.add(f.get("fact_text").toString()));
          }
          String version=source.get("source_version_id").toString();
          if(checkedSources.add(version)) {
            var original=jdbc.queryForMap("SELECT original_object_key,sha256 FROM document_version WHERE version_id=?",version);
            try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(original.get("original_object_key").toString()).build())) {sourceObjects &= digest(stream).equals(original.get("sha256"));}
          }
        }
        for(var input:build) {
          String summary=input.get("summary_id").toString();
          if(checkedSummaries.add(summary)) {
            var body=jdbc.queryForMap("SELECT object_key,content_hash FROM summary WHERE summary_id=?",summary);
            try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(body.get("object_key").toString()).build())) {summaryObjects &= digest(stream).equals(body.get("content_hash"));}
          }
        }
        Map<?,?> business=mapper.readValue(entry.get("scope_json").toString(),Map.class);
        if("SCOPE_GROUNDED".equals(business.get("identity_status"))) {
          List<?> anchors=(List<?>)business.get("anchors");scope &= anchors!=null&&!anchors.isEmpty();
          for(Object anchor:anchors) {
            String value=((Map<?,?>)anchor).get("value").toString();
            scope &= facts.stream().anyMatch(f->cn.lgs.knowledgebase.application.compilation.GraphCompilationService.canonicalKey(f).contains(value));
          }
        } else scope &= "SOURCE_SCOPED".equals(business.get("identity_status"));
        var asset=jdbc.queryForList("SELECT a.* FROM kb_asset_current c JOIN kb_derived_asset a USING(asset_id,asset_version) WHERE c.asset_id=?",id);
        identity &= asset.size()==1&&"PUBLISHED".equals(asset.get(0).get("publication_state"))&&entry.get("stage").equals(asset.get(0).get("stage"));
        var rows=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == "+mapper.writeValueAsString(id+":r"+asset.get(0).get("asset_version")))
            .outputFields(List.of("id","dense","knowledge_type","content","is_active","version_ids","evidence_ids")).build()).getQueryResults();
        identity &= rows.size()==1;var physical=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
          var value=row.getEntity();List<?> vector=(List<?>)value.get("dense");
          vectors &= vector.size()==1024&&vector.stream().allMatch(v->v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))&&vector.stream().anyMatch(v->Math.abs(((Number)v).doubleValue())>0.00001);
          membership &= Boolean.TRUE.equals(value.get("is_active"))&&entry.get("stage").equals(value.get("knowledge_type"))&&new HashSet<>((List<?>)value.get("version_ids")).equals(new HashSet<>(build.stream().map(b->b.get("source_version_id")).toList()))&&!((List<?>)value.get("evidence_ids")).isEmpty();
          physical.add(Map.of("id",value.get("id"),"dimensions",vector.size(),"vectorSha256",sha(mapper.writeValueAsBytes(vector)),"projection",value.get("content"),"versionIds",value.get("version_ids"),"evidenceIds",value.get("evidence_ids")));
        }
        observed.add(Map.of("entry",entry,"buildInputs",build,"sources",supported,"currentAssets",asset,"physicalRows",physical));
      }
      checks.put("everyModelInputStillHasItsExactCurrentAcl",inputs);checks.put("everyMemberHasGroundedFactsAndCurrentRawEvidence",sources);
      checks.put("stableIdentityHasOnePublishedCurrentProjection",identity);checks.put("actualVectorsAreFiniteNonzero1024",vectors);
      checks.put("physicalProjectionMatchesWholeInputVersionsAndEvidence",membership);checks.put("allOriginalObjectBytesMatchFullStoredSha256",sourceObjects);
      checks.put("allSummaryObjectBytesMatchPublishedContentHash",summaryObjects);checks.put("businessAnchorsHaveLiteralGroundedSupportingFacts",scope);
      report.put("entries",observed);report.put("originalsChecked",checkedSources.size());report.put("summariesChecked",checkedSummaries.size());
      report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_ACTUAL_STABLE_ENTRY_PUBLICATION":"FAIL_ACTUAL_STABLE_ENTRY_PUBLICATION");
    } catch(Throwable error) {error.printStackTrace();report.put("status","FAIL_ACTUAL_STABLE_ENTRY_PUBLICATION");report.put("failure",error.toString());}
    finally {if(milvus!=null)milvus.close();if(minio!=null)minio.close();}
    report.put("checks",checks);report.put("boundary","Read-only actual PostgreSQL/MinIO/Milvus output inspection; no inference, state edits, synthetic vectors or fixed model output count. Actual generation artifact and time are bound by the supplied prior task observation, separately from the reader artifact.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
  static String digest(java.io.InputStream stream)throws Exception {var digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];for(int n;(n=stream.read(block))!=-1;)digest.update(block,0,n);return HexFormat.of().formatHex(digest.digest());}
  static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
