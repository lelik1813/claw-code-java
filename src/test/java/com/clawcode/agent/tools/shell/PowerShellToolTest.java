package com.clawcode.agent.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawcode.agent.tools.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class PowerShellToolTest {

    @Test
    void definitionDescribesCommandExecutionNotFileAccess() {
        var desc = new PowerShellTool(30).definition().description();
        assertThat(desc)
            .contains("build")
            .contains("git")
            .contains("system commands")
            .contains("Stdout and stderr are returned separately")
            .contains("app.tools.powershell.timeout-seconds")
            .contains("Interactive commands are denied")
            .contains("Destructive filesystem and git commands are denied")
            .contains("explicitly requested by the user")
            .containsIgnoringCase("do not use for file read/search/write");
    }

    @Test
    void allowedCommandExecutesSuccessfully() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(Map.of("command", "Get-Date"), null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                PowerShellResult shellResult = (PowerShellResult) result;
                assertThat(shellResult.command()).isEqualTo("Get-Date");
                assertThat(shellResult.exitCode()).isZero();
                assertThat(shellResult.stdout()).isNotEmpty();
                assertThat(shellResult.stderr()).isEmpty();
                assertThat(shellResult.timedOut()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void allowedCommandWithArgs() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("Get-Location", null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                PowerShellResult shellResult = (PowerShellResult) result;
                assertThat(shellResult.exitCode()).isZero();
                assertThat(shellResult.stdout()).isNotEmpty();
                assertThat(shellResult.stderr()).isEmpty();
                assertThat(shellResult.timedOut()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void stderrPreservedSeparately() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("Get-Date ; [Console]::Error.WriteLine('ps-stderr-marker')", null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                PowerShellResult shellResult = (PowerShellResult) result;
                assertThat(shellResult.exitCode()).isZero();
                assertThat(shellResult.stdout()).isNotEmpty()
                    .doesNotContain("ps-stderr-marker");
                assertThat(shellResult.stderr()).contains("ps-stderr-marker");
                assertThat(shellResult.timedOut()).isFalse();
            })
            .verifyComplete();
    }

    @Test
    void resultNormalizesNullStrings() {
        PowerShellResult result = new PowerShellResult(null, 0, null, null, false);

        assertThat(result.command()).isEmpty();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void deniedCommandRejectedByAllowlist() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(Map.of("command", "New-Item -Path x"), null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Command not allowed: New-Item"))
            .verify();
    }

    @Test
    void deniedCommandInvokeExpression() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("Invoke-WebRequest http://evil.com", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Command not allowed: Invoke-WebRequest"))
            .verify();
    }

    @Test
    void deniedUnknownPathUsesParsedBaseCommand() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(".\\unknown-tool.cmd --version", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Command not allowed: unknown-tool.cmd")
                && e.getMessage().contains("mvnw.cmd"))
            .verify();
    }

    @Test
    void disallowedInteractiveCommandsRejectedWithoutExecution() {
        AtomicInteger executions = new AtomicInteger();
        PowerShellTool tool = new PowerShellTool(30) {
            @Override
            PowerShellResult runCommand(String command) throws IOException, InterruptedException {
                executions.incrementAndGet();
                return super.runCommand(command);
            }
        };

        StepVerifier.create(tool.execute("powershell", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Interactive command denied: powershell"))
            .verify();

        assertThat(executions).hasValue(0);
    }

    @Test
    void destructiveFilesystemCommandsDenied() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("Remove-Item -Recurse -Force src", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Destructive filesystem command denied: Remove-Item")
                && e.getMessage().contains("Explicit user request is required")
                && e.getMessage().contains("Do not retry"))
            .verify();
    }

    @Test
    void destructiveGitCommandsDenied() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("git reset --hard", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Destructive git command denied")
                && e.getMessage().contains("Explicit user request is required")
                && e.getMessage().contains("Do not retry"))
            .verify();
    }

    @Test
    void defaultContextDeniesDestructiveGitCommands() {
        PowerShellTool tool = new PowerShellTool(30);
        ToolExecutionContext context = new ToolExecutionContext("t", "s", "m", "system");

        StepVerifier.create(tool.execute("git reset --hard", context))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Destructive git command denied"))
            .verify();
    }

    @Test
    void explicitApprovalAllowsOtherwiseAllowlistedDestructiveCommand() {
        AtomicInteger executions = new AtomicInteger();
        PowerShellTool tool = new PowerShellTool(30) {
            @Override
            PowerShellResult runCommand(String command) {
                executions.incrementAndGet();
                return new PowerShellResult(command, 0, "approved", "", false);
            }
        };
        ToolExecutionContext context = new ToolExecutionContext("t", "s", "m", "system", true);

        StepVerifier.create(tool.execute("git reset --hard", context))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                assertThat(((PowerShellResult) result).stdout()).isEqualTo("approved");
            })
            .verifyComplete();

        assertThat(executions).hasValue(1);
    }

    @Test
    void explicitApprovalStillRequiresAllowlistedBaseCommand() {
        PowerShellTool tool = new PowerShellTool(30);
        ToolExecutionContext context = new ToolExecutionContext("t", "s", "m", "system", true);

        StepVerifier.create(tool.execute("Remove-Item x", context))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Command not allowed: Remove-Item"))
            .verify();
    }

    @Test
    void readOnlyGitCommandsAllowed() {
        AtomicInteger executions = new AtomicInteger();
        PowerShellTool tool = new PowerShellTool(30) {
            @Override
            PowerShellResult runCommand(String command) {
                executions.incrementAndGet();
                return new PowerShellResult(command, 0, "ok", "", false);
            }
        };

        StepVerifier.create(tool.execute("git status", null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                assertThat(((PowerShellResult) result).command()).isEqualTo("git status");
            })
            .verifyComplete();
        StepVerifier.create(tool.execute("git diff --check", null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                assertThat(((PowerShellResult) result).command()).isEqualTo("git diff --check");
            })
            .verifyComplete();

        assertThat(executions).hasValue(2);
    }

    @Test
    void phase11ShellSafetyParitySurface() {
        AtomicInteger executions = new AtomicInteger();
        PowerShellTool tool = new PowerShellTool(30) {
            @Override
            PowerShellResult runCommand(String command) {
                executions.incrementAndGet();
                return new PowerShellResult(command, 0, "ok", "", false);
            }
        };

        StepVerifier.create(tool.execute("& .\\mvnw.cmd --version", null))
            .assertNext(result -> {
                assertThat(result).isInstanceOf(PowerShellResult.class);
                assertThat(((PowerShellResult) result).command()).isEqualTo("& .\\mvnw.cmd --version");
            })
            .verifyComplete();
        StepVerifier.create(tool.execute("git status", null))
            .expectNextCount(1)
            .verifyComplete();

        StepVerifier.create(tool.execute("powershell", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Interactive command denied: powershell"))
            .verify();
        StepVerifier.create(tool.execute("git reset --hard", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Destructive git command denied"))
            .verify();
        StepVerifier.create(tool.execute("Remove-Item -Recurse -Force src", null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Destructive filesystem command denied: Remove-Item"))
            .verify();

        assertThat(executions).hasValue(2);
    }

    @Test
    void emptyCommandRejected() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(Map.of("command", ""), null))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("command is required"))
            .verify();
    }

    @Test
    void nullCommandRejected() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(Map.of(), null))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("command is required"))
            .verify();
    }

    @Test
    void timeoutKillsProcess() {
        PowerShellTool tool = new PowerShellTool(1);

        StepVerifier.create(tool.execute("Start-Sleep -Seconds 60", null))
            .expectErrorMatches(e ->
                e instanceof IllegalStateException
                && e.getMessage().contains("timed out"))
            .verify();
    }

    @Test
    void nonZeroExitIncludesSeparatedStdoutAndStderr() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(
                "Get-Date ; Write-Output 'ps-stdout-marker' ; "
                    + "[Console]::Error.WriteLine('ps-stderr-marker') ; exit 7",
                null))
            .expectErrorMatches(e ->
                e instanceof IllegalStateException
                && e.getMessage().contains("Exit code 7")
                && e.getMessage().contains("stdout:")
                && e.getMessage().contains("ps-stdout-marker")
                && e.getMessage().contains("stderr:")
                && e.getMessage().contains("ps-stderr-marker"))
            .verify();
    }

    @Test
    void toolNameIsPowershell() {
        assertThat(new PowerShellTool(30).name()).isEqualTo("powershell");
    }

    @Test
    void dotSlashPrefixExtractsBaseCommand() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute("./mvnw --version", null))
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    void dotBackslashMvnwCmdPathAllowed() {
        PowerShellTool tool = new PowerShellTool(30);

        StepVerifier.create(tool.execute(".\\mvnw.cmd --version", null))
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    void quotedMvnwCmdPathAllowed() {
        PowerShellTool tool = new PowerShellTool(30);
        String mvnw = Path.of("mvnw.cmd").toAbsolutePath().toString();

        StepVerifier.create(tool.execute("& \"" + mvnw + "\" --version", null))
            .expectNextCount(1)
            .verifyComplete();
    }
}
