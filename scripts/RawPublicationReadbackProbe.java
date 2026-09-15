package cn.lgs.knowledgebase.application.raw;

import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.EncodingIdentity;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.infrastructure.model.OpenAiCompatibleModelServingAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcKnowledgeRepository;
import cn.lgs.knowledgebase.infrastructure.parser.JacksonParsedArtifactEncoder;
import cn.lgs.knowledgebase.infrastructure.parser.MinerUPipelineAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.RoutingParserAdapter;
import cn.lgs.knowledgebase.infrastructure.parser.TextDocumentParserAdapter;
import cn.lgs.knowledgebase.infrastructure.raw.FrozenRawEncodingConfiguration;
import cn.lgs.knowledgebase.infrastructure.raw.JdbcRawVectorCache;
import cn.lgs.knowledgebase.infrastructure.storage.MinioObjectStoreAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only native resources; never loads/encodes a model, claims a task or updates a profile. */
public final class RawPublicationReadbackProbe {
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String,Object>();
    var checks = new LinkedHashMap<String,Boolean>();
    MilvusClientV2 milvus = null;
    try {
      String selectedVersion = optional(args,4), selectedFile = optional(args,5);
      String expectedSha = optional(args,6); long expectedSize = optional(args,7).isBlank() ? -1 : Long.parseLong(args[7]);
      String expectedParserVersion = optional(args,9).isBlank() ? "3.4.5" : args[9];
      JsonNode ready = optional(args,8).isBlank() ? null : MAPPER.readTree(Path.of(args[8]).toFile());
      var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), System.getenv("PROBE_DB_USER"), System.getenv("PROBE_DB_PASSWORD"));
      var jdbc = new JdbcTemplate(ds);
      var repo = new JdbcKnowledgeRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),MAPPER);
      // JdbcModelConfigurationStore's constructor bootstraps rows. Use a SELECT-only Store here.
      Store readonly = new Store() {
        public EffectiveConfiguration current() {
          var rows=jdbc.query("SELECT chat_base_url,chat_api_key,small_model,large_model,chat_timeout_seconds,embedding_base_url,embedding_api_key,embedding_model,embedding_dimensions,embedding_batch_size,embedding_timeout_seconds,rerank_base_url,rerank_api_key,rerank_model,rerank_timeout_seconds,updated_at FROM model_configuration_profile WHERE active=TRUE",(rs,n)->
              new EffectiveConfiguration(rs.getString(1),Objects.toString(rs.getString(2),""),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),Objects.toString(rs.getString(7),""),rs.getString(8),rs.getInt(9),rs.getInt(10),rs.getInt(11),rs.getString(12),Objects.toString(rs.getString(13),""),rs.getString(14),rs.getInt(15),rs.getTimestamp(16).toInstant()));
          if(rows.size()!=1) throw new IllegalStateException("Exactly one saved active embedding profile required");
          return rows.get(0);
        }
        public EffectiveConfiguration update(UpdateCommand ignored) { throw new UnsupportedOperationException("read-only"); }
      };
      try(var config=new FrozenRawEncodingConfiguration(args[2],MAPPER,readonly,"whole-input-no-truncation-v1:openai-embeddings-dimensions:server-qwen-document-template-v1")) {
        var effective=readonly.current(); var profile=config.currentProfile();
        var serving=new OpenAiCompatibleModelServingAdapter(readonly,MAPPER).rawEmbeddingPort();
        var identity=serving.currentEncodingIdentity().orElseThrow(()->new IllegalStateException("CURRENT_ENCODING_IDENTITY_UNAVAILABLE: observational GET cannot authenticate a nonresident/legacy encoder; no model load performed"));
        var certificate=certificate(effective);
        String independentServingHash=hash(MAPPER.writeValueAsBytes(canonical(certificate.path("profile"))));
        checks.put("actualServingCertificateMatchesSharedValidatorAndIndependentHash",identity.profileSha256().equals(independentServingHash)&&independentServingHash.equals(certificate.path("profile_sha256").asText()));
        String independentProfile=hash(framed("raw-atomic-encoding-v1",independentFingerprint(profile),independentServingHash));
        String certifiedProfile=profile.certifiedFingerprint(identity);
        checks.put("exactSavedAndFrozenProfileCertifiedIndependently",profile.fingerprint().equals(independentFingerprint(profile))&&certifiedProfile.equals(independentProfile));
        report.put("actualServingCertificate",certificate); report.put("actualEncodingIdentity",identity);
        report.put("profile",profile);report.put("profileFingerprint",profile.fingerprint());report.put("certifiedProfileFingerprint",certifiedProfile);
        List<Map<String,Object>> versions=selectedVersion.isBlank()
            ?jdbc.queryForList("SELECT v.* FROM document_version v JOIN document d USING(document_id) WHERE d.logical_name=? ORDER BY version_no",args[1])
            :jdbc.queryForList("SELECT v.* FROM document_version v JOIN document d USING(document_id) WHERE d.logical_name=? AND v.version_id=?",args[1],selectedVersion);
        checks.put("normalSelectedVersionExists",!versions.isEmpty()&&(selectedVersion.isBlank()||versions.size()==1));
        milvus=new MilvusClientV2(ConnectConfig.builder().uri(System.getenv("PROBE_MILVUS_URL")).rpcDeadlineMs(10000).build());
        var objects=new MinioObjectStoreAdapter(System.getenv("PROBE_MINIO_URL"),System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD"),System.getenv("PROBE_MINIO_BUCKET"));
        var cache=new JdbcRawVectorCache(jdbc,MAPPER);
        for(var version:versions) {
          String id=version.get("version_id").toString(),document=version.get("document_id").toString(),prefix=id+":";
          var meta=jdbc.query("SELECT source_department,material_category,manual_tags,company,document_date,auto_document_type FROM document_metadata WHERE version_id=?",(rs,n)->new Metadata(rs.getString(1),rs.getString(2),strings(rs.getString(3)),rs.getString(4),rs.getDate(5)==null?null:rs.getDate(5).toLocalDate(),rs.getString(6)),id).get(0);
          var blocks=repo.rawBlocks(id);var chunks=RawKnowledgeBuilder.smartChunks(document,id,meta,blocks,RawChunkProfile.DEFAULT,config.tokens());
          checks.put(prefix+"exactExpectedParserIdentity",args[3].equals(version.get("parser_name"))&&expectedParserVersion.equals(version.get("parser_version")));
          checks.put(prefix+"retrievableRawBlocksAndNativeFullInputChunks",!blocks.isEmpty()&&!chunks.isEmpty());
          checks.put(prefix+"rawBlockHashesMatchActualNormalizedBodies",blocks.stream().allMatch(b->b.contentHash().equals(hash(b.normalized()))));
          var pointers=jdbc.queryForList("SELECT p.*,f.status,f.started_at AS file_started_at,f.execution_epoch AS current_file_epoch,f.sha256 AS file_sha256,f.version_id AS file_version_id,f.size_bytes AS file_size,m.acl_version AS current_acl FROM raw_publication p JOIN ingestion_file f ON f.file_id=p.file_id JOIN document_metadata m ON m.version_id=p.version_id WHERE p.version_id=?",id);
          var members=jdbc.queryForList("SELECT physical_id FROM raw_publication_member WHERE version_id=? ORDER BY physical_id",id);
          version.put("currentPublication",pointers);version.put("currentPublicationMembers",members);
          boolean current=pointers.size()==1&&"ACTIVE".equals(version.get("lifecycle"));
          var pointer=current?pointers.get(0):Map.<String,Object>of();
          String scope=current?Objects.toString(pointer.get("producer_scope"),""):"";
          current &= "SUCCEEDED".equals(pointer.get("status"))&&id.equals(pointer.get("file_version_id"))
              &&version.get("sha256").equals(pointer.get("file_sha256"))&&Objects.equals(pointer.get("acl_version"),pointer.get("current_acl"))
              &&Objects.toString(pointer.get("file_id"),"").concat(":e"+pointer.get("execution_epoch")).equals(scope)
              &&number(pointer.get("execution_epoch"))<=number(pointer.get("current_file_epoch"));
          if(!selectedFile.isBlank()) current &= selectedFile.equals(pointer.get("file_id"))&&pointer.get("file_started_at") instanceof java.sql.Timestamp started&&started.toInstant().isAfter(Instant.parse(System.getenv("PROBE_RUNTIME_STARTED_AT")));
          checks.put(prefix+"currentPublicationOwnedByActualNormalFileEpochAndAcl",current);
          var rows=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"RAW\" and array_contains(version_ids, "+MAPPER.writeValueAsString(id)+")").outputFields(List.of("id","dense","evidence_ids","document_ids","version_ids","security_level","is_active","content","attributes")).build()).getQueryResults();
          var physical=new LinkedHashMap<String,Map<String,Object>>();boolean unique=true;
          for(var row:rows){var value=row.getEntity();unique &= physical.put(value.get("id").toString(),value)==null;}
          Set<String> published=members.stream().map(m->m.get("physical_id").toString()).collect(Collectors.toSet());
          Set<String> expectedPhysical=chunks.stream().map(c->c.id()+":e"+hash(scope).substring(0,24)).collect(Collectors.toSet());
          checks.put(prefix+"exactCurrentPhysicalSetAndCompletePublicationMembers",unique&&published.size()==chunks.size()&&published.equals(expectedPhysical)&&physical.keySet().containsAll(published));
          var observed=new ArrayList<Map<String,Object>>();boolean keys=true,vectors=true,bindings=true,tokens=true;
          for(var chunk:chunks) {
            String key=profile.certifiedKey(document,chunk.content(),identity),inputHash=hash(chunk.content());
            String independentKey=hash(framed(document,independentProfile,chunk.content()));
            var cacheRows=jdbc.queryForList("SELECT cache_key,profile_hash,input_hash,dimensions,vector_json,created_at FROM raw_vector_cache WHERE cache_key=?",key);
            var stored=cache.find(key,certifiedProfile,inputHash,profile.dimensions());
            keys &= key.equals(independentKey)&&cacheRows.size()==1&&stored.isPresent();
            int count=config.tokens().count(chunk.content());tokens &= count<=RawChunkProfile.DEFAULT.hardLimitTokens();
            String physicalId=chunk.id()+":e"+hash(scope).substring(0,24);
            var actual=physical.get(physicalId);var item=new LinkedHashMap<String,Object>();
            item.put("chunkId",chunk.id());item.put("physicalId",physicalId);item.put("inputHash",inputHash);item.put("cacheKey",key);item.put("independentCacheKey",independentKey);item.put("tokenCount",count);item.put("sourceBlockIds",chunk.sourceBlockIds());
            // Inspect actual PG metadata and bytes separately from cache.find's shared validity guard.
            if(cacheRows.size()==1){var row=cacheRows.get(0);keys &= certifiedProfile.equals(row.get("profile_hash"))&&inputHash.equals(row.get("input_hash"))&&number(row.get("dimensions"))==profile.dimensions();var safe=new LinkedHashMap<>(row);safe.remove("vector_json");item.put("cacheRow",safe);}
            if(actual==null||stored.isEmpty()){vectors=false;bindings=false;item.put("missingActualVectorOrCertifiedCache",true);}else{
              var dense=(List<?>)actual.get("dense");var cached=stored.get();
              double norm=norm(dense),cacheNorm=norm(cached),delta=distance(cached,dense);
              String actualVectorHash=vectorHash(dense),cachedVectorHash=vectorHash(cached);
              JsonNode pgVector=MAPPER.readTree(cacheRows.get(0).get("vector_json").toString());
              var directPg=new ArrayList<Double>();if(pgVector.isArray())pgVector.forEach(n->{if(!n.isNumber())throw new IllegalStateException("Actual cache JSON is not a numeric vector");directPg.add(n.doubleValue());});
              double directPgNorm=norm(directPg);String directPgHash=vectorHash(directPg);
              vectors &= directPg.size()==1024&&Math.abs(directPgNorm-1)<.005&&directPgHash.equals(cachedVectorHash)&&distance(directPg,cached)<=1e-7;
              vectors &= dense.size()==1024&&cached.size()==1024&&Math.abs(norm-1)<.005&&Math.abs(cacheNorm-1)<.005&&delta<=1e-7&&actualVectorHash.equals(cachedVectorHash);
              var attrs=node(actual.get("attributes"));var evidence=(List<?>)actual.get("evidence_ids");
              var expectedEvidence=jdbc.queryForList("SELECT evidence_id FROM evidence WHERE version_id=? AND active=TRUE AND block_id IN ("+String.join(",",Collections.nCopies(chunk.sourceBlockIds().size(),"?"))+")",concat(id,chunk.sourceBlockIds())).stream().map(r->r.get("evidence_id").toString()).collect(Collectors.toSet());
              bindings &= actual.get("content").equals(chunk.content())&&Boolean.TRUE.equals(actual.get("is_active"))
                  &&attrs.path("raw_scope").asText().equals(scope)&&attrs.path("content_hash").asText().equals(chunk.contentHash())
                  &&strings(actual.get("version_ids")).equals(List.of(id))&&strings(actual.get("document_ids")).equals(List.of(document))
                  &&!expectedEvidence.isEmpty()&&new HashSet<>(strings(evidence)).equals(expectedEvidence)
                  &&new HashSet<>(strings(attrs.path("source_block_ids"))).equals(new HashSet<>(chunk.sourceBlockIds()));
              item.put("actualMilvusInputHash",hash(actual.get("content").toString()));item.put("actualVectorFloat32Sha256",actualVectorHash);item.put("cacheVectorFloat32Sha256",cachedVectorHash);item.put("actualPGVectorJsonFloat32Sha256",directPgHash);item.put("actualPGVectorJsonNorm",directPgNorm);item.put("actualVectorNorm",norm);item.put("cacheVectorNorm",cacheNorm);item.put("maxAbsoluteDelta",delta);item.put("actualEvidenceIds",evidence);
            }
            observed.add(item);
          }
          checks.put(prefix+"certifiedProfileAndExactFullInputCacheKeysExist",keys&&!chunks.isEmpty());checks.put(prefix+"actualCacheAndMilvus1024UnitVectorBytesAgree",vectors&&!chunks.isEmpty());checks.put(prefix+"publishedTextEvidenceBlocksAndScopeExactlyMatch",bindings&&!chunks.isEmpty());checks.put(prefix+"nativeFinalInputTokensAtMost900",tokens&&!chunks.isEmpty());
          // Nonmembers are retained and reported, never borrowed as evidence of the current task.
          version.put("nonmemberPhysicalRows",physical.entrySet().stream().filter(e->!published.contains(e.getKey())).map(e->{var safe=new LinkedHashMap<>(e.getValue());safe.remove("dense");return safe;}).toList());
          String actualSha;long bytes=0;var digest=MessageDigest.getInstance("SHA-256");
          try(var stream=objects.get(version.get("original_object_key").toString()).stream()){byte[] buffer=new byte[65536];int n;while((n=stream.read(buffer))!=-1){bytes+=n;digest.update(buffer,0,n);}}
          actualSha=HexFormat.of().formatHex(digest.digest());version.put("actualOriginalSha256",actualSha);version.put("actualOriginalSizeBytes",bytes);
          checks.put(prefix+"actualMinioOriginalMatchesPGAndNormalInput",actualSha.equals(version.get("sha256"))&&bytes==number(version.get("size_bytes"))&&(expectedSha.isBlank()||expectedSha.equals(actualSha))&&(expectedSize<0||expectedSize==bytes));
          byte[] artifactBytes;try(var stream=objects.get(version.get("structured_object_key").toString()).stream()){artifactBytes=stream.readAllBytes();}
          var artifact=MAPPER.readTree(artifactBytes);var receipt=artifact.path("parser_receipt");version.put("actualStructuredSha256",hash(artifactBytes));version.put("actualParserReceipt",receipt);version.put("actualNativeArtifact",artifact);
          boolean receiptBound=args[3].equals(artifact.path("parser_name").asText())&&expectedParserVersion.equals(artifact.path("parser_version").asText())&&actualSha.equals(receipt.path("sourceSha256").asText());
          if(ready!=null)receiptBound &= receipt.path("runtimeSourceSha256").equals(ready.path("parserSourceSha256"))&&receipt.path("runtimeBundleSha256").equals(ready.path("parserRuntimeBundleSha256"))&&receipt.path("runtimeModulesSha256").equals(ready.path("parserRuntimeModulesSha256"));
          checks.put(prefix+"actualStructuredReceiptBindsSourceAndOptionalCurrentFrozenBundle",receiptBound);
          boolean rawMatchesArtifact=true;
          for(var block:blocks){var source=artifact.path("content_list").path(block.blockNo());String body=sourceBody(source,receipt);rawMatchesArtifact &= source.isObject()&&body.equals(block.content())&&source.path("type").asText().equals(block.blockType())&&hash(normalize(body)).equals(block.contentHash());if(!"office".equals(receipt.path("effectiveBackend").asText()))rawMatchesArtifact &= Objects.equals(block.pageStart(),source.has("page_idx")&&source.path("page_idx").asInt(-1)>=0?source.path("page_idx").asInt()+1:null);}
          checks.put(prefix+"actualPGBlocksMatchStructuredBodiesAndOriginalPageIdentity",rawMatchesArtifact&&!blocks.isEmpty());
          if (Boolean.parseBoolean(System.getenv("PROBE_REQUIRE_CURRENT_CHECKPOINT"))) {
            var proof = repo.parseCheckpoint(id).orElse(null);
            var currentParser = currentPipeline(version.get("original_filename").toString(),config,objects);
            var active = repo.currentActiveVersion(document).orElse(null);
            var source = new cn.lgs.knowledgebase.application.port.Ports.SourceFile(
                version.get("original_object_key").toString(),version.get("original_filename").toString(),
                bytes,actualSha,Objects.toString(version.get("media_type"),""));
            boolean complete = proof != null && active != null && id.equals(active.id())
                && proof.pipeline().equals(currentParser) && currentParser.sha256().equals(version.get("parser_pipeline_sha256"))
                && ParserCheckpointPolicy.reusable(proof,active,source,currentParser,blocks,objects);
            checks.put(prefix+"completeCurrentV23PipelineBlocksAndPhysicalCheckpoint",complete);
            checks.put(prefix+"independentStructuredBytesMatchSavedCheckpoint",proof != null && proof.artifactSha256().equals(hash(artifactBytes)));
            version.put("actualCurrentParserPipeline",currentParser);version.put("actualSavedParseCheckpoint",proof);
          }
          version.put("chunks",observed);version.put("blockHashes",blocks.stream().map(b->Map.of("id",b.id(),"contentHash",b.contentHash(),"headings",b.headingPath())).toList());
        }
        report.put("versions",versions);
        report.put("deliveryMetadata",jdbc.queryForList("SELECT f.file_id,f.version_id,f.status,o.message_id,o.delivery_epoch,(SELECT count(*) FROM raw_ingestion_delivery_history h WHERE h.file_id=f.file_id AND h.message_id=o.message_id AND h.delivery_epoch=o.delivery_epoch) AS exact_delivery_count FROM ingestion_file f LEFT JOIN raw_ingestion_outbox o USING(file_id) WHERE f.logical_name=? ORDER BY f.created_at",args[1]));
        report.put("ingestionFilesIncludingFailures",jdbc.queryForList("SELECT f.file_id,f.batch_id,f.status,f.progress_stage,f.attempt,f.document_id,f.version_id,f.execution_epoch,f.started_at,f.finished_at,f.error_code FROM ingestion_file f WHERE f.logical_name=? ORDER BY f.created_at",args[1]));
        checks.put("actualEncodingIdentityAndSavedProfileUnchangedDuringReadback",serving.currentEncodingIdentity().filter(identity::equals).isPresent()&&profile.equals(config.currentProfile()));
      }
      report.put("status",!checks.isEmpty()&&checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_ACTUAL_RAW_ENCODING_READBACK":"FAIL_ACTUAL_RAW_ENCODING_READBACK");
    } catch(Throwable error){error.printStackTrace();report.put("status","FAIL_ACTUAL_RAW_ENCODING_READBACK");report.put("failure",error.toString());}
    finally{if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Read-only PG/MinIO/Milvus/resident Serving GET/native tokenizer, exact current publication and full-input certified cache. Old failures and nonmember rows retained. No inference, claim, retry, ACK, mutation, general semantic proof or fabricated parser certification. Business Gold and Redis/role/browser checks are separate.");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
  private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n').trim();}
  private static String sourceBody(JsonNode b,JsonNode receipt){
    String type=b.path("type").asText(),text=b.path("text").asText("");
    if("table".equals(type)){var context=new ArrayList<String>();for(String key:List.of("table_caption","table_footnote"))if(b.path(key).isArray())b.path(key).forEach(n->{if(n.isTextual()&&!n.textValue().isBlank())context.add(n.textValue());});String html=b.path("table_body").asText("");if(html.isBlank())html=b.path("table_html").asText("");return (String.join("\n",context)+(context.isEmpty()?"":"\n")+html).trim();}
    if("image".equals(type)&&text.isBlank()&&b.path("image_caption").isArray())text=String.join(" ",strings(b.path("image_caption")));
    if("image".equals(type)&&text.isBlank()){String image=b.path("img_path").asText("");String stored=receipt.path("imageObjects").path(image).asText(image);return stored.isBlank()?"":"[image] "+stored;}
    return text.trim();
  }
  private static String optional(String[] a,int i){return a.length>i?a[i]:"";}
  private static ParserPipelineIdentity currentPipeline(String filename,FrozenRawEncodingConfiguration config,MinioObjectStoreAdapter objects)throws Exception {
    JsonNode saved=MAPPER.readTree(System.getenv("PROBE_CURRENT_PARSER_SETTINGS"));
    String endpoint=saved.path("EKB_MINERU_URL").asText("");
    var binary=endpoint.isBlank()?null:new MinerUPipelineAdapter(endpoint,MAPPER,
        Duration.ofMillis(saved.path("EKB_MINERU_POLL_MS").asLong(500)),
        Duration.ofSeconds(saved.path("EKB_MINERU_TASK_TIMEOUT_SECONDS").asLong(600)),
        saved.path("EKB_MINERU_PARSE_METHOD").asText("auto"),saved.path("EKB_MINERU_LANGUAGE").asText("ch"),
        saved.path("EKB_MINERU_BACKEND").asText("hybrid-engine"),saved.path("EKB_MINERU_EFFORT").asText("medium"),
        saved.path("EKB_MINERU_VERSION").asText("3.4.5"),objects);
    var router=new RoutingParserAdapter(new TextDocumentParserAdapter(MAPPER),binary);
    return ParserCheckpointPolicy.current(router,filename,RawChunkProfile.DEFAULT,config.tokens(),new JacksonParsedArtifactEncoder(MAPPER));
  }
  private static long number(Object o){return o instanceof Number?((Number)o).longValue():-1;}
  private static String hash(String s){return hash(s.getBytes(StandardCharsets.UTF_8));}
  private static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
  private static String framed(String...fields){var b=new StringBuilder();for(String f:fields)b.append(f.length()).append(':').append(f);return b.toString();}
  private static String independentFingerprint(RawEncodingProfile p){return hash(framed(p.model(),p.modelRevision(),Integer.toString(p.dimensions()),p.tokenizerIdentity(),p.preprocessingIdentity(),p.instruction(),p.endpointIdentity()));}
  private static JsonNode node(Object value)throws Exception{return value instanceof String||value instanceof com.google.gson.JsonElement?MAPPER.readTree(value.toString()):MAPPER.valueToTree(value);}
  private static List<String> strings(Object value){if(value instanceof JsonNode n){var result=new ArrayList<String>();n.forEach(x->result.add(x.asText()));return result;}if(value instanceof Collection<?> c)return c.stream().map(Object::toString).toList();try{return value==null?List.of():MAPPER.readValue(value.toString(),List.class);}catch(Exception e){throw new IllegalStateException("Invalid string array",e);}}
  private static Object[] concat(String id,List<String> blocks){var args=new ArrayList<Object>();args.add(id);args.addAll(blocks);return args.toArray();}
  private static double norm(List<?> values){double n=0;for(Object o:values){if(!(o instanceof Number)||!Double.isFinite(((Number)o).doubleValue()))return Double.NaN;double x=((Number)o).doubleValue();n+=x*x;}return Math.sqrt(n);}
  private static double distance(List<?> a,List<?> b){if(a.size()!=b.size())return Double.POSITIVE_INFINITY;double d=0;for(int i=0;i<a.size();i++)d=Math.max(d,Math.abs(((Number)a.get(i)).doubleValue()-((Number)b.get(i)).doubleValue()));return d;}
  private static String vectorHash(List<?> v){var b=ByteBuffer.allocate(v.size()*4).order(ByteOrder.LITTLE_ENDIAN);for(Object n:v)b.putFloat(((Number)n).floatValue());return hash(b.array());}
  private static JsonNode certificate(EffectiveConfiguration c)throws Exception{var b=HttpRequest.newBuilder(URI.create(c.embeddingBaseUrl()+"/v1/embedding-identity?input_type=document&dimensions="+c.embeddingDimensions())).timeout(Duration.ofSeconds(3)).GET();if(!c.embeddingApiKey().isBlank())b.header("Authorization","Bearer "+c.embeddingApiKey());var r=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(b.build(),HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()!=200)throw new IllegalStateException("Observational encoding certificate HTTP "+r.statusCode());return MAPPER.readTree(r.body());}
  private static Object canonical(JsonNode n){if(n.isObject()){var map=new TreeMap<String,Object>();n.fields().forEachRemaining(e->map.put(e.getKey(),canonical(e.getValue())));return map;}if(n.isArray()){var list=new ArrayList<Object>();n.forEach(x->list.add(canonical(x)));return list;}if(n.isTextual())return n.textValue();if(n.isBoolean())return n.booleanValue();if(n.isIntegralNumber())return n.bigIntegerValue();if(n.isNull())return null;throw new IllegalStateException("Unsupported resident profile field");}
}
