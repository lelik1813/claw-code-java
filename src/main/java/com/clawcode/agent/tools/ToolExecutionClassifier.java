package com.clawcode.agent.tools;

import java.util.Set;

/**
 * Classifies tools by execution safety for batching decisions.
 *
 * <p>Read-only tools (file_read, file_list, file_glob, file_search) can execute
 * concurrently. All other tools, including writes (file_write, file_edit) and
 * shell (powershell), require serial execution. Unknown tools default to serial
 * so that denial/error paths never execute in parallel with write or shell tools.
 */
public final class ToolExecutionClassifier {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
        "file_read",
        "file_list",
        "file_glob",
        "file_search"
    );

    private static final Set<String> SERIAL_TOOLS = Set.of(
        "file_write",
        "file_edit",
        "powershell"
    );

    public static boolean isReadOnly(String toolName) {
        return toolName != null && READ_ONLY_TOOLS.contains(toolName);
    }

    public static boolean requiresSerialExecution(String toolName) {
        return !isReadOnly(toolName);
    }

    private ToolExecutionClassifier() {
    }
}
