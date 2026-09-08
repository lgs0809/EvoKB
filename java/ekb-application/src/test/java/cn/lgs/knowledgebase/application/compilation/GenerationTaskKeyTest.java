package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.domain.compilation.CompilationDomain.GenerationTaskType;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class GenerationTaskKeyTest {
  @Test
  void existingShortEnterpriseAndNamespacedKeysStayByteIdentical() {
    String old = "generation:summary:" + GroundingConstraintAudit.POLICY_VERSION + ":source:";
    assertEquals(
        old,
        CompilationContracts.generationTaskKey(
            "enterprise", "source", GenerationTaskType.SUMMARY, ""));
    assertEquals(
        KnowledgeDomain.sha256("5:other:" + old),
        CompilationContracts.generationTaskKey("other", "source", GenerationTaskType.SUMMARY, ""));
  }

  @Test
  void fullRepairIdentityIsBoundedDistinctAndDeterministic() {
    String subject =
        "summary/11111111-1111-1111-1111-111111111111/acl/1/content/"
            + "a".repeat(64)
            + "/repair/"
            + "b".repeat(64);
    String key =
        CompilationContracts.generationTaskKey(
            "enterprise",
            "22222222-2222-2222-2222-222222222222",
            GenerationTaskType.CONCEPT,
            subject);
    assertTrue(key.startsWith("generation-key-sha256:"));
    assertTrue(key.length() <= 255);
    assertEquals(
        key,
        CompilationContracts.generationTaskKey(
            "enterprise",
            "22222222-2222-2222-2222-222222222222",
            GenerationTaskType.CONCEPT,
            subject));
    assertNotEquals(
        key,
        CompilationContracts.generationTaskKey(
            "enterprise",
            "22222222-2222-2222-2222-222222222222",
            GenerationTaskType.CONCEPT,
            subject + "c"));
    String full =
        "generation:concept:"
            + GroundingConstraintAudit.POLICY_VERSION
            + ":22222222-2222-2222-2222-222222222222:"
            + subject;
    assertEquals(
        KnowledgeDomain.sha256("5:other:" + full),
        CompilationContracts.generationTaskKey(
            "other", "22222222-2222-2222-2222-222222222222", GenerationTaskType.CONCEPT, subject));
  }
}
