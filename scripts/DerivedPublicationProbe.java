import cn.lgs.knowledgebase.application.port.Ports.DerivedContext;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTask;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import cn.lgs.knowledgebase.infrastructure.search.MilvusKnowledgeIndexAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.*;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.vector.request.QueryReq;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual isolated PG/Milvus writes with synthetic numeric vectors; no model success claimed. */
public class DerivedPublicationProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String name,boolean value) { checks.put(name,value); }
  static boolean rejected(Runnable action) {
    try {action.run();return false;} catch(RuntimeException failure) {return true;}
  }
  static Runnable prepare(MilvusKnowledgeIndexAdapter index,String text,DerivedContext context,List<Float> vector) throws Exception {
    return (Runnable)index.getClass().getMethod("prepareConcept",String.class,String.class,List.class,DerivedContext.class,List.class)
        .invoke(index,"stable-entry",text,List.of("evidence-public"),context,vector);
  }
  static Object publication(JdbcGenerationTaskRepository tasks,GenerationTask task,List<String> sources) throws Exception {
    Object gate=tasks.getClass().getMethod("publication",GenerationTask.class).invoke(tasks,task);
    Class.forName("cn.lgs.knowledgebase.application.compilation.CompilationPublication")
        .getMethod("captureInputs",List.class).invoke(gate,sources);
    return gate;
  }
  static void publish(Object gate,Runnable action) {
    try {Class.forName("cn.lgs.knowledgebase.application.compilation.CompilationPublication")
        .getMethod("publish",Runnable.class).invoke(gate,action);}
    catch(java.lang.reflect.InvocationTargetException failure) {throw (RuntimeException)failure.getCause();}
    catch(Exception failure) {throw new IllegalStateException(failure);}
  }
  public static void main(String[] args) throws Exception {
    var report=new LinkedHashMap<String,Object>();
    MilvusClientV2 client=null;
    try {
      String namespace=args[1],mode=args[2];
      var admin=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").rpcDeadlineMs(60000).build());
      admin.createDatabase(CreateDatabaseReq.builder().databaseName(namespace).build());admin.close();
      client=new MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19535").dbName(namespace).rpcDeadlineMs(60000).build());
      var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
      var index=new MilvusKnowledgeIndexAdapter(client,"knowledge_chunks",new JdbcProjectionAuthority(jdbc));
      index.ensureIndex(1024);
      var vector=new ArrayList<Float>(Collections.nCopies(1024,0f));vector.set(0,1f);
      var context=mode.equals("before")
          ? new DerivedContext(List.of("doc-public"),List.of("version-public"),List.of(),List.of(),List.of(),List.of(),List.of())
          : new DerivedContext(List.of("doc-public","doc-other"),List.of("version-public","version-other"),List.of(),List.of(),List.of(),List.of(),List.of());
      var low=new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters(null,null,null,List.of());
      String first="采购合同旧的条目版本",second="采购合同新的条目版本";
      if (mode.equals("before")) {
        index.indexConcept("stable-entry",first,List.of("evidence-public"),context,vector);
        index.indexConcept("stable-entry",second,List.of("evidence-public"),context,vector);
        index.indexConcept("stable-entry",first,List.of("evidence-public"),context,vector);
        var found=index.searchWikiVector(vector,low,10);
        check("latePriorProjectionCannotReplaceNewerPublishedContent",found.size()==1 && second.equals(found.get(0).knowledge()));
        report.put("observedContent",found.stream().map(c->c.knowledge()).toList());
      } else {
        Runnable firstCommit=prepare(index,first,context,vector);
        check("actualIndexedStagingHasNoSearchVisibility",index.searchWikiVector(vector,low,10).isEmpty());
        check("stagedCatalogueCannotProvideDownloadAttribution",new cn.lgs.knowledgebase.infrastructure.persistence.JdbcAssetCatalogue(jdbc,tx).sources("stable-entry",1,low).isEmpty());
        var tasks=new JdbcGenerationTaskRepository(jdbc,tx);
        tasks.queue("version-public",GenerationTaskType.CONCEPT,"entry-component",Instant.now());
        var claim=tasks.claimPending(1,Instant.now()).get(0);
        Object gate=publication(tasks,claim,List.of("version-public","version-other"));
        publish(gate,firstCommit);
        var initial=index.searchWikiVector(vector,low,10);
        check("currentClaimPublishesOneLogicalAsset",initial.size()==1 && initial.get(0).id().equals("stable-entry") && first.equals(initial.get(0).knowledge()));
        Runnable secondCommit=prepare(index,second,context,vector);
        check("newStagingKeepsPreviousPublishedRevisionVisible",first.equals(index.searchWikiVector(vector,low,10).get(0).knowledge()));
        check("databaseFailureAfterPointerSwitchRollsBack",rejected(()->publish(gate,()->{
          secondCommit.run();jdbc.update("INSERT INTO publication_probe(marker) VALUES('rollback')");
          throw new IllegalStateException("synthetic final database failure");}))
          && first.equals(index.searchWikiVector(vector,low,10).get(0).knowledge())
          && jdbc.queryForObject("SELECT COUNT(*) FROM publication_probe",Integer.class)==0);
        publish(gate,secondCommit);
        check("successfulCommitSwitchesOnlyCurrentProjection",index.searchWikiVector(vector,low,10).size()==1
            && second.equals(index.searchWikiVector(vector,low,10).get(0).knowledge()));
        prepare(index,first,context,vector); // Actual late old-row upsert, without publication authority.
        check("latePriorProjectionCannotReplaceNewerPublishedContent",index.searchWikiVector(vector,low,10).size()==1
            && second.equals(index.searchWikiVector(vector,low,10).get(0).knowledge()));
        check("backwardsCurrentRevisionSwitchRejected",rejected(()->publish(gate,firstCommit)));
        var physicalCurrent=client.query(QueryReq.builder().collectionName("knowledge_chunks")
            .filter("id == \"stable-entry:r2\"").outputFields(List.of("*")).build()).getQueryResults().get(0).getEntity();
        var legacyClone=new com.google.gson.Gson().toJsonTree(physicalCurrent).getAsJsonObject();
        legacyClone.addProperty("id","stable-entry");legacyClone.remove("sparse");
        client.upsert(io.milvus.v2.service.vector.request.UpsertReq.builder().collectionName("knowledge_chunks").data(List.of(legacyClone)).build());
        check("legacyAndImmutableRowsDoNotDuplicateLogicalResult",index.searchWikiVector(vector,low,10).size()==1);
        var sourceAuthority=new JdbcProjectionAuthority(jdbc);
        var legacyIndex=new MilvusKnowledgeIndexAdapter(client,"knowledge_chunks",new MilvusKnowledgeIndexAdapter.ProjectionAuthority() {
          public int securityLevel(List<String> ids) {return sourceAuthority.securityLevel(ids);}
          public boolean accessible(List<String> ids,cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters f) {return sourceAuthority.accessible(ids,f);}
        });
        var oldSummary=new cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact("summary-entry","doc-public","version-public",
            "旧的摘要",List.of(new cn.lgs.knowledgebase.domain.compilation.CompilationDomain.Claim("旧的摘要",List.of("evidence-public"),1)),
            cn.lgs.knowledgebase.domain.compilation.CompilationDomain.ArtifactStatus.ACTIVE,Instant.now());
        legacyIndex.indexSummary(oldSummary,context,vector);
        check("legacySummaryRemainsAvailableBeforeUpgrade",index.searchSummaryVector(vector,low,10).size()==1);
        var newSummary=new cn.lgs.knowledgebase.domain.compilation.CompilationDomain.SummaryArtifact("summary-entry","doc-public","version-public",
            "新的摘要",oldSummary.claims(),oldSummary.status(),Instant.now());
        var summaryCommit=index.getClass().getMethod("prepareSummary",newSummary.getClass(),DerivedContext.class,List.class).invoke(index,newSummary,context,vector);
        publish(gate,(Runnable)summaryCommit);
        check("upgradedSummaryHidesLegacyPhysicalRow",index.searchSummaryVector(vector,low,10).size()==1
            && "新的摘要".equals(index.searchSummaryVector(vector,low,10).get(0).knowledge()));
        check("priorPublishedAttributionRemainsAvailable",new cn.lgs.knowledgebase.infrastructure.persistence.JdbcAssetCatalogue(jdbc,tx).sources("stable-entry",1,low).isPresent());
        jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-other'");
        check("uncitedInputAclChangeStillPreventsPublication",rejected(()->publish(gate,()->jdbc.update("INSERT INTO publication_probe(marker) VALUES('uncited')")))
            && jdbc.queryForObject("SELECT COUNT(*) FROM publication_probe",Integer.class)==0);
        check("attemptRetainsFullInputSnapshot",jdbc.queryForObject("SELECT COUNT(*) FROM compile_task_input WHERE task_id=? AND execution_epoch=?",Integer.class,claim.taskId(),claim.executionEpoch())==2);
        var physical=client.query(QueryReq.builder().collectionName("knowledge_chunks").filter("knowledge_type == \"CONCEPT\"")
            .outputFields(List.of("id","content")).limit(10).build());
        check("twoImmutablePhysicalProjectionsAndLegacyRowRetained",physical.getQueryResults().size()==3);
        report.put("physicalRows",physical.getQueryResults().stream().map(r->r.getEntity()).toList());
      }
      report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_REAL_STAGED_DERIVED_PUBLICATION":"FAIL_REAL_DERIVED_PUBLICATION");
    } catch(Throwable failure) { report.put("status","ERROR_REAL_DERIVED_PUBLICATION");report.put("error",failure.toString()); }
    finally {
      report.put("checks",checks);report.put("boundary","Real retained isolated PostgreSQL and Milvus components. Numeric fixture vectors test publication, not embedding quality, user approval, or model output.");
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(args[0]),report);
      if(client!=null)client.close();
    }
    if (!"PASS_REAL_STAGED_DERIVED_PUBLICATION".equals(report.get("status")))System.exit(1);
  }
}
