package com.clawcode.agent.tools.file;

import com.clawcode.agent.tools.Tool;
import com.clawcode.agent.tools.ToolDefinition;
import com.clawcode.agent.tools.security.WorkspacePathGuard;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class FileSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 250;

    static final Path WORKSPACE_ROOT = Path.of(System.getProperty("user.dir")).normalize();

    String rgCommand = "rg";

    private static final ToolDefinition DEFINITION = new ToolDefinition(
        "file_search",
        "Search file contents for a text or regex pattern within the workspace. "
            + "Use this content search instead of shell grep/rg for workspace-wide searches. "
            + "Returns matching file paths and line snippets (bounded by limit). "
            + "Results are workspace-relative paths that can be passed to file_read.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string",
                    "description", "Text or regex pattern to search for."),
                "path", Map.of("type", "string",
                    "description", "Directory to search in, relative to workspace root. Defaults to workspace root."),
                "glob", Map.of("type", "string",
                    "description", "File glob filter, e.g. '*.java'. Only files matching this glob are searched."),
                "mode", Map.of("type", "string",
                    "description", "Result mode: 'snippets' (default) returns file:line:text, 'files' returns only file paths."),
                "limit", Map.of("type", "integer",
                    "description", "Maximum number of results. Defaults to 250.")
            ),
            "required", List.of("pattern"),
            "additionalProperties", false
        )
    );

    @Override
    public String name() {
        return "file_search";
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
        String glob = params.get("glob") != null ? params.get("glob").toString() : null;
        boolean filesOnly = "files".equals(params.get("mode"));
        int limit = params.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;

        return Mono.fromCallable(() -> pathStr != null
                ? WorkspacePathGuard.validate(pathStr)
                : WorkspacePathGuard.validate("."))
            .flatMap(base -> search(base, pattern, glob, filesOnly, limit));
    }

    private Mono<Object> search(Path base, String pattern, String glob, boolean filesOnly, int limit) {
        return Mono.fromCallable(() -> {
            if (Files.notExists(base) || !Files.isDirectory(base)) {
                return List.of();
            }
            RgResult rg = tryRipgrep(base, pattern, glob, filesOnly, limit);
            if (rg.ranSuccessfully) {
                return rg.lines;
            }
            return javaFallback(base, pattern, glob, filesOnly, limit);
        });
    }

    RgResult tryRipgrep(Path base, String pattern, String glob, boolean filesOnly, int limit) {
        List<String> cmd = new ArrayList<>(List.of(rgCommand, "--no-heading", "-n", "--max-columns", "200"));
        if (filesOnly) {
            cmd.add("--files-with-matches");
        }
        if (glob != null) {
            cmd.addAll(List.of("--glob", glob));
        }
        cmd.addAll(List.of("--max-count", String.valueOf(limit), pattern, "."));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(base.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            List<String> stdout = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))
                .lines().limit(limit).toList();
            proc.getOutputStream().close();
            proc.waitFor();
            int exitCode = proc.exitValue();
            if (exitCode == 0 || exitCode == 1) {
                return new RgResult(true, stdout.stream()
                    .map(line -> normalizeRgLine(base, line))
                    .toList());
            }
            return RgResult.FALLBACK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RgResult.FALLBACK;
        } catch (IOException e) {
            return RgResult.FALLBACK;
        }
    }

    record RgResult(boolean ranSuccessfully, List<String> lines) {
        static final RgResult FALLBACK = new RgResult(false, List.of());
    }

    static String normalizeRgLine(Path base, String line) {
        String baseRel = WORKSPACE_ROOT.relativize(base).toString().replace('\\', '/');
        if (baseRel.isEmpty() || ".".equals(baseRel)) return line;
        if (line.startsWith(baseRel + "/")) return line;
        return baseRel + "/" + line;
    }

    private List<String> javaFallback(Path base, String pattern, String glob, boolean filesOnly, int limit) {
        PathMatcher matcher = glob != null
            ? FileSystems.getDefault().getPathMatcher("glob:" + glob)
            : null;
        List<String> results = new ArrayList<>();
        try (var stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> matcher == null || matcher.matches(base.relativize(p)))
                .forEach(file -> {
                    if (results.size() >= limit) return;
                    try {
                        int[] lineNum = {0};
                        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                            lineNum[0]++;
                            if (line.contains(pattern)) {
                                String relPath = WORKSPACE_ROOT.relativize(file)
                                    .toString().replace('\\', '/');
                                if (filesOnly) {
                                    if (results.stream().noneMatch(r -> r.equals(relPath))) {
                                        results.add(relPath);
                                    }
                                } else {
                                    results.add(relPath + ":" + lineNum[0] + ":" + truncate(line.stripLeading(), 180));
                                }
                                if (results.size() >= limit) return;
                            }
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
        return results;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
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
