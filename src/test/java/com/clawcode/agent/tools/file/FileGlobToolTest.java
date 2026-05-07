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

class FileGlobToolTest {

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

    private final FileGlobTool tool = new FileGlobTool();

    @Test
    void definitionDescribesBoundedGlobDiscovery() {
        var desc = tool.definition().description();
        assertThat(desc)
            .contains("glob")
            .contains("bounded")
            .contains("before reading multiple files")
            .contains("file_read");
    }

    @Test
    void returnsWorkspaceRelativePaths() throws IOException {
        Path dir = Path.of("target").resolve("file-glob-test");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src").resolve("App.java"), "class App {}");
        Files.writeString(dir.resolve("src").resolve("util.txt"), "text");
        Files.writeString(dir.resolve("README.md"), "hello");

        StepVerifier.create(tool.execute(Map.of("pattern", "**/*.java", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).anyMatch(p -> p.startsWith("target/file-glob-test/"));
                assertThat(paths).anyMatch(p -> p.endsWith("App.java"));
            })
            .verifyComplete();
    }

    @Test
    void emptyResultWhenNothingMatches() throws IOException {
        Path dir = Path.of("target").resolve("file-glob-empty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("notes.txt"), "hello");

        StepVerifier.create(tool.execute(Map.of("pattern", "**/*.java", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void limitCapsResults() throws IOException {
        Path dir = Path.of("target").resolve("file-glob-limit");
        Files.createDirectories(dir);
        for (int i = 0; i < 10; i++) {
            Files.writeString(dir.resolve("file" + i + ".txt"), "x");
        }

        StepVerifier.create(tool.execute(Map.of("pattern", "*.txt", "path", dir.toString(), "limit", 3), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).hasSize(3);
            })
            .verifyComplete();
    }

    @Test
    void resultsAreSortedBeforeLimit() throws IOException {
        Path dir = Path.of("target").resolve("file-glob-sort");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("c.txt"), "x");
        Files.writeString(dir.resolve("a.txt"), "x");
        Files.writeString(dir.resolve("b.txt"), "x");

        StepVerifier.create(tool.execute(Map.of("pattern", "*.txt", "path", dir.toString(), "limit", 2), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).hasSize(2);
                assertThat(paths).isSorted();
            })
            .verifyComplete();
    }

    @Test
    void pathOutsideWorkspaceDenied() {
        StepVerifier.create(tool.execute(Map.of("pattern", "*.txt", "path", "../../etc"), null))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("Access denied"))
            .verify();
    }

    @Test
    void missingDirectoryReturnsEmpty() {
        Path missing = Path.of("target").resolve("file-glob-missing-does-not-exist");

        StepVerifier.create(tool.execute(Map.of("pattern", "*", "path", missing.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void defaultLimitIs100() throws IOException {
        Path dir = Path.of("target").resolve("file-glob-default-limit");
        Files.createDirectories(dir);
        for (int i = 0; i < 120; i++) {
            Files.writeString(dir.resolve("f" + i + ".dat"), "x");
        }

        StepVerifier.create(tool.execute(Map.of("pattern", "*.dat", "path", dir.toString()), null))
            .assertNext(result -> {
                @SuppressWarnings("unchecked")
                List<String> paths = (List<String>) result;
                assertThat(paths).hasSize(100);
            })
            .verifyComplete();
    }
}
