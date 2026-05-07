package com.clawcode.agent.cli.commands.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.PluginConfig;
import com.clawcode.agent.cli.registry.CommandDescriptor;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "reload",
    description = "Reload plugin registry from store, validating manifests.",
    mixinStandardHelpOptions = true)
public class PluginReloadCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var plugins = parent.store().load();

        if (plugins.isEmpty()) {
            out.println("No plugins installed — nothing to reload.");
            return AgentCliApplication.EXIT_OK;
        }

        int ok = 0;
        int skipped = 0;
        var warnings = new ArrayList<String>();

        for (var plugin : plugins) {
            if (!plugin.enabled()) {
                skipped++;
                continue;
            }
            var validation = validateManifest(plugin);
            if (validation.ok()) {
                ok++;
            } else {
                skipped++;
                warnings.add(plugin.name() + ": " + validation.error());
            }
        }

        // Update registry with current plugin state
        updateRegistry(plugins);

        out.printf("Reloaded %d plugin(s)%n", ok);
        if (skipped > 0) {
            out.printf("  skipped: %d (disabled or invalid)%n", skipped);
        }
        for (var w : warnings) {
            out.printf("  warning: %s%n", w);
        }
        return warnings.isEmpty() ? AgentCliApplication.EXIT_OK : AgentCliApplication.EXIT_API_ERROR;
    }

    ValidationResult validateManifest(PluginConfig plugin) {
        if (plugin.source() != PluginConfig.PluginSource.PATH) {
            return new ValidationResult(true, null);
        }
        if (plugin.pathOrUrl() == null) {
            return new ValidationResult(false, "no path stored");
        }
        var path = Path.of(plugin.pathOrUrl());
        if (!Files.exists(path)) {
            return new ValidationResult(false, "path not found: " + path);
        }
        var manifestPath = Files.isDirectory(path) ? path.resolve("plugin.json") : path;
        if (!Files.exists(manifestPath)) {
            return new ValidationResult(false, "manifest not found: " + manifestPath);
        }
        try {
            ManifestParser.parse(manifestPath);
            return new ValidationResult(true, null);
        } catch (Exception e) {
            return new ValidationResult(false, "manifest error: " + e.getMessage());
        }
    }

    private void updateRegistry(List<PluginConfig> plugins) {
        if (parent.parent == null) return;
        var registry = parent.parent.registry();
        // Remove all existing plugin-origin descriptors
        registry.list().stream()
            .filter(d -> d.origin() == CommandDescriptor.CommandOrigin.PLUGIN)
            .map(CommandDescriptor::name)
            .forEach(registry::unregister);
        // Re-register enabled plugins
        for (var plugin : plugins) {
            if (plugin.enabled()) {
                registry.register(new CommandDescriptor(
                    "plugin:" + plugin.name(),
                    java.util.Set.of(),
                    plugin.name() + " plugin (id=" + plugin.id() + ")",
                    CommandDescriptor.CommandOrigin.PLUGIN,
                    true
                ));
            }
        }
    }

    record ValidationResult(boolean ok, String error) {}

    // Exposed for testing
    List<ValidationResult> validateAll(List<PluginConfig> plugins) {
        return plugins.stream().map(this::validateManifest).toList();
    }
}
