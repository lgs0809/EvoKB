package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcKnowledgeRepository;
import cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawVectorCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only probe compiled against the immutable deployed JAR. Never encodes or changes profiles. */
public final class RawEncodingReadbackProbe {
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper(); var report = new LinkedHashMap<String,Object>();
    var checks = new LinkedHashMap<String,Boolean>(); MilvusClientV2 milvus = null;
    try {
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds); var repo = new JdbcKnowledgeRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), mapper);
      Store readonly = new Store() {
        public EffectiveConfiguration current() {
          return jdbc.query("SELECT embedding_base_url,embedding_model,embedding_dimensions,embedding_batch_size,embedding_timeout_seconds FROM model_configuration_profile WHERE active=TRUE", (rs,n)->
              new EffectiveConfiguration("","","gpt-5.6-luna","gpt-5.6-terra",60,rs.getString(1),"",rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),"","","",60,Instant.EPOCH)).stream().findFirst().orElseThrow();
        }
        public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException("read-only"); }
      };
      try(var config=new FrozenRawEncodingConfiguration(args[2],mapper,readonly,"whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
        var profile=config.currentProfile(); var cache=new JdbcRawVectorCache(jdbc,mapper);
        var versions=jdbc.queryForList("SELECT v.version_id,v.document_id,v.version_no,v.lifecycle,v.sha256,v.original_object_key,v.parser_name,v.parser_version,v.structured_object_key FROM document_version v JOIN document d USING(document_id) WHERE d.logical_name=? ORDER BY version_no",args[1]);
        milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(10000).build());
        boolean cached=true,finite=true,budget=true,bindings=true,parser=true,haveChunks=true;
        for(var version:versions) {
          String id=version.get("version_id").toString(),document=version.get("document_id").toString();
          var metadata=jdbc.query("SELECT source_department,material_category,manual_tags,company,document_date,auto_document_type FROM document_metadata WHERE version_id=?",(rs,n)->
              new Metadata(rs.getString(1),rs.getString(2),strings(mapper,rs.getString(3)),rs.getString(4),rs.getDate(5)==null?null:rs.getDate(5).toLocalDate(),rs.getString(6)),id).get(0);
          var blocks=repo.rawBlocks(id); var chunks=RawKnowledgeBuilder.smartChunks(document,id,metadata,blocks,RawChunkProfile.DEFAULT,config.tokens());
          haveChunks &= !chunks.isEmpty(); parser &= "mineru-office".equals(version.get("parser_name"))&&"3.4.5".equals(version.get("parser_version"));
          var observed=new ArrayList<Map<String,Object>>();
          for(var chunk:chunks) {
            String inputHash=sha(chunk.content()),key=profile.key(document,chunk.content());
            var stored=cache.find(key,profile.fingerprint(),inputHash,profile.dimensions());
            cached &= stored.isPresent();finite &= stored.isPresent()&&stored.get().stream().allMatch(Float::isFinite)&&stored.get().stream().anyMatch(n->Math.abs(n)>0.00001);
            int count=config.tokens().count(chunk.content());budget &= count<=900;
            var item=new LinkedHashMap<String,Object>();item.put("chunkId",chunk.id());item.put("inputHash",inputHash);item.put("cacheKey",key);item.put("tokenCount",count);item.put("headingPath",chunk.headingPath());item.put("sourceBlockIds",chunk.sourceBlockIds());
            var cacheRows=jdbc.queryForList("SELECT cache_key,profile_hash,input_hash,dimensions,created_at FROM raw_vector_cache WHERE cache_key=?",key);item.put("cacheRows",cacheRows);
            observed.add(item);
          }
          var rows=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"RAW\" and array_contains(version_ids, "+mapper.writeValueAsString(id)+")").outputFields(List.of("id","dense","evidence_ids","version_ids","is_active","content")).build()).getQueryResults();
          var vectors=new ArrayList<Map<String,Object>>();
          for(var row:rows) {
            var values=row.getEntity(); var vec=(List<?>)values.get("dense");
            finite &= vec.size()==1024&&vec.stream().allMatch(n->n instanceof Number&&Double.isFinite(((Number)n).doubleValue()));
            var proof=(List<?>)values.get("evidence_ids");
            for(var evidence:proof) bindings &= jdbc.queryForObject("SELECT count(*) FROM evidence WHERE evidence_id=? AND version_id=? AND document_id=?",Integer.class,evidence.toString(),id,document)==1;
            bindings &= !proof.isEmpty() && ((List<?>)values.get("version_ids")).contains(id);
            vectors.add(Map.of("chunkId",values.get("id"),"dimensions",vec.size(),"evidenceIds",proof,"versionIds",values.get("version_ids"),"active",values.get("is_active"),"inputHash",sha(values.get("content").toString())));
          }
          if("ACTIVE".equals(version.get("lifecycle"))) bindings &= rows.size()==chunks.size()&&rows.stream().allMatch(row->Boolean.TRUE.equals(row.getEntity().get("is_active")));
          version.put("chunks",observed);version.put("physicalRows",vectors);version.put("blockHashes",blocks.stream().map(b->Map.of("id",b.id(),"contentHash",b.contentHash(),"headings",b.headingPath())).toList());
        }
        checks.put("actualOffice345Versions",parser&&!versions.isEmpty());checks.put("exactProfileAndInputCacheKeysExist",cached&&haveChunks);checks.put("finiteNonzero1024Vectors",finite&&haveChunks);checks.put("nativeFinalInputTokensAtMost900",budget&&haveChunks);checks.put("actualCurrentMilvusProjectionAndVersionProofs",bindings&&haveChunks);
        report.put("versions",versions);report.put("profile",profile);report.put("profileFingerprint",profile.fingerprint());
        report.put("ingestionFiles",jdbc.queryForList("SELECT f.file_id,f.batch_id,f.status,f.progress_stage,f.attempt,f.document_id,f.version_id,f.started_at,f.finished_at,f.error_code FROM ingestion_file f WHERE f.logical_name=? ORDER BY f.created_at",args[1]));
      }
      report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_ACTUAL_RAW_ENCODING_READBACK":"FAIL_ACTUAL_RAW_ENCODING_READBACK");
    } catch(Throwable error) {error.printStackTrace();report.put("status","FAIL_ACTUAL_RAW_ENCODING_READBACK");report.put("failure",error.toString());}
    finally {if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Actual read-only PostgreSQL/Milvus/native tokenizer inspection; no embeddings, mutations or synthetic vectors. Runtime revision verification belongs to shared model deployment contract separately.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    // The standalone probe owns this JVM; MinIO/HTTP SDK idle dispatcher must not retain it.
    System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
  private static String sha(String value){return cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(value);}
  private static List<String> strings(ObjectMapper mapper,String json) {
    try {return json==null?List.of():mapper.readValue(json,List.class);}catch(Exception error){throw new IllegalStateException("invalid tags",error);}
  }
}
