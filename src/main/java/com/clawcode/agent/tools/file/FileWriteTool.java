package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileWriteTool implements Tool {

    private static final long MAX_CONTENT_SIZE = 1_048_576; // 1 MB

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_write",
        "Write UTF-8 text content to a file in the workspace (full overwrite). "
            + "Before writing to an existing file, use file_read first to review current content. "
            + "Do not create docs unless explicitly requested by the user. "
            + "Creates parent directories if they do not exist. "
            + "Content must not exceed 1 MB.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to the file, relative to the workspace root."),
                "content", Map.of("type", "string",
                    "description", "Complete text content to write. The entire file is replaced.")
            ),
            "required", List.of("path", "content"),
            "additionalProperties", false
        )
    );

    private final FileReadStateStore readStateStore;

    public FileWriteTool(FileReadStateStore readStateStore) {
        this.readStateStore = readStateStore;
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        Map<String, String> args = extractArgs(input);
        String pathStr = args.get("path");
        String content = args.get("content");

        if (content == null) {
            return Mono.error(new IllegalArgumentException("content is required"));
        }
        if (content.length() > MAX_CONTENT_SIZE) {
            return Mono.error(new IllegalStateException(
                "Content too large: " + content.length() + " chars (max " + MAX_CONTENT_SIZE + ")"));
        }

        return Mono.fromCallable(() -> WorkspacePathGuard.validate(pathStr))
            .flatMap(path -> {
                if (!Files.exists(path)) {
                    return writeFile(path, content, false);
                }
                String sessionId = context instanceof ToolExecutionContext ctx
                    ? ctx.sessionId() : null;
                return readStateStore.findRead(sessionId, path)
                    .map(saved -> checkStale(path, saved).then(writeFile(path, content, true)))
                    .orElseGet(() -> Mono.error(new IllegalStateException(
                        "Refusing to overwrite '" + path + "': read the existing file "
                            + "with file_read first, then retry with an updated plan.")));
            });
    }

    private Mono<Object> writeFile(Path path, String content, boolean isOverwrite) {
        return Mono.fromCallable(() -> {
            Files.createDirectories(path.getParent());
            if (isOverwrite) {
                String oldContent = Files.readString(path);
                long oldChars = oldContent.length();
                long changedLines = countChangedLines(oldContent, content);
                Files.writeString(path, content);
                return (Object) ("Overwrote " + path + " (" + oldChars + " -> "
                    + content.length() + " chars, " + changedLines + " changed lines)");
            }
            long chars = content.length();
            Files.writeString(path, content);
            return (Object) ("Created " + path + " (" + chars + " chars)");
        });
    }

    private static long countChangedLines(String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        long changes = 0;
        int maxLen = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i < newLines.length ? newLines[i] : "";
            if (!oldLine.equals(newLine)) {
                changes++;
            }
        }
        return changes;
    }

    private Mono<Void> checkStale(Path path, FileReadSnapshot saved) {
        return Mono.fromCallable(() -> {
            FileReadSnapshot current = FileReadSnapshot.from(path);
            if (!current.sha256().equals(saved.sha256())
                || current.size() != saved.size()
                || !current.lastModifiedTime().equals(saved.lastModifiedTime())) {
                throw new IllegalStateException(
                    "Refusing stale overwrite '" + path + "': file changed since it was read. "
                        + "Read it again with file_read before writing.");
            }
            return true;
        }).then();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractArgs(Object input) {
        if (input instanceof Map<?, ?> m) return (Map<String, String>) m;
        return Map.of();
    }
}
