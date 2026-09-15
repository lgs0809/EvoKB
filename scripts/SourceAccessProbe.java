import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import cn.lgs.knowledgebase.infrastructure.search.JdbcProjectionAuthority;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL read/build contracts; component fixtures are separate from user workflows. */
public class SourceAccessProbe {
  static final Map<String,Boolean> checks=new LinkedHashMap<>();
  static void check(String name,boolean pass){checks.put(name,pass);if(!pass)throw new AssertionError(name);}
  static WikiRecord wiki(String id){return new WikiRecord(id,1,"CONTRACT","","仅引用公开资料","component-only","component-only","ACTIVE","公开资料",List.of(new WikiClaim(id+"-claim",id,1,"公开条款","公开信息",List.of("fact-public"),List.of("evidence-public"),"PASS","PASS")),List.of(),List.of(),Instant.now());}
  public static void main(String[] args)throws Exception{
    var mapper=new ObjectMapper().findAndRegisterModules();var report=new LinkedHashMap<String,Object>();
    try {
      var ds=new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"evokb_target",System.getenv("PROBE_DB_PASSWORD"));
      var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
      var repo=new JdbcCompilationRepository(jdbc,tx,mapper,null);var reads=new JdbcEnterpriseOperations(jdbc,tx,mapper);
      var authority=new JdbcProjectionAuthority(jdbc);var low=new SearchFilters(null,null,null,List.of());var high=low.withClearance(30);
      repo.saveWiki(wiki("mixed-wiki"),List.of("summary-public","summary-secret"));
      check("actualUncitedBuildInputPersisted",jdbc.queryForObject("SELECT count(*) FROM kb_asset_build_dependency WHERE asset_id='mixed-wiki'",Integer.class)==2);
      check("publicCitationDoesNotGrantMixedWikiAccess",reads.wiki("mixed-wiki",1L,10).isEmpty());
      check("trustedClearanceReadsMixedWiki",reads.wiki("mixed-wiki",1L,30).isPresent());
      var page=new EnterpriseOperations.PageQuery(1,30,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
      check("wikiListingFiltersBeforeCountAndLimit",reads.wikis(page,10).total()==0&&reads.wikis(page,30).total()==1);
      repo.saveWiki(wiki("public-wiki"),List.of("summary-public"));
      check("publicBuildRemainsUsable",reads.wiki("public-wiki",1L,10).isPresent());
      repo.saveWiki(wiki("unknown-inputs"));
      check("legacyWikiWithoutActualBuildInputsFailsClosed",reads.wiki("unknown-inputs",1L,127).isEmpty());
      boolean missing=false;try{repo.saveWiki(wiki("missing-summary"),List.of("summary-public","missing"));}catch(IllegalStateException expected){missing=true;}
      check("missingSourceRollsBackPublication",missing&&jdbc.queryForObject("SELECT count(*) FROM wiki WHERE wiki_id='missing-summary'",Integer.class)==0);
      check("directEvidenceAndSourceUseSameAuthority",reads.evidence("evidence-secret",10).isEmpty()&&reads.evidenceSources(List.of("evidence-secret"),10).isEmpty()&&reads.evidence("evidence-secret",30).isPresent());
      check("documentDetailCannotBypassClearance",reads.documentDetail("version-secret",20,10).isEmpty()&&reads.documentDetail("version-secret",20,30).isPresent());
      var evaluation = new JdbcArtifactEvaluationRepository(jdbc);
      check("scopedEvaluationRejectsUncitedPrivateWikiInputs", evaluation.load(List.of("version-public"),10).wikiClaims().size()==1 && evaluation.load(List.of("version-public"),30).wikiClaims().size()==2);
      var snapshot=evaluation.loadScoped(List.of("version-public"),30);
      check("evaluationSnapshotCarriesAllUncitedWikiInputs", snapshot.sourceVersionIds().containsAll(List.of("version-public","version-secret")) && snapshot.artifacts().wikiClaims().size()==2);
      jdbc.update("UPDATE summary SET grounding_status='FAIL' WHERE summary_id='summary-secret'");
      jdbc.update("INSERT INTO compile_task(task_id,version_id,task_type,idempotency_key,status,error_message) VALUES('private-task','version-secret','SUMMARY','private-task','FAILED','Synthetic confidential error text')");
      check("taskAuthorityFiltersBeforeCountAndLimit", reads.tasks(page,"user-a",true,"COMPILE",10).total()==0 && reads.tasks(page,"user-a",true,"COMPILE",30).total()==1);
      check("qualityCountsAndIssuesUseCurrentAuthority", reads.quality(30,10).summaryGroundingFailures()==0 && reads.quality(30,10).failedGenerationTasks()==0 && reads.quality(30,30).summaryGroundingFailures()==1 && reads.quality(30,30).failedGenerationTasks()==1);
      jdbc.update("INSERT INTO audit_event(event_id,actor_user_id,actor_username,actor_role,action,target_type,target_id,target_name) VALUES('private-audit','user-a','Reader','ADMIN','component.read','document_version','version-secret','Synthetic private filename'),('public-audit','user-b','Other','ADMIN','component.read','document_version','version-public','Public filename')");
      check("auditOwnPriorActionDoesNotBypassCurrentDocumentAuthority", reads.audit(page,"user-a",10).total()==1 && reads.audit(page,"user-a",30).total()==2);
      jdbc.update("INSERT INTO enterprise_user(user_id,username,display_name,password_hash) VALUES('user-a','component_a','Component A','not-a-login-hash'),('user-b','component_b','Component B','not-a-login-hash')");
      var mcp = new JdbcMcpSessionRepository(jdbc); mcp.bind("protocol-component","user-a");
      check("mcpProtocolSessionCannotCrossAccounts",mcp.owned("protocol-component","user-a") && !mcp.owned("protocol-component","user-b"));
      jdbc.update("UPDATE mcp_session_access SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE session_id='protocol-component'");
      check("mcpBindingExpiresByDatabaseClock",!mcp.owned("protocol-component","user-a"));
      mcp.remove("protocol-component","user-b");
      check("foreignAccountCannotRemoveMcpBinding",jdbc.queryForObject("SELECT count(*) FROM mcp_session_access",Integer.class)==1);
      mcp.remove("protocol-component","user-a");
      check("ownedMcpDeletionRemovesBinding",jdbc.queryForObject("SELECT count(*) FROM mcp_session_access",Integer.class)==0);
      jdbc.update("UPDATE document_metadata SET security_level=40,acl_version=acl_version+1 WHERE version_id='version-secret'");
      check("wikiRevokedByAnyBuildInputAclChange",reads.wiki("mixed-wiki",1L,127).isEmpty()&&reads.wiki("public-wiki",1L,10).isPresent());
      jdbc.update("INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('public-successor','doc-public',2,'ACTIVE','component.md',0,'component-v2','component-only')");
      jdbc.update("INSERT INTO document_metadata(version_id,security_level) VALUES('public-successor',30)");
      check("newCurrentClassificationAlsoProtectsOlderSourceVersions",!authority.accessible(List.of("version-public"),low)&&authority.accessible(List.of("version-public"),high)&&reads.evidence("evidence-public",10).isEmpty());
      check("oldActiveVersionCannotLeakThroughDocumentListing",reads.documents(page,10).total()==1 && reads.documents(page,10).items().stream().noneMatch(d->d.versionId().equals("version-public") || d.versionId().equals("public-successor")));
      report.put("status","PASS_REAL_POSTGRES_SOURCE_AND_BUILD_AUTHORITY");
    }catch(Throwable failure){failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_SOURCE_AND_BUILD_AUTHORITY");report.put("failureType",failure.getClass().getName());report.put("failure",failure.getMessage());}
    report.put("checks",checks);report.put("boundary","Real JDBC repositories against isolated PostgreSQL fixtures. Does not certify real Wiki generation, stable Concept promotion, source catalog or deadline publication protocol.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
}
