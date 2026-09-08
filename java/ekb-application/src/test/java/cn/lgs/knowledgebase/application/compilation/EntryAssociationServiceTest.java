package cn.lgs.knowledgebase.application.compilation;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.CompilationContracts.*;
import cn.lgs.knowledgebase.application.port.Ports.*;
import cn.lgs.knowledgebase.domain.knowledge.KnowledgeDomain.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntryAssociationServiceTest {
  @Test
  void parserHeadingWithoutEvidenceKeepsTheBodyAssociationUsable() {
    var fixture = new Fixture(1, true, true);
    fixture.output =
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0001"), "付款期限调整为60日")));
    assertEquals(1, fixture.service.associate("new-version", fixture.publication));
    assertEquals(List.of("evidence-1"), fixture.publishedEvidence);
    assertEquals(1, fixture.calls);
    assertEquals(1, fixture.repairs);
  }

  @Test
  void structuralHeadingCannotMakeMissingBusinessEvidenceAcceptable() {
    var fixture = new Fixture(1, true, true);
    fixture.missingProof = true;
    assertThrows(
        IllegalStateException.class,
        () -> fixture.service.associate("new-version", fixture.publication));
    assertEquals(0, fixture.calls);
    assertEquals(0, fixture.publications);
    assertEquals(0, fixture.repairs);
  }

  @Test
  void coldUnrelatedSourceDoesNotCallModelOrQueueSummary() {
    var fixture = new Fixture(1, false);
    assertEquals(0, fixture.service.associate("new-version", fixture.publication));
    assertEquals(List.of("new-version"), fixture.inputs);
    assertEquals(0, fixture.calls);
    assertEquals(0, fixture.repairs);
  }

  @Test
  void groundedMatchPinsAllCandidateInputsAndQueuesOnePriorityRepairAfterModel() {
    var fixture = new Fixture(1, true);
    fixture.output =
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0001"), "付款期限调整为60日")));
    assertEquals(1, fixture.service.associate("new-version", fixture.publication));
    assertEquals(List.of("new-version", "old-version", "uncited-input"), fixture.inputs);
    assertEquals(List.of("evidence-1"), fixture.publishedEvidence);
    assertEquals(1, fixture.repairs);
    assertEquals(1, fixture.publications);
    assertEquals(List.of("model", "related-source", "repair"), fixture.events);
  }

  @Test
  void inventedEntryUnknownEvidenceAndFabricatedImpactAreRejectedBeforeWrites() {
    for (var match :
        List.of(
            new EntryAssociationService.Match("invented", List.of("E0001"), "付款期限调整为60日"),
            new EntryAssociationService.Match("entry", List.of("E0999"), "付款期限调整为60日"),
            new EntryAssociationService.Match("entry", List.of("E0001"), "法院裁定60日"))) {
      var fixture = new Fixture(1, true);
      fixture.output = new EntryAssociationService.Matches(List.of(match));
      assertThrows(
          IllegalStateException.class,
          () -> fixture.service.associate("new-version", fixture.publication));
      assertEquals(0, fixture.repairs);
      assertEquals(0, fixture.publications);
      assertTrue(fixture.publishedEvidence.isEmpty());
    }
  }

  @Test
  void everySourceBlockIsProcessedWithoutAcceptingReferencesFromAnotherBatch() {
    var fixture = new Fixture(9, true);
    fixture.outputs.add(
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0001"), "付款期限调整为60日"))));
    fixture.outputs.add(
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0009"), "付款期限调整为60日"))));
    assertEquals(1, fixture.service.associate("new-version", fixture.publication));
    assertEquals(2, fixture.calls);
    assertEquals(1, fixture.repairs);
    assertEquals(List.of("evidence-1", "evidence-9"), fixture.publishedEvidence);
  }

  @Test
  void lateBatchFailureLeavesEarlierDecisionsUnpublished() {
    var fixture = new Fixture(9, true);
    fixture.outputs.add(
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0001"), "付款期限调整为60日"))));
    fixture.outputs.add(
        new EntryAssociationService.Matches(
            List.of(new EntryAssociationService.Match("entry", List.of("E0001"), "付款期限调整为60日"))));
    assertThrows(
        IllegalStateException.class,
        () -> fixture.service.associate("new-version", fixture.publication));
    assertEquals(2, fixture.calls);
    assertEquals(0, fixture.repairs);
    assertEquals(0, fixture.publications);
  }

  @Test
  void noImpactDecisionDoesNotInvalidateExistingEntry() {
    var fixture = new Fixture(1, true);
    fixture.output = new EntryAssociationService.Matches(List.of());
    assertEquals(0, fixture.service.associate("new-version", fixture.publication));
    assertEquals(1, fixture.calls);
    assertEquals(0, fixture.repairs);
    assertTrue(fixture.publishedEvidence.isEmpty());
  }

  @Test
  void malformedDecisionAndMissingRawProofFailBeforePublication() {
    var fixture = new Fixture(1, true);
    assertThrows(
        IllegalStateException.class,
        () -> fixture.service.associate("new-version", fixture.publication));
    assertEquals(0, fixture.publications);
    var missing = new Fixture(1, true);
    missing.missingProof = true;
    assertThrows(
        IllegalStateException.class,
        () -> missing.service.associate("new-version", missing.publication));
    assertEquals(0, missing.calls);
    assertEquals(0, missing.publications);
  }

  @Test
  void modelTransportFailureCannotExecuteBackgroundPublication() {
    var fixture = new Fixture(1, true);
    fixture.transportFailure = true;
    assertThrows(
        TransientDependencyFailure.class,
        () -> fixture.service.associate("new-version", fixture.publication));
    assertEquals(0, fixture.repairs);
    assertEquals(0, fixture.publications);
  }

  static final class Fixture {
    int calls, repairs, publications;
    boolean missingProof, transportFailure;
    List<String> inputs = List.of(), publishedEvidence = List.of();
    final List<String> events = new ArrayList<>();
    final ArrayDeque<EntryAssociationService.Matches> outputs = new ArrayDeque<>();
    EntryAssociationService.Matches output;
    final EntryAssociationService service;
    final CompilationPublication publication =
        new CompilationPublication() {
          public void captureInputs(List<String> versions) {
            inputs = List.copyOf(versions);
          }

          public void publish(Runnable writes) {
            publications++;
            writes.run();
          }

          public String objectKey(String key) {
            return "synthetic/" + key;
          }
        };

    Fixture(int blockCount, boolean candidate) {
      this(blockCount, candidate, false);
    }

    Fixture(int blockCount, boolean candidate, boolean structuralHeading) {
      var blocks = new ArrayList<RawBlock>();
      var evidence = new LinkedHashMap<String, Evidence>();
      if (structuralHeading)
        blocks.add(
            new RawBlock(
                "heading",
                "new-version",
                0,
                "text",
                "付款条件",
                "付款条件",
                List.of("付款条件"),
                null,
                null,
                List.of(),
                List.of(),
                List.of("heading"),
                "synthetic",
                4));
      for (int i = 1; i <= blockCount; i++) {
        String id = "block-" + i, text = "甲公司合同HT-1付款期限调整为60日，第" + i + "段。";
        blocks.add(
            new RawBlock(
                id,
                "new-version",
                i,
                "PARAGRAPH",
                text,
                text,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(id),
                "synthetic",
                20));
        evidence.put(
            id,
            new Evidence(
                "evidence-" + i,
                "new-document",
                "new-version",
                id,
                "synthetic-only",
                Map.of(),
                text,
                "synthetic"));
      }
      var entry =
          new KnowledgeEntryCatalogue.Entry(
              "entry",
              1,
              "scope",
              Map.of("identity_status", "SCOPE_GROUNDED"),
              "CONTRACT",
              "HT-1",
              List.of(),
              "synthetic scope",
              "CONCEPT",
              "ACTIVE",
              List.of(),
              List.of(
                  new KnowledgeEntryCatalogue.BuildInput("old-summary", "old-version", 1),
                  new KnowledgeEntryCatalogue.BuildInput("uncited-summary", "uncited-input", 1)));
      var repository =
          proxy(
              CompilationRepository.class,
              (method, args) -> {
                if (method.equals("loadVersionSource"))
                  return new VersionSource(
                      "new-document",
                      "new-version",
                      "synthetic",
                      null,
                      null,
                      blocks,
                      missingProof ? Map.of() : evidence);
                throw new UnsupportedOperationException(method);
              });
      var catalogue =
          proxy(
              KnowledgeEntryCatalogue.class,
              (method, args) -> {
                if (method.equals("relatedCandidates"))
                  return candidate ? List.of(entry) : List.of();
                if (method.equals("relatedSource")) {
                  events.add("related-source");
                  publishedEvidence = (List<String>) args[2];
                  return null;
                }
                throw new UnsupportedOperationException(method);
              });
      var tasks =
          proxy(
              GenerationTaskRepository.class,
              (method, args) -> {
                if (method.equals("queueRepair")) {
                  events.add("repair");
                  repairs++;
                  return null;
                }
                throw new UnsupportedOperationException(method);
              });
      KnowledgeModel model =
          new KnowledgeModel() {
            public String identity(ModelRole role) {
              return "synthetic-not-real-model";
            }

            public String generate(ModelRole role, ModelTask task, String system, String prompt) {
              throw new UnsupportedOperationException();
            }

            public <T> T generateStructured(
                ModelRole role, ModelTask task, String system, String prompt, Class<T> type) {
              assertEquals(ModelRole.SMALL, role);
              assertEquals(ModelTask.SOURCE_ASSOCIATION, task);
              assertTrue(inputs.contains("new-version"));
              assertEquals(0, publications);
              events.add("model");
              calls++;
              if (transportFailure)
                throw new TransientDependencyFailure(
                    "synthetic native adapter transport test", null);
              return type.cast(outputs.isEmpty() ? output : outputs.removeFirst());
            }
          };
      service = new EntryAssociationService(repository, catalogue, model, tasks);
    }
  }

  interface Handler {
    Object call(String name, Object[] args);
  }

  static <T> T proxy(Class<T> type, Handler handler) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (object, method, args) -> handler.call(method.getName(), args)));
  }
}
