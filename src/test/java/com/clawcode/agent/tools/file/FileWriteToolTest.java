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

class FileWriteToolTest {

    private final FileReadStateStore store = new FileReadStateStore();
    private final FileWriteTool tool = new FileWriteTool(store);
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
    void definitionDescribesFullOverwriteSemantics() {
        var desc = tool.definition().description();
        assertThat(desc)
            .contains("full overwrite")
            .contains("read first")
            .contains("existing file")
            .containsIgnoringCase("do not create docs unless explicitly requested");
    }

    @Test
    void writesNewFileAndReturnsConfirmation() throws IOException {
        Path dir = Path.of("target").resolve("file-write-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("new.txt");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "hello"), ctx))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(String.class);
                    String msg = (String) result;
                    assertThat(msg).startsWith("Created ");
                    assertThat(msg).contains(file.toString());
                    assertThat(msg).contains("(5 chars)");
                })
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("hello");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void overwritesExistingFile() throws IOException {
        Path dir = Path.of("target").resolve("file-write-test-ow");
        Files.createDirectories(dir);
        Path file = dir.resolve("overwrite.txt");
        Files.writeString(file, "old content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "new"), ctx))
                .assertNext(result -> {
                    String msg = (String) result;
                    assertThat(msg).startsWith("Overwrote ");
                    assertThat(msg).contains(" -> ");
                    assertThat(msg).contains("changed lines");
                })
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("new");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void createsParentDirectories() throws IOException {
        Path file = Path.of("target").resolve("file-write-test-deep").resolve("a").resolve("b").resolve("file.txt");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "deep"), ctx))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("deep");
        } finally {
            Path deep = Path.of("target").resolve("file-write-test-deep");
            cleanRecursive(deep);
        }
    }

    @Test
    void pathEscapingWorkspaceRejected() {
        StepVerifier.create(tool.execute(Map.of(
            "path", "../../etc/evil", "content", "hacked"), ctx))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                && e.getMessage().contains("escapes workspace"))
            .verify();
    }

    @Test
    void nullContentRejected() {
        StepVerifier.create(tool.execute(Map.of("path", "target/test.txt"), ctx))
            .expectErrorMatches(e ->
                e instanceof IllegalArgumentException
                && e.getMessage().contains("content is required"))
            .verify();
    }

    @Test
    void toolNameIsFileWrite() {
        assertThat(tool.name()).isEqualTo("file_write");
    }

    @Test
    void overwriteWithoutReadIsDenied() throws IOException {
        Path dir = Path.of("target").resolve("file-write-deny-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("no-read.txt");
        Files.writeString(file, "existing content");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "new content"), ctx))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                    && e.getMessage().contains("Refusing to overwrite")
                    && e.getMessage().contains("read the existing file")
                    && e.getMessage().contains("with file_read first"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("existing content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void overwriteWithReadIsAllowed() throws IOException {
        Path dir = Path.of("target").resolve("file-write-allow-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("with-read.txt");
        Files.writeString(file, "original");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "updated"), ctx))
                .assertNext(result ->
                    assertThat((String) result).startsWith("Overwrote "))
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("updated");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void newFileWithoutReadIsAllowed() throws IOException {
        Path dir = Path.of("target").resolve("file-write-new-allow");
        Files.createDirectories(dir);
        Path file = dir.resolve("brand-new.txt");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "fresh"), ctx))
                .assertNext(result -> {
                    assertThat((String) result).startsWith("Created ");
                    assertThat((String) result).contains("(5 chars)");
                })
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("fresh");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void overwriteDeniedAcrossSessions() throws IOException {
        Path dir = Path.of("target").resolve("file-write-session-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("session-guard.txt");
        Files.writeString(file, "session a content");
        store.recordRead("session-a", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "session b tries"),
                new ToolExecutionContext("t2", "session-b", "m1", "system")))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                    && e.getMessage().contains("Refusing to overwrite"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("session a content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void contentTooLargeRejected() {
        Path file = Path.of("target", "large.txt");
        String big = "x".repeat(1_048_577);

        StepVerifier.create(tool.execute(Map.of(
            "path", file.toString(), "content", big), ctx))
            .expectErrorMatches(e ->
                e instanceof IllegalStateException
                && e.getMessage().contains("Content too large"))
            .verify();
    }

    @Test
    void staleOverwriteAfterExternalModifyIsDenied() throws IOException {
        Path dir = Path.of("target").resolve("file-write-stale-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("stale.txt");
        Files.writeString(file, "version one");
        store.recordRead("s1", file);
        Files.writeString(file, "version two (external edit)");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "overwrite attempt"), ctx))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                    && e.getMessage().contains("Refusing stale overwrite")
                    && e.getMessage().contains("file changed since it was read")
                    && e.getMessage().contains("Read it again with file_read"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("version two (external edit)");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void overwriteAfterReadWithoutModifySucceeds() throws IOException {
        Path dir = Path.of("target").resolve("file-write-nochange-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("nochange.txt");
        Files.writeString(file, "stable content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "new content"), ctx))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("new content");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void summaryDoesNotContainFullContent() throws IOException {
        Path dir = Path.of("target").resolve("file-write-summary-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("summary.txt");
        Files.writeString(file, "old content that is long enough");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(), "content", "new content that is also long enough"), ctx))
                .assertNext(result -> {
                    String msg = (String) result;
                    assertThat(msg).doesNotContain("old content that is");
                    assertThat(msg).doesNotContain("new content that is");
                    assertThat(msg).contains("Overwrote");
                    assertThat(msg).contains("changed lines");
                })
                .verifyComplete();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void cleanRecursive(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        }
    }
}
