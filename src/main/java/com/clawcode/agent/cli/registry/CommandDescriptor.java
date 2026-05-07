package com.clawcode.agent.cli.registry;

import java.util.List;
import java.util.Set;

/**
 * Metadata descriptor for a CLI command.
 * Single source of truth for routing, help, slash-dispatch, and availability checks.
 */
public record CommandDescriptor(
    String name,
    Set<String> aliases,
    String description,
    CommandOrigin origin,
    boolean enabled
) {

    public enum CommandOrigin {
        BUILTIN, PLUGIN, SKILL
    }

    public boolean matches(String query) {
        if (name.equalsIgnoreCase(query)) return true;
        return aliases.stream().anyMatch(a -> a.equalsIgnoreCase(query));
    }

    public static CommandDescriptor builtin(String name, String description) {
        return new CommandDescriptor(name, Set.of(), description, CommandOrigin.BUILTIN, true);
    }

    public static CommandDescriptor builtin(String name, Set<String> aliases, String description) {
        return new CommandDescriptor(name, aliases, description, CommandOrigin.BUILTIN, true);
    }

    public CommandDescriptor withEnabled(boolean enabled) {
        return new CommandDescriptor(name, aliases, description, origin, enabled);
    }
}
