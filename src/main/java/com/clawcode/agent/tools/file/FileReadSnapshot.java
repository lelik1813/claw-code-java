package com.clawcode.agent.tools.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

public record FileReadSnapshot(
    Path path,
    Instant readAt,
    long size,
    FileTime lastModifiedTime,
    String sha256
) {

    public FileReadSnapshot {
        path = path.toAbsolutePath().normalize();
    }

    public static FileReadSnapshot from(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        Instant readAt = Instant.now();
        long size = Files.size(abs);
        FileTime lastModified = Files.getLastModifiedTime(abs);
        String sha256 = digest(abs);
        return new FileReadSnapshot(path, readAt, size, lastModified, sha256);
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(is, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // drain
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
