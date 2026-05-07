package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileListTool implements Tool {

    private static final int MAX_ENTRIES = 500;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_list",
        "List direct children of a workspace directory only -- not recursive. "
            + "Use this for local navigation before reading individual files with file_read. "
            + "The path must be an existing directory. "
            + "Returns only immediate files and subdirectories with paths relative to the listed directory.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the directory, relative to the workspace root or absolute if allowed.")
            ),
            "required", List.of("path"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "file_list";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String pathStr = extractPath(input);
        return Mono.fromCallable(() -> WorkspacePathGuard.validate(pathStr))
            .flatMap(this::listPath);
    }

    private Mono<Object> listPath(Path path) {
        return Mono.fromCallable(() -> {
            if (Files.notExists(path)) {
                throw new NoSuchFileException(path.toString());
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException(
                    "Path is not a directory, use file_read instead: " + path);
            }

            try (var stream = Files.list(path)) {
                return stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .limit(MAX_ENTRIES)
                    .map(child -> Map.<String, Object>of(
                        "name", child.getFileName().toString(),
                        "path", path.relativize(child).toString(),
                        "type", Files.isDirectory(child) ? "directory" : "file"
                    ))
                    .toList();
            }
        });
    }

    private String extractPath(Object input) {
        if (input instanceof String s) return s;
        if (input instanceof Map<?, ?> m) {
            Object val = m.get("path");
            return val != null ? val.toString() : null;
        }
        return null;
    }
}
