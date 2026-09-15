import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import static cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.compilation.GroundingConstraintAudit;
import cn.lgs.knowledgebase.application.compilation.CompilationPublication;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only actual stable Graph/Wiki tasks, with the deployed policy and native repositories. */
public final class StableDerivedReadbackProbe {
  static String[] subject(String input) {
    String[] key=input.split("/",-1);
    if(key.length!=4||!key[0].equals("entry")||!key[2].equals("revision")||key[1].isBlank()
        ||Long.parseLong(key[3])<1)throw new IllegalStateException("Invalid frozen entry task subject");
    return key;
  }
  static String key(String kb,String kind,String version,String subject) {
    var type=cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.valueOf(kind);
    return generationTaskKey(kb,version,type,subject);
  }
  static void require(boolean condition,String reason) {if(!condition)throw new IllegalStateException(reason);}
  static Set<String> strings(List<?> values) {var result=new HashSet<String>();values.forEach(v->result.add(v.toString()));return result;}
  static boolean vector(List<?> vector) {
    return vector!=null&&vector.size()==1024&&vector.stream().allMatch(v->v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))
        &&vector.stream().anyMatch(v->Math.abs(((Number)v).doubleValue())>0.00001);
  }
  static String digest(java.io.InputStream input)throws Exception {
    var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];for(int n;(n=input.read(block))!=-1;)digest.update(block,0,n);
    return HexFormat.of().formatHex(digest.digest());
  }
  static String auditedResponse(Map<String,Object> receipt) {
    String text=Objects.toString(receipt.get("responseText"),"");
    require("compilation-model-receipt-v1".equals(receipt.get("receiptVersion"))
        &&"RELATION_EXTRACT".equals(receipt.get("modelTask"))&&"LARGE".equals(receipt.get("modelRole"))
        &&"gpt-5.6-terra".equals(receipt.get("model"))
        &&GraphCompilationService.Extraction.class.getName().equals(receipt.get("outputType"))
        &&Boolean.FALSE.equals(receipt.get("responseTruncated"))&&!text.isBlank()
        &&Objects.equals(receipt.get("responseSha256"),KnowledgeDomain.sha256(text))
        &&receipt.get("responseBytes") instanceof Number bytes
        &&bytes.longValue()==text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "Exact untruncated actual Graph model response receipt is required");
    return text;
  }
  public static void main(String[] args)throws Exception {
    if(args[0].equals("--selfcheck")) {
      require(Arrays.equals(subject("entry/fixture/revision/2"),new String[]{"entry","fixture","revision","2"}),"valid subject");
      for(String value:List.of("summary/fixture","entry//revision/2","entry/fixture/revision/0","entry/fixture/revision/-1","entry/fixture/revision/abc","entry/fixture/epoch/2","entry/fixture/revision/2/extra")) {
        boolean rejected=false;try{subject(value);}catch(RuntimeException expected){rejected=true;}require(rejected,"Rejected malformed subject: "+value);
      }
      String expected="generation:graph:"+GroundingConstraintAudit.POLICY_VERSION+":"+COMPLETE_INPUT_POLICY+":source:entry/fixture/revision/2";
      require(key("enterprise","GRAPH","source","entry/fixture/revision/2").equals(expected),"current native task key");
      require(!key("other","GRAPH","source","entry/fixture/revision/2").equals(expected),"custom KB task key isolation");
      var receipt=new LinkedHashMap<String,Object>();String response="{\"entities\":[],\"relations\":[],\"factDecisions\":[]}";
      receipt.put("receiptVersion","compilation-model-receipt-v1");receipt.put("modelTask","RELATION_EXTRACT");receipt.put("modelRole","LARGE");receipt.put("model","gpt-5.6-terra");receipt.put("outputType",GraphCompilationService.Extraction.class.getName());receipt.put("responseText",response);receipt.put("responseBytes",response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);receipt.put("responseSha256",KnowledgeDomain.sha256(response));receipt.put("responseTruncated",false);
      require(auditedResponse(receipt).equals(response),"exact received response accepted");
      for(var change:List.of(Map.entry("responseText",(Object)(response+" ")),Map.entry("responseTruncated",(Object)true),Map.entry("model",(Object)"unapproved"),Map.entry("responseBytes",(Object)1),Map.entry("outputType",(Object)"wrong"))) {
        var invalid=new LinkedHashMap<>(receipt);invalid.put(change.getKey(),change.getValue());boolean rejected=false;
        try{auditedResponse(invalid);}catch(IllegalStateException expectedFailure){rejected=true;}require(rejected,"Corrupt response receipt rejected: "+change.getKey());
      }
      System.out.println("PASS_SYNTHETIC_NATIVE_BINDING 16 checks; no database/model/resource calls");return;
    }
    UUID.fromString(args[1]);UUID.fromString(args[2]);
    var mapper=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    var snake=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var report=new LinkedHashMap<String,Object>();var checks=new LinkedHashMap<String,Boolean>();MinioClient minio=null;MilvusClientV2 milvus=null;
    try {
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      var task=jdbc.queryForMap("SELECT * FROM compile_task WHERE task_id=?",args[1]);report.put("actualTask",task);
      String kind=task.get("task_type").toString(),kb=System.getenv().getOrDefault("PROBE_KNOWLEDGE_BASE","enterprise");
      require(Set.of("GRAPH","WIKI").contains(kind)&&kind.equals(args[3])&&"SUCCEEDED".equals(task.get("status"))
          &&args[2].equals(task.get("version_id"))&&kb.equals(task.get("knowledge_base_id")),"Actual selected task did not succeed for source/type/KB");
      String taskSubject=Objects.toString(task.get("subject_id"),"");String[] binding=subject(taskSubject);
      String taskKey=key(kb,kind,args[2],taskSubject);
      require(taskKey.equals(task.get("idempotency_key"))&&KnowledgeDomain.sha256(taskKey).equals(task.get("input_hash")),"Current policy task identity/hash is invalid");
      require(!Objects.toString(task.get("owner_id"),"").isBlank()&&((Number)task.get("execution_epoch")).longValue()>0
          &&task.get("finished_at")!=null&&task.get("stage_deadline")!=null
          &&!((java.sql.Timestamp)task.get("finished_at")).after((java.sql.Timestamp)task.get("stage_deadline")),"Actual task lacks a valid completed ownership/deadline claim");
      var nativeCompilation=new JdbcCompilationRepository(jdbc,tx,snake,null);
      CompilationRepository compilation=(CompilationRepository)java.lang.reflect.Proxy.newProxyInstance(
          CompilationRepository.class.getClassLoader(),new Class<?>[]{CompilationRepository.class},(proxy,method,values)->{
            if(method.getName().equals("findSummary"))return nativeCompilation.findSummaryForAudit((String)values[0]);
            if(method.getName().equals("loadSummaries")) {
              var result=new ArrayList<SummaryRecord>();for(Object id:(List<?>)values[0])nativeCompilation.findSummaryForAudit(id.toString()).ifPresent(result::add);return result;
            }
            return method.invoke(nativeCompilation,values);
          });
      var catalogue=new JdbcKnowledgeEntryCatalogue(jdbc,tx,mapper,kb,compilation);
      var entry=catalogue.find(binding[1]).orElseThrow();report.put("frozenEntryReadback",entry);
      require(entry.revision()==Long.parseLong(binding[3])&&entry.versionIds().contains(args[2]),"Actual task entry revision/input source no longer matches");
      var summaries=GroundingConstraintAudit.requireCurrentEntry(entry,compilation);
      require(!summaries.isEmpty()&&!entry.sources().isEmpty(),"Current entry has no audited source members");
      var frozenInputs=jdbc.queryForList("SELECT i.*,a.acl_version AS current_acl,a.security_level AS current_security_level FROM compile_task_input i LEFT JOIN kb_source_authority a ON a.version_id=i.source_version_id WHERE i.task_id=? AND i.execution_epoch=? ORDER BY i.source_version_id",args[1],task.get("execution_epoch"));
      require(strings(frozenInputs.stream().map(i->i.get("source_version_id")).toList()).equals(new HashSet<>(entry.versionIds()))
          &&frozenInputs.stream().allMatch(i->Objects.equals(i.get("acl_version"),i.get("current_acl"))),"Actual claimed full model inputs or current ACL differ");
      var ownAcl=jdbc.queryForObject("SELECT acl_version FROM kb_source_authority WHERE version_id=?",Long.class,args[2]);
      require(ownAcl!=null&&ownAcl.longValue()==((Number)task.get("source_acl_version")).longValue(),"Actual task source ACL changed");
      for(var input:entry.buildInputs()) {
        var summary=summaries.stream().filter(s->s.id().equals(input.summaryId())).findFirst().orElseThrow();
        var acl=jdbc.queryForObject("SELECT acl_version FROM kb_source_authority WHERE version_id=?",Long.class,input.versionId());
        require(summary.versionId().equals(input.versionId())&&acl!=null&&acl.longValue()==input.aclVersion(),"Current entry full model dependency source/ACL differs");
      }
      for(var source:entry.sources()) {
        var summary=summaries.stream().filter(s->s.id().equals(source.summaryId())).findFirst().orElseThrow();
        var acl=jdbc.queryForObject("SELECT acl_version FROM kb_source_authority WHERE version_id=?",Long.class,source.versionId());
        require(summary.versionId().equals(source.versionId())&&summary.documentId().equals(source.documentId())
            &&acl!=null&&acl.longValue()==source.aclVersion()
            &&!source.factIds().isEmpty()&&summary.facts().stream().map(SummaryFact::id).toList().containsAll(source.factIds()),"Actual source member/fact binding changed");
      }
      report.put("currentAuditedSummaryIds",summaries.stream().map(SummaryRecord::id).toList());report.put("fullActualClaimedInputs",frozenInputs);
      int readerClearance=jdbc.queryForObject("SELECT security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE'",Integer.class,args[6]);
      int requiredClearance=frozenInputs.stream().mapToInt(i->((Number)i.get("current_security_level")).intValue()).max().orElseThrow();
      report.put("readerSecurityClearance",readerClearance);report.put("fullInputRequiredClearance",requiredClearance);
      checks.put("actualTaskPolicySubjectRevisionOwnerDeadlineFullAuditAndAcl",true);
      report.put("completeEntryTaskHistory",jdbc.queryForList("SELECT * FROM compile_task WHERE knowledge_base_id=? AND subject_id LIKE ? ORDER BY created_at",kb,"entry/"+entry.id()+"/revision/%"));
      minio=MinioClient.builder().endpoint("http://127.0.0.1:19400").credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      var originalHashes=new LinkedHashMap<String,String>();var summaryHashes=new LinkedHashMap<String,String>();
      for(var summary:summaries) {
        var original=jdbc.queryForMap("SELECT original_object_key,sha256 FROM document_version WHERE version_id=?",summary.versionId());
        try(var bytes=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(original.get("original_object_key").toString()).build())) {
          String sha=digest(bytes);require(sha.equals(original.get("sha256")),"Original MinIO bytes changed");originalHashes.put(summary.versionId(),sha);
        }
        try(var bytes=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(summary.objectKey()).build())) {
          String sha=digest(bytes);require(sha.equals(summary.contentHash()),"Audited Summary MinIO bytes changed");summaryHashes.put(summary.id(),sha);
        }
      }
      report.put("actualOriginalSha256",originalHashes);report.put("actualAuditedSummaryContentSha256",summaryHashes);
      checks.put("actualAllOriginalAndSummaryMinioBytesMatchFullHashes",true);
      if(kind.equals("GRAPH")) {
        var relationIds=new LinkedHashSet<String>();var fingerprints=new ArrayList<String>();
        // Summary relation storage is cumulative across normal entry rebuilds. It is not the
        // output manifest of this one execution. Replay its immutable received responses through
        // the exact deployed validator, suppressing ALL publication callbacks and model requests.
        var receiptRows=jdbc.queryForList("SELECT event_id,details_json,created_at FROM audit_event WHERE action='COMPILATION_MODEL_OUTPUT' AND target_id=? AND details_json::jsonb->>'executionEpoch'=? ORDER BY created_at,event_id",args[1],task.get("execution_epoch").toString());
        require(receiptRows.size()==entry.summaryIds().size(),"Actual Graph response count does not match frozen Summary order");
        var receipts=new ArrayList<Map<String,Object>>();
        for(var row:receiptRows) {
          var value=mapper.readValue(row.get("details_json").toString(),Map.class);
          auditedResponse(value);
          require(args[1].equals(value.get("taskId"))&&"GRAPH".equals(value.get("compilationType"))
              &&((Number)value.get("attempt")).intValue()==((Number)task.get("attempt")).intValue()
              &&args[2].equals(value.get("sourceVersionId")),"Actual Graph response task authority differs");
          receipts.add(value);
        }
        var replayIndex=new int[]{0};var suppressedWrites=new int[]{0};
        KnowledgeModel receivedOnly=(KnowledgeModel)java.lang.reflect.Proxy.newProxyInstance(KnowledgeModel.class.getClassLoader(),new Class<?>[]{KnowledgeModel.class},(proxy,method,values)->{
          if(method.getName().equals("identity"))return "gpt-5.6-terra";
          if(method.getName().equals("generateStructured")) {
            require(values[0]==ModelRole.LARGE&&values[1]==ModelTask.RELATION_EXTRACT
                &&values[4]==GraphCompilationService.Extraction.class&&replayIndex[0]<receipts.size(),"Unexpected replay model operation");
            return mapper.readValue(auditedResponse(receipts.get(replayIndex[0]++)),GraphCompilationService.Extraction.class);
          }
          throw new AssertionError("No new model operation permitted: "+method.getName());
        });
        GraphStore noGraphWrite=(GraphStore)java.lang.reflect.Proxy.newProxyInstance(GraphStore.class.getClassLoader(),new Class<?>[]{GraphStore.class},(proxy,method,values)->{throw new AssertionError("Readback must not publish Graph: "+method.getName());});
        var dryPublication=CompilationPublication.direct(write->suppressedWrites[0]++);
        var compiler=new GraphCompilationService(compilation,receivedOnly,noGraphWrite);
        var cumulativeIds=new LinkedHashMap<String,List<String>>();
        for(String summary:entry.summaryIds()) {
          var result=compiler.compileSummary(summary,dryPublication,entry.versionIds());
          var ids=result.relations().stream().map(r->r.id()).sorted().toList();
          fingerprints.add(summary+":"+ids);relationIds.addAll(ids);
          cumulativeIds.put(summary,jdbc.queryForList("SELECT DISTINCT f.relation_id FROM relation_fact f JOIN relation_evidence_support p USING(relation_id) WHERE p.summary_id=? AND p.active AND f.lifecycle='ACTIVE' ORDER BY f.relation_id",String.class,summary));
        }
        require(replayIndex[0]==receipts.size()&&suppressedWrites[0]==2*entry.summaryIds().size()
            &&!relationIds.isEmpty()&&KnowledgeDomain.sha256(fingerprints.toString()).equals(task.get("output_hash")),"Received Graph responses do not reproduce actual selected task output hash");
        report.put("actualModelResponseReceipts",receiptRows);report.put("reproducedExactOutputFingerprints",fingerprints);
        report.put("cumulativeCurrentSummaryRelationIds",cumulativeIds);
        report.put("replayBoundary",Map.of("newModelRequests",0,"publicationCallbacksExecuted",0,"suppressedPublicationCallbacks",suppressedWrites[0],"source","Exact actual immutable model output receipts, validated by deployed compiler; not a new live model run"));
        checks.put("exactReceivedModelResponsesReproduceTaskOutputWithoutWrites",true);
        var relations=new ArrayList<Map<String,Object>>();var proofs=new ArrayList<Map<String,Object>>();var graphInputs=new ArrayList<Map<String,Object>>();var entities=new LinkedHashMap<String,Map<String,Object>>();
        for(String id:relationIds) {
          var revisions=jdbc.queryForList("SELECT * FROM kb_graph_relation WHERE kb_id=? AND relation_id=? AND is_active",kb,id);
          require(revisions.size()==1,"Actual Graph relation has missing or multiple current physical revisions");var relation=revisions.get(0);relations.add(relation);
          var inputs=jdbc.queryForList("SELECT d.*,a.acl_version AS current_acl FROM kb_graph_build_dependency d LEFT JOIN kb_source_authority a ON a.version_id=d.source_version_id WHERE d.kb_id=? AND d.relation_id=? AND d.revision_id=? ORDER BY d.source_version_id",kb,id,relation.get("revision_id"));
          require(COMPLETE_INPUT_POLICY.equals(relation.get("input_policy_version"))
              &&((Number)relation.get("input_source_count")).intValue()==entry.versionIds().size()
              &&strings(inputs.stream().map(d->d.get("source_version_id")).toList()).equals(new HashSet<>(entry.versionIds()))
              &&inputs.stream().allMatch(d->Objects.equals(d.get("acl_version"),d.get("current_acl"))),"Graph complete current-policy model inputs/ACL differ");graphInputs.addAll(inputs);
          var evidence=jdbc.queryForList("SELECT p.*,e.version_id,e.document_id,e.active AS evidence_active,a.acl_version AS current_acl,b.content_hash AS raw_content_hash FROM kb_graph_evidence p JOIN evidence e USING(evidence_id) JOIN raw_block b ON b.block_id=e.block_id LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE p.kb_id=? AND p.relation_id=? AND p.revision_id=? ORDER BY p.evidence_id",kb,id,relation.get("revision_id"));
          require(!evidence.isEmpty()&&evidence.stream().allMatch(p->Boolean.TRUE.equals(p.get("evidence_active"))&&Objects.equals(p.get("acl_version"),p.get("current_acl"))&&entry.versionIds().contains(p.get("version_id"))),"Actual Graph physical proof is outside current frozen sources/ACL");proofs.addAll(evidence);
          for(String endpoint:List.of(relation.get("source_entity_id").toString(),relation.get("target_entity_id").toString())) {
            var entity=jdbc.queryForMap("SELECT * FROM kb_graph_entity WHERE kb_id=? AND entity_id=?",kb,endpoint);
            require(!Set.of("CONCEPT","WIKI","SUMMARY","CHUNK").contains(entity.get("entity_type"))&&entity.get("object_id")!=null&&!Objects.toString(entity.get("scope_key"),"").isBlank(),"Graph endpoint is not a scoped physical business/document entity");entities.put(endpoint,entity);
          }
        }
        require(relations.stream().anyMatch(r->!r.get("relation_type").equals("DOCUMENTS")),"No actual business Graph relation was published");
        int clearance=jdbc.queryForObject("SELECT security_clearance FROM enterprise_user WHERE user_id=? AND status='ACTIVE'",Integer.class,args[4]);
        require(!args[5].isBlank(),"Actual normal Graph browse query is required");
        var nativeRows=new JdbcPublishedGraphReader(jdbc,kb).browse(args[5],2,100,null,null,clearance);
        require(nativeRows.stream().map(r->r.relationId()).collect(java.util.stream.Collectors.toSet()).containsAll(relationIds),"Normal authorized native graph browse cannot read selected current Graph output");
        report.put("physicalGraphRelations",relations);report.put("physicalGraphFullBuildInputs",graphInputs);report.put("physicalGraphEvidence",proofs);report.put("physicalGraphEntities",entities.values());report.put("authorizedNativeGraphBrowse",nativeRows);report.put("actualGraphBrowseQuery",args[5]);
        report.put("authorizedReaderNativeGraphBrowse",new JdbcPublishedGraphReader(jdbc,kb).browse(args[5],2,100,null,null,readerClearance));
        checks.put("graphActualOutputHashPhysicalRevisionProofAndNativeBrowse",true);
      } else {
        var wikiRows=jdbc.queryForList("SELECT * FROM wiki WHERE wiki_id=? AND lifecycle='ACTIVE' ORDER BY wiki_version",entry.id());
        require(wikiRows.size()==1&&entry.stage().equals("WIKI")&&entry.lifecycle().equals("ACTIVE"),"Stable entry has no single normal active Wiki promotion");
        var wiki=wikiRows.get(0);report.put("actualWiki",wiki);
        require(task.get("output_hash").equals(wiki.get("content_hash"))&&COMPLETE_INPUT_POLICY.equals(wiki.get("input_policy_version"))
            &&((Number)wiki.get("input_source_count")).intValue()==entry.versionIds().size(),"Actual Wiki task content hash or complete input policy differs from current page");
        var dependencies=jdbc.queryForList("SELECT d.*,a.acl_version AS current_acl FROM kb_asset_build_dependency d LEFT JOIN kb_source_authority a ON a.version_id=d.source_version_id WHERE d.asset_type='WIKI' AND d.asset_id=? AND d.build_revision=?",entry.id(),wiki.get("wiki_version").toString());
        require(strings(dependencies.stream().map(d->d.get("source_version_id")).toList()).equals(new HashSet<>(entry.versionIds()))
            &&dependencies.stream().allMatch(d->Objects.equals(d.get("acl_version"),d.get("current_acl"))),"Wiki full model dependency/ACL snapshot differs");
        var claims=new JdbcWikiKeywordProjectionRepository(jdbc,tx,snake).loadClaims(entry.id(),((Number)wiki.get("wiki_version")).longValue());
        long allClaims=jdbc.queryForObject("SELECT count(*) FROM wiki_claim WHERE wiki_id=? AND wiki_version=?",Long.class,entry.id(),wiki.get("wiki_version"));
        long temporalClaims=jdbc.queryForObject("SELECT count(*) FROM wiki_claim WHERE wiki_id=? AND wiki_version=? AND temporal_status='PASS'",Long.class,entry.id(),wiki.get("wiki_version"));
        require(!claims.isEmpty()&&claims.size()==allClaims&&claims.size()==temporalClaims
            &&claims.stream().allMatch(c->c.sourceFactsAudited()&&GroundingConstraintAudit.current(c.claimText(),c.supportingText(),c.groundingAudit())),"Actual Wiki claims do not all have current typed source audits");
        var auditedFacts=new HashSet<>(summaries.stream().flatMap(s->s.facts().stream()).map(SummaryFact::id).toList());
        for(var claim:claims) {
          var supported=jdbc.queryForList("SELECT fact_id FROM wiki_claim_fact WHERE claim_id=? ORDER BY fact_id",String.class,claim.claimId());
          require(!supported.isEmpty()&&auditedFacts.containsAll(supported),"Wiki claim has an unbound actual model source fact");
          var expected=new HashSet<String>();
          summaries.forEach(s->s.facts().stream().filter(f->supported.contains(f.id())).forEach(f->expected.addAll(f.evidenceIds())));
          require(new HashSet<>(claim.evidenceIds()).equals(expected),"Wiki claim physical evidence differs from its exact audited supporting facts");
        }
        report.put("actualWikiClaimsNativeAuditReadback",claims);report.put("actualWikiFullBuildDependencies",dependencies);
        try(var bytes=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(wiki.get("object_key").toString()).build())) {
          require(digest(bytes).equals(wiki.get("content_hash")),"Actual Wiki MinIO bytes/content hash differ");
        }
        var asset=jdbc.queryForMap("SELECT a.* FROM kb_asset_current c JOIN kb_derived_asset a USING(asset_id,asset_version) WHERE c.asset_id=?",entry.id());
        require(asset.get("stage").equals("WIKI")&&asset.get("publication_state").equals("PUBLISHED")&&COMPLETE_INPUT_POLICY.equals(asset.get("input_policy_version"))
            &&((Number)asset.get("input_source_count")).intValue()==entry.versionIds().size(),"Actual Wiki asset is not the current complete-policy published physical revision");
        milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
        var vectors=milvus.query(QueryReq.builder().collectionName("knowledge_chunks").filter("id == "+mapper.writeValueAsString(entry.id()+":r"+asset.get("asset_version")))
            .outputFields(List.of("id","dense","content","knowledge_type","version_ids","evidence_ids","is_active")).build()).getQueryResults();
        require(vectors.size()==1,"Current Wiki physical Milvus row missing or duplicated");var physical=vectors.get(0).getEntity();var dense=(List<?>)physical.get("dense");
        var expectedEvidence=new HashSet<String>();claims.forEach(c->expectedEvidence.addAll(c.evidenceIds()));
        require(vector(dense)&&Boolean.TRUE.equals(physical.get("is_active"))&&"WIKI".equals(physical.get("knowledge_type"))
            &&KnowledgeDomain.sha256(physical.get("content").toString()).equals(wiki.get("content_hash"))
            &&strings((List<?>)physical.get("version_ids")).equals(new HashSet<>(entry.versionIds()))
            &&strings((List<?>)physical.get("evidence_ids")).equals(expectedEvidence),"Actual Wiki vector/content/whole source evidence membership differs");
        report.put("actualWikiPhysicalAsset",asset);report.put("actualWikiPhysicalProjection",Map.of("id",physical.get("id"),"dimensions",dense.size(),"vectorSha256",digest(new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(dense))),"content",physical.get("content"),"versionIds",physical.get("version_ids"),"evidenceIds",physical.get("evidence_ids")));
        checks.put("wikiActualOutputHashAuditObjectVectorWholeSourcesAndPromotion",true);
      }
      report.put("status","PASS_ACTUAL_STABLE_DERIVED_PUBLICATION");
      report.put("currentInputPolicy",COMPLETE_INPUT_POLICY);
    } catch(Throwable failure) {failure.printStackTrace();report.put("status","FAIL_ACTUAL_STABLE_DERIVED_PUBLICATION");report.put("failure",failure.toString());}
    finally {if(milvus!=null)milvus.close();if(minio!=null)minio.close();}
    report.put("checks",checks);report.put("boundary","Read-only actual normal selected stable Graph/Wiki task, native current audit/ACL metadata, exact deployed task hashes and actual PG/MinIO/Milvus physical publication. No model, mutation, synthetic resource or independent semantic formal-proof claim. Legacy unrelated outputs remain history, never substitutes.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
}
