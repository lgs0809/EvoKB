import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native metadata/input-fence order; synthetic retained Summary, never a model/publication pass. */
public final class CompilationMetadataPostgresProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String name,boolean ok){checks.put(name,ok);if(!ok)throw new AssertionError(name);}
  static final class FirstModelReached extends RuntimeException {}
  @SuppressWarnings("unchecked") static <T>T forbidden(Class<T> type){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new AssertionError("unexpected external call: "+m.getName());});}
  public static void main(String[] args)throws Exception {
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper().findAndRegisterModules();
    try {
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      var objectReads=new AtomicInteger();var modelCalls=new AtomicInteger();
      ObjectStore objects=(ObjectStore)Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),new Class<?>[]{ObjectStore.class},(p,m,a)->{
        if(m.getName().equals("get")){objectReads.incrementAndGet();CompilationExecutionBudget.limit(Duration.ofMinutes(5));}
        throw new AssertionError("Compilation input preparation must not read remote markdown");
      });
      var repo=new JdbcCompilationRepository(jdbc,tx,mapper,objects);
      String text="Synthetic fixture source";
      var fact=new SummaryFact("metadata-fact","metadata-summary","source",text,List.of("block-public"),List.of("evidence-public"),"PASS","explicit synthetic component fixture",
          GroundingConstraintAudit.accepted(text,GroundingConstraintAudit.exactCopyChecks(text,List.of(text))));
      var summary=new SummaryRecord("metadata-summary","metadata-unit","doc-public","version-public","component-only-missing-markdown",KnowledgeDomain.sha256(text),"PASS","PASS","ACTIVE","SYNTHETIC_COMPONENT_NO_MODEL",text,List.of(fact),Instant.now());
      var fixture=repo.newPublication();fixture.captureInputs(List.of("version-public"));
      fixture.publish(()->{repo.replaceDocumentUnits("version-public",List.of(new DocumentUnit("metadata-unit","version-public",1,"fixture","single","test",1,1)));repo.saveSummary(summary);});
      KnowledgeModel model=new KnowledgeModel(){
        public String identity(ModelRole role){return role==ModelRole.LARGE?"synthetic-writer":"synthetic-judge";}
        public String generate(ModelRole r,ModelTask t,String s,String u){throw new UnsupportedOperationException();}
        public <T>T generateStructured(ModelRole r,ModelTask t,String s,String u,Class<T> type){
          CompilationExecutionBudget.limit(Duration.ofSeconds(120));modelCalls.incrementAndGet();throw new FirstModelReached();
        }
      };
      GenerationTask conceptTask=null;CompilationPublication conceptGate=null;
      for(var kind:List.of(GenerationTaskType.CONCEPT,GenerationTaskType.GRAPH,GenerationTaskType.WIKI)){
        var tasks=new JdbcGenerationTaskRepository(jdbc,tx,"metadata-"+kind.name());
        String subject=kind==GenerationTaskType.CONCEPT?new ConceptTaskBinding(summary.id(),1,summary.contentHash(),"").subject():summary.id();
        tasks.queue("version-public",kind,subject,Instant.now());var task=tasks.claimPending(1,Instant.now()).get(0);var gate=tasks.publication(task);
        if(kind==GenerationTaskType.CONCEPT){conceptTask=task;conceptGate=gate;}
        boolean reached=false;
        try(var scope=CompilationExecutionBudget.open(task,gate,Clock.systemUTC())){
          try {
            switch(kind){
              case CONCEPT -> {check("frozenConceptIdentityResolvesFromNativeMetadata",ConceptTaskBinding.verifiedSummaryId(task,repo).equals(summary.id()));new ConceptCompilationService(repo,model).compileSummary(summary.id(),gate);}
              case GRAPH -> new GraphCompilationService(repo,model,forbidden(GraphStore.class)).compileSummary(summary.id(),gate);
              case WIKI -> new WikiCompilationService(repo,model,objects,forbidden(EmbeddingPort.class),forbidden(DerivedSearchIndex.class)).compile(new WikiCompilationService.CompileRequest("CONTRACT","Synthetic metadata fixture","",List.of(summary.id())),gate,ignored->{});
              default -> throw new AssertionError();
            }
          }catch(FirstModelReached expected){reached=true;}
        }
        check(kind+"PinsNativeInputsBeforeItsFirstModelCall",reached&&jdbc.queryForObject("SELECT count(*) FROM compile_task_input WHERE task_id=? AND execution_epoch=?",Integer.class,task.taskId(),task.executionEpoch())==1);
      }
      check("allThreePreparationPathsAvoidRemoteMarkdown",objectReads.get()==0&&modelCalls.get()==3);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
      boolean revoked=false;
      try(var scope=CompilationExecutionBudget.open(conceptTask,conceptGate,Clock.systemUTC())){
        try{new ConceptCompilationService(repo,model).compileSummary(summary.id(),conceptGate);}
        catch(RuntimeException expected){revoked=!(expected instanceof FirstModelReached);}
      }
      check("revokedSourceStillStopsBeforeModelAndRemoteObject",revoked&&modelCalls.get()==3&&objectReads.get()==0);
      check("noSuccessfulTaskOrGeneratedConceptGraphWikiIsManufactured",
          jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE status='SUCCEEDED'",Integer.class)==0
          &&jdbc.queryForObject("SELECT count(*) FROM concept_candidate",Integer.class)==0
          &&jdbc.queryForObject("SELECT count(*) FROM wiki",Integer.class)==0);
      report.put("status","PASS_REAL_POSTGRES_METADATA_BEFORE_REMOTE_IO");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_METADATA_BEFORE_REMOTE_IO");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);report.put("boundary","Real PostgreSQL repository, normal task claims/input fences and explicit synthetic Summary fixture. The test stops at the first model boundary; no model/MinIO/Milvus call, successful task, or new generated artifact. Revocation is only in the retained isolated component schema.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
