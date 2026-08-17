package cn.lgs.knowledgebase;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Opt-in integration smoke against the configured OpenAI-compatible provider.
 * It is skipped in normal builds so CI never spends model quota accidentally.
 */
@EnabledIfEnvironmentVariable(named = "EKB_REAL_MODEL_SMOKE", matches = "(?i)true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.ai.mcp.server.enabled=false")
class RealModelSmokeTest {
    @Autowired KnowledgeModel model;

    record Probe(String status) {}

    @Test
    void smallPlainAndLargeStructuredCallsWork() {
        String small = model.generate(ModelRole.SMALL, ModelTask.ASK,
                "You are a connectivity probe. Be concise.",
                "Reply with OK only.");
        assertFalse(small == null || small.isBlank());

        Probe large = model.generateStructured(ModelRole.LARGE, ModelTask.SUMMARY,
                "Return only the requested structured result.",
                "Set status to OK.", Probe.class);
        assertEquals("OK", large.status());
    }
}
