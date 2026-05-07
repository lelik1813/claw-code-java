package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolUseBatchSummaryTest {

    @Test
    void noSummaryForOneSmallCall() {
        var summary = ToolUseBatchSummary.build(0, List.of(outcome(
            "c1", "file_read", Map.of("path", "src/App.java"),
            false, false, "small visible content")), 4);

        assertThat(summary).isEmpty();
    }

    @Test
    void summaryForCompactedResultDoesNotIncludeRawOutput() {
        var summary = ToolUseBatchSummary.build(1, List.of(outcome(
            "c1", "file_read", Map.of("path", "src/Large.java"),
            true, false, "HEAD raw secret TAIL")), 4).orElseThrow();

        assertThat(summary.round()).isEqualTo(1);
        assertThat(summary.totalToolCalls()).isEqualTo(1);
        assertThat(summary.compactedResults()).isEqualTo(1);
        assertThat(summary.errorResults()).isZero();
        assertThat(summary.paths()).containsExactly("src/Large.java");
        assertThat(summary.summaryText())
            .contains("[tool batch summary]")
            .contains("total_tool_calls: 1")
            .contains("compacted_results: 1")
            .contains("paths: src/Large.java")
            .doesNotContain("raw secret");
    }

    @Test
    void summaryForMinCallCount() {
        var summary = ToolUseBatchSummary.build(2, List.of(
            outcome("c1", "file_read", Map.of("path", "src/A.java"), false, false, "a"),
            outcome("c2", "file_read", Map.of("path", "src/B.java"), false, false, "b"),
            outcome("c3", "file_search", Map.of("pattern", "TODO"), false, false, "c")
        ), 3).orElseThrow();

        assertThat(summary.round()).isEqualTo(2);
        assertThat(summary.totalToolCalls()).isEqualTo(3);
        assertThat(summary.compactedResults()).isZero();
        assertThat(summary.errorResults()).isZero();
        assertThat(summary.paths()).containsExactly("src/A.java", "src/B.java");
        assertThat(summary.summaryText()).contains("total_tool_calls: 3");
    }

    @Test
    void pathsAreUniqueOrderedAndBounded() {
        List<ToolCallOutcome> outcomes = java.util.stream.IntStream.range(0, 25)
            .mapToObj(i -> outcome(
                "c" + i,
                "file_read",
                Map.of("path", "src/File" + (i == 2 ? 1 : i) + ".java"),
                false,
                false,
                "content-" + i))
            .toList();

        var summary = ToolUseBatchSummary.build(3, outcomes, 4).orElseThrow();

        assertThat(summary.paths()).hasSize(20);
        assertThat(summary.paths().get(0)).isEqualTo("src/File0.java");
        assertThat(summary.paths().get(1)).isEqualTo("src/File1.java");
        assertThat(summary.paths()).doesNotHaveDuplicates();
        assertThat(summary.paths()).doesNotContain("src/File24.java");
    }

    @Test
    void errorCountAndErrorToolNamesArePreserved() {
        var summary = ToolUseBatchSummary.build(4, List.of(
            outcome("c1", "file_read", Map.of("path", "src/A.java"), false, true, "read failed"),
            outcome("c2", "powershell", Map.of("command", "mvn test"), false, true, "shell failed"),
            outcome("c3", "file_search", Map.of("path", "src"), false, false, "ok")
        ), 3).orElseThrow();

        assertThat(summary.errorResults()).isEqualTo(2);
        assertThat(summary.summaryText())
            .contains("error_results: 2")
            .contains("error_tools: file_read, powershell")
            .doesNotContain("read failed")
            .doesNotContain("shell failed");
    }

    @Test
    void rejectsInvalidMinCalls() {
        assertThatThrownBy(() -> ToolUseBatchSummary.build(0, List.of(), 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toolSummaryMinCalls");
    }

    private static ToolCallOutcome outcome(
        String id,
        String toolName,
        Object input,
        boolean compacted,
        boolean error,
        String rawContent
    ) {
        var request = new ToolUseRequest(id, toolName, input);
        var raw = error
            ? ToolResult.error(id, toolName, rawContent)
            : ToolResult.success(id, toolName, rawContent);
        var budgeted = new BudgetedToolResult(
            id, toolName, error ? "error summary" : "visible summary", error, compacted,
            rawContent.length(), compacted ? 20 : rawContent.length(),
            compacted ? Math.max(0, rawContent.length() - 20) : 0,
            ToolCallOutcome.pathHintFrom(request));
        return new ToolCallOutcome(request, raw, budgeted);
    }
}
