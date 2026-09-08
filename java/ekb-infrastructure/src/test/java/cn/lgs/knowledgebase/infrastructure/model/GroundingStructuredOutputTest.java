package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import cn.lgs.knowledgebase.application.compilation.GroundingConstraintAudit;
import cn.lgs.knowledgebase.application.compilation.GroundingJudgeProtocol;
import cn.lgs.knowledgebase.application.compilation.SummaryCompilationService.SupportDecision;
import cn.lgs.knowledgebase.application.model.ModelConfigurationApplicationService.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

class GroundingStructuredOutputTest {
  @Test
  void fullCandidateProofRoundTripsAndLegacyJsonKeepsItsOriginalBinding() throws Exception {
    var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    String fact = "甲公司向乙公司交付设备。";
    var checks = GroundingConstraintAudit.exactCopyChecks(fact, List.of(fact));
    var full = GroundingConstraintAudit.acceptedFullCandidate(fact, checks);
    assertEquals(
        full,
        mapper.readValue(mapper.writeValueAsBytes(full), GroundingConstraintAudit.Audit.class));
    var legacyJson = mapper.valueToTree(full);
    ((com.fasterxml.jackson.databind.node.ObjectNode) legacyJson).remove("claim_binding");
    var legacy = mapper.treeToValue(legacyJson, GroundingConstraintAudit.Audit.class);
    assertEquals(GroundingConstraintAudit.ClaimBinding.MODEL_QUOTED_SPAN_V1, legacy.claimBinding());
    assertTrue(GroundingConstraintAudit.current(fact, fact, legacy));
    var minimal = new java.util.ArrayList<>(checks);
    minimal.set(
        0,
        new GroundingConstraintAudit.Check(
            GroundingConstraintAudit.Dimension.SUBJECT,
            GroundingConstraintAudit.Finding.SUPPORTED,
            "甲公司",
            List.of(fact),
            "synthetic old located span"));
    assertTrue(
        GroundingConstraintAudit.current(
            fact, fact, GroundingConstraintAudit.accepted(fact, minimal)));
    assertFalse(
        GroundingConstraintAudit.current(
            fact,
            fact,
            new GroundingConstraintAudit.Audit(
                GroundingConstraintAudit.POLICY_VERSION,
                fact,
                true,
                minimal,
                GroundingConstraintAudit.ClaimBinding.FULL_CANDIDATE_V2)));
  }

  @Test
  void semanticJudgeDoesNotHaveToReproduceCandidateSpansInItsWireSchema() {
    var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    String schema =
        new BeanOutputConverter<>(GroundingJudgeProtocol.Decision.class, mapper).getFormat();
    assertFalse(schema.contains("claimPart"), schema);
    assertTrue(schema.contains("FULL_CANDIDATE_V2"), schema);
    assertTrue(schema.contains("evidenceQuotes"), schema);
  }

  @Test
  void frameworkSchemaAndProductionMapperPreserveLocatedConstraintChecks() throws Exception {
    var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    String fact = "甲公司向乙公司交付设备。";
    var expected =
        new SupportDecision(
            true, "located", GroundingConstraintAudit.exactCopyChecks(fact, List.of(fact)));
    String response = mapper.writeValueAsString(expected);
    assertThrows(
        com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> new ObjectMapper().readValue(response, SupportDecision.class),
        "Old read-only probe omitted the production naming contract");
    var actual = mapper.readValue(response, SupportDecision.class);
    assertEquals(expected, actual);
    assertTrue(GroundingConstraintAudit.supported(fact, fact, actual.supported(), actual.checks()));
    var schema = new BeanOutputConverter<>(SupportDecision.class, mapper).getFormat();
    // Spring AI emits Java property names. The existing serving adapter normalizes those
    // recursively before reading with the production snake-case mapper.
    assertTrue(schema.contains("claimPart"), schema);
    assertTrue(schema.contains("evidenceQuotes"), schema);
    assertTrue(schema.contains("SUBJECT"));
    Store unusedStore =
        (Store)
            Proxy.newProxyInstance(
                Store.class.getClassLoader(),
                new Class<?>[] {Store.class},
                (proxy, method, arguments) -> {
                  throw new AssertionError("Structured-key normalization must not call a provider");
                });
    var adapter = new SpringAiAlibabaKnowledgeModel(unusedStore, mapper);
    var normalize =
        SpringAiAlibabaKnowledgeModel.class.getDeclaredMethod(
            "normalizeStructuredKeys", com.fasterxml.jackson.databind.JsonNode.class);
    normalize.setAccessible(true);
    var camelResponse = new ObjectMapper().valueToTree(expected);
    var normalized =
        (com.fasterxml.jackson.databind.JsonNode) normalize.invoke(adapter, camelResponse);
    assertEquals(expected, mapper.treeToValue(normalized, SupportDecision.class));
  }
}
