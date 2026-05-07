package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileReadTool implements Tool {

    private static final long MAX_SIZE = 1_048_576; // 1 MB

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_read",
        "Read the full contents of a single UTF-8 text file from the workspace. "
            + "This tool is for read-only, targeted file reads only -- not directories, "
            + "not tree navigation. For directory listing use file_list; for pattern-based file "
            + "discovery use file_glob; for content search use file_search. "
            + "The file must exist and be no larger than 1 MB. "
            + "Returns the file content as a string.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the file, relative to the workspace root. "
                        + "Targeted read-only file access - not for directories or tree browsing.")
            ),
            "required", List.of("path"),
            "additionalProperties", false
        )
    );

    private final FileReadStateStore readStateStore;

    public FileReadTool(FileReadStateStore readStateStore) {
        this.readStateStore = readStateStore;
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        String pathStr = extractPath(input);
        return Mono.fromCallable(() -> WorkspacePathGuard.validate(pathStr))
            .flatMap(path -> readPath(path)
                .doOnSuccess(content -> recordSnapshot(path, context)))
            .onErrorMap(MalformedInputException.class,
                e -> new IllegalStateException("Cannot read file as UTF-8", e));
    }

    private void recordSnapshot(Path path, Object context) {
        if (context instanceof ToolExecutionContext ctx) {
            try {
                readStateStore.recordRead(ctx.sessionId(), path);
            } catch (java.io.IOException e) {
                // snapshot recording is non-critical; do not fail the read
            }
        }
    }

    private Mono<Object> readPath(Path path) {
        return Mono.fromCallable(() -> {
            if (Files.notExists(path)) {
                throw new NoSuchFileException(path.toString());
            }
            if (Files.isDirectory(path)) {
                throw new IllegalArgumentException(
                    "Path is a directory, use file_list instead: " + path);
            }
            if (Files.size(path) > MAX_SIZE) {
                throw new IllegalStateException(
                    "File too large: " + Files.size(path) + " bytes (max " + MAX_SIZE + ")");
            }
            return (Object) Files.readString(path);
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
