package com.clawcode.agent.cli.commands;

import com.clawcode.agent.cli.model.CliQueryEvent;
import java.util.HashMap;
import java.util.Map;

public final class CliTurnRenderer {

    private boolean hadDelta;
    private final Map<String, String> toolCallNames = new HashMap<>();
    private int toolCallCount;

    public String render(CliQueryEvent event) {
        return switch (event) {
            case CliQueryEvent.Started s -> "";
            case CliQueryEvent.Delta d -> onDelta(d.text());
            case CliQueryEvent.ToolCall tc -> onToolCall(tc);
            case CliQueryEvent.ToolResult tr -> onToolResult(tr);
            case CliQueryEvent.ToolUseSummary ts -> onToolUseSummary(ts);
            case CliQueryEvent.Error e -> "[error] " + e.message() + "\n";
            case CliQueryEvent.Completed c -> onCompleted();
            case CliQueryEvent.StopReason sr -> "";
            case CliQueryEvent.Usage u -> "";
            case CliQueryEvent.Result r -> onResult(r);
            case CliQueryEvent.Unknown u -> renderUnknown(u);
        };
    }

    private String onDelta(String text) {
        String prefix = hadDelta ? "" : "● ";
        hadDelta = true;
        return prefix + text;
    }

    private String onToolCall(CliQueryEvent.ToolCall tc) {
        toolCallNames.put(tc.toolCallId(), tc.toolName());
        toolCallCount++;
        String line = "● " + formatToolCall(tc.toolName(), tc.input()) + "\n";
        if (hadDelta) {
            hadDelta = false;
            return "\n" + line;
        }
        return line;
    }

    private String onToolResult(CliQueryEvent.ToolResult tr) {
        String registered = toolCallNames.remove(tr.toolCallId());
        String name = (tr.toolName() != null && !tr.toolName().isBlank())
            ? tr.toolName()
            : (registered != null ? registered : "unknown");
        String summary = CliToolSummaryFormatter.format(name, tr.summary(), tr.isError());
        if (tr.isError()) {
            return "  ⎿  " + name + " failed: " + summary + "\n";
        }
        return "  ⎿  " + summary + "\n";
    }

    private String onToolUseSummary(CliQueryEvent.ToolUseSummary summary) {
        return "  ⎿  Tool batch: " + summary.totalToolCalls()
            + " calls, " + summary.compactedResults()
            + " compacted, " + summary.errorResults()
            + " errors\n";
    }

    private String onCompleted() {
        if (hadDelta) {
            return "\n";
        }
        return "";
    }

    private String onResult(CliQueryEvent.Result result) {
        StringBuilder out = new StringBuilder();
        if (hadDelta) {
            out.append('\n');
            hadDelta = false;
        }
        out.append("\n✻ Cooked for ")
            .append(formatDuration(result.durationMs()))
            .append('\n');
        return out.toString();
    }

    private String renderUnknown(CliQueryEvent.Unknown unknown) {
        if ("SessionService$StreamCompletedEvent".equals(unknown.type())) {
            return "";
        }
        return "[unknown: " + unknown.type() + "]\n";
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    private String formatToolCall(String toolName, Object input) {
        String display = switch (toolName) {
            case "file_read" -> "Read";
            case "file_list" -> "List";
            case "file_glob" -> "Glob";
            case "file_search" -> "Search";
            case "file_write" -> "Write";
            case "file_edit" -> "Edit";
            case "powershell", "bash" -> "Bash";
            default -> toolName;
        };
        String arg = primaryArgument(toolName, input);
        return arg.isBlank() ? display : display + "(" + arg + ")";
    }

    @SuppressWarnings("unchecked")
    private String primaryArgument(String toolName, Object input) {
        if (!(input instanceof Map<?, ?> map)) {
            return "";
        }
        Object value = switch (toolName) {
            case "file_read", "file_list", "file_write", "file_edit" -> map.get("path");
            case "file_glob" -> map.get("pattern");
            case "file_search" -> map.get("pattern");
            case "powershell", "bash" -> map.get("command");
            default -> null;
        };
        if (value == null) {
            return "";
        }
        String text = value.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return truncateMiddle(text, 120);
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        long seconds = Math.round(durationMs / 1000.0);
        return seconds + "s";
    }

    private String truncateMiddle(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int left = Math.max(1, maxLength / 2 - 1);
        int right = Math.max(1, maxLength - left - 1);
        return text.substring(0, left) + "…" + text.substring(text.length() - right);
    }
}
