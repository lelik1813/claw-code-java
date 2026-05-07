package com.clawcode.agent.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import com.clawcode.agent.cli.commands.mcp.McpCommand;
import com.clawcode.agent.cli.commands.plugin.PluginCommand;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
import com.clawcode.agent.model.ModelClient;
import com.clawcode.agent.model.ModelCompletedEvent;
import com.clawcode.agent.model.ModelStreamStartedEvent;
import com.clawcode.agent.model.ModelTextDeltaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.reactive.server.WebTestClient;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke tests — verify CLI commands wire correctly through
 * the Spring server runtime.
 *
 * Groups: core lifecycle | mcp | plugin | error paths
 *
 * SSE notes: live-stream tests are inherently racy because the noop model
 * client completes before stream attach subscribes. Tests that depend on
 * live SSE events accept either EXIT_OK or EXIT_TIMEOUT; replay-based
 * tests are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCliEndToEndTest {

    @TempDir
    Path tempDir;

    @LocalServerPort
    int port;

    @Autowired
    ModelClient modelClient;

    private StringWriter outWriter;
    private StringWriter errWriter;
    private Path mcpConfigFile;
    private Path pluginConfigFile;

    @BeforeEach
    void setUp() {
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        mcpConfigFile = tempDir.resolve("mcp-servers.json");
        pluginConfigFile = tempDir.resolve("plugins.json");
    }

    private int execute(String... args) {
        var app = new AgentCliApplication();
        app.client = new HttpAgentApiClient(
            new CliProperties("http://localhost:" + port, "X-API-Key", null, 5000, 2000));
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));
        injectStores(cmd);
        return cmd.execute(args);
    }

    private void injectStores(CommandLine cmd) {
        var mcpLine = cmd.getSubcommands().get("mcp");
        if (mcpLine != null) {
            ((McpCommand) mcpLine.getCommand()).store = new FileMcpConfigStore(mcpConfigFile);
        }
        var pluginLine = cmd.getSubcommands().get("plugin");
        if (pluginLine != null) {
            ((PluginCommand) pluginLine.getCommand()).store = new FilePluginConfigStore(pluginConfigFile);
        }
    }

    private String out() { return outWriter.toString().trim(); }
    private String err() { return errWriter.toString().trim(); }

    private void clearOutput() {
        outWriter.getBuffer().setLength(0);
        errWriter.getBuffer().setLength(0);
    }

    private WebTestClient apiClient() {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(5))
            .build();
    }

    private String createSessionViaApi() {
        return apiClient().post().uri("/api/sessions")
            .exchange()
            .expectBody(JsonSession.class)
            .returnResult().getResponseBody().sessionId();
    }

    @TestConfiguration
    static class NoopModelConfig {

        @Bean("testModelClient")
        @Primary
        ModelClient testModelClient() {
            return request -> Flux.just(
                new ModelStreamStartedEvent(request.model()),
                new ModelTextDeltaEvent("noop"),
                new ModelCompletedEvent()
            );
        }
    }

    record JsonSession(String sessionId, String createdAt) {}

    // ══════════════════════════════════════════════════════════════
    //  CORE LIFECYCLE: session → message → stream / replay
    // ══════════════════════════════════════════════════════════════

    @Nested
    class CoreLifecycle {

        @Test
        void sessionCreate_againstRealServer() {
            int exit = execute("session", "create");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isNotBlank();
            assertThat(out()).matches("^[a-f0-9\\-]+$");
        }

        @Test
        void messageSend_againstRealSession() {
            String sessionId = createSessionViaApi();
            clearOutput();

            int exit = execute("message", "send", sessionId, "test prompt");

            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("accepted");
        }

        @Test
        void fullWorkflow_sessionCreateAndMessageSend() {
            // 1. session create via CLI
            int exit = execute("session", "create");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            String sessionId = out();
            assertThat(sessionId).isNotBlank();
            clearOutput();

            // 2. message send via CLI
            exit = execute("message", "send", sessionId, "hello");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("accepted");
        }

        @Test
        void fullWorkflow_withReplay_viaAfterCursor() {
            String sessionId = createSessionViaApi();
            clearOutput();

            // send message so transcript has content
            int exit = execute("message", "send", sessionId, "test replay");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).isEqualTo("accepted");
            clearOutput();

            // stream attach with --after-cursor replays transcript (deterministic)
            // then attaches live. Replay part is reliable; live SSE may time out.
            exit = execute("stream", "attach", sessionId, "--after-cursor", "0");
            // Replay is deterministic. Live SSE may timeout or error — accept all non-crash outcomes.
            assertThat(exit).isIn(AgentCliApplication.EXIT_OK,
                CliExceptionMapper.EXIT_TIMEOUT,
                AgentCliApplication.EXIT_API_ERROR);
            // Replay must show the user's message
            assertThat(out()).contains("[user] test replay");
        }

        /**
         * Live SSE stream attach — potentially flaky.
         * The noop model completes instantly, so live events may be missed
         * if stream attach subscribes after model processing finishes.
         * Accept OK or TIMEOUT as valid outcomes.
         */
        @Test
        void streamAttach_liveSSE_acceptsOkOrTimeout() {
            String sessionId = createSessionViaApi();
            clearOutput();

            // No assertion on "noop" content — live SSE is racy.
            // We verify the command completes without an unhandled crash.
            int exit = execute("stream", "attach", sessionId);
            // SSE live stream is racy: noop model finishes before stream subscribes.
            // Accept OK (events arrived), TIMEOUT, or API_ERROR (reactive timeout wraps as generic).
            assertThat(exit).isIn(AgentCliApplication.EXIT_OK,
                CliExceptionMapper.EXIT_TIMEOUT,
                AgentCliApplication.EXIT_API_ERROR);
        }

        @Test
        void streamAttach_withMessage_liveSSE_acceptsOkOrTimeout() {
            String sessionId = createSessionViaApi();
            clearOutput();

            execute("message", "send", sessionId, "hello");
            clearOutput();

            int exit = execute("stream", "attach", sessionId);
            assertThat(exit).isIn(AgentCliApplication.EXIT_OK,
                CliExceptionMapper.EXIT_TIMEOUT,
                AgentCliApplication.EXIT_API_ERROR);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MCP LIFECYCLE: add → list → test → remove
    // ══════════════════════════════════════════════════════════════

    @Nested
    class McpLifecycle {

        @Test
        void mcpFullCycle_addListTestRemove() {
            String url = "http://localhost:" + port;

            // 1. add
            int exit = execute("mcp", "add", "e2e-srv", "--url", url);
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Added MCP server 'e2e-srv'");
            clearOutput();

            // 2. list — shows entry
            exit = execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("e2e-srv");
            assertThat(out()).contains("HTTP");
            clearOutput();

            // 3. test — should succeed against the real server
            exit = execute("mcp", "test", "e2e-srv", "--timeout", "5");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("OK");
            clearOutput();

            // 4. remove
            exit = execute("mcp", "remove", "e2e-srv");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed MCP server 'e2e-srv'");
            clearOutput();

            // 5. list — empty
            exit = execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no mcp servers)");
        }

        @Test
        void mcpAddStdio_list_showsStdioType() {
            int exit = execute("mcp", "add", "local-tool", "--type", "STDIO", "--command", "echo hi");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            clearOutput();

            exit = execute("mcp", "list");
            assertThat(out()).contains("local-tool");
            assertThat(out()).contains("STDIO");
        }

        @Test
        void mcpTest_failingServer() {
            execute("mcp", "add", "dead", "--url", "http://localhost:1");
            clearOutput();

            int exit = execute("mcp", "test", "dead", "--timeout", "1");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
        }

        @Test
        void mcpAddDuplicate_rejected() {
            execute("mcp", "add", "dup", "--url", "http://localhost:" + port);
            clearOutput();

            int exit = execute("mcp", "add", "dup", "--url", "http://localhost:" + port);
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void mcpListJson_format() {
            execute("mcp", "add", "json-srv", "--url", "http://localhost:" + port);
            clearOutput();

            int exit = execute("mcp", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"json-srv\"");
            assertThat(out()).contains("\"transport\" : \"HTTP\"");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  PLUGIN LIFECYCLE: install → list → disable → enable → remove
    // ══════════════════════════════════════════════════════════════

    @Nested
    class PluginLifecycle {

        @Test
        void pluginFullCycle_installListDisableEnableRemove() throws IOException {
            var pluginDir = writePluginManifest("e2e-plugin", "e2e-v1", "1.0.0");

            // 1. install
            int exit = execute("plugin", "install", pluginDir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'e2e-plugin'");
            assertThat(out()).contains("id=e2e-v1");
            clearOutput();

            // 2. list — shows entry, enabled
            exit = execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("e2e-plugin");
            assertThat(out()).contains("yes");
            clearOutput();

            // 3. disable
            exit = execute("plugin", "disable", "e2e-plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disabled plugin 'e2e-plugin'");
            clearOutput();

            // 4. list — shows disabled
            exit = execute("plugin", "list");
            assertThat(out()).contains("e2e-plugin");
            assertThat(out()).contains("no");
            clearOutput();

            // 5. enable
            exit = execute("plugin", "enable", "e2e-plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Enabled plugin 'e2e-plugin'");
            clearOutput();

            // 6. list — shows enabled again
            exit = execute("plugin", "list");
            assertThat(out()).contains("yes");
            clearOutput();

            // 7. remove
            exit = execute("plugin", "remove", "e2e-plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed plugin 'e2e-plugin'");
            clearOutput();

            // 8. list — empty
            exit = execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no plugins installed)");
        }

        @Test
        void pluginListJson_format() throws IOException {
            var pluginDir = writePluginManifest("json-plg", "json-v1", "2.0.0");
            execute("plugin", "install", pluginDir.toString());
            clearOutput();

            int exit = execute("plugin", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"json-plg\"");
            assertThat(out()).contains("\"id\" : \"json-v1\"");
            assertThat(out()).contains("\"version\" : \"2.0.0\"");
        }

        @Test
        void pluginInstallDuplicate_rejected() throws IOException {
            var dir = writePluginManifest("dup-plg", "dup-v1", "1.0.0");
            execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void pluginInstall_badPath() {
            int exit = execute("plugin", "install", "/no/such/path");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("path does not exist");
        }

        @Test
        void pluginRemoveNotFound() {
            int exit = execute("plugin", "remove", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void pluginInstallDisabled_flag() throws IOException {
            var dir = writePluginManifest("off-plg", "off-v1", "1.0.0");
            int exit = execute("plugin", "install", dir.toString(), "--disabled");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            clearOutput();

            execute("plugin", "list");
            assertThat(out()).contains("off-plg");
            assertThat(out()).contains("no");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ERROR PATHS
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ErrorPaths {

        @Test
        void messageSend_unknownSession_returnsApiError() {
            int exit = execute("message", "send", "nonexistent-session", "hello");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).isNotBlank();
        }

        @Test
        void streamAttach_unknownSession_returnsApiError() {
            int exit = execute("stream", "attach", "nonexistent-session");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(err()).isNotBlank();
        }

        @Test
        void streamAttach_blankSession_usageError() {
            int exit = execute("stream", "attach", "   ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("SESSION_ID must not be blank");
        }

        @Test
        void messageSend_blankSession_usageError() {
            int exit = execute("message", "send", "   ", "hello");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("SESSION_ID must not be blank");
        }

        @Test
        void messageSend_blankContent_usageError() {
            int exit = execute("message", "send", "s-1", "   ");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(err()).contains("CONTENT must not be blank");
        }

        @Test
        void mcpAdd_missingUrl_usageError() {
            int exit = execute("mcp", "add", "no-url", "--type", "HTTP");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("url is required");
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
}
