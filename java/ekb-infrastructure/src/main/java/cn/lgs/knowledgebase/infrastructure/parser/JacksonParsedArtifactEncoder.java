package cn.lgs.knowledgebase.infrastructure.parser;

import cn.lgs.knowledgebase.application.port.Ports.ParsedArtifactEncoder;
import cn.lgs.knowledgebase.application.port.Ports.ParsedDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JacksonParsedArtifactEncoder implements ParsedArtifactEncoder {
    private final ObjectMapper mapper;

    public JacksonParsedArtifactEncoder(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("object mapper is required");
        this.mapper = mapper;
    }

    @Override
    public byte[] structuredArtifact(ParsedDocument parsed) {
        if (parsed == null) throw new IllegalArgumentException("parsed document is required");
        try {
            JsonNode middle = mapper.readTree(parsed.middleJson());
            JsonNode content = mapper.readTree(parsed.contentList());
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("parser_name", parsed.parserName());
            artifact.put("parser_version", parsed.parserVersion());
            artifact.put("middle_json", middle);
            artifact.put("content_list", content);
            return mapper.writeValueAsBytes(artifact);
        } catch (Exception e) {
            throw new IllegalStateException("cannot build structured parsed artifact", e);
        }
    }
}
