package com.clawcode.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class StdioMcpClient implements McpClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ProcessConnection> connections = new ConcurrentHashMap<>();

    StdioMcpClient(McpProperties properties) {
        this.properties = properties;
    }

    @Override
    public Flux<McpResource> listResources(String serverName) {
        return Mono.fromCallable(() -> getConnection(serverName))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(conn -> Mono.fromCallable(() ->
                    conn.sendRequest("resources/list", JsonNodeFactory.instance.objectNode()))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMapMany(result -> parseResourceList(serverName, result))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e));
    }

    @Override
    public Mono<McpResourceContent> readResource(String serverName, URI uri) {
        return Mono.fromCallable(() -> getConnection(serverName))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(conn -> {
                var params = mapper.createObjectNode();
                params.put("uri", uri.toString());
                return Mono.fromCallable(() -> conn.sendRequest("resources/read", params))
                    .subscribeOn(Schedulers.boundedElastic());
            })
            .map(result -> parseResourceContent(serverName, result, uri))
            .onErrorMap(IOException.class, e ->
                new McpRemoteException(serverName, "I/O error: " + e.getMessage(), e))
            .onErrorMap(TimeoutException.class, e ->
                new McpRemoteException(serverName, "request timed out", e));
    }

    private ProcessConnection getConnection(String serverName) throws IOException {
        McpProperties.McpServerDefinition def = resolveServer(serverName);
        return connections.computeIfAbsent(serverName, name -> {
            try {
                return new ProcessConnection(name, def);
            } catch (IOException e) {
                throw new RuntimeException("Failed to start MCP stdio process for '" + name + "'", e);
            } catch (TimeoutException e) {
                throw new RuntimeException("MCP stdio process for '" + name + "' timed out during initialization", e);
            }
        });
    }

    private McpProperties.McpServerDefinition resolveServer(String serverName) {
        McpProperties.McpServerDefinition def = properties.servers().get(serverName);
        if (def == null) {
            throw new McpServerNotFoundException(serverName);
        }
        if (!def.enabled()) {
            throw new McpRemoteException(serverName, "server is disabled", null);
        }
        if (def.command() == null || def.command().isBlank()) {
            throw new McpRemoteException(serverName,
                "STDIO server requires non-blank 'command' config", null);
        }
        return def;
    }

    private Flux<McpResource> parseResourceList(String serverName, JsonNode result) {
        JsonNode resources = result.path("resources");
        if (!resources.isArray()) {
            return Flux.empty();
        }
        return Flux.fromIterable(() -> resources.elements())
            .map(node -> new McpResource(
                parseUri(node.path("uri").asText(""), serverName),
                node.path("name").asText(""),
                node.path("description").asText(""),
                node.path("mimeType").asText(null)));
    }

    private McpResourceContent parseResourceContent(String serverName, JsonNode result, URI uri) {
        JsonNode contents = result.path("contents");
        JsonNode content = contents.isArray() ? contents.get(0) : result.path("content");
        return new McpResourceContent(
            parseUri(content.path("uri").asText(uri.toString()), serverName),
            content.path("mimeType").asText("text/plain"),
            content.path("text").asText(""));
    }

    private URI parseUri(String raw, String serverName) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new McpRemoteException(serverName, "invalid URI in response: " + raw, e);
        }
    }

    void shutdown() {
        connections.values().forEach(ProcessConnection::close);
        connections.clear();
    }

    class ProcessConnection {

        private final String serverName;
        private final Process process;
        private final Writer writer;
        private final BufferedReader reader;
        private final AtomicInteger nextId = new AtomicInteger(0);
        private final ReentrantLock lock = new ReentrantLock();

        ProcessConnection(String serverName, McpProperties.McpServerDefinition def) throws IOException, TimeoutException {
            this.serverName = serverName;

            List<String> command = buildCommand(def);
            ProcessBuilder pb = new ProcessBuilder(command);
            def.env().forEach(pb.environment()::put);
            if (def.workingDir() != null && !def.workingDir().isBlank()) {
                pb.directory(Path.of(def.workingDir()).toFile());
            }
            pb.redirectErrorStream(false);

            this.process = pb.start();
            this.writer = new OutputStreamWriter(process.getOutputStream());
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            initialize(def.startupTimeoutMs());
        }

        private List<String> buildCommand(McpProperties.McpServerDefinition def) {
            var cmd = new java.util.ArrayList<String>();
            cmd.add(def.command());
            cmd.addAll(def.args());
            return cmd;
        }

        private void initialize(long startupTimeoutMs) throws IOException, TimeoutException {
            var params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            var clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "claw-code-java");
            clientInfo.put("version", "1.0.0");

            JsonNode result = sendRequest("initialize", params, startupTimeoutMs);

            var notification = mapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "notifications/initialized");
            writeLine(mapper.writeValueAsString(notification));
        }

        JsonNode sendRequest(String method, JsonNode params) throws IOException, TimeoutException {
            return sendRequest(method, params, REQUEST_TIMEOUT.toMillis());
        }

        JsonNode sendRequest(String method, JsonNode params, long timeoutMs) throws IOException, TimeoutException {
            lock.lock();
            try {
                int id = nextId.incrementAndGet();
                var request = mapper.createObjectNode();
                request.put("jsonrpc", "2.0");
                request.put("id", id);
                request.put("method", method);
                request.set("params", params);

                writeLine(mapper.writeValueAsString(request));

                String responseLine = readLineWithTimeout(timeoutMs);
                if (responseLine == null) {
                    throw new IOException("Process terminated unexpectedly");
                }

                JsonNode response = mapper.readTree(responseLine);

                JsonNode error = response.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    int code = error.path("code").asInt(-1);
                    String msg = error.path("message").asText("unknown error");
                    throw new IOException("JSON-RPC error " + code + ": " + msg);
                }

                return response.path("result");
            } finally {
                lock.unlock();
            }
        }

        private String readLineWithTimeout(long timeoutMs) throws IOException, TimeoutException {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Failed to read from process", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading from process", e);
            }
        }

        private void writeLine(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }

        void close() {
            try { writer.close(); } catch (IOException ignored) {}
            try { reader.close(); } catch (IOException ignored) {}
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
