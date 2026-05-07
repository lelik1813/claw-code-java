package com.clawcode.agent.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;
import com.clawcode.agent.cli.commands.CliTurnRenderer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class StreamAttachCommandTest {

    private MockWebServer server;
    private StringWriter outWriter;
    private StringWriter errWriter;
    private AgentCliApplication app;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        outWriter = new StringWriter();
        errWriter = new StringWriter();

        app = new AgentCliApplication();
        app.client = new HttpAgentApiClient(
            new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private int execute(String... args) {
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));
        return cmd.execute(args);
    }

    private String out() { return outWriter.toString().trim(); }
    private String err() { return errWriter.toString().trim(); }

    // ── happy path ──────────────────────────────────────────

    @Nested
    class HappyPath {

        @Test
        void printsDeltaEvents() {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"line-1\"}\n\ndata: {\"type\":\"delta\",\"text\":\"line-2\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("line-1");
            assertThat(out()).contains("line-2");
        }

        @Test
        void printsAllEventTypes() {
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"thinking\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"read\",\"isError\":false,\"summary\":\"42 lines\"}\n\n"
                    + "data: {\"type\":\"tool_use_summary\",\"round\":0,\"total_tool_calls\":1,\"compacted_results\":1,\"error_results\":0,\"summary\":\"RAW SUMMARY SHOULD NOT PRINT\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("thinking");
            assertThat(out()).contains("● read");
            assertThat(out()).contains("42 lines");
            assertThat(out()).contains("Tool batch: 1 calls, 1 compacted, 0 errors");
            assertThat(out()).doesNotContain("RAW SUMMARY SHOULD NOT PRINT");
            assertThat(out()).doesNotContain("[started]");
            assertThat(out()).doesNotContain("[completed]");
        }

        @Test
        void printsErrorEvent() {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"error\",\"message\":\"rate limited\",\"code\":\"429\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("[error] rate limited");
        }

        @Test
        void emptyStream_completesWithoutError() {
            server.enqueue(new MockResponse()
                .setBody("")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void sendsCorrectRequest() throws InterruptedException {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"ok\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            execute("stream", "attach", "s-xyz");

            RecordedRequest req = server.takeRequest();
            assertThat(req.getMethod()).isEqualTo("GET");
            assertThat(req.getPath()).isEqualTo("/api/sessions/s-xyz/stream");
        }

        @Test
        void stopReasonAndUsageSuppressedFromOutput() {
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"delta\",\"text\":\"hello\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"end_turn\"}\n\n"
                    + "data: {\"type\":\"usage\",\"inputTokens\":10,\"outputTokens\":20}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\" world\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("hello world");
            assertThat(out()).doesNotContain("[completed]");
            assertThat(out()).doesNotContain("stop_reason");
            assertThat(out()).doesNotContain("usage");
        }

        @Test
        void contextTooLargeResultShowsOnlyUserFacingText() {
            String message = "Context is too large for this model request. "
                + "Run /compact or start a new session, then retry.";
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"" + message + "\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"context_too_large\"}\n\n"
                    + "data: {\"type\":\"result\",\"success\":false,\"stop_reason\":\"context_too_large\","
                    + "\"usage\":{\"inputTokens\":0,\"outputTokens\":0},\"duration_ms\":10,"
                    + "\"num_turns\":1,\"permission_denials\":0}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains(message);
            assertThat(out()).doesNotContain("[error]");
            assertThat(out()).doesNotContain("QueryResultEvent");
            assertThat(out()).doesNotContain("context_too_large");
        }

        @Test
        void maxOutputTokensResultShowsOnlyUserFacingText() {
            String message = "Partial answer. The response stopped because max_output_tokens "
                + "was reached before completion. Start a new turn to continue.";
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"" + message + "\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"max_output_tokens\"}\n\n"
                    + "data: {\"type\":\"result\",\"success\":false,\"stop_reason\":\"max_output_tokens\","
                    + "\"usage\":{\"inputTokens\":20,\"outputTokens\":40},\"duration_ms\":50,"
                    + "\"num_turns\":2,\"permission_denials\":0}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains(message);
            assertThat(out()).doesNotContain("[error]");
            assertThat(out()).doesNotContain("QueryResultEvent");
            assertThat(out()).doesNotContain("stop_reason");
        }

        @Test
        void toolResultError_showsFailedFormat() {
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"bash\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"bash\",\"isError\":true,\"summary\":\"exit 1\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("● Bash");
            assertThat(out()).contains("bash failed: exit 1");
        }

        @Test
        void flushesAfterEachRenderedEvent() {
            AtomicInteger flushCount = new AtomicInteger();
            StringWriter trackingWriter = new StringWriter();
            PrintWriter flushingOut = new PrintWriter(trackingWriter, false) {
                @Override
                public void flush() {
                    flushCount.incrementAndGet();
                    super.flush();
                }
            };

            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"hi\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"read\",\"isError\":false,\"summary\":\"ok\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            CommandLine cmd = new CommandLine(app);
            cmd.setOut(flushingOut);
            cmd.setErr(new PrintWriter(errWriter, true));
            int exit = cmd.execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            // 3 non-empty renders: delta("hi"), tool_call("read"), tool_result("ok")
            // completed returns empty because no delta in final sub-turn, so no flush
            assertThat(flushCount.get()).isEqualTo(3);
        }
    }

    // ── error paths ─────────────────────────────────────────

    @Nested
    class ErrorPaths {

        @Test
        void blankSessionId_usageError() {
            int exit = execute("stream", "attach", "   ");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("SESSION_ID must not be blank");
        }

        @Test
        void missingSessionId_usageError() {
            int exit = execute("stream", "attach");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void authError_mappedExitCode() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void forbidden_mappedExitCode() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Forbidden");
        }

        @Test
        void notFound_mappedExitCode() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("Session not found"));

            int exit = execute("stream", "attach", "missing");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("Not found");
        }

        @Test
        void rateLimited_mappedExitCode() {
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too Many Requests\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
            assertThat(err()).contains("Rate limited");
        }

        @Test
        void serverError_mappedExitCode() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("HTTP 500");
        }
    }

    // ── replay with --after-cursor ──────────────────────────

    @Nested
    class ReplayResume {

        @Test
        void replayThenAttach() {
            // First: replay response
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"user\",\"content\":\"hello\"}," +
                    "{\"role\":\"assistant\",\"content\":\"hi\"}" +
                    "],\"nextCursor\":2,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            // Then: live stream
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"new-msg\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "0");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("[user] hello");
            assertThat(out()).contains("[assistant] hi");
            assertThat(out()).contains("new-msg");
        }

        @Test
        void replayPaginated() throws InterruptedException {
            // Page 1: has more
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"user\",\"content\":\"msg-1\"}" +
                    "],\"nextCursor\":101,\"hasMore\":true}")
                .setHeader("Content-Type", "application/json"));

            // Page 2: final
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"assistant\",\"content\":\"msg-2\"}" +
                    "],\"nextCursor\":102,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            // Then: live stream
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"live\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "0");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("msg-1");
            assertThat(out()).contains("msg-2");
            assertThat(out()).contains("live");

            // Verify pagination requests
            RecordedRequest req1 = server.takeRequest();
            assertThat(req1.getPath()).contains("after=0");
            RecordedRequest req2 = server.takeRequest();
            assertThat(req2.getPath()).contains("after=101");
        }

        @Test
        void replayEmpty_skipsToAttach() {
            // Empty replay page
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[],\"nextCursor\":0,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            // Live stream
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"fresh\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "5");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("fresh");
            assertThat(out()).doesNotContain("[user]");
        }

        @Test
        void replayError_returnsMappedCode() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("Session not found"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "0");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("Not found");
        }

        @Test
        void noAfterCursor_skipsReplay() throws InterruptedException {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"direct\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("direct");

            // Only one request — no replay call
            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).isEqualTo("/api/sessions/s-1/stream");
        }
    }

    // ── CliTurnRenderer ─────────────────────────────────────

    @Nested
    class TurnRenderer {

        @Test
        void rendersUnknownType() {
            var renderer = new CliTurnRenderer();
            var event = new com.clawcode.agent.cli.model.CliQueryEvent.Unknown("future_type");
            assertThat(renderer.render(event)).isEqualTo("[unknown: future_type]\n");
        }

        @Test
        void rendersToolResultWithError() {
            var renderer = new CliTurnRenderer();
            renderer.render(new com.clawcode.agent.cli.model.CliQueryEvent.ToolCall("c1", "bash"));
            var event = new com.clawcode.agent.cli.model.CliQueryEvent.ToolResult(
                "c1", "bash", true, "exit code 1");
            assertThat(renderer.render(event)).isEqualTo("  ⎿  bash failed: exit code 1\n");
        }
    }
}
