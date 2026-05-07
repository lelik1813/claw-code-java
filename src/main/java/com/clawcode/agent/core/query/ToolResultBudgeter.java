package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;

public final class ToolResultBudgeter {

    private static final String HEADER = "[tool result compacted]";
    private static final String OMITTED_MARKER = "\n[... omitted middle ...]\n";

    public BudgetedToolResult budget(
        ToolUseRequest request,
        ToolResult result,
        ToolResultBudget budget
    ) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }

        String content = resultContent(result);
        int originalChars = content.length();
        String pathHint = ToolCallOutcome.pathHintFrom(request);
        if (originalChars <= budget.maxChars()) {
            return new BudgetedToolResult(
                request.toolCallId(),
                request.toolName(),
                content,
                result.isError(),
                false,
                originalChars,
                originalChars,
                0,
                pathHint);
        }

        Compacted compacted = compact(request, pathHint, content, budget);
        return new BudgetedToolResult(
            request.toolCallId(),
            request.toolName(),
            compacted.content(),
            result.isError(),
            true,
            originalChars,
            compacted.shownChars(),
            originalChars - compacted.shownChars(),
            pathHint);
    }

    private static String resultContent(ToolResult result) {
        Object value = result.isError() ? result.errorMessage() : result.output();
        return value == null ? "" : value.toString();
    }

    private static Compacted compact(
        ToolUseRequest request,
        String pathHint,
        String original,
        ToolResultBudget budget
    ) {
        int excerptLimit = Math.min(budget.excerptChars(), budget.maxChars());
        while (excerptLimit > 0) {
            Excerpt excerpt = headTailExcerpt(original, excerptLimit);
            int omittedChars = original.length() - excerpt.shownChars();
            String metadata = metadata(request, pathHint, original.length(), excerpt.shownChars(), omittedChars);
            String content = join(metadata, excerpt.text());
            if (content.length() <= budget.maxChars()) {
                return new Compacted(content, excerpt.shownChars());
            }
            int overflow = content.length() - budget.maxChars();
            excerptLimit = Math.max(0, excerptLimit - overflow);
        }

        String metadata = metadata(request, pathHint, original.length(), 0, original.length());
        if (metadata.length() <= budget.maxChars()) {
            return new Compacted(metadata, 0);
        }
        return new Compacted(metadata.substring(0, budget.maxChars()), 0);
    }

    private static Excerpt headTailExcerpt(String content, int maxExcerptChars) {
        if (maxExcerptChars <= 0) {
            return new Excerpt("", 0);
        }
        if (content.length() <= maxExcerptChars) {
            return new Excerpt(content, content.length());
        }
        if (maxExcerptChars <= OMITTED_MARKER.length()) {
            String head = content.substring(0, maxExcerptChars);
            return new Excerpt(head, head.length());
        }
        int sourceChars = maxExcerptChars - OMITTED_MARKER.length();
        int headChars = sourceChars / 2;
        int tailChars = sourceChars - headChars;
        String head = content.substring(0, headChars);
        String tail = content.substring(content.length() - tailChars);
        return new Excerpt(head + OMITTED_MARKER + tail, head.length() + tail.length());
    }

    private static String metadata(
        ToolUseRequest request,
        String pathHint,
        int originalChars,
        int shownChars,
        int omittedChars
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("tool: ").append(nullToEmpty(request.toolName())).append('\n');
        sb.append("tool_call_id: ").append(nullToEmpty(request.toolCallId())).append('\n');
        if (!pathHint.isBlank()) {
            sb.append("path: ").append(pathHint).append('\n');
        }
        sb.append("original_chars: ").append(originalChars).append('\n');
        sb.append("shown_chars: ").append(shownChars).append('\n');
        sb.append("omitted_chars: ").append(omittedChars);
        return sb.toString();
    }

    private static String join(String metadata, String excerpt) {
        if (excerpt.isEmpty()) {
            return metadata;
        }
        return metadata + "\n\n" + excerpt;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Excerpt(String text, int shownChars) {
    }

    private record Compacted(String content, int shownChars) {
    }
}
