package com.smartticket.agent;

import com.smartticket.ai.AiChatService;
import com.smartticket.exception.ApiFailure;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SpringAgentModel implements AgentModel {
    private final ObjectProvider<ChatModel> models;
    private final AiChatService channel;
    private final String model;

    public SpringAgentModel(
            ObjectProvider<ChatModel> m,
            AiChatService c,
            @Value("${spring.ai.openai.chat.options.model:}") String name) {
        models = m;
        channel = c;
        model = name;
    }

    public Reply call(List<Message> messages, List<ToolCallback> tools, int maxOutputTokens) {
        if (!channel.isAvailable()) throw new ApiFailure(503, 10006, "AI未配置，转人工处理");
        var response =
                models.getObject()
                        .call(
                                new Prompt(
                                        messages,
                                        OpenAiChatOptions.builder()
                                                .model(model)
                                                .temperature(0.1)
                                                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                                                .maxTokens(maxOutputTokens)
                                                .toolCallbacks(tools)
                                                .internalToolExecutionEnabled(false)
                                                .parallelToolCalls(false)
                                                .build()));
        if (response == null || response.getResult() == null)
            throw new ApiFailure(503, 10007, "模型返回为空");
        Integer tokens = response.getMetadata().getUsage().getTotalTokens();
        return new Reply(response.getResult().getOutput(), tokens == null ? 0 : tokens);
    }
}
