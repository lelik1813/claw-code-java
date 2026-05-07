package com.clawcode.agent.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCliApplicationTest {

    private MockWebServer server;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        outWriter = new StringWriter();
        errWriter = new StringWriter();
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
        return cmd.execute(args);
    }

    private String out() { return outWriter.toString().trim(); }
    private String err() { return errWriter.toString().trim(); }

    // --- no args / help ---

    @Test
    void noArgs_entersRepl_exitsCleanly() {
        InputStream savedIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("exit\n".getBytes(StandardCharsets.UTF_8)));
            int exit = execute();
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Free Code Java Agent");
        } finally {
            System.setIn(savedIn);
        }
    }

    @Test
    void helpFlag_showsGroupCommands() {
        int exit = execute("--help");
        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).contains("session");
        assertThat(out()).contains("message");
        assertThat(out()).contains("stream");
        assertThat(out()).contains("auth");
        assertThat(out()).contains("mcp");
        assertThat(out()).contains("plugin");
        assertThat(out()).contains("config");
        assertThat(out()).contains("repl");
    }

    // --- session create ---

    @Test
    void sessionCreate_success() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-123\",\"createdAt\":\"2026-04-22T12:00:00Z\"}")
            .setHeader("Content-Type", "application/json"));

        int exit = execute("session", "create");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).isEqualTo("s-123");
    }

    @Test
    void sessionCreate_apiError() {
        server.enqueue(new MockResponse().setResponseCode(401)
            .setBody("{\"error\":\"Unauthorized\"}"));

        int exit = execute("session", "create");

        assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
        assertThat(err()).contains("Authentication failed");
    }

    @Test
    void sessionCreate_emptyResponse() {
        server.enqueue(new MockResponse()
            .setBody("")
            .setHeader("Content-Type", "application/json"));

        int exit = execute("session", "create");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
        assertThat(err()).contains("empty response");
    }

    // --- message send ---

    @Test
    void messageSend_success() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-1\",\"accepted\":true}")
            .setHeader("Content-Type", "application/json"));

        int exit = execute("message", "send", "s-1", "hello");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).isEqualTo("accepted");
    }

    @Test
    void messageSend_withSkills() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-1\",\"accepted\":true}")
            .setHeader("Content-Type", "application/json"));

        int exit = execute("message", "send", "s-1", "translate", "--skill", "translator");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).isEqualTo("accepted");
    }

    @Test
    void messageSend_missingArgs_usageError() {
        int exit = execute("message", "send");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
    }

    @Test
    void messageSend_blankSessionId_usageError() {
        int exit = execute("message", "send", " ", "hello");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        assertThat(err()).contains("SESSION_ID must not be blank");
    }

    @Test
    void messageSend_blankContent_usageError() {
        int exit = execute("message", "send", "s-1", " ");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        assertThat(err()).contains("CONTENT must not be blank");
    }

    @Test
    void messageSend_apiError() {
        server.enqueue(new MockResponse().setResponseCode(404)
            .setBody("{\"error\":\"Session not found\"}"));

        int exit = execute("message", "send", "missing", "hello");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
        assertThat(err()).contains("Not found");
    }

    @Test
    void messageSend_rejected() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-1\",\"accepted\":false}")
            .setHeader("Content-Type", "application/json"));

        int exit = execute("message", "send", "s-1", "hello");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
        assertThat(out()).contains("rejected");
    }

    // --- stream attach ---

    @Test
    void streamAttach_receivesEvents() {
        server.enqueue(new MockResponse()
            .setBody("data: {\"type\":\"delta\",\"text\":\"event1\"}\n\ndata: {\"type\":\"delta\",\"text\":\"event2\"}\n\n")
            .setHeader("Content-Type", "text/event-stream"));

        int exit = execute("stream", "attach", "s-1");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).contains("event1");
        assertThat(out()).contains("event2");
    }

    @Test
    void streamAttach_apiError() {
        server.enqueue(new MockResponse().setResponseCode(401)
            .setBody("{\"error\":\"Unauthorized\"}"));

        int exit = execute("stream", "attach", "s-1");

        assertThat(exit).isEqualTo(CliExceptionMapper.EXIT_AUTH);
        assertThat(err()).contains("Authentication failed");
    }

    @Test
    void streamAttach_blankSessionId_usageError() {
        int exit = execute("stream", "attach", " ");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        assertThat(err()).contains("SESSION_ID must not be blank");
    }

    // --- incomplete group commands (usage) ---

    @Test
    void sessionWithoutAction_showsUsage() {
        int exit = execute("session");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).contains("create");
    }

    @Test
    void messageWithoutAction_showsUsage() {
        int exit = execute("message");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).contains("send");
    }

    @Test
    void streamWithoutAction_showsUsage() {
        int exit = execute("stream");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).contains("attach");
    }

    // --- global options ---

    @Test
    void customBaseUrlOverridesDefault() {
        server.enqueue(new MockResponse()
            .setBody("{\"sessionId\":\"s-custom\",\"createdAt\":\"2026-01-01T00:00:00Z\"}")
            .setHeader("Content-Type", "application/json"));

        var app = new AgentCliApplication();
        app.client = new HttpAgentApiClient(
            new CliProperties(server.url("").toString(), "X-API-Key", null, 5000, 5000));
        CommandLine cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));
        int exit = cmd.execute("session", "create");

        assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
        assertThat(out()).isEqualTo("s-custom");
    }
}
