package com.clawcode.agent.tools.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class FileReadStateStore {

    private static final String GLOBAL = "GLOBAL";

    private final ConcurrentMap<String, FileReadSnapshot> store = new ConcurrentHashMap<>();

    public FileReadSnapshot recordRead(String sessionId, Path path) throws IOException {
        FileReadSnapshot snap = FileReadSnapshot.from(path);
        store.put(key(sessionId, snap.path()), snap);
        return snap;
    }

    public Optional<FileReadSnapshot> findRead(String sessionId, Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return Optional.ofNullable(store.get(key(sessionId, abs)));
    }

    public void clearSession(String sessionId) {
        String prefix = sessionKeyPrefix(sessionId);
        store.keySet().removeIf(k -> k.startsWith(prefix + "::"));
    }

    private static String key(String sessionId, Path absPath) {
        return sessionKeyPrefix(sessionId) + "::" + absPath;
    }

    private static String sessionKeyPrefix(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? GLOBAL : sessionId;
    }
}
