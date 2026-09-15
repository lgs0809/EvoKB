import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcCompilationRepository;
import com.fasterxml.jackson.databind.*;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read only one actual normal Summary task and its exact PG/MinIO/Milvus publication. */
public final class SummaryPublicationReadbackProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String name,boolean value){checks.put(name,value);if(!value)throw new AssertionError(name);}
  static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
  static Set<String> strings(Collection<?> values){var set=new HashSet<String>();values.forEach(v->set.add(v.toString()));return set;}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();
    var mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    var snake=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    MilvusClientV2 milvus=null;MinioClient minio=null;
    try{
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var task=jdbc.queryForMap("SELECT * FROM compile_task WHERE task_id=?",args[1]);report.put("actualTask",task);
      check("selectedNormalTaskSucceededUnderItsOriginalExecution",args[2].equals(task.get("version_id"))&&"SUMMARY".equals(task.get("task_type"))&&"SUCCEEDED".equals(task.get("status"))
          &&!Objects.toString(task.get("owner_id"),"").isBlank()&&((Number)task.get("execution_epoch")).longValue()>0
          &&((java.sql.Timestamp)task.get("started_at")).toInstant().isAfter(Instant.parse(args[3]))
          &&!((java.sql.Timestamp)task.get("finished_at")).after((java.sql.Timestamp)task.get("stage_deadline")));
      var inputs=jdbc.queryForList("SELECT i.*,a.acl_version AS current_acl FROM compile_task_input i LEFT JOIN kb_source_authority a ON a.version_id=i.source_version_id WHERE i.task_id=? AND i.execution_epoch=?",args[1],task.get("execution_epoch"));
      check("actualCompletePrimarySourceInputAndAclRemainCurrent",inputs.size()==1&&args[2].equals(inputs.get(0).get("source_version_id"))&&Objects.equals(inputs.get(0).get("acl_version"),inputs.get(0).get("current_acl")));
      var repo=new JdbcCompilationRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),snake,null);
      var source=repo.loadVersionSource(args[2]);
      report.put("retainedSummaryHistory",jdbc.queryForList("SELECT summary_id,lifecycle,object_key,created_at FROM summary WHERE version_id=? ORDER BY created_at",args[2]));
      var ids=jdbc.queryForList("SELECT s.summary_id FROM summary s JOIN document_unit u ON u.unit_id=s.unit_id WHERE s.version_id=? AND s.object_key LIKE ? ORDER BY u.unit_no,s.created_at",String.class,args[2],"compilation/"+args[1]+"/epoch-"+task.get("execution_epoch")+"/%");
      check("normalTaskHasNewPublishedSummaryUnits",!ids.isEmpty());
      minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      var original=jdbc.queryForMap("SELECT original_object_key,sha256 FROM document_version WHERE version_id=?",args[2]);
      try(var bytes=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(original.get("original_object_key").toString()).build())){
        check("actualOriginalMinioBytesMatchPgAndFrozenInput",hash(bytes.readAllBytes()).equals(original.get("sha256"))&&hash(Files.readAllBytes(Path.of(args[4]))).equals(original.get("sha256")));
      }
      var observed=new ArrayList<Object>();var fingerprint=new StringBuilder();
      for(String id:ids){
        var summary=repo.findSummaryForAudit(id).orElseThrow();
        check(id+":everyFactPassesCurrentSevenDimensionSourceAudit",summary.activeAndGrounded()&&!summary.facts().isEmpty()&&GroundingConstraintAudit.currentSummary(summary,source)
            &&summary.facts().stream().allMatch(f->f.groundingAudit().claimBinding()==GroundingConstraintAudit.ClaimBinding.FULL_CANDIDATE_V2));
        check(id+":objectBelongsToSelectedTaskAndEpoch",summary.objectKey().startsWith("compilation/"+args[1]+"/epoch-"+task.get("execution_epoch")+"/"));
        String markdown;
        try(var bytes=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(summary.objectKey()).build())){
          byte[] body=bytes.readAllBytes();markdown=new String(body,java.nio.charset.StandardCharsets.UTF_8);
          check(id+":actualSummaryMinioBytesMatchStoredFullHash",hash(body).equals(summary.contentHash()));
        }
        var assets=jdbc.queryForList("SELECT a.* FROM kb_asset_current c JOIN kb_derived_asset a USING(asset_id,asset_version) WHERE c.asset_id=?",id);
        check(id+":currentPhysicalAssetIsPublishedSummary",assets.size()==1&&"SUMMARY".equals(assets.get(0).get("stage"))&&"PUBLISHED".equals(assets.get(0).get("publication_state")));
        var asset=assets.get(0);String physicalId=id+":r"+asset.get("asset_version");
        var rows=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == "+mapper.writeValueAsString(physicalId)).outputFields(List.of("id","dense","knowledge_type","content","version_ids","evidence_ids","is_active")).build()).getQueryResults();
        var evidence=new HashSet<String>();summary.facts().forEach(f->evidence.addAll(f.evidenceIds()));
        check(id+":exactMilvusContentAndCompleteSourceEvidenceMatch",rows.size()==1&&"SUMMARY".equals(rows.get(0).getEntity().get("knowledge_type"))&&Boolean.TRUE.equals(rows.get(0).getEntity().get("is_active"))
            &&markdown.equals(rows.get(0).getEntity().get("content"))&&strings((List<?>)rows.get(0).getEntity().get("version_ids")).equals(Set.of(args[2]))&&strings((List<?>)rows.get(0).getEntity().get("evidence_ids")).equals(evidence));
        var vector=(List<?>)rows.get(0).getEntity().get("dense");
        double norm=vector.stream().mapToDouble(v->Math.pow(((Number)v).doubleValue(),2)).sum();
        check(id+":actual1024FiniteUnitVector",vector.size()==1024&&vector.stream().allMatch(v->v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))&&Math.abs(norm-1.0)<0.01);
        fingerprint.append(id).append(':').append(summary.contentHash()).append(';');
        observed.add(Map.of("summary",summary,"asset",asset,"physicalId",physicalId,"physicalVectorSha256",hash(mapper.writeValueAsBytes(vector)),"vectorDimensions",vector.size(),"markdown",markdown));
      }
      check("actualTaskOutputHashReproducesPublishedUnits",KnowledgeDomain.sha256(fingerprint+"active_concept_tasks="+ids.size()).equals(task.get("output_hash")));
      var receipts=jdbc.queryForList("SELECT event_id,action,details_json::text AS details FROM audit_event WHERE target_type='compile_task' AND target_id=? AND (details_json->>'executionEpoch')::bigint=? ORDER BY created_at",args[1],task.get("execution_epoch"));
      check("actualResponseReceiptsRetainedUnderOriginalAttempt",!receipts.isEmpty()&&receipts.stream().allMatch(row->{try{
        var d=mapper.readTree(row.get("details").toString());return d.path("executionEpoch").asLong()==((Number)task.get("execution_epoch")).longValue()&&d.path("inputSourceCount").asInt()==inputs.size()
            &&!d.path("responseTruncated").asBoolean()&&KnowledgeDomain.sha256(d.path("responseText").asText()).equals(d.path("responseSha256").asText());
      }catch(Exception failure){throw new IllegalStateException(failure);}}));
      report.put("actualFrozenInputs",inputs);report.put("actualSummaries",observed);report.put("actualReceipts",receipts);
      report.put("status","PASS_ACTUAL_SUMMARY_PUBLICATION_READBACK");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_ACTUAL_SUMMARY_PUBLICATION_READBACK");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    finally{if(milvus!=null)milvus.close();if(minio!=null)minio.close();}
    report.put("checks",checks);report.put("boundary","Read-only selected normal GUI Summary execution, original producer start time, native current seven-dimensional proofs, MinIO original/summary bytes, published PG asset and physical Milvus content/vector. No model, state mutation or Concept/Graph/Wiki success claim; no formal semantic benchmark claim.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
