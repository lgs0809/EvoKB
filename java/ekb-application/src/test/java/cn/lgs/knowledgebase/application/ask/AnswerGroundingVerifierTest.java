package cn.lgs.knowledgebase.application.ask;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.application.query.QueryEvidenceContext.Source;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.Evidence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnswerGroundingVerifierTest {
  private final AtomicReference<String> prompt = new AtomicReference<>();
  private final AtomicReference<String> systemPrompt = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();

  private AnswerGroundingVerifier verifier(AnswerGroundingVerifier.Output output) {
    return new AnswerGroundingVerifier(
        new KnowledgeModel() {
          public String identity(ModelRole role) {
            return "synthetic-verifier";
          }

          public String generate(ModelRole role, ModelTask task, String system, String user) {
            throw new AssertionError("verification must use its typed protocol");
          }

          public <T> T generateStructured(
              ModelRole role, ModelTask task, String system, String user, Class<T> type) {
            assertEquals(ModelRole.SMALL, role);
            assertEquals(ModelTask.ANSWER_VERIFICATION, task);
            calls.incrementAndGet();
            systemPrompt.set(system);
            prompt.set(user);
            return type.cast(output);
          }
        });
  }

  @Test
  void aSupportedBooleanAloneCannotCertifyTheObjectOrScope() {
    for (var output :
        List.of(
            new AnswerGroundingVerifier.Output(false, true, true, "wrong subject", List.of()),
            new AnswerGroundingVerifier.Output(true, false, true, "wrong scope", List.of()),
            new AnswerGroundingVerifier.Output(true, true, false, "unsupported claim", List.of()),
            new AnswerGroundingVerifier.Output(null, null, true, "missing protocol", List.of()))) {
      assertFalse(
          verifier(output).verify("original question", "cited answer [1]", List.of()).accepted());
    }
  }

  @Test
  void aMissingVerdictFieldCannotBeTreatedAsAnExplicitRequestToReviseAClaim() {
    var result =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true, true, null, "missing claims field", List.of()))
            .verify("question", "answer [1]", List.of());
    assertFalse(result.accepted());
    assertFalse(result.sameContextRevisionAllowed());
    assertEquals(1, calls.get());
  }

  @Test
  void aDraftWithWrongCitationsCanBeRevisedWhenNoAdditionalSourceIsRequested() {
    var correction =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true,
                    false,
                    false,
                    "correct citations and remove unsupported claims",
                    List.of()))
            .verify("question", "answer [1]", List.of());
    assertFalse(correction.accepted());
    assertTrue(correction.sameContextRevisionAllowed());

    var missing =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true, false, false, "missing source for this object", List.of("find source")))
            .verify("question", "answer [1]", List.of());
    assertFalse(missing.accepted());
    assertFalse(missing.sameContextRevisionAllowed());

    var wrongSubject =
        verifier(new AnswerGroundingVerifier.Output(false, false, false, "wrong object", List.of()))
            .verify("question", "answer [1]", List.of());
    assertFalse(wrongSubject.accepted());
    assertFalse(wrongSubject.sameContextRevisionAllowed());
  }

  @Test
  void missingOutputFailsClosedAndDoesNotInventSearches() {
    var verdict = verifier(null).verify("question", "answer [1]", List.of());
    assertFalse(verdict.accepted());
    assertFalse(verdict.reason().isBlank());
    assertTrue(verdict.nextQueries().isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void aDerivedDateStillRequiresACompleteScopedVerdictAndCannotProvePayment() {
    var source =
        new Source(
            1,
            "raw",
            new Evidence(
                "e",
                "d",
                "v",
                "b",
                "o",
                Map.of(),
                "验收日2028-02-27，发票日2028-02-29，两项满足后3日内付款。未记录到账。",
                "h"));
    var answer = "按自然日且起算日不计入，较晚的2028-02-29加3日为2028-03-03；不能确认实际到账。[1]";
    var accepted =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true, true, true, "scoped derivation", List.of()))
            .verify("满足两项前提后的期限及到账情况", answer, List.of(source));
    assertTrue(accepted.accepted());
    assertTrue(prompt.get().contains(source.evidence().content()));
    assertTrue(prompt.get().contains(answer));
    assertTrue(systemPrompt.get().contains(GroundedDerivationPolicy.instructions()));
    var rejected =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true, true, false, "start condition omitted", List.of("两项前提")))
            .verify("满足两项前提后的期限及到账情况", "从验收日直接起算且已到账。[1]", List.of(source));
    assertFalse(rejected.accepted());
    assertEquals(List.of("两项前提"), rejected.nextQueries());
  }

  @Test
  void verifierKeepsOriginalQuestionAndExactCitedSourceNumberingWithoutRetrieval() {
    var source =
        new Source(
            3, "graph", new Evidence("e", "d", "v", "b", "o", Map.of(), "EXACT RAW TEXT", "hash"));
    var verdict =
        verifier(new AnswerGroundingVerifier.Output(true, true, true, "verified", List.of()))
            .verify("the original business object", "the proposed claim [3]", List.of(source));
    assertTrue(verdict.accepted());
    assertEquals(1, calls.get());
    assertTrue(prompt.get().contains("the original business object"));
    assertTrue(prompt.get().contains("the proposed claim [3]"));
    assertTrue(prompt.get().contains("[SOURCE 3]"));
    assertTrue(prompt.get().contains("EXACT RAW TEXT"));
    assertFalse(prompt.get().contains("[SOURCE 1]"));
  }

  @Test
  void correctionQueriesAreLimitedAndDeduplicatedWithoutManufacturingAnAnswer() {
    var verdict =
        verifier(
                new AnswerGroundingVerifier.Output(
                    true,
                    false,
                    true,
                    "scope gap",
                    java.util.Arrays.asList(
                        null,
                        " ",
                        " object evidence ",
                        "object evidence",
                        "exact amendment",
                        "extra")))
            .verify("question", "answer [1]", List.of());
    assertFalse(verdict.accepted());
    assertEquals(List.of("object evidence", "exact amendment"), verdict.nextQueries());
  }

  @Test
  void onlySameVersionExplicitIdentityContextCanSupplementACitedClause() {
    var clause =
        new Source(
            1, "raw", new Evidence("clause", "d", "current", "b", "o", Map.of(), "总价120万元。", "h"));
    var identity =
        new Source(
            2,
            "raw",
            new Evidence("identity", "d", "current", "b2", "o", Map.of(), "项目PX-42合同。", "h2"));
    var other =
        new Source(
            3,
            "raw",
            new Evidence("other", "d", "old", "b3", "o", Map.of(), "项目PX-42总价999万元。", "h3"));
    var uncited =
        new Source(
            4,
            "raw",
            new Evidence("uncited", "d", "current", "b4", "o", Map.of(), "未引用的其他事实888万元。", "h4"));
    verifier(new AnswerGroundingVerifier.Output(true, true, true, "valid", List.of()))
        .verify(
            "项目PX-42总价", "总价120万元。[1]", List.of(clause), List.of(clause, identity, other, uncited));
    assertTrue(prompt.get().contains("[IDENTITY_CONTEXT]"));
    assertTrue(prompt.get().contains("Evidence id: identity"));
    assertFalse(prompt.get().contains("999万元"));
    assertFalse(prompt.get().contains("888万元"));
    assertFalse(prompt.get().contains("[SOURCE 2]"));
  }
}
