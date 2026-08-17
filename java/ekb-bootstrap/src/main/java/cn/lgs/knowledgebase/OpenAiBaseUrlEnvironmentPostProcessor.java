package cn.lgs.knowledgebase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Normalizes OpenAI-compatible chat endpoints. Spring AI appends /v1/chat/completions,
 * while many providers publish their base URL with a trailing /v1.
 */
public final class OpenAiBaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE = "ekb-openai-base-url-compatibility";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("EKB_CHAT_BASE_URL");
        if (configured == null || configured.isBlank()) return;
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


    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
