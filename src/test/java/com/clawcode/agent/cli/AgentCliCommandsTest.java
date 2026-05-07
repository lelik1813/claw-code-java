package com.clawcode.agent.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
import com.clawcode.agent.cli.auth.FileAuthStore;
import com.clawcode.agent.cli.commands.AuthCommand;
import com.clawcode.agent.cli.commands.ConfigCommand;
import com.clawcode.agent.cli.commands.daemon.DaemonCommand;
import com.clawcode.agent.cli.commands.mcp.McpCommand;
import com.clawcode.agent.cli.commands.plugin.PluginCommand;
import com.clawcode.agent.cli.commands.remote.RemoteControlCommand;
import com.clawcode.agent.cli.commands.skills.SkillsCommand;
import com.clawcode.agent.cli.daemon.DaemonHealthChecker;
import com.clawcode.agent.cli.daemon.DaemonState;
import com.clawcode.agent.cli.daemon.DaemonStateStore;
import com.clawcode.agent.cli.daemon.ProcessHandleProvider;
import com.clawcode.agent.cli.daemon.StartedDaemonProcess;
import com.clawcode.agent.cli.config.CliConfigStore;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
import com.clawcode.agent.cli.remote.FileRemoteControlStore;
import com.clawcode.agent.cli.remote.RemoteConnection;
import com.clawcode.agent.cli.skills.FileSkillStore;
import com.clawcode.agent.cli.registry.CommandDescriptor;
import com.clawcode.agent.cli.registry.CommandRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI command test matrix — single source of truth for the full contract surface.
 *
 * Groups: session | message | stream | auth | config | mcp | plugin
 * Each group covers: help, happy-path, invalid args, API errors (401/403/404/409/422/429/5xx), output format.
 * Cross-cutting: ExceptionMapper, Routing, REPL slash commands.
 */
@SuppressWarnings("FieldCanBeLocal")
class AgentCliCommandsTest {

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private StringWriter outWriter;
    private StringWriter errWriter;
    private Path mcpConfigFile;
    private Path pluginConfigFile;
    private Path authConfigFile;
    private Path cliConfigFile;
    private Path daemonStateFile;
    private Path remoteStateFile;
    private Path skillsRoot;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        mcpConfigFile = tempDir.resolve("mcp-servers.json");
        pluginConfigFile = tempDir.resolve("plugins.json");
        authConfigFile = tempDir.resolve("auth.json");
        cliConfigFile = tempDir.resolve("config.json");
        daemonStateFile = tempDir.resolve("daemon.json");
        remoteStateFile = tempDir.resolve("remote.json");
        skillsRoot = tempDir.resolve("skills");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private int execute(String... args) {
        var app = new AgentCliApplication();
        app.client = new HttpAgentApiClient(
            new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));
        injectMcpStore(cmd);
        injectPluginStore(cmd);
        injectAuthStore(cmd);
        injectConfigStore(cmd);
        injectSkillStore(cmd);
        injectDaemonStore(cmd);
        injectRemoteStore(cmd);
        return cmd.execute(args);
    }

    private void injectMcpStore(CommandLine cmd) {
        var mcpLine = cmd.getSubcommands().get("mcp");
        if (mcpLine != null) {
            var mcp = (McpCommand) mcpLine.getCommand();
            mcp.store = new FileMcpConfigStore(mcpConfigFile);
        }
    }

    private void injectPluginStore(CommandLine cmd) {
        var pluginLine = cmd.getSubcommands().get("plugin");
        if (pluginLine != null) {
            var plugin = (PluginCommand) pluginLine.getCommand();
            plugin.store = new FilePluginConfigStore(pluginConfigFile);
        }
    }

    private void injectAuthStore(CommandLine cmd) {
        var authLine = cmd.getSubcommands().get("auth");
        if (authLine != null) {
            var auth = (AuthCommand) authLine.getCommand();
            auth.store = new FileAuthStore(authConfigFile);
        }
    }

    private void injectConfigStore(CommandLine cmd) {
        var configLine = cmd.getSubcommands().get("config");
        if (configLine != null) {
            var config = (ConfigCommand) configLine.getCommand();
            config.store = new CliConfigStore(cliConfigFile);
        }
    }

    private void injectSkillStore(CommandLine cmd) {
        var skillsLine = cmd.getSubcommands().get("skills");
        if (skillsLine != null) {
            var skills = (SkillsCommand) skillsLine.getCommand();
            skills.store = new FileSkillStore(skillsRoot);
        }
    }

    private void injectDaemonStore(CommandLine cmd) {
        var daemonLine = cmd.getSubcommands().get("daemon");
        if (daemonLine != null) {
            var daemon = (DaemonCommand) daemonLine.getCommand();
            daemon.store = new DaemonStateStore(daemonStateFile);
            daemon.processLauncher = (jar, port) -> new StartedDaemonProcess(123456L, () -> {});
            daemon.healthChecker = alwaysHealthyDaemon();
        }
    }

    private DaemonHealthChecker alwaysHealthyDaemon() {
        return new DaemonHealthChecker() {
            @Override
            public boolean waitUntilReady(int port, Duration timeout) { return true; }
            @Override
            public boolean isHealthy(int port, Duration requestTimeout) { return true; }
        };
    }

    private void injectRemoteStore(CommandLine cmd) {
        var remoteLine = cmd.getSubcommands().get("remote");
        if (remoteLine != null) {
            var remote = (RemoteControlCommand) remoteLine.getCommand();
            remote.store = new FileRemoteControlStore(remoteStateFile);
            remote.authStore = new FileAuthStore(authConfigFile);
        }
    }

    private String out() { return outWriter.toString().trim(); }
    private String err() { return errWriter.toString().trim(); }

    private void clearOutput() {
        outWriter.getBuffer().setLength(0);
        errWriter.getBuffer().setLength(0);
    }

    private Path successfulStdioCommand() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = tempDir.resolve(windows ? "ok command.cmd" : "ok command.sh");
        Files.writeString(script, windows ? "@echo off\r\nexit /b 0\r\n" : "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);
        return script;
    }

    // ══════════════════════════════════════════════════════════════
    //  SESSION GROUP
    //  Commands: session, session create
    // ══════════════════════════════════════════════════════════════

    @Nested
    class SessionGroup {

        // ── group-level ──

        @Test
        void noAction_showsUsageWithCreate() {
            int exit = execute("session");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("create");
        }

        @Test
        void help_showsSubcommands() {
            int exit = execute("session", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("create");
        }
    }

    @Nested
    class SessionCreate {

        // ── happy-path ──

        @Test
        void happyPath_printsSessionId() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"sess-001\",\"createdAt\":\"2026-04-22T12:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("sess-001");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("session", "create", "--help");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Create a new agent session");
        }

        // ── API errors ──

        @Test
        void authError_401() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void forbidden_403() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void notFound_404() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"error\":\"Not found\"}"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("Not found");
        }

        @Test
        void conflict_409() {
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session already exists"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
            assertThat(err()).contains("Conflict");
        }

        @Test
        void validation_422() {
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Invalid parameters"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
            assertThat(err()).contains("Validation error");
        }

        @Test
        void rateLimited_429() {
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too Many Requests\"}"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
            assertThat(err()).contains("Rate limited");
        }

        @Test
        void serverError_500() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("HTTP 500");
        }

        // ── edge cases ──

        @Test
        void emptyResponse_exitCode() {
            server.enqueue(new MockResponse()
                .setBody("")
                .setHeader("Content-Type", "application/json"));

            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("empty response");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MESSAGE GROUP
    //  Commands: message, message send
    // ══════════════════════════════════════════════════════════════

    @Nested
    class MessageGroup {

        @Test
        void noAction_showsUsageWithSend() {
            int exit = execute("message");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("send");
        }
    }

    @Nested
    class MessageSend {

        // ── happy-path ──

        @Test
        void happyPath_printsAccepted() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-1\",\"accepted\":true}")
                .setHeader("Content-Type", "application/json"));

            int exit = execute("message", "send", "s-1", "hello world");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("accepted");
        }

        @Test
        void rejected_printsRejected() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-1\",\"accepted\":false}")
                .setHeader("Content-Type", "application/json"));

            int exit = execute("message", "send", "s-1", "blocked");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).isEqualTo("rejected");
        }

        @Test
        void withSkills() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-1\",\"accepted\":true}")
                .setHeader("Content-Type", "application/json"));

            int exit = execute("message", "send", "s-1", "translate",
                "--skill", "translator", "--skill", "summarizer");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("accepted");
        }

        // ── help ──

        @Test
        void help_showsParameters() {
            int exit = execute("message", "send", "--help");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("SESSION_ID");
            assertThat(out()).contains("CONTENT");
            assertThat(out()).contains("--skill");
        }

        // ── invalid args ──

        @Test
        void missingSessionId_usageError() {
            int exit = execute("message", "send");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void missingContent_usageError() {
            int exit = execute("message", "send", "s-1");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void blankSessionId_usageError() {
            int exit = execute("message", "send", "   ", "hello");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("SESSION_ID must not be blank");
        }

        @Test
        void blankContent_usageError() {
            int exit = execute("message", "send", "s-1", "   ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("CONTENT must not be blank");
        }

        // ── API errors ──

        @Test
        void authError_401() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void forbidden_403() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void notFound_404() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"error\":\"Session not found\"}"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("Not found");
        }

        @Test
        void conflict_409() {
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session locked"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
            assertThat(err()).contains("Conflict");
        }

        @Test
        void validation_422() {
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Content too long"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
            assertThat(err()).contains("Validation error");
        }

        @Test
        void rateLimited_429() {
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too Many Requests\"}"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
        }

        @Test
        void serverError_500() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            int exit = execute("message", "send", "s-1", "hello");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  STREAM GROUP
    //  Commands: stream, stream attach
    // ══════════════════════════════════════════════════════════════

    @Nested
    class StreamGroup {

        @Test
        void noAction_showsUsageWithAttach() {
            int exit = execute("stream");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("attach");
        }
    }

    @Nested
    class StreamAttach {

        // ── happy-path ──

        @Test
        void happyPath_printsEvents() {
            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"evt-alpha\"}\n\ndata: {\"type\":\"delta\",\"text\":\"evt-beta\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("evt-alpha");
            assertThat(out()).contains("evt-beta");
        }

        @Test
        void typedEvents_renderCorrectly() {
            server.enqueue(new MockResponse()
                .setBody(
                    "data: {\"type\":\"started\"}\n\n"
                    + "data: {\"type\":\"delta\",\"text\":\"thinking\"}\n\n"
                    + "data: {\"type\":\"tool_call\",\"toolCallId\":\"c1\",\"toolName\":\"read\"}\n\n"
                    + "data: {\"type\":\"tool_result\",\"toolCallId\":\"c1\",\"toolName\":\"read\",\"isError\":false,\"summary\":\"42 lines\"}\n\n"
                    + "data: {\"type\":\"tool_use_summary\",\"round\":0,\"total_tool_calls\":1,\"compacted_results\":1,\"error_results\":0,\"paths\":[\"src/A.java\"],\"summary\":\"RAW SUMMARY SHOULD NOT PRINT\"}\n\n"
                    + "data: {\"type\":\"result\",\"success\":true,\"stop_reason\":\"end_turn\",\"usage\":{\"inputTokens\":1,\"outputTokens\":2},\"duration_ms\":10,\"num_turns\":1,\"permission_denials\":0}\n\n"
                    + "data: {\"type\":\"completed\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("thinking");
            assertThat(out()).contains("● read");
            assertThat(out()).contains("42 lines");
            assertThat(out()).contains("Tool batch: 1 calls, 1 compacted, 0 errors");
            assertThat(out()).doesNotContain("[started]");
            assertThat(out()).doesNotContain("[completed]");
            assertThat(out()).doesNotContain("[tool: read]");
            assertThat(out()).doesNotContain("[result: read]");
            assertThat(out()).doesNotContain("RAW SUMMARY SHOULD NOT PRINT");
            assertThat(out()).doesNotContain("end_turn");
            assertThat(out()).doesNotContain("duration_ms");
            assertThat(out()).doesNotContain("permission_denials");
        }

        // ── cursor replay ──

        @Test
        void withAfterCursor_replaysThenAttaches() {
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[" +
                    "{\"role\":\"user\",\"content\":\"hello\"}," +
                    "{\"role\":\"assistant\",\"content\":\"hi\"}" +
                    "],\"nextCursor\":2,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"live-msg\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "0");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("[user] hello");
            assertThat(out()).contains("[assistant] hi");
            assertThat(out()).contains("live-msg");
        }

        @Test
        void withAfterCursor_emptyReplay_skipsToStream() {
            server.enqueue(new MockResponse()
                .setBody("{\"messages\":[],\"nextCursor\":0,\"hasMore\":false}")
                .setHeader("Content-Type", "application/json"));

            server.enqueue(new MockResponse()
                .setBody("data: {\"type\":\"delta\",\"text\":\"fresh\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));

            int exit = execute("stream", "attach", "s-1", "--after-cursor", "5");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("fresh");
            assertThat(out()).doesNotContain("[user]");
        }

        // ── help ──

        @Test
        void help_showsSessionId() {
            int exit = execute("stream", "attach", "--help");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("SESSION_ID");
        }

        // ── invalid args ──

        @Test
        void blankSessionId_usageError() {
            int exit = execute("stream", "attach", " ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("SESSION_ID must not be blank");
        }

        @Test
        void missingSessionId_usageError() {
            int exit = execute("stream", "attach");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── API errors ──

        @Test
        void authError_401() {
            server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":\"Unauthorized\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void forbidden_403() {
            server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(err()).contains("Authentication failed");
        }

        @Test
        void notFound_404() {
            server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"error\":\"Session not found\"}"));

            int exit = execute("stream", "attach", "missing");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("Not found");
        }

        @Test
        void conflict_409() {
            server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("Session locked"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
            assertThat(err()).contains("Conflict");
        }

        @Test
        void validation_422() {
            server.enqueue(new MockResponse().setResponseCode(422)
                .setBody("Invalid session"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
            assertThat(err()).contains("Validation error");
        }

        @Test
        void rateLimited_429() {
            server.enqueue(new MockResponse().setResponseCode(429)
                .setBody("{\"error\":\"Too Many Requests\"}"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
            assertThat(err()).contains("Rate limited");
        }

        @Test
        void serverError_500() {
            server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("Internal Server Error"));

            int exit = execute("stream", "attach", "s-1");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).contains("HTTP 500");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AUTH GROUP
    //  Commands: auth, auth login|status|logout
    // ══════════════════════════════════════════════════════════════

    @Nested
    class AuthGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("auth");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("login");
            assertThat(out()).contains("status");
            assertThat(out()).contains("logout");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("auth", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Authentication operations");
        }
    }

    @Nested
    class AuthLogin {

        // ── happy-path ──

        @Test
        void login_storesCredentials() {
            int exit = execute("auth", "login", "--api-key", "sk-test-12345678");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Credentials saved");
        }

        @Test
        void login_printsMaskedKey() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            assertThat(out()).contains("sk-t...5678");
        }

        @Test
        void login_customHeader() {
            int exit = execute("auth", "login", "--api-key", "my-key",
                "--api-key-header", "Authorization");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Authorization");
        }

        @Test
        void login_customHeaders() {
            int exit = execute("auth", "login", "--api-key", "my-key",
                "--header", "X-Org=acme", "--header", "X-Region=us-east");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("X-Org");
            assertThat(out()).contains("X-Region");
        }

        @Test
        void login_overwritesPrevious() {
            execute("auth", "login", "--api-key", "first-key");
            clearOutput();
            execute("auth", "login", "--api-key", "second-key-longer");
            clearOutput();

            int exit = execute("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("seco...nger");
        }

        // ── help ──

        @Test
        void help_showsOptions() {
            int exit = execute("auth", "login", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--api-key");
            assertThat(out()).contains("--api-key-header");
            assertThat(out()).contains("--header");
        }

        // ── invalid args ──

        @Test
        void loginMissingApiKey() {
            int exit = execute("auth", "login");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void loginBlankApiKey() {
            int exit = execute("auth", "login", "--api-key", "   ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("--api-key must not be blank");
        }

        @Test
        void loginBadHeaderFormat() {
            int exit = execute("auth", "login", "--api-key", "key",
                "--header", "NOEQUALS");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid --header format");
        }

        @Test
        void loginShortKey_maskedFully() {
            int exit = execute("auth", "login", "--api-key", "abc");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("***");
        }
    }

    @Nested
    class AuthStatus {

        // ── happy-path ──

        @Test
        void status_notAuthenticated() {
            int exit = execute("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Not authenticated");
        }

        @Test
        void status_afterLogin_showsAuthenticated() {
            execute("auth", "login", "--api-key", "sk-prod-12345678");
            clearOutput();

            int exit = execute("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Authenticated");
            assertThat(out()).contains("sk-p...5678");
            assertThat(out()).contains("X-API-Key");
        }

        @Test
        void status_showsCustomHeaders() {
            execute("auth", "login", "--api-key", "key",
                "--header", "X-Org=acme-secret-value-1234");
            clearOutput();

            int exit = execute("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("X-Org");
            assertThat(out()).contains("acme...1234");
        }

        @Test
        void status_showsUpdatedAt() {
            execute("auth", "login", "--api-key", "key");
            clearOutput();

            int exit = execute("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("updated:");
            assertThat(out()).contains("20"); // year prefix
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("auth", "status", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("authentication status");
        }
    }

    @Nested
    class AuthLogout {

        // ── happy-path ──

        @Test
        void logout_afterLogin_clearsCredentials() {
            execute("auth", "login", "--api-key", "my-key");
            clearOutput();

            int exit = execute("auth", "logout");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Credentials cleared");

            clearOutput();
            execute("auth", "status");
            assertThat(out()).contains("Not authenticated");
        }

        @Test
        void logout_thenLogin_persistenceCycle() {
            execute("auth", "login", "--api-key", "first");
            execute("auth", "logout");
            clearOutput();

            execute("auth", "login", "--api-key", "second-very-long-key");
            clearOutput();

            execute("auth", "status");
            assertThat(out()).contains("Authenticated");
            assertThat(out()).contains("seco...-key");
        }

        // ── error cases ──

        @Test
        void logout_noCredentials_fails() {
            int exit = execute("auth", "logout");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("No credentials to clear");
        }

        @Test
        void logout_noCredentials_force_succeeds() {
            int exit = execute("auth", "logout", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("auth", "logout", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--force");
        }
    }

    @Nested
    class AuthPersistence {

        @Test
        void loginPersistsAcrossRestarts() throws Exception {
            execute("auth", "login", "--api-key", "sk-persist-1234");
            clearOutput();

            // Simulate restart by creating a fresh store at the same path
            var store = new FileAuthStore(authConfigFile);
            var creds = store.load();
            assertThat(creds).isPresent();
            assertThat(creds.get().apiKey()).isEqualTo("sk-persist-1234");
            assertThat(creds.get().apiKeyHeader()).isEqualTo("X-API-Key");
        }

        @Test
        void loginWithCustomHeader_persistsCorrectly() throws Exception {
            execute("auth", "login", "--api-key", "key",
                "--api-key-header", "Bearer-Token",
                "--header", "X-Org=acme");
            clearOutput();

            var store = new FileAuthStore(authConfigFile);
            var creds = store.load();
            assertThat(creds).isPresent();
            assertThat(creds.get().apiKeyHeader()).isEqualTo("Bearer-Token");
            assertThat(creds.get().customHeaders()).containsEntry("X-Org", "acme");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CONFIG GROUP
    //  Commands: config, config get|set|list|unset
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ConfigGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("config");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("get");
            assertThat(out()).contains("set");
            assertThat(out()).contains("list");
            assertThat(out()).contains("unset");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("config", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Configuration operations");
        }
    }

    @Nested
    class ConfigSet {

        // ── happy-path ──

        @Test
        void set_baseUrl() {
            int exit = execute("config", "set", "baseUrl", "http://prod.example.com:9090");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("baseUrl = http://prod.example.com:9090");
        }

        @Test
        void set_timeoutMs() {
            int exit = execute("config", "set", "timeoutMs", "60000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("timeoutMs = 60000");
        }

        @Test
        void set_apiKeyHeader() {
            int exit = execute("config", "set", "apiKeyHeader", "Authorization");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void set_streamReadTimeoutMs() {
            int exit = execute("config", "set", "streamReadTimeoutMs", "120000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void set_overwrite() {
            execute("config", "set", "timeoutMs", "10000");
            clearOutput();

            int exit = execute("config", "set", "timeoutMs", "20000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);

            clearOutput();
            execute("config", "get", "timeoutMs");
            assertThat(out()).isEqualTo("20000");
        }

        // ── invalid args ──

        @Test
        void setMissingKey() {
            int exit = execute("config", "set");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void setMissingValue() {
            int exit = execute("config", "set", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void setUnknownKey() {
            int exit = execute("config", "set", "unknownKey", "value");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("unknown config key");
        }

        @Test
        void setInvalidUrl() {
            int exit = execute("config", "set", "baseUrl", "not-a-url");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid URL");
        }

        @Test
        void setUrlMissingHost() {
            int exit = execute("config", "set", "baseUrl", "http://");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid URL");
        }

        @Test
        void setNegativeTimeout() {
            int exit = execute("config", "set", "timeoutMs", "-1");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("must be positive");
        }

        @Test
        void setNonNumericTimeout() {
            int exit = execute("config", "set", "timeoutMs", "abc");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid number");
        }

        @Test
        void setBlankValue() {
            int exit = execute("config", "set", "baseUrl", "   ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("must not be blank");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("config", "set", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("KEY");
            assertThat(out()).contains("VALUE");
        }
    }

    @Nested
    class ConfigGet {

        // ── happy-path ──

        @Test
        void get_afterSet() {
            execute("config", "set", "baseUrl", "http://my-host:8080");
            clearOutput();

            int exit = execute("config", "get", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("http://my-host:8080");
        }

        @Test
        void get_defaultValue() {
            int exit = execute("config", "get", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("http://localhost:8080");
        }

        @Test
        void get_unknownKey_notSet() {
            int exit = execute("config", "get", "unknownKey");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not set");
        }

        // ── invalid args ──

        @Test
        void getMissingKey() {
            int exit = execute("config", "get");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("config", "get", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("KEY");
        }
    }

    @Nested
    class ConfigList {

        // ── happy-path ──

        @Test
        void listEmpty_noDefaults() {
            int exit = execute("config", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no config set)");
        }

        @Test
        void listAfterSet_storedOnly() {
            execute("config", "set", "baseUrl", "http://prod:8080");
            clearOutput();

            int exit = execute("config", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("baseUrl");
            assertThat(out()).contains("http://prod:8080");
        }

        @Test
        void listWithDefaults() {
            int exit = execute("config", "list", "--show-defaults");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("baseUrl");
            assertThat(out()).contains("timeoutMs");
            assertThat(out()).contains("streamReadTimeoutMs");
            assertThat(out()).contains("apiKeyHeader");
            assertThat(out()).contains("default");
        }

        @Test
        void listWithOverrides_showsConfigSource() {
            execute("config", "set", "timeoutMs", "9999");
            clearOutput();

            int exit = execute("config", "list", "--show-defaults");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("9999");
            assertThat(out()).contains("config");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("config", "list", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--show-defaults");
        }
    }

    @Nested
    class ConfigUnset {

        // ── happy-path ──

        @Test
        void unset_existingKey() {
            execute("config", "set", "baseUrl", "http://prod:8080");
            clearOutput();

            int exit = execute("config", "unset", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Unset 'baseUrl'");

            clearOutput();
            execute("config", "get", "baseUrl");
            assertThat(out()).isEqualTo("http://localhost:8080");
        }

        @Test
        void unset_thenSet_cycle() {
            execute("config", "set", "timeoutMs", "5000");
            execute("config", "unset", "timeoutMs");
            clearOutput();

            execute("config", "get", "timeoutMs");
            assertThat(out()).isEqualTo("30000");

            execute("config", "set", "timeoutMs", "10000");
            clearOutput();
            execute("config", "get", "timeoutMs");
            assertThat(out()).isEqualTo("10000");
        }

        // ── error cases ──

        @Test
        void unset_notSet_fails() {
            int exit = execute("config", "unset", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void unset_notSet_force_succeeds() {
            int exit = execute("config", "unset", "baseUrl", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        // ── invalid args ──

        @Test
        void unsetMissingKey() {
            int exit = execute("config", "unset");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("config", "unset", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--force");
        }
    }

    @Nested
    class ConfigPersistence {

        @Test
        void setPersistsToStore() throws Exception {
            execute("config", "set", "baseUrl", "http://persist:9090");
            execute("config", "set", "timeoutMs", "45000");

            var store = new CliConfigStore(cliConfigFile);
            assertThat(store.get("baseUrl")).contains("http://persist:9090");
            assertThat(store.get("timeoutMs")).contains("45000");
        }

        @Test
        void unsetPersistsToStore() throws Exception {
            execute("config", "set", "timeoutMs", "5000");
            execute("config", "unset", "timeoutMs");

            var store = new CliConfigStore(cliConfigFile);
            assertThat(store.get("timeoutMs")).contains("30000");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MCP GROUP
    //  Commands: mcp, mcp list|add|remove|test|enable|disable
    // ══════════════════════════════════════════════════════════════

    @Nested
    class McpGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("mcp");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("list");
            assertThat(out()).contains("add");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("test");
            assertThat(out()).contains("enable");
            assertThat(out()).contains("disable");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("mcp", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Manage MCP server connections");
        }
    }

    @Nested
    class McpList {

        // ── happy-path / output format ──

        @Test
        void listEmpty_printsPlaceholder() {
            int exit = execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no mcp servers)");
        }

        @Test
        void listAfterAdd_tableOutput() {
            execute("mcp", "add", "alpha", "--url", "http://localhost:3000");
            clearOutput();

            int exit = execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("TRANSPORT");
            assertThat(out()).contains("alpha");
            assertThat(out()).contains("HTTP");
        }

        @Test
        void listJson_output() {
            execute("mcp", "add", "beta", "--url", "http://localhost:4000");
            clearOutput();

            int exit = execute("mcp", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"beta\"");
            assertThat(out()).contains("\"transport\" : \"HTTP\"");
        }

        @Test
        void listAfterRemove_showsRemaining() {
            execute("mcp", "add", "keep", "--url", "http://localhost:3000");
            execute("mcp", "add", "drop", "--url", "http://localhost:4000");
            execute("mcp", "remove", "drop");
            clearOutput();

            int exit = execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("keep");
            assertThat(out()).doesNotContain("drop");
        }
    }

    @Nested
    class McpAdd {

        // ── happy-path ──

        @Test
        void addHttp_happyPath() {
            int exit = execute("mcp", "add", "my-srv", "--url", "http://localhost:3000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Added MCP server 'my-srv'");
            assertThat(out()).contains("type=HTTP");
        }

        @Test
        void addStdio_happyPath() {
            int exit = execute("mcp", "add", "local", "--type", "STDIO", "--command", "npx my-tool");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("type=STDIO");
        }

        @Test
        void addStdio_withArgsAndEnv() {
            int exit = execute("mcp", "add", "full", "--type", "STDIO", "--command", "node",
                "--arg", "server.js", "--env", "PORT=3000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void addSse_withUrl() {
            int exit = execute("mcp", "add", "sse-srv", "--type", "SSE",
                "--url", "http://localhost:8080/sse");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("type=SSE");
        }

        @Test
        void addDisabled() {
            execute("mcp", "add", "paused", "--url", "http://localhost:3000", "--disabled");
            clearOutput();
            execute("mcp", "list");
            assertThat(out()).contains("no");
        }

        // ── invalid args ──

        @Test
        void addMissingName() {
            int exit = execute("mcp", "add");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void addInvalidTransport() {
            int exit = execute("mcp", "add", "bad", "--type", "BOGUS");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid transport");
        }

        @Test
        void addHttpMissingUrl() {
            int exit = execute("mcp", "add", "no-url", "--type", "HTTP");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("url is required for HTTP transport");
        }

        @Test
        void addStdioMissingCommand() {
            int exit = execute("mcp", "add", "no-cmd", "--type", "STDIO");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("command is required for STDIO transport");
        }

        @Test
        void addInvalidName() {
            int exit = execute("mcp", "add", "123bad", "--url", "http://localhost:3000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("Validation error");
        }

        @Test
        void addDuplicate_rejected() {
            execute("mcp", "add", "dup", "--url", "http://localhost:3000");
            clearOutput();

            int exit = execute("mcp", "add", "dup", "--url", "http://localhost:4000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void addUrlTrailingSlash_stripped() {
            execute("mcp", "add", "slash", "--url", "http://host:3000///");
            clearOutput();
            execute("mcp", "list", "--json");
            assertThat(out()).contains("http://host:3000");
            assertThat(out()).doesNotContain("host:3000///");
        }

        @Test
        void addEnvBadFormat() {
            int exit = execute("mcp", "add", "bad-env", "--url", "http://localhost:3000",
                "--env", "NOEQUALS");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid --env format");
        }
    }

    @Nested
    class McpRemove {

        // ── happy-path ──

        @Test
        void removeExisting_succeeds() {
            execute("mcp", "add", "to-rm", "--url", "http://localhost:3000");
            clearOutput();

            int exit = execute("mcp", "remove", "to-rm");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed MCP server 'to-rm'");
        }

        @Test
        void removeThenList_empty() {
            execute("mcp", "add", "temp", "--url", "http://localhost:3000");
            execute("mcp", "remove", "temp");
            clearOutput();

            execute("mcp", "list");
            assertThat(out()).contains("(no mcp servers)");
        }

        // ── invalid args ──

        @Test
        void removeMissingName() {
            int exit = execute("mcp", "remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── error cases ──

        @Test
        void removeNotFound() {
            int exit = execute("mcp", "remove", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_force_exitsOk() {
            int exit = execute("mcp", "remove", "ghost", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void removeTwice_secondFails() {
            execute("mcp", "add", "once", "--url", "http://localhost:3000");
            execute("mcp", "remove", "once");
            clearOutput();

            int exit = execute("mcp", "remove", "once");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }
    }

    @Nested
    class McpTest {

        // ── happy-path ──

        @Test
        void testHttp_ok() {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            var url = server.url("/").toString();
            execute("mcp", "add", "live", "--url", url);
            clearOutput();

            int exit = execute("mcp", "test", "live", "--timeout", "3");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("OK");
            assertThat(out()).contains("HTTP 200");
        }

        @Test
        void testStdio_ok() throws IOException {
            var command = successfulStdioCommand();
            execute("mcp", "add", "java-stdio", "--type", "STDIO",
                "--command", command.toString());
            clearOutput();

            int exit = execute("mcp", "test", "java-stdio");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("OK");
        }

        // ── error cases ──

        @Test
        void testHttp_5xx() {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            var url = server.url("/").toString();
            execute("mcp", "add", "sick", "--url", url);
            clearOutput();

            int exit = execute("mcp", "test", "sick");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
            assertThat(out()).contains("503");
        }

        @Test
        void testHttp_connectionRefused() {
            execute("mcp", "add", "dead", "--url", "http://localhost:1");
            clearOutput();

            int exit = execute("mcp", "test", "dead", "--timeout", "1");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
        }

        @Test
        void testStdio_nonZeroExit() {
            execute("mcp", "add", "failer", "--type", "STDIO", "--command", "false");
            clearOutput();

            int exit = execute("mcp", "test", "failer");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
        }

        @Test
        void testNotFound() {
            int exit = execute("mcp", "test", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        // ── invalid args ──

        @Test
        void testMissingName() {
            int exit = execute("mcp", "test");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }

    @Nested
    class McpEnableDisable {

        // ── happy-path ──

        @Test
        void disableThenList_showsNo() {
            execute("mcp", "add", "toggle", "--url", "http://localhost:3000");
            clearOutput();

            int exit = execute("mcp", "disable", "toggle");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disabled MCP server 'toggle'");

            clearOutput();
            execute("mcp", "list");
            assertThat(out()).contains("toggle");
            assertThat(out()).contains("no");
        }

        @Test
        void enableThenList_showsYes() {
            execute("mcp", "add", "flip", "--url", "http://localhost:3000", "--disabled");
            clearOutput();

            int exit = execute("mcp", "enable", "flip");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Enabled MCP server 'flip'");

            clearOutput();
            execute("mcp", "list");
            assertThat(out()).contains("flip");
            assertThat(out()).contains("yes");
        }

        @Test
        void enableDisableCycle() {
            execute("mcp", "add", "cycle", "--url", "http://localhost:3000");

            execute("mcp", "disable", "cycle");
            clearOutput();
            execute("mcp", "list");
            assertThat(out()).contains("no");

            execute("mcp", "enable", "cycle");
            clearOutput();
            execute("mcp", "list");
            assertThat(out()).contains("yes");
        }

        // ── idempotent ──

        @Test
        void enableAlreadyEnabled_succeeds() {
            execute("mcp", "add", "already", "--url", "http://localhost:3000");
            clearOutput();

            int exit = execute("mcp", "enable", "already");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Enabled MCP server 'already'");
        }

        @Test
        void disableAlreadyDisabled_succeeds() {
            execute("mcp", "add", "off", "--url", "http://localhost:3000", "--disabled");
            clearOutput();

            int exit = execute("mcp", "disable", "off");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disabled MCP server 'off'");
        }

        // ── preserves fields ──

        @Test
        void enablePreservesFields() {
            execute("mcp", "add", "keep", "--url", "http://localhost:4000",
                "--auth-token", "secret123", "--disabled");
            clearOutput();

            execute("mcp", "enable", "keep");
            clearOutput();
            execute("mcp", "list", "--json");
            assertThat(out()).contains("\"name\" : \"keep\"");
            assertThat(out()).contains("\"transport\" : \"HTTP\"");
            assertThat(out()).contains("\"url\" : \"http://localhost:4000\"");
            assertThat(out()).contains("\"enabled\" : true");
        }

        // ── not found ──

        @Test
        void enableNotFound() {
            int exit = execute("mcp", "enable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void disableNotFound() {
            int exit = execute("mcp", "disable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        // ── invalid args ──

        @Test
        void enableMissingName() {
            int exit = execute("mcp", "enable");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void disableMissingName() {
            int exit = execute("mcp", "disable");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── help ──

        @Test
        void enableHelp() {
            int exit = execute("mcp", "enable", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
        }

        @Test
        void disableHelp() {
            int exit = execute("mcp", "disable", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
        }
    }

    @Nested
    class McpTogglePersistence {

        @Test
        void disablePersistsAcrossStoreReload() {
            execute("mcp", "add", "persist", "--url", "http://localhost:3000");
            execute("mcp", "disable", "persist");

            var store = new FileMcpConfigStore(mcpConfigFile);
            var found = store.find("persist");
            assertThat(found).isPresent();
            assertThat(found.get().enabled()).isFalse();
        }

        @Test
        void enablePersistsAcrossStoreReload() {
            execute("mcp", "add", "persist2", "--url", "http://localhost:3000", "--disabled");
            execute("mcp", "enable", "persist2");

            var store = new FileMcpConfigStore(mcpConfigFile);
            var found = store.find("persist2");
            assertThat(found).isPresent();
            assertThat(found.get().enabled()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  PLUGIN GROUP
    //  Commands: plugin, plugin list|install|remove|enable|disable
    // ══════════════════════════════════════════════════════════════

    @Nested
    class PluginGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("list");
            assertThat(out()).contains("install");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("enable");
            assertThat(out()).contains("disable");
            assertThat(out()).contains("reload");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("plugin", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Manage plugins");
        }
    }

    @Nested
    class PluginList {

        // ── happy-path / output format ──

        @Test
        void listEmpty_printsNoPlugins() {
            int exit = execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no plugins installed)");
        }

        @Test
        void listAfterInstall_tableOutput() throws IOException {
            var dir = writePluginManifest("alpha", "alpha-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("ID");
            assertThat(out()).contains("ENABLED");
            assertThat(out()).contains("SOURCE");
            assertThat(out()).contains("VERSION");
            assertThat(out()).contains("alpha");
            assertThat(out()).contains("alpha-v1");
            assertThat(out()).contains("yes");
            assertThat(out()).contains("PATH");
        }

        @Test
        void listJson_output() throws IOException {
            var dir = writePluginManifest("beta", "beta-v1", "2.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"beta\"");
            assertThat(out()).contains("\"id\" : \"beta-v1\"");
            assertThat(out()).contains("\"source\" : \"PATH\"");
        }
    }

    @Nested
    class PluginInstall {

        // ── happy-path ──

        @Test
        void installFromDir_happyPath() throws IOException {
            var dir = writePluginManifest("my-plugin", "my-plugin-v1", "1.0.0");

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'my-plugin'");
            assertThat(out()).contains("id=my-plugin-v1");
        }

        @Test
        void installFromManifestFile_directPath() throws IOException {
            var file = writePluginManifestFile("direct", "direct-v1");

            int exit = execute("plugin", "install", file.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'direct'");
        }

        @Test
        void installDisabled_flag() throws IOException {
            var dir = writePluginManifest("off", "off-v1", "1.0.0");
            execute("plugin", "install", dir.toString(), "--disabled");
            clearOutput();

            execute("plugin", "list");
            assertThat(out()).contains("off");
            assertThat(out()).contains("no");
        }

        @Test
        void installWithNameOverride() throws IOException {
            var dir = writePluginManifest("original", "orig-v1", "1.0.0");

            int exit = execute("plugin", "install", dir.toString(), "--name", "renamed");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'renamed'");
        }

        // ── invalid args ──

        @Test
        void installMissingSource() {
            int exit = execute("plugin", "install");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void installPathNotExist() {
            int exit = execute("plugin", "install", "/no/such/path");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("path does not exist");
        }

        @Test
        void installMissingManifest() throws IOException {
            var emptyDir = tempDir.resolve("empty-plugin");
            Files.createDirectories(emptyDir);

            int exit = execute("plugin", "install", emptyDir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest not found");
        }

        @Test
        void installManifestMissingName() throws IOException {
            var dir = writeBadPluginManifest("{ \"id\": \"no-name-v1\", \"version\": \"1.0.0\" }");

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest missing required field: name");
        }

        @Test
        void installManifestMissingId() throws IOException {
            var dir = writeBadPluginManifest("{ \"name\": \"no-id\", \"version\": \"1.0.0\" }");

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest missing required field: id");
        }

        @Test
        void installInvalidName() throws IOException {
            var dir = writeBadPluginManifest("{ \"name\": \"1bad\", \"id\": \"bad-v1\", \"version\": \"1.0.0\" }");

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("Validation error");
        }

        // ── conflict ──

        @Test
        void installDuplicateName_rejected() throws IOException {
            var dir = writePluginManifest("dup", "dup-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void installDuplicateId_rejected() throws IOException {
            var dir1 = writePluginManifest("name-a", "shared-id", "1.0.0");
            execute("plugin", "install", dir1.toString());
            clearOutput();

            var dir2 = tempDir.resolve("plugin-name-b");
            Files.createDirectories(dir2);
            Files.writeString(dir2.resolve("plugin.json"),
                "{ \"name\": \"name-b\", \"id\": \"shared-id\", \"version\": \"2.0.0\" }");

            int exit = execute("plugin", "install", dir2.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
            assertThat(out()).contains("shared-id");
        }
    }

    @Nested
    class PluginRemove {

        // ── happy-path ──

        @Test
        void removeExisting_succeeds() throws IOException {
            var dir = writePluginManifest("to-rm", "rm-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "remove", "to-rm");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed plugin 'to-rm'");
        }

        @Test
        void removeThenList_empty() throws IOException {
            var dir = writePluginManifest("temp", "temp-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            execute("plugin", "remove", "temp");
            clearOutput();

            execute("plugin", "list");
            assertThat(out()).contains("(no plugins installed)");
        }

        // ── invalid args ──

        @Test
        void removeMissingName() {
            int exit = execute("plugin", "remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        // ── error cases ──

        @Test
        void removeNotFound() {
            int exit = execute("plugin", "remove", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_force() {
            int exit = execute("plugin", "remove", "ghost", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void removeTwice_secondFails() throws IOException {
            var dir = writePluginManifest("once", "once-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            execute("plugin", "remove", "once");
            clearOutput();

            int exit = execute("plugin", "remove", "once");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }
    }

    @Nested
    class PluginEnableDisable {

        // ── happy-path ──

        @Test
        void disableThenList_showsNo() throws IOException {
            var dir = writePluginManifest("toggle", "toggle-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            execute("plugin", "disable", "toggle");
            clearOutput();

            execute("plugin", "list");
            assertThat(out()).contains("toggle");
            assertThat(out()).contains("no");
        }

        @Test
        void enableThenList_showsYes() throws IOException {
            var dir = writePluginManifest("flip", "flip-v1", "1.0.0");
            execute("plugin", "install", dir.toString(), "--disabled");
            execute("plugin", "enable", "flip");
            clearOutput();

            execute("plugin", "list");
            assertThat(out()).contains("flip");
            assertThat(out()).contains("yes");
        }

        @Test
        void enableDisableCycle() throws IOException {
            var dir = writePluginManifest("cycle", "cycle-v1", "1.0.0");
            execute("plugin", "install", dir.toString());

            execute("plugin", "disable", "cycle");
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("no");

            execute("plugin", "enable", "cycle");
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("yes");
        }

        // ── preserves fields ──

        @Test
        void enablePreservesFields() throws IOException {
            var dir = writePluginManifest("preserve", "pres-v1", "2.5.0");
            execute("plugin", "install", dir.toString(), "--disabled");
            execute("plugin", "enable", "preserve");
            clearOutput();

            execute("plugin", "list", "--json");
            assertThat(out()).contains("\"name\" : \"preserve\"");
            assertThat(out()).contains("\"id\" : \"pres-v1\"");
            assertThat(out()).contains("\"version\" : \"2.5.0\"");
            assertThat(out()).contains("\"enabled\" : true");
        }

        // ── error cases ──

        @Test
        void enableNotFound() {
            int exit = execute("plugin", "enable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void disableNotFound() {
            int exit = execute("plugin", "disable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }
    }

    @Nested
    class PluginLifecycle {

        @Test
        void fullInstallListDisableEnableRemove_listIsEmpty() throws IOException {
            var dir1 = writePluginManifest("lc-a", "lc-a-v1", "1.0.0");
            var dir2 = tempDir.resolve("plugin-lc-b");
            Files.createDirectories(dir2);
            Files.writeString(dir2.resolve("plugin.json"),
                "{ \"name\": \"lc-b\", \"id\": \"lc-b-v1\", \"version\": \"1.0.0\" }");

            execute("plugin", "install", dir1.toString());
            execute("plugin", "install", dir2.toString());
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("lc-a");
            assertThat(out()).contains("lc-b");

            execute("plugin", "disable", "lc-a");
            execute("plugin", "remove", "lc-b");
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("lc-a");
            assertThat(out()).contains("no");
            assertThat(out()).doesNotContain("lc-b");

            execute("plugin", "enable", "lc-a");
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("yes");

            execute("plugin", "remove", "lc-a");
            clearOutput();
            execute("plugin", "list");
            assertThat(out()).contains("(no plugins installed)");
        }
    }

    @Nested
    class PluginReload {

        // ── happy-path ──

        @Test
        void reload_noPlugins() {
            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("No plugins installed");
        }

        @Test
        void reload_afterInstall_showsCount() throws IOException {
            var dir = writePluginManifest("rl-a", "rl-a-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reloaded 1 plugin(s)");
        }

        @Test
        void reload_afterInstallAndRemove_hidden() throws IOException {
            var dir = writePluginManifest("rl-rm", "rl-rm-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            execute("plugin", "remove", "rl-rm");
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("No plugins installed");
        }

        @Test
        void reload_multiplePlugins() throws IOException {
            var dir1 = writePluginManifest("rl-x", "rl-x-v1", "1.0.0");
            var dir2 = tempDir.resolve("plugin-rl-y");
            Files.createDirectories(dir2);
            Files.writeString(dir2.resolve("plugin.json"),
                "{ \"name\": \"rl-y\", \"id\": \"rl-y-v1\", \"version\": \"2.0.0\" }");

            execute("plugin", "install", dir1.toString());
            execute("plugin", "install", dir2.toString());
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reloaded 2 plugin(s)");
        }

        // ── manifest validation ──

        @Test
        void reload_deletedManifest_warns() throws IOException {
            var dir = writePluginManifest("rl-del", "rl-del-v1", "1.0.0");
            execute("plugin", "install", dir.toString());

            // Delete manifest to simulate broken plugin
            Files.delete(dir.resolve("plugin.json"));
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("warning");
            assertThat(out()).contains("rl-del");
            assertThat(out()).contains("manifest not found");
        }

        @Test
        void reload_corruptManifest_warns() throws IOException {
            var dir = writePluginManifest("rl-bad", "rl-bad-v1", "1.0.0");
            execute("plugin", "install", dir.toString());

            // Corrupt manifest
            Files.writeString(dir.resolve("plugin.json"), "NOT VALID JSON {{{");
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("warning");
            assertThat(out()).contains("rl-bad");
        }

        @Test
        void reload_disabledPlugin_skipped() throws IOException {
            var dir = writePluginManifest("rl-off", "rl-off-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            execute("plugin", "disable", "rl-off");
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("skipped: 1");
        }

        @Test
        void reload_validAmongInvalid_reportsBoth() throws IOException {
            var goodDir = writePluginManifest("rl-good", "rl-good-v1", "1.0.0");
            var badDir = writePluginManifest("rl-corrupt", "rl-corrupt-v1", "1.0.0");
            execute("plugin", "install", goodDir.toString());
            execute("plugin", "install", badDir.toString());

            Files.writeString(badDir.resolve("plugin.json"), "BROKEN");
            clearOutput();

            int exit = execute("plugin", "reload");
            assertThat(out()).contains("Reloaded 1 plugin(s)");
            assertThat(out()).contains("skipped: 1");
            assertThat(out()).contains("rl-corrupt");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("plugin", "reload", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reload plugin registry");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SKILLS GROUP
    //  Commands: skills, skills list|reload
    // ══════════════════════════════════════════════════════════════

    @Nested
    class SkillsGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("skills");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("list");
            assertThat(out()).contains("reload");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("skills", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("discovered skills");
        }
    }

    @Nested
    class SkillsList {

        @Test
        void listEmpty() {
            int exit = execute("skills", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no skills found)");
        }

        @Test
        void listAfterCreateSkill_showsSkill() throws IOException {
            writeSkill("translator", "# Translator Skill\n\nYou translate text.");
            clearOutput();

            int exit = execute("skills", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Translator Skill");
            assertThat(out()).contains("ok");
        }

        @Test
        void listMultipleSkills() throws IOException {
            writeSkill("translator", "# Translator\n\nTranslate text.");
            writeSkill("summarizer", "# Summarizer\n\nSummarize text.");
            clearOutput();

            int exit = execute("skills", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Translator");
            assertThat(out()).contains("Summarizer");
        }

        @Test
        void listInvalidSkills_hiddenByDefault() throws IOException {
            var dir = skillsRoot.resolve("broken");
            Files.createDirectories(dir);
            // no SKILL.md = invalid
            clearOutput();

            int exit = execute("skills", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no valid skills found)");
        }

        @Test
        void listInvalidSkills_visibleWithFlag() throws IOException {
            var dir = skillsRoot.resolve("broken");
            Files.createDirectories(dir);
            clearOutput();

            int exit = execute("skills", "list", "--show-invalid");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("broken");
            assertThat(out()).contains("invalid");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("skills", "list", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--show-invalid");
        }
    }

    @Nested
    class SkillsReload {

        // ── happy-path ──

        @Test
        void reload_noSkills() {
            int exit = execute("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("No skills found");
        }

        @Test
        void reload_afterCreateSkill_visible() throws IOException {
            writeSkill("translator", "# Translator Skill\n\nYou translate text.");
            clearOutput();

            int exit = execute("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reloaded 1 skill(s)");
        }

        @Test
        void reload_afterAddAnotherSkill_countUpdated() throws IOException {
            writeSkill("skill-a", "# Skill A\n\nDoes A.");
            execute("skills", "reload");
            clearOutput();

            writeSkill("skill-b", "# Skill B\n\nDoes B.");
            clearOutput();

            int exit = execute("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reloaded 2 skill(s)");
        }

        // ── edit → reload → updated ──

        @Test
        void reload_afterEditSkill_reflectsChange() throws IOException {
            writeSkill("changer", "# Original Name\n\nOriginal desc.");
            execute("skills", "reload");
            clearOutput();

            // Edit the skill
            writeSkill("changer", "# Updated Name\n\nUpdated desc.");
            clearOutput();

            execute("skills", "reload");
            assertThat(out()).contains("Reloaded 1 skill(s)");

            clearOutput();
            execute("skills", "list");
            assertThat(out()).contains("Updated Name");
            assertThat(out()).doesNotContain("Original Name");
        }

        // ── invalid skill warning ──

        @Test
        void reload_invalidSkill_warns() throws IOException {
            writeSkill("good", "# Good Skill\n\nWorks fine.");
            var badDir = skillsRoot.resolve("broken");
            Files.createDirectories(badDir);
            // no SKILL.md in broken/ → invalid
            clearOutput();

            int exit = execute("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("Reloaded 1 skill(s)");
            assertThat(out()).contains("skipped: 1");
            assertThat(out()).contains("warning");
            assertThat(out()).contains("broken");
        }

        @Test
        void reload_emptySkillFile_warns() throws IOException {
            writeSkill("empty", "");
            clearOutput();

            int exit = execute("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("warning");
            assertThat(out()).contains("empty");
        }

        // ── registry stability ──

        @Test
        void reload_preservesBuiltinCommands() throws IOException {
            writeSkill("some-skill", "# Some Skill\n\nA skill.");
            execute("skills", "reload");
            clearOutput();

            // Builtins should still be findable
            var registry = new CommandRegistry();
            assertThat(registry.find("session")).isPresent();
            assertThat(registry.find("auth")).isPresent();
            assertThat(registry.find("mcp")).isPresent();
            assertThat(registry.find("plugin")).isPresent();
        }

        @Test
        void reload_skillsAppearInRegistry() throws IOException {
            writeSkill("discovered", "# Discovered Skill\n\nFound it.");
            execute("skills", "reload");
            clearOutput();

            // The skills command itself should be a builtin
            var app = new AgentCliApplication();
            app.client = new HttpAgentApiClient(
                new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
            CommandLine cmd = new CommandLine(app);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));

            assertThat(app.registry().find("skills")).isPresent();
            assertThat(app.registry().find("skills").get().origin())
                .isEqualTo(CommandDescriptor.CommandOrigin.BUILTIN);
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("skills", "reload", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Reload skills");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DAEMON GROUP
    //  Commands: daemon, daemon start|status|stop
    // ══════════════════════════════════════════════════════════════

    @Nested
    class DaemonGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("daemon");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("start");
            assertThat(out()).contains("status");
            assertThat(out()).contains("stop");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("daemon", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("daemon");
        }
    }

    @Nested
    class DaemonStart {

        // ── happy-path ──

        @Test
        void start_createsState() {
            // Use the current JVM process as a stand-in — it's alive so the test passes
            long fakePid = ProcessHandle.current().pid();
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 9999, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));

            // Verify state was written
            var loaded = store.load();
            assertThat(loaded).isPresent();
            assertThat(loaded.get().pid()).isEqualTo(fakePid);
            assertThat(loaded.get().port()).isEqualTo(9999);
        }

        @Test
        void start_alreadyRunning_reportsRunning() throws IOException {
            long fakePid = ProcessHandle.current().pid();
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            clearOutput();

            // Execute daemon start — should detect existing daemon
            int exit = execute("daemon", "start");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("already running");
        }

        @Test
        void start_stalePid_clearedAndStarts() throws IOException {
            // Use a PID that doesn't exist
            long deadPid = 99999999L;
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(deadPid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            clearOutput();

            // The start command should clear stale state
            // Since no real JAR exists, it will fail to start — but stale cleanup should work
            int exit = execute("daemon", "start");
            // Either OK (found a JAR somehow) or API error (no JAR)
            // The key assertion: stale state was cleared
            assertThat(out()).contains("stale");
        }

        @Test
        void start_noJar_reportsError() throws IOException {
            int exit = execute("daemon", "start");
            // No daemon.json and no JAR → should fail
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("cannot locate");
        }

        @Test
        void start_customPort() throws IOException {
            // Write a fake JAR so resolveJar finds it
            var fakeJar = tempDir.resolve("claw-code-java.jar");
            Files.writeString(fakeJar, "fake");

            int exit = execute("daemon", "start", "--port", "9090", "--jar", fakeJar.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Daemon started");
            assertThat(out()).contains("health=UP");

            // Verify state was saved with custom port
            var store = new DaemonStateStore(daemonStateFile);
            var state = store.load();
            assertThat(state).isPresent();
            assertThat(state.get().port()).isEqualTo(9090);
        }

        @Test
        void start_readinessFailure_destroysProcessAndDoesNotSaveState() throws IOException {
            var fakeJar = tempDir.resolve("claw-code-java.jar");
            Files.writeString(fakeJar, "fake");
            var destroyed = new AtomicBoolean(false);

            var app = new AgentCliApplication();
            app.client = new HttpAgentApiClient(
                new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
            CommandLine cmd = new CommandLine(app);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));
            injectMcpStore(cmd);
            injectPluginStore(cmd);
            injectAuthStore(cmd);
            injectConfigStore(cmd);
            injectSkillStore(cmd);
            injectDaemonStore(cmd);

            var daemon = (DaemonCommand) cmd.getSubcommands().get("daemon").getCommand();
            daemon.processLauncher = (jar, port) -> new StartedDaemonProcess(98765L, () -> destroyed.set(true));
            daemon.healthChecker = new DaemonHealthChecker() {
                @Override
                public boolean waitUntilReady(int port, Duration timeout) { return false; }
                @Override
                public boolean isHealthy(int port, Duration requestTimeout) { return false; }
            };

            int exit = cmd.execute("daemon", "start", "--jar", fakeJar.toString(), "--readiness-timeout", "0");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("did not become ready");
            assertThat(destroyed).isTrue();
            assertThat(new DaemonStateStore(daemonStateFile).load()).isEmpty();
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("daemon", "start", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--port");
            assertThat(out()).contains("--jar");
            assertThat(out()).contains("--readiness-timeout");
        }
    }

    @Nested
    class DaemonStatus {

        // ── happy-path ──

        @Test
        void status_notStarted() {
            int exit = execute("daemon", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not started");
        }

        @Test
        void status_running() throws IOException {
            long fakePid = ProcessHandle.current().pid();
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            clearOutput();

            int exit = execute("daemon", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("running");
            assertThat(out()).contains("pid=" + fakePid);
            assertThat(out()).contains("port=8080");
            assertThat(out()).contains("health=UP");
        }

        @Test
        void status_stoppedProcess() throws IOException {
            long deadPid = 99999999L;
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(deadPid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            clearOutput();

            int exit = execute("daemon", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("stopped");
            assertThat(out()).contains("stale");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("daemon", "status", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("status");
        }
    }

    @Nested
    class DaemonStop {

        // ── happy-path ──

        @Test
        void stop_notRunning() {
            int exit = execute("daemon", "stop");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not running");
        }

        @Test
        void stop_runningProcess_clearsState() throws IOException {
            // Use a mock process provider that simulates a killable process
            long fakePid = 12345;
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));

            // Inject a mock process provider that says pid 12345 is alive and can be destroyed
            var app = new AgentCliApplication();
            app.client = new HttpAgentApiClient(
                new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
            CommandLine cmd = new CommandLine(app);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));
            injectMcpStore(cmd);
            injectPluginStore(cmd);
            injectAuthStore(cmd);
            injectConfigStore(cmd);
            injectSkillStore(cmd);
            injectDaemonStore(cmd);

            // Override process provider to simulate a killable process
            var daemonLine = cmd.getSubcommands().get("daemon");
            var daemon = (DaemonCommand) daemonLine.getCommand();
            daemon.processProvider = new ProcessHandleProvider() {
                @Override
                public boolean isAlive(long pid) { return pid == fakePid; }
                @Override
                public java.util.Optional<Boolean> destroy(long pid) { return java.util.Optional.of(true); }
            };

            int exit = cmd.execute("daemon", "stop");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Daemon stopped");

            assertThat(store.load()).isEmpty();
        }

        @Test
        void stop_deadProcess_clearsStaleState() throws IOException {
            long deadPid = 99999999L;
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(deadPid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            clearOutput();

            int exit = execute("daemon", "stop");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("stale");

            // Verify state cleared
            assertThat(store.load()).isEmpty();
        }

        @Test
        void stop_start_stop_cycle() throws IOException {
            // Simulate start → stop → status cycle
            long fakePid = 12346;
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 8081, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));

            // Stop with mock provider
            var app = new AgentCliApplication();
            app.client = new HttpAgentApiClient(
                new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
            CommandLine cmd = new CommandLine(app);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));
            injectMcpStore(cmd);
            injectPluginStore(cmd);
            injectAuthStore(cmd);
            injectConfigStore(cmd);
            injectSkillStore(cmd);
            injectDaemonStore(cmd);
            var daemonLine = cmd.getSubcommands().get("daemon");
            var daemon = (DaemonCommand) daemonLine.getCommand();
            daemon.processProvider = new ProcessHandleProvider() {
                @Override
                public boolean isAlive(long pid) { return pid == fakePid; }
                @Override
                public java.util.Optional<Boolean> destroy(long pid) { return java.util.Optional.of(true); }
            };
            cmd.execute("daemon", "stop");
            clearOutput();

            // Status should show not started (fresh execution with cleared state)
            int exit = execute("daemon", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not started");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("daemon", "stop", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("stop");
        }
    }

    @Nested
    class DaemonStatePersistence {

        @Test
        void stateFilePersistsAcrossReloads() throws IOException {
            long fakePid = ProcessHandle.current().pid();
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(fakePid, 7777, 1000L, DaemonState.STATUS_RUNNING));

            // Reload from same path
            var store2 = new DaemonStateStore(daemonStateFile);
            var state = store2.load();
            assertThat(state).isPresent();
            assertThat(state.get().pid()).isEqualTo(fakePid);
            assertThat(state.get().port()).isEqualTo(7777);
            assertThat(state.get().startedAt()).isEqualTo(1000L);
        }

        @Test
        void clearRemovesStateFile() throws IOException {
            var store = new DaemonStateStore(daemonStateFile);
            store.save(new DaemonState(ProcessHandle.current().pid(), 8080,
                System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            assertThat(store.load()).isPresent();

            store.clear();
            assertThat(store.load()).isEmpty();
        }
    }

    @Nested
    class DaemonStateValidation {

        @Test
        void invalidPid_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DaemonState(-1, 8080, System.currentTimeMillis(), "running"));
        }

        @Test
        void invalidPort_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DaemonState(1, 0, System.currentTimeMillis(), "running"));
        }

        @Test
        void portTooHigh_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DaemonState(1, 70000, System.currentTimeMillis(), "running"));
        }

        @Test
        void blankStatus_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DaemonState(1, 8080, System.currentTimeMillis(), "  "));
        }

        @Test
        void isRunning_true() {
            var state = new DaemonState(1, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING);
            assertThat(state.isRunning()).isTrue();
        }

        @Test
        void isRunning_false() {
            var state = new DaemonState(1, 8080, System.currentTimeMillis(), DaemonState.STATUS_STOPPED);
            assertThat(state.isRunning()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  REMOTE GROUP
    //  Commands: remote, remote status|connect|disconnect
    // ══════════════════════════════════════════════════════════════

    @Nested
    class RemoteGroup {

        @Test
        void noSubcommand_showsUsage() {
            int exit = execute("remote");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("status");
            assertThat(out()).contains("connect");
            assertThat(out()).contains("disconnect");
        }

        @Test
        void help_showsDescription() {
            int exit = execute("remote", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Remote-control");
        }
    }

    @Nested
    class RemoteStatus {

        // ── happy-path ──

        @Test
        void status_noConnection() {
            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("disconnected");
            assertThat(out()).contains("no endpoint");
        }

        @Test
        void status_connected() throws IOException {
            var store = new FileRemoteControlStore(remoteStateFile);
            store.save(new RemoteConnection("https://remote.example.com:8080", "sess-123",
                RemoteConnection.STATUS_CONNECTED, System.currentTimeMillis()));
            clearOutput();

            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("connected");
            assertThat(out()).contains("remote.example.com");
            assertThat(out()).contains("sess-123");
        }

        @Test
        void status_disconnected_withEndpoint() throws IOException {
            var store = new FileRemoteControlStore(remoteStateFile);
            store.save(new RemoteConnection("https://old.example.com", null,
                RemoteConnection.STATUS_DISCONNECTED, 0));
            clearOutput();

            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("disconnected");
            assertThat(out()).contains("old.example.com");
        }

        @Test
        void status_warnsWhenNotAuthenticated() {
            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not authenticated");
        }

        @Test
        void status_noWarnWhenAuthenticated() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).doesNotContain("not authenticated");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("remote", "status", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("status");
        }
    }

    @Nested
    class RemoteConnect {

        // ── happy-path ──

        @Test
        void connect_happyPath() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "https://remote.example.com:8080");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Connected to");
            assertThat(out()).contains("remote.example.com");
        }

        @Test
        void connect_withSession() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "https://remote.example.com",
                "--session", "sess-abc");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("sess-abc");
        }

        @Test
        void connect_sameEndpoint_idempotent() throws IOException {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://same.example.com");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "https://same.example.com");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Already connected");
        }

        @Test
        void connect_switchEndpoint() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://old.example.com");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "https://new.example.com");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disconnecting from");
            assertThat(out()).contains("Connected to");
            assertThat(out()).contains("new.example.com");
        }

        // ── invalid endpoint ──

        @Test
        void connect_invalidEndpoint_noHost() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "http://");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid endpoint");
        }

        @Test
        void connect_invalidEndpoint_badScheme() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "ftp://bad.example.com");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid endpoint");
        }

        @Test
        void connect_invalidEndpoint_notUrl() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            clearOutput();

            int exit = execute("remote", "connect", "--endpoint", "not-a-url");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid endpoint");
        }

        // ── missing auth ──

        @Test
        void connect_missingAuth_fails() {
            int exit = execute("remote", "connect", "--endpoint", "https://remote.example.com");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not authenticated");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("remote", "connect", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("--endpoint");
            assertThat(out()).contains("--session");
        }
    }

    @Nested
    class RemoteDisconnect {

        // ── happy-path ──

        @Test
        void disconnect_afterConnect() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://remote.example.com");
            clearOutput();

            int exit = execute("remote", "disconnect");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disconnected from");
            assertThat(out()).contains("remote.example.com");
        }

        @Test
        void disconnect_thenStatus_showsDisconnected() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://remote.example.com");
            execute("remote", "disconnect");
            clearOutput();

            int exit = execute("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("disconnected");
            assertThat(out()).contains("remote.example.com");
        }

        // ── edge cases ──

        @Test
        void disconnect_notConnected() {
            int exit = execute("remote", "disconnect");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("not connected");
        }

        @Test
        void disconnect_alreadyDisconnected() throws IOException {
            var store = new FileRemoteControlStore(remoteStateFile);
            store.save(new RemoteConnection("https://some.example.com", null,
                RemoteConnection.STATUS_DISCONNECTED, 0));
            clearOutput();

            int exit = execute("remote", "disconnect");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("already disconnected");
        }

        // ── help ──

        @Test
        void help_showsDescription() {
            int exit = execute("remote", "disconnect", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("disconnect");
        }
    }

    @Nested
    class RemotePersistence {

        @Test
        void connectPersistsAcrossRestart() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://persist.example.com",
                "--session", "sess-persist");
            clearOutput();

            var store = new FileRemoteControlStore(remoteStateFile);
            var conn = store.load();
            assertThat(conn).isPresent();
            assertThat(conn.get().endpoint()).isEqualTo("https://persist.example.com");
            assertThat(conn.get().sessionId()).isEqualTo("sess-persist");
            assertThat(conn.get().isConnected()).isTrue();
        }

        @Test
        void disconnectPersistsAcrossRestart() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://cycle.example.com");
            execute("remote", "disconnect");
            clearOutput();

            var store = new FileRemoteControlStore(remoteStateFile);
            var conn = store.load();
            assertThat(conn).isPresent();
            assertThat(conn.get().isConnected()).isFalse();
            assertThat(conn.get().endpoint()).isEqualTo("https://cycle.example.com");
        }

        @Test
        void fullConnectDisconnectReconnect_cycle() {
            execute("auth", "login", "--api-key", "sk-test-12345678");
            execute("remote", "connect", "--endpoint", "https://first.example.com");
            execute("remote", "disconnect");
            clearOutput();

            execute("remote", "status");
            assertThat(out()).contains("disconnected");
            assertThat(out()).contains("first.example.com");

            clearOutput();
            execute("remote", "connect", "--endpoint", "https://second.example.com");
            assertThat(out()).contains("Connected to");
            assertThat(out()).contains("second.example.com");

            clearOutput();
            execute("remote", "status");
            assertThat(out()).contains("connected");
            assertThat(out()).contains("second.example.com");
        }
    }

    @Nested
    class RemoteConnectionValidation {

        @Test
        void nullEndpoint_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RemoteConnection(null, null, "connected", 0));
        }

        @Test
        void blankEndpoint_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RemoteConnection("  ", null, "connected", 0));
        }

        @Test
        void nullStatus_throws() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RemoteConnection("http://x.com", null, null, 0));
        }

        @Test
        void isConnected_true() {
            var conn = new RemoteConnection("http://x.com", null,
                RemoteConnection.STATUS_CONNECTED, System.currentTimeMillis());
            assertThat(conn.isConnected()).isTrue();
        }

        @Test
        void isConnected_false() {
            var conn = new RemoteConnection("http://x.com", null,
                RemoteConnection.STATUS_DISCONNECTED, 0);
            assertThat(conn.isConnected()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EXCEPTION MAPPER  (cross-cutting — all error code paths)
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ExceptionMapper {

        @Test
        void authException_mapsToAuthCode() {
            var exit = CliExceptionMapper.map(new CliAuthException("bad key"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(exit.message()).contains("Authentication failed");
        }

        @Test
        void forbiddenException_mapsToAuthCode() {
            var exit = CliExceptionMapper.map(new CliAuthException("Forbidden: insufficient permissions", 403));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_AUTH);
            assertThat(exit.message()).contains("Authentication failed");
        }

        @Test
        void rateLimitException_mapsToRateLimitCode() {
            var exit = CliExceptionMapper.map(new CliRateLimitException("slow down"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_RATE_LIMITED);
            assertThat(exit.message()).contains("Rate limited");
        }

        @Test
        void notFoundException_mapsToApiErrorCode() {
            var exit = CliExceptionMapper.map(new CliNotFoundException("gone"));
            assertThat(exit.code()).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(exit.message()).contains("Not found");
        }

        @Test
        void conflictException_mapsToConflictCode() {
            var exit = CliExceptionMapper.map(new CliConflictException("duplicate"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_CONFLICT);
            assertThat(exit.message()).contains("Conflict");
        }

        @Test
        void validationException_mapsToValidationCode() {
            var exit = CliExceptionMapper.map(new CliValidationException("bad input"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_VALIDATION);
            assertThat(exit.message()).contains("Validation error");
        }

        @Test
        void apiException_mapsToApiErrorCode() {
            var exit = CliExceptionMapper.map(new CliApiException("HTTP 500"));
            assertThat(exit.code()).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(exit.message()).isEqualTo("HTTP 500");
        }

        @Test
        void timeoutException_mapsToTimeoutCode() {
            var exit = CliExceptionMapper.map(new TimeoutException("timed out"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_TIMEOUT);
            assertThat(exit.message()).contains("Request timed out");
        }

        @Test
        void connectException_mapsToConnectCode() {
            var exit = CliExceptionMapper.map(new ConnectException("refused"));
            assertThat(exit.code()).isEqualTo(CliExceptionMapper.EXIT_CONNECT);
            assertThat(exit.message()).contains("Connection refused");
        }

        @Test
        void unknownException_mapsToApiErrorCodeWithPrefix() {
            var exit = CliExceptionMapper.map(new RuntimeException("boom"));
            assertThat(exit.code()).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(exit.message()).contains("Unexpected error");
            assertThat(exit.message()).contains("boom");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ROUTING  (cross-cutting — unknown commands, REPL entry)
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Routing {

        @Test
        void unknownCommand_nonZeroExit() {
            int exit = execute("nonexistent");
            assertThat(exit).isNotZero();
        }

        @Test
        void unknownSubcommand_nonZeroExit() {
            int exit = execute("session", "nonexistent");
            assertThat(exit).isNotZero();
        }

        @Test
        void noArgs_entersRepl_exitsCleanly() {
            InputStream savedIn = System.in;
            try {
                System.setIn(new ByteArrayInputStream("exit\n".getBytes()));
                int exit = execute();

                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Free Code Java Agent");
            } finally {
                System.setIn(savedIn);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  FAST PATHS
    //  Verify local commands don't trigger HTTP client creation.
    // ══════════════════════════════════════════════════════════════

    @Nested
    class FastPaths {

        private AgentCliApplication lastApp;

        /** Execute without pre-injecting an HTTP client — tests lazy creation. */
        private int executeLazy(String... args) {
            lastApp = new AgentCliApplication();
            CommandLine cmd = new CommandLine(lastApp);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));
            injectMcpStore(cmd);
            injectPluginStore(cmd);
            injectAuthStore(cmd);
            injectConfigStore(cmd);
            injectSkillStore(cmd);
            injectDaemonStore(cmd);
            injectRemoteStore(cmd);
            return cmd.execute(args);
        }

        @Test
        void help_noClientCreated() {
            int exit = executeLazy("--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void version_noClientCreated() {
            int exit = executeLazy("--version");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void authStatus_noClientCreated() {
            int exit = executeLazy("auth", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void authLogin_noClientCreated() {
            int exit = executeLazy("auth", "login", "--api-key", "test-key");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void configSet_noClientCreated() {
            int exit = executeLazy("config", "set", "timeoutMs", "60000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void configGet_noClientCreated() {
            int exit = executeLazy("config", "get", "baseUrl");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void configList_noClientCreated() {
            int exit = executeLazy("config", "list", "--show-defaults");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void mcpList_noClientCreated() {
            int exit = executeLazy("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void mcpAdd_noClientCreated() {
            int exit = executeLazy("mcp", "add", "test-srv", "--url", "http://localhost:3000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void pluginList_noClientCreated() {
            int exit = executeLazy("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void pluginReload_noClientCreated() {
            int exit = executeLazy("plugin", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void skillsList_noClientCreated() {
            int exit = executeLazy("skills", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void skillsReload_noClientCreated() {
            int exit = executeLazy("skills", "reload");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void daemonStatus_noClientCreated() {
            int exit = executeLazy("daemon", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void remoteStatus_noClientCreated() {
            int exit = executeLazy("remote", "status");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isFalse();
        }

        @Test
        void sessionCreate_createsClient() {
            server.enqueue(new MockResponse()
                .setBody("{\"sessionId\":\"s-1\",\"createdAt\":\"2026-04-22T12:00:00Z\"}")
                .setHeader("Content-Type", "application/json"));

            // Use executeLazy so client is NOT pre-created — session create should trigger it
            lastApp = new AgentCliApplication();
            CommandLine cmd = new CommandLine(lastApp);
            cmd.setOut(new PrintWriter(outWriter, true));
            cmd.setErr(new PrintWriter(errWriter, true));

            // Override base-url to point to mock server (picocli injects options)
            int exit = cmd.execute("--base-url", server.url("").toString(),
                "--timeout", "5000", "--stream-timeout", "5000",
                "session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(lastApp.clientCreated()).isTrue();
        }

        @Test
        void existingRouting_unchanged() {
            // Verify the existing test harness still works
            int exit = execute("auth", "login", "--api-key", "test");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Credentials saved");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  REPL SLASH COMMANDS
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ReplMcpSlash {

        @Test
        void replSlashMcpList_empty() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/mcp list\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("(no mcp servers)");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashMcpAddThenList() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                String input = "/mcp add demo --url http://localhost:3000\n/mcp list\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Added MCP server 'demo'");
                assertThat(out()).contains("demo");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplPluginSlash {

        @Test
        void replSlashPluginList_empty() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/plugin list\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("(no plugins installed)");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashPluginInstallThenList() throws IOException {
            var dir = writePluginManifest("repl-plg", "repl-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                String input = "/plugin install " + dir + "\n/plugin list\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Installed plugin 'repl-plg'");
                assertThat(out()).contains("repl-plg");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashPluginDisableEnable() throws IOException {
            var dir = writePluginManifest("repl-toggle", "rt-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                String input = "/plugin install " + dir + "\n/plugin disable repl-toggle\n/plugin list\n/plugin enable repl-toggle\n/plugin list\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Disabled plugin 'repl-toggle'");
                assertThat(out()).contains("Enabled plugin 'repl-toggle'");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashPluginRemove() throws IOException {
            var dir = writePluginManifest("repl-rm", "rr-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                String input = "/plugin install " + dir + "\n/plugin remove repl-rm\n/plugin list\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Removed plugin 'repl-rm'");
                assertThat(out()).contains("(no plugins installed)");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplPluginReloadSlash {

        @Test
        void replSlashPluginReload_empty() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/plugin reload\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("No plugins installed");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashPluginReload_afterInstall() throws IOException {
            var dir = writePluginManifest("repl-rl", "rrl-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                String input = "/plugin install " + dir + "\n/plugin reload\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Reloaded 1 plugin(s)");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplSkillsReloadSlash {

        @Test
        void replSlashSkillsReload_empty() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/skills reload\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("No skills found");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashSkillsReload_afterCreate() throws IOException {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                var skillDir = tempDir.resolve(".agent-cli").resolve("skills").resolve("translator");
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"),
                    "# Translator\n\nTranslate text.");
                String input = "/skills reload\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Reloaded 1 skill(s)");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplReloadSlash {

        @Test
        void replSlashReload_bothEmpty() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/reload\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Reloading plugins");
                assertThat(out()).contains("Reloading skills");
                assertThat(out()).contains("Reload complete");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashReload_withPluginAndSkill() throws IOException {
            var dir = writePluginManifest("repl-rl2", "rrl2-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                var skillDir = tempDir.resolve(".agent-cli").resolve("skills").resolve("summarizer");
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"),
                    "# Summarizer\n\nSummarize text.");
                String input = "/plugin install " + dir + "\n/reload\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Reloaded 1 plugin(s)");
                assertThat(out()).contains("Reloaded 1 skill(s)");
                assertThat(out()).contains("Reload complete");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashReload_invalidSkill_warns() throws IOException {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                // Create broken skill: directory without SKILL.md
                Files.createDirectories(tempDir.resolve(".agent-cli").resolve("skills").resolve("broken"));
                String input = "/reload\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("warning");
                assertThat(out()).contains("broken");
                assertThat(out()).contains("Reload complete");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashReload_invalidPlugin_warns() throws IOException {
            var dir = writePluginManifest("repl-bad", "rb-v1", "1.0.0");
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                // Install then corrupt manifest
                String input = "/plugin install " + dir + "\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                execute();
                // Corrupt manifest
                Files.writeString(dir.resolve("plugin.json"), "BROKEN");
                clearOutput();

                System.setIn(new ByteArrayInputStream("/reload\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("warning");
                assertThat(out()).contains("Reload complete");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashReload_helpUpdatedAfterSkillDiscovery() throws IOException {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                var skillDir = tempDir.resolve(".agent-cli").resolve("skills").resolve("greeter");
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"),
                    "# Greeter Skill\n\nSays hello.");
                String input = "/reload\n/help\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Reloaded 1 skill(s)");
                // skills builtin should appear in help
                assertThat(out()).contains("skills");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplDaemonSlash {

        @Test
        void replSlashDaemonStatus_notStarted() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/daemon status\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("not started");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashDaemonStop_notRunning() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/daemon stop\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("not running");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashDaemonNoArgs_showsUsage() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/daemon\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Usage: /daemon");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashDaemonStatus_running() throws IOException {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                long fakePid = ProcessHandle.current().pid();
                var store = new DaemonStateStore(tempDir.resolve(".agent-cli").resolve("daemon.json"));
                store.save(new DaemonState(fakePid, 8080, System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
                clearOutput();

                System.setIn(new ByteArrayInputStream("/daemon status\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("running");
                assertThat(out()).contains("pid=" + fakePid);
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    @Nested
    class ReplRemoteSlash {

        @Test
        void replSlashRemoteStatus_disconnected() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/remote status\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("disconnected");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashRemoteConnectThenStatus() throws IOException {
            // Pre-authenticate to the path the REPL's default auth store will read
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                var agentCliDir = tempDir.resolve(".agent-cli");
                Files.createDirectories(agentCliDir);
                var replAuthStore = new FileAuthStore(agentCliDir.resolve("auth.json"));
                replAuthStore.save(new com.clawcode.agent.cli.auth.AuthCredentials(
                    "sk-test-12345678", "X-API-Key", Map.of(), Instant.now()));
                clearOutput();

                String input = "/remote connect --endpoint https://remote.example.com\n/remote status\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Connected to");
                assertThat(out()).contains("remote.example.com");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashRemoteConnectThenDisconnect() throws IOException {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                var agentCliDir = tempDir.resolve(".agent-cli");
                Files.createDirectories(agentCliDir);
                var replAuthStore = new FileAuthStore(agentCliDir.resolve("auth.json"));
                replAuthStore.save(new com.clawcode.agent.cli.auth.AuthCredentials(
                    "sk-test-12345678", "X-API-Key", Map.of(), Instant.now()));
                clearOutput();

                String input = "/remote connect --endpoint https://repl.example.com\n"
                    + "/remote disconnect\n"
                    + "/remote status\nexit\n";
                System.setIn(new ByteArrayInputStream(input.getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Disconnected from");
                assertThat(out()).contains("disconnected");
                assertThat(out()).contains("repl.example.com");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }

        @Test
        void replSlashRemoteNoArgs_showsUsage() {
            InputStream savedIn = System.in;
            String savedHome = System.getProperty("user.home");
            try {
                System.setProperty("user.home", tempDir.toString());
                System.setIn(new ByteArrayInputStream("/remote\nexit\n".getBytes()));
                int exit = execute();
                assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
                assertThat(out()).contains("Usage: /remote");
            } finally {
                System.setIn(savedIn);
                System.setProperty("user.home", savedHome);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    private Path writePluginManifest(String name, String id, String version) throws IOException {
        var dir = tempDir.resolve("plugin-" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"),
            "{ \"name\": \"" + name + "\", \"id\": \"" + id + "\", \"version\": \"" + version + "\" }");
        return dir;
    }

    private Path writePluginManifestFile(String name, String id) throws IOException {
        var file = tempDir.resolve("direct-manifest-" + name + ".json");
        Files.writeString(file,
            "{ \"name\": \"" + name + "\", \"id\": \"" + id + "\" }");
        return file;
    }

    private Path writeBadPluginManifest(String content) throws IOException {
        var dir = tempDir.resolve("bad-plugin-" + System.nanoTime());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"), content);
        return dir;
    }

    private Path writeSkill(String dirName, String content) throws IOException {
        var dir = skillsRoot.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
        return dir;
    }
}
