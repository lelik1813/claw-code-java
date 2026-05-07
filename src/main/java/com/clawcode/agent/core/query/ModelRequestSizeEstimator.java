package com.clawcode.agent.core.query;

import com.clawcode.agent.model.ModelToolDefinition;
import com.clawcode.agent.shared.message.AssistantContentBlock;
import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.AssistantTextBlock;
import com.clawcode.agent.shared.message.AssistantThinkingBlock;
import com.clawcode.agent.shared.message.AssistantToolUseBlock;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.util.List;

public final class ModelRequestSizeEstimator {

    public long estimate(
        String systemPrompt,
        List<Message> messages,
        List<ModelToolDefinition> tools
    ) {
        long total = length(systemPrompt);

        if (messages != null) {
            for (Message message : messages) {
                total += estimateMessage(message);
            }
        }

        if (tools != null) {
            for (ModelToolDefinition tool : tools) {
                total += estimateTool(tool);
            }
        }

        return total;
    }

    private long estimateMessage(Message message) {
        if (message == null) {
            return 0;
        }
        if (message instanceof UserMessage user) {
            return length(user.content());
        }
        if (message instanceof AssistantMessage assistant) {
            return estimateAssistantContent(assistant.content());
        }
        if (message instanceof ToolResultMessage toolResult) {
            return length(toolResult.content());
        }
        return 0;
    }

    private long estimateAssistantContent(List<AssistantContentBlock> blocks) {
        if (blocks == null) {
            return 0;
        }
        long total = 0;
        for (AssistantContentBlock block : blocks) {
            total += estimateAssistantContentBlock(block);
        }
        return total;
    }

    private long estimateAssistantContentBlock(AssistantContentBlock block) {
        if (block == null) {
            return 0;
        }
        if (block instanceof AssistantTextBlock text) {
            return length(text.text());
        }
        if (block instanceof AssistantToolUseBlock toolUse) {
            return length(toolUse.id())
                + length(toolUse.name())
                + length(toolUse.input());
        }
        if (block instanceof AssistantThinkingBlock thinking) {
            return length(thinking.thinking()) + length(thinking.signature());
        }
        return 0;
    }

    private long estimateTool(ModelToolDefinition tool) {
        if (tool == null) {
            return 0;
        }
        return length(tool.name())
            + length(tool.description())
            + length(tool.inputSchema());
    }

    private long length(Object value) {
        return value == null ? 0 : value.toString().length();
    }
}
