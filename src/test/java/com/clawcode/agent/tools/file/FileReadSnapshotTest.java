package com.clawcode.agent.tools.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadSnapshotTest {

    @Test
    void fromPopulatesSizeAndLastModifiedTime() throws IOException {
        Path dir = Path.of("target", "snapshot-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("meta.txt");
        Files.writeString(file, "hello");

        try {
            FileReadSnapshot snap = FileReadSnapshot.from(file);

            assertThat(snap.size()).isEqualTo(5);
            assertThat(snap.lastModifiedTime()).isNotNull();
            assertThat(snap.lastModifiedTime()).isInstanceOf(FileTime.class);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fromNormalizesPath() throws IOException {
        Path dir = Path.of("target", "snapshot-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("norm.txt");
        Files.writeString(file, "data");

        try {
            Path relative = Path.of("target", "snapshot-test", "norm.txt");
            FileReadSnapshot snap = FileReadSnapshot.from(relative);

            assertThat(snap.path()).isAbsolute();
            assertThat(snap.path()).isEqualTo(relative.toAbsolutePath().normalize());
            assertThat(snap.path()).doesNotHaveToString(".*\\\\..*");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sha256ChangesAfterFileModified() throws IOException {
        Path dir = Path.of("target", "snapshot-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("hash-test.txt");
        Files.writeString(file, "version one");

        try {
            FileReadSnapshot snap1 = FileReadSnapshot.from(file);
            String hash1 = snap1.sha256();

            Files.writeString(file, "version two");
            FileReadSnapshot snap2 = FileReadSnapshot.from(file);
            String hash2 = snap2.sha256();

            assertThat(hash1).isNotEqualTo(hash2);
            assertThat(hash1).hasSize(64);
            assertThat(hash2).hasSize(64);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sha256IsDeterministic() throws IOException {
        Path dir = Path.of("target", "snapshot-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("deterministic.txt");
        Files.writeString(file, "stable");

        try {
            FileReadSnapshot snap1 = FileReadSnapshot.from(file);
            FileReadSnapshot snap2 = FileReadSnapshot.from(file);

            assertThat(snap1.sha256()).isEqualTo(snap2.sha256());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void readAtPopulated() throws IOException {
        Path dir = Path.of("target", "snapshot-test");
        Files.createDirectories(dir);
        Path file = dir.resolve("time-test.txt");
        Files.writeString(file, "when");

        try {
            FileReadSnapshot snap = FileReadSnapshot.from(file);
            assertThat(snap.readAt()).isNotNull();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
