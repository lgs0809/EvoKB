package cn.lgs.knowledgebase.domain;

import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactEvaluationDomainTest {
    @Test
    void computesDeterministicArtifactMetricsWithAliases() {
        var golden = new ArtifactEvaluationDomain.ArtifactGoldenCase("case-1", List.of("v1"),
                List.of(new ArtifactEvaluationDomain.TextExpectation("Revenue was 100 million yuan", List.of("Revenue reached 100 million yuan")),
                        new ArtifactEvaluationDomain.TextExpectation("Gross margin was 30 percent", List.of())),
                List.of(new ArtifactEvaluationDomain.RelationExpectation("Acme Holdings", "CONTROLS", "Acme Tech", List.of("ACME"), List.of(), List.of()),
                        new ArtifactEvaluationDomain.RelationExpectation("Acme Tech", "LOCATED_IN", "Harbin", List.of(), List.of(), List.of())),
                List.of(new ArtifactEvaluationDomain.EntityMappingExpectation("ACME Co.", "COMPANY", "Acme Holdings", List.of(), List.of("ACME Holdings Ltd"))),
                List.of(new ArtifactEvaluationDomain.TextExpectation("Acme Tech is controlled by Acme Holdings", List.of()),
                        new ArtifactEvaluationDomain.TextExpectation("Acme Tech is based in Harbin", List.of())));
        var actual = new ArtifactEvaluationDomain.ActualArtifacts(
                List.of("Revenue reached   100 million yuan", "Unexpected fact"),
                List.of(new ArtifactEvaluationDomain.RelationActual("ACME", "controls", "Acme Tech"),
                        new ArtifactEvaluationDomain.RelationActual("Acme Tech", "SELLS_TO", "Other Co")),
                List.of(new ArtifactEvaluationDomain.EntityMappingActual("ACME Co.", "company", "ACME Holdings Ltd")),
                List.of(new ArtifactEvaluationDomain.WikiClaimActual("Acme Tech is controlled by Acme Holdings", true),
                        new ArtifactEvaluationDomain.WikiClaimActual("Unsupported wiki claim", false)));

        var result = ArtifactEvaluationDomain.evaluateCase(golden, actual);

        assertEquals(0.5, result.summaryFact().precision(), 1e-9);
        assertEquals(0.5, result.summaryFact().recall(), 1e-9);
        assertEquals(0.5, result.graphRelation().precision(), 1e-9);
        assertEquals(0.5, result.graphRelation().recall(), 1e-9);
        assertEquals(1.0, result.entityResolution().accuracy(), 1e-9);
        assertEquals(0.5, result.wikiClaim().precision(), 1e-9);
        assertEquals(0.5, result.wikiClaim().recall(), 1e-9);
        assertEquals(0.5, result.wikiClaimGrounding().rate(), 1e-9);
    }

    @Test
    void metricsRemainUnavailableWithoutGoldenExpectations() {
        var result = ArtifactEvaluationDomain.evaluateCase(
                new ArtifactEvaluationDomain.ArtifactGoldenCase("empty", List.of("v1"), List.of(), List.of(), List.of(), List.of()),
                new ArtifactEvaluationDomain.ActualArtifacts(List.of(), List.of(), List.of(), List.of()));

        assertFalse(result.summaryFact().available());
        assertNull(result.summaryFact().precision());
        assertNull(result.summaryFact().recall());
        assertFalse(result.graphRelation().available());
        assertFalse(result.entityResolution().available());
        assertNull(result.entityResolution().accuracy());
        assertFalse(result.wikiClaim().available());
        assertFalse(result.wikiClaimGrounding().available());
        assertNull(result.wikiClaimGrounding().rate());
    }

    @Test
    void reportUsesMicroAveragingAndDoesNotClaimAgentMetrics() {
        var one = ArtifactEvaluationDomain.evaluateCase(
                new ArtifactEvaluationDomain.ArtifactGoldenCase("one", List.of("v1"),
                        List.of(new ArtifactEvaluationDomain.TextExpectation("a", List.of())), List.of(), List.of(), List.of()),
                new ArtifactEvaluationDomain.ActualArtifacts(List.of("a"), List.of(), List.of(), List.of()));
        var two = ArtifactEvaluationDomain.evaluateCase(
                new ArtifactEvaluationDomain.ArtifactGoldenCase("two", List.of("v2"),
                        List.of(new ArtifactEvaluationDomain.TextExpectation("b", List.of()),
                                new ArtifactEvaluationDomain.TextExpectation("c", List.of()),
                                new ArtifactEvaluationDomain.TextExpectation("d", List.of())), List.of(), List.of(), List.of()),
                new ArtifactEvaluationDomain.ActualArtifacts(List.of("b", "extra-1", "extra-2"), List.of(), List.of(), List.of()));

        var report = ArtifactEvaluationDomain.buildReport(List.of(one, two));

        assertEquals(2, report.summaryFact().matched());
        assertEquals(4, report.summaryFact().actual());
        assertEquals(4, report.summaryFact().expected());
        assertEquals(0.5, report.summaryFact().precision(), 1e-9);
        assertEquals(0.5, report.summaryFact().recall(), 1e-9);
        assertFalse(report.taskSuccessAvailable());
        assertFalse(report.retrievalRoundsAvailable());
    }
}
