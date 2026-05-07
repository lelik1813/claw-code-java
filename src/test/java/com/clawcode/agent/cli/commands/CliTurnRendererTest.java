package com.clawcode.agent.cli.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawcode.agent.cli.model.CliQueryEvent;
import com.clawcode.agent.tools.ToolErrorMessages;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliTurnRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CliTurnRenderer renderer = new CliTurnRenderer();

    @Test
    void fullSequence_started_toolCall_toolResult_delta_completed() {
        assertThat(renderer.render(new CliQueryEvent.Started())).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.ToolCall("c1", "file_read")))
            .isEqualTo("● Read\n");
        assertThat(renderer.render(new CliQueryEvent.ToolResult("c1", "file_read", false, "42 lines")))
            .isEqualTo("  ⎿  42 lines\n");
        assertThat(renderer.render(new CliQueryEvent.Started())).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Delta("Here is the answer")))
            .isEqualTo("● Here is the answer");
        assertThat(renderer.render(new CliQueryEvent.StopReason("end_turn"))).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Usage(100L, 50L))).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Completed())).isEqualTo("\n");
    }

    @Test
    void concatenatedTranscript_toolRoundThenText() {
        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.ToolCall("c1", "file_read")));
        sb.append(renderer.render(new CliQueryEvent.ToolResult("c1", "file_read", false, "42 lines")));
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta("final answer")));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString()).isEqualTo(
            "● Read\n"
            + "  ⎿  42 lines\n"
            + "● final answer\n");
    }

    @Test
    void concatenatedTranscript_textOnlyRound() {
        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta("hello")));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString()).isEqualTo("● hello\n");
    }

    @Test
    void textOnlyRound_started_delta_completed() {
        assertThat(renderer.render(new CliQueryEvent.Started())).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Delta("hello"))).isEqualTo("● hello");
        assertThat(renderer.render(new CliQueryEvent.Completed())).isEqualTo("\n");
    }

    @Test
    void toolCallWithInput_rendersDisplayNameAndPrimaryArgument() {
        assertThat(renderer.render(new CliQueryEvent.ToolCall(
            "c1", "file_read", Map.of("path", "src/App.java"))))
            .isEqualTo("● Read(src/App.java)\n");
    }

    @Test
    void shellToolCallWithInput_rendersBashLabel() {
        assertThat(renderer.render(new CliQueryEvent.ToolCall(
            "c1", "powershell", Map.of("command", "ls -la"))))
            .isEqualTo("● Bash(ls -la)\n");
    }

    @Test
    void toolCallAfterDeltaInsertsNewline() {
        renderer.render(new CliQueryEvent.Started());
        renderer.render(new CliQueryEvent.Delta("let me check"));
        assertThat(renderer.render(new CliQueryEvent.ToolCall("c1", "search")))
            .isEqualTo("\n● search\n");
    }

    @Test
    void deltaAfterToolResults_noExtraNewline() {
        renderer.render(new CliQueryEvent.Started());
        renderer.render(new CliQueryEvent.ToolCall("c1", "read"));
        renderer.render(new CliQueryEvent.ToolResult("c1", "read", false, "ok"));
        renderer.render(new CliQueryEvent.Started());
        assertThat(renderer.render(new CliQueryEvent.Delta("answer")))
            .isEqualTo("● answer");
    }

    @Test
    void toolResultError_showsFailedFormat() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "bash"));
        String result = renderer.render(new CliQueryEvent.ToolResult("c1", "bash", true, "exit 1"));
        assertThat(result).isEqualTo("  ⎿  bash failed: exit 1\n");
    }

    @Test
    void deniedFileWrite_showsFailedWithNoRepeatGuidance() {
        String summary = ToolErrorMessages.denied("file_write", "current tool permission policy");
        renderer.render(new CliQueryEvent.ToolCall("c1", "file_write"));
        String result = renderer.render(new CliQueryEvent.ToolResult("c1", "file_write", true, summary));
        assertThat(result).contains("file_write failed");
        assertThat(result).contains("Do not retry");
        assertThat(result).doesNotContain("[ERR");
    }

    @Test
    void unknownTool_showsFailedWithNoRepeatGuidance() {
        String summary = ToolErrorMessages.unknown("made_up_tool");
        renderer.render(new CliQueryEvent.ToolCall("c1", "made_up_tool"));
        String result = renderer.render(new CliQueryEvent.ToolResult("c1", "made_up_tool", true, summary));
        assertThat(result).contains("made_up_tool failed");
        assertThat(result).contains("Do not retry");
        assertThat(result).doesNotContain("[ERR");
    }

    @Test
    void multipleToolCallsInOneRound() {
        renderer.render(new CliQueryEvent.Started());
        assertThat(renderer.render(new CliQueryEvent.ToolCall("a", "read")))
            .isEqualTo("● read\n");
        assertThat(renderer.render(new CliQueryEvent.ToolCall("b", "search")))
            .isEqualTo("● search\n");
        assertThat(renderer.render(new CliQueryEvent.ToolResult("a", "read", false, "data")))
            .isEqualTo("  ⎿  data\n");
        assertThat(renderer.render(new CliQueryEvent.ToolResult("b", "search", false, "found")))
            .isEqualTo("  ⎿  found\n");
        renderer.render(new CliQueryEvent.Started());
        assertThat(renderer.render(new CliQueryEvent.Delta("done")))
            .isEqualTo("● done");
        assertThat(renderer.render(new CliQueryEvent.Completed())).isEqualTo("\n");
    }

    @Test
    void toolUseSummaryRendersConciseBatchLine() {
        String rawSummary = "[tool batch summary]\n"
            + "round: 0\n"
            + "total_tool_calls: 4\n"
            + "paths: src/A.java, src/B.java";

        String rendered = renderer.render(new CliQueryEvent.ToolUseSummary(
            0, 4, 1, 2, rawSummary));

        assertThat(rendered).isEqualTo("  ⎿  Tool batch: 4 calls, 1 compacted, 2 errors\n");
        assertThat(rendered).doesNotContain("src/A.java");
        assertThat(rendered).doesNotContain("[tool batch summary]");
    }

    @Test
    void toolUseSummaryAfterToolResultsBeforeFinalAnswer() {
        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.ToolCall("a", "read")));
        sb.append(renderer.render(new CliQueryEvent.ToolResult("a", "read", false, "42 lines")));
        sb.append(renderer.render(new CliQueryEvent.ToolUseSummary(0, 1, 1, 0, "hidden")));
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta("done")));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString()).isEqualTo(
            "● read\n"
            + "  ⎿  42 lines\n"
            + "  ⎿  Tool batch: 1 calls, 1 compacted, 0 errors\n"
            + "● done\n");
    }

    @Test
    void toolCallCountTracksCalls() {
        renderer.render(new CliQueryEvent.ToolCall("a", "t1"));
        renderer.render(new CliQueryEvent.ToolCall("b", "t2"));
        renderer.render(new CliQueryEvent.ToolCall("c", "t3"));
        assertThat(renderer.toolCallCount()).isEqualTo(3);
    }

    @Test
    void unknownEventRenders() {
        assertThat(renderer.render(new CliQueryEvent.Unknown("future_type")))
            .isEqualTo("[unknown: future_type]\n");
    }

    @Test
    void resultEventDeserializesAndRendersSilently() throws Exception {
        String json = """
            {"type":"result","success":true,"stop_reason":"end_turn","usage":{"inputTokens":100,"outputTokens":50},"duration_ms":1234,"num_turns":2,"permission_denials":0}
            """;

        CliQueryEvent event = mapper.readValue(json, CliQueryEvent.class);

        assertThat(event).isInstanceOf(CliQueryEvent.Result.class);
        assertThat(renderer.render(event)).isEqualTo("\n✻ Cooked for 1s\n");
    }

    @Test
    void resultEventDoesNotChangeFinalAnswerOutput() {
        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta("final answer")));
        sb.append(renderer.render(new CliQueryEvent.Result(true, "end_turn",
            new CliQueryEvent.ResultUsage(10L, 5L), 100L, 1, 0)));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString()).isEqualTo("● final answer\n\n✻ Cooked for 100ms\n");
    }

    @Test
    void contextTooLargeFailureRendersOnlyUserFacingText() {
        String message = "Context is too large for this model request. "
            + "Run /compact or start a new session, then retry.";

        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta(message)));
        sb.append(renderer.render(new CliQueryEvent.StopReason("context_too_large")));
        sb.append(renderer.render(new CliQueryEvent.Result(false, "context_too_large",
            new CliQueryEvent.ResultUsage(0L, 0L), 10L, 1, 0)));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString())
            .isEqualTo("● " + message + "\n\n✻ Cooked for 10ms\n")
            .doesNotContain("[error]")
            .doesNotContain("QueryResultEvent")
            .doesNotContain("stack trace");
    }

    @Test
    void maxOutputTokensFailureRendersOnlyUserFacingText() {
        String message = "Partial answer. The response stopped because max_output_tokens "
            + "was reached before completion. Start a new turn to continue.";

        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta(message)));
        sb.append(renderer.render(new CliQueryEvent.StopReason("max_output_tokens")));
        sb.append(renderer.render(new CliQueryEvent.Result(false, "max_output_tokens",
            new CliQueryEvent.ResultUsage(20L, 40L), 50L, 2, 0)));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString())
            .isEqualTo("● " + message + "\n\n✻ Cooked for 50ms\n")
            .doesNotContain("[error]")
            .doesNotContain("QueryResultEvent")
            .doesNotContain("stack trace");
    }

    @Test
    void errorEventRenders() {
        assertThat(renderer.render(new CliQueryEvent.Error("boom", "E001")))
            .isEqualTo("[error] boom\n");
    }

    @Test
    void internalStreamCompletedEventIsSilent() {
        assertThat(renderer.render(new CliQueryEvent.Unknown("SessionService$StreamCompletedEvent")))
            .isEmpty();
    }

    @Test
    void completedWithoutDelta_returnsEmpty() {
        renderer.render(new CliQueryEvent.Started());
        renderer.render(new CliQueryEvent.ToolCall("c1", "t"));
        renderer.render(new CliQueryEvent.ToolResult("c1", "t", false, "ok"));
        assertThat(renderer.render(new CliQueryEvent.Completed())).isEmpty();
    }

    @Test
    void toolResultFallbackNameFromToolCallId() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "my_tool"));
        String result = renderer.render(
            new CliQueryEvent.ToolResult("c1", "", false, "done"));
        assertThat(result).isEqualTo("  ⎿  done\n");
    }

    @Test
    void toolResultNullToolName_fallsBackToRegistered() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "real_name"));
        String result = renderer.render(
            new CliQueryEvent.ToolResult("c1", null, true, "bad"));
        assertThat(result).isEqualTo("  ⎿  real_name failed: bad\n");
    }

    @Test
    void noLegacyBracketFormatInToolEvents() {
        String callResult = renderer.render(new CliQueryEvent.ToolCall("c1", "grep"));
        String okResult = renderer.render(new CliQueryEvent.ToolResult("c1", "grep", false, "found 3"));
        assertThat(callResult).doesNotStartWith("[tool:");
        assertThat(okResult).doesNotStartWith("[OK");
        assertThat(okResult).doesNotStartWith("[ERR");
    }

    @Test
    void fileReadMultiline_compactLinesCount() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "file_read"));
        String result = renderer.render(new CliQueryEvent.ToolResult(
            "c1", "file_read", false, "line1\nline2\nline3\nline4\nline5"));
        assertThat(result).isEqualTo("  ⎿  5 lines\n");
    }

    @Test
    void fileGlobResult_compactMatchCount() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "file_glob"));
        String result = renderer.render(new CliQueryEvent.ToolResult(
            "c1", "file_glob", false,
            "[{path=src/App.java}, {path=src/Util.java}]"));
        assertThat(result).isEqualTo("  ⎿  2 files matched\n");
    }

    @Test
    void fileSearchResult_compactResultCount() {
        renderer.render(new CliQueryEvent.ToolCall("c1", "file_search"));
        String result = renderer.render(new CliQueryEvent.ToolResult(
            "c1", "file_search", false, "App.java:10:TODO\nUtil.java:5:FIXME\n"));
        assertThat(result).isEqualTo("  ⎿  2 results\n");
    }

    @Test
    void unknownTool_longSummary_truncated() {
        String longSummary = "x".repeat(300);
        renderer.render(new CliQueryEvent.ToolCall("c1", "custom"));
        String result = renderer.render(new CliQueryEvent.ToolResult(
            "c1", "custom", false, longSummary));
        assertThat(result).startsWith("  ⎿  ");
        assertThat(result).endsWith("...\n");
        assertThat(result.length()).isLessThan(longSummary.length());
    }

    @Test
    void maxRoundStop_rendersAsPlainTextNotError() {
        String maxRoundMessage = "I stopped because the maximum tool rounds limit was reached. "
            + "If you need a longer conversation, please start a new session "
            + "or try consolidating your requests.";

        assertThat(renderer.render(new CliQueryEvent.Started())).isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Delta(maxRoundMessage)))
            .isEqualTo("● " + maxRoundMessage)
            .doesNotStartWith("[ERR")
            .doesNotStartWith("[error")
            .doesNotContain("stack trace")
            .doesNotContain("infrastructure");
        assertThat(renderer.render(new CliQueryEvent.StopReason("max_tool_rounds")))
            .isEmpty();
        assertThat(renderer.render(new CliQueryEvent.Completed()))
            .isEqualTo("\n");
    }

    @Test
    void maxRoundStop_concatenatedTranscript_looksLikeNormalAnswer() {
        String maxRoundMessage = "I stopped because the maximum tool rounds limit was reached. "
            + "If you need a longer conversation, please start a new session "
            + "or try consolidating your requests.";

        StringBuilder sb = new StringBuilder();
        sb.append(renderer.render(new CliQueryEvent.Started()));
        sb.append(renderer.render(new CliQueryEvent.Delta(maxRoundMessage)));
        sb.append(renderer.render(new CliQueryEvent.StopReason("max_tool_rounds")));
        sb.append(renderer.render(new CliQueryEvent.Completed()));

        assertThat(sb.toString()).isEqualTo("● " + maxRoundMessage + "\n");
    }
}
