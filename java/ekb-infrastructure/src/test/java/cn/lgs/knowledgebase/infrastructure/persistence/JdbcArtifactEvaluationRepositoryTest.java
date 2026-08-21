package cn.lgs.knowledgebase.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcArtifactEvaluationRepositoryTest {
    @Test
    void versionScopeDeduplicatesAndRejectsEmptyInput() {
        var scope = JdbcArtifactEvaluationRepository.scope(List.of(" v1 ", "v1", "v2"));
        assertEquals("?,?", scope.placeholders());
        assertEquals(List.of("v1", "v2"), scope.args());
        assertThrows(IllegalArgumentException.class, () -> JdbcArtifactEvaluationRepository.scope(List.of("", "  ")));
    }
}
