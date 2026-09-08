package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingSourceIntegrationTest {
  private KnowledgeEntryCatalogue.Entry entry(String id, String summary) {
    return new KnowledgeEntryCatalogue.Entry(
        id,
        2,
        "scope",
        Map.of(),
        "EVENT",
        "验收前提",
        List.of(),
        "依据原文",
        "CONCEPT",
        "ACTIVE",
        List.of(
            new KnowledgeEntryCatalogue.Source(
                "doc", "version", summary, "concept", 1, List.of("fact"))),
        List.of(new KnowledgeEntryCatalogue.BuildInput(summary, "version", 1)));
  }

  private KnowledgeEntryCatalogue.Candidate candidate(boolean required) {
    var entry = entry("existing", "old-summary");
    return new KnowledgeEntryCatalogue.Candidate(
        entry, entry.label(), List.of(), entry.description(), entry.buildInputs(), true, required);
  }

  @Test
  void aKnownPendingEntryCannotBeReplacedByAnUnrelatedExtractedEntity() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ConceptCompilationService.requirePendingIntegrations(
                "incoming",
                List.of(candidate(true)),
                List.of(
                    new KnowledgeEntryCatalogue.Publication(entry("different", "incoming"), 0))));
  }

  @Test
  void echoingTheOldEntryWithoutIntegratingTheIncomingEvidenceCannotFinishTheTask() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ConceptCompilationService.requirePendingIntegrations(
                "incoming",
                List.of(candidate(true)),
                List.of(
                    new KnowledgeEntryCatalogue.Publication(entry("existing", "old-summary"), 1))));
  }

  @Test
  void exactGroundedIntegrationPassesAndUnrelatedSoftCandidatesRemainOptional() {
    assertDoesNotThrow(
        () ->
            ConceptCompilationService.requirePendingIntegrations(
                "incoming",
                List.of(candidate(true)),
                List.of(
                    new KnowledgeEntryCatalogue.Publication(entry("existing", "incoming"), 1))));
    assertDoesNotThrow(
        () ->
            ConceptCompilationService.requirePendingIntegrations(
                "incoming", List.of(candidate(false)), List.of()));
  }
}
