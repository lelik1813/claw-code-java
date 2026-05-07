package com.clawcode.agent.cli.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class ServerEnvStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String CONFIG_FILE_NAME = "server.env";

    private final Path configPath;

    public ServerEnvStore() {
        this(defaultConfigPath());
    }

    public ServerEnvStore(Path configPath) {
        this.configPath = configPath;
    }

    public Map<String, String> load() {
        if (!Files.exists(configPath)) {
            return Map.of();
        }
        var props = new Properties();
        try (var in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load server env: " + configPath, e);
        }
        var result = new LinkedHashMap<String, String>();
        for (String name : props.stringPropertyNames()) {
            result.put(name, props.getProperty(name));
        }
        return Map.copyOf(result);
    }

    public synchronized void save(Map<String, String> values) {
        try {
            Files.createDirectories(configPath.getParent());
            var props = new Properties();
            values.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .forEach(e -> props.setProperty(e.getKey(), e.getValue()));
            var tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "claw-code-java local server environment");
            }
            Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save server env: " + configPath, e);
        }
    }

    public Path configPath() {
        return configPath;
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, CONFIG_FILE_NAME);
    }
}
