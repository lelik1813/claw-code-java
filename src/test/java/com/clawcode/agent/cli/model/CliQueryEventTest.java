package com.clawcode.agent.cli.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliQueryEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void parseStarted() throws Exception {
        var event = mapper.readValue("{\"type\":\"started\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Started.class);
    }

    @Test
    void parseDelta() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"delta\",\"text\":\"hello world\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Delta.class);
        assertThat(((CliQueryEvent.Delta) event).text()).isEqualTo("hello world");
    }

    @Test
    void parseCompleted() throws Exception {
        var event = mapper.readValue("{\"type\":\"completed\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Completed.class);
    }

    @Test
    void parseError() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"error\",\"message\":\"boom\",\"code\":\"ERR_001\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Error.class);
        assertThat(((CliQueryEvent.Error) event).message()).isEqualTo("boom");
        assertThat(((CliQueryEvent.Error) event).code()).isEqualTo("ERR_001");
    }

    @Test
    void parseToolCall() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\"}",
            CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.ToolCall.class);
        assertThat(((CliQueryEvent.ToolCall) event).toolName()).isEqualTo("file_read");
    }

    @Test
    void parseToolCallInput() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\","
                + "\"input\":{\"path\":\"src/App.java\"}}",
            CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.ToolCall.class);
        assertThat(((CliQueryEvent.ToolCall) event).input()).isNotNull();
    }

    @Test
    void parseToolResult() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\","
                + "\"isError\":false,\"summary\":\"file contents\"}",
            CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.ToolResult.class);
        assertThat(((CliQueryEvent.ToolResult) event).summary()).isEqualTo("file contents");
        assertThat(((CliQueryEvent.ToolResult) event).isError()).isFalse();
    }

    @Test
    void parseToolUseSummary() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"tool_use_summary\",\"round\":0,\"total_tool_calls\":4,"
                + "\"compacted_results\":1,\"error_results\":2,"
                + "\"paths\":[\"src/A.java\"],\"summary\":\"raw summary text should stay hidden\"}",
            CliQueryEvent.class);

        assertThat(event).isInstanceOf(CliQueryEvent.ToolUseSummary.class);
        var summary = (CliQueryEvent.ToolUseSummary) event;
        assertThat(summary.round()).isZero();
        assertThat(summary.totalToolCalls()).isEqualTo(4);
        assertThat(summary.compactedResults()).isEqualTo(1);
        assertThat(summary.errorResults()).isEqualTo(2);
        assertThat(summary.summary()).isEqualTo("raw summary text should stay hidden");
    }

    @Test
    void parseToolUseSummaryRejectsCamelCaseCounts() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"tool_use_summary\",\"totalToolCalls\":4,"
                + "\"compactedResults\":1,\"errorResults\":2,\"summary\":\"s\"}",
            CliQueryEvent.class);

        assertThat(event).isInstanceOf(CliQueryEvent.ToolUseSummary.class);
        var summary = (CliQueryEvent.ToolUseSummary) event;
        assertThat(summary.totalToolCalls()).isZero();
        assertThat(summary.compactedResults()).isZero();
        assertThat(summary.errorResults()).isZero();
    }

    @Test
    void tolerantParsing_extraFieldsIgnored() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"delta\",\"text\":\"hi\",\"futureField\":42,\"nested\":{\"a\":1}}",
            CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Delta.class);
        assertThat(((CliQueryEvent.Delta) event).text()).isEqualTo("hi");
    }

    @Test
    void tolerantParsing_nullFields() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"error\",\"message\":null,\"code\":null}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Error.class);
        assertThat(((CliQueryEvent.Error) event).message()).isNull();
    }

    @Test
    void tolerantParsing_emptyObject_fallsBackToUnknown() throws Exception {
        var event = mapper.readValue("{}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Unknown.class);
        assertThat(((CliQueryEvent.Unknown) event).type()).isNull();
    }

    @Test
    void parseUnknownType_fallsBackToUnknown() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"future_event\",\"data\":\"something\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Unknown.class);
        assertThat(((CliQueryEvent.Unknown) event).type()).isEqualTo("future_event");
    }

    @Test
    void parseStopReason() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"stop_reason\",\"reason\":\"end_turn\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.StopReason.class);
        assertThat(((CliQueryEvent.StopReason) event).reason()).isEqualTo("end_turn");
    }

    @Test
    void parseStopReasonToolUse() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"stop_reason\",\"reason\":\"tool_use\"}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.StopReason.class);
        assertThat(((CliQueryEvent.StopReason) event).reason()).isEqualTo("tool_use");
    }

    @Test
    void parseUsage() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"usage\",\"inputTokens\":150,\"outputTokens\":42}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Usage.class);
        assertThat(((CliQueryEvent.Usage) event).inputTokens()).isEqualTo(150);
        assertThat(((CliQueryEvent.Usage) event).outputTokens()).isEqualTo(42);
    }

    @Test
    void parseUsageWithNullInputTokens() throws Exception {
        var event = mapper.readValue(
            "{\"type\":\"usage\",\"inputTokens\":null,\"outputTokens\":10}", CliQueryEvent.class);
        assertThat(event).isInstanceOf(CliQueryEvent.Usage.class);
        assertThat(((CliQueryEvent.Usage) event).inputTokens()).isNull();
        assertThat(((CliQueryEvent.Usage) event).outputTokens()).isEqualTo(10);
    }

    @Test
    void backwardCompat_allExistingTypesStillParse() throws Exception {
        String[] payloads = {
            "{\"type\":\"started\"}",
            "{\"type\":\"delta\",\"text\":\"x\"}",
            "{\"type\":\"completed\"}",
            "{\"type\":\"error\",\"message\":\"e\",\"code\":\"c\"}",
            "{\"type\":\"tool_call\",\"toolCallId\":\"c\",\"toolName\":\"t\"}",
            "{\"type\":\"tool_result\",\"toolCallId\":\"c\",\"toolName\":\"t\",\"isError\":false,\"summary\":\"s\"}",
            "{\"type\":\"tool_use_summary\",\"total_tool_calls\":2,\"compacted_results\":1,\"error_results\":0,\"summary\":\"s\"}"
        };
        Class<?>[] expected = {
            CliQueryEvent.Started.class,
            CliQueryEvent.Delta.class,
            CliQueryEvent.Completed.class,
            CliQueryEvent.Error.class,
            CliQueryEvent.ToolCall.class,
            CliQueryEvent.ToolResult.class,
            CliQueryEvent.ToolUseSummary.class
        };
        for (int i = 0; i < payloads.length; i++) {
            var event = mapper.readValue(payloads[i], CliQueryEvent.class);
            assertThat(event).as("payload[%d] = %s", i, payloads[i]).isInstanceOf(expected[i]);
        }
    }
}
