import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.vector.request.QueryReq;
import io.minio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PG/Milvus/MinIO publication inspection, independent of fixture output counts. */
public final class GeneratedPublicationReadbackProbe {
  public static void main(String[] args) throws Exception {
    var report = new LinkedHashMap<String,Object>(); var checks = new LinkedHashMap<String,Boolean>();
    MilvusClientV2 milvus = null;
    try {
      var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
          "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
      var summary = jdbc.queryForMap("SELECT summary_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model FROM summary WHERE summary_id=?",args[1]);
      String version = summary.get("version_id").toString();
      checks.put("summaryActiveGroundedCovered", "ACTIVE".equals(summary.get("lifecycle"))
          && "PASS".equals(summary.get("grounding_status")) && "PASS".equals(summary.get("coverage_status")));
      var concepts = jdbc.queryForList("SELECT concept_id,summary_id,version_id,preferred_label,concept_type_hint FROM concept_candidate WHERE summary_id=? AND lifecycle='ACTIVE' ORDER BY concept_id",args[1]);
      checks.put("actualSupportedConceptsExist", !concepts.isEmpty());
      var assets = jdbc.queryForList("""
          SELECT DISTINCT a.* FROM kb_asset_current c JOIN kb_derived_asset a USING(asset_id,asset_version)
          JOIN kb_asset_source s USING(asset_id,asset_version) WHERE s.source_version_id=? ORDER BY a.asset_id
          """, version);
      checks.put("currentAssetsArePublished", !assets.isEmpty() && assets.stream().allMatch(a -> "PUBLISHED".equals(a.get("publication_state"))));
      var assetIds = assets.stream().map(a->a.get("asset_id").toString()).toList();
      checks.put("allActualConceptsHaveCurrentCatalogue", concepts.stream().allMatch(c->assetIds.contains(c.get("concept_id"))));
      var mapper = new ObjectMapper();
      var physicalIds = assets.stream().map(a->a.get("asset_id")+":r"+a.get("asset_version")).toList();
      milvus = new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      var rows = milvus.query(QueryReq.builder().collectionName("knowledge_chunks")
          .filter("id in "+mapper.writeValueAsString(physicalIds))
          .outputFields(List.of("id","knowledge_type","content","attributes","evidence_ids","dense","version_ids","security_level","is_active")).build()).getQueryResults();
      var vectors = new ArrayList<Map<String,Object>>();
      int dimensions = jdbc.queryForObject("SELECT embedding_dimensions FROM model_configuration_profile WHERE active=TRUE",Integer.class);
      checks.put("oneActualPhysicalProjectionPerCurrentRevision", rows.size()==assets.size()
          && new HashSet<>(rows.stream().map(r->r.getEntity().get("id").toString()).toList()).equals(new HashSet<>(physicalIds)));
      boolean identity=true, finite=true, currentSources=true, groundedMembership=true;
      for(var row: rows){
        var values=row.getEntity(); Object raw=values.get("attributes");
        Map<String,Object> attrs=raw instanceof Map<?,?>? (Map<String,Object>)raw : mapper.readValue(raw.toString(),Map.class);
        String id=attrs.get("asset_id").toString(); long revision=((Number)attrs.get("asset_version")).longValue();
        var asset=assets.stream().filter(a->id.equals(a.get("asset_id"))).findFirst().orElseThrow();
        identity &= revision==((Number)asset.get("asset_version")).longValue()
            && values.get("id").equals(id+":r"+revision) && values.get("knowledge_type").equals(asset.get("stage"))
            && Boolean.TRUE.equals(values.get("is_active"));
        List<?> vector=(List<?>)values.get("dense");
        finite &= vector.size()==dimensions && vector.stream().allMatch(n->n instanceof Number && Double.isFinite(((Number)n).doubleValue()))
            && vector.stream().anyMatch(n->Math.abs(((Number)n).doubleValue())>0.00001);
        var dependencies=jdbc.queryForList("""
            SELECT d.source_version_id,d.acl_version,m.acl_version AS current_acl,a.security_level
            FROM kb_asset_dependency d LEFT JOIN kb_source_authority a ON a.version_id=d.source_version_id
            LEFT JOIN document_metadata m ON m.version_id=d.source_version_id WHERE d.asset_id=? AND d.asset_version=?
            """,id,revision);
        currentSources &= !dependencies.isEmpty() && dependencies.stream().allMatch(d->d.get("security_level")!=null && d.get("acl_version").equals(d.get("current_acl")))
            && new HashSet<>((List<?>)values.get("version_ids")).equals(new HashSet<>(dependencies.stream().map(d->d.get("source_version_id")).toList()));
        int expectedLevel=dependencies.stream().filter(d->d.get("security_level")!=null).mapToInt(d->((Number)d.get("security_level")).intValue()).max().orElse(-1);
        currentSources &= ((Number)values.get("security_level")).intValue()==expectedLevel;
        var membership=jdbc.queryForList("""
            SELECT refs.value AS evidence_id,e.document_id,e.version_id,e.active,s.document_id AS expected_document,s.source_version_id
            FROM kb_asset_source s CROSS JOIN LATERAL jsonb_array_elements_text(s.evidence_ids) refs
            LEFT JOIN evidence e ON e.evidence_id=refs.value WHERE s.asset_id=? AND s.asset_version=?
            """,id,revision);
        groundedMembership &= !membership.isEmpty() && membership.stream().allMatch(m->Boolean.TRUE.equals(m.get("active"))
            && Objects.equals(m.get("document_id"),m.get("expected_document")) && Objects.equals(m.get("version_id"),m.get("source_version_id")))
            && new HashSet<>((List<?>)values.get("evidence_ids")).equals(new HashSet<>(membership.stream().map(m->m.get("evidence_id")).toList()));
        var observed=new LinkedHashMap<String,Object>();observed.put("id",values.get("id"));observed.put("assetId",id);observed.put("revision",revision);
        observed.put("stage",values.get("knowledge_type"));observed.put("vectorDimensions",vector.size());
        observed.put("projectionSha256",hash(values.get("content").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        observed.put("dependencies",dependencies);observed.put("memberships",membership);vectors.add(observed);
      }
      checks.put("projectionIdentityStageAndRevisionMatchCurrentCatalogue",identity);
      checks.put("actualNonzeroFiniteVectorsMatchConfiguredDimensions",finite&&!rows.isEmpty());
      checks.put("allProjectionInputsMatchCurrentAuthorityAndMaximumClassification",currentSources);
      checks.put("allProjectionProofsMatchActualRawEvidenceAndSources",groundedMembership);
      var wikis=jdbc.queryForList("""
          SELECT DISTINCT w.wiki_id,w.wiki_version,w.title,w.object_key,w.content_hash,w.lifecycle FROM wiki w
          JOIN kb_asset_current c ON c.asset_id=w.wiki_id JOIN kb_derived_asset a USING(asset_id,asset_version)
          JOIN kb_asset_source s USING(asset_id,asset_version) WHERE s.source_version_id=? AND w.lifecycle='ACTIVE' AND a.stage='WIKI'
          """,version);
      checks.put("actualCurrentWikiExists",!wikis.isEmpty());
      var minio=MinioClient.builder().endpoint("http://127.0.0.1:19400")
          .credentials(System.getenv("PROBE_MINIO_USER"),System.getenv("PROBE_MINIO_PASSWORD")).build();
      var objects=new ArrayList<Map<String,Object>>(); boolean objectHashes=true, claimsGrounded=true, claimsSupported=true;
      var objectsToRead=new ArrayList<Map<String,Object>>(wikis);objectsToRead.add(summary);
      for(var object:objectsToRead){try(var stream=minio.getObject(GetObjectArgs.builder().bucket("evokb-target").object(object.get("object_key").toString()).build())){
        byte[] bytes=stream.readAllBytes();String actual=hash(bytes);objectHashes &= bytes.length>0 && actual.equals(object.get("content_hash"));
        objects.add(Map.of("objectKey",object.get("object_key"),"sha256",actual,"bytes",bytes.length));
      }}
      for(var wiki:wikis){
        var claims=jdbc.queryForList("SELECT claim_id,grounding_status,temporal_status FROM wiki_claim WHERE wiki_id=? AND wiki_version=?",wiki.get("wiki_id"),wiki.get("wiki_version"));
        claimsGrounded &= !claims.isEmpty()&&claims.stream().allMatch(c->"PASS".equals(c.get("grounding_status")));
        for(var claim:claims){
          var facts=jdbc.queryForList("SELECT f.fact_id,f.grounding_status,s.lifecycle,s.grounding_status AS summary_grounding,s.coverage_status FROM wiki_claim_fact c JOIN summary_fact f USING(fact_id) JOIN summary s USING(summary_id) WHERE c.claim_id=?",claim.get("claim_id"));
          Integer sources=jdbc.queryForObject("SELECT count(*) FROM wiki_evidence we JOIN evidence e USING(evidence_id) JOIN kb_source_authority a USING(version_id) WHERE we.claim_id=? AND e.active=TRUE",Integer.class,claim.get("claim_id"));
          claimsSupported &= !facts.isEmpty() && facts.stream().allMatch(f->"PASS".equals(f.get("grounding_status"))&&"ACTIVE".equals(f.get("lifecycle"))&&"PASS".equals(f.get("summary_grounding"))&&"PASS".equals(f.get("coverage_status")))&&sources!=null&&sources>0;
        }
        wiki.put("actualClaims",claims);
      }
      checks.put("summaryAndWikiObjectBytesMatchStoredHashes",objectHashes);
      checks.put("allActualWikiClaimsIndependentlyGrounded",claimsGrounded);
      checks.put("allActualWikiClaimsHaveGroundedFactsAndCurrentRawEvidence",claimsSupported);
      report.put("summary",summary);report.put("concepts",concepts);report.put("currentAssets",assets);report.put("projections",vectors);report.put("wikis",wikis);report.put("objects",objects);
      report.put("tasks",jdbc.queryForList("SELECT task_id,task_type,status,attempt,execution_epoch,started_at,finished_at,output_hash,error_code FROM compile_task WHERE version_id=? ORDER BY created_at",version));
      report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_ACTUAL_PUBLICATION_READBACK":"FAIL_ACTUAL_PUBLICATION_READBACK");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_ACTUAL_PUBLICATION_READBACK");report.put("failure",failure.toString());}
    finally{if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Read-only actual output inspection; no model calls, generation, publication mutation or fixed fixture output counts. Model execution belongs to the recorded stage start/finish and original deployed artifact, not the artifact used for later readback. Does not certify stable entry promotion, selective scheduling, all source-revocation or all graph scenarios.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
