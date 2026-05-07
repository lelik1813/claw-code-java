package com.clawcode.agent.core.prompt;

import com.clawcode.agent.tools.ToolDefinition;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record RuntimePromptContext(
    Instant now,
    Path cwd,
    List<Path> allowedRoots,
    List<ToolDefinition> advertisedTools
) {

    public RuntimePromptContext {
        allowedRoots = List.copyOf(allowedRoots);
        advertisedTools = List.copyOf(advertisedTools);
    }

    public boolean hasTool(String name) {
        return advertisedTools.stream()
            .anyMatch(t -> t.name().equals(name));
    }

    public boolean hasFileWrite() {
        return hasTool("file_write");
    }

    public boolean hasFileEdit() {
        return hasTool("file_edit");
    }

    public boolean hasPowerShell() {
        return hasTool("powershell");
    }
}
