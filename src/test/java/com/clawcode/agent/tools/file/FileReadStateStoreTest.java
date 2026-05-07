package com.clawcode.agent.tools.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadStateStoreTest {

    private final FileReadStateStore store = new FileReadStateStore();
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        Path dir = Path.of("target", "read-state-test");
        Files.createDirectories(dir);
        testFile = dir.resolve("state.txt");
        Files.writeString(testFile, "hello");
    }

    @Test
    void recordAndFind() throws IOException {
        FileReadSnapshot snap = store.recordRead("session-1", testFile);

        assertThat(snap.path()).isEqualTo(testFile.toAbsolutePath().normalize());
        assertThat(store.findRead("session-1", testFile)).hasValueSatisfying(found ->
            assertThat(found.sha256()).isEqualTo(snap.sha256()));
    }

    @Test
    void missingStateReturnsEmpty() {
        assertThat(store.findRead("session-1", testFile)).isEmpty();
    }

    @Test
    void pathNormalizationInKey() throws IOException {
        store.recordRead("session-1", testFile);

        Path unnormalized = Path.of("target", "..", "target", "read-state-test", "state.txt");
        Path different = Path.of("target", "read-state-test", "state.txt");

        assertThat(store.findRead("session-1", unnormalized)).isPresent();
        assertThat(store.findRead("session-1", different)).isPresent();
    }

    @Test
    void sessionIsolation() throws IOException {
        store.recordRead("session-a", testFile);

        assertThat(store.findRead("session-a", testFile)).isPresent();
        assertThat(store.findRead("session-b", testFile)).isEmpty();
    }

    @Test
    void clearSessionRemovesOnlyThatSession() throws IOException {
        store.recordRead("session-a", testFile);
        store.recordRead("session-b", testFile);

        store.clearSession("session-a");

        assertThat(store.findRead("session-a", testFile)).isEmpty();
        assertThat(store.findRead("session-b", testFile)).isPresent();
    }

    @Test
    void nullSessionUsesGlobalKey() throws IOException {
        FileReadSnapshot snap = store.recordRead(null, testFile);

        assertThat(store.findRead(null, testFile)).hasValueSatisfying(found ->
            assertThat(found.sha256()).isEqualTo(snap.sha256()));
    }

    @Test
    void blankSessionUsesGlobalKey() throws IOException {
        FileReadSnapshot snap = store.recordRead("  ", testFile);

        assertThat(store.findRead("  ", testFile)).hasValueSatisfying(found ->
            assertThat(found.sha256()).isEqualTo(snap.sha256()));
    }

    @Test
    void clearSessionDoesNotAffectGlobal() throws IOException {
        store.recordRead(null, testFile);
        store.recordRead("session-1", testFile);

        store.clearSession("session-1");

        assertThat(store.findRead(null, testFile)).isPresent();
        assertThat(store.findRead("session-1", testFile)).isEmpty();
    }
}
