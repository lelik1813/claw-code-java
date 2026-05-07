package com.clawcode.agent.cli.commands.mcp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class McpCommandTest {

    @TempDir
    Path tempDir;

    private StringWriter outWriter;
    private StringWriter errWriter;
    private CommandLine cmd;

    @BeforeEach
    void setUp() {
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        var app = new AgentCliApplication();
        cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));

        var tempConfig = tempDir.resolve("mcp-servers.json");
        var mcpCmdLine = cmd.getSubcommands().get("mcp");
        var mcpCmd = (McpCommand) mcpCmdLine.getCommand();
        mcpCmd.store = new FileMcpConfigStore(tempConfig);
    }

    private String out() { return outWriter.toString().trim(); }

    private void clearOutput() {
        outWriter.getBuffer().setLength(0);
    }

    private Path successfulStdioCommand() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = tempDir.resolve(windows ? "ok command.cmd" : "ok command.sh");
        Files.writeString(script, windows ? "@echo off\r\nexit /b 0\r\n" : "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);
        return script;
    }

    // ── group routing ───────────────────────────────────────

    @Nested
    class GroupRouting {

        @Test
        void mcpWithoutSubcommand_showsUsage() {
            int exit = cmd.execute("mcp");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("list");
            assertThat(out()).contains("add");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("test");
        }

        @Test
        void mcpHelp_showsSubcommands() {
            int exit = cmd.execute("mcp", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Manage MCP server connections");
            assertThat(out()).contains("list");
            assertThat(out()).contains("add");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("test");
        }
    }

    // ── list ────────────────────────────────────────────────

    @Nested
    class ListCommand {

        @Test
        void listHelp_showsDescription() {
            int exit = cmd.execute("mcp", "list", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("List configured MCP servers");
            assertThat(out()).contains("--json");
        }

        @Test
        void listEmpty_printsNoServers() {
            int exit = cmd.execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no mcp servers)");
        }

        @Test
        void listServers_tableOutput() {
            cmd.execute("mcp", "add", "alpha", "--url", "http://localhost:3000");
            cmd.execute("mcp", "add", "beta", "--type", "STDIO", "--command", "npx");
            clearOutput();

            int exit = cmd.execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("TRANSPORT");
            assertThat(out()).contains("TARGET");
            assertThat(out()).contains("ENABLED");
            assertThat(out()).contains("alpha");
            assertThat(out()).contains("HTTP");
            assertThat(out()).contains("beta");
            assertThat(out()).contains("STDIO");
        }

        @Test
        void listServers_jsonOutput() {
            cmd.execute("mcp", "add", "alpha", "--url", "http://localhost:3000");
            clearOutput();

            int exit = cmd.execute("mcp", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"alpha\"");
            assertThat(out()).contains("\"transport\" : \"HTTP\"");
        }
    }

    // ── add ─────────────────────────────────────────────────

    @Nested
    class AddCommand {

        @Test
        void addHelp_showsOptions() {
            int exit = cmd.execute("mcp", "add", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("--type");
            assertThat(out()).contains("--url");
            assertThat(out()).contains("--command");
            assertThat(out()).contains("--arg");
            assertThat(out()).contains("--env");
            assertThat(out()).contains("--auth-token");
            assertThat(out()).contains("--disabled");
        }

        @Test
        void addHttpMinimal() {
            int exit = cmd.execute("mcp", "add", "my-server", "--url", "http://localhost:3000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Added MCP server 'my-server'");
            assertThat(out()).contains("type=HTTP");
        }

        @Test
        void addStdioWithCommand() {
            int exit = cmd.execute("mcp", "add", "my-server",
                "--type", "STDIO", "--command", "npx");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("type=STDIO");
        }

        @Test
        void addStdioWithArgsAndEnv() {
            int exit = cmd.execute("mcp", "add", "runner",
                "--type", "STDIO", "--command", "node",
                "--arg", "server.js", "--arg", "--verbose",
                "--env", "PORT=3000", "--env", "DEBUG=true");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);

            clearOutput();
            cmd.execute("mcp", "list", "--json");
            assertThat(out()).contains("\"name\" : \"runner\"");
            assertThat(out()).contains("\"command\" : \"node\"");
        }

        @Test
        void addDisabled() {
            cmd.execute("mcp", "add", "paused", "--url", "http://localhost:3000", "--disabled");
            clearOutput();
            cmd.execute("mcp", "list");
            assertThat(out()).contains("paused");
            assertThat(out()).contains("no");
        }

        @Test
        void addDuplicate_rejected() {
            cmd.execute("mcp", "add", "dup", "--url", "http://localhost:3000");
            clearOutput();
            int exit = cmd.execute("mcp", "add", "dup", "--url", "http://localhost:4000");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void addUrlTrailingSlash_stripped() {
            cmd.execute("mcp", "add", "slash", "--url", "http://localhost:3000///");
            clearOutput();
            cmd.execute("mcp", "list", "--json");
            assertThat(out()).contains("http://localhost:3000");
            assertThat(out()).doesNotContain("localhost:3000///");
        }

        @Test
        void addEnvBadFormat_validationError() {
            int exit = cmd.execute("mcp", "add", "bad-env",
                "--url", "http://localhost:3000", "--env", "NOEQUALS");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid --env format");
        }

        @Test
        void addThenList_visible() {
            cmd.execute("mcp", "add", "svr1", "--url", "http://a:80");
            cmd.execute("mcp", "add", "svr2", "--type", "STDIO", "--command", "run.sh");
            clearOutput();
            int exit = cmd.execute("mcp", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("svr1");
            assertThat(out()).contains("svr2");
        }

        @Test
        void addMissingName_usageError() {
            int exit = cmd.execute("mcp", "add");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void addHttpMissingUrl_validationError() {
            int exit = cmd.execute("mcp", "add", "my-server", "--type", "HTTP");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("url is required for HTTP transport");
        }

        @Test
        void addStdioMissingCommand_validationError() {
            int exit = cmd.execute("mcp", "add", "my-server", "--type", "STDIO");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("command is required for STDIO transport");
        }

        @Test
        void addInvalidType_validationError() {
            int exit = cmd.execute("mcp", "add", "my-server", "--type", "BOGUS");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("invalid transport");
        }
    }

    // ── remove ──────────────────────────────────────────────

    @Nested
    class RemoveCommand {

        @Test
        void removeHelp_showsNameAndForce() {
            int exit = cmd.execute("mcp", "remove", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("--force");
        }

        @Test
        void removeExisting_succeeds() {
            cmd.execute("mcp", "add", "to-remove", "--url", "http://localhost:3000");
            int exit = cmd.execute("mcp", "remove", "to-remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed MCP server 'to-remove'");
        }

        @Test
        void removeThenList_gone() {
            cmd.execute("mcp", "add", "temp", "--url", "http://localhost:3000");
            cmd.execute("mcp", "remove", "temp");
            clearOutput();
            cmd.execute("mcp", "list");
            assertThat(out()).contains("(no mcp servers)");
        }

        @Test
        void removeTwice_secondFails() {
            cmd.execute("mcp", "add", "once", "--url", "http://localhost:3000");
            cmd.execute("mcp", "remove", "once");
            clearOutput();
            int exit = cmd.execute("mcp", "remove", "once");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_returnsApiError() {
            int exit = cmd.execute("mcp", "remove", "no-such-server");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_force_exitsOk() {
            int exit = cmd.execute("mcp", "remove", "no-such-server", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        }

        @Test
        void removeMissingName_usageError() {
            int exit = cmd.execute("mcp", "remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }

    // ── test ────────────────────────────────────────────────

    @Nested
    class TestCommand {

        MockWebServer server;

        @BeforeEach
        void startServer() throws Exception {
            server = new MockWebServer();
            server.start();
        }

        @AfterEach
        void stopServer() throws Exception {
            if (server != null) server.shutdown();
        }

        @Test
        void testHelp_showsNameAndTimeout() {
            int exit = cmd.execute("mcp", "test", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Test connectivity");
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("--timeout");
        }

        @Test
        void testHttpServerOk() {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            var url = server.url("/").toString();
            cmd.execute("mcp", "add", "live", "--url", url);
            clearOutput();

            int exit = cmd.execute("mcp", "test", "live", "--timeout", "3");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("OK");
            assertThat(out()).contains("HTTP 200");
        }

        @Test
        void testHttpServer5xx() {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            var url = server.url("/").toString();
            cmd.execute("mcp", "add", "sick", "--url", url);
            clearOutput();

            int exit = cmd.execute("mcp", "test", "sick");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
            assertThat(out()).contains("503");
        }

        @Test
        void testHttpConnectionRefused() {
            cmd.execute("mcp", "add", "dead", "--url", "http://localhost:1");
            clearOutput();

            int exit = cmd.execute("mcp", "test", "dead", "--timeout", "1");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
        }

        @Test
        void testStdioOk() throws IOException {
            var command = successfulStdioCommand();
            cmd.execute("mcp", "add", "java-cmd", "--type", "STDIO",
                "--command", command.toString());
            clearOutput();

            int exit = cmd.execute("mcp", "test", "java-cmd");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("OK");
        }

        @Test
        void testStdioFails() {
            cmd.execute("mcp", "add", "bad-cmd", "--type", "STDIO",
                "--command", "false");
            clearOutput();

            int exit = cmd.execute("mcp", "test", "bad-cmd");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("FAIL");
        }

        @Test
        void testNotFound_returnsApiError() {
            int exit = cmd.execute("mcp", "test", "no-such-server");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void testMissingName_usageError() {
            int exit = cmd.execute("mcp", "test");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }
}
