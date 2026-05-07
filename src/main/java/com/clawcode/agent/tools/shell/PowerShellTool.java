package com.clawcode.agent.tools.shell;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.ToolExecutionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class PowerShellTool implements Tool {

    private static final int MAX_OUTPUT_CHARS = 64_000;

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "Get-ChildItem", "Get-Content", "Get-Item",
        "ls", "dir", "cat", "type",
        "Test-Path", "Get-Location", "pwd",
        "Get-Process", "Get-Date", "Start-Sleep",
        "git", "mvn", "mvnw", "mvnw.cmd"
    );

    private final long timeoutSeconds;

    @Autowired
    public PowerShellTool(PowerShellToolProperties properties) {
        this(properties.timeoutSeconds());
    }

    PowerShellTool(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "powershell",
        "Execute terminal, build, git, and system commands in the workspace. "
            + "Only the base command must be in the allowlist; arguments are unrestricted. "
            + "Allowed base commands: Get-ChildItem, Get-Content, Get-Item, ls, dir, cat, type, "
            + "Test-Path, Get-Location, pwd, Get-Process, Get-Date, Start-Sleep, git, mvn, mvnw, mvnw.cmd. "
            + "Stdout and stderr are returned separately. Timeout is configurable via app.tools.powershell.timeout-seconds. "
            + "Interactive commands are denied. Destructive filesystem and git commands are denied unless explicitly requested by the user. "
            + "Do not use for file read/search/write -- use file_read, file_search, file_write instead. "
            + "Each output stream is truncated at 64K chars.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string",
                    "description", "PowerShell command to execute, e.g. 'Get-ChildItem src' or 'git status'.")
            ),
            "required", List.of("command"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "powershell";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String command = extractCommand(input);
        if (command == null || command.isBlank()) {
            return Mono.error(new IllegalArgumentException("command is required"));
        }

        try {
            PowerShellSafetyGuard.rejectUnsafeCommand(command, explicitDestructiveApproval(context));
        } catch (SecurityException e) {
            return Mono.error(e);
        }

        String baseCommand = PowerShellCommandParser.baseCommand(command);
        if (!ALLOWED_COMMANDS.contains(baseCommand)) {
            return Mono.error(new SecurityException(
                "Command not allowed: " + baseCommand
                + ". Allowed: " + ALLOWED_COMMANDS));
        }

        return Mono.<Object>fromCallable(() -> runCommand(command))
            .subscribeOn(Schedulers.boundedElastic());
    }

    PowerShellResult runCommand(String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder()
            .command("powershell", "-NoProfile", "-Command", command)
            .directory(Path.of(System.getProperty("user.dir")).toFile());

        Process process = pb.start();
        CompletableFuture<String> stdoutFuture = readStream(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStream(process.getErrorStream());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                "Command timed out after " + timeoutSeconds + "s: " + command);
        }

        String stdout = awaitStream(stdoutFuture);
        String stderr = awaitStream(stderrFuture);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(
                "Exit code " + exitCode + "\n" + labeledOutput(stdout, stderr));
        }

        return new PowerShellResult(command, exitCode, stdout, stderr, false);
    }

    private String extractCommand(Object input) {
        if (input instanceof String s) return s;
        if (input instanceof Map<?, ?> m) {
            Object val = m.get("command");
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private boolean explicitDestructiveApproval(Object context) {
        return context instanceof ToolExecutionContext ctx && ctx.explicitDestructiveApproval();
    }

    private CompletableFuture<String> readStream(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return truncate(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String awaitStream(CompletableFuture<String> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    private String truncate(String output) {
        if (output.length() > MAX_OUTPUT_CHARS) {
            return output.substring(0, MAX_OUTPUT_CHARS) + "\n... [truncated]";
        }
        return output;
    }

    private String labeledOutput(String stdout, String stderr) {
        return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
    }
}
