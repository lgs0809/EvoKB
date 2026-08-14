package cn.lgs.knowledgebase.infrastructure.model;

import cn.lgs.knowledgebase.application.port.Ports.KnowledgeModel;
import cn.lgs.knowledgebase.application.port.Ports.ModelRole;
import cn.lgs.knowledgebase.application.port.Ports.ModelTask;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Objects;

/**
 * Spring AI Alibaba integration boundary. The application speaks only KnowledgeModel;
 * this adapter is the only place where ChatClient/provider options are visible.
 */
public final class SpringAiAlibabaKnowledgeModel implements KnowledgeModel {
    private final ChatClient chatClient;
    private final String smallModel;
    private final String largeModel;

    public SpringAiAlibabaKnowledgeModel(ChatClient.Builder builder, String smallModel, String largeModel) {
        this.chatClient = Objects.requireNonNull(builder, "chatClient builder").build();
        this.smallModel = requireText(smallModel, "small model");
        this.largeModel = requireText(largeModel, "large model");
    }

    @Override
    public String identity(ModelRole role) {
        return role == ModelRole.LARGE ? largeModel : smallModel;
    }

    @Override
    public String generate(ModelRole role, ModelTask task, String systemPrompt, String userPrompt) {
        String content = chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .user(userPrompt == null ? "" : userPrompt)
                .options(options(role))
                .call()
                .content();
        if (content == null || content.isBlank()) throw new IllegalStateException("model returned empty content for " + task);
        return content.trim();
    }

    @Override
    public <T> T generateStructured(ModelRole role, ModelTask task, String systemPrompt, String userPrompt, Class<T> outputType) {
        T output = chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .user(userPrompt == null ? "" : userPrompt)
                .options(options(role))
                .call()
                .entity(outputType);
        if (output == null) throw new IllegalStateException("model returned empty structured output for " + task);
        return output;
    }

    private OpenAiChatOptions options(ModelRole role) {
        return OpenAiChatOptions.builder().model(role == ModelRole.LARGE ? largeModel : smallModel).build();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
