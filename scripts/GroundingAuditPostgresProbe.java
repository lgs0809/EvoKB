import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import static cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Retained PostgreSQL schema, actual repository/publication, explicitly synthetic model data. */
public final class GroundingAuditPostgresProbe {
  static List<GroundingJudgeProtocol.Constraint> wireChecks(List<GroundingConstraintAudit.Check> checks) {
    return checks.stream().map(c -> new GroundingJudgeProtocol.Constraint(c.dimension(),c.finding(),c.evidenceQuotes(),c.reason())).toList();
  }
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
  final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
      System.getenv("PROBE_JDBC_URL"), "evokb_target", System.getenv("PROBE_DB_PASSWORD")));
  final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  final Map<String,byte[]> content = new HashMap<>();
  final java.util.concurrent.atomic.AtomicInteger objectReads = new java.util.concurrent.atomic.AtomicInteger();
  final ObjectStore objects = (ObjectStore)Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),new Class<?>[]{ObjectStore.class},(p,m,a)-> {
    if (m.getName().equals("put")) {content.put((String)a[0],((InputStream)a[1]).readAllBytes());return null;}
    if (m.getName().equals("get")) {
      if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        throw new AssertionError("ObjectStore GET must not enter native publication/audit transaction");
      objectReads.incrementAndGet();var b=content.getOrDefault((String)a[0],"# synthetic component".getBytes());return new StoredObject(new ByteArrayInputStream(b),b.length,"text/markdown");}
    throw new UnsupportedOperationException(m.getName());
  });
  final JdbcCompilationRepository repo = new JdbcCompilationRepository(jdbc,tx,mapper,objects);
  final Map<String,Boolean> checks = new LinkedHashMap<>();
  void check(String name, boolean ok) {checks.put(name,ok);if(!ok)throw new AssertionError(name);}
  long count(String sql,Object... args) {return jdbc.queryForObject(sql,Long.class,args);}
  boolean refused(Runnable action) {try{action.run();return false;}catch(RuntimeException e){return true;}}
  String audit = GroundingConstraintAudit.describe("Synthetic exact-copy component decision",
      GroundingConstraintAudit.exactCopyChecks("Synthetic fixture source",List.of("Synthetic fixture source")));
  GroundingConstraintAudit.Audit typedAudit = GroundingConstraintAudit.accepted("Synthetic fixture source",GroundingConstraintAudit.exactCopyChecks("Synthetic fixture source",List.of("Synthetic fixture source")));
  SummaryRecord summary(String id,String version,String suffix) {
    var fact = new SummaryFact(id+"-fact",id,"source", "Synthetic fixture source",
        List.of("block-"+suffix), List.of("evidence-"+suffix),"PASS",audit,typedAudit);
    return new SummaryRecord(id,"unit-"+suffix,"doc-"+suffix,version,"component-only","synthetic",
        "PASS","PASS","ACTIVE","SYNTHETIC_COMPONENT_NO_MODEL","# synthetic",List.of(fact),Instant.now());
  }
  void entryRecovery(SummaryRecord fresh) throws Exception {
    var entries=new JdbcKnowledgeEntryCatalogue(jdbc,tx,mapper,"grounding-component",repo);
    var original=entries.find("legacy-grounding-entry").orElseThrow();
    check("legacyInputRemainsActivePassYetHasNoCurrentAudit",count("SELECT count(*) FROM summary s JOIN summary_fact f USING(summary_id) WHERE s.summary_id='legacy-summary' AND s.lifecycle='ACTIVE' AND s.grounding_status='PASS' AND s.coverage_status='PASS' AND f.grounding_status='PASS' AND f.grounding_audit IS NULL")==1);
    int before=objectReads.get();
    var auditOnly=repo.findSummaryForAudit(fresh.id()).orElseThrow();
    check("auditOnlyRepositoryUsesNativeFactsWithoutRemoteMarkdownRead",auditOnly.markdown().isEmpty()
        && GroundingConstraintAudit.currentSummary(auditOnly,repo.loadVersionSource(fresh.versionId())) && objectReads.get()==before);
    var candidates=entries.candidates(fresh.id(),24);
    check("nativeLegacyCandidateKeepsIdentityButOmitsUnauditedProseAndMembers",candidates.size()==1
        && candidates.get(0).id().equals(original.id()) && !candidates.get(0).completeMetadata()
        && candidates.get(0).description().isEmpty() && candidates.get(0).inputs().isEmpty()
        && candidates.get(0).aliases().equals(List.of("Synthetic")) && objectReads.get()==before);
    check("nativeLegacyCandidateReadDoesNotChangeHistoricRows",entries.find(original.id()).orElseThrow().equals(original)
        && count("SELECT count(*) FROM summary_fact WHERE fact_id='legacy-fact' AND grounding_status='PASS' AND grounding_audit IS NULL")==1);
    check("backgroundAssociationExcludesUnauditedLegacyEntry",entries.relatedCandidates("version-other",24).isEmpty());
    var prompt=new java.util.concurrent.atomic.AtomicReference<String>();
    var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
    KnowledgeModel model=new KnowledgeModel(){
      public String identity(ModelRole role){return role==ModelRole.LARGE ? "SYNTHETIC_WRITER_NO_MODEL" : "SYNTHETIC_JUDGE_NO_MODEL";}
      public String generate(ModelRole role,ModelTask task,String system,String user){throw new UnsupportedOperationException("structured component only");}
      public <T>T generateStructured(ModelRole role,ModelTask task,String system,String user,Class<T> type){
        modelCalls.incrementAndGet();prompt.set(user);
        if(type!=ConceptCompilationService.ConceptProposalSet.class)throw new UnsupportedOperationException(type.toString());
        return type.cast(new ConceptCompilationService.ConceptProposalSet(List.of(
            new ConceptCompilationService.ConceptProposal("Synthetic fixture",List.of(),"CONTRACT","Synthetic fixture source",
                List.of(fresh.facts().get(0).id()),1.0,original.id(),List.of(
                    new GraphScopeIdentity.Anchor("CONTRACT","Synthetic fixture source",List.of(fresh.facts().get(0).id())))))));
      }
    };
    var encoded=new java.util.concurrent.atomic.AtomicInteger();
    var projected=new java.util.concurrent.atomic.AtomicInteger();
    EmbeddingPort embedding=new EmbeddingPort(){public int dimensions(){return 1024;}
      public List<List<Float>> embed(List<String> values){encoded.addAndGet(values.size());return values.stream().map(v->Collections.nCopies(1024,0.1f)).toList();}};
    DerivedSearchIndex index=(DerivedSearchIndex)Proxy.newProxyInstance(DerivedSearchIndex.class.getClassLoader(),new Class<?>[]{DerivedSearchIndex.class},(p,m,a)->{
      if(m.getName().equals("prepareConcept"))return (Runnable)projected::incrementAndGet;
      throw new UnsupportedOperationException(m.getName());
    });
    var tasks=new JdbcGenerationTaskRepository(jdbc,tx,"grounding-component");
    var queued=tasks.queue(fresh.versionId(),cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.CONCEPT,fresh.id(),Instant.now());
    var task=tasks.claimPending(1,Instant.now()).get(0);
    if(!queued.taskId().equals(task.taskId()))throw new AssertionError("owned recovery task was not claimed");
    var compiled=new ConceptCompilationService(repo,model,embedding,index,entries).compileSummary(fresh.id(),tasks.publication(task));
    tasks.complete(task,"SYNTHETIC_COMPONENT_NO_MODEL",Instant.now());
    var rebuilt=entries.find(original.id()).orElseThrow();
    check("actualCompilerReceivesSanitizedCandidateAndNormalAuditedFacts",modelCalls.get()==1
        && !prompt.get().contains("LEGACY_UNREVIEWED_PROSE_SHOULD_NEVER_ENTER_MODEL")
        && !prompt.get().contains("Unrelated stale alias") && prompt.get().contains(original.id())
        && compiled.concepts().size()==1);
    check("normalOwnedPublicationRebuildsSameStableEntryWithCurrentAuditedInput",rebuilt.id().equals(original.id())
        && rebuilt.revision()==original.revision()+1 && rebuilt.summaryIds().equals(List.of(fresh.id()))
        && rebuilt.inputSummaryIds().equals(List.of(fresh.id())) && encoded.get()==1 && projected.get()==1
        && count("SELECT count(*) FROM compile_task WHERE task_id=? AND status='SUCCEEDED' AND attempt=1",task.taskId())==1);
    check("recoveryPreservesLegacyFactAndBuildInputHistory",count("SELECT count(*) FROM summary_fact WHERE fact_id='legacy-fact' AND grounding_status='PASS' AND grounding_audit IS NULL")==1
        && count("SELECT count(*) FROM kb_entry_build_input WHERE entry_id=? AND entry_revision=1 AND summary_id='legacy-summary'",original.id())==1);
    check("rebuiltEntryPassesSharedCurrentAuditAndNormalAssociationSelection",GroundingConstraintAudit.requireCurrentEntry(rebuilt,repo).size()==1
        && entries.relatedCandidates("version-other",24).stream().anyMatch(e->e.id().equals(original.id())));
    var staleInput=new KnowledgeEntryCatalogue.Entry(rebuilt.id(),rebuilt.revision()+1,rebuilt.scopeKey(),rebuilt.scopeAttributes(),rebuilt.type(),rebuilt.label(),rebuilt.aliases(),rebuilt.description(),rebuilt.stage(),rebuilt.lifecycle(),rebuilt.sources(),List.of(new KnowledgeEntryCatalogue.BuildInput("legacy-summary","version-public",1)));
    var rollback=repo.newPublication();rollback.captureInputs(List.of("version-public"));
    check("nativeSelectedLegacyModelInputStillFailsClosedAndDoesNotAdvanceEntry",refused(()->rollback.publish(()->entries.publish(new KnowledgeEntryCatalogue.Publication(staleInput,rebuilt.revision()))))
        && entries.find(original.id()).orElseThrow().equals(rebuilt));
    var missing=new KnowledgeEntryCatalogue.Entry(rebuilt.id(),rebuilt.revision(),rebuilt.scopeKey(),rebuilt.scopeAttributes(),rebuilt.type(),rebuilt.label(),rebuilt.aliases(),rebuilt.description(),rebuilt.stage(),rebuilt.lifecycle(),rebuilt.sources(),List.of(new KnowledgeEntryCatalogue.BuildInput("missing-summary","version-public",1)));
    check("sharedGuardRejectsMissingRetainedInputRatherThanSilentlyDroppingIt",refused(()->GroundingConstraintAudit.requireCurrentEntry(missing,repo)));
  }

  void completeInputAuthority(SummaryRecord cited, SummaryRecord uncited) throws Exception {
    var operations=new JdbcEnterpriseOperations(jdbc,tx,mapper);
    check("legacyWikiWithSomeCitationsAndDependenciesStillHasNoCompleteCertification",
        count("SELECT count(*) FROM wiki WHERE wiki_id='legacy-wiki' AND lifecycle='ACTIVE' AND input_policy_version IS NULL")==1
        &&operations.wiki("legacy-wiki",1L,127).isEmpty()
        &&new JdbcWikiKeywordProjectionRepository(jdbc,tx,mapper).loadBuildInputVersions("legacy-wiki",1).isEmpty());
    var entry=new KnowledgeEntryCatalogue.Entry("native-inputs-entry",1,"synthetic-native-scope",Map.of(),
        "CONTRACT","Synthetic complete-input Wiki",List.of(),"Synthetic grounded identity","CONCEPT","ACTIVE",
        List.of(new KnowledgeEntryCatalogue.Source(cited.documentId(),cited.versionId(),cited.id(),"synthetic-cited-concept",1,
            cited.facts().stream().map(SummaryFact::id).toList())),
        List.of(new KnowledgeEntryCatalogue.BuildInput(cited.id(),cited.versionId(),1),
            new KnowledgeEntryCatalogue.BuildInput(uncited.id(),uncited.versionId(),1)));
    var writerPrompt=new java.util.concurrent.atomic.AtomicReference<String>();
    KnowledgeModel model=new KnowledgeModel(){
      public String identity(ModelRole role){return role==ModelRole.LARGE ? "SYNTHETIC_WRITER_NO_MODEL" : "SYNTHETIC_JUDGE_NO_MODEL";}
      public String generate(ModelRole role,ModelTask task,String system,String user){throw new UnsupportedOperationException("structured only");}
      public <T>T generateStructured(ModelRole role,ModelTask task,String system,String user,Class<T> type){
        if(type==WikiCompilationService.Proposal.class){writerPrompt.set(user);return type.cast(new WikiCompilationService.Proposal(
            "CONTRACT",entry.label(),List.of(new WikiCompilationService.ProposedClaim("source","Synthetic fixture source",List.of("F001"))),
            List.of(new WikiCompilationService.FactDecision("F001",true,""))));}
        if(type==GroundingJudgeProtocol.Decision.class)return type.cast(new GroundingJudgeProtocol.Decision(
            true,"Synthetic full-candidate support",wireChecks(GroundingConstraintAudit.exactCopyChecks("Synthetic fixture source",List.of("Synthetic fixture source")))));
        throw new UnsupportedOperationException(type.toString());
      }
    };
    var catalogue=new JdbcAssetCatalogue(jdbc,tx);
    var prepared=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
    var preparedContext=new java.util.concurrent.atomic.AtomicReference<DerivedContext>();
    EmbeddingPort embedding=new EmbeddingPort(){public int dimensions(){return 1024;}
      public List<List<Float>> embed(List<String> values){return values.stream().map(v->Collections.nCopies(1024,0.1f)).toList();}};
    DerivedSearchIndex index=(DerivedSearchIndex)Proxy.newProxyInstance(DerivedSearchIndex.class.getClassLoader(),new Class<?>[]{DerivedSearchIndex.class},(p,m,a)->{
      if(!m.getName().equals("prepareWiki"))throw new UnsupportedOperationException(m.getName());
      var page=(cn.lgs.knowledgebase.domain.compilation.CompilationDomain.WikiPage)a[0];var context=(DerivedContext)a[1];preparedContext.set(context);
      var attributes=catalogue.stage(page.id(),"WIKI",page.content(),page.claims().stream().flatMap(c->c.evidenceIds().stream()).distinct().toList(),context);prepared.set(attributes);
      return (Runnable)()->catalogue.publish(page.id(),((Number)attributes.get("asset_version")).longValue());
    });
    String kb="complete-inputs-component";
    var tasks=new JdbcGenerationTaskRepository(jdbc,tx,kb);
    var old=jdbc.queryForMap("SELECT task_id,status,idempotency_key FROM compile_task WHERE task_id='legacy-input-policy-wiki-task'");
    var queued=tasks.queue(cited.versionId(),cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.WIKI,
        "entry/"+entry.id()+"/revision/1",Instant.now());
    var observed=tasks.queue(cited.versionId(),cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.WIKI,
        "entry/"+entry.id()+"/revision/1",Instant.now());
    check("completeInputPolicyQueuesNewNormalTaskAndPreservesOldSuccessIdentity",
        !queued.taskId().equals(old.get("task_id"))&&queued.taskId().equals(observed.taskId())
        &&jdbc.queryForObject("SELECT idempotency_key FROM compile_task WHERE task_id=?",String.class,queued.taskId()).equals(
            cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.sha256(kb.length()+":"+kb+":generation:wiki:"
                +generationPolicy(cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.WIKI)
                +":"+cited.versionId()+":entry/"+entry.id()+"/revision/1"))
        &&jdbc.queryForMap("SELECT task_id,status,idempotency_key FROM compile_task WHERE task_id='legacy-input-policy-wiki-task'").equals(old));
    // A separate KB preserves the earlier pending Summary fixture and its writer ownership.
    var claimed=tasks.claimPending(10,Instant.now());
    var task=claimed.stream().filter(t->t.taskId().equals(queued.taskId())).findFirst().orElseThrow();
    var result=new WikiCompilationService(repo,model,objects,embedding,index).compileEntry(entry,tasks.publication(task),ignored->{});
    tasks.complete(task,result.wiki().contentHash(),Instant.now());
    check("actualEntryCompilerCitesOnlyPublicFactsButPersistsAllModelInputs",
        result.wiki().claims().get(0).evidenceIds().equals(List.of("evidence-public"))
        &&!writerPrompt.get().contains(uncited.facts().get(0).id())
        &&count("SELECT count(*) FROM kb_asset_build_dependency WHERE asset_type='WIKI' AND asset_id=? AND build_revision='1'",entry.id())==2
        &&count("SELECT count(*) FROM compile_task_input WHERE task_id=? AND execution_epoch=?",task.taskId(),task.executionEpoch())==2);
    check("newWikiInputCertificationIsNativeAndIndependentOfClaimCitationCount",
        count("SELECT count(*) FROM wiki WHERE wiki_id=? AND input_policy_version=? AND input_source_count=2",entry.id(),COMPLETE_INPUT_POLICY)==1
        &&count("SELECT count(*) FROM wiki_evidence we JOIN wiki_claim wc USING(claim_id) WHERE wc.wiki_id=?",entry.id())==1);
    check("uncitedRestrictedInputBlocksNativeDirectAndPagedWikiAccess",
        operations.wiki(entry.id(),1L,10).isEmpty()&&operations.wiki(entry.id(),1L,30).isPresent()
        &&operations.wikis(new cn.lgs.knowledgebase.application.operations.EnterpriseOperations.PageQuery(1,30,entry.label(),null,null,null,null,null,null,null,null,null,null,null,null,null,null),10).total()==0
        &&operations.wikis(new cn.lgs.knowledgebase.application.operations.EnterpriseOperations.PageQuery(1,30,entry.label(),null,null,null,null,null,null,null,null,null,null,null,null,null,null),30).total()==1);
    check("documentCountsUseSameFullWikiAuthorityBeforeCounting",
        operations.documentDetail(cited.versionId(),10,10).orElseThrow().knowledge().wikiClaims()==1
        &&operations.documentDetail(cited.versionId(),10,30).orElseThrow().knowledge().wikiClaims()==2);
    var revision=((Number)prepared.get().get("asset_version")).longValue();
    check("nativeWikiPhysicalRevisionCarriesSameCompletePolicyAndAllInputAcl",
        preparedContext.get().versionIds().containsAll(List.of(cited.versionId(),uncited.versionId()))
        &&count("SELECT count(*) FROM kb_derived_asset WHERE asset_id=? AND asset_version=? AND input_policy_version=? AND input_source_count=2 AND publication_state='PUBLISHED'",entry.id(),revision,COMPLETE_INPUT_POLICY)==1
        &&catalogue.currentSources(entry.id(),revision,new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters(null,null,null,List.of())).isEmpty()
        &&catalogue.currentSources(entry.id(),revision,new cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.SearchFilters(null,null,null,List.of()).withClearance(30)).isPresent());
    check("keywordProjectionInheritsAllWikiInputsRatherThanOnlyCitations",
        new HashSet<>(new JdbcWikiKeywordProjectionRepository(jdbc,tx,mapper).loadBuildInputVersions(entry.id(),1)).equals(Set.of(cited.versionId(),uncited.versionId())));
    check("newFullyPublicCertifiedWikiRemainsUsable",operations.wiki("audit-wiki",1L,10).isPresent());
    // A retained synthetic fault proves the input-count guard without deleting business data.
    jdbc.update("DELETE FROM kb_asset_build_dependency WHERE asset_type='WIKI' AND asset_id=? AND source_version_id=?",entry.id(),uncited.versionId());
    check("partialPersistedWikiSnapshotCannotBypassCompleteInputPolicy",
        operations.wiki(entry.id(),1L,127).isEmpty()&&new JdbcWikiKeywordProjectionRepository(jdbc,tx,mapper).loadBuildInputVersions(entry.id(),1).isEmpty());
  }
  void run(String output) throws Exception {
    var report = new LinkedHashMap<String,Object>();
    try {
      check("upgradePreservesLegacyPassAndDoesNotFabricateAudit",
          count("SELECT count(*) FROM summary_fact WHERE fact_id='legacy-fact' AND grounding_status='PASS' AND grounding_reason=''")==1
          && count("SELECT count(*) FROM wiki_claim WHERE claim_id='legacy-claim' AND grounding_status='PASS' AND grounding_reason=''")==1);
      check("repositoryReadsHistoricalEmptyAudit",repo.findSummary("legacy-summary").orElseThrow().facts().get(0).groundingReason().isEmpty());
      var publication=repo.newPublication();publication.captureInputs(List.of("version-public"));
      var fresh=summary("audit-summary","version-public","public");
      publication.publish(()->repo.saveSummary(fresh));
      check("summaryAuditRoundTripsThroughActualRepository",repo.findSummary(fresh.id()).orElseThrow().facts().get(0).groundingReason().equals(audit));
      check("structuredAuditRoundTripsAndRevalidatesCurrentNativeSource",
          typedAudit.equals(repo.findSummary(fresh.id()).orElseThrow().facts().get(0).groundingAudit())
          && GroundingConstraintAudit.currentSummary(repo.findSummary(fresh.id()).orElseThrow(),repo.loadVersionSource("version-public")));
      check("legacyReasonCannotBecomeTypedProof",repo.findSummary("legacy-summary").orElseThrow().facts().get(0).groundingAudit()==null
          && !GroundingConstraintAudit.currentSummary(repo.findSummary("legacy-summary").orElseThrow(),repo.loadVersionSource("version-public")));
      check("summaryAuditContainsPolicyAndLocatedConstraints",count("SELECT count(*) FROM summary_fact WHERE fact_id=? AND grounding_reason=?",fresh.facts().get(0).id(),audit)==1);
      var claim = new WikiClaim("audit-claim","audit-wiki",1,"source","Synthetic fixture source",
          List.of(fresh.facts().get(0).id()),List.of("evidence-public"),"PASS","PASS",audit,typedAudit);
      var wiki = new WikiRecord("audit-wiki",1,"CONTRACT","","Synthetic audit Wiki","component-only","synthetic",
          "ACTIVE","# synthetic",List.of(claim),List.of(),List.of(),Instant.now());
      publication.publish(()->repo.saveWiki(wiki,List.of(fresh.id())));
      check("wikiStructuredAuditRetainsClaimAndCompleteChecks",count("SELECT count(*) FROM wiki_claim WHERE claim_id=? AND grounding_audit->>'policy_version'=? AND jsonb_array_length(grounding_audit->'checks')=7",claim.id(),GroundingConstraintAudit.POLICY_VERSION)==1);
      entryRecovery(fresh);
      var tasks = new JdbcGenerationTaskRepository(jdbc,tx,"enterprise");
      var currentTask=tasks.queue("version-public",cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.SUMMARY,"synthetic-legacy-policy",Instant.now());
      var repeatedTask=tasks.queue("version-public",cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.SUMMARY,"synthetic-legacy-policy",Instant.now());
      check("currentPolicyQueuesNewTaskAndKeepsOldSuccessHistory",
          !currentTask.taskId().equals("legacy-generation-task") && currentTask.taskId().equals(repeatedTask.taskId())
          && count("SELECT count(*) FROM compile_task WHERE task_id='legacy-generation-task' AND status='SUCCEEDED' AND attempt=1")==1);
      var keywordRepo=new JdbcWikiKeywordProjectionRepository(jdbc,tx,mapper);
      var currentClaims=keywordRepo.loadClaims("audit-wiki",1);
      check("nativeWikiProjectionReadsAndRechecksBothClaimAndSupportingFactAudits",
          currentClaims.size()==1 && currentClaims.get(0).sourceFactsAudited()
          && GroundingConstraintAudit.current(currentClaims.get(0).claimText(),currentClaims.get(0).supportingText(),currentClaims.get(0).groundingAudit()));
      check("legacyWikiProjectionCannotCertifyOldPass",keywordRepo.loadClaims("legacy-wiki",1).stream().allMatch(c -> !c.sourceFactsAudited() && c.groundingAudit()==null));
      check("wikiAuditPersistsInExistingPublicationTransaction",count("SELECT count(*) FROM wiki_claim WHERE claim_id=? AND grounding_reason=?",claim.id(),audit)==1);
      var operations=new JdbcEnterpriseOperations(jdbc,tx,mapper);
      var visible=operations.wiki("audit-wiki",1L,10).orElseThrow();
      check("ordinaryPublicDtoDoesNotExposeInternalAuditQuotes",!mapper.writeValueAsString(visible).contains("grounding_reason")
          && !mapper.writeValueAsString(visible).contains(GroundingConstraintAudit.POLICY_VERSION));
      var secretPublication=repo.newPublication();secretPublication.captureInputs(List.of("version-secret"));
      var secretSummary=summary("audit-secret-summary","version-secret","secret");
      var secretClaim=new WikiClaim("audit-secret-claim","audit-secret-wiki",1,"source","Synthetic fixture source",
          List.of(secretSummary.facts().get(0).id()),List.of("evidence-secret"),"PASS","PASS",audit,typedAudit);
      var secretWiki=new WikiRecord("audit-secret-wiki",1,"CONTRACT","","Synthetic secret Wiki","component-only","synthetic",
          "ACTIVE","# synthetic",List.of(secretClaim),List.of(),List.of(),Instant.now());
      secretPublication.publish(()->{repo.saveSummary(secretSummary);repo.saveWiki(secretWiki,List.of(secretSummary.id()));});
      check("auditedSecretAssetIsStillInvisibleToPublicReader",operations.wiki("audit-secret-wiki",1L,10).isEmpty()
          && operations.wiki("audit-secret-wiki",1L,30).isPresent());
      completeInputAuthority(fresh,secretSummary);
      var rollback=repo.newPublication();rollback.captureInputs(List.of("version-other"));
      check("failedPublicationRollsBackFactAndAuditTogether",refused(()->rollback.publish(()-> {
        repo.saveSummary(summary("rollback-summary","version-other","other"));throw new IllegalStateException("synthetic commit failure");
      })) && count("SELECT count(*) FROM summary_fact WHERE fact_id='rollback-summary-fact'")==0
        && count("SELECT count(*) FROM summary WHERE summary_id='rollback-summary'")==0);
      var secret=repo.newPublication();secret.captureInputs(List.of("version-secret"));
      jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-secret'");
      check("changedSourceAclPreventsAuditWrite",refused(()->secret.publish(()->repo.saveSummary(summary("revoked-summary","version-secret","secret"))))
          && count("SELECT count(*) FROM summary_fact WHERE fact_id='revoked-summary-fact'")==0);
      var unknown = new ArrayList<>(GroundingConstraintAudit.exactCopyChecks("Synthetic fixture source",List.of("Synthetic fixture source")));
      unknown.set(0,new GroundingConstraintAudit.Check(GroundingConstraintAudit.Dimension.SUBJECT,
          GroundingConstraintAudit.Finding.UNKNOWN,"Synthetic fixture source",List.of(),"Synthetic unresolved subject"));
      check("unknownModelVerdictCannotAuthorizePublication",!GroundingConstraintAudit.supported("Synthetic fixture source","Synthetic fixture source",true,unknown));
      var calls=new java.util.concurrent.atomic.AtomicInteger();
      KnowledgeModel unresolvedModel=new KnowledgeModel() {
        public String identity(ModelRole role){return role==ModelRole.LARGE ? "SYNTHETIC_WRITER" : "SYNTHETIC_JUDGE";}
        public String generate(ModelRole role,ModelTask task,String system,String user){throw new UnsupportedOperationException("component structured only");}
        public <T>T generateStructured(ModelRole role,ModelTask task,String system,String user,Class<T> type){
          Object value;
          if(type==SummaryCompilationService.Classification.class) value=new SummaryCompilationService.Classification("general","single",List.of());
          else if(type==SummaryCompilationService.FactProposalSet.class) value=new SummaryCompilationService.FactProposalSet(
              List.of(new SummaryCompilationService.FactProposal("source","Synthetic fixture source",List.of("block-other"))));
          else if(type==GroundingJudgeProtocol.Decision.class) value=new GroundingJudgeProtocol.Decision(true,"Synthetic unresolved subject",wireChecks(unknown));
          else if(type==SummaryCompilationService.CoverageDecision.class) value=new SummaryCompilationService.CoverageDecision(List.of());
          else throw new UnsupportedOperationException(type.toString());
          return type.cast(value);
        }
      };
      EmbeddingPort noModel=new EmbeddingPort(){public int dimensions(){return 1024;}
        public List<List<Float>> embed(List<String> text){calls.incrementAndGet();return text.stream().map(t->Collections.nCopies(1024,0.1f)).toList();}};
      DerivedSearchIndex projection=(DerivedSearchIndex)Proxy.newProxyInstance(DerivedSearchIndex.class.getClassLoader(),new Class<?>[]{DerivedSearchIndex.class},
          (proxy,method,arguments)-> {calls.incrementAndGet();if(method.getReturnType()==Runnable.class)return (Runnable)()->{};if(method.getReturnType()==List.class)return List.of();return null;});
      try {new SummaryCompilationService(repo,unresolvedModel,objects,noModel,projection).compileVersion("version-other");}
      catch(RuntimeException expected) {report.put("unresolvedCompilerRejection",expected.getClass().getSimpleName());}
      check("actualCompilerDoesNotPublishOrEncodeUnknownFact",count("SELECT count(*) FROM summary WHERE version_id='version-other' AND lifecycle='ACTIVE'")==0
          && count("SELECT count(*) FROM summary_fact f JOIN summary s USING(summary_id) WHERE s.version_id='version-other' AND f.grounding_status='PASS'")==0
          && calls.get()==0);
      check("originalLegacyHistoryRemainsWithoutRelabeling",count("SELECT count(*) FROM summary_fact WHERE fact_id='legacy-fact' AND grounding_status='PASS' AND grounding_reason=''")==1);
      report.put("status","PASS_POSTGRES_GROUNDING_AUDIT_COMPONENT");
    } catch(Throwable failure) {
      failure.printStackTrace();report.put("status","FAIL_POSTGRES_GROUNDING_AUDIT_COMPONENT");report.put("failure",failure.toString());
    }
    report.put("checks",checks);report.put("policyVersion",GroundingConstraintAudit.POLICY_VERSION);
    report.put("boundary","Real retained PostgreSQL upgrade from pre-audit schema, repository roundtrip and publication rollback/ACL fence. Model/audit/object content is explicitly synthetic; no production task state, model call, MinIO, Milvus or UI workflow acceptance is claimed.");
    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  public static void main(String[] args)throws Exception {new GroundingAuditPostgresProbe().run(args[0]);}
}
