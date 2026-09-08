package cn.lgs.knowledgebase.application.ask;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.query.QueryEvidenceContext;
import cn.lgs.knowledgebase.application.retrieval.RetrievalApplicationService;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import cn.lgs.knowledgebase.domain.retrieval.RetrievalDomain.Quotas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnswerRevisionTest {
  private final List<String> prompts = new ArrayList<>();
  private final List<ModelRole> roles = new ArrayList<>();
  private int verifications;
  private final List<AskApplicationService.AnswerRevision> revisions = new ArrayList<>();
  private final QueryEvidenceContext.Selection selection =
      new QueryEvidenceContext.Selection(
          List.of(
              new QueryEvidenceContext.Source(
                  1,
                  "raw",
                  new Evidence(
                      "e",
                      "d",
                      "v",
                      "b",
                      "o",
                      Map.of(),
                      "书面验收2028-02-27，发票取得2028-02-29，两项满足后3日内付款。未定义计日口径，未记录到账。",
                      "h"))),
          "[SOURCE 1]\n原文：书面验收2028-02-27，发票取得2028-02-29，两项满足后3日内付款。未定义计日口径，未记录到账。\n",
          120);

  private AskApplicationService service(boolean scopeValid, boolean secondAccepted) {
    return service(scopeValid, secondAccepted, false);
  }

  private AskApplicationService service(
      boolean scopeValid, boolean secondAccepted, boolean firstUncited) {
    var model =
        new KnowledgeModel() {
          public String identity(ModelRole role) {
            return "scripted-protocol-fixture";
          }

          public String generate(ModelRole role, ModelTask task, String system, String prompt) {
            assertEquals(ModelTask.ASK, task);
            prompts.add(prompt);
            roles.add(role);
            return prompts.size() == 1
                ? (firstUncited ? "期限确定为2028-03-03。" : "期限确定为2028-03-03。[1]")
                : "原文未定义计日口径；若按自然日、次日起算，较晚的2028-02-29加3日为2028-03-03；不能确认到账。[1]";
          }

          public <T> T generateStructured(
              ModelRole role, ModelTask task, String system, String prompt, Class<T> type) {
            assertEquals(ModelTask.ANSWER_VERIFICATION, task);
            verifications++;
            return type.cast(
                new AnswerGroundingVerifier.Output(
                    true,
                    scopeValid,
                    prompts.size() > 1 && secondAccepted,
                    scopeValid ? "原文没有定义计日口径，日期只能作为明确的条件结果。" : "引用属于另一个对象，需要指定对象原文。",
                    scopeValid ? List.of() : List.of("指定对象的原文")));
          }
        };
    var retrieval =
        new RetrievalApplicationService(
            List.of(),
            (q, docs) -> List.of(),
            ids -> {
              throw new AssertionError("same-context correction must not retrieve");
            },
            Quotas.defaults(),
            20,
            400);
    return new AskApplicationService(retrieval, model, 24000);
  }

  @Test
  void missingCitationUsesTheSameBoundedCorrectionBeforeAnyGroundingVerdict() {
    var response =
        service(true, true, true)
            .answerFromSelection(
                "两项前提后的期限", "s", selection, ModelRole.SMALL, () -> {}, revisions::add);
    assertTrue(response.groundedAnswer());
    assertEquals(2, prompts.size());
    assertEquals(1, verifications);
    assertEquals(1, revisions.size());
    assertTrue(revisions.get(0).reason().contains("INVALID_EVIDENCE_CITATION"));
    assertEquals(List.of(ModelRole.SMALL, ModelRole.LARGE), roles);
    assertTrue(prompts.get(1).contains(selection.rendered()));
  }

  @Test
  void revisionAuditRetainsOriginalSourceNumbersWhileDeliveredCitationsAreCanonical() {
    var first =
        new QueryEvidenceContext.Source(
            1, "raw", new Evidence("first", "d1", "v1", "b1", "o1", Map.of(), "其他对象的原文", "h1"));
    var second =
        new QueryEvidenceContext.Source(
            2, "raw", new Evidence("second", "d2", "v2", "b2", "o2", Map.of(), "目标对象的原文", "h2"));
    var selected =
        new QueryEvidenceContext.Selection(
            List.of(first, second),
            QueryEvidenceContext.renderSources(List.of(first, second)),
            100);
    var model =
        new KnowledgeModel() {
          public String identity(ModelRole role) {
            return "scripted-numbering-fixture";
          }

          public String generate(ModelRole role, ModelTask task, String system, String prompt) {
            prompts.add(prompt);
            return prompts.size() == 1 ? "待修订表述。[2]" : "修订后的表述。[2]";
          }

          public <T> T generateStructured(
              ModelRole role, ModelTask task, String system, String prompt, Class<T> type) {
            verifications++;
            assertTrue(prompt.contains("[SOURCE 2]"));
            assertFalse(prompt.contains("[SOURCE 1]"));
            assertTrue(prompt.contains("Evidence id: second"));
            return type.cast(
                new AnswerGroundingVerifier.Output(
                    true, true, verifications == 2, "来源[2]只支持条件结论，请保留条件", List.of()));
          }
        };
    var retrieval =
        new RetrievalApplicationService(
            List.of(), (q, docs) -> List.of(), ids -> List.of(), Quotas.defaults(), 20, 400);
    var response =
        new AskApplicationService(retrieval, model, 24000)
            .answerFromSelection("目标对象", "s", selected, ModelRole.SMALL, () -> {}, revisions::add);
    assertEquals("待修订表述。[2]", revisions.get(0).rejectedAnswer());
    assertTrue(prompts.get(1).contains("来源[2]只支持条件结论"));
    assertTrue(prompts.get(1).contains("[PREVIOUS_DRAFT_UNTRUSTED]\n待修订表述。[2]"));
    assertEquals("修订后的表述。[1]", response.answer());
    assertEquals("second", response.citations().get(0).evidence().id());
  }

  @Test
  void verifierFeedbackRepairsTheConditionalDerivationWithoutReplacingSourceContext() {
    var originalSnapshot = selection.snapshot();
    var response =
        service(true, true)
            .answerFromSelection(
                "两项前提后的付款期限及是否到账", "s", selection, ModelRole.SMALL, () -> {}, revisions::add);
    assertFalse(response.evidenceOnly());
    assertNull(response.gap());
    assertEquals(List.of(ModelRole.SMALL, ModelRole.LARGE), roles);
    assertEquals(2, verifications);
    assertEquals(1, revisions.size());
    assertTrue(revisions.get(0).rejectedAnswer().contains("期限确定"));
    assertTrue(prompts.get(1).contains(revisions.get(0).reason()));
    for (String prompt : prompts) assertTrue(prompt.contains(selection.rendered()));
    assertEquals(originalSnapshot, selection.snapshot());
    assertEquals("e", response.citations().get(0).evidence().id());
    assertTrue(response.answer().contains("不能确认到账"));
  }

  @Test
  void aSecondRejectedDraftStopsWithoutReturningTheUnverifiedClaim() {
    var response =
        service(true, false)
            .answerFromSelection("期限", "s", selection, ModelRole.LARGE, () -> {}, revisions::add);
    assertTrue(response.evidenceOnly());
    assertNotNull(response.gap());
    assertEquals(2, prompts.size());
    assertEquals(2, verifications);
    assertEquals(1, revisions.size());
    assertFalse(response.answer().contains("2028-03-03"));
  }

  @Test
  void aScopeMismatchRequiresNewEvidenceInsteadOfRerollingTheSameInputs() {
    var response =
        service(false, true)
            .answerFromSelection("指定对象", "s", selection, ModelRole.SMALL, () -> {}, revisions::add);
    assertTrue(response.evidenceOnly());
    assertEquals(List.of("指定对象的原文"), response.gap().nextQueries());
    assertEquals(1, prompts.size());
    assertEquals(1, verifications);
    assertTrue(revisions.isEmpty());
  }

  @Test
  void authorizationIsRecheckedBeforeSendingARevisedDraftToTheModel() {
    var checks = new AtomicInteger();
    assertThrows(
        SecurityException.class,
        () ->
            service(true, true)
                .answerFromSelection(
                    "期限",
                    "s",
                    selection,
                    ModelRole.SMALL,
                    () -> {
                      if (checks.incrementAndGet() == 3) throw new SecurityException("revoked");
                    },
                    revisions::add));
    assertEquals(1, prompts.size());
    assertEquals(1, verifications);
  }
}
