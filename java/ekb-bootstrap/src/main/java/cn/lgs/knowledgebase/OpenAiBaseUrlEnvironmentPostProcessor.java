package cn.lgs.knowledgebase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Keeps the Java runtime compatible with the existing Go-era EKB_LLM_BASE_URL convention.
 * Spring AI appends /v1/chat/completions to its OpenAI base URL, while many OpenAI-compatible
 * endpoints are commonly configured by users with a trailing /v1. Normalize only that final
 * version segment so either convention works without duplicating /v1.
 */
public final class OpenAiBaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE = "ekb-openai-base-url-compatibility";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = firstNonBlank(environment.getProperty("EKB_ASK_BASE_URL"),
                environment.getProperty("EKB_LLM_BASE_URL"));
        if (configured == null) return;
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE,
                Map.of("spring.ai.openai.base-url", normalize(configured))));
    }

    static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) normalized = normalized.substring(0, normalized.length() - 3);
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
