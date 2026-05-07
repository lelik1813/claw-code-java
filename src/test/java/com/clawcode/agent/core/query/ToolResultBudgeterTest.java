package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultBudgeterTest {

    private final ToolResultBudgeter budgeter = new ToolResultBudgeter();
    private final ToolResultBudget budget = new ToolResultBudget(1000, 200);

    @Test
    void smallSuccessResultIsUnchanged() {
        var request = new ToolUseRequest("c1", "file_read", Map.of("path", "src/App.java"));
        var result = ToolResult.success("c1", "file_read", "small output");

        BudgetedToolResult budgeted = budgeter.budget(request, result, budget);

        assertThat(budgeted.content()).isEqualTo("small output");
        assertThat(budgeted.compacted()).isFalse();
        assertThat(budgeted.originalChars()).isEqualTo("small output".length());
        assertThat(budgeted.shownChars()).isEqualTo("small output".length());
        assertThat(budgeted.omittedChars()).isZero();
        assertThat(budgeted.pathHint()).isEqualTo("src/App.java");
    }

    @Test
    void largeReadResultIsCompactedWithPathAndHeadTail() {
        String content = "HEAD-" + "a".repeat(1100) + "-TAIL";
        var request = new ToolUseRequest("read-1", "file_read", Map.of("path", "src/Large.java"));

        BudgetedToolResult budgeted = budgeter.budget(
            request,
            ToolResult.success("read-1", "file_read", content),
            budget);

        assertThat(budgeted.compacted()).isTrue();
        assertThat(budgeted.content()).contains("[tool result compacted]");
        assertThat(budgeted.content()).contains("tool: file_read");
        assertThat(budgeted.content()).contains("tool_call_id: read-1");
        assertThat(budgeted.content()).contains("path: src/Large.java");
        assertThat(budgeted.content()).contains("original_chars: " + content.length());
        assertThat(budgeted.content()).contains("shown_chars: " + budgeted.shownChars());
        assertThat(budgeted.content()).contains("omitted_chars: " + budgeted.omittedChars());
        assertThat(budgeted.content()).contains("HEAD-");
        assertThat(budgeted.content()).contains("-TAIL");
        assertThat(budgeted.content()).contains("[... omitted middle ...]");
        assertThat(budgeted.originalChars()).isEqualTo(content.length());
        assertThat(budgeted.content().length()).isLessThanOrEqualTo(budget.maxChars());
        assertThat(budgeted.shownChars()).isLessThanOrEqualTo(budget.excerptChars());
        assertThat(budgeted.omittedChars()).isEqualTo(content.length() - budgeted.shownChars());
        assertThat(budgeted.pathHint()).isEqualTo("src/Large.java");
    }

    @Test
    void largeSearchResultKeepsFirstAndLastMatches() {
        String first = "first-match: src/A.java:1";
        String last = "last-match: src/Z.java:99";
        String content = first + "\n" + "middle\n".repeat(200) + last;
        var request = new ToolUseRequest("search-1", "file_search", Map.of("pattern", "TODO"));

        BudgetedToolResult budgeted = budgeter.budget(
            request,
            ToolResult.success("search-1", "file_search", content),
            budget);

        assertThat(budgeted.compacted()).isTrue();
        assertThat(budgeted.content()).contains(first);
        assertThat(budgeted.content()).contains(last);
        assertThat(budgeted.content()).doesNotContain("path:");
        assertThat(budgeted.pathHint()).isEmpty();
    }

    @Test
    void largeShellResultKeepsBeginningAndEnd() {
        String start = "PS> mvn test\n[INFO] start";
        String end = "[ERROR] final failure";
        String content = start + "\n" + "log line\n".repeat(200) + end;
        var request = new ToolUseRequest("shell-1", "powershell", Map.of("command", "mvn test"));

        BudgetedToolResult budgeted = budgeter.budget(
            request,
            ToolResult.success("shell-1", "powershell", content),
            budget);

        assertThat(budgeted.compacted()).isTrue();
        assertThat(budgeted.content()).contains("tool: powershell");
        assertThat(budgeted.content()).contains(start);
        assertThat(budgeted.content()).contains(end);
    }

    @Test
    void normalDeniedAndReadBeforeWriteErrorsAreUnchanged() {
        var request = new ToolUseRequest("write-1", "file_write", Map.of("path", "src/App.java"));
        String denied = "Tool 'file_write' is denied: current tool permission policy. Do not retry the same tool call; use an advertised tool or explain the limitation to the user.";
        String readBeforeWrite = "Refusing to overwrite 'src/App.java': read the existing file with file_read first, then retry with an updated plan.";

        BudgetedToolResult deniedResult = budgeter.budget(request,
            ToolResult.error("write-1", "file_write", denied), budget);
        BudgetedToolResult readBeforeWriteResult = budgeter.budget(request,
            ToolResult.error("write-1", "file_write", readBeforeWrite), budget);

        assertThat(deniedResult.compacted()).isFalse();
        assertThat(deniedResult.content()).isEqualTo(denied);
        assertThat(readBeforeWriteResult.compacted()).isFalse();
        assertThat(readBeforeWriteResult.content()).isEqualTo(readBeforeWrite);
        assertThat(readBeforeWriteResult.pathHint()).isEqualTo("src/App.java");
    }

    @Test
    void oversizedErrorIsCompacted() {
        String error = "ERROR-START" + "e".repeat(1100) + "ERROR-END";
        var request = new ToolUseRequest("shell-err", "powershell", null);

        BudgetedToolResult budgeted = budgeter.budget(
            request,
            ToolResult.error("shell-err", "powershell", error),
            budget);

        assertThat(budgeted.isError()).isTrue();
        assertThat(budgeted.compacted()).isTrue();
        assertThat(budgeted.content()).contains("[tool result compacted]");
        assertThat(budgeted.content()).contains("ERROR-START");
        assertThat(budgeted.content()).contains("ERROR-END");
        assertThat(budgeted.originalChars()).isEqualTo(error.length());
        assertThat(budgeted.content().length()).isLessThanOrEqualTo(budget.maxChars());
        assertThat(budgeted.shownChars()).isLessThanOrEqualTo(budget.excerptChars());
        assertThat(budgeted.omittedChars()).isEqualTo(error.length() - budgeted.shownChars());
    }

    @Test
    void compactedPayloadIncludesMetadataWithinMaxChars() {
        ToolResultBudget tightBudget = new ToolResultBudget(1000, 1000);
        String content = "HEAD-" + "x".repeat(3000) + "-TAIL";
        var request = new ToolUseRequest("tight-1", "file_read", Map.of("path", "src/Tight.java"));

        BudgetedToolResult budgeted = budgeter.budget(
            request,
            ToolResult.success("tight-1", "file_read", content),
            tightBudget);

        assertThat(budgeted.compacted()).isTrue();
        assertThat(budgeted.content().length()).isLessThanOrEqualTo(1000);
        assertThat(budgeted.shownChars()).isLessThanOrEqualTo(1000);
        assertThat(budgeted.omittedChars()).isEqualTo(content.length() - budgeted.shownChars());
        assertThat(budgeted.content()).contains("[tool result compacted]");
        assertThat(budgeted.content()).contains("tool: file_read");
        assertThat(budgeted.content()).contains("tool_call_id: tight-1");
        assertThat(budgeted.content()).contains("original_chars: " + content.length());
        assertThat(budgeted.content()).contains("omitted_chars: " + budgeted.omittedChars());
    }

    @Test
    void rejectsNullInputs() {
        var request = new ToolUseRequest("c1", "t", null);
        var result = ToolResult.success("c1", "t", "ok");

        assertThatThrownBy(() -> budgeter.budget(null, result, budget))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("request");
        assertThatThrownBy(() -> budgeter.budget(request, null, budget))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("result");
        assertThatThrownBy(() -> budgeter.budget(request, result, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("budget");
    }
}
