import cn.lgs.knowledgebase.application.compilation.WikiKeywordProjectionService;
import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.infrastructure.graph.PostgresGraphStoreAdapter;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcGenerationTaskRepository;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcWikiKeywordProjectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Retained synthetic native PostgreSQL publication checks; no model quality claim. */
public final class WikiDatabaseProjectionProbe {
  final Map<String,Boolean> checks = new LinkedHashMap<>();
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final ObjectMapper mapper = new ObjectMapper();
  WikiDatabaseProjectionProbe() {
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD"));
    jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }
  int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
  void writeWiki() {
    jdbc.update("INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle) VALUES('wiki-component',1,'COMPANY','Synthetic component Wiki','component-only','component-only','ACTIVE')");
    jdbc.update("INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status) VALUES('claim-component','wiki-component',1,'synthetic','[[甲公司]] supplies to [[乙公司]]。','PASS','PASS')");
    jdbc.update("INSERT INTO wiki_evidence(claim_id,evidence_id) VALUES('claim-component','evidence-public')");
  }
  boolean rejected(Runnable action) { try { action.run(); return false; } catch (RuntimeException expected) { return true; } }
  void run(String output) throws Exception {
    var report = new LinkedHashMap<String,Object>();
    try {
      for (var entry : Map.of("company-a", "甲公司", "company-b", "乙公司", "literal", "A!_%（实体）").entrySet()) {
        jdbc.update("INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key,attributes_json) VALUES(?,?,'COMPANY',?,?::jsonb)", entry.getKey(), entry.getValue(), entry.getKey(), "{\"scope_key\":\"component-scope\"}");
      }
      var repository = new JdbcWikiKeywordProjectionRepository(jdbc,tx,mapper);
      boolean literal = false;
      try { literal = repository.findCanonicalEntitiesByName("A!_%").size() == 1; }
      catch (RuntimeException failure) { report.put("literalLookupFailure",failure.toString()); }
      checks.put("nativePostgresLookupTreatsWildcardsAndEscapeAsLiteralCharacters",literal);
      var graph = new PostgresGraphStoreAdapter(jdbc,tx,mapper,"component-kb");
      var projection = new WikiKeywordProjectionService(repository,graph);
      var tasks = new JdbcGenerationTaskRepository(jdbc,tx,"component-kb");
      tasks.queue("version-public",GenerationTaskType.WIKI,"component-wiki",Instant.now());
      var task = tasks.claimPending(1,Instant.now()).get(0);
      var gate = tasks.publication(task); gate.captureInputs(List.of("version-public","version-other"));
      boolean[] projected = {false};
      boolean rollback = rejected(() -> gate.publish(() -> {
        writeWiki(); var result = projection.project("wiki-component",1);
        projected[0] = result.relations().size() == 1;
        throw new IllegalStateException("synthetic post-projection database failure");
      }));
      checks.put("wikiClaimsKeywordSupportAndGraphAllRollbackWithFinalPublication",rollback && projected[0] && count("wiki") == 0 && count("wiki_claim") == 0 && count("relation_fact") == 0 && count("relation_evidence_support") == 0 && count("kb_graph_relation") == 0 && count("kb_graph_entity") == 0);
      gate.publish(() -> { writeWiki(); report.put("projection",projection.project("wiki-component",1)); });
      checks.put("currentFencedPublicationCommitsWikiAndOneGroundedKeywordRelation",count("wiki") == 1 && count("wiki_claim") == 1 && count("wiki_evidence") == 1 && count("relation_fact") == 1 && count("relation_evidence_support") == 1 && count("kb_graph_relation") == 1 && count("kb_graph_entity") == 2);
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-other'");
      int[] called = {0};
      checks.put("uncitedInputPermissionChangeRejectsProjectionBeforeCallback",rejected(() -> gate.publish(() -> called[0]++)) && called[0] == 0);
      tasks.complete(task,"synthetic-component-not-model",Instant.now());
      checks.put("completedTaskCannotPublishAnyLateKeywordCallback",rejected(() -> gate.publish(() -> called[0]++)) && called[0] == 0 && count("kb_graph_relation") == 1);
      report.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS_REAL_WIKI_DATABASE_PROJECTION" : "FAIL_REAL_WIKI_DATABASE_PROJECTION");
    } catch (Throwable failure) { report.put("status","ERROR_REAL_WIKI_DATABASE_PROJECTION"); report.put("error",failure.toString()); }
    report.put("checks",checks);
    report.put("boundary","Real PostgreSQL, Spring transactions, production task ownership/source fences, native Wiki keyword repository and PostgreSQL graph adapter in a retained synthetic schema. Fixture Wiki claims are not generated model output or application acceptance. No manufactured business/task success, no SQL deadline or execution status edits.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),report);
    if (!"PASS_REAL_WIKI_DATABASE_PROJECTION".equals(report.get("status"))) System.exit(1);
  }
  public static void main(String[] args) throws Exception { new WikiDatabaseProjectionProbe().run(args[0]); }
}
