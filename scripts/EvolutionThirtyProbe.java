import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.infrastructure.compilation.SelectiveGenerationScheduler;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Independent PG cases; all generated/query payloads are synthetic component fixtures. */
public final class EvolutionThirtyProbe {
  final SelectiveGenerationProbe fixture = new SelectiveGenerationProbe();
  final Map<String, Boolean> checks = new LinkedHashMap<>();
  final Map<String, Object> measured = new LinkedHashMap<>();
  final SelectiveGenerationScheduler scheduler = new SelectiveGenerationScheduler(
      fixture.jdbc, fixture.tx, fixture.tasks, "quota-component");

  void observe(String key, Object value) { measured.put(key, value); }
  int count(String table) { return fixture.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
  int candidates(String stage) {
    return fixture.jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE knowledge_base_id='quota-component' AND stage=? AND qualified_at<>'-infinity'::timestamptz", Integer.class, stage);
  }
  int selected(String stage) {
    return fixture.jdbc.queryForObject("SELECT count(*) FROM kb_generation_daily_selection WHERE stage=?", Integer.class, stage);
  }
  int remaining(String stage) {
    return fixture.jdbc.queryForObject("SELECT count(*) FROM kb_generation_candidate WHERE stage=? AND selected_task_id IS NULL AND qualified_at<>'-infinity'::timestamptz", Integer.class, stage);
  }
  void uses(String source, int count, String entry) {
    for (int i = 0; i < count; i++) fixture.usage(source, i, entry);
  }
  String createEntry(String source, int ownUses, int unboundUses) {
    fixture.raw(source);
    String entry = fixture.entry(source);
    uses(source, ownUses, entry);
    for (int i = ownUses; i < ownUses + unboundUses; i++) fixture.usage(source, i, null);
    return entry;
  }
  String createManifest(String suffix) {
    fixture.raw(suffix);
    String session = "q-" + suffix, pack = "p-" + suffix, evidence = "e-" + suffix;
    fixture.jdbc.update("INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids) VALUES(?,'quota-user','Synthetic mechanism fixture','Synthetic component question','{}','ANSWERED','[]',jsonb_build_array(?::text))", session, evidence);
    new JdbcPackageAuthorizationRepository(fixture.jdbc, fixture.mapper).save(
        new PackageAuthorization(pack, "quota-user", "component-only/" + pack, List.of(evidence), Instant.now().plusSeconds(900)));
    new JdbcDownloadUsageRepository(fixture.jdbc, fixture.tx).saveManifest("quota-user", pack,
        new PackageManifest(Instant.now(), List.of(evidence),
            List.of(new PackageVersion("v-" + suffix, "d-" + suffix, "component.md", "component-only", "component.md", List.of(evidence))),
            List.of(new PackageAnswer(session, List.of(evidence)))), 9);
    return pack;
  }
  void sourceCase(String scenario) {
    if (scenario.equals("MANIFEST_ONLY") || scenario.equals("SHORT_TRANSFER") || scenario.equals("ROLLED_BACK_TRANSFER") || scenario.equals("SOURCE_REPLAY")) {
      String pack = createManifest("source");
      var usage = new JdbcDownloadUsageRepository(fixture.jdbc, fixture.tx);
      if (scenario.equals("SHORT_TRANSFER")) {
        boolean rejected = false;
        try { usage.recordCompletedTransfer("quota-user", pack, 8); }
        catch (IllegalArgumentException expected) { rejected = true; }
        observe("rejected", rejected);
      } else if (scenario.equals("ROLLED_BACK_TRANSFER")) {
        try { fixture.tx.executeWithoutResult(tx -> { usage.recordCompletedTransfer("quota-user", pack, 9); throw new IllegalStateException("SYNTHETIC_COMPONENT_ROLLBACK"); }); }
        catch (IllegalStateException expected) { checks.put("expectedNativeRollback", expected.getMessage().equals("SYNTHETIC_COMPONENT_ROLLBACK")); }
      } else if (scenario.equals("SOURCE_REPLAY")) {
        for (int i = 0; i < 8; i++) usage.recordCompletedTransfer("quota-user", pack, 9);
      }
    } else {
      fixture.raw("source");
      int uses = switch (scenario) { case "SOURCE_ZERO" -> 0; case "SOURCE_TWO" -> 2; case "SOURCE_THREE" -> 3; case "SOURCE_FOUR" -> 4; default -> throw new IllegalArgumentException(scenario); };
      uses("source", uses, null);
    }
    scheduler.refreshCandidates();
    observe("sourceContributions", count("source_usage_contribution"));
    observe("receipts", count("package_download_receipt"));
    observe("summaryCandidates", candidates("SUMMARY"));
  }
  void entryCase(String scenario) {
    int own = switch (scenario) { case "ENTRY_FOUR" -> 4; case "ENTRY_SOURCE_ONLY" -> 0; case "ENTRY_MIXED" -> 3; default -> 5; };
    String entry = createEntry("entry", own, scenario.equals("ENTRY_SOURCE_ONLY") || scenario.equals("ENTRY_MIXED") ? 8 : 0);
    switch (scenario) {
      case "ENTRY_EXPIRED_HEAT" -> fixture.jdbc.update("UPDATE asset_usage_contribution SET created_at=clock_timestamp()-INTERVAL '31 days'");
      case "ENTRY_ACL_CHANGED" -> fixture.jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='v-entry'");
      case "ENTRY_DELETED_SOURCE" -> fixture.jdbc.update("UPDATE document SET status='DELETED' WHERE document_id='d-entry'");
      case "ENTRY_ALREADY_WIKI" -> fixture.jdbc.update("UPDATE kb_knowledge_entry SET stage='WIKI' WHERE entry_id=?", entry);
      case "ENTRY_STALE" -> fixture.jdbc.update("UPDATE kb_knowledge_entry SET lifecycle='STALE' WHERE entry_id=?", entry);
    }
    scheduler.refreshCandidates();
    observe("entryContributions", count("asset_usage_contribution"));
    observe("wikiCandidates", candidates("WIKI"));
    observe("graphCandidates", candidates("GRAPH"));
  }
  void graphCase(String scenario) {
    String entry = createEntry("graph", 0, scenario.equals("GRAPH_TWO") ? 2 : 3);
    switch (scenario) {
      case "GRAPH_SOURCE_SCOPED" -> fixture.jdbc.update("UPDATE kb_knowledge_entry SET scope_json=jsonb_set(scope_json,'{identity_status}','\"SOURCE_SCOPED\"'::jsonb) WHERE entry_id=?", entry);
      case "GRAPH_TOPIC_TYPE" -> fixture.jdbc.update("UPDATE kb_knowledge_entry SET entry_type='TOPIC' WHERE entry_id=?", entry);
      case "GRAPH_ACL_CHANGED" -> fixture.jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='v-graph'");
    }
    scheduler.refreshCandidates();
    observe("graphCandidates", candidates("GRAPH"));
  }
  void lifecycleCase(String scenario) {
    if (scenario.equals("SUMMARY_ALREADY_ACTIVE")) {
      createEntry("active", 0, 3);
      scheduler.refreshCandidates();
      observe("summaryCandidates", candidates("SUMMARY"));
    } else if (scenario.equals("SOURCE_NEW_VERSION")) {
      fixture.raw("old"); uses("old", 3, null);
      fixture.jdbc.update("UPDATE document_version SET lifecycle='INACTIVE' WHERE version_id='v-old'");
      fixture.jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('v-new','d-old',2,'ACTIVE','component-new.md',9,'new-component-only','component-only')");
      fixture.jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES('v-new',10)");
      scheduler.refreshCandidates();
      observe("newVersionContributions", fixture.jdbc.queryForObject("SELECT count(*) FROM source_usage_contribution WHERE version_id='v-new'", Integer.class));
      observe("summaryCandidates", candidates("SUMMARY"));
    } else if (scenario.equals("ACL_REPAIR_PRIORITY")) {
      createEntry("repair", 0, 0);
      fixture.jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='v-repair'");
      scheduler.invalidateChangedInputs();
      observe("repairJobs", fixture.jdbc.queryForObject("SELECT count(*) FROM compile_task WHERE task_type='SUMMARY' AND version_id='v-repair' AND subject_id='repair/acl/2'", Integer.class));
      observe("repairPriority", fixture.jdbc.queryForObject("SELECT max(priority) FROM compile_task WHERE task_type='SUMMARY' AND version_id='v-repair'", Integer.class));
      observe("newDailySelections", count("kb_generation_daily_selection"));
    } else throw new IllegalArgumentException(scenario);
  }
  void quotaCase(String scenario) throws Exception {
    boolean concurrent = scenario.equals("CONCURRENT_DAILY_QUOTAS");
    int sources = concurrent ? 25 : scenario.equals("SUMMARY_QUOTA_20") ? 20 : scenario.equals("SUMMARY_QUOTA_21") ? 21 : 0;
    for (int i = 0; i < sources; i++) { String suffix = "source" + i; fixture.raw(suffix); uses(suffix, 3, null); }
    int entries = concurrent ? 12 : 11;
    if (concurrent || scenario.equals("WIKI_QUOTA_11") || scenario.equals("GRAPH_QUOTA_11")) {
      for (int i = 0; i < entries; i++) createEntry("entry" + i, scenario.equals("GRAPH_QUOTA_11") ? 0 : 5, scenario.equals("GRAPH_QUOTA_11") ? 3 : 0);
    }
    if (concurrent) {
      var pool = Executors.newFixedThreadPool(4);
      var ready = new CountDownLatch(4); var start = new CountDownLatch(1);
      try {
        var futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("fixture start timeout"); return new SelectiveGenerationScheduler(fixture.jdbc, fixture.tx, fixture.tasks, "quota-component").selectDaily(); }));
        if (!ready.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("fixture readiness timeout");
        start.countDown(); int total = 0; for (var result : futures) total += result.get(60, TimeUnit.SECONDS);
        observe("selectedTotal", total);
      } finally { pool.shutdownNow(); }
    } else scheduler.selectDaily();
    observe("selectedSummary", selected("SUMMARY")); observe("remainingSummary", remaining("SUMMARY"));
    observe("selectedWiki", selected("WIKI")); observe("remainingWiki", remaining("WIKI"));
    observe("selectedGraph", selected("GRAPH")); observe("remainingGraph", remaining("GRAPH"));
    observe("restartSelections", new SelectiveGenerationScheduler(fixture.jdbc, fixture.tx, fixture.tasks, "quota-component").selectDaily());
    checks.put("actualShanghaiBusinessDay", Boolean.TRUE.equals(fixture.jdbc.queryForObject("SELECT bool_and(business_day=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')::date) FROM kb_generation_daily_selection", Boolean.class)));
  }
  void run(String output, String caseJson) throws Exception {
    Map<String, Object> test = fixture.mapper.readValue(caseJson, new TypeReference<>() {});
    String scenario = (String) test.get("scenario");
    var report = new LinkedHashMap<String, Object>(); report.put("case", test);
    try {
      if (scenario.startsWith("SOURCE_") && !scenario.equals("SOURCE_NEW_VERSION") || Set.of("MANIFEST_ONLY", "SHORT_TRANSFER", "ROLLED_BACK_TRANSFER").contains(scenario)) sourceCase(scenario);
      else if (scenario.startsWith("ENTRY_")) entryCase(scenario);
      else if (scenario.startsWith("GRAPH_") && !scenario.equals("GRAPH_QUOTA_11")) graphCase(scenario);
      else if (scenario.contains("QUOTA") || scenario.equals("CONCURRENT_DAILY_QUOTAS")) quotaCase(scenario);
      else lifecycleCase(scenario);
      var expected = (Map<?, ?>) test.get("expected");
      for (var assertion : expected.entrySet()) {
        String key = (String) assertion.getKey(); Object actual = measured.get(key), want = assertion.getValue();
        checks.put(key, actual instanceof Number a && want instanceof Number w ? a.longValue() == w.longValue() : Objects.equals(actual, want));
      }
      report.put("status", checks.values().stream().allMatch(Boolean.TRUE::equals) ? "PASS_NATIVE_EVOLUTION_CASE" : "FAIL_NATIVE_EVOLUTION_CASE");
    } catch (Throwable failure) { failure.printStackTrace(); report.put("status", "ERROR_NATIVE_EVOLUTION_CASE"); report.put("failure", failure.toString()); }
    report.put("measured", measured); report.put("checks", checks);
    report.put("nativeSources", fixture.jdbc.queryForList("SELECT * FROM source_usage_contribution ORDER BY session_id,version_id"));
    report.put("nativeEntryUses", fixture.jdbc.queryForList("SELECT * FROM asset_usage_contribution ORDER BY session_id,asset_id"));
    report.put("nativeCandidates", fixture.jdbc.queryForList("SELECT * FROM kb_generation_candidate ORDER BY stage,target_id"));
    report.put("nativeSelections", fixture.jdbc.queryForList("SELECT * FROM kb_generation_daily_selection ORDER BY stage,target_id"));
    report.put("nativeTasks", fixture.jdbc.queryForList("SELECT task_id,knowledge_base_id,version_id,task_type,subject_id,status,attempt,execution_epoch,priority FROM compile_task ORDER BY created_at"));
    report.put("boundary", "Native production PostgreSQL repositories/schedulers in one independently retained schema per frozen case. Query/summary/concept/bytes are synthetic component fixtures. Relative ages and invalid input states are seeded only in this schema. No real model, browser transfer, elapsed thirty days, midnight, Milvus projection or complete KB quality acceptance is claimed. Public business workflow state is untouched.");
    fixture.mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), report);
    if (!report.get("status").equals("PASS_NATIVE_EVOLUTION_CASE")) System.exit(1);
  }
  public static void main(String[] args) throws Exception { new EvolutionThirtyProbe().run(args[0], args[1]); }
}
