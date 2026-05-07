package com.clawcode.agent.tools.shell;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PowerShellSafetyGuardTest {

    @Test
    void deniesInteractiveShells() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("powershell"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Interactive command denied: powershell");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("pwsh"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("cmd"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("bash"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("sh"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void deniesNetworkInteractiveCommands() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("ssh user@example.test"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("ftp example.test"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("telnet example.test"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void deniesPowerShellInteractivePromptsInLaterSegments() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("Get-Date ; Read-Host"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Read-Host");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("Get-Date | pause"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("pause");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("Enter-PSSession server"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Enter-PSSession");
    }

    @Test
    void deniesPythonAndNodeWithoutNonInteractiveArgs() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("python"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectInteractive("node -i"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void allowsKnownNonInteractiveCommands() {
        assertThatCode(() -> PowerShellSafetyGuard.rejectInteractive("Get-ChildItem src"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectInteractive("git status"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectInteractive(".\\mvnw.cmd --version"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectInteractive("python -c \"print(1)\""))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectInteractive("node -e \"console.log(1)\""))
            .doesNotThrowAnyException();
    }

    @Test
    void deniesRecursiveDelete() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Remove-Item -Recurse -Force src"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: Remove-Item")
            .hasMessageContaining("Explicit user request is required")
            .hasMessageContaining("Do not retry");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("rm -r build"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: rm");
    }

    @Test
    void deniesWildcardDelete() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("del *.tmp"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: del");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("erase logs\\*"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: erase");
    }

    @Test
    void deniesRootDelete() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Remove-Item C:\\ -Recurse -Force"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: Remove-Item");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("rmdir /s C:\\"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: rmdir");
    }

    @Test
    void deniesCmdDeleteForms() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("cmd /c del *.tmp"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: cmd");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("cmd /c rd /s build"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: cmd");
    }

    @Test
    void deniesBroadMoveItem() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Move-Item * archive"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: Move-Item");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Move-Item C:\\ backup"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive filesystem command denied: Move-Item");
    }

    @Test
    void allowsSafeReadCommands() {
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Get-Content pom.xml"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Get-ChildItem src"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Test-Path src"))
            .doesNotThrowAnyException();
    }

    @Test
    void deniesDestructiveGitCommands() {
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git reset --hard"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied")
            .hasMessageContaining("Explicit user request is required")
            .hasMessageContaining("Do not retry");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git clean -fdx"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git checkout -- src/main.java"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git restore ."))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git branch -D old-branch"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git push --force origin main"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git rebase main"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git gc --prune=now"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Destructive git command denied");
    }

    @Test
    void allowsReadOnlyGitCommands() {
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git status"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git diff --check"))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git log --oneline -5"))
            .doesNotThrowAnyException();
    }

    @Test
    void explicitApprovalAllowsDestructiveGuardsOnly() {
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("git reset --hard", true))
            .doesNotThrowAnyException();
        assertThatCode(() -> PowerShellSafetyGuard.rejectUnsafeCommand("Remove-Item -Recurse build", true))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PowerShellSafetyGuard.rejectUnsafeCommand("powershell", true))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Interactive command denied");
    }
}
