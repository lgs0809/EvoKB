package cn.lgs.knowledgebase.infrastructure.model;

import static cn.lgs.knowledgebase.application.port.Ports.ModelRole.LARGE;
import static cn.lgs.knowledgebase.application.port.Ports.ModelRole.SMALL;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.CONCEPT_EXTRACT;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.RELATION_EXTRACT;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.SUMMARY;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.SUMMARY_JUDGE;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.WIKI_JUDGE;
import static cn.lgs.knowledgebase.application.port.Ports.ModelTask.WIKI_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lgs.knowledgebase.application.compilation.ConceptCompilationService.ConceptProposalSet;
import cn.lgs.knowledgebase.application.compilation.GraphCompilationService.Extraction;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.CoverageDecision;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.FactProposalSet;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.SupportDecision;
import cn.lgs.knowledgebase.application.compilation.WikiCompilationService.Proposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AssistantEvidenceFallbackKnowledgeModelTest {
  private static final String BLOCKS =
      "[b1 | block_no=1] 第一章\n"
          + "[b2 | block_no=2] 公司在2024年完成了完整披露。\n"
          + "[b3 | block_no=3] 第二章\n"
          + "[b4 | block_no=4] 董事会审议了预算方案，金额为100万元。\n";

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void projectsCompleteRawBlocksIntoGroundedFacts() {
    var model = new AssistantEvidenceFallbackKnowledgeModel(new Delegate(), mapper, "always");

    FactProposalSet result =
        model.generateStructured(LARGE, SUMMARY, "", BLOCKS, FactProposalSet.class);

    assertEquals(2, result.facts().size());
    assertEquals("b2", result.facts().get(0).evidenceBlockIds().get(0));
    assertEquals("公司在2024年完成了完整披露。", result.facts().get(0).factText());
    assertEquals("b4", result.facts().get(1).evidenceBlockIds().get(0));
    assertTrue(result.facts().get(1).factText().contains("100万元"));
    assertTrue(
        model
            .generateStructured(SMALL, SUMMARY_JUDGE, "", "PASS FACTS=", CoverageDecision.class)
            .missing()
            .isEmpty());
  }

  @Test
  void fansOutConceptsByFactSectionWithoutInventingRelations() {
    var model = new AssistantEvidenceFallbackKnowledgeModel(new Delegate(), mapper, "always");
    String facts =
        "<fact id=\"f1\" section=\"治理\">\n董事会审议了预算。\n</fact>\n"
            + "<fact id=\"f2\" section=\"治理\">\n监事会完成了监督。\n</fact>\n"
            + "<fact id=\"f3\" section=\"财务\">\n收入为100万元。\n</fact>\n";

    ConceptProposalSet concepts =
        model.generateStructured(LARGE, CONCEPT_EXTRACT, "", facts, ConceptProposalSet.class);
    Extraction extraction =
        model.generateStructured(LARGE, RELATION_EXTRACT, "", facts, Extraction.class);

    assertEquals(2, concepts.concepts().size());
    assertEquals(2, concepts.concepts().get(0).supportingFactIds().size());
    assertEquals(3, extraction.factDecisions().size());
    assertTrue(extraction.relations().isEmpty());
    assertTrue(extraction.factDecisions().stream().allMatch(decision -> !decision.graphEligible()));
  }

  @Test
  void writesWikiClaimsAsExactFactCopies() {
    var model = new AssistantEvidenceFallbackKnowledgeModel(new Delegate(), mapper, "always");
    String facts =
        "<fact id=\"f1\" section=\"治理\">\n董事会审议了预算。\n</fact>\n"
            + "<fact id=\"f2\" section=\"财务\">\n收入为100万元。\n</fact>\n";

    Proposal proposal =
        model.generateStructured(LARGE, WIKI_WRITE, "wiki_type 使用 COMPANY", facts, Proposal.class);
    var judged =
        model.generateStructured(
            SMALL,
            WIKI_JUDGE,
            "",
            "CLAIM=董事会审议了预算。\nSUPPORTING FACTS:\n[f1] 董事会审议了预算。",
            cn.lgs.knowledgebase.application.compilation.WikiCompilationService.SupportDecision
                .class);

    assertEquals("COMPANY", proposal.wikiType());
    assertEquals(2, proposal.claims().size());
    assertEquals("收入为100万元。", proposal.claims().get(1).claimText());
    assertEquals(2, proposal.factDecisions().size());
    assertTrue(judged.supported());
  }

  @Test
  void exposesFallbackIdentityAndCanFallbackOnlyOnTransientFailures() {
    AtomicInteger calls = new AtomicInteger();
    Delegate delegate = new Delegate(calls, true);
    var model = new AssistantEvidenceFallbackKnowledgeModel(delegate, mapper, "on-failure");

    SupportDecision result =
        model.generateStructured(
            SMALL, SUMMARY_JUDGE, "", "FACT: x\nEVIDENCE:\ny", SupportDecision.class);

    assertTrue(result.supported());
    assertTrue(
        model.identity(SMALL).contains(AssistantEvidenceFallbackKnowledgeModel.IDENTITY_MARKER));
    assertEquals(1, calls.get());
  }

  private static final class Delegate
      implements cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel {
    private final AtomicInteger calls;
    private final boolean transientFailure;

    private Delegate() {
      this(new AtomicInteger(), false);
    }

    private Delegate(AtomicInteger calls, boolean transientFailure) {
      this.calls = calls;
      this.transientFailure = transientFailure;
    }

    @Override
    public String identity(cn.lgs.knowledgebase.application.port.Ports.ModelRole role) {
      return role == LARGE ? "gpt-5.6-terra" : "gpt-5.6-luna";
    }

    @Override
    public String generate(
        cn.lgs.knowledgebase.application.port.Ports.ModelRole role,
        cn.lgs.knowledgebase.application.port.Ports.ModelTask task,
        String systemPrompt,
        String userPrompt) {
      return "delegate";
    }

    @Override
    public <T> T generateStructured(
        cn.lgs.knowledgebase.application.port.Ports.ModelRole role,
        cn.lgs.knowledgebase.application.port.Ports.ModelTask task,
        String systemPrompt,
        String userPrompt,
        Class<T> outputType) {
      calls.incrementAndGet();
      if (transientFailure) throw new IllegalStateException("SSLHandshakeException: transient");
      throw new IllegalStateException("unexpected delegate call");
    }
  }
}
