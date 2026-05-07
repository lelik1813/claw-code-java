package com.clawcode.agent.tools.shell;

public record PowerShellResult(
    String command,
    int exitCode,
    String stdout,
    String stderr,
    boolean timedOut
) {

    public PowerShellResult {
        command = command == null ? "" : command;
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
