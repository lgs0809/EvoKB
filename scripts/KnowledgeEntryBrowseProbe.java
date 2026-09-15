import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.EntryQuery;
import cn.lgs.knowledgebase.infrastructure.persistence.JdbcEnterpriseOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated PostgreSQL read-policy fixtures, never model or workflow success. */
public final class KnowledgeEntryBrowseProbe {
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final JdbcTemplate jdbc;
  final JdbcEnterpriseOperations operations;
  final Map<String,Boolean> checks = new LinkedHashMap<>();
  KnowledgeEntryBrowseProbe() {
    var ds = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    operations = new JdbcEnterpriseOperations(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),mapper,"browse-kb");
  }
  void check(String name, boolean value) { checks.put(name,value); if(!value) throw new AssertionError(name); }
  void seed(String suffix) {
    jdbc.update("INSERT INTO document_unit(unit_id,version_id,unit_no,title) VALUES(?,?,1,?)","unit-"+suffix,"version-"+suffix,"Synthetic read policy");
    jdbc.update("INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES(?,?,?,'component-only','component-only','PASS','PASS','ACTIVE','SYNTHETIC_COMPONENT_NOT_MODEL')","summary-"+suffix,"unit-"+suffix,"version-"+suffix);
    jdbc.update("INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status) VALUES(?,?,'Synthetic','Synthetic contract payment condition','PASS')","fact-"+suffix,"summary-"+suffix);
    jdbc.update("INSERT INTO summary_evidence(fact_id,evidence_id) VALUES(?,?)","fact-"+suffix,"evidence-"+suffix);
    jdbc.update("INSERT INTO concept_candidate(concept_id,summary_id,version_id,preferred_label,concept_type_hint,description,model) VALUES(?,?,?,'Same synthetic label','CONTRACT','Synthetic component description','SYNTHETIC_COMPONENT_NOT_MODEL')","concept-"+suffix,"summary-"+suffix,"version-"+suffix);
  }
  void entry(String id,String member,List<String> inputs,String stage,String kb) {
    jdbc.update("INSERT INTO kb_knowledge_entry(knowledge_base_id,entry_id,revision,scope_key,scope_json,entry_type,preferred_label,aliases,description,stage,lifecycle) VALUES(?,?,1,?,?::jsonb,'CONTRACT','Same synthetic label','[\"Synthetic alias\"]','Synthetic component description',?,'ACTIVE')",kb,id,"document:doc-"+member,"{\"identity_status\":\"SOURCE_SCOPED\",\"document_id\":\"doc-"+member+"\"}",stage);
    if(member!=null) jdbc.update("INSERT INTO kb_entry_source(knowledge_base_id,entry_id,document_id,source_version_id,summary_id,concept_id,acl_version,fact_ids) VALUES(?,?,?,?,?,?,1,jsonb_build_array(?::text))",kb,id,"doc-"+member,"version-"+member,"summary-"+member,"concept-"+member,"fact-"+member);
    for(String input:inputs) jdbc.update("INSERT INTO kb_entry_build_input(knowledge_base_id,entry_id,entry_revision,summary_id,source_version_id,acl_version) VALUES(?,?,1,?,?,1)",kb,id,"summary-"+input,"version-"+input);
  }
  Set<String> ids(int clearance) {return new LinkedHashSet<>(operations.knowledgeEntries(new EntryQuery(1,100,null,"ALL"),clearance).items().stream().map(x->x.entryId()).toList());}
  void run(String output) throws Exception {
    Map<String,Object> report=new LinkedHashMap<>();
    try {
      for(String s:List.of("public","secret","other")) seed(s);
      entry("public-entry","public",List.of("public"),"CONCEPT","browse-kb");
      entry("same-name-entry","other",List.of("other"),"WIKI","browse-kb");
      entry("secret-entry","secret",List.of("secret"),"CONCEPT","browse-kb");
      entry("hidden-input-entry","public",List.of("public","secret"),"CONCEPT","browse-kb");
      entry("missing-input-entry","public",List.of(),"CONCEPT","browse-kb");
      entry("legacy-orphan",null,List.of(),"CONCEPT","browse-kb");
      entry("other-kb-entry","public",List.of("public"),"CONCEPT","other-kb");
      check("fullInputClearanceFiltersBeforeCountAndLimit",ids(10).equals(Set.of("public-entry","same-name-entry")) && operations.knowledgeEntries(new EntryQuery(1,1,null,null),10).total()==2);
      check("hiddenModelInputCannotLeakEvenWhenCitedSourceIsPublic",operations.knowledgeEntry("hidden-input-entry",10).isEmpty() && operations.knowledgeEntry("hidden-input-entry",30).isPresent());
      check("authorizedHigherClearanceSeesAllCurrentGroundedEntries",ids(30).equals(Set.of("public-entry","same-name-entry","secret-entry","hidden-input-entry")));
      check("trustedKnowledgeBaseFiltersListAndDetail",operations.knowledgeEntry("other-kb-entry",30).isEmpty() && !ids(30).contains("other-kb-entry"));
      check("historicSameNameWithoutProvenanceIsNeverReinterpretedAsEntry",operations.knowledgeEntry("legacy-orphan",30).isEmpty() && operations.knowledgeEntry("missing-input-entry",30).isEmpty());
      var detail=operations.knowledgeEntry("public-entry",10).orElseThrow();
      check("detailContainsCurrentScopeExactSourceFactsAndEvidence",detail.sources().size()==1 && detail.sourceCount()==1 && detail.sources().get(0).facts().get(0).evidenceIds().equals(List.of("evidence-public")) && detail.scope().get("document_id").equals("doc-public"));
      mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
      check("bootSnakeCaseMapperDoesNotCorruptInternalProvenance",operations.knowledgeEntry("public-entry",10).orElseThrow().sources().get(0).logicalName().contains("public"));
      check("equalLabelsKeepTheirIndependentBusinessScope",!detail.scope().equals(operations.knowledgeEntry("same-name-entry",10).orElseThrow().scope()));
      check("stageFilterAndBoundedPagingUseNativeFilteredCount",operations.knowledgeEntries(new EntryQuery(1,1,"Synthetic alias","CONCEPT"),10).total()==1 && operations.knowledgeEntries(new EntryQuery(2,1,null,null),10).items().size()==1 && operations.knowledgeEntries(new EntryQuery(1,999,null,null),10).pageSize()==100);
      boolean invalid=false;try{operations.knowledgeEntries(new EntryQuery(1,30,null,"RAW"),10);}catch(IllegalArgumentException expected){invalid=true;}check("unknownStageRejected",invalid);
      jdbc.update("UPDATE document_metadata SET acl_version=2 WHERE version_id='version-public'");
      check("aclChangeRevokesEntireEntryIncludingUncitedModelDependencies",operations.knowledgeEntry("public-entry",127).isEmpty() && operations.knowledgeEntry("hidden-input-entry",127).isEmpty() && ids(127).equals(Set.of("same-name-entry","secret-entry")));
      jdbc.update("UPDATE document_metadata SET acl_version=1 WHERE version_id='version-public'");
      jdbc.update("UPDATE summary SET lifecycle='STALE' WHERE summary_id='summary-public'");
      check("staleSummaryBlocksEntryProse",operations.knowledgeEntry("public-entry",127).isEmpty());
      jdbc.update("UPDATE summary SET lifecycle='ACTIVE' WHERE summary_id='summary-public'");
      jdbc.update("UPDATE evidence SET active=FALSE WHERE evidence_id='evidence-public'");
      check("inactiveEvidenceBlocksListAndDetail",operations.knowledgeEntry("public-entry",127).isEmpty() && !ids(127).contains("public-entry"));
      jdbc.update("UPDATE evidence SET active=TRUE WHERE evidence_id='evidence-public'");
      jdbc.update("UPDATE summary_fact SET grounding_status='FAIL' WHERE fact_id='fact-public'");
      check("ungroundedMemberFactBlocksEntry",operations.knowledgeEntry("public-entry",127).isEmpty());
      jdbc.update("UPDATE summary_fact SET grounding_status='PASS' WHERE fact_id='fact-public'");
      jdbc.update("INSERT INTO kb_entry_pending_source(knowledge_base_id,entry_id,source_version_id,evidence_ids,impact_text,affected_source_versions) VALUES('browse-kb','public-entry','version-other','[\"evidence-other\"]','Synthetic pending change','[\"version-public\"]')");
      check("unassociatedSupplementCannotExposeOldProse",operations.knowledgeEntry("public-entry",127).isEmpty());
      jdbc.update("UPDATE kb_entry_pending_source SET associated_at=clock_timestamp() WHERE entry_id='public-entry'");
      check("unaffectedCurrentEntryReappearsAfterFixtureAssociation",operations.knowledgeEntry("public-entry",10).isPresent());
      jdbc.update("UPDATE document SET status='DELETED' WHERE document_id='doc-public'");
      check("removedSourceBlocksEntryAtAllClearances",operations.knowledgeEntry("public-entry",127).isEmpty());
      report.put("status","PASS_NATIVE_ENTRY_BROWSE");
    } catch(Throwable failure) {report.put("status","FAIL_NATIVE_ENTRY_BROWSE");report.put("failure",failure.toString());}
    report.put("checks",checks);report.put("boundary","Retained isolated native PG read-policy fixtures. No actual model call or production workflow success is manufactured. Public business records remain unchanged.");
    Files.writeString(Path.of(output),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    if(!report.get("status").toString().startsWith("PASS"))throw new AssertionError(report.toString());
  }
  public static void main(String[] args) throws Exception {new KnowledgeEntryBrowseProbe().run(args[0]);}
}
