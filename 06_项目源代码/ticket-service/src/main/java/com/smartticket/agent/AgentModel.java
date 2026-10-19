package com.smartticket.agent;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;

public interface AgentModel {
    record Reply(AssistantMessage message, int tokens) {}

    Reply call(List<Message> messages, List<ToolCallback> tools, int maxOutputTokens);
}
