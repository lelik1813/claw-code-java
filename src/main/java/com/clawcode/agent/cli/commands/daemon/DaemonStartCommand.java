package com.clawcode.agent.cli.commands.daemon;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.daemon.DaemonState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "start",
    description = "Start the claw-code-java as a background daemon.",
    mixinStandardHelpOptions = true)
public class DaemonStartCommand implements Callable<Integer> {

    @ParentCommand
    DaemonCommand parent;

    @Option(names = {"--port"}, description = "Port to bind (default: ${DEFAULT-VALUE})",
        defaultValue = "8080")
    int port;

    @Option(names = {"--jar"}, description = "Path to claw-code-java JAR")
    String jarPath;

    @Option(names = {"--readiness-timeout"},
        description = "Seconds to wait for /actuator/health before saving daemon state (default: ${DEFAULT-VALUE})",
        defaultValue = "30")
    long readinessTimeoutSeconds;

    @Override
    public Integer call() {
        var out = parent.out();
        var store = parent.store();
        var processProvider = parent.processProvider;

        // Check for existing daemon
        var existing = store.load();
        if (existing.isPresent()) {
            var state = existing.get();
            if (state.isRunning() && processProvider.isAlive(state.pid())) {
                out.printf("Daemon already running (pid=%d, port=%d)%n", state.pid(), state.port());
                return AgentCliApplication.EXIT_OK;
            }
            // Stale state — clean up
            out.printf("Clearing stale daemon state (pid=%d)%n", state.pid());
            store.clear();
        }

        // Resolve JAR path
        String jar = resolveJar();
        if (jar == null) {
            out.println("Error: cannot locate claw-code-java JAR. Use --jar to specify the path.");
            return AgentCliApplication.EXIT_API_ERROR;
        }

        try {
            var process = parent.processLauncher.start(jar, port);
            long pid = process.pid();

            boolean ready = parent.healthChecker.waitUntilReady(
                port, Duration.ofSeconds(Math.max(0, readinessTimeoutSeconds)));
            if (!ready) {
                process.destroyForcibly();
                out.printf("Error: daemon did not become ready at http://127.0.0.1:%d/actuator/health within %ds%n",
                    port, readinessTimeoutSeconds);
                return AgentCliApplication.EXIT_API_ERROR;
            }

            var state = new DaemonState(pid, port, System.currentTimeMillis(), DaemonState.STATUS_RUNNING);
            store.save(state);

            out.printf("Daemon started (pid=%d, port=%d, health=UP)%n", pid, port);
            return AgentCliApplication.EXIT_OK;
        } catch (IOException e) {
            out.println("Error starting daemon: " + e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }

    String resolveJar() {
        if (jarPath != null) {
            var p = Path.of(jarPath);
            if (p.toFile().exists()) return p.toAbsolutePath().toString();
            return null;
        }
        // Try common locations relative to project
        var dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            var candidate = dir.resolve("claw-code-java.jar");
            if (candidate.toFile().exists()) return candidate.toString();
            var target = dir.resolve("target").resolve("claw-code-java.jar");
            if (target.toFile().exists()) return target.toString();
            dir = dir.getParent();
        }
        return null;
    }
}
