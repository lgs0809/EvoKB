package cn.lgs.knowledgebase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiBaseUrlEnvironmentPostProcessorTest {
    @Test
    void normalizesTrailingV1WithoutChangingOtherProviderPaths() {
        assertEquals("https://provider.example", OpenAiBaseUrlEnvironmentPostProcessor.normalize("https://provider.example/v1"));
        assertEquals("https://provider.example", OpenAiBaseUrlEnvironmentPostProcessor.normalize("https://provider.example/v1/"));
        assertEquals("https://provider.example/api", OpenAiBaseUrlEnvironmentPostProcessor.normalize("https://provider.example/api/v1"));
        assertEquals("https://provider.example/openai", OpenAiBaseUrlEnvironmentPostProcessor.normalize("https://provider.example/openai"));
    }
}
