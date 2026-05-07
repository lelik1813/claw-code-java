package com.clawcode.agent.cli.commands.mcp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.mcp.McpServerConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "test",
    description = "Test connectivity to an MCP server.",
    mixinStandardHelpOptions = true)
public class McpTestCommand implements Callable<Integer> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    @CommandLine.ParentCommand
    McpCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Server name to test")
    String name;

    @Option(names = {"--timeout"}, description = "Probe timeout in seconds (default: ${DEFAULT-VALUE})",
        defaultValue = "" + DEFAULT_TIMEOUT_SECONDS)
    int timeoutSeconds;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            McpServerConfig.validateName(name);
            var config = parent.store().find(name);
            if (config.isEmpty()) {
                out.printf("Server '%s' not found in configuration.%n", name);
                return AgentCliApplication.EXIT_API_ERROR;
            }
            var c = config.get();
            String target = c.url() != null ? c.url() : c.command();
            out.printf("Testing connection to '%s' (%s at %s)...%n", c.name(), c.transport(), target);

            return switch (c.transport()) {
                case HTTP, SSE -> probeHttp(c, out);
                case STDIO -> probeStdio(c, out);
            };
        } catch (McpServerConfig.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
    }

    private int probeHttp(McpServerConfig c, java.io.PrintWriter out) {
        if (c.url() == null || c.url().isBlank()) {
            out.println("FAIL: no URL configured");
            return AgentCliApplication.EXIT_API_ERROR;
        }
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(c.url()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
            if (c.authToken() != null && !c.authToken().isBlank()) {
                builder.header("Authorization", "Bearer " + c.authToken());
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 500) {
                out.printf("OK (HTTP %d, %dms)%n", response.statusCode(), 0);
                return AgentCliApplication.EXIT_OK;
            }
            out.printf("FAIL: HTTP %d — %s%n", response.statusCode(), truncate(response.body(), 200));
            return AgentCliApplication.EXIT_API_ERROR;
        } catch (ConnectException e) {
            out.println("FAIL: connection refused — " + e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        } catch (IOException e) {
            out.println("FAIL: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("FAIL: interrupted");
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }

    private int probeStdio(McpServerConfig c, java.io.PrintWriter out) {
        if (c.command() == null || c.command().isBlank()) {
            out.println("FAIL: no command configured");
            return AgentCliApplication.EXIT_API_ERROR;
        }
        Process process = null;
        try {
            var parts = new ArrayList<String>();
            parts.add(c.command());
            if (!c.args().isEmpty()) {
                parts.addAll(c.args());
            }
            var pb = new ProcessBuilder(parts);
            if (c.env() != null && !c.env().isEmpty()) {
                pb.environment().putAll(c.env());
            }
            pb.redirectErrorStream(true);
            process = pb.start();
            var exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                out.println("FAIL: process timed out after " + timeoutSeconds + "s");
                return AgentCliApplication.EXIT_API_ERROR;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                out.printf("OK (exit=0)%n");
                return AgentCliApplication.EXIT_OK;
            }
            var output = truncate(process.inputReader().readLine(), 200);
            out.printf("FAIL: process exited with code %d%s%n", exitCode,
                output != null ? " — " + output : "");
            return AgentCliApplication.EXIT_API_ERROR;
        } catch (IOException e) {
            out.println("FAIL: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            out.println("FAIL: interrupted");
            return AgentCliApplication.EXIT_API_ERROR;
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
