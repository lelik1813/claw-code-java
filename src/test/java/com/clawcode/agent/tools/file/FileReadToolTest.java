package com.clawcode.agent.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawcode.agent.tools.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class FileReadToolTest {

    private final FileReadStateStore store = new FileReadStateStore();
    private final FileReadTool tool = new FileReadTool(store);
    private final ToolExecutionContext ctx = new ToolExecutionContext("t1", "s1", "m1", "system");
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

    @Test
    void definitionContainsReadOnlyGuidance() {
        var def = tool.definition();
        assertThat(def.description())
            .contains("read-only")
            .containsIgnoringCase("targeted")
            .contains("not directories")
            .contains("file_list")
            .contains("file_glob")
            .contains("file_search");
        assertThat(def.inputSchema().toString())
            .contains("read-only")
            .containsIgnoringCase("targeted");
    }

    @Test
    void readsExistingFileContent() throws IOException {
        Path dir = Path.of("target").resolve("file-read-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("hello.txt");
        Files.writeString(file, "hello world");

        try {
            StepVerifier.create(tool.execute(Map.of("path", file.toString()), ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(String.class);
                    assertThat((String) result).isEqualTo("hello world");
                })
                .verifyComplete();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void readsFileWithAbsolutePath() throws IOException {
        Path dir = Path.of("target").resolve("file-read-test-abs");
        Files.createDirectories(dir);
        Path file = dir.resolve("abs.txt");
        Files.writeString(file, "absolute");

        try {
            StepVerifier.create(tool.execute(Map.of("path", file.toAbsolutePath().toString()), ctx))
                .assertNext(result -> assertThat(result).isEqualTo("absolute"))
                .verifyComplete();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void acceptsStringInput() throws IOException {
        Path dir = Path.of("target").resolve("file-read-test-str");
        Files.createDirectories(dir);
        Path file = dir.resolve("str.txt");
        Files.writeString(file, "via string");

        try {
            StepVerifier.create(tool.execute(file.toString(), ctx))
                .assertNext(result -> assertThat(result).isEqualTo("via string"))
                .verifyComplete();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileReturnsError() {
        String missing = Path.of("target").resolve("nope.txt").toString();

        StepVerifier.create(tool.execute(Map.of("path", missing), ctx))
            .expectErrorMatches(e ->
                e.getMessage() != null
                && e.getMessage().contains("nope.txt"))
            .verify();
    }

    @Test
    void directoryReturnsHelpfulError() throws IOException {
        Path dir = Path.of("target").resolve("file-read-directory");
        Files.createDirectories(dir);

        StepVerifier.create(tool.execute(Map.of("path", dir.toString()), ctx))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("use file_list instead"))
            .verify();
    }

    @Test
    void pathEscapingWorkspaceRejected() {
        StepVerifier.create(tool.execute(Map.of("path", "../../etc/passwd"), ctx))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("escapes workspace"))
            .verify();
    }

    @Test
    void nullPathRejected() {
        StepVerifier.create(tool.execute(Map.of(), ctx))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("path is required"))
            .verify();
    }

    @Test
    void blankPathRejected() {
        StepVerifier.create(tool.execute(Map.of("path", "  "), ctx))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("path is required"))
            .verify();
    }

    @Test
    void successfulReadRecordsSnapshot() throws IOException {
        Path dir = Path.of("target").resolve("file-read-snap-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("snapshot-me.txt");
        Files.writeString(file, "snapshot content");

        try {
            StepVerifier.create(tool.execute(Map.of("path", file.toString()), ctx))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(store.findRead("s1", file)).isPresent();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileDoesNotRecordSnapshot() {
        String missing = Path.of("target").resolve("no-such-file-123.txt").toString();

        StepVerifier.create(tool.execute(Map.of("path", missing), ctx))
            .expectError()
            .verify();

        assertThat(store.findRead("s1", Path.of("target").resolve("no-such-file-123.txt"))).isEmpty();
    }

    @Test
    void directoryDoesNotRecordSnapshot() throws IOException {
        Path dir = Path.of("target").resolve("file-read-no-snap-dir");
        Files.createDirectories(dir);

        StepVerifier.create(tool.execute(Map.of("path", dir.toString()), ctx))
            .expectError()
            .verify();

        assertThat(store.findRead("s1", dir)).isEmpty();
    }

    @Test
    void pathEscapingDoesNotRecordSnapshot() {
        StepVerifier.create(tool.execute(Map.of("path", "../../etc/passwd"), ctx))
            .expectError()
            .verify();

        assertThat(store.findRead("s1", Path.of("../../etc/passwd"))).isEmpty();
    }
}
