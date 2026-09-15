import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.*;
import cn.lgs.knowledgebase.infrastructure.compilation.DurableCompilationWorker;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Native PG handoff failure and same-task checkpoint resume; model/index/object fixtures explicit. */
public final class SummaryHandoffPostgresProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String key,boolean ok){checks.put(key,ok);if(!ok)throw new AssertionError(key);}
  @SuppressWarnings("unchecked") static <T>T forbidden(Class<T> type){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new AssertionError("unexpected unused dependency: "+m.getName());});}
  public static void main(String[] args)throws Exception{
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper().findAndRegisterModules();
    try{
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
      var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      // A normal UUID source identity reproduces the real 277-character repair Concept key.
      String version="11111111-1111-1111-1111-111111111111";
      jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,'doc-public',2,'ACTIVE','component-fixture.md',0,'component-handoff-fixture','component-only')",version);
      jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES(?,10)",version);
      jdbc.update("UPDATE raw_block SET version_id=? WHERE version_id='version-public'",version);
      jdbc.update("UPDATE evidence SET version_id=? WHERE version_id='version-public'",version);
      var writes=new AtomicInteger();var embeddings=new AtomicInteger();var indexes=new AtomicInteger();var calls=new EnumMap<ModelTask,Integer>(ModelTask.class);
      var bytes=new HashMap<String,byte[]>();
      ObjectStore objects=new ObjectStore(){
        public void put(String key,InputStream in,long size,String type){try{bytes.put(key,in.readAllBytes());writes.incrementAndGet();}catch(IOException e){throw new UncheckedIOException(e);}}
        public StoredObject get(String key){byte[] value=Objects.requireNonNull(bytes.get(key));return new StoredObject(new ByteArrayInputStream(value),value.length,"text/markdown");}
        public ObjectInfo stat(String key){byte[] value=Objects.requireNonNull(bytes.get(key));return new ObjectInfo(key,value.length,"text/markdown","");}
        public String presignGet(String key,Duration expiry){throw new AssertionError();}public void delete(String key){throw new AssertionError();}
      };
      EmbeddingPort embedding=new EmbeddingPort(){public int dimensions(){return 3;}public List<List<Float>> embed(List<String> texts){embeddings.incrementAndGet();return texts.stream().map(t->List.of(1f,0f,0f)).toList();}};
      DerivedSearchIndex derived=(DerivedSearchIndex)Proxy.newProxyInstance(DerivedSearchIndex.class.getClassLoader(),new Class<?>[]{DerivedSearchIndex.class},(p,m,a)->{
        if(m.getName().equals("prepareSummary"))return (Runnable)()->indexes.incrementAndGet();throw new AssertionError(m.getName());
      });
      KnowledgeModel model=new KnowledgeModel(){
        public String identity(ModelRole role){return role==ModelRole.LARGE?"SYNTHETIC_COMPONENT_WRITER":"SYNTHETIC_COMPONENT_JUDGE";}
        public String generate(ModelRole r,ModelTask t,String s,String u){throw new AssertionError();}
        public <T>T generateStructured(ModelRole r,ModelTask task,String s,String u,Class<T> type){
          calls.merge(task,1,Integer::sum);Object result;
          if(type==Classification.class)result=new Classification("General / Mixed","single",List.of(new UnitProposal("fixture","General / Mixed",1,1)));
          else if(type==FactProposalSet.class)result=new FactProposalSet(List.of(new FactProposal("source","Synthetic fixture source",List.of("block-public"))));
          else if(type==CoverageDecision.class)result=new CoverageDecision(List.of());
          else if(type==GroundingJudgeProtocol.Batch.class||type==GroundingJudgeProtocol.Decision.class){
            var constraints=Arrays.stream(GroundingConstraintAudit.Dimension.values()).map(d->new GroundingJudgeProtocol.Constraint(d,GroundingConstraintAudit.Finding.SUPPORTED,List.of("Synthetic fixture source"),"Explicit orchestration fixture, no semantic-model pass")).toList();
            result=type==GroundingJudgeProtocol.Decision.class?new GroundingJudgeProtocol.Decision(true,"component fixture",constraints):new GroundingJudgeProtocol.Batch(List.of(new GroundingJudgeProtocol.Item("C1",true,"component fixture",constraints)));
          }else throw new AssertionError(type.getName());return type.cast(result);
        }
      };
      var repo=new JdbcCompilationRepository(jdbc,tx,mapper,objects);var tasks=new JdbcGenerationTaskRepository(jdbc,tx,"enterprise");
      var graph=forbidden(GraphStore.class);
      var worker=new DurableCompilationWorker(tasks,repo,new SummaryCompilationService(repo,model,objects,embedding,derived),new ConceptCompilationService(repo,model),new GraphCompilationService(repo,model,graph),new WikiCompilationService(repo,model,objects,embedding,derived),new WikiKeywordProjectionService(forbidden(WikiKeywordProjectionService.Repository.class),graph),Duration.ofSeconds(1),1,forbidden(KnowledgeEntryCatalogue.class));
      jdbc.execute("CREATE TABLE fixture_handoff_fault(enabled BOOLEAN NOT NULL); INSERT INTO fixture_handoff_fault VALUES(true)");
      jdbc.execute("CREATE FUNCTION fixture_fail_concept() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_type='CONCEPT' AND EXISTS(SELECT 1 FROM fixture_handoff_fault WHERE enabled) THEN RAISE EXCEPTION 'Synthetic downstream insert fault' USING ERRCODE='22001'; END IF; RETURN NEW; END $$");
      jdbc.execute("CREATE TRIGGER fixture_fail_concept BEFORE INSERT ON compile_task FOR EACH ROW EXECUTE FUNCTION fixture_fail_concept()");
      var task=tasks.queueRepair(version,GenerationTaskType.SUMMARY,"",Instant.now());
      check("firstNormalWorkerCycleClaimsSummary",worker.runOneCycle()==1);
      var first=jdbc.queryForMap("SELECT status,attempt,execution_epoch,error_code FROM compile_task WHERE task_id=?",task.taskId());
      report.put("firstTask",first);
      check("injectedHandoffFailureIsRetainedOnOriginalTask",first.get("status").equals("FAILED")&&((Number)first.get("attempt")).intValue()==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=?",Integer.class,task.taskId())==1);
      var before=jdbc.queryForMap("SELECT summary_id,content_hash,object_key FROM summary WHERE version_id=? AND lifecycle='ACTIVE'",version);
      check("auditedCheckpointPublishedBeforeDownstreamInsertFailure",jdbc.queryForObject("SELECT count(*) FROM summary_fact WHERE summary_id=? AND grounding_status='PASS'",Integer.class,before.get("summary_id"))==1&&writes.get()==1&&embeddings.get()==1&&indexes.get()==1);
      check("failedHandoffDidNotCreateDownstreamTask",jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='CONCEPT'",Integer.class)==0);
      var firstCalls=new EnumMap<ModelTask,Integer>(calls);
      // Only remove the injected dependency fault. Business state changes use the normal repository.
      jdbc.update("UPDATE fixture_handoff_fault SET enabled=false");tasks.retry(task.taskId(),Instant.now());
      var due=jdbc.queryForObject("SELECT next_attempt_at FROM compile_task WHERE task_id=?",java.sql.Timestamp.class,task.taskId()).toInstant();
      check("manualRecoveryAlsoRespectsItsPersistedBackoff",worker.runOneCycle()==0);
      Thread.sleep(Math.max(0,Duration.between(Instant.now(),due).toMillis())+80);

      check("retryRunsSameTaskWithItsSecondAttempt",worker.runOneCycle()==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='SUCCEEDED' AND attempt=2 AND execution_epoch=2",Integer.class,task.taskId())==1);
      var after=jdbc.queryForMap("SELECT summary_id,content_hash,object_key FROM summary WHERE version_id=? AND lifecycle='ACTIVE'",version);
      check("resumeRetainsExactCheckpointAndAvoidsDuplicateExternalPublication",before.equals(after)&&jdbc.queryForObject("SELECT count(*) FROM summary WHERE version_id=?",Integer.class,version)==1&&writes.get()==1&&embeddings.get()==1&&indexes.get()==1);
      check("resumeOnlyReclassifiesWithoutReextractingRejudgingOrEmbedding",calls.entrySet().stream().allMatch(e->e.getValue().equals(firstCalls.get(e.getKey())+(e.getKey()==ModelTask.CLASSIFY_UNIT?1:0))));
      var downstream=jdbc.queryForMap("SELECT idempotency_key,subject_id,status,attempt FROM compile_task WHERE task_type='CONCEPT'");
      String subject=downstream.get("subject_id").toString(),key=downstream.get("idempotency_key").toString();
      check("realLongRepairBindingEnqueuesOnceWithBoundedCanonicalKey",subject.contains("/repair/")&&key.length()<=255&&key.startsWith("generation-key-sha256:")&&key.equals(CompilationContracts.generationTaskKey("enterprise",version,GenerationTaskType.CONCEPT,subject))&&downstream.get("status").equals("PENDING")&&((Number)downstream.get("attempt")).intValue()==0);
      check("oldFailureHistoryAndBothEpochInputSnapshotsSurvive",jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=?",Integer.class,task.taskId())==1&&jdbc.queryForObject("SELECT count(DISTINCT execution_epoch) FROM compile_task_input WHERE task_id=?",Integer.class,task.taskId())==2);
      var retained=repo.findSummaryForAudit(before.get("summary_id").toString()).orElseThrow();
      repo.saveSummary(new SummaryRecord(retained.id(),retained.unitId(),retained.documentId(),retained.versionId(),retained.objectKey(),retained.contentHash(),retained.groundingStatus(),retained.coverageStatus(),"STALE",retained.model(),retained.markdown(),retained.facts(),retained.createdAt()));
      tasks.queueRepair(version,GenerationTaskType.SUMMARY,"",Instant.now());
      check("normalRegenerationOfStaleCheckpointRunsAnotherOwnedAttempt",worker.runOneCycle()==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_id=? AND status='SUCCEEDED' AND attempt=3",Integer.class,task.taskId())==1);
      var fresh=jdbc.queryForMap("SELECT summary_id,content_hash,object_key FROM summary WHERE version_id=? AND lifecycle='ACTIVE'",version);
      check("identicalFreshOutputCannotOverwriteOrReactivateRetainedStaleHistory",fresh.get("content_hash").equals(before.get("content_hash"))&&!fresh.get("summary_id").equals(before.get("summary_id"))&&!fresh.get("object_key").equals(before.get("object_key"))&&jdbc.queryForObject("SELECT count(*) FROM summary WHERE summary_id=? AND lifecycle='STALE' AND object_key=?",Integer.class,before.get("summary_id"),before.get("object_key"))==1&&jdbc.queryForObject("SELECT count(*) FROM summary_fact WHERE summary_id=?",Integer.class,before.get("summary_id"))==1&&jdbc.queryForObject("SELECT count(*) FROM compile_task_failure WHERE task_id=?",Integer.class,task.taskId())==1);
      report.put("freshSummary",fresh);
      report.put("status","PASS_REAL_POSTGRES_SUMMARY_HANDOFF_RECOVERY");report.put("summaryTaskId",task.taskId());report.put("checkpoint",after);report.put("downstream",downstream);report.put("syntheticModelCalls",calls);
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_SUMMARY_HANDOFF_RECOVERY");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);report.put("boundary","Real PostgreSQL, durable worker, normal task/input-fence/publication/retry; explicit synthetic model, object store, embedding and index. Fault removal changes only a retained isolated test dependency flag. This is no real-model or MinIO/Milvus end-to-end pass.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
