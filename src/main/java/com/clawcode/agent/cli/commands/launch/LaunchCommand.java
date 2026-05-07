package com.clawcode.agent.cli.commands.launch;

import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.ReplRunner;
import com.clawcode.agent.cli.auth.AuthCredentials;
import com.clawcode.agent.cli.auth.FileAuthStore;
import com.clawcode.agent.cli.config.CliConfigStore;
import com.clawcode.agent.cli.config.ServerEnvStore;
import com.clawcode.agent.cli.daemon.DaemonHealthChecker;
import com.clawcode.agent.cli.daemon.DaemonLaunchRequest;
import com.clawcode.agent.cli.daemon.DaemonProcessLauncher;
import com.clawcode.agent.cli.daemon.DaemonState;
import com.clawcode.agent.cli.daemon.DaemonStateStore;
import com.clawcode.agent.cli.daemon.HttpDaemonHealthChecker;
import com.clawcode.agent.cli.daemon.ProcessHandleProvider;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "launch",
    description = "Start the local daemon and open the interactive REPL.",
    mixinStandardHelpOptions = true)
public class LaunchCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Option(names = {"--jar"}, description = "Path to claw-code-java JAR")
    String jarPath;

    @Option(names = {"--port"}, description = "Server port")
    Integer port;

    @Option(names = {"--setup"}, description = "Run setup prompts before launching")
    boolean setup;

    @Option(names = {"--no-setup"}, description = "Skip first-run setup prompts")
    boolean noSetup;

    @Option(names = {"--api-key"}, description = "Local server API key")
    String apiKey;

    @Option(names = {"--model-token"}, description = "Anthropic-compatible model API token")
    String modelToken;

    @Option(names = {"--persistence"}, description = "Persistence backend: in-memory or postgres")
    String persistenceBackend;

    public CliConfigStore configStore = new CliConfigStore();
    public FileAuthStore authStore = new FileAuthStore();
    public ServerEnvStore serverEnvStore = new ServerEnvStore();
    public DaemonStateStore daemonStore = new DaemonStateStore();
    public ProcessHandleProvider processProvider = new ProcessHandleProvider();
    public DaemonProcessLauncher processLauncher = DaemonProcessLauncher.system();
    public DaemonHealthChecker healthChecker = new HttpDaemonHealthChecker();

    @Override
    public Integer call() {
        var out = parent.out();
        var console = System.console();
        var storedConfig = configStore.listStored();
        var storedEnv = new LinkedHashMap<>(serverEnvStore.load());
        var storedAuth = authStore.load();

        boolean firstRun = storedConfig.isEmpty() && storedAuth.isEmpty() && storedEnv.isEmpty();
        boolean runSetup = setup || (firstRun && !noSetup);

        int resolvedPort = resolvePort(storedConfig);
        String resolvedBackend = resolveBackend(storedConfig);
        String resolvedJar = resolveJar(storedConfig);
        String resolvedApiKey = resolveApiKey(storedAuth.orElse(null));

        if (runSetup) {
            out.println("First run setup");
            resolvedPort = promptPort(console, resolvedPort);
            resolvedBackend = promptBackend(console, resolvedBackend);
            resolvedApiKey = promptApiKey(console, resolvedApiKey);
            modelToken = promptModelToken(console, modelToken);
            out.println();
        }

        if (resolvedJar == null) {
            out.println("Error: cannot locate claw-code-java JAR. Build it with './mvnw package -DskipTests -B' or pass --jar.");
            return AgentCliApplication.EXIT_API_ERROR;
        }

        persistSetup(resolvedPort, resolvedBackend, resolvedJar, resolvedApiKey, storedEnv);

        if (!ensureDaemon(resolvedJar, resolvedPort, storedEnv)) {
            return AgentCliApplication.EXIT_API_ERROR;
        }

        String baseUrl = "http://localhost:" + resolvedPort;
        parent.configureClient(baseUrl, "X-API-Key", resolvedApiKey);
        out.printf("Opening REPL at %s%n", baseUrl);
        return new ReplRunner(parent.out(), System.in, parent.client(), parent.registry()).run();
    }

    private int resolvePort(Map<String, String> storedConfig) {
        if (port != null) return port;
        return parsePort(storedConfig.getOrDefault("serverPort", "8080"), 8080);
    }

    private String resolveBackend(Map<String, String> storedConfig) {
        if (persistenceBackend != null && !persistenceBackend.isBlank()) {
            return normalizeBackend(persistenceBackend);
        }
        return normalizeBackend(storedConfig.getOrDefault("persistenceBackend", "in-memory"));
    }

    private String resolveJar(Map<String, String> storedConfig) {
        if (jarPath != null && !jarPath.isBlank()) {
            return existingJar(jarPath);
        }
        String storedJar = storedConfig.get("serverJar");
        if (storedJar != null && !"auto".equalsIgnoreCase(storedJar)) {
            String resolved = existingJar(storedJar);
            if (resolved != null) return resolved;
        }
        return findDefaultJar();
    }

    private String resolveApiKey(AuthCredentials credentials) {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.strip();
        if (credentials != null && credentials.apiKey() != null && !credentials.apiKey().isBlank()) {
            return credentials.apiKey();
        }
        return "ccj-" + UUID.randomUUID();
    }

    private void persistSetup(int port, String backend, String jar, String apiKey, Map<String, String> env) {
        configStore.set("baseUrl", "http://localhost:" + port);
        configStore.set("serverPort", Integer.toString(port));
        configStore.set("serverJar", jar);
        configStore.set("persistenceBackend", backend);
        authStore.save(new AuthCredentials(apiKey, "X-API-Key", Map.of(), Instant.now()));

        env.put("APP_SECURITY_API_KEY_ENABLED", "true");
        env.put("APP_SECURITY_API_KEY_KEY", apiKey);
        env.put("PERSISTENCE_BACKEND", backend);
        if (modelToken != null && !modelToken.isBlank()) {
            env.put("ANTHROPIC_AUTH_TOKEN", modelToken.strip());
        }
        serverEnvStore.save(env);
    }

    private boolean ensureDaemon(String jar, int port, Map<String, String> env) {
        var out = parent.out();
        var existing = daemonStore.load();
        if (existing.isPresent()) {
            var state = existing.get();
            if (state.isRunning() && processProvider.isAlive(state.pid())) {
                out.printf("Daemon already running (pid=%d, port=%d)%n", state.pid(), state.port());
                return true;
            }
            daemonStore.clear();
        }

        try {
            var process = processLauncher.start(new DaemonLaunchRequest(
                jar, port, env, appArgsFor(env)));
            boolean ready = healthChecker.waitUntilReady(port, Duration.ofSeconds(30));
            if (!ready) {
                process.destroyForcibly();
                out.printf("Error: daemon did not become ready at http://127.0.0.1:%d/actuator/health%n", port);
                return false;
            }
            daemonStore.save(new DaemonState(process.pid(), port,
                System.currentTimeMillis(), DaemonState.STATUS_RUNNING));
            out.printf("Daemon started (pid=%d, port=%d, health=UP)%n", process.pid(), port);
            return true;
        } catch (IOException e) {
            out.println("Error starting daemon: " + e.getMessage());
            return false;
        }
    }

    private int promptPort(Console console, int defaultPort) {
        String value = prompt(console, "Server port [" + defaultPort + "]: ");
        return value.isBlank() ? defaultPort : parsePort(value, defaultPort);
    }

    private String promptBackend(Console console, String defaultBackend) {
        String value = prompt(console, "Persistence backend (in-memory/postgres) [" + defaultBackend + "]: ");
        return value.isBlank() ? defaultBackend : normalizeBackend(value);
    }

    private String promptApiKey(Console console, String defaultApiKey) {
        String value = prompt(console, "Local API key [generated]: ");
        return value.isBlank() ? defaultApiKey : value.strip();
    }

    private String promptModelToken(Console console, String currentToken) {
        if (currentToken != null && !currentToken.isBlank()) return currentToken;
        if (console == null) return currentToken;
        char[] value = console.readPassword("Model API token (blank for noop model): ");
        return value == null ? currentToken : new String(value).strip();
    }

    private String prompt(Console console, String message) {
        if (console == null) return "";
        String value = console.readLine(message);
        return value == null ? "" : value.strip();
    }

    private static int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String normalizeBackend(String value) {
        String normalized = value == null ? "in-memory" : value.strip().toLowerCase();
        return "postgres".equals(normalized) ? "postgres" : "in-memory";
    }

    private static List<String> appArgsFor(Map<String, String> env) {
        String backend = normalizeBackend(env.get("PERSISTENCE_BACKEND"));
        if (!"in-memory".equals(backend)) {
            return List.of("--app.persistence.backend=" + backend);
        }
        return List.of(
            "--app.persistence.backend=in-memory",
            "--spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
                + "org.springframework.boot.r2dbc.autoconfigure.R2dbcDataAutoConfiguration,"
                + "org.springframework.boot.r2dbc.autoconfigure.R2dbcRepositoriesAutoConfiguration,"
                + "org.springframework.boot.r2dbc.autoconfigure.R2dbcTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        );
    }

    private static String existingJar(String raw) {
        Path path = Path.of(raw);
        return Files.isRegularFile(path) ? path.toAbsolutePath().toString() : null;
    }

    private static String findDefaultJar() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path exact = dir.resolve("claw-code-java.jar");
            if (Files.isRegularFile(exact)) return exact.toString();
            Path target = dir.resolve("target");
            if (Files.isDirectory(target)) {
                try (var stream = Files.list(target)) {
                    return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("claw-code-java-")
                                && name.endsWith(".jar")
                                && !name.endsWith(".jar.original");
                        })
                        .findFirst()
                        .map(Path::toString)
                        .orElse(null);
                } catch (IOException ignored) {}
            }
            dir = dir.getParent();
        }
        return null;
    }
}
