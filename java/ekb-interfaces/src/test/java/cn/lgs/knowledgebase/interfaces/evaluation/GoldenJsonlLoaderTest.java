package cn.lgs.knowledgebase.interfaces.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoldenJsonlLoaderTest {
    private final GoldenJsonlLoader loader = new GoldenJsonlLoader(new ObjectMapper());

    @Test
    void loadsSnakeCaseJsonlAndSkipsComments() {
        String jsonl = "# comment\n\n" +
                "{\"id\":\"case-1\",\"version_ids\":[\"v1\"],\"expected_summary_facts\":[{\"text\":\"fact\"}]}\n";

        var cases = loader.load(new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, cases.size());
        assertEquals("case-1", cases.get(0).id());
        assertEquals("v1", cases.get(0).versionIds().get(0));
        assertEquals("fact", cases.get(0).expectedSummaryFacts().get(0).text());
    }

    @Test
    void rejectsDuplicateIdsWithLineContext() {
        String jsonl = "{\"id\":\"same\",\"version_ids\":[\"v1\"]}\n" +
                "{\"id\":\"same\",\"version_ids\":[\"v2\"]}\n";

        var error = assertThrows(IllegalArgumentException.class,
                () -> loader.load(new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8))));

        assertEquals(true, error.getMessage().contains("line 2"));
        assertEquals(true, error.getMessage().contains("duplicate id"));
    }
}
