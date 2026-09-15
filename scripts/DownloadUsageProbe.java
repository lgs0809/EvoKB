import cn.lgs.knowledgebase.application.access.KnowledgeAccessService;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.usage.SourcePackageUsageService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.LaneType;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual PostgreSQL component fixtures, never writes successful user workflow states in public. */
public class DownloadUsageProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static JdbcTemplate jdbc; static JdbcDownloadUsageRepository repo;
  static JdbcPackageAuthorizationRepository auth;
  static void check(String name,boolean pass){checks.put(name,pass);if(!pass)throw new AssertionError(name);}
  static int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
  static boolean rejected(Runnable action){try{action.run();return false;}catch(KnowledgeAccessService.Denied|IllegalArgumentException expected){return true;}}
  static PackageVersion source(String suffix,List<String> evidence){return new PackageVersion("version-"+suffix,"doc-"+suffix,suffix+".md","component-only",suffix+".md",evidence);}
  static PackageManifest manifest(List<PackageVersion> sources,List<PackageAnswer> answers){return new PackageManifest(Instant.now(),sources.stream().flatMap(s->s.evidenceIds().stream()).distinct().toList(),sources,answers);}
  static void authorize(String id,PackageManifest m){auth.save(new PackageAuthorization(id,"usage-u","component-only/"+id,m.evidenceIds(),Instant.now().plusSeconds(900)));}
  static void create(String id,PackageManifest m){authorize(id,m);repo.saveManifest("usage-u",id,m,9);}

  public static void main(String[] args)throws Exception{
    var mapper=new ObjectMapper().findAndRegisterModules();var report=new LinkedHashMap<String,Object>();
    try {
      var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
      repo=new JdbcDownloadUsageRepository(jdbc,tx);auth=new JdbcPackageAuthorizationRepository(jdbc,mapper);
      if(args.length>1&&args[1].equals("replay-new-jvm")){
        int before=count("source_usage_contribution"),receipts=count("package_download_receipt");
        repo.recordCompletedTransfer("usage-u","usage-first",9);
        check("newJvmReplayDoesNotDuplicateCommittedUsage",before==count("source_usage_contribution")&&receipts==count("package_download_receipt")&&before>0);
      }else{
        var sessions=new JdbcQuerySessionRepository(jdbc,tx,mapper);var knowledge=new JdbcKnowledgeRepository(jdbc,tx,mapper);
        var evidence=new EvidenceResolver(){public List<Evidence> resolve(List<String> ids){return knowledge.resolveEvidence(ids);}public List<Evidence> resolve(List<String> ids,SearchFilters filters){return knowledge.resolveEvidence(ids,filters);}};
        var usage=new SourcePackageUsageService(sessions,new KnowledgeAccessService(new JdbcIdentityRepository(jdbc),evidence,sessions,new JdbcProjectionAuthority(jdbc)),repo);
        var both=List.of(source("a",List.of("e-a","e-a2")),source("b",List.of("e-b")));
        var q1=new PackageAnswer("usage-q1",List.of("e-a","e-a2","e-b"));
        usage.validate("usage-u",List.of(q1));
        check("ownedCompletedAnswerAcceptsItsActualCitations",true);
        check("foreignAnswerCannotReceiveAttribution",rejected(()->usage.validate("usage-u",List.of(new PackageAnswer("usage-foreign",List.of("e-a"))))));
        check("runningQueryCannotGenerateUsage",rejected(()->usage.validate("usage-u",List.of(new PackageAnswer("usage-running",List.of("e-a"))))));
        check("uncitedRetrievedSourceCannotBeClaimed",rejected(()->usage.validate("usage-u",List.of(new PackageAnswer("usage-q2",List.of("e-b"))))));
        check("uncitedPrivateModelInputBlocksAnswerPackage",rejected(()->usage.validate("usage-u",List.of(new PackageAnswer("usage-private",List.of("e-a"))))));
        var search=new JdbcKnowledgeGenerationRecommendationRepository(jdbc,tx,mapper);
        for(int i=0;i<6;i++)search.recordDeliveredSearch(new DeliveredSearch("usage-search-"+i,"component search",new SearchFilters(null,null,null,List.of()),List.of(new DeliveredResult(1,LaneType.RAW,"e-a",1,List.of("e-a"),List.of("version-a")))));
        check("searchRecordsNeverCreateDownloadHeatOrRecommendations",count("search_result")==6&&count("effective_version_hit")==0&&count("distill_recommendation")==0&&count("source_usage_contribution")==0&&jdbc.queryForObject("SELECT count(*) FROM search_result WHERE effective",Integer.class)==0);
        var full=manifest(both,List.of(q1));create("usage-first",full);
        check("creatingPackageManifestDoesNotCountUsage",count("source_usage_contribution")==0&&count("package_download_receipt")==0);
        check("multipleEvidenceFragmentsCollapseIntoSourceVersionManifest",jdbc.queryForObject("SELECT count(*) FROM package_manifest_source WHERE package_id='usage-first'",Integer.class)==2);
        check("shortTransferRejectedWithoutReceipt",rejected(()->repo.recordCompletedTransfer("usage-u","usage-first",8))&&count("package_download_receipt")==0);
        check("foreignDownloadRejectedWithoutReceipt",rejected(()->repo.recordCompletedTransfer("usage-other","usage-first",9))&&count("package_download_receipt")==0);
        var pool=Executors.newFixedThreadPool(8);try{var futures=new ArrayList<Future<?>>();for(int i=0;i<32;i++)futures.add(pool.submit(()->repo.recordCompletedTransfer("usage-u","usage-first",9)));for(var future:futures)future.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        check("32ConcurrentCompletionsCreateOneReceiptAndTwoSourceContributions",count("package_download_receipt")==1&&count("source_usage_contribution")==2);
        create("usage-regenerated",full);repo.recordCompletedTransfer("usage-u","usage-regenerated",9);
        check("newPackageForSameAnswerDoesNotDuplicateSourceUsage",count("package_download_receipt")==2&&count("source_usage_contribution")==2);
        var q2=new PackageAnswer("usage-q2",List.of("e-a"));var partial=manifest(List.of(source("a",List.of("e-a"))),List.of(q2));create("usage-partial",partial);repo.recordCompletedTransfer("usage-u","usage-partial",9);
        check("newAnswerAddsOnlySourcesActuallyPackaged",count("source_usage_contribution")==3&&jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE session_id='usage-q2' AND version_id='version-b'",Integer.class)==0);
        var grouped=manifest(both,List.of(q1,new PackageAnswer("usage-q3",List.of("e-b"))));create("usage-group",grouped);repo.recordCompletedTransfer("usage-u","usage-group",9);
        check("groupedDownloadAttributesSourcesToEachActualAnswer",count("source_usage_contribution")==4&&jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE session_id='usage-q3' AND version_id='version-a'",Integer.class)==0);
        var unbound=manifest(both,List.of());create("usage-unbound",unbound);repo.recordCompletedTransfer("usage-u","usage-unbound",9);
        check("genericSearchPackageDownloadCannotInventAnswerUsage",count("source_usage_contribution")==4);
        authorize("usage-legacy",full);repo.recordCompletedTransfer("usage-u","usage-legacy",9);
        check("oldPackageWithoutVerifiedManifestDoesNotInventHistoricalReceipts",jdbc.queryForObject("SELECT count(*) FROM package_download_receipt WHERE package_id='usage-legacy'",Integer.class)==0);
        create("usage-revoked",full);int receipts=count("package_download_receipt");jdbc.update("UPDATE document_metadata SET security_level=30,acl_version=acl_version+1 WHERE version_id='version-a'");
        check("revocationAfterPackageCreationPreventsCompletionCredit",rejected(()->repo.recordCompletedTransfer("usage-u","usage-revoked",9))&&count("package_download_receipt")==receipts);
        jdbc.update("UPDATE document_metadata SET security_level=10,acl_version=acl_version+1 WHERE version_id='version-a'");
        create("usage-expired",full);jdbc.update("UPDATE source_package_access SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE package_id='usage-expired'");
        check("expiredPackageCannotProduceReceipt",rejected(()->repo.recordCompletedTransfer("usage-u","usage-expired",9))&&count("package_download_receipt")==receipts);
        create("usage-deleted",full);auth.markDeleted("component-only/usage-deleted");
        check("deletedPackageCannotProduceReceipt",rejected(()->repo.recordCompletedTransfer("usage-u","usage-deleted",9))&&count("package_download_receipt")==receipts);
        var invalid=manifest(List.of(source("a",List.of("e-a"))),List.of(new PackageAnswer("usage-q2",List.of("e-b"))));authorize("usage-invalid",invalid);
        check("invalidAttributionRollsBackWholeManifest",rejected(()->repo.saveManifest("usage-u","usage-invalid",invalid,9))&&jdbc.queryForObject("SELECT count(*) FROM package_usage_manifest WHERE package_id='usage-invalid'",Integer.class)==0);
        create("usage-rollback",full);try{tx.executeWithoutResult(s->{repo.recordCompletedTransfer("usage-u","usage-rollback",9);throw new IllegalStateException("component rollback");});}catch(IllegalStateException expected){}
        check("transactionRollbackLeavesNeitherReceiptNorContribution",jdbc.queryForObject("SELECT count(*) FROM package_download_receipt WHERE package_id='usage-rollback'",Integer.class)==0&&count("source_usage_contribution")==4);
        jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('version-a-new','doc-a',2,'ACTIVE','new.md',1,'new-component','component-only')");jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES('version-a-new',10)");
        check("historicalUsageIsNotCopiedToNewSourceVersion",jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE version_id='version-a-new'",Integer.class)==0&&count("source_usage_contribution")==4);
        jdbc.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES('block-a-new','version-a-new',1,'PARAGRAPH','New component version','new-component')");
        jdbc.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES('e-a-new','doc-a','version-a-new','block-a-new','component-only','{}','new-component')");
        for(String q:List.of("usage-multi-q","usage-old-first-q"))jdbc.update("INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids) VALUES(?,'usage-u','component-only','Multiple version component','{}','ANSWERED','[]','[\"e-a\",\"e-a-new\"]')",q);
        var newSource=new PackageVersion("version-a-new","doc-a","new.md","component-only","new.md",List.of("e-a-new"));
        var dual=manifest(List.of(source("a",List.of("e-a")),newSource),List.of(new PackageAnswer("usage-multi-q",List.of("e-a","e-a-new"))));
        create("usage-multi-version",dual);repo.recordCompletedTransfer("usage-u","usage-multi-version",9);
        check("twoVersionsOfOneDocumentInAnswerCountOnlyOnce",count("source_usage_contribution")==5&&jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE session_id='usage-multi-q'",Integer.class)==1);
        check("firstCreditStoresHighestVersionActuallyIncludedInPackage",jdbc.queryForObject("SELECT version_id FROM source_usage_contribution WHERE session_id='usage-multi-q'",String.class).equals("version-a-new"));
        var oldOnly=manifest(List.of(source("a",List.of("e-a"))),List.of(new PackageAnswer("usage-old-first-q",List.of("e-a"))));create("usage-old-first",oldOnly);repo.recordCompletedTransfer("usage-u","usage-old-first",9);
        var newer=manifest(dual.versions(),List.of(new PackageAnswer("usage-old-first-q",List.of("e-a","e-a-new"))));create("usage-later-new",newer);repo.recordCompletedTransfer("usage-u","usage-later-new",9);
        check("laterNewVersionDownloadCannotMoveOldHeatForSameAnswer",count("source_usage_contribution")==6&&jdbc.queryForObject("SELECT version_id FROM source_usage_contribution WHERE session_id='usage-old-first-q'",String.class).equals("version-a"));
      }
      report.put("status","PASS_REAL_POSTGRES_COMPLETED_DOWNLOAD_USAGE");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_COMPLETED_DOWNLOAD_USAGE");report.put("failure",failure.toString());}
    report.put("checks",checks);report.put("boundary","Real production repositories in a retained isolated PostgreSQL schema. Component query states are synthetic fixtures, not real model success. Source contribution only; entry expansion provenance, selective compilation and actual browser transfer are separate acceptance work.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
