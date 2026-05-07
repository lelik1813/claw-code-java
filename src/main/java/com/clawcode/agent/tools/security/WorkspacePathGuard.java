package com.clawcode.agent.tools.security;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorkspacePathGuard {

    private static final String EXTRA_ROOTS_PROPERTY = "app.tools.allowed-roots";
    private static final String EXTRA_ROOTS_ENV = "APP_TOOLS_ALLOWED_ROOTS";
    private static final Path WORKSPACE_ROOT = Path.of(System.getProperty("user.dir")).normalize();

    private WorkspacePathGuard() {}

    public static List<Path> effectiveAllowedRoots() {
        List<Path> extra = extraAllowedRoots();
        if (extra.isEmpty()) {
            return List.of(WORKSPACE_ROOT.toAbsolutePath().normalize());
        }
        return List.copyOf(extra);
    }

    public static Path validate(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        List<Path> configuredRoots = extraAllowedRoots();
        Path baseRoot = configuredRoots.isEmpty()
            ? WORKSPACE_ROOT
            : configuredRoots.getFirst();
        Path requested = Path.of(rawPath);
        Path resolved = requested.isAbsolute()
            ? requested.normalize()
            : baseRoot.resolve(requested).normalize();
        if (!isAllowed(resolved, configuredRoots)) {
            throw new SecurityException("Access denied: path escapes workspace");
        }
        return resolved;
    }

    private static boolean isAllowed(Path resolved, List<Path> configuredRoots) {
        if (configuredRoots.isEmpty()) {
            return resolved.startsWith(WORKSPACE_ROOT);
        }
        for (Path root : configuredRoots) {
            if (resolved.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> extraAllowedRoots() {
        String raw = System.getProperty(EXTRA_ROOTS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(EXTRA_ROOTS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<Path> roots = new ArrayList<>();
        for (String item : raw.split(",")) {
            String candidate = item.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                roots.add(Path.of(candidate).normalize().toAbsolutePath());
            } catch (InvalidPathException ignored) {
                // ignore malformed configured paths
            }
        }
        return roots;
    }
}
