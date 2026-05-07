package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.ToolExecutionContext;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileEditTool implements Tool {

    private static final long MAX_CONTENT_SIZE = 1_048_576; // 1 MB

    private final FileReadStateStore readStateStore;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_edit",
        "Perform a targeted edit on an existing file in the workspace. "
            + "Use after reading the file with file_read. "
            + "Requires exactly one occurrence of old_text in the file; if not found "
            + "or found multiple times, the edit is rejected. "
            + "Not for creating new files (use file_write) and not for full overwrites (use file_write). "
            + "Parent directories must already exist.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string",
                    "description", "Path to an existing file, relative to the workspace root."),
                "old_text", Map.of("type", "string",
                    "description", "Exact text to find in the file. Must match literally; not a regex or glob. "
                        + "Must appear exactly once in the file."),
                "new_text", Map.of("type", "string",
                    "description", "Replacement text for the matched occurrence.")
            ),
            "required", List.of("path", "old_text", "new_text"),
            "additionalProperties", false
        )
    );

    public FileEditTool(FileReadStateStore readStateStore) {
        this.readStateStore = readStateStore;
    }

    @Override
    public String name() {
        return "file_edit";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        return Mono.fromCallable(() -> {
            var args = extractArgs(input);
            String pathStr = args.get("path");
            String oldText = args.get("old_text");
            String newText = args.get("new_text");

            if (oldText == null || oldText.isEmpty()) {
                throw new IllegalArgumentException("old_text is required and must not be empty");
            }
            if (newText == null) {
                throw new IllegalArgumentException("new_text is required");
            }

            Path path = WorkspacePathGuard.validate(pathStr);
            if (Files.notExists(path)) {
                throw new NoSuchFileException(path.toString());
            }
            if (Files.isDirectory(path)) {
                throw new IllegalArgumentException("Path is a directory, cannot edit: " + path);
            }

            String sessionId = context instanceof ToolExecutionContext ctx ? ctx.sessionId() : null;
            var saved = readStateStore.findRead(sessionId, path)
                .orElseThrow(() -> new IllegalStateException(
                    "Refusing edit '" + path + "': read the existing file "
                        + "with file_read first, then retry with an updated plan."));

            String content = Files.readString(path);
            long oldChars = content.length();

            FileReadSnapshot current = FileReadSnapshot.from(path);
            if (!current.sha256().equals(saved.sha256())
                || current.size() != saved.size()
                || !current.lastModifiedTime().equals(saved.lastModifiedTime())) {
                throw new IllegalStateException(
                    "Refusing stale edit '" + path + "': file changed since it was read. "
                        + "Read it again with file_read before editing.");
            }

            int count = countOccurrences(content, oldText);

            if (count == 0) {
                throw new IllegalStateException(
                    "Refusing edit '" + path + "': old_text was not found. "
                        + "Read the file and provide an exact snippet.");
            }
            if (count > 1) {
                throw new IllegalStateException(
                    "Refusing edit '" + path + "': old_text matched " + count
                        + " times. Provide a more specific snippet.");
            }

            int pos = content.indexOf(oldText);
            String newContent = content.substring(0, pos) + newText
                + content.substring(pos + oldText.length());
            long newChars = newContent.length();
            if (newChars > MAX_CONTENT_SIZE) {
                throw new IllegalStateException(
                    "Resulting content too large: " + newChars + " chars (max " + MAX_CONTENT_SIZE + ")");
            }
            long changedLines = countChangedLines(content, newContent);

            Files.writeString(path, newContent);
            readStateStore.recordRead(sessionId, path);

            return (Object) ("Edited " + path + " (" + oldChars + " -> " + newChars
                + " chars, " + changedLines + " changed lines)");
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

    private static int countOccurrences(String content, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractArgs(Object input) {
        if (input instanceof Map<?, ?> m) return (Map<String, String>) m;
        return Map.of();
    }
}
