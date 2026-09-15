import cn.lgs.knowledgebase.application.compilation.*;
import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.operations.EnterpriseOperations.EntryQuery;
import cn.lgs.knowledgebase.infrastructure.persistence.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Native PG purpose-separated source proof; all source/model payloads are synthetic fixtures. */
public final class EntryScopeProofPostgresProbe {
  public static void main(String[] args) throws Exception {
    var p=new KnowledgeEntryProbe();var report=new LinkedHashMap<String,Object>();
    try {
      String description="Warranty is 24 months.",anchor="Synthetic agreement SCOPE-COMPONENT-321.";
      p.jdbc.update("UPDATE raw_block SET raw_content=? WHERE block_id='block-public'",description);
      p.jdbc.update("INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES('block-scope','version-public',2,'PARAGRAPH',?,'component-only')",anchor);
      p.jdbc.update("INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash) VALUES('evidence-scope','doc-public','version-public','block-scope','component-only','{}','component-only')");
      p.compilation.replaceDocumentUnits("version-public",List.of(new DocumentUnit("unit-scope","version-public",1,"Synthetic purpose split","single","CONTRACT",1,2)));
      var facts=List.of(fact("description-fact",description,"block-public","evidence-public"),fact("scope-fact",anchor,"block-scope","evidence-scope"));
      var summary=new SummaryRecord("summary-scope","unit-scope","doc-public","version-public","component-only","component-only","PASS","PASS","ACTIVE","SYNTHETIC_COMPONENT_NO_MODEL",description+"\n"+anchor,facts,Instant.now());
      p.compilation.saveSummary(summary);
      var concept=new ConceptRecord("concept-scope",summary.id(),summary.versionId(),"Warranty",List.of(),"CONTRACT",description,List.of("description-fact"),"ACTIVE","SYNTHETIC_COMPONENT_NO_MODEL",Instant.now());
      var scope=GraphScopeIdentity.scope("doc-public",List.of(new GraphScopeIdentity.Anchor("CONTRACT","SCOPE-COMPONENT-321",List.of("scope-fact"))),Map.of("description-fact",facts.get(0),"scope-fact",facts.get(1)));
      var plan=p.entries.prepare(concept,scope,"",List.of());
      p.check("descriptionAndScopeHaveIndependentFactPurposesBeforePublication",plan.entry().sources().get(0).factIds().equals(List.of("description-fact"))&&plan.entry().sources().get(0).scopeFactIds().equals(List.of("scope-fact")));
      var entry=p.publish(summary,concept,plan,List.of());
      p.check("normalOwnedPublicationPersistsBothRolesWithoutChangingConceptFacts",entry.sources().get(0).factIds().equals(List.of("description-fact"))&&entry.sources().get(0).scopeFactIds().equals(List.of("scope-fact"))&&p.compilation.loadActiveConceptsForSummary(summary.id()).get(0).factIds().equals(List.of("description-fact")));
      p.check("completeFrozenSourceIsRetainedForBothPurposeFacts",entry.buildInputs().size()==1&&entry.buildInputs().get(0).summaryId().equals(summary.id())&&entry.sources().get(0).proofFactIds().equals(List.of("description-fact","scope-fact")));
      p.check("projectionIncludesScopeOnlyEvidence",p.jdbc.queryForObject("SELECT count(*) FROM kb_asset_source WHERE asset_id=? AND jsonb_exists(evidence_ids,'evidence-scope')",Integer.class,entry.id())==1);
      var operations=new JdbcEnterpriseOperations(p.jdbc,p.tx,p.mapper,"component-kb");
      var view=operations.knowledgeEntry(entry.id(),10).orElseThrow();
      p.check("nativeReadApiSeparatesDescriptionFactsAndScopeFacts",view.sources().get(0).facts().stream().map(x->x.factId()).toList().equals(List.of("description-fact"))&&view.sources().get(0).scopeFacts().stream().map(x->x.factId()).toList().equals(List.of("scope-fact")));
      p.check("scopeOnlyFactsKeepOriginalRawEvidence",view.sources().get(0).scopeFacts().get(0).evidenceIds().equals(List.of("evidence-scope")));
      // Emulate a retained V23 purpose field in this isolated synthetic schema only.
      p.jdbc.update("UPDATE kb_entry_source SET scope_fact_ids='[]' WHERE entry_id=?",entry.id());
      var original=p.entries.find(entry.id()).orElseThrow();
      var encoded=new java.util.concurrent.atomic.AtomicInteger();var indexed=new java.util.concurrent.atomic.AtomicInteger();
      cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel noModel=(cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel)java.lang.reflect.Proxy.newProxyInstance(EntryScopeProofPostgresProbe.class.getClassLoader(),new Class<?>[]{cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel.class},(proxy,method,values)->{throw new AssertionError("scope repair must not call a model: "+method.getName());});
      var embedding=new cn.lgs.knowledgebase.application.port.Ports.EmbeddingPort(){public int dimensions(){return 3;}public List<List<Float>> embed(List<String> texts){encoded.addAndGet(texts.size());return texts.stream().map(text->List.of(1f,0f,0f)).toList();}};
      cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex index=(cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex)java.lang.reflect.Proxy.newProxyInstance(EntryScopeProofPostgresProbe.class.getClassLoader(),new Class<?>[]{cn.lgs.knowledgebase.application.port.Ports.DerivedSearchIndex.class},(proxy,method,values)->{
        if(!method.getName().equals("prepareConcept"))throw new AssertionError(method.getName());
        p.check("normalRepairProjectionIncludesBothPurposeEvidence",new HashSet<>((List<?>)values[2]).equals(Set.of("evidence-public","evidence-scope")));
        return (Runnable)indexed::incrementAndGet;
      });
      var service=new ConceptCompilationService(p.compilation,noModel,embedding,index,p.entries);
      p.tasks.queue(summary.versionId(),cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType.CONCEPT,"synthetic-projection-repair",Instant.now());
      var owned=p.tasks.claimPending(1,Instant.now()).get(0);
      service.rebuildProjections(summary.id(),p.tasks.publication(owned));p.tasks.complete(owned,"SYNTHETIC_COMPONENT_NO_MODEL",Instant.now());
      var repaired=p.entries.find(entry.id()).orElseThrow();
      p.check("normalOwnedProjectionRepairPublishesNewRevisionWithoutRegeneratingDescription",repaired.revision()==original.revision()+1&&repaired.description().equals(original.description())&&repaired.sources().get(0).factIds().equals(original.sources().get(0).factIds())&&repaired.sources().get(0).scopeFactIds().equals(List.of("scope-fact"))&&encoded.get()==1&&indexed.get()==1);
      p.check("previousRevisionAndOriginalEmptyPurposeRemainAuditable",p.jdbc.queryForObject("SELECT count(*) FROM kb_entry_build_input WHERE entry_id=? AND entry_revision=?",Integer.class,entry.id(),original.revision())==1&&p.jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='ENTRY_SCOPE_PROOF_REBUILT' AND target_id=? AND details_json->>'previousRevision'=? AND details_json->'previousSources'->0->'scopeFactIds'='[]'::jsonb",Integer.class,entry.id(),Long.toString(original.revision()))==1);
      p.jdbc.update("UPDATE kb_entry_source SET scope_fact_ids='[]' WHERE entry_id=?",entry.id());
      p.jdbc.update("UPDATE kb_knowledge_entry SET scope_json=jsonb_set(scope_json,'{anchors,0,value}','\"unsupported-synthetic-anchor\"') WHERE entry_id=?",entry.id());
      p.check("normalRepairRejectsUnstatedStoredAnchorWithoutChangingRevision",p.rejected(()->service.rebuildProjections(summary.id()))&&p.entries.find(entry.id()).orElseThrow().revision()==repaired.revision()&&encoded.get()==1);
      p.jdbc.update("UPDATE kb_knowledge_entry SET scope_json=?::jsonb WHERE entry_id=?",p.mapper.writeValueAsString(repaired.scopeAttributes()),entry.id());
      p.jdbc.update("UPDATE kb_entry_source SET scope_fact_ids='[\"scope-fact\"]' WHERE entry_id=?",entry.id());
      var forged=new GraphScopeIdentity.Scope(scope.key(),scope.attributes(),scope.evidenceIds(),List.of("missing-scope-fact"));
      p.check("scopeProofCannotReferenceFactsOutsideTheFrozenSummary",p.rejected(()->p.entries.prepare(new ConceptRecord("bad-concept",summary.id(),summary.versionId(),"Other warranty",List.of(),"CONTRACT",description,List.of("description-fact"),"ACTIVE","SYNTHETIC_COMPONENT_NO_MODEL",Instant.now()),forged,"",List.of())));
      p.jdbc.update("UPDATE evidence SET active=false WHERE evidence_id='evidence-scope'");
      p.check("normalRepairRejectsUnavailableScopeInputRatherThanGuessingProof",p.rejected(()->service.rebuildProjections(summary.id())));
      p.check("revokingOnlyScopeEvidenceBlocksListAndDetail",operations.knowledgeEntry(entry.id(),127).isEmpty()&&operations.knowledgeEntries(new EntryQuery(1,100,null,"ALL"),127).items().isEmpty());
      p.check("scopeRevocationDoesNotEraseDescriptionOrHistoricalProof",p.jdbc.queryForObject("SELECT count(*) FROM summary_evidence WHERE fact_id='description-fact'",Integer.class)==1&&p.entries.find(entry.id()).orElseThrow().sources().get(0).scopeFactIds().equals(List.of("scope-fact")));
      p.jdbc.update("UPDATE evidence SET active=true WHERE evidence_id='evidence-scope'");
      p.jdbc.update("UPDATE document_metadata SET acl_version=acl_version+1 WHERE version_id='version-public'");
      p.check("sameSourceAclChangeBlocksBothPurposeFacts",operations.knowledgeEntry(entry.id(),127).isEmpty());
      // Existing V23 rows keep an explicit empty default, not invented scope evidence.
      p.check("legacyMigrationDoesNotFabricatePurposeProof",p.jdbc.queryForObject("SELECT scope_fact_ids='[]'::jsonb AND fact_ids='[\"legacy-description\"]'::jsonb FROM kb_entry_source WHERE entry_id='legacy-scope-entry'",Boolean.class));
      report.put("status","PASS_REAL_POSTGRES_SCOPE_PROOF");report.put("entryId",entry.id());
    } catch(Throwable failure) {failure.printStackTrace();report.put("status","FAIL_REAL_POSTGRES_SCOPE_PROOF");report.put("failure",failure.toString());}
    report.put("checks",p.checks);report.put("boundary","Real isolated PostgreSQL, normal catalogue/task owner/publication and API authority. Synthetic exact-copy facts; no real-model/MinIO/Milvus acceptance claim. Existing V23 fixture preserved without scope-proof backfill.");
    p.mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
    if(report.get("status").toString().startsWith("FAIL"))System.exit(1);
  }
  static SummaryFact fact(String id,String text,String block,String evidence) {
    return new SummaryFact(id,"summary-scope","Synthetic purpose split",text,List.of(block),List.of(evidence),"PASS","synthetic exact copy",GroundingConstraintAudit.accepted(text,GroundingConstraintAudit.exactCopyChecks(text,List.of(text))));
  }
}
