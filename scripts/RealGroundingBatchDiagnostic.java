import cn.lgs.knowledgebase.application.compilation.GroundingConstraintAudit;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** New read-only diagnostic inputs, never a reconstruction of unrecorded task responses. */
public final class RealGroundingBatchDiagnostic {
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    String version = args[1], summary = args[2];
    int offset = Integer.parseInt(args[3]);
    var mapper = new ObjectMapper().findAndRegisterModules()
        .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),
        "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
    var report = new LinkedHashMap<String, Object>();
    report.put("status", "PREPARING_NEW_READONLY_DIAGNOSTIC");
    report.put("startedAt", Instant.now());
    report.put("boundary", "DIAGNOSTIC_ONLY: actual persisted synthetic-source facts/evidence with the deployed BeanOutputConverter schema and grounding criteria. One new SMALL request, existing HTTP budgets and retries, no tasks/models/profile/source/Gold/publication state writes. Not the missing 605015 batch, not business compilation or formal semantic proof.");
    try {
      var source = jdbc.queryForMap("SELECT v.version_id,v.sha256,v.lifecycle,m.manual_tags::text AS tags FROM document_version v JOIN document_metadata m USING(version_id) WHERE v.version_id=?", version);
      if (!"ACTIVE".equals(source.get("lifecycle")) || !source.get("tags").toString().contains("HT-ENTRY-104"))
        throw new IllegalStateException("Only the existing owned synthetic acceptance source is allowed");
      var facts = jdbc.queryForList("SELECT f.fact_id,f.section,f.fact_text,f.summary_id FROM summary_fact f JOIN summary s USING(summary_id) WHERE s.summary_id=? AND s.version_id=? AND s.lifecycle='ACTIVE' AND f.grounding_status='PASS' ORDER BY f.fact_id OFFSET ? LIMIT 4", summary, version, offset);
      if (facts.isEmpty()) throw new IllegalStateException("Actual stored fact batch is empty");
      var candidates = new ArrayList<Object>();
      var frozen = new ArrayList<Map<String, Object>>();
      var claimById = new LinkedHashMap<String, String>();
      var evidenceById = new LinkedHashMap<String, String>();
      var candidateClass = Class.forName(SummaryCompilationService.class.getName()+"$GroundingCandidate");
      var constructor = candidateClass.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      int position = 0;
      for (var fact : facts) {
        var rows = jdbc.queryForList("SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location::text AS location,e.content_hash,b.raw_content FROM summary_evidence se JOIN evidence e USING(evidence_id) JOIN raw_block b USING(block_id) WHERE se.fact_id=? ORDER BY e.evidence_id", fact.get("fact_id"));
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !version.equals(row.get("version_id"))))
          throw new IllegalStateException("The diagnostic evidence binding is incomplete or changed");
        var evidence = new ArrayList<KnowledgeDomain.Evidence>();
        for (var row : rows) evidence.add(new KnowledgeDomain.Evidence(
            row.get("evidence_id").toString(), row.get("document_id").toString(), version,
            row.get("block_id").toString(), row.get("object_key").toString(),
            mapper.readValue(row.get("location").toString(), Map.class),
            row.get("raw_content").toString(), row.get("content_hash").toString()));
        String id = "C"+(++position), claim = fact.get("fact_text").toString();
        var render = SummaryCompilationService.class.getDeclaredMethod("renderEvidence", List.class);
        render.setAccessible(true);
        String originalEvidence = (String) render.invoke(null, evidence);
        claimById.put(id, claim); evidenceById.put(id, originalEvidence);
        candidates.add(constructor.newInstance(id, fact.get("fact_id").toString(),
            fact.get("section").toString(), claim,
            evidence.stream().map(KnowledgeDomain.Evidence::blockId).toList(), evidence, true, ""));
        frozen.add(Map.of("candidateId", id, "actualStoredFact", fact, "actualEvidenceRows", rows,
            "claimSha256", KnowledgeDomain.sha256(claim), "evidenceSha256", KnowledgeDomain.sha256(originalEvidence)));
      }
      report.put("legacyWireProtocolOnly", true);
      var criteria = GroundingConstraintAudit.class.getDeclaredMethod("criteria");
      criteria.setAccessible(true);
      var render = SummaryCompilationService.class.getDeclaredMethod("renderGroundingCandidates", List.class);
      render.setAccessible(true);
      String user = (String) render.invoke(null, candidates);
      String system = "你是独立事实 Grounding Judge。逐条判断每个 candidate 是否被它自己的 RAW EVIDENCE 直接支持。"
          +criteria.invoke(null)+"必须为每个 candidate_id 恰好返回一条 decision，不得遗漏、增加或改写 candidate_id；禁止使用常识补全。";
      var converter = new BeanOutputConverter<>(SummaryCompilationService.SupportDecisionSet.class, mapper);
      String structuredSystem = system+"\n\nReturn only a JSON value that follows this schema and formatting contract:\n"+converter.getFormat();
      Store store = new Store() {
        public EffectiveConfiguration current() {
          return jdbc.queryForObject("SELECT * FROM model_configuration_profile WHERE active=TRUE", (r,n) -> new EffectiveConfiguration(
              r.getString("chat_base_url"),r.getString("chat_api_key"),r.getString("small_model"),r.getString("large_model"),r.getInt("chat_timeout_seconds"),
              r.getString("embedding_base_url"),r.getString("embedding_api_key"),r.getString("embedding_model"),r.getInt("embedding_dimensions"),r.getInt("embedding_batch_size"),r.getInt("embedding_timeout_seconds"),
              r.getString("rerank_base_url"),r.getString("rerank_api_key"),r.getString("rerank_model"),r.getInt("rerank_timeout_seconds"),r.getTimestamp("updated_at").toInstant()));
        }
        public EffectiveConfiguration update(UpdateCommand command) { throw new UnsupportedOperationException("read-only diagnostic"); }
      };
      var before = store.current();
      if (!"gpt-5.6-luna".equals(before.smallModel()) || !"gpt-5.6-terra".equals(before.largeModel()))
        throw new IllegalStateException("Approved model roles changed");
      report.put("source", source); report.put("actualSummaryId", summary); report.put("factOffset", offset);
      report.put("frozenNewDiagnosticInputs", frozen); report.put("schemaContract", converter.getFormat());
      report.put("systemPrompt", structuredSystem); report.put("userPrompt", user);
      report.put("promptSha256", KnowledgeDomain.sha256(structuredSystem+"\n"+user));
      report.put("smallModel", before.smallModel()); report.put("chatTimeoutSeconds", before.chatTimeoutSeconds());
      report.put("profileUpdatedAt", before.updatedAt());
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      var model = new SpringAiAlibabaKnowledgeModel(store, mapper);
      long started = System.nanoTime();
      String actualContent = model.generate(ModelRole.SMALL, ModelTask.SUMMARY_JUDGE, structuredSystem, user);
      report.put("actualReturnedModelContent", actualContent);
      report.put("actualReturnedContentSha256", KnowledgeDomain.sha256(actualContent));
      report.put("modelSeconds", (System.nanoTime()-started)/1_000_000_000d);
      // Retain the genuine content before schema decoding or span validation can reject it.
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      var extract = SpringAiAlibabaKnowledgeModel.class.getDeclaredMethod("extractJson", String.class);
      extract.setAccessible(true);
      var normalize = SpringAiAlibabaKnowledgeModel.class.getDeclaredMethod("normalizeStructuredKeys", JsonNode.class);
      normalize.setAccessible(true);
      var parsed = mapper.treeToValue((JsonNode) normalize.invoke(model,
          mapper.readTree((String) extract.invoke(model, actualContent))), SummaryCompilationService.SupportDecisionSet.class);
      report.put("actualParsedResponse", parsed);
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      var outcomes = new ArrayList<Map<String,Object>>(); report.put("auditObservations", outcomes);
      var records = GroundingConstraintAudit.modelDecisions(parsed.decisions(), claimById.keySet(),
          SummaryCompilationService.SupportDecisionItem::candidateId, "diagnostic actual grounding batch");
      for (var entry : records.entrySet()) {
        String id = entry.getKey(), claim = claimById.get(id), evidence = evidenceById.get(id);
        var record = entry.getValue(); var item = new LinkedHashMap<String,Object>(); outcomes.add(item);
        item.put("candidateId", id); item.put("actualCheckCount", record.checks()==null?0:record.checks().size());
        var spans = new ArrayList<Map<String,Object>>(); item.put("actualSpanObservations", spans);
        if (record.checks()!=null) for (var check : record.checks()) {
          if (check==null) { spans.add(Map.of("nullRecord",true)); continue; }
          var span = new LinkedHashMap<String,Object>(); spans.add(span);
          span.put("actualDimension",check.dimension()); span.put("actualClaimPart",check.claimPart());
          span.put("claimContainsActualPart",check.claimPart()!=null && claim.contains(check.claimPart()));
          span.put("actualEvidenceQuotes",check.evidenceQuotes());
          span.put("evidenceContainsEveryActualQuote", check.evidenceQuotes()!=null
              && check.evidenceQuotes().stream().allMatch(q -> q!=null && evidence.contains(q)));
        }
        try {
          item.put("publicationSupportAfterCurrentAudit", GroundingConstraintAudit.modelSupported(claim,
              evidence, record.supported(), record.checks()));
          item.put("auditRecordAccepted", true);
        } catch (RuntimeException invalid) {
          item.put("auditRecordAccepted", false); item.put("actualAuditFailureType",invalid.getClass().getSimpleName());
          item.put("actualAuditFailure",invalid.getMessage());
        }
      }
      report.put("configurationUnchanged", before.equals(store.current()));
      report.put("status", "OBSERVED_NEW_REAL_GROUNDING_DIAGNOSTIC_RESPONSE");
    } catch (Throwable failure) {
      report.put("status", "FAIL_NEW_REAL_GROUNDING_DIAGNOSTIC");
      report.put("failureType", failure.getClass().getSimpleName());
      report.put("failure", failure.getMessage());
    }
    report.put("finishedAt", Instant.now());
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    if (report.get("status").toString().startsWith("FAIL")) System.exit(1);
  }
}
