package com.clawcode.agent.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.clawcode.agent.cli.registry.CommandRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the REPL loop — covers the interactive path
 * that SlashCommandDispatcher and SlashCommandParser feed into.
 *
 * Groups: lifecycle | text dispatch | /help | /session | /stream |
 *         /replay | /mcp | /plugin | /clear | /history | unknown
 * Each group covers: happy-path, error rendering, edge cases.
 */
class ReplRunnerTest {

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private StringWriter outWriter;
    private CommandRegistry registry;
    private AgentApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        outWriter = new StringWriter();
        registry = new CommandRegistry();
        client = new HttpAgentApiClient(
            new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 2000));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String out() { return outWriter.toString().trim(); }

    private int runRepl(String input) {
        return new ReplRunner(
            new PrintWriter(outWriter, true),
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            client,
            registry
        ).run();
    }

    private int runReplWithHome(String input) {
        String savedHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            return runRepl(input);
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    private void enqueueCreateSession() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-test-123\",\"createdAt\":\"2026-04-23T12:00:00Z\"}")
            .setHeader("Content-Type", "application/json"));
    }

    private void enqueueAcceptMessage() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-test-123\",\"accepted\":true}")
            .setHeader("Content-Type", "application/json"));
    }

    // ══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Lifecycle {

        @Test
        void exitCommand_returnsOk() {
            int exit = runRepl("/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Free Code Java Agent");
            assertThat(out()).doesNotContain("no advertised tools");
            assertThat(out()).contains("Bye.");
        }

        @Test
        void quitCommand_returnsOk() {
            int exit = runRepl("/quit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void eof_returnsOk() {
            int exit = runRepl("");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void blankLine_continuesLoop() {
            int exit = runRepl("   \n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TEXT DISPATCH
    // ══════════════════════════════════════════════════════════════

    @Nested
    class TextDispatch {

        @Test
        void textWithoutSession_autoCreatesSession() {
            enqueueCreateSession();
            enqueueAcceptMessage();

            runRepl("hello\n/exit\n");

            assertThat(outWriter.toString()).contains("Auto-created session");
        }

        @Test
        void unicodeInput_preservesUtf8Content() throws Exception {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("привет\n/exit\n");

            var create = server.takeRequest();
            var stream = server.takeRequest();
            var message = server.takeRequest();

            assertThat(create.getPath()).isEqualTo("/api/sessions");
            assertThat(stream.getPath()).isEqualTo("/api/sessions/s-test-123/stream");
            assertThat(message.getBody().readUtf8()).contains("\"content\":\"привет\"");
        }

        @Test
        void textWithExistingSession_sendsMessage() {
            enqueueCreateSession();
            enqueueAcceptMessage();

            var runner = new ReplRunner(
                new PrintWriter(outWriter, true),
                new ByteArrayInputStream("/session new\nhello\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                client, registry);

            runner.run();

            assertThat(runner.currentSessionId()).isEqualTo("s-test-123");
        }

        @Test
        void autoSession_apiError_printsError() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            runRepl("hello\n/exit\n");

            assertThat(outWriter.toString()).contains("Error creating session");
        }

        @Test
        void autoSession_serverError_printsError() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            runRepl("hello\n/exit\n");

            assertThat(outWriter.toString()).contains("Error creating session");
        }

        @Test
        void textMessage_apiError_printsError() {
            enqueueCreateSession();
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too Many Requests\"}"));

            runRepl("/session new\nhello\n/exit\n");

            assertThat(outWriter.toString()).contains("Error:");
        }

        @Test
        void toolLoop_showsToolProgressAndFinalAnswer() {
            enqueueCreateSession();
            // SSE stream: initial Completed drains replay, then actual turn events
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\",\"isError\":false,\"summary\":\"5 lines\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"here is the answer\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("hello\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("● Read");
            assertThat(output).contains("5 lines");
            assertThat(output).contains("here is the answer");
        }

        @Test
        void toolOnlyRound_noEmptyOutput() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"bash\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"bash\",\"isError\":false,\"summary\":\"ok\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("run it\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("● Bash");
            assertThat(output).contains("ok");
            // No dangling text between tool result and next prompt
            int lastPrompt = output.lastIndexOf("❯");
            int toolOutput = output.lastIndexOf("ok");
            assertThat(lastPrompt).isGreaterThan(toolOutput);
        }

        @Test
        void deltaOnly_completedAddsNewline() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"quick reply\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("hi\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("quick reply");
            // "quick reply" followed by newline then next prompt
            assertThat(output).contains("quick reply\n");
        }

        @Test
        void toolResultError_showsFailedFormat() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"bash\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"bash\",\"isError\":true,\"summary\":\"exit 1\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("run it\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("● Bash");
            assertThat(output).contains("bash failed: exit 1");
        }

        @Test
        void serverErrorEventEndsTurnWithoutStreamTimeout() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"error\",\"message\":\"Anthropic API error: 400 BAD_REQUEST\",\"code\":\"model_error\"}\n\n"
                    + "data: {\"type\":\"result\",\"success\":false,\"stop_reason\":\"model_error\",\"usage\":null,\"duration_ms\":10,\"num_turns\":1,\"permission_denials\":0}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("list files\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("[error] Anthropic API error: 400 BAD_REQUEST");
            assertThat(output).doesNotContain("raw body");
            assertThat(output).doesNotContain("Stream timed out");
            assertThat(output).doesNotContain("Did not observe any item");
        }

        @Test
        void multiToolLoop_showsAllProgressAndFinalAnswer_beforeNextPrompt() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_glob\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"file_glob\",\"isError\":false,\"summary\":\"[{path=src/App.java}, {path=src/Util.java}]\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c2\",\"toolName\":\"file_read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c2\",\"toolName\":\"file_read\",\"isError\":false,\"summary\":\"line1\\nline2\\nline3\\nline4\\nline5\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c3\",\"toolName\":\"file_search\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c3\",\"toolName\":\"file_search\",\"isError\":false,\"summary\":\"App.java:10:TODO\\nUtil.java:5:FIXME\"}\n\n"
                    + "data: {\"type\":\"tool_use_summary\",\"round\":1,\"total_tool_calls\":3,\"compacted_results\":1,\"error_results\":0,\"summary\":\"RAW SUMMARY SHOULD NOT PRINT\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"The project has 2 files with 3 issues.\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("analyze\n/exit\n");

            String output = outWriter.toString();
            // All tool calls visible
            assertThat(output).contains("● Glob");
            assertThat(output).contains("2 files matched");
            assertThat(output).contains("● Read");
            assertThat(output).contains("5 lines");
            assertThat(output).contains("● Search");
            assertThat(output).contains("2 results");
            assertThat(output).contains("Tool batch: 3 calls, 1 compacted, 0 errors");
            assertThat(output).doesNotContain("RAW SUMMARY SHOULD NOT PRINT");
            // Final answer visible before next prompt
            assertThat(output).contains("The project has 2 files with 3 issues.");
            int answerPos = output.indexOf("The project has 2 files with 3 issues.");
            int promptPos = output.indexOf("❯", answerPos);
            assertThat(promptPos).isGreaterThan(answerPos);
        }

        @Test
        void multiToolLoop_promptAppearsAfterCompletedTurn() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"file_read\",\"isError\":false,\"summary\":\"10 lines\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"done\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            String input = "first\nsecond\n/exit\n";
            runRepl(input);

            String output = outWriter.toString();
            assertThat(output).contains("● Read");
            assertThat(output).contains("10 lines");
            assertThat(output).contains("done");
            // "done" must appear before the second prompt (for "second" input)
            int donePos = output.indexOf("done");
            int firstPromptAfterDone = output.indexOf("❯", donePos);
            assertThat(firstPromptAfterDone).isGreaterThan(donePos);
        }

        @Test
        void intermediateCompletedDoesNotEndSubscription_beforeFinalAnswer() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"file_glob\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"file_glob\",\"isError\":false,\"summary\":\"3 files matched\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"tool_use\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c2\",\"toolName\":\"file_read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c2\",\"toolName\":\"file_read\",\"isError\":false,\"summary\":\"42 lines\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"tool_use\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n"
                    + "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"Here is the final answer.\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"end_turn\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
            enqueueAcceptMessage();

            runRepl("analyze\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("● Glob");
            assertThat(output).contains("3 files matched");
            assertThat(output).contains("● Read");
            assertThat(output).contains("42 lines");
            assertThat(output).contains("Here is the final answer.");
            // Final answer before next prompt
            int answerPos = output.indexOf("Here is the final answer.");
            int promptPos = output.indexOf("❯", answerPos);
            assertThat(promptPos).isGreaterThan(answerPos);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /help
    // ══════════════════════════════════════════════════════════════

    @Nested
    class HelpCommand {

        @Test
        void showsSlashCommands() {
            runRepl("/help\n/exit\n");
            assertThat(outWriter.toString()).contains("Slash commands:");
            assertThat(outWriter.toString()).contains("/session");
            assertThat(outWriter.toString()).contains("/exit");
            assertThat(outWriter.toString()).contains("/help");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /session
    // ══════════════════════════════════════════════════════════════

    @Nested
    class SessionCommand {

        @Test
        void sessionNew_createsSession() {
            enqueueCreateSession();
            var runner = new ReplRunner(
                new PrintWriter(outWriter, true),
                new ByteArrayInputStream("/session new\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                client, registry);

            int exit = runner.run();

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("Session created: s-test-123");
            assertThat(runner.currentSessionId()).isEqualTo("s-test-123");
        }

        @Test
        void sessionSwitch_setsSessionId() {
            runRepl("/session abc-123\n/exit\n");
        }

        @Test
        void sessionNew_apiError_401() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));
            runRepl("/session new\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
        }

        @Test
        void sessionNew_apiError_403() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));
            runRepl("/session new\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
        }

        @Test
        void sessionNew_apiError_409() {
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session already exists"));
            runRepl("/session new\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Conflict");
        }

        @Test
        void sessionNew_apiError_500() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));
            runRepl("/session new\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /stream
    // ══════════════════════════════════════════════════════════════

    @Nested
    class StreamCommand {

        @Test
        void stream_attachesWithCurrentSession() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"live-update\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            runRepl("/session new\n/stream\n/exit\n");

            assertThat(outWriter.toString()).contains("live-update");
        }

        @Test
        void stream_withExplicitId() {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"explicit\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            runRepl("/stream s-explicit\n/exit\n");

            assertThat(outWriter.toString()).contains("explicit");
        }

        @Test
        void stream_noSession_printsError() {
            runRepl("/stream\n/exit\n");
            assertThat(outWriter.toString()).contains("no active session");
        }

        @Test
        void stream_apiError_404() {
            enqueueCreateSession();
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("Session not found"));
            runRepl("/session new\n/stream\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
        }

        @Test
        void stream_apiError_500() {
            enqueueCreateSession();
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));
            runRepl("/session new\n/stream\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("HTTP 500");
        }

        @Test
        void stream_suppressesStopReasonAndUsage() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"delta\",\"text\":\"hello\"}\n\n"
                    + "data: {\"type\":\"stop_reason\",\"reason\":\"end_turn\"}\n\n"
                    + "data: {\"type\":\"usage\",\"inputTokens\":10,\"outputTokens\":20}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\" world\"}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            runRepl("/session new\n/stream\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains("hello");
            assertThat(output).contains("world");
            assertThat(output).doesNotContain("stop_reason");
            assertThat(output).doesNotContain("usage");
        }

        @Test
        void stream_contextTooLargeResultShowsOnlyUserFacingText() {
            String message = "Context is too large for this model request. "
                + "Run /compact or start a new session, then retry.";
            enqueueCreateSession();
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

            runRepl("/session new\n/stream\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains(message);
            assertThat(output).doesNotContain("[error]");
            assertThat(output).doesNotContain("QueryResultEvent");
            assertThat(output).doesNotContain("context_too_large");
        }

        @Test
        void stream_maxOutputTokensResultShowsOnlyUserFacingText() {
            String message = "Partial answer. The response stopped because max_output_tokens "
                + "was reached before completion. Start a new turn to continue.";
            enqueueCreateSession();
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

            runRepl("/session new\n/stream\n/exit\n");

            String output = outWriter.toString();
            assertThat(output).contains(message);
            assertThat(output).doesNotContain("[error]");
            assertThat(output).doesNotContain("QueryResultEvent");
            assertThat(output).doesNotContain("stop_reason");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /replay
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ReplayCommand {

        @Test
        void replay_withCurrentSession() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"user\",\"content\":\"hi\"}," +
                    "{\"role\":\"assistant\",\"content\":\"hello\"}" +
                    "],\"nextCursor\":2,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            runRepl("/session new\n/replay\n/exit\n");

            assertThat(outWriter.toString()).contains("[user] hi");
            assertThat(outWriter.toString()).contains("[assistant] hello");
        }

        @Test
        void replay_empty() {
            enqueueCreateSession();
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[],\"nextCursor\":0,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            runRepl("/session new\n/replay\n/exit\n");

            assertThat(outWriter.toString()).contains("(no messages)");
        }

        @Test
        void replay_noSession_printsError() {
            runRepl("/replay\n/exit\n");
            assertThat(outWriter.toString()).contains("no active session");
        }

        @Test
        void replay_apiError_401() {
            enqueueCreateSession();
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));
            runRepl("/session new\n/replay\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
        }

        @Test
        void replay_apiError_500() {
            enqueueCreateSession();
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));
            runRepl("/session new\n/replay\n/exit\n");
            assertThat(outWriter.toString()).contains("Error:");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /mcp  (via REPL loop)
    // ══════════════════════════════════════════════════════════════

    @Nested
    class McpViaRepl {

        @Test
        void mcpList_empty() {
            int exit = runReplWithHome("/mcp list\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("(no mcp servers)");
        }

        @Test
        void mcpAddThenList() {
            int exit = runReplWithHome(
                "/mcp add demo --url http://localhost:3000\n/mcp list\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("Added MCP server 'demo'");
            assertThat(outWriter.toString()).contains("demo");
        }

        @Test
        void mcpEmptyArgs_showsUsage() {
            int exit = runReplWithHome("/mcp\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("Usage: /mcp");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /plugin  (via REPL loop)
    // ══════════════════════════════════════════════════════════════

    @Nested
    class PluginViaRepl {

        @Test
        void pluginList_empty() {
            int exit = runReplWithHome("/plugin list\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("(no plugins installed)");
        }

        @Test
        void pluginInstallThenList() throws IOException {
            var pluginDir = tempDir.resolve("plugin-repl");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("plugin.json"),
                "{ \"name\": \"repl-test\", \"id\": \"repl-v1\", \"version\": \"1.0.0\" }");

            int exit = runReplWithHome(
                "/plugin install " + pluginDir + "\n/plugin list\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("Installed plugin 'repl-test'");
            assertThat(outWriter.toString()).contains("repl-test");
        }

        @Test
        void pluginEmptyArgs_showsUsage() {
            int exit = runReplWithHome("/plugin\n/exit\n");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(outWriter.toString()).contains("Usage: /plugin");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /clear
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ClearCommand {

        @Test
        void clear_outputsAnsiEscape() {
            runRepl("/clear\n/exit\n");
            assertThat(outWriter.toString()).contains("\033[H\033[2J");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /history
    // ══════════════════════════════════════════════════════════════

    @Nested
    class HistoryCommand {

        @Test
        void showsHistoryEntries() {
            runRepl("/session abc\n/history\n/exit\n");
            assertThat(outWriter.toString()).contains("/session abc");
            assertThat(outWriter.toString()).contains("/history");
        }

        @Test
        void emptyHistory_showsCurrentEntries() {
            runRepl("/history\n/exit\n");
            assertThat(outWriter.toString()).contains("/history");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UNKNOWN / INVALID
    // ══════════════════════════════════════════════════════════════

    @Nested
    class UnknownCommand {

        @Test
        void printsErrorAndContinues() {
            runRepl("/bogus\n/exit\n");
            assertThat(outWriter.toString()).contains("Unknown command: /bogus");
            assertThat(outWriter.toString()).contains("Bye.");
        }
    }

    @Nested
    class InvalidSlash {

        @Test
        void bareSlash_printsErrorAndContinues() {
            runRepl("/\n/exit\n");
            assertThat(outWriter.toString()).contains("empty command");
            assertThat(outWriter.toString()).contains("Bye.");
        }

        @Test
        void slashWithNumberStart_printsErrorAndContinues() {
            runRepl("/1abc\n/exit\n");
            assertThat(outWriter.toString()).contains("invalid command");
            assertThat(outWriter.toString()).contains("Bye.");
        }
    }
}
