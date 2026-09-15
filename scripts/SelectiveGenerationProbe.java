import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual PostgreSQL/callback/quota concurrency component, with explicitly synthetic payloads. */
public final class SelectiveGenerationProbe {
  final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final JdbcCompilationRepository compilation;
  final JdbcKnowledgeEntryCatalogue entries;
  final JdbcGenerationTaskRepository tasks;
  final JdbcAssetCatalogue assets;
  final Map<String,Boolean> checks=new LinkedHashMap<>();
  SelectiveGenerationProbe() {
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    compilation=new JdbcCompilationRepository(jdbc,tx,mapper,null);
    entries=new JdbcKnowledgeEntryCatalogue(jdbc,tx,mapper,"quota-component");
    tasks=new JdbcGenerationTaskRepository(jdbc,tx,"quota-component");
    assets=new JdbcAssetCatalogue(jdbc,tx);
  }
  void check(String name,boolean value){checks.put(name,value);if(!value)throw new AssertionError(name);}
  void raw(String suffix) {
    String text="甲公司确认合同 HT-"+suffix+" 的付款期限为30日。";
    jdbc.update("INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')","d-"+suffix,"Synthetic quota "+suffix);
    jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,1,'ACTIVE','component.md',9,'component-only','component-only')","v-"+suffix,"d-"+suffix);
    jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES(?,10)","v-"+suffix);
    jdbc.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?,?,1,'PARAGRAPH',?,'component-only')","b-"+suffix,"v-"+suffix,text);
    jdbc.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES(?,?,?,?,'component-only','{}','component-only')","e-"+suffix,"d-"+suffix,"v-"+suffix,"b-"+suffix);
  }
  String entry(String suffix) {
    var unit=new DocumentUnit("u-"+suffix,"v-"+suffix,1,"Synthetic quota contract","single","CONTRACT",1,1);
    compilation.replaceDocumentUnits("v-"+suffix,List.of(unit));
    String text=jdbc.queryForObject("SELECT raw_content FROM raw_block WHERE block_id=?",String.class,"b-"+suffix);
    var fact=new SummaryFact("f-"+suffix,"s-"+suffix,"Synthetic",text,List.of("b-"+suffix),List.of("e-"+suffix),"PASS","synthetic component fixture");
    var summary=new SummaryRecord("s-"+suffix,unit.id(),"d-"+suffix,"v-"+suffix,"component-only","component-only","PASS","PASS","ACTIVE","SYNTHETIC_COMPONENT_NOT_MODEL",text,List.of(fact),Instant.now());
    compilation.saveSummary(summary);
    var concept=new ConceptRecord("c-"+suffix,summary.id(),summary.versionId(),"合成合同 "+suffix,List.of(),"CONTRACT",text,List.of(fact.id()),"ACTIVE","SYNTHETIC_COMPONENT_NOT_MODEL",Instant.now());
    var scope=GraphScopeIdentity.scope(summary.documentId(),List.of(new GraphScopeIdentity.Anchor("CONTRACT","HT-"+suffix,List.of(fact.id()))),Map.of(fact.id(),fact));
    var plan=entries.prepare(concept,scope,"",List.of());
    tasks.queue(summary.versionId(),GenerationTaskType.CONCEPT,summary.id(),Instant.now());
    var task=tasks.claimPending(1,Instant.now()).get(0);var gate=tasks.publication(task);gate.captureInputs(plan.entry().versionIds());
    gate.publish(()->{compilation.replaceConceptsForSummary(summary.id(),List.of(concept));entries.publish(plan);assets.register(plan.entry().id(),"CONCEPT",text,List.of("e-"+suffix),compilation.derivedContextForSummaries(plan.entry().inputSummaryIds()));});
    tasks.complete(task,"component-only",Instant.now());return plan.entry().id();
  }
  void usage(String suffix,int index,String asset) {
    String session="q-"+suffix+"-"+index, evidence="e-"+suffix, pack="p-"+session;
    jdbc.update("INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids) VALUES(?,'quota-user','Synthetic quota','Component usage','{}','ANSWERED','[]',jsonb_build_array(?::text))",session,evidence);
    new JdbcPackageAuthorizationRepository(jdbc,mapper).save(new PackageAuthorization(pack,"quota-user","component-only/"+pack,List.of(evidence),Instant.now().plusSeconds(3600)));
    var usage=new JdbcDownloadUsageRepository(jdbc,tx);
    usage.saveManifest("quota-user",pack,new PackageManifest(Instant.now(),List.of(evidence),List.of(new PackageVersion("v-"+suffix,"d-"+suffix,"component.md","component-only","component.md",List.of(evidence))),List.of(new PackageAnswer(session,List.of(evidence)))),9);
    if(asset!=null){long revision=jdbc.queryForObject("SELECT asset_version FROM kb_asset_current WHERE asset_id=?",Long.class,asset);jdbc.update("INSERT INTO query_asset_expansion(session_id,asset_id,asset_version,source_version_id,evidence_id,first_seen_round) VALUES(?,?,?,?,?,1)",session,asset,revision,"v-"+suffix,evidence);}
    usage.recordCompletedTransfer("quota-user",pack,9);usage.recordCompletedTransfer("quota-user",pack,9);
  }
  void run(String output)throws Exception {
    var report=new LinkedHashMap<String,Object>();
    try {
      for(int i=0;i<25;i++){String s="hot"+i;raw(s);for(int j=0;j<3;j++)usage(s,j,null);}
      var ids=new ArrayList<String>();
      for(int i=0;i<12;i++){String s="entry"+i;raw(s);String id=entry(s);ids.add(id);for(int j=0;j<(i==0?4:5);j++)usage(s,j,id);}
      for(int j=4;j<8;j++)usage("entry0",j,null);
      var scheduler=new SelectiveGenerationScheduler(jdbc,tx,tasks,"quota-component");scheduler.refreshCandidates();
      check("twentyFiveSourcesQualifyAtThreeNativeCompletionContributions",jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE stage='SUMMARY' AND contributions=3",Integer.class)==25);
      check("elevenEntriesQualifyAtFiveOwnAssetContributions",jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE stage='WIKI'",Integer.class)==11);
      check("sourceHeatDoesNotTurnFourEntryUsesIntoFive",jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE stage='WIKI' AND target_id=?",Integer.class,ids.get(0))==0
          && jdbc.queryForObject("SELECT count(*) FROM asset_usage_contribution WHERE asset_id=?",Integer.class,ids.get(0))==4);
      check("graphQualifiesIndependentlyBeforeWikiPromotion",jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE stage='GRAPH'",Integer.class)==12);
      var pool=Executors.newFixedThreadPool(2);int total;
      try{var runs=pool.invokeAll(List.<Callable<Integer>>of(scheduler::selectDaily,()->new SelectiveGenerationScheduler(jdbc,tx,tasks,"quota-component").selectDaily()));total=runs.get(0).get()+runs.get(1).get();}finally{pool.shutdownNow();}
      check("concurrentSameDaySelectorsCommitExactlyFortyNewJobs",total==40 && jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection",Integer.class)==40);
      check("summaryDailyBudgetIsExactlyTwenty",jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='SUMMARY'",Integer.class)==20);
      check("wikiDailyBudgetIsExactlyTen",jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='WIKI'",Integer.class)==10);
      check("graphDailyBudgetIsExactlyTen",jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='GRAPH'",Integer.class)==10);
      check("higherSourceHeatRanksGraphWithoutPromotingWiki",jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='GRAPH' AND target_id=?",Integer.class,ids.get(0))==1
          && jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage='WIKI' AND target_id=?",Integer.class,ids.get(0))==0);
      check("newInstanceAndSameDayRetryCannotSpendQuotaAgain",new SelectiveGenerationScheduler(jdbc,tx,tasks,"quota-component").selectDaily()==0);
      check("overflowRemainsQueuedForNextBusinessDay",jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE selected_task_id IS NULL AND stage='SUMMARY'",Integer.class)==5
          && jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE selected_task_id IS NULL AND stage='WIKI'",Integer.class)==1
          && jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE selected_task_id IS NULL AND stage='GRAPH'",Integer.class)==2);
      check("businessDayComesFromShanghaiDatabaseClock",jdbc.queryForObject("SELECT bool_and(business_day=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::date) FROM kb_generation_daily_selection",Boolean.class));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='v-entry0'");scheduler.invalidateChangedInputs();
      check("repairBeyondFullDailyBudgetGetsPriorityOneHundred",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE version_id='v-entry0' AND task_type='SUMMARY' AND priority=100 AND subject_id='repair/acl/2'",Integer.class)==1
          && jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection",Integer.class)==40);
      var next=tasks.claimPending(1,Instant.now()).get(0);
      check("repairClaimRunsBeforeNewDailyTasks",next.versionId().equals("v-entry0") && next.taskType()==GenerationTaskType.SUMMARY);
      check("receiptReplayNeverDoublesSourceOrAssetUsage",jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution",Integer.class)==138
          && jdbc.queryForObject("SELECT count(*) FROM asset_usage_contribution",Integer.class)==59);
      report.put("status","PASS_REAL_SELECTIVE_GENERATION_COMPONENT");
    }catch(Throwable error){error.printStackTrace();report.put("status","FAIL_REAL_SELECTIVE_GENERATION_COMPONENT");report.put("error",error.toString());}
    report.put("checks",checks);
    report.put("boundary","Retained isolated PostgreSQL with actual production repositories, completed-transfer callback deduplication, owner fences and concurrent scheduler transactions. Source/entry/model/answer/transfer payloads are synthetic component fixtures. No genuine browser download, model inference, midnight wall-clock wait or all-project acceptance claimed.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  public static void main(String[] args)throws Exception{new SelectiveGenerationProbe().run(args[0]);}
}
