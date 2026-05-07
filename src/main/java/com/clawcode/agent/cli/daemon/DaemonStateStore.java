package com.clawcode.agent.cli.daemon;

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
 * File-backed store for daemon process state.
 * Persists to {@code ~/.agent-cli/daemon.json}.
 *
 * <p>Thread-safe via synchronized methods. Atomic writes via temp-file + move.
 */
public class DaemonStateStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String STATE_FILE_NAME = "daemon.json";

    private final Path statePath;
    private final ObjectMapper mapper;

    public DaemonStateStore() {
        this(defaultStatePath());
    }

    public DaemonStateStore(Path statePath) {
        this.statePath = statePath;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<DaemonState> load() {
        if (!Files.exists(statePath)) {
            return Optional.empty();
        }
        try {
            var tree = mapper.readTree(statePath.toFile());
            long pid = tree.get("pid").asLong();
            int port = tree.get("port").asInt();
            long startedAt = tree.get("startedAt").asLong();
            String status = tree.get("status").asText();
            return Optional.of(new DaemonState(pid, port, startedAt, status));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load daemon state: " + statePath, e);
        }
    }

    public synchronized void save(DaemonState state) {
        try {
            Files.createDirectories(statePath.getParent());
            var data = new LinkedHashMap<String, Object>();
            data.put("pid", state.pid());
            data.put("port", state.port());
            data.put("startedAt", state.startedAt());
            data.put("status", state.status());
            var tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), data);
            Files.move(tmp, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save daemon state: " + statePath, e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear daemon state: " + statePath, e);
        }
    }

    public Path statePath() {
        return statePath;
    }

    private static Path defaultStatePath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, STATE_FILE_NAME);
    }
}
