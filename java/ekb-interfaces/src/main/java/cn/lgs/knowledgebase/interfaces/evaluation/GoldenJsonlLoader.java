package cn.lgs.knowledgebase.interfaces.evaluation;

import cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.ArtifactGoldenCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.lgs.knowledgebase.domain.evaluation.ArtifactEvaluationDomain.validateGoldenCase;

/** Interface adapter for the human-authored Golden JSONL format. */
public final class GoldenJsonlLoader {
    private static final int MAX_LINE_CHARS = 4 * 1024 * 1024;
    private final ObjectMapper mapper;

    public GoldenJsonlLoader(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("object mapper is required");
        this.mapper = mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public List<ArtifactGoldenCase> load(InputStream input) {
        if (input == null) throw new IllegalArgumentException("Golden JSONL input is required");
        List<ArtifactGoldenCase> cases = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.length() > MAX_LINE_CHARS) throw new IllegalArgumentException("artifact golden line " + lineNo + ": line exceeds 4 MiB");
                ArtifactGoldenCase item;
                try {
                    item = mapper.readValue(trimmed, ArtifactGoldenCase.class);
                    validateGoldenCase(item);
                } catch (Exception e) {
                    throw new IllegalArgumentException("artifact golden line " + lineNo + ": " + rootMessage(e), e);
                }
                if (!seenIds.add(item.id())) throw new IllegalArgumentException("artifact golden line " + lineNo + ": duplicate id \"" + item.id() + "\"");
                cases.add(item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read artifact Golden JSONL", e);
        }
        return List.copyOf(cases);
    }

    private static String rootMessage(Throwable value) {
        Throwable cursor = value;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null || cursor.getMessage().isBlank() ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
