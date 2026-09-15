import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.storage.LocalFileSourceAdapter;
import cn.lgs.knowledgebase.application.raw.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.ingestion.IngestionDomain.ProgressStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
/** Isolated clone of the CUA's actual completed resources; unused external ports fail closed. */
public class RawCompletedInputGuardProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static <T>T unused(Class<T> type){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new AssertionError("unexpected external operation "+type.getSimpleName()+"."+m.getName());}));}
  static boolean exactDenied(Runnable call,String code){try{call.run();return false;}catch(IllegalStateException e){return e.getMessage()!=null&&e.getMessage().startsWith(code);}}
  public static void main(String[] args)throws Exception {
    var report=new LinkedHashMap<String,Object>();var mapper=new ObjectMapper();
    try {
      var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var uploads=new JdbcIngestionRepository(db,tx,mapper);var metadata=new JdbcKnowledgeRepository(db,tx,mapper);String file=args[1],path=args[2];
      uploads.retryFile(file,Instant.now());var claim=uploads.claimRaw(file,UUID.randomUUID().toString()).orElseThrow();var execution=uploads.rawExecution(claim);var source=new LocalFileSourceAdapter().inspect(path);uploads.rawVerifyOriginal(claim,source);var version=execution.reservedInput().orElseThrow();
      var initial=db.queryForMap("SELECT stage_deadline-stage_started_at=interval '600 seconds' AS exact,attempt,execution_epoch FROM ingestion_file WHERE file_id=?",file);checks.put("claimDeadlineExactly600UsingOneDbClock",Boolean.TRUE.equals(initial.get("exact")));
      execution.phase(ProgressStage.EMBEDDING);var phase=db.queryForMap("SELECT stage_deadline-stage_started_at=interval '600 seconds' AS exact,stage_deadline FROM ingestion_file WHERE file_id=?",file);execution.phase(ProgressStage.EMBEDDING);checks.put("stageExactly600AndRepeatedPhaseDoesNotRenew",Boolean.TRUE.equals(phase.get("exact"))&&phase.get("stage_deadline").equals(db.queryForObject("SELECT stage_deadline FROM ingestion_file WHERE file_id=?",java.sql.Timestamp.class,file)));
      checks.put("actualPublishedVersionCloneIsCurrent",execution.completedInputCurrent(version.documentId(),version.id()));execution.bind(version.documentId(),version.id());
      db.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id=?",version.id());checks.put("aclRevocationRejectsProjectionAndTypedCompletion",!execution.completedInputCurrent(version.documentId(),version.id())&&exactDenied(()->uploads.rawComplete(claim,version.documentId(),version.id()),"RAW_SOURCE_REVOKED")&&"RUNNING".equals(db.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,file)));db.update("UPDATE document_metadata SET acl_version=acl_version-1 WHERE version_id=?",version.id());
      var raw=new RawIngestionApplicationService(new LocalFileSourceAdapter(),unused(ObjectStore.class),metadata,unused(ParserPort.class),unused(ParsedArtifactEncoder.class),unused(RawSearchIndex.class),unused(EmbeddingPort.class),null);
      var result=raw.ingest(new RawIngestionApplicationService.IngestCommand(path,claim.file().metadata().logicalName(),"component-clone",null),null,"",task->{throw new AssertionError("unexpected parser task");},execution);checks.put("activePublicationRetryUsesExactVersionAndNoExternalOperations",result.reused()&&result.versionId().equals(version.id()));uploads.rawComplete(claim,result.documentId(),result.versionId());checks.put("actualTerminalSqlCompletesOnlyCurrentFencedSource", "SUCCEEDED".equals(db.queryForObject("SELECT status FROM ingestion_file WHERE file_id=?",String.class,file)));
      checks.put("completedOwnerCannotCompleteAgain",exactDenied(()->uploads.rawComplete(claim,result.documentId(),result.versionId()),"RAW_STALE_CLAIM"));report.put("actualFinalFile",db.queryForList("SELECT file_id,status,attempt,execution_epoch,version_id,claimed_acl_version FROM ingestion_file WHERE file_id=?",file));report.put("actualPublicationOrigin",db.queryForList("SELECT p.*,m.physical_id FROM raw_publication p JOIN raw_publication_member m USING(version_id) WHERE p.version_id=?",version.id()));report.put("status",checks.values().stream().allMatch(Boolean::booleanValue)?"PASS_NATIVE_ACTUAL_PUBLICATION_RECOVERY_CLONE":"FAIL_NATIVE_ACTUAL_PUBLICATION_RECOVERY_CLONE");
    }catch(Throwable error){error.printStackTrace();report.put("failure",error.toString());report.put("status","FAIL_NATIVE_ACTUAL_PUBLICATION_RECOVERY_CLONE");}
    report.put("checks",checks);report.put("boundary","Isolated PostgreSQL clone of the real CUA DOCX version/publication/members, actual original bytes/SHA and normal repository retry/claim/complete. ACL mutation is an explicit isolated fault fixture. External ports throw if invoked; this only proves post-publication closure without external operations, not a new model/Milvus write or deployed API recovery.");mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);System.exit(report.get("status").toString().startsWith("PASS")?0:1);
  }
}
