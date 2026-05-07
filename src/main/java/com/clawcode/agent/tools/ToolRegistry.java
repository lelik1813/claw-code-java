package com.clawcode.agent.tools;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves tools by name at execution time. Each call to {@link #findByName}
 * is an independent lookup — the interface does not guarantee that consecutive
 * calls within the same turn return the same result.
 *
 * <p><b>Dynamics contract.</b> Implementations are free to provide either
 * stable or evolving tool sets. {@code SpringToolRegistry}, the production
 * implementation, builds an immutable snapshot at construction time (Spring
 * beans + eagerly-loaded plugin tools), so all turns see a consistent view.
 * A future hot-reload implementation could return different tools across calls.
 *
 * <p>Clients that need turn-scoped consistency should capture
 * {@link #listNames()} at the start of a turn and resolve against the same
 * registry instance throughout.
 */
public interface ToolRegistry {

    Optional<Tool> findByName(String name);

    Set<String> listNames();

    default List<ToolDefinition> definitions() {
        return listNames().stream()
            .map(this::findByName)
            .flatMap(Optional::stream)
            .map(Tool::definition)
            .toList();
    }
}
