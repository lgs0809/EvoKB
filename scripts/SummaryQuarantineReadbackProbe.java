import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcAssetCatalogue;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only the actual historical overbudget leaf; physical retention is not an accepted result. */
public final class SummaryQuarantineReadbackProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String key,boolean ok){checks.put(key,ok);if(!ok)throw new AssertionError(key);}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper().findAndRegisterModules();
    MilvusClientV2 milvus=null;
    try(var minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build()){
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var summary=jdbc.queryForMap("SELECT * FROM summary WHERE summary_id=?",args[1]);
      var task=jdbc.queryForMap("SELECT * FROM compile_task WHERE task_id=?",args[2]);
      check("historicalTaskRemainsFailedWithOneAttemptAndNoStatusRewrite",task.get("status").equals("FAILED")&&((Number)task.get("attempt")).intValue()==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=?",Integer.class,args[2])==1);
      check("originalFiveAttemptFailureAndAllFiveHistoriesRemain",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id='5c9895d2-c7a2-485a-9025-6343e5f967a4' AND status='FAILED' AND attempt=5",Integer.class)==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id='5c9895d2-c7a2-485a-9025-6343e5f967a4'",Integer.class)==5);
      check("normalMaintenanceMarksOnlyActualLeafStale",summary.get("lifecycle").equals("STALE")&&jdbc.queryForObject("SELECT count(*) FROM concept_candidate WHERE summary_id=?",Integer.class,args[1])==0&&jdbc.queryForObject("SELECT count(*) FROM kb_entry_build_input WHERE summary_id=?",Integer.class,args[1])==0);
      var asset=jdbc.queryForMap("SELECT a.* FROM kb_asset_current c JOIN kb_derived_asset a USING(asset_id,asset_version) WHERE c.asset_id=?",args[1]);
      check("assetIsStagedAndCannotResolveThroughCurrentAuthority",asset.get("publication_state").equals("STAGED")&&new JdbcAssetCatalogue(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))).currentSources(args[1],((Number)asset.get("asset_version")).longValue(),null).isEmpty());
      byte[] body;try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(summary.get("object_key").toString()).build())){body=stream.readAllBytes();}
      String content=new String(body,java.nio.charset.StandardCharsets.UTF_8);
      check("actualMinioObjectIsRetainedWithItsOriginalFullHash",KnowledgeDomain.sha256(content).equals(summary.get("content_hash"))&&summary.get("object_key").toString().startsWith("compilation/"+args[2]+"/epoch-1/"));
      var rows=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == "+mapper.writeValueAsString(args[1]+":r"+asset.get("asset_version"))).outputFields(List.of("id","content","is_active","knowledge_type")).build()).getQueryResults();
      check("physicalMilvusProjectionRetainedButPgAuthorityRevokesServing",rows.size()==1&&content.equals(rows.get(0).getEntity().get("content"))&&rows.get(0).getEntity().get("knowledge_type").equals("SUMMARY")&&Boolean.TRUE.equals(rows.get(0).getEntity().get("is_active")));
      check("factsAndOriginalModelReceiptsRemainForInvestigation",jdbc.queryForObject("SELECT count(*) FROM summary_fact WHERE summary_id=?",Integer.class,args[1])>0&&jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_type='compile_task' AND target_id=?",Integer.class,args[2])==7);
      check("independentSuccessfulNoteSummaryRemainsPublished",jdbc.queryForObject("SELECT count(*) FROM summary s JOIN kb_derived_asset a ON a.asset_id=s.summary_id WHERE s.summary_id='2a38beaa-7daa-4fb4-955a-db26c4c35e45' AND s.lifecycle='ACTIVE' AND a.publication_state='PUBLISHED'",Integer.class)==1);
      report.put("status","PASS_ACTUAL_OVERLIMIT_LEAF_QUARANTINE_READBACK");report.put("actualTask",task);report.put("actualSummary",summary);report.put("actualAsset",asset);report.put("physicalMilvusRow",rows.get(0).getEntity());
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_ACTUAL_OVERLIMIT_LEAF_QUARANTINE_READBACK");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    finally{if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Read-only actual historical overlimit output. Normal application startup maintenance set existing STALE/STAGED visibility, leaving task/failure/facts/model receipts/MinIO/Milvus bytes. The historical sixth business attempt remains a recorded defect, never a successful acceptance case.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
