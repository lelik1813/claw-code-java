package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolResult;
import com.clawcode.agent.tools.ToolUseRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultBudgetTest {

    @Test
    void rejectsInvalidBudgetValues() {
        assertThatThrownBy(() -> new ToolResultBudget(999, 200))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxChars");
        assertThatThrownBy(() -> new ToolResultBudget(1000, 199))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excerptChars");
        assertThatThrownBy(() -> new ToolResultBudget(1000, 1200))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("excerptChars");
    }

    @Test
    void acceptsBoundaryBudgetValues() {
        var budget = new ToolResultBudget(1000, 200);

        assertThat(budget.maxChars()).isEqualTo(1000);
        assertThat(budget.excerptChars()).isEqualTo(200);
    }

    @Test
    void budgetedToolResultKeepsImmutableMetadata() {
        var result = new BudgetedToolResult(
            "c1", "file_read", "visible content", false, true,
            1000, 200, 800, "src/App.java");

        assertThat(result.toolCallId()).isEqualTo("c1");
        assertThat(result.toolName()).isEqualTo("file_read");
        assertThat(result.content()).isEqualTo("visible content");
        assertThat(result.isError()).isFalse();
        assertThat(result.compacted()).isTrue();
        assertThat(result.originalChars()).isEqualTo(1000);
        assertThat(result.shownChars()).isEqualTo(200);
        assertThat(result.omittedChars()).isEqualTo(800);
        assertThat(result.pathHint()).isEqualTo("src/App.java");
    }

    @Test
    void budgetedToolResultNormalizesNullStrings() {
        var result = new BudgetedToolResult(
            null, null, null, false, false, 0, 0, 0, null);

        assertThat(result.toolCallId()).isEmpty();
        assertThat(result.toolName()).isEmpty();
        assertThat(result.content()).isEmpty();
        assertThat(result.pathHint()).isEmpty();
    }

    @Test
    void budgetedToolResultAllowsEmptyNullSafeOutputAndErrors() {
        var output = new BudgetedToolResult("c1", "custom", null, false, false, 0, 0, 0, "");
        var error = new BudgetedToolResult("c2", "bash", null, true, false, 0, 0, 0, "");

        assertThat(output.content()).isEmpty();
        assertThat(output.isError()).isFalse();
        assertThat(error.content()).isEmpty();
        assertThat(error.isError()).isTrue();
    }

    @Test
    void rejectsNegativeBudgetedStats() {
        assertThatThrownBy(() -> new BudgetedToolResult("c1", "t", "", false, false, -1, 0, 0, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("originalChars");
        assertThatThrownBy(() -> new BudgetedToolResult("c1", "t", "", false, false, 0, -1, 0, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shownChars");
        assertThatThrownBy(() -> new BudgetedToolResult("c1", "t", "", false, false, 0, 0, -1, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("omittedChars");
    }

    @Test
    void outcomeRequiresAllPartsAndPreservesRawResult() {
        var request = new ToolUseRequest("c1", "file_read", Map.of("path", "src/App.java"));
        var raw = ToolResult.success("c1", "file_read", "raw output");
        var budgeted = new BudgetedToolResult(
            "c1", "file_read", "visible", false, false, 10, 10, 0, "src/App.java");

        var outcome = new ToolCallOutcome(request, raw, budgeted);

        assertThat(outcome.request()).isSameAs(request);
        assertThat(outcome.rawResult()).isSameAs(raw);
        assertThat(outcome.budgeted()).isSameAs(budgeted);
        assertThatThrownBy(() -> new ToolCallOutcome(null, raw, budgeted))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("request");
        assertThatThrownBy(() -> new ToolCallOutcome(request, null, budgeted))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rawResult");
        assertThatThrownBy(() -> new ToolCallOutcome(request, raw, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("budgeted");
    }

    @Test
    void pathHintFromExtractsRequestInputPathNullSafely() {
        assertThat(ToolCallOutcome.pathHintFrom(null)).isEmpty();
        assertThat(ToolCallOutcome.pathHintFrom(new ToolUseRequest("c1", "t", null))).isEmpty();
        assertThat(ToolCallOutcome.pathHintFrom(new ToolUseRequest("c1", "t", "not a map"))).isEmpty();
        assertThat(ToolCallOutcome.pathHintFrom(new ToolUseRequest("c1", "t", Map.of()))).isEmpty();
        assertThat(ToolCallOutcome.pathHintFrom(new ToolUseRequest("c1", "t", Map.of("path", "   ")))).isEmpty();
        assertThat(ToolCallOutcome.pathHintFrom(new ToolUseRequest("c1", "t", Map.of("path", "src/App.java"))))
            .isEqualTo("src/App.java");
    }
}
