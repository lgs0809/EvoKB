import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** New frozen semantic fixtures and genuine remote output; no tasks/publication/profile writes. */
public final class RealGroundingProtocolProbe {
  public static void main(String[] args) throws Exception {
    var output=Path.of(args[0]);var fixture=Path.of(args[1]);
    var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD")));
    var report=new LinkedHashMap<String,Object>();var observations=new ArrayList<Map<String,Object>>();
    report.put("startedAt",Instant.now());report.put("status","RUNNING_READONLY_REAL_PROTOCOL_DIAGNOSTIC");report.put("batches",observations);
    report.put("boundary","New independent synthetic semantic cases. Real approved SMALL model with existing HTTP budget/retries and exact production converter/decoder/binding/validator. No native task, sixth attempt, source/profile/Gold/publication mutation, parser or embedding call. Not the lost historical five outputs.");
    try {
      var cases=mapper.readTree(fixture.toFile()).path("cases");
      Store store=new Store(){
        public EffectiveConfiguration current(){return jdbc.queryForObject("SELECT * FROM model_configuration_profile WHERE active=TRUE",(r,n)->new EffectiveConfiguration(
            r.getString("chat_base_url"),r.getString("chat_api_key"),r.getString("small_model"),r.getString("large_model"),r.getInt("chat_timeout_seconds"),
            r.getString("embedding_base_url"),r.getString("embedding_api_key"),r.getString("embedding_model"),r.getInt("embedding_dimensions"),r.getInt("embedding_batch_size"),r.getInt("embedding_timeout_seconds"),
            r.getString("rerank_base_url"),r.getString("rerank_api_key"),r.getString("rerank_model"),r.getInt("rerank_timeout_seconds"),r.getTimestamp("updated_at").toInstant()));}
        public EffectiveConfiguration update(UpdateCommand command){throw new UnsupportedOperationException("read-only");}
      };
      var before=store.current();
      if(!"gpt-5.6-luna".equals(before.smallModel())||!"gpt-5.6-terra".equals(before.largeModel()))throw new IllegalStateException("Approved model roles changed");
      report.put("smallModel",before.smallModel());report.put("profileUpdatedAt",before.updatedAt());report.put("chatTimeoutSeconds",before.chatTimeoutSeconds());
      var model=new SpringAiAlibabaKnowledgeModel(store,mapper);
      var converter=new BeanOutputConverter<>(GroundingJudgeProtocol.Batch.class,mapper);
      String system="你是独立事实 Grounding Judge。逐条判断每个 candidate 是否被它自己的 RAW EVIDENCE 直接支持。"+GroundingJudgeProtocol.criteria()
          +"必须为每个 candidate_id 恰好返回一条 decision，不得遗漏、增加或改写 candidate_id。\n\nReturn only a JSON value that follows this schema and formatting contract:\n"+converter.getFormat();
      report.put("systemPrompt",system);
      var decode=SpringAiAlibabaKnowledgeModel.class.getDeclaredMethod("extractJson",String.class);decode.setAccessible(true);
      var normalize=SpringAiAlibabaKnowledgeModel.class.getDeclaredMethod("normalizeStructuredKeys",JsonNode.class);normalize.setAccessible(true);
      boolean passed=true;
      for(int offset=0;offset<cases.size();offset+=3){
        var batch=new LinkedHashMap<String,Object>();observations.add(batch);
        var expected=new LinkedHashMap<String,JsonNode>();var user=new StringBuilder("CANDIDATES:\n");
        for(int n=offset;n<Math.min(offset+3,cases.size());n++){
          var c=cases.get(n);String id=c.path("id").asText();expected.put(id,c);
          user.append("candidate_id=").append(id).append("\nFACT=").append(c.path("claim").asText()).append("\nEVIDENCE:\n").append(c.path("evidence").asText()).append("\n===\n");
        }
        batch.put("userPrompt",user.toString());batch.put("startedAt",Instant.now());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        try {
          String content=model.generate(ModelRole.SMALL,ModelTask.SUMMARY_JUDGE,system,user.toString());
          batch.put("actualReturnedModelContent",content);
          mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
          var parsed=mapper.treeToValue((JsonNode)normalize.invoke(model,mapper.readTree((String)decode.invoke(model,content))),GroundingJudgeProtocol.Batch.class);
          batch.put("parsed",parsed);GroundingJudgeProtocol.requireVersion(parsed.protocolVersion());
          var records=GroundingConstraintAudit.modelDecisions(parsed.decisions(),expected.keySet(),GroundingJudgeProtocol.Item::candidateId,"new diagnostic batch");
          var outcomes=new ArrayList<Map<String,Object>>();batch.put("results",outcomes);
          for(var entry:records.entrySet()){
            var c=expected.get(entry.getKey());var d=entry.getValue();
            var item=new LinkedHashMap<String,Object>();outcomes.add(item);item.put("id",entry.getKey());item.put("expectedSupported",c.path("expectedSupported").asBoolean());
            var checks=GroundingJudgeProtocol.bind(c.path("claim").asText(),d.checks());
            boolean actual=GroundingConstraintAudit.modelSupported(c.path("claim").asText(),c.path("evidence").asText(),d.supported(),checks);
            item.put("actualSupportedAfterStrictAudit",actual);item.put("boundChecks",checks);
            item.put("pass",actual==c.path("expectedSupported").asBoolean());passed&=actual==c.path("expectedSupported").asBoolean();
          }
        }catch(Throwable failure){batch.put("failureType",failure.getClass().getName());batch.put("failure",failure.getMessage());passed=false;}
        batch.put("finishedAt",Instant.now());mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
      }
      report.put("configurationUnchanged",before.equals(store.current()));
      report.put("status",passed&&before.equals(store.current())?"PASS_READONLY_REAL_GROUNDING_PROTOCOL":"FAIL_READONLY_REAL_GROUNDING_PROTOCOL");
    }catch(Throwable failure){report.put("status","FAIL_READONLY_REAL_GROUNDING_PROTOCOL");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("finishedAt",Instant.now());mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
