package com.clawcode.agent.cli.remote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * File-backed store for remote-control connection state.
 * Persists to {@code ~/.agent-cli/remote.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class FileRemoteControlStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String STATE_FILE_NAME = "remote.json";

    private final Path statePath;
    private final ObjectMapper mapper;

    public FileRemoteControlStore() {
        this(defaultStatePath());
    }

    public FileRemoteControlStore(Path statePath) {
        this.statePath = statePath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<RemoteConnection> load() {
        if (!Files.exists(statePath)) {
            return Optional.empty();
        }
        try {
            var tree = mapper.readTree(statePath.toFile());
            String endpoint = tree.get("endpoint").asText();
            String sessionId = tree.has("sessionId") && !tree.get("sessionId").isNull()
                ? tree.get("sessionId").asText() : null;
            String status = tree.get("status").asText();
            long connectedAt = tree.has("connectedAt") ? tree.get("connectedAt").asLong() : 0;
            return Optional.of(new RemoteConnection(endpoint, sessionId, status, connectedAt));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load remote state: " + statePath, e);
        }
    }

    public synchronized void save(RemoteConnection connection) {
        try {
            Files.createDirectories(statePath.getParent());
            var data = new LinkedHashMap<String, Object>();
            data.put("endpoint", connection.endpoint());
            if (connection.sessionId() != null) data.put("sessionId", connection.sessionId());
            data.put("status", connection.status());
            data.put("connectedAt", connection.connectedAt());
            var tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), data);
            Files.move(tmp, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save remote state: " + statePath, e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear remote state: " + statePath, e);
        }
    }

    public Path statePath() {
        return statePath;
    }

    private static Path defaultStatePath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, STATE_FILE_NAME);
    }
}
