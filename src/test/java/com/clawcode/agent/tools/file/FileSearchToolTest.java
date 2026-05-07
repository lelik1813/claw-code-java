package com.clawcode.agent.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class FileSearchToolTest {

    private String savedRoots;

    @BeforeEach
    void clearAllowedRoots() {
        savedRoots = System.getProperty("app.tools.allowed-roots");
        System.clearProperty("app.tools.allowed-roots");
    }

    @AfterEach
    void restoreAllowedRoots() {
        if (savedRoots != null) {
            System.setProperty("app.tools.allowed-roots", savedRoots);
        } else {
            System.clearProperty("app.tools.allowed-roots");
        }
    }

    private final FileSearchTool tool = new FileSearchTool();

    @Test
    void definitionDescribesBoundedContentSearch() {
        var desc = tool.definition().description();
        assertThat(desc)
            .contains("content search")
            .contains("instead of shell")
            .contains("bounded")
            .contains("limit");
    }

    @Test
    void findsMatchingLinesInFiles() throws IOException {
        Path dir = Path.of("target").resolve("file-search-test");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("App.java"), "public class App {\n    // TODO fix\n}\n");
        Files.writeString(dir.resolve("Util.java"), "class Util {}\n");

        StepVerifier.create(tool.execute(Map.of("pattern", "TODO", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).anyMatch(l -> l.contains("TODO") && l.contains("App.java") && l.contains(":2:"));
                assertThat(lines).noneMatch(l -> l.contains("Util.java"));
            })
            .verifyComplete();
    }

    @Test
    void filesWithMatchesModeReturnsOnlyPaths() throws IOException {
        Path dir = Path.of("target").resolve("file-search-files");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "hello world\n");
        Files.writeString(dir.resolve("b.txt"), "no match\n");

        StepVerifier.create(tool.execute(
            Map.of("pattern", "hello", "path", dir.toString(), "mode", "files"), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).anyMatch(p -> p.contains("a.txt"));
                assertThat(paths).noneMatch(p -> p.contains("b.txt"));
                assertThat(paths).allMatch(p -> !p.contains(":"));
            })
            .verifyComplete();
    }

    @Test
    void limitCapsResults() throws IOException {
        Path dir = Path.of("target").resolve("file-search-limit");
        Files.createDirectories(dir);
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "match\nmatch\n");
        }

        StepVerifier.create(tool.execute(
            Map.of("pattern", "match", "path", dir.toString(), "limit", 3), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).hasSize(3);
            })
            .verifyComplete();
    }

    @Test
    void globFiltersFiles() throws IOException {
        Path dir = Path.of("target").resolve("file-search-glob");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("App.java"), "// marker xyz");
        Files.writeString(dir.resolve("notes.txt"), "marker xyz");

        StepVerifier.create(tool.execute(
            Map.of("pattern", "marker", "path", dir.toString(), "glob", "*.java"), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).anyMatch(l -> l.contains("App.java"));
                assertThat(lines).noneMatch(l -> l.contains("notes.txt"));
            })
            .verifyComplete();
    }

    @Test
    void emptyResultWhenNoMatch() throws IOException {
        Path dir = Path.of("target").resolve("file-search-empty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "nothing relevant\n");

        StepVerifier.create(tool.execute(Map.of("pattern", "absent123", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void pathOutsideWorkspaceDenied() {
        StepVerifier.create(tool.execute(Map.of("pattern", "test", "path", "../../etc"), null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Access denied"))
            .verify();
    }

    @Test
    void javaFallbackUsedWhenRgMissing() throws IOException {
        FileSearchTool noRgTool = new FileSearchTool();
        noRgTool.rgCommand = "nonexistent_rg_binary_12345";

        Path dir = Path.of("target").resolve("file-search-fallback");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hello.txt"), "findme in line 1\nnothing here\nfindme again\n");

        StepVerifier.create(noRgTool.execute(Map.of("pattern", "findme", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).hasSize(2);
                assertThat(lines.get(0)).contains("hello.txt:1:");
                assertThat(lines.get(1)).contains("hello.txt:3:");
                assertThat(lines).allMatch(l -> l.contains("findme"));
            })
            .verifyComplete();
    }

    @Test
    void rgErrorDoesNotLeakIntoResults() throws IOException {
        // Create a script that writes to stderr and exits 2
        Path script = Path.of("target").resolve("rg-fake-error.bat");
        Files.writeString(script, "@echo error message >&2\r\n@exit /b 2");

        FileSearchTool errorRgTool = new FileSearchTool();
        errorRgTool.rgCommand = script.toAbsolutePath().toString();

        Path dir = Path.of("target").resolve("file-search-rg-error");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("data.txt"), "searchable content\n");

        StepVerifier.create(errorRgTool.execute(Map.of("pattern", "searchable", "path", dir.toString()), null))
            .assertNext(result -> {
                // Falls back to Java, finds the line — no stderr contamination
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).anyMatch(l -> l.contains("data.txt") && l.contains("searchable"));
                assertThat(lines).noneMatch(l -> l.contains("error message"));
            })
            .verifyComplete();
    }

    @Test
    void normalizeRgLinePrependsSubdirectory() {
        Path base = FileSearchTool.WORKSPACE_ROOT.resolve("target").resolve("my-dir");
        String rgLine = "App.java:10:public class App";
        assertThat(FileSearchTool.normalizeRgLine(base, rgLine))
            .isEqualTo("target/my-dir/App.java:10:public class App");
    }

    @Test
    void normalizeRgLineLeavesWorkspaceRootLinesUnchanged() {
        String rgLine = "src/Main.java:5:public class Main";
        assertThat(FileSearchTool.normalizeRgLine(FileSearchTool.WORKSPACE_ROOT, rgLine))
            .isEqualTo("src/Main.java:5:public class Main");
    }

    @Test
    void normalizeRgLineDoesNotDoublePrefix() {
        Path base = FileSearchTool.WORKSPACE_ROOT.resolve("target");
        String alreadyPrefixed = "target/App.java:1:hello";
        assertThat(FileSearchTool.normalizeRgLine(base, alreadyPrefixed))
            .isEqualTo("target/App.java:1:hello");
    }

    @Test
    void normalizeRgLineFilesModePath() {
        Path base = FileSearchTool.WORKSPACE_ROOT.resolve("target").resolve("proj");
        assertThat(FileSearchTool.normalizeRgLine(base, "App.java"))
            .isEqualTo("target/proj/App.java");
    }

    @Test
    void rgExit1NoMatchesSkipsJavaFallback() throws IOException {
        // Fake rg that exits 1 (no matches) — Java fallback would find the match
        Path script = Path.of("target").resolve("rg-fake-nomatch.bat");
        Files.writeString(script, "@exit /b 1");

        FileSearchTool noMatchRgTool = new FileSearchTool();
        noMatchRgTool.rgCommand = script.toAbsolutePath().toString();

        Path dir = Path.of("target").resolve("file-search-rg-nomatch");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("data.txt"), "would-be-found-by-java\n");

        StepVerifier.create(noMatchRgTool.execute(Map.of("pattern", "would-be-found", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) result;
                assertThat(lines).isEmpty();
            })
            .verifyComplete();
    }
}
