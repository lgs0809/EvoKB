import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.domain.query.QueryDomain.*;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PG/Milvus component facts, deliberately isolated from genuine model acceptance. */
public class AssetAttributionProbe {
  static JdbcTemplate jdbc;
  static Map<String,Boolean> checks = new LinkedHashMap<>();
  static void check(String name,boolean pass) { checks.put(name,pass); if (!pass) throw new AssertionError(name); }
  static boolean rejected(Runnable action) { try { action.run(); return false; } catch (RuntimeException expected) { return true; } }
  static int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
  static Result result(String id,long revision,String... sources) {
    var evidence = Arrays.stream(sources).map(s -> new Evidence("e-"+s,"doc-"+s,"version-"+s,"block-"+s,"component-only",Map.of(),"component source "+s,"component-only")).toList();
    var candidate = new Candidate(id,"doc-a","version-a",LaneType.WIKI,"Component entry",1,
        evidence.stream().map(Evidence::id).toList(),Map.of("asset_id",id,"asset_version",revision));
    return new Result(candidate,evidence);
  }
  static PackageManifest manifest(String answer,String... sources) {
    var versions=Arrays.stream(sources).map(s -> new PackageVersion("version-"+s,"doc-"+s,"fixture.md","component-only",s+".md",List.of("e-"+s))).toList();
    return new PackageManifest(Instant.now(),versions.stream().flatMap(v -> v.evidenceIds().stream()).toList(),versions,
        List.of(new PackageAnswer(answer,versions.stream().flatMap(v -> v.evidenceIds().stream()).toList())));
  }
  static void create(JdbcPackageAuthorizationRepository auth,JdbcDownloadUsageRepository usage,String id,PackageManifest manifest) {
    auth.save(new PackageAuthorization(id,"usage-u","component-only/"+id,manifest.evidenceIds(),Instant.now().plusSeconds(3600)));
    usage.saveManifest("usage-u",id,manifest,9);
  }
  public static void main(String[] args) throws Exception {
    var report=new LinkedHashMap<String,Object>(); MilvusClientV2 milvus=null;
    try {
      var data=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      jdbc=new JdbcTemplate(data);
      var tx=new TransactionTemplate(new DataSourceTransactionManager(data));var mapper=new ObjectMapper().findAndRegisterModules();
      if(args.length>2 && args[2].equals("restart-replay")) {
        var usage=new JdbcDownloadUsageRepository(jdbc,tx);
        int receipts=count("package_download_receipt"),credits=count("asset_usage_contribution"),expanded=count("query_asset_expansion");
        usage.recordCompletedTransfer("usage-u","asset-package-a",9);
        check("independentJvmRetainsExpansionAndDoesNotDuplicateDownloadAttribution",count("package_download_receipt")==receipts&&count("asset_usage_contribution")==credits&&credits==1&&expanded==3);
        report.put("status","PASS_REAL_PG_MILVUS_ASSET_ATTRIBUTION");
        report.put("checks",checks);new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);return;
      }
      var catalogue=new JdbcAssetCatalogue(jdbc,tx);
      var context=new DerivedContext(List.of("doc-a","doc-b"),List.of("version-a","version-b"),List.of(),List.of(),List.of(),List.of(),List.of());
      long first=((Number)catalogue.register("asset-contract","CONCEPT","合同验收付款条目",List.of("e-a","e-a2"),context).get("asset_version")).longValue();
      check("revisionOneUsesSupportedCatalogueNotAllBuildInputs",first==1 && count("kb_asset_source")==1 && count("kb_asset_dependency")==2);
      check("multipleFragmentsOneMembership",jdbc.queryForObject("SELECT jsonb_array_length(evidence_ids) FROM kb_asset_source",Integer.class)==2);
      var pool=Executors.newFixedThreadPool(8);
      try {
        var jobs=new ArrayList<Callable<Map<String,Object>>>();for(int i=0;i<32;i++)jobs.add(()->catalogue.register("asset-contract","CONCEPT","合同验收付款条目",List.of("e-a2","e-a","e-a"),context));
        var values=pool.invokeAll(jobs);check("concurrentReplaySameImmutableRevision",values.stream().allMatch(v->{try{return ((Number)v.get().get("asset_version")).longValue()==1;}catch(Exception e){throw new RuntimeException(e);}})&&count("kb_derived_asset")==1);
      } finally {pool.shutdownNow();}
      check("catalogueReadIsAuthoritative",catalogue.sources("asset-contract",first,new SearchFilters(null,null,null,List.of())).orElseThrow().size()==1);
      check("unknownEvidenceRejectedAtomically",rejected(()->catalogue.register("asset-unknown","CONCEPT","unknown",List.of("not-an-evidence"),context))&&count("kb_derived_asset")==1);
      check("wrongDocumentIdentityRejected",rejected(()->catalogue.register("asset-wrong","CONCEPT","wrong",List.of("e-a"),new DerivedContext(List.of("doc-b"),List.of("version-a"),List.of(),List.of(),List.of(),List.of(),List.of())))&&count("kb_derived_asset")==1);
      check("proofOutsideInputsRejected",rejected(()->catalogue.register("asset-outside","CONCEPT","outside",List.of("e-private"),context))&&count("kb_derived_asset")==1);
      long second=((Number)catalogue.register("asset-contract","WIKI","合同验收付款条目正文",List.of("e-a","e-b"),context).get("asset_version")).longValue();
      check("conceptToWikiSameIdentityNewRevision",second==2&&count("kb_derived_asset")==2&&jdbc.queryForObject("SELECT count(DISTINCT asset_id) FROM kb_derived_asset",Integer.class)==1);
      check("oldCatalogueNeverRewritten",catalogue.sources("asset-contract",1,new SearchFilters(null,null,null,List.of())).orElseThrow().size()==1&&catalogue.sources("asset-contract",2,new SearchFilters(null,null,null,List.of())).orElseThrow().size()==2);
      catalogue.register("asset-unexpanded","CONCEPT","同文档另一个条目",List.of("e-a"),context);
      var sessions=new JdbcQuerySessionRepository(jdbc,tx,mapper);
      jdbc.update("UPDATE query_session SET status='RUNNING' WHERE session_id='usage-q1'");
      for(String s:List.of("a","b"))sessions.addEvidence(new QueryEvidenceRef("usage-q1","e-"+s,1,"component query"));
      sessions.addSourceVersions("usage-q1",List.of("version-a","version-b"));
      var expanded=result("asset-contract",2,"a","b");sessions.addAssetExpansions("usage-q1",1,List.of(expanded));sessions.addAssetExpansions("usage-q1",2,List.of(expanded));
      check("onlyActualExpansionRecordedOnceWithFirstRound",count("query_asset_expansion")==2&&jdbc.queryForObject("SELECT min(first_seen_round)=1 AND max(first_seen_round)=1 FROM query_asset_expansion",Boolean.class));
      var raw=new Candidate("raw-a","doc-a","version-a",LaneType.RAW,"raw source",1,List.of("e-a"),Map.of());
      sessions.addAssetExpansions("usage-q1",1,List.of(new Result(raw,expanded.evidence().subList(0,1))));
      check("rawResultDoesNotCreateEntryParticipation",count("query_asset_expansion")==2);
      check("uncataloguedResolvedEvidenceRejected",rejected(()->sessions.addAssetExpansions("usage-q1",1,List.of(result("asset-contract",1,"b"))))&&count("query_asset_expansion")==2);
      var legacy=new Candidate("legacy-entry","doc-a","version-a",LaneType.WIKI,"legacy",1,List.of("e-a"),Map.of());
      sessions.addAssetExpansions("usage-q1",1,List.of(new Result(legacy,expanded.evidence().subList(0,1))));
      check("legacyMissingRevisionHasNoInventedParticipation",count("query_asset_expansion")==2);
      var invalid=new Candidate("forged","doc-a","version-a",LaneType.WIKI,"forged",1,List.of("e-a"),Map.of("asset_id","asset-contract","asset_version",2));
      check("forgedAssetIdentityRejected",rejected(()->sessions.addAssetExpansions("usage-q1",1,List.of(new Result(invalid,expanded.evidence().subList(0,1))))));
      check("catalogueAndExpansionHaveNoUsage",count("asset_usage_contribution")==0);
      jdbc.update("UPDATE query_session SET status='ANSWERED' WHERE session_id='usage-q1'");
      check("completedQueryCannotAppendParticipation",rejected(()->sessions.addAssetExpansions("usage-q1",1,List.of(expanded))));
      var auth=new JdbcPackageAuthorizationRepository(jdbc,mapper);var usage=new JdbcDownloadUsageRepository(jdbc,tx);
      create(auth,usage,"asset-package-a",manifest("usage-q1","a"));
      check("packagingDoesNotCreditEntries",count("asset_usage_contribution")==0);
      usage.recordCompletedTransfer("usage-u","asset-package-a",9);
      check("actualPackagedExpandedSourceCreditsOnlyParticipatingEntry",count("asset_usage_contribution")==1&&jdbc.queryForObject("SELECT asset_id FROM asset_usage_contribution",String.class).equals("asset-contract"));
      var transfers=Executors.newFixedThreadPool(8);
      try {
        var jobs=new ArrayList<Callable<Boolean>>();for(int i=0;i<32;i++)jobs.add(()->{usage.recordCompletedTransfer("usage-u","asset-package-a",9);return true;});
        for(var job:transfers.invokeAll(jobs))job.get();
        check("32ConcurrentCompletedTransfersCannotMultiplyEntryHeat",count("asset_usage_contribution")==1&&count("package_download_receipt")==1);
      } finally {transfers.shutdownNow();}
      create(auth,usage,"asset-package-b",manifest("usage-q1","b"));usage.recordCompletedTransfer("usage-u","asset-package-b",9);usage.recordCompletedTransfer("usage-u","asset-package-a",9);
      check("sameAnswerStableEntryDeduplicatedAcrossSourcesAndPackages",count("asset_usage_contribution")==1);
      create(auth,usage,"asset-package-raw-only",manifest("usage-q2","a"));usage.recordCompletedTransfer("usage-u","asset-package-raw-only",9);
      check("rawOnlyAnswerDoesNotBroadcastSourceHeat",count("asset_usage_contribution")==1);
      jdbc.update("UPDATE document_metadata SET security_level=30,acl_version=acl_version+1 WHERE version_id='version-b'");
      check("unmentionedBuildInputRevocationBlocksCatalogue",catalogue.sources("asset-contract",1,new SearchFilters(null,null,null,List.of())).isEmpty());
      create(auth,usage,"asset-revoked-package",manifest("usage-q2","a"));
      check("permissionLevelRestorationDoesNotReviveOldAclRevision",catalogue.sources("asset-contract",2,new SearchFilters(null,null,null,List.of()).withClearance(30)).isEmpty());
      jdbc.update("UPDATE document_metadata SET security_level=10,acl_version=acl_version+1 WHERE version_id='version-b'");
      long third=((Number)catalogue.register("asset-contract","WIKI","合同验收付款条目正文",List.of("e-a","e-b"),context).get("asset_version")).longValue();
      check("currentAclProvenanceCreatesNewRevisionWithoutCopyingHeat",third==3&&count("asset_usage_contribution")==1);
      jdbc.update("UPDATE query_session SET status='RUNNING' WHERE session_id='usage-q3'");
      sessions.addEvidence(new QueryEvidenceRef("usage-q3","e-b",1,"component rollback"));
      sessions.addAssetExpansions("usage-q3",1,List.of(result("asset-contract",3,"b")));
      jdbc.update("UPDATE query_session SET status='INSUFFICIENT' WHERE session_id='usage-q3'");
      create(auth,usage,"asset-package-rollback",manifest("usage-q3","b"));
      int oldReceipts=count("package_download_receipt"),oldSources=count("source_usage_contribution");
      jdbc.execute("CREATE FUNCTION fail_asset_credit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''component credit failure''; END'");
      jdbc.execute("CREATE TRIGGER fail_asset_credit BEFORE INSERT ON asset_usage_contribution FOR EACH ROW EXECUTE FUNCTION fail_asset_credit()");
      check("entryCreditFailureRollsBackReceiptAndSourceCreditTogether",rejected(()->usage.recordCompletedTransfer("usage-u","asset-package-rollback",9))&&count("package_download_receipt")==oldReceipts&&count("source_usage_contribution")==oldSources&&count("asset_usage_contribution")==1);
      jdbc.execute("DROP TRIGGER fail_asset_credit ON asset_usage_contribution");

      // Real Milvus round trip of the production adapter; the fixed vector is explicit component data.
      String database=args[1];var admin=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").build());
      try{admin.createDatabase(CreateDatabaseReq.builder().databaseName(database).build());}finally{admin.close();}
      milvus=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(database).rpcDeadlineMs(60000).build());
      var index=new MilvusKnowledgeIndexAdapter(milvus,"knowledge_chunks",new JdbcProjectionAuthority(jdbc));
      var vector=new ArrayList<Float>(Collections.nCopies(1024,0f));vector.set(0,1f);
      index.indexConcept("asset-milvus","合同验收付款条目来源",List.of("e-a"),context,vector);
      var hits=index.searchWikiBm25("验收付款",new SearchFilters(null,null,null,List.of()),10);
      check("realMilvusPreservesPgRevisionAndSourceRefs",hits.size()==1&&hits.get(0).attributes().get("asset_id").equals("asset-milvus")&&((Number)hits.get(0).attributes().get("asset_version")).longValue()==1&&((List<?>)hits.get(0).attributes().get("sourceRefs")).size()==1);
      jdbc.update("UPDATE document_metadata SET security_level=30,acl_version=acl_version+1 WHERE version_id='version-b'");
      check("realMilvusCannotReturnEntryWhoseUncitedInputWasRevoked",index.searchWikiBm25("验收付款",new SearchFilters(null,null,null,List.of()),10).isEmpty());
      jdbc.update("UPDATE document_metadata SET security_level=10,acl_version=acl_version+1 WHERE version_id='version-b'");
      report.put("status","PASS_REAL_PG_MILVUS_ASSET_ATTRIBUTION");
    } catch(Throwable failure) { failure.printStackTrace();report.put("status","FAIL_REAL_PG_MILVUS_ASSET_ATTRIBUTION");report.put("failure",failure.toString()); }
    finally {if(milvus!=null)milvus.close();}
    report.put("checks",checks);report.put("boundary","Isolated synthetic component questions and explicit fixed vectors. Real PG transactions and real Milvus adapter; not real model QA or user workflow success.");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
