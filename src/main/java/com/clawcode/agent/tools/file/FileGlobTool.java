package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileGlobTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_glob",
        "Find files by name or glob pattern within the workspace. "
            + "Returns workspace-relative paths that can be passed directly to file_read. "
            + "Use before reading multiple files with file_read: narrow down filenames first, "
            + "then read individually. Results are bounded (sorted and capped by limit).",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "Glob pattern to match, e.g. '**/*.java' or 'src/**/test/*.txt'."),
                "path", Map.of("type", "string",
                    "description", "Directory to search in, relative to workspace root. Defaults to workspace root."),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of results. Defaults to 100.")
            ),
            "required", List.of("pattern"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "file_glob";
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Mono<Object> execute(Object input, Object context) {
        Map<String, Object> params = extractParams(input);
        String pattern = requireNonBlank(params.get("pattern"), "pattern");
        String pathStr = params.get("path") != null ? params.get("path").toString() : null;
        int limit = params.get("limit") instanceof Number n
            ? n.intValue()
            : DEFAULT_LIMIT;

        return Mono.fromCallable(() -> pathStr != null
                ? WorkspacePathGuard.validate(pathStr)
                : WorkspacePathGuard.validate("."))
            .flatMap(base -> glob(base, pattern, limit));
    }

    private Mono<Object> glob(Path base, String pattern, int limit) {
        return Mono.fromCallable(() -> {
            if (Files.notExists(base)) {
                return List.of();
            }
            Path workspaceRoot = Path.of(System.getProperty("user.dir")).normalize();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> results;
            try (var stream = Files.walk(base)) {
                results = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(base.relativize(p)))
                    .sorted()
                    .map(workspaceRoot::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .limit(limit)
                    .toList();
            }
            return results;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractParams(Object input) {
        if (input instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (input instanceof String s && !s.isBlank()) return Map.of("pattern", s);
        return Map.of();
    }

    private String requireNonBlank(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
