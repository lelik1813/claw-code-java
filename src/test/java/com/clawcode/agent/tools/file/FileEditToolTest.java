package com.clawcode.agent.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawcode.agent.tools.ToolExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class FileEditToolTest {

    private final FileReadStateStore store = new FileReadStateStore();
    private final FileEditTool tool = new FileEditTool(store);
    private static final ToolExecutionContext CTX = new ToolExecutionContext("t1", "s1", "m1", "system");
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

    // -- Schema and description tests --

    @Test
    void toolNameIsFileEdit() {
        assertThat(tool.name()).isEqualTo("file_edit");
    }

    @Test
    void descriptionContainsTargeted() {
        assertThat(tool.definition().description()).containsIgnoringCase("targeted");
    }

    @Test
    void descriptionContainsExistingFiles() {
        assertThat(tool.definition().description()).containsIgnoringCase("existing file");
    }

    @Test
    void descriptionContainsFileRead() {
        assertThat(tool.definition().description()).contains("file_read");
    }

    @Test
    void descriptionRequiresExactlyOneOccurrence() {
        assertThat(tool.definition().description())
            .containsIgnoringCase("exactly one occurrence");
    }

    @Test
    void descriptionSaysNotForNewFiles() {
        assertThat(tool.definition().description())
            .containsIgnoringCase("not for creating new files");
    }

    @Test
    void schemaContainsPath() {
        var props = properties();
        assertThat(props).containsKey("path");
    }

    @Test
    void schemaContainsOldText() {
        var props = properties();
        assertThat(props).containsKey("old_text");
    }

    @Test
    void schemaContainsNewText() {
        var props = properties();
        assertThat(props).containsKey("new_text");
    }

    @Test
    void schemaRequiresAllThree() {
        var def = tool.definition();
        @SuppressWarnings("unchecked")
        var required = (List<String>) def.inputSchema().get("required");
        assertThat(required).containsExactlyInAnyOrder("path", "old_text", "new_text");
    }

    @Test
    void oldTextDescriptionSaysLiteralMatchAndExactlyOnce() {
        var oldText = field("old_text");
        assertThat(oldText.get("description").toString())
            .containsIgnoringCase("not a regex")
            .containsIgnoringCase("exactly once");
    }

    // -- Execution tests --

    @Test
    void editWithoutPriorReadIsDenied() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-noread");
        Files.createDirectories(dir);
        Path file = dir.resolve("noread.txt");
        Files.writeString(file, "hello world");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "world",
                "new_text", "there"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing edit")
                        && e.getMessage().contains("read the existing file")
                        && e.getMessage().contains("with file_read first"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("hello world");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void crossSessionReadIsDenied() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-crosssession");
        Files.createDirectories(dir);
        Path file = dir.resolve("cross.txt");
        Files.writeString(file, "original");
        store.recordRead("other-session", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "original",
                "new_text", "changed"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing edit")
                        && e.getMessage().contains("read the existing file"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("original");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void readThenEditSucceeds() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-readthenedit");
        Files.createDirectories(dir);
        Path file = dir.resolve("readthenedit.txt");
        Files.writeString(file, "hello world foo");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "world",
                "new_text", "there"), CTX))
                .assertNext(result -> {
                    var msg = (String) result;
                    assertThat(msg).startsWith("Edited ");
                    assertThat(msg).contains(file.toString());
                    assertThat(msg).contains("chars");
                    assertThat(msg).contains("changed lines");
                })
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("hello there foo");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void staleEditIsDenied() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-stale");
        Files.createDirectories(dir);
        Path file = dir.resolve("stale.txt");
        Files.writeString(file, "version one");
        store.recordRead("s1", file);
        Files.writeString(file, "version two (external edit)");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "version",
                "new_text", "edition"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing stale edit")
                        && e.getMessage().contains("file changed since it was read")
                        && e.getMessage().contains("Read it again with file_read"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("version two (external edit)");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void staleEditIsDeniedEvenWhenOldTextNoLongerMatches() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-stale-nomatch");
        Files.createDirectories(dir);
        Path file = dir.resolve("stale-nomatch.txt");
        Files.writeString(file, "find this text");
        store.recordRead("s1", file);
        Files.writeString(file, "this text is gone (external edit)");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "find this text",
                "new_text", "replacement"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing stale edit")
                        && e.getMessage().contains("file changed since it was read")
                        && !e.getMessage().contains("old_text was not found"))
                .verify();

            assertThat(Files.readString(file)).isEqualTo("this text is gone (external edit)");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void readThenEditUpdatesSnapshotState() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-snapshot");
        Files.createDirectories(dir);
        Path file = dir.resolve("snapshot.txt");
        Files.writeString(file, "first version");
        store.recordRead("s1", file);

        try {
            // First edit succeeds
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "first version",
                "new_text", "second version"), CTX))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("second version");

            // Second edit without manual recordRead -- should succeed because
            // store was updated by recordRead after the first edit
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "second version",
                "new_text", "third version"), CTX))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("third version");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void contentChangedExactlyOnce() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-exact");
        Files.createDirectories(dir);
        Path file = dir.resolve("exact.txt");
        Files.writeString(file, "replace this once only");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "this",
                "new_text", "that"), CTX))
                .expectNextCount(1)
                .verifyComplete();

            assertThat(Files.readString(file))
                .as("only the exact match is replaced")
                .isEqualTo("replace that once only");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void summaryFormatNoFullContent() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-summary");
        Files.createDirectories(dir);
        Path file = dir.resolve("summary.txt");
        Files.writeString(file, "original content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "original",
                "new_text", "updated"), CTX))
                .assertNext(result -> {
                    var msg = (String) result;
                    assertThat(msg).startsWith("Edited ");
                    assertThat(msg).contains("chars");
                    assertThat(msg).contains("changed lines");
                    assertThat(msg).doesNotContain("original content");
                    assertThat(msg).doesNotContain("updated content");
                })
                .verifyComplete();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void editWithMultilineContent() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-multi");
        Files.createDirectories(dir);
        Path file = dir.resolve("multi.txt");
        Files.writeString(file, "line1\nline2\nline3");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "line2",
                "new_text", "changed"), CTX))
                .assertNext(result -> {
                    var msg = (String) result;
                    assertThat(msg).contains("changed lines");
                })
                .verifyComplete();

            assertThat(Files.readString(file)).isEqualTo("line1\nchanged\nline3");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void noMatchIsDenied() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-nomatch");
        Files.createDirectories(dir);
        Path file = dir.resolve("nomatch.txt");
        Files.writeString(file, "hello world");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "nonexistent",
                "new_text", "anything"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing edit")
                        && e.getMessage().contains("old_text was not found")
                        && e.getMessage().contains("exact snippet"))
                .verify();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void multipleMatchIsDenied() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-multimatch");
        Files.createDirectories(dir);
        Path file = dir.resolve("multimatch.txt");
        Files.writeString(file, "foo foo bar");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "foo",
                "new_text", "baz"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Refusing edit")
                        && e.getMessage().contains("matched 2 times")
                        && e.getMessage().contains("more specific snippet"))
                .verify();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void fileUnchangedAfterNoMatch() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-unchanged-nomatch");
        Files.createDirectories(dir);
        Path file = dir.resolve("unchanged-nomatch.txt");
        Files.writeString(file, "original content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "nonexistent",
                "new_text", "anything"), CTX))
                .expectError(IllegalStateException.class)
                .verify();

            assertThat(Files.readString(file)).isEqualTo("original content");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void fileUnchangedAfterMultipleMatch() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-unchanged-multi");
        Files.createDirectories(dir);
        Path file = dir.resolve("unchanged-multi.txt");
        Files.writeString(file, "original content that stays original");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "original",
                "new_text", "changed"), CTX))
                .expectError(IllegalStateException.class)
                .verify();

            assertThat(Files.readString(file))
                .isEqualTo("original content that stays original");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    // -- Input and workspace guard tests --

    @Test
    void pathEscapingWorkspaceRejected() {
        StepVerifier.create(tool.execute(Map.of(
            "path", "../../etc/evil",
            "old_text", "x",
            "new_text", "y"), CTX))
            .expectErrorMatches(e ->
                e instanceof SecurityException
                    && e.getMessage().contains("escapes workspace"))
            .verify();
    }

    @Test
    void nullOldTextRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-null-old");
        Files.createDirectories(dir);
        Path file = dir.resolve("null-old.txt");
        Files.writeString(file, "content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "new_text", "y"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("old_text is required"))
                .verify();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blankOldTextRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-blank-old");
        Files.createDirectories(dir);
        Path file = dir.resolve("blank-old.txt");
        Files.writeString(file, "content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "",
                "new_text", "y"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("old_text is required"))
                .verify();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void nullNewTextRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-null-new");
        Files.createDirectories(dir);
        Path file = dir.resolve("null-new.txt");
        Files.writeString(file, "content");
        store.recordRead("s1", file);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "x"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("new_text is required"))
                .verify();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void missingFileRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-missing");
        Files.createDirectories(dir);
        Path file = dir.resolve("does-not-exist.txt");

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "x",
                "new_text", "y"), CTX))
                .expectErrorMatches(e ->
                    e instanceof java.nio.file.NoSuchFileException)
                .verify();
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void directoryPathRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-directory");
        Files.createDirectories(dir);

        try {
            StepVerifier.create(tool.execute(Map.of(
                "path", dir.toString(),
                "old_text", "x",
                "new_text", "y"), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("directory"))
                .verify();
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void resultTooLargeRejected() throws Exception {
        Path dir = Path.of("target").resolve("file-edit-test-large");
        Files.createDirectories(dir);
        Path file = dir.resolve("large.txt");
        Files.writeString(file, "A");
        store.recordRead("s1", file);

        try {
            String bigReplacement = "A" + "y".repeat(1_048_576); // 1_048_577 chars > 1 MB
            StepVerifier.create(tool.execute(Map.of(
                "path", file.toString(),
                "old_text", "A",
                "new_text", bigReplacement), CTX))
                .expectErrorMatches(e ->
                    e instanceof IllegalStateException
                        && e.getMessage().contains("Resulting content too large"))
                .verify();

            assertThat(Files.readString(file))
                .as("file must remain unchanged after rejected oversized edit")
                .isEqualTo("A");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties() {
        return (Map<String, Object>) tool.definition().inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(String name) {
        return (Map<String, Object>) properties().get(name);
    }
}
