import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Existing synthetic source, actual configured Luna and immutable production judge policy. */
public final class RealFactGroundingProbe {
  public static void main(String[] args) throws Exception {
    Path output=Path.of(args[0]); String version=args[1];
    var mapper=new ObjectMapper().findAndRegisterModules();
    var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    var jdbc=new JdbcTemplate(ds);
    var report=new LinkedHashMap<String,Object>();
    report.put("status","RUNNING_REAL_GROUNDING_DIAGNOSTIC");report.put("startedAt",Instant.now().toString());
    report.put("boundary","Real configured SMALL model via production Spring AI BeanOutputConverter/HTTP retry client and immutable Summary judge criteria. Read-only actual synthetic source and facts. No task, Summary, entry, source, model profile or usage state is written. Not a full compilation, formal QA score, or repair of old published content.");
    var results=new ArrayList<Map<String,Object>>();report.put("results",results);
    try {
      var source=jdbc.queryForMap("SELECT v.version_id,v.sha256,v.lifecycle,m.manual_tags::text AS tags FROM document_version v JOIN document_metadata m USING(version_id) WHERE v.version_id=?",version);
      if(!"ACTIVE".equals(source.get("lifecycle")) || !source.get("tags").toString().contains("HT-ENTRY-104"))
        throw new IllegalStateException("Only the exact owned synthetic acceptance source is allowed");
      var rows=jdbc.queryForList("SELECT f.fact_id,f.summary_id,f.fact_text,e.evidence_id,b.raw_content FROM summary_fact f JOIN summary s USING(summary_id) JOIN summary_evidence se USING(fact_id) JOIN evidence e USING(evidence_id) JOIN raw_block b USING(block_id) WHERE s.version_id=? AND s.lifecycle='ACTIVE' AND f.grounding_status='PASS' AND f.fact_text LIKE '%无发票%'",version);
      if(rows.size()!=1)throw new IllegalStateException("Expected one actual suspect grounded fact; preserve ambiguous state");
      var original=rows.get(0);report.put("source",source);report.put("actualPublishedFact",original);
      String evidence=original.get("raw_content").toString();
      String originalFact=original.get("fact_text").toString();
      if(!originalFact.contains("采购方不能要求无发票付款"))throw new IllegalStateException("The observed defect changed; do not substitute a new candidate");
      var method=SummaryCompilationService.class.getDeclaredMethod("groundingCriteria");method.setAccessible(true);
      String system="你是独立事实 Grounding Judge。仅判断 FACT 是否被给定 RAW EVIDENCE 直接支持。"
          +method.invoke(null)+"EVIDENCE 可能是同一原始证据的完整分片；只依据当前分片，严禁使用常识补全。严格输出 supported/reason。";
      report.put("judgeSystemPrompt",system);
      Store store=new Store(){
        public EffectiveConfiguration current(){
          return jdbc.queryForObject("SELECT * FROM model_configuration_profile WHERE active=TRUE",(r,n)->new EffectiveConfiguration(
            r.getString("chat_base_url"),r.getString("chat_api_key"),r.getString("small_model"),r.getString("large_model"),r.getInt("chat_timeout_seconds"),
            r.getString("embedding_base_url"),r.getString("embedding_api_key"),r.getString("embedding_model"),r.getInt("embedding_dimensions"),r.getInt("embedding_batch_size"),r.getInt("embedding_timeout_seconds"),
            r.getString("rerank_base_url"),r.getString("rerank_api_key"),r.getString("rerank_model"),r.getInt("rerank_timeout_seconds"),r.getTimestamp("updated_at").toInstant()));
        }
        public EffectiveConfiguration update(UpdateCommand c){throw new UnsupportedOperationException("read-only diagnostic");}
      };
      var before=store.current();
      if(!before.smallModel().equals("gpt-5.6-luna") || !before.largeModel().equals("gpt-5.6-terra"))
        throw new IllegalStateException("Approved roles must remain Luna/Terra");
      report.put("smallModel",before.smallModel());report.put("largeModel",before.largeModel());report.put("profileUpdatedAt",before.updatedAt());
      var model=new SpringAiAlibabaKnowledgeModel(store,mapper);
      // Freeze both candidates and expected values before the first call. Gold never enters the prompt.
      List<String> candidates=List.of(originalFact,evidence);
      List<Boolean> expected=List.of(false,true);
      report.put("frozenCandidates",candidates);report.put("expectedSupportNotSentToModel",expected);
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
      for(int i=0;i<candidates.size();i++){
        var item=new LinkedHashMap<String,Object>();results.add(item);item.put("candidate",i+1);item.put("expectedSupport",expected.get(i));
        long start=System.nanoTime();
        try{
          var result=model.generateStructured(ModelRole.SMALL,ModelTask.SUMMARY_JUDGE,system,
              "FACT: "+candidates.get(i)+"\nEVIDENCE:\n"+evidence,SummaryCompilationService.SupportDecision.class);
          item.put("response",result);item.put("pass",result!=null && result.supported()==expected.get(i));
        }catch(RuntimeException failure){item.put("pass",false);item.put("failureType",failure.getClass().getSimpleName());item.put("failure",failure.getMessage());}
        item.put("seconds",(System.nanoTime()-start)/1_000_000_000d);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
      }
      report.put("configurationUnchanged",before.equals(store.current()));
      report.put("status",results.stream().allMatch(r->Boolean.TRUE.equals(r.get("pass")))&&before.equals(store.current())?"PASS_REAL_GROUNDING_DIAGNOSTIC":"FAIL_REAL_GROUNDING_DIAGNOSTIC");
    }catch(Throwable failure){report.put("status","FAIL_REAL_GROUNDING_DIAGNOSTIC");report.put("failureType",failure.getClass().getSimpleName());report.put("failure",failure.getMessage());}
    report.put("finishedAt",Instant.now().toString());mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    if(!"PASS_REAL_GROUNDING_DIAGNOSTIC".equals(report.get("status")))System.exit(1);
  }
}
