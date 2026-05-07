package com.clawcode.agent.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class StdioMcpClientTest {

    @TempDir
    Path tempDir;

    private McpProperties.McpServerDefinition stdioDef(String command, List<String> args, Map<String, String> env) {
        return new McpProperties.McpServerDefinition(
            true, McpTransportType.STDIO, null, "",
            command, args, env, tempDir.toString(), 10_000, null, 30_000, 300_000);
    }

    @Test
    void listResourcesWithMockMcpServer() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "test-server", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("test-server"))
                .assertNext(resource -> {
                    assertThat(resource.uri()).isEqualTo(URI.create("file:///data/test.txt"));
                    assertThat(resource.name()).isEqualTo("test.txt");
                    assertThat(resource.description()).isEqualTo("A test file");
                    assertThat(resource.mimeType()).isEqualTo("text/plain");
                })
                .verifyComplete();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void readResourceWithMockMcpServer() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "test-server", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.readResource("test-server", URI.create("file:///data/test.txt")))
                .assertNext(content -> {
                    assertThat(content.uri()).isEqualTo(URI.create("file:///data/test.txt"));
                    assertThat(content.mimeType()).isEqualTo("text/plain");
                    assertThat(content.text()).isEqualTo("Hello from MCP stdio");
                })
                .verifyComplete();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void unknownServerReturnsError() {
        var properties = new McpProperties(true, Map.of());
        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("no-such-server"))
                .expectErrorMatches(e -> e instanceof McpServerNotFoundException)
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void disabledServerReturnsError() {
        var properties = new McpProperties(true, Map.of(
            "off", new McpProperties.McpServerDefinition(false, McpTransportType.STDIO,
                null, "", "cmd", null, null, null, 10_000, null, 30_000, 300_000)
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("off"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("server is disabled"))
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void missingCommandReturnsError() {
        var properties = new McpProperties(true, Map.of(
            "no-cmd", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", null, null, null, null, 10_000, null, 30_000, 300_000)
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("no-cmd"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("non-blank 'command'"))
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void envVarsPassedToProcess() throws IOException {
        Path script = createEchoEnvServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "env-test", stdioDef(command, args, Map.of("MCP_TEST_VAR", "hello-world"))
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.readResource("env-test", URI.create("file:///env")))
                .assertNext(content ->
                    assertThat(content.text()).isEqualTo("hello-world"))
                .verifyComplete();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void shutdownCleansUp() throws IOException {
        Path script = createLongRunningServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "long", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        client.shutdown();
        // No exception means cleanup succeeded
    }

    @Test
    void startupTimeout_failsWhenProcessNeverResponds() throws IOException {
        Path dir = Files.createTempDirectory("mcp-slow");
        try {
            Path script = createSilentServer(dir);
            String command = isWindows() ? "cmd" : "sh";
            List<String> args = isWindows()
                ? List.of("/c", script.toString())
                : List.of(script.toString());

            var properties = new McpProperties(true, Map.of(
                "slow", new McpProperties.McpServerDefinition(
                    true, McpTransportType.STDIO, null, "",
                    command, args, Map.of(), dir.toString(), 1_000, null, 30_000, 300_000)
            ));

            StdioMcpClient client = new StdioMcpClient(properties);
            try {
                StepVerifier.create(client.listResources("slow"))
                    .expectErrorMatches(e -> e.getMessage() != null
                        && (e.getMessage().contains("timed out") || e.getMessage().contains("timeout")))
                    .verify(java.time.Duration.ofSeconds(10));
            } finally {
                client.shutdown();
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void brokenJsonResponse_mapsToRemoteException() throws IOException {
        Path dir = Files.createTempDirectory("mcp-broken");
        try {
            Path script = createBrokenJsonServer(dir);
            String command = isWindows() ? "cmd" : "sh";
            List<String> args = isWindows()
                ? List.of("/c", script.toString())
                : List.of(script.toString());

            var properties = new McpProperties(true, Map.of(
                "broken", new McpProperties.McpServerDefinition(
                    true, McpTransportType.STDIO, null, "",
                    command, args, Map.of(), dir.toString(), 10_000, null, 30_000, 300_000)
            ));

            StdioMcpClient client = new StdioMcpClient(properties);
            try {
                StepVerifier.create(client.listResources("broken"))
                    .expectError()
                    .verify();
            } finally {
                client.shutdown();
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void brokenJsonOnInitialize_failsFast() throws IOException {
        Path dir = Files.createTempDirectory("mcp-badinit");
        try {
            Path script = createBrokenInitServer(dir);
            String command = isWindows() ? "cmd" : "sh";
            List<String> args = isWindows()
                ? List.of("/c", script.toString())
                : List.of(script.toString());

            var properties = new McpProperties(true, Map.of(
                "bad-init", stdioDef(command, args, Map.of())
            ));

            StdioMcpClient client = new StdioMcpClient(properties);
            try {
                StepVerifier.create(client.listResources("bad-init"))
                    .expectError()
                    .verify();
            } finally {
                client.shutdown();
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void processExitsImmediately_mapsToError() throws IOException {
        Path script = createImmediateExitServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "exit", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("exit"))
                .expectError()
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void jsonRpcErrorResponse_mapsToError() throws IOException {
        Path script = createErrorServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "error-srv", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("error-srv"))
                .expectErrorMatches(e -> e.getMessage() != null
                    && e.getMessage().contains("JSON-RPC error"))
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void emptyResources_returnsEmptyFlux() throws IOException {
        Path script = createEmptyResourcesServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "empty", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("empty"))
                .verifyComplete();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void doubleShutdown_isIdempotent() throws IOException {
        Path script = createLongRunningServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "long", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        client.shutdown();
        client.shutdown();
        // No exception on double shutdown
    }

    @Test
    void multipleRequestsReuseSameConnection() throws IOException {
        Path script = createMockMcpServer(tempDir);
        String command = isWindows() ? "cmd" : "sh";
        List<String> args = isWindows()
            ? List.of("/c", script.toString())
            : List.of(script.toString());

        var properties = new McpProperties(true, Map.of(
            "multi", stdioDef(command, args, Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            // First request
            StepVerifier.create(client.listResources("multi"))
                .expectNextCount(1)
                .verifyComplete();

            // Second request reuses the same connection
            StepVerifier.create(client.readResource("multi", URI.create("file:///data/test.txt")))
                .assertNext(c -> assertThat(c.text()).isEqualTo("Hello from MCP stdio"))
                .verifyComplete();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void nonExistentCommand_mapsToError() {
        var properties = new McpProperties(true, Map.of(
            "bad-cmd", stdioDef("nonexistent_command_xyz_123", List.of(), Map.of())
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("bad-cmd"))
                .expectError()
                .verify();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void blankCommand_returnsConfigError() {
        var properties = new McpProperties(true, Map.of(
            "blank", new McpProperties.McpServerDefinition(true, McpTransportType.STDIO,
                null, "", "  ", null, null, null, 10_000, null, 30_000, 300_000)
        ));

        StdioMcpClient client = new StdioMcpClient(properties);
        try {
            StepVerifier.create(client.listResources("blank"))
                .expectErrorMatches(e -> e instanceof McpRemoteException mre
                    && mre.getMessage().contains("non-blank 'command'"))
                .verify();
        } finally {
            client.shutdown();
        }
    }

    // --- Additional fixture scripts ---

    private Path createSilentServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("silent_mcp.bat");
            Files.writeString(script, """
                @echo off
                ping -n 60 127.0.0.1 > NUL
                """);
            return script;
        }
        Path script = dir.resolve("silent_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            sleep 60
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createBrokenJsonServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("broken_mcp.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo {this is not valid json!!!
                set /p LINE=
                echo {"jsonrpc":"2.0","id":2,"result":{"resources":[]}}
                """);
            return script;
        }
        Path script = dir.resolve("broken_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{this is not valid json!!!'
            read LINE
            echo '{"jsonrpc":"2.0","id":2,"result":{"resources":[]}}'
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createBrokenInitServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("broken_init.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo NOT_JSON_AT_ALL
                """);
            return script;
        }
        Path script = dir.resolve("broken_init.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo 'NOT_JSON_AT_ALL'
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createImmediateExitServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("exit_mcp.bat");
            Files.writeString(script, """
                @echo off
                exit /b 1
                """);
            return script;
        }
        Path script = dir.resolve("exit_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            exit 1
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createErrorServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("error_mcp.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"err","version":"1.0"}}}
                set /p LINE=
                set /p LINE=
                echo {"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"Internal error"}}
                """);
            return script;
        }
        Path script = dir.resolve("error_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"err","version":"1.0"}}}'
            read LINE
            read LINE
            echo '{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"Internal error"}}'
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createEmptyResourcesServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("empty_mcp.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"empty","version":"1.0"}}}
                set /p LINE=
                set /p LINE=
                echo {"jsonrpc":"2.0","id":2,"result":{"resources":[]}}
                """);
            return script;
        }
        Path script = dir.resolve("empty_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"empty","version":"1.0"}}}'
            read LINE
            read LINE
            echo '{"jsonrpc":"2.0","id":2,"result":{"resources":[]}}'
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    // --- Helper: create a mock MCP stdio server script ---

    private Path createMockMcpServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("mock_mcp.bat");
            Files.writeString(script, """
                @echo off
                setlocal enabledelayedexpansion
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}
                :loop
                set /p LINE=
                if "!LINE!"=="" exit /b 0
                echo !LINE!| findstr /C:"notifications/" > NUL 2>&1
                if !ERRORLEVEL!==0 goto loop
                echo !LINE!| findstr /C:"resources/list" > NUL 2>&1
                if !ERRORLEVEL!==0 (
                    echo {"jsonrpc":"2.0","id":2,"result":{"resources":[{"uri":"file:///data/test.txt","name":"test.txt","description":"A test file","mimeType":"text/plain"}]}}
                    goto loop
                )
                echo !LINE!| findstr /C:"resources/read" > NUL 2>&1
                if !ERRORLEVEL!==0 (
                    echo {"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///data/test.txt","mimeType":"text/plain","text":"Hello from MCP stdio"}]}}
                    goto loop
                )
                echo {"jsonrpc":"2.0","id":99,"result":{}}
                goto loop
                """);
            return script;
        }
        Path script = dir.resolve("mock_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}}'
            while read LINE; do
              case "$LINE" in
                *"notifications/"*)
                  ;;
                *resources/list*)
                  echo '{"jsonrpc":"2.0","id":2,"result":{"resources":[{"uri":"file:///data/test.txt","name":"test.txt","description":"A test file","mimeType":"text/plain"}]}}'
                  ;;
                *resources/read*)
                  echo '{"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///data/test.txt","mimeType":"text/plain","text":"Hello from MCP stdio"}]}}'
                  ;;
                *)
                  echo '{"jsonrpc":"2.0","id":99,"result":{}}'
                  ;;
              esac
            done
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createEchoEnvServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("env_mcp.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"env-mock","version":"1.0"}}}
                set /p LINE=
                set /p LINE=
                echo {"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///env","mimeType":"text/plain","text":"%MCP_TEST_VAR%"}]}}
                """);
            return script;
        }
        Path script = dir.resolve("env_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"env-mock","version":"1.0"}}}'
            read LINE
            read LINE
            echo '{"jsonrpc":"2.0","id":3,"result":{"contents":[{"uri":"file:///env","mimeType":"text/plain","text":"'$MCP_TEST_VAR'"}]}}'
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path createLongRunningServer(Path dir) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve("long_mcp.bat");
            Files.writeString(script, """
                @echo off
                set /p LINE=
                echo {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"long","version":"1.0"}}}
                :loop
                ping -n 60 127.0.0.1 > NUL
                goto loop
                """);
            return script;
        }
        Path script = dir.resolve("long_mcp.sh");
        Files.writeString(script, """
            #!/bin/sh
            read LINE
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"long","version":"1.0"}}}'
            while true; do sleep 60; done
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
