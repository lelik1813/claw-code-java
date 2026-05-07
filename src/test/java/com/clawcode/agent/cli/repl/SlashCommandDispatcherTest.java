package com.clawcode.agent.cli.repl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.clawcode.agent.cli.AgentApiClient;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
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
 * Unit tests for SlashCommandDispatcher — verifies each slash command handler
 * against the ReplContext stub, including happy-path, invalid args, and API errors.
 *
 * Groups: help | exit | session | stream | replay | history | clear |
 *         mcp | plugin | registered-not-implemented | unknown | custom
 */
class SlashCommandDispatcherTest {

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private StringWriter outWriter;
    private CommandRegistry registry;
    private SlashCommandDispatcher dispatcher;
    private StubContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        outWriter = new StringWriter();
        registry = new CommandRegistry();
        dispatcher = new SlashCommandDispatcher(registry);
        dispatcher.setMcpStore(new FileMcpConfigStore(tempDir.resolve("mcp-servers.json")));
        dispatcher.setPluginStore(new FilePluginConfigStore(tempDir.resolve("plugins.json")));
        ctx = new StubContext(outWriter, server);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String out() { return outWriter.toString().trim(); }

    private void clearOutput() {
        outWriter.getBuffer().setLength(0);
    }

    // ══════════════════════════════════════════════════════════════
    //  /help
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Help {

        @Test
        void listsEnabledCommands() {
            dispatcher.dispatch("help", "", ctx);
            assertThat(outWriter.toString()).contains("/session");
            assertThat(outWriter.toString()).contains("/exit");
            assertThat(outWriter.toString()).contains("Slash commands:");
        }

        @Test
        void showsPlainTextHint() {
            dispatcher.dispatch("help", "", ctx);
            assertThat(outWriter.toString()).contains("Any other text");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /exit / /quit
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Exit {

        @Test
        void exitRequestsExit() {
            dispatcher.dispatch("exit", "", ctx);
            assertThat(ctx.exitRequested).isTrue();
        }

        @Test
        void quitRequestsExit() {
            dispatcher.dispatch("quit", "", ctx);
            assertThat(ctx.exitRequested).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /session
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Session {

        @Test
        void sessionNew_createsSession() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-abc\",\"createdAt\":\"2026-04-24T00:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("session", "new", ctx);

            assertThat(ctx.sessionId).isEqualTo("s-abc");
            assertThat(outWriter.toString()).contains("Session created: s-abc");
        }

        @Test
        void sessionEmptyArg_createsSession() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-def\",\"createdAt\":\"2026-04-24T00:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("session", "", ctx);

            assertThat(ctx.sessionId).isEqualTo("s-def");
        }

        @Test
        void sessionSwitch_setsId() {
            dispatcher.dispatch("session", "existing-id", ctx);
            assertThat(ctx.sessionId).isEqualTo("existing-id");
            assertThat(outWriter.toString()).contains("Switched to session: existing-id");
        }

        // ── API errors ──

        @Test
        void sessionNew_401() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void sessionNew_403() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void sessionNew_404() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("no such resource"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Not found");
        }

        @Test
        void sessionNew_409() {
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session already exists"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Conflict");
        }

        @Test
        void sessionNew_422() {
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Invalid parameters"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Validation error");
        }

        @Test
        void sessionNew_429() {
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too many\"}"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Rate limited");
        }

        @Test
        void sessionNew_500() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            dispatcher.dispatch("session", "new", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /stream
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Stream {

        @Test
        void stream_attachesAndRendersEvents() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"hello\"}\n\ndata: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            dispatcher.dispatch("stream", "", ctx);

            assertThat(outWriter.toString()).isEqualTo("● hello\n");
        }

        @Test
        void stream_withExplicitSessionId() {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"world\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            dispatcher.dispatch("stream", "s-explicit", ctx);

            assertThat(outWriter.toString()).contains("world");
        }

        @Test
        void stream_noSession_printsError() {
            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("no active session");
        }

        @Test
        void stream_toolCallAndResult() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"tool_call\",\"toolName\":\"read\",\"callId\":\"c1\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolName\":\"read\",\"callId\":\"c1\",\"summary\":\"42 lines\"}\n\n"
                    + "data: {\"type\":\"tool_use_summary\",\"round\":0,\"total_tool_calls\":1,\"compacted_results\":1,\"error_results\":0,\"summary\":\"RAW SUMMARY SHOULD NOT PRINT\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            dispatcher.dispatch("stream", "", ctx);

            assertThat(outWriter.toString()).contains("● read");
            assertThat(outWriter.toString()).contains("42 lines");
            assertThat(outWriter.toString()).contains("Tool batch: 1 calls, 1 compacted, 0 errors");
            assertThat(outWriter.toString()).doesNotContain("RAW SUMMARY SHOULD NOT PRINT");
            assertThat(outWriter.toString()).doesNotContain("[tool:");
            assertThat(outWriter.toString()).doesNotContain("[result:");
        }

        // ── API errors ──

        @Test
        void stream_401() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void stream_403() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void stream_404() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("not found"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Not found");
        }

        @Test
        void stream_409() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session locked"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Conflict");
        }

        @Test
        void stream_422() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Invalid session"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Validation error");
        }

        @Test
        void stream_429() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too many\"}"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Rate limited");
        }

        @Test
        void stream_500() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            dispatcher.dispatch("stream", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /replay
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Replay {

        @Test
        void replay_showsMessages() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"user\",\"content\":\"hello\"}," +
                    "{\"role\":\"assistant\",\"content\":\"hi\"}" +
                    "],\"nextCursor\":2,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("replay", "", ctx);

            assertThat(outWriter.toString()).contains("[user] hello");
            assertThat(outWriter.toString()).contains("[assistant] hi");
        }

        @Test
        void replay_withExplicitSessionId() {
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"nextCursor\":1,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("replay", "s-other", ctx);

            assertThat(outWriter.toString()).contains("[user] test");
        }

        @Test
        void replay_empty() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[],\"nextCursor\":0,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("(no messages)");
        }

        @Test
        void replay_noSession_printsError() {
            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("no active session");
        }

        @Test
        void replay_hasMore_showsCursor() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[{\"role\":\"user\",\"content\":\"msg\"}],\"nextCursor\":101,\"hasMore\":true}")
                .setHeader("Content-Type", "application/json"));

            dispatcher.dispatch("replay", "", ctx);

            assertThat(outWriter.toString()).contains("cursor 101");
            assertThat(outWriter.toString()).contains("more available");
        }

        // ── API errors ──

        @Test
        void replay_401() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void replay_403() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Authentication failed");
        }

        @Test
        void replay_404() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("Session not found"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Not found");
        }

        @Test
        void replay_409() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session locked"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Conflict");
        }

        @Test
        void replay_422() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Invalid cursor"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Validation error");
        }

        @Test
        void replay_429() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too many\"}"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("Rate limited");
        }

        @Test
        void replay_500() {
            ctx.sessionId = "s-1";
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            dispatcher.dispatch("replay", "", ctx);
            assertThat(outWriter.toString()).contains("Error:");
            assertThat(outWriter.toString()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /history
    // ══════════════════════════════════════════════════════════════

    @Nested
    class History {

        @Test
        void showsEntries() {
            ctx.historyLines.add("/session new");
            ctx.historyLines.add("hello");

            dispatcher.dispatch("history", "", ctx);
            assertThat(outWriter.toString()).contains("/session new");
            assertThat(outWriter.toString()).contains("hello");
        }

        @Test
        void emptyHistory() {
            dispatcher.dispatch("history", "", ctx);
            assertThat(outWriter.toString()).contains("(no history)");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /clear
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Clear {

        @Test
        void outputsAnsiClear() {
            dispatcher.dispatch("clear", "", ctx);
            assertThat(outWriter.toString()).contains("\033[H\033[2J");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /mcp
    // ══════════════════════════════════════════════════════════════

    @Nested
    class McpSlash {

        @Test
        void mcpEmptyArgs_showsUsage() {
            dispatcher.dispatch("mcp", "", ctx);
            assertThat(outWriter.toString()).contains("Usage: /mcp");
        }

        @Test
        void mcpList_showsNoServers() {
            dispatcher.dispatch("mcp", "list", ctx);
            assertThat(outWriter.toString()).contains("no mcp servers");
        }

        @Test
        void mcpAddThenList() {
            dispatcher.dispatch("mcp", "add test-srv --url http://localhost:9999", ctx);
            assertThat(outWriter.toString()).contains("Added MCP server 'test-srv'");

            clearOutput();
            dispatcher.dispatch("mcp", "list", ctx);
            assertThat(outWriter.toString()).contains("test-srv");
        }

        @Test
        void mcpAddValidation() {
            dispatcher.dispatch("mcp", "add bad-name --type BOGUS", ctx);
            assertThat(outWriter.toString()).contains("invalid transport");
        }

        @Test
        void mcpAddDuplicate_rejected() {
            dispatcher.dispatch("mcp", "add dup --url http://localhost:3000", ctx);
            clearOutput();
            dispatcher.dispatch("mcp", "add dup --url http://localhost:4000", ctx);
            assertThat(outWriter.toString()).contains("already exists");
        }

        @Test
        void mcpRemoveThenList_empty() {
            dispatcher.dispatch("mcp", "add temp --url http://localhost:3000", ctx);
            clearOutput();
            dispatcher.dispatch("mcp", "remove temp", ctx);
            assertThat(outWriter.toString()).contains("Removed MCP server 'temp'");

            clearOutput();
            dispatcher.dispatch("mcp", "list", ctx);
            assertThat(outWriter.toString()).contains("(no mcp servers)");
        }

        @Test
        void mcpRemoveNonExisting() {
            dispatcher.dispatch("mcp", "remove no-such", ctx);
            assertThat(outWriter.toString()).contains("not found");
        }

        @Test
        void mcpTest_notFound() {
            dispatcher.dispatch("mcp", "test ghost", ctx);
            assertThat(outWriter.toString()).contains("not found");
        }

        @Test
        void mcpTest_httpOk() throws Exception {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            var url = server.url("/").toString();
            dispatcher.dispatch("mcp", "add live --url " + url, ctx);
            clearOutput();

            dispatcher.dispatch("mcp", "test live --timeout 3", ctx);
            assertThat(outWriter.toString()).contains("OK");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  /plugin
    // ══════════════════════════════════════════════════════════════

    @Nested
    class PluginSlash {

        @Test
        void pluginEmptyArgs_showsUsage() {
            dispatcher.dispatch("plugin", "", ctx);
            assertThat(outWriter.toString()).contains("Usage: /plugin");
            assertThat(outWriter.toString()).contains("list");
            assertThat(outWriter.toString()).contains("install");
            assertThat(outWriter.toString()).contains("remove");
            assertThat(outWriter.toString()).contains("enable");
            assertThat(outWriter.toString()).contains("disable");
        }

        @Test
        void pluginList_showsNoPlugins() {
            dispatcher.dispatch("plugin", "list", ctx);
            assertThat(outWriter.toString()).contains("(no plugins installed)");
        }

        @Test
        void pluginInstallThenList() throws Exception {
            var pluginDir = tempDir.resolve("plugin-hello");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("plugin.json"),
                "{ \"name\": \"hello\", \"id\": \"hello-v1\", \"version\": \"1.0.0\" }");

            dispatcher.dispatch("plugin", "install " + pluginDir, ctx);
            assertThat(outWriter.toString()).contains("Installed plugin 'hello'");

            clearOutput();
            dispatcher.dispatch("plugin", "list", ctx);
            assertThat(outWriter.toString()).contains("hello");
            assertThat(outWriter.toString()).contains("hello-v1");
            assertThat(outWriter.toString()).contains("yes");
        }

        @Test
        void pluginDisableEnable() throws Exception {
            var pluginDir = tempDir.resolve("plugin-toggle");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("plugin.json"),
                "{ \"name\": \"toggle\", \"id\": \"toggle-v1\", \"version\": \"1.0.0\" }");

            dispatcher.dispatch("plugin", "install " + pluginDir, ctx);

            clearOutput();
            dispatcher.dispatch("plugin", "disable toggle", ctx);
            assertThat(outWriter.toString()).contains("Disabled plugin 'toggle'");

            clearOutput();
            dispatcher.dispatch("plugin", "list", ctx);
            assertThat(outWriter.toString()).contains("no");

            clearOutput();
            dispatcher.dispatch("plugin", "enable toggle", ctx);
            assertThat(outWriter.toString()).contains("Enabled plugin 'toggle'");

            clearOutput();
            dispatcher.dispatch("plugin", "list", ctx);
            assertThat(outWriter.toString()).contains("yes");
        }

        @Test
        void pluginRemove() throws Exception {
            var pluginDir = tempDir.resolve("plugin-rm");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("plugin.json"),
                "{ \"name\": \"removeme\", \"id\": \"rm-v1\", \"version\": \"1.0.0\" }");

            dispatcher.dispatch("plugin", "install " + pluginDir, ctx);
            assertThat(outWriter.toString()).contains("Installed plugin 'removeme'");

            clearOutput();
            dispatcher.dispatch("plugin", "remove removeme", ctx);
            assertThat(outWriter.toString()).contains("Removed plugin 'removeme'");

            clearOutput();
            dispatcher.dispatch("plugin", "list", ctx);
            assertThat(outWriter.toString()).contains("(no plugins installed)");
        }

        @Test
        void pluginRemoveNotFound() {
            dispatcher.dispatch("plugin", "remove ghost", ctx);
            assertThat(outWriter.toString()).contains("not found");
        }

        @Test
        void pluginEnableNotFound() {
            dispatcher.dispatch("plugin", "enable ghost", ctx);
            assertThat(outWriter.toString()).contains("not found");
        }

        @Test
        void pluginDisableNotFound() {
            dispatcher.dispatch("plugin", "disable ghost", ctx);
            assertThat(outWriter.toString()).contains("not found");
        }

        @Test
        void pluginInstallBadPath() {
            dispatcher.dispatch("plugin", "install /no/such/path", ctx);
            assertThat(outWriter.toString()).contains("path does not exist");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  REGISTERED BUT NOT IMPLEMENTED
    // ══════════════════════════════════════════════════════════════

    @Nested
    class RegisteredNotImplemented {

        @Test
        void auth_notYetImplemented() {
            dispatcher.dispatch("auth", "", ctx);
            assertThat(outWriter.toString()).contains("not yet implemented");
        }

        @Test
        void config_notYetImplemented() {
            dispatcher.dispatch("config", "", ctx);
            assertThat(outWriter.toString()).contains("not yet implemented");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UNKNOWN
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Unknown {

        @Test
        void printsUnknownError() {
            dispatcher.dispatch("bogus", "", ctx);
            assertThat(outWriter.toString()).contains("Unknown command: /bogus");
            assertThat(outWriter.toString()).contains("/help");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CUSTOM HANDLER
    // ══════════════════════════════════════════════════════════════

    @Nested
    class CustomHandler {

        @Test
        void customHandler_dispatched() {
            dispatcher.register("custom", (args, c) -> c.out().println("custom: " + args));
            dispatcher.dispatch("custom", "test-args", ctx);
            assertThat(outWriter.toString()).contains("custom: test-args");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  STUB CONTEXT
    // ══════════════════════════════════════════════════════════════

    private static class StubContext implements ReplContext {
        final StringWriter out;
        final MockWebServer server;
        String sessionId;
        boolean exitRequested;
        final List<String> historyLines = new ArrayList<>();

        StubContext(StringWriter out, MockWebServer server) {
            this.out = out;
            this.server = server;
        }

        @Override public PrintWriter out() { return new PrintWriter(out, true); }
        @Override public AgentApiClient client() {
            return new com.clawcode.agent.cli.HttpAgentApiClient(
                new com.clawcode.agent.cli.CliProperties(
                    server.url("").toString(), "X-API-Key", null, 5000, 2000));
        }
        @Override public String currentSessionId() { return sessionId; }
        @Override public void setSessionId(String id) { this.sessionId = id; }
        @Override public List<String> history() { return List.copyOf(historyLines); }
        @Override public void requestExit() { this.exitRequested = true; }
    }
}
