package com.clawcode.agent.cli.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central catalog of CLI commands.
 * Analogous to TS {@code commands.ts} — single source of truth for
 * routing, help, slash-dispatch, and availability checks.
 */
public class CommandRegistry {

    private final Map<String, CommandDescriptor> commandsByName = new LinkedHashMap<>();

    public CommandRegistry() {
        this(List.of());
    }

    public CommandRegistry(Collection<CommandDescriptor> extra) {
        loadBuiltins();
        extra.forEach(this::register);
    }

    public List<CommandDescriptor> list() {
        return List.copyOf(commandsByName.values());
    }

    public List<CommandDescriptor> listEnabled() {
        return commandsByName.values().stream()
            .filter(CommandDescriptor::enabled)
            .toList();
    }

    public Optional<CommandDescriptor> find(String nameOrAlias) {
        return commandsByName.values().stream()
            .filter(d -> d.matches(nameOrAlias))
            .findFirst();
    }

    public boolean isEnabled(String nameOrAlias) {
        return find(nameOrAlias).map(CommandDescriptor::enabled).orElse(false);
    }

    public void register(CommandDescriptor descriptor) {
        commandsByName.put(descriptor.name(), descriptor);
    }

    public void unregister(String name) {
        commandsByName.remove(name);
    }

    private void loadBuiltins() {
        register(CommandDescriptor.builtin("session", "Session operations"));
        register(CommandDescriptor.builtin("message", "Message operations"));
        register(CommandDescriptor.builtin("stream", "Stream operations"));
        register(CommandDescriptor.builtin("auth", "Authentication operations"));
        register(CommandDescriptor.builtin("mcp", "MCP server operations"));
        register(CommandDescriptor.builtin("plugin", "Plugin lifecycle: list|install|remove|enable|disable|reload"));
        register(CommandDescriptor.builtin("config", "Configuration operations"));
        register(CommandDescriptor.builtin("skills", "Skill discovery: list|reload"));
        register(CommandDescriptor.builtin("daemon", "Background daemon: start|status|stop"));
        register(CommandDescriptor.builtin("launch", "Start local daemon and open REPL"));
        register(CommandDescriptor.builtin("remote", "Remote-control: status|connect|disconnect"));
        register(CommandDescriptor.builtin("repl", "Start interactive REPL"));
        register(CommandDescriptor.builtin("help", "Show available commands"));
        register(CommandDescriptor.builtin("exit", "Exit the REPL"));
    }
}
