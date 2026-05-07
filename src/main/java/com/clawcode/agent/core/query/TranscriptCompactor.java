package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.AssistantMessage;
import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.ToolResultMessage;
import com.clawcode.agent.shared.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TranscriptCompactor {

    private static final int EXCERPT_CHARS = 160;

    public List<Message> compact(List<Message> history, int preserveRecentMessages) {
        if (history == null || history.isEmpty()) {
            return List.of(summaryMessage(List.of()));
        }

        int tailCount = Math.max(0, Math.min(preserveRecentMessages, history.size()));
        int tailStart = history.size() - tailCount;
        List<Message> omitted = history.subList(0, tailStart);

        List<Message> compacted = new ArrayList<>(tailCount + 1);
        compacted.add(summaryMessage(omitted));
        compacted.addAll(history.subList(tailStart, history.size()));
        return List.copyOf(compacted);
    }

    private UserMessage summaryMessage(List<Message> omitted) {
        int userCount = 0;
        int assistantCount = 0;
        int toolCount = 0;
        int toolErrorCount = 0;
        String firstUserText = null;
        String lastUserText = null;
        String firstAssistantText = null;
        String lastAssistantText = null;

        for (Message message : omitted) {
            if (message instanceof UserMessage user) {
                userCount++;
                String text = user.content();
                if (firstUserText == null) {
                    firstUserText = text;
                }
                lastUserText = text;
            } else if (message instanceof AssistantMessage assistant) {
                assistantCount++;
                String text = assistant.textContent();
                if (firstAssistantText == null) {
                    firstAssistantText = text;
                }
                lastAssistantText = text;
            } else if (message instanceof ToolResultMessage toolResult) {
                toolCount++;
                if (toolResult.isError()) {
                    toolErrorCount++;
                }
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("[conversation compacted]\n");
        summary.append("omitted_messages: ").append(omitted.size()).append('\n');
        summary.append("omitted_user_messages: ").append(userCount).append('\n');
        summary.append("omitted_assistant_messages: ").append(assistantCount).append('\n');
        summary.append("omitted_tool_result_messages: ").append(toolCount).append('\n');
        summary.append("omitted_tool_error_messages: ").append(toolErrorCount).append('\n');
        appendExcerpt(summary, "first_user", firstUserText);
        appendExcerpt(summary, "last_user", lastUserText);
        appendExcerpt(summary, "first_assistant", firstAssistantText);
        appendExcerpt(summary, "last_assistant", lastAssistantText);

        return new UserMessage(UUID.randomUUID(), Instant.now(), summary.toString());
    }

    private void appendExcerpt(StringBuilder summary, String label, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        summary.append(label)
            .append(": ")
            .append(excerpt(text))
            .append('\n');
    }

    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= EXCERPT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, EXCERPT_CHARS) + "...";
    }
}
