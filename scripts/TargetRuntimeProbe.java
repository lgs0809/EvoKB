import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only cross-check of actual browser-submitted ingestion in PostgreSQL, MinIO and Milvus. */
public class TargetRuntimeProbe {
  public static void main(String[] args)throws Exception{
    var mapper=new ObjectMapper();var checks=new LinkedHashMap<String,Boolean>();var report=new LinkedHashMap<String,Object>();
    MilvusClientV2 milvus=null;
    try{
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var rows=jdbc.queryForList("SELECT f.file_id,f.status,f.progress_stage,f.attempt,f.sha256 upload_sha256,f.size_bytes,f.error_code,d.document_id,d.logical_name,v.version_id,v.lifecycle,v.sha256,v.original_object_key,v.markdown_object_key,v.structured_object_key,v.parser_name,v.parser_version FROM ingestion_file f JOIN document_version v ON v.version_id=f.version_id JOIN document d ON d.document_id=v.document_id WHERE f.batch_id=? ORDER BY d.logical_name",args[1]);
      checks.put("threeNormalWorkflowResults",rows.size()==3&&rows.stream().allMatch(r->"SUCCEEDED".equals(r.get("status"))&&"ACTIVE".equals(r.get("lifecycle"))));
      if(!checks.get("threeNormalWorkflowResults"))throw new AssertionError("threeNormalWorkflowResults");
      var minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      var evidence=new ArrayList<Map<String,Object>>();
      for(var row:rows){
        String version=row.get("version_id").toString();if(!version.matches("[0-9a-f-]{36}"))throw new AssertionError("untrusted version identity");
        var objects=new ArrayList<Map<String,Object>>();
        for(String field:List.of("original_object_key","markdown_object_key","structured_object_key")){
          String key=row.get(field).toString();var stat=minio.statObject(StatObjectArgs.builder().bucket("evokb-target").object(key).build());
          var digest=MessageDigest.getInstance("SHA-256");long bytes=0;
          try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(key).build())){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))>=0){bytes+=n;digest.update(buffer,0,n);}}
          String hash=HexFormat.of().formatHex(digest.digest());objects.add(Map.of("key",key,"size",bytes,"sha256",hash));
          checks.put("actualMinioBytes_"+version+"_"+field,bytes==stat.size()&&bytes>0);
          if(field.equals("original_object_key"))checks.put("originalShaMatchesBackendComputedHash_"+version,hash.equals(row.get("sha256"))&&hash.equals(row.get("upload_sha256"))&&bytes==((Number)row.get("size_bytes")).longValue());
        }
        var vectors=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"RAW\" and ARRAY_CONTAINS(version_ids, \""+version+"\")").outputFields(List.of("id","is_active","dense","evidence_ids","version_ids")).build()).getQueryResults();
        int raw=jdbc.queryForObject("SELECT count(*) FROM raw_block WHERE version_id=?",Integer.class,version);
        int cites=jdbc.queryForObject("SELECT count(*) FROM evidence WHERE version_id=? AND active",Integer.class,version);
        checks.put("actualMilvusPublication_"+version,!vectors.isEmpty()&&vectors.stream().allMatch(v->Boolean.TRUE.equals(v.getEntity().get("is_active"))&&v.getEntity().get("dense") instanceof List<?> vector&&vector.size()==1024));
        var sourceIds=new HashSet<>(jdbc.queryForList("SELECT e.evidence_id FROM evidence e JOIN raw_block b ON b.block_id=e.block_id AND b.version_id=e.version_id WHERE e.version_id=? AND e.active AND b.raw_content<>''",String.class,version));
        // Heading blocks are retained for structure, but are deliberately not standalone citations.
        checks.put("rawBlocksAndEvidenceStored_"+version,raw>0&&cites>0&&cites<=raw&&sourceIds.size()==cites);
        checks.put("everyIndexedCitationResolvesToActualSourceBlock_"+version,vectors.stream().allMatch(v->v.getEntity().get("evidence_ids") instanceof List<?> refs&&!refs.isEmpty()&&sourceIds.containsAll(refs)));
        var item=new LinkedHashMap<String,Object>(row);item.put("minioObjects",objects);item.put("milvusRawChunks",vectors.size());item.put("rawBlocks",raw);item.put("activeEvidence",cites);evidence.add(item);
      }
      checks.put("sourceDocumentsRemainSeparate",rows.stream().map(r->r.get("document_id")).distinct().count()==3);
      checks.put("uploadDoesNotAutomaticallyCompileDerivedAssets",jdbc.queryForObject("SELECT count(*) FROM compile_task",Integer.class)==0);
      report.put("records",evidence);report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_REAL_BROWSER_INGESTION_PG_MILVUS_MINIO":"FAIL_REAL_BROWSER_INGESTION_PG_MILVUS_MINIO");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_BROWSER_INGESTION_PG_MILVUS_MINIO");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    finally{if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("batchId",args[1]);report.put("boundary","Actual CUA submission and real components for text Markdown. Does not certify MinerU, Stream delivery, epoch fencing, native multipart upload or QA.");mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
