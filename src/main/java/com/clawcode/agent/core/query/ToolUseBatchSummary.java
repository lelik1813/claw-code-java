package com.clawcode.agent.core.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public record ToolUseBatchSummary(
    int round,
    int totalToolCalls,
    int compactedResults,
    int errorResults,
    List<String> paths,
    String summaryText
) {
    private static final int MAX_PATHS = 20;

    public ToolUseBatchSummary {
        if (round < 0) {
            throw new IllegalArgumentException("round must not be negative");
        }
        if (totalToolCalls < 0) {
            throw new IllegalArgumentException("totalToolCalls must not be negative");
        }
        if (compactedResults < 0) {
            throw new IllegalArgumentException("compactedResults must not be negative");
        }
        if (errorResults < 0) {
            throw new IllegalArgumentException("errorResults must not be negative");
        }
        paths = List.copyOf(paths == null ? List.of() : paths);
        summaryText = summaryText == null ? "" : summaryText;
    }

    public static Optional<ToolUseBatchSummary> build(
        int round,
        List<ToolCallOutcome> outcomes,
        int toolSummaryMinCalls
    ) {
        if (toolSummaryMinCalls < 2) {
            throw new IllegalArgumentException("toolSummaryMinCalls must be at least 2");
        }
        List<ToolCallOutcome> ordered = outcomes == null ? List.of() : outcomes;
        int totalToolCalls = ordered.size();
        int compactedResults = 0;
        int errorResults = 0;
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        List<String> errorToolNames = new ArrayList<>();

        for (ToolCallOutcome outcome : ordered) {
            if (outcome == null) {
                continue;
            }
            BudgetedToolResult budgeted = outcome.budgeted();
            if (budgeted.compacted()) {
                compactedResults++;
            }
            if (budgeted.isError()) {
                errorResults++;
                errorToolNames.add(nonBlankOrUnknown(budgeted.toolName()));
            }
            addPath(paths, ToolCallOutcome.pathHintFrom(outcome.request()));
        }

        if (compactedResults == 0 && totalToolCalls < toolSummaryMinCalls) {
            return Optional.empty();
        }

        List<String> boundedPaths = paths.stream().limit(MAX_PATHS).toList();
        String summaryText = summaryText(round, totalToolCalls, compactedResults,
            errorResults, boundedPaths, errorToolNames);
        return Optional.of(new ToolUseBatchSummary(
            round, totalToolCalls, compactedResults, errorResults, boundedPaths, summaryText));
    }

    private static void addPath(LinkedHashSet<String> paths, String path) {
        if (path == null || path.isBlank() || paths.size() >= MAX_PATHS) {
            return;
        }
        paths.add(path);
    }

    private static String summaryText(
        int round,
        int totalToolCalls,
        int compactedResults,
        int errorResults,
        List<String> paths,
        List<String> errorToolNames
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append("[tool batch summary]\n");
        summary.append("round: ").append(round).append('\n');
        summary.append("total_tool_calls: ").append(totalToolCalls).append('\n');
        summary.append("compacted_results: ").append(compactedResults).append('\n');
        summary.append("error_results: ").append(errorResults);
        if (!paths.isEmpty()) {
            summary.append('\n').append("paths: ").append(String.join(", ", paths));
        }
        if (!errorToolNames.isEmpty()) {
            summary.append('\n').append("error_tools: ").append(String.join(", ", errorToolNames));
        }
        return summary.toString();
    }

    private static String nonBlankOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
