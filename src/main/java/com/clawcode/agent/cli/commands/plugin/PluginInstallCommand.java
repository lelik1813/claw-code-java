package com.clawcode.agent.cli.commands.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentApiDtos.PluginManifest;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.PluginConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "install",
    description = "Install a plugin from a local path or URL.",
    mixinStandardHelpOptions = true)
public class PluginInstallCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Parameters(index = "0", paramLabel = "SOURCE",
        description = "Plugin source: a local directory (containing plugin.json), a path to plugin.json, or a URL")
    String source;

    @Option(names = {"--name"},
        description = "Override plugin name (default: read from manifest)")
    String nameOverride;

    @Option(names = {"--disabled"}, description = "Install in disabled state")
    boolean disabled;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            var resolvedSource = resolveSource(source);
            var manifest = readManifest(resolvedSource);

            PluginConfig config;
            if (nameOverride != null && !nameOverride.isBlank()) {
                config = new PluginConfig(nameOverride, manifest.id(), resolvedSource.source,
                    manifest.version(), !disabled, Instant.now(), resolvedSource.localPath);
            } else {
                config = new PluginConfig(manifest.name(), manifest.id(), resolvedSource.source,
                    manifest.version(), !disabled, Instant.now(), resolvedSource.localPath);
            }

            PluginConfig.validate(config);
            parent.store().add(config);

            out.printf("Installed plugin '%s' (id=%s, version=%s, source=%s) to %s%n",
                config.name(), config.id(),
                config.version() != null ? config.version() : "-",
                config.source(), parent.store().configPath());
            return AgentCliApplication.EXIT_OK;
        } catch (PluginConfig.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        } catch (IOException e) {
            out.println("Install error: " + e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }

    ResolvedSource resolveSource(String raw) {
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return new ResolvedSource(PluginConfig.PluginSource.URL, raw, raw);
        }
        var path = Path.of(raw);
        if (!Files.exists(path)) {
            throw new PluginConfig.ValidationException("path does not exist: " + raw);
        }
        return new ResolvedSource(PluginConfig.PluginSource.PATH, raw, path.toAbsolutePath().toString());
    }

    PluginManifest readManifest(ResolvedSource resolved) throws IOException {
        return switch (resolved.source) {
            case PATH -> readPathManifest(Path.of(resolved.localPath));
            case URL -> readUrlManifest(resolved.location);
            case REGISTRY -> throw new PluginConfig.ValidationException("REGISTRY source not yet supported");
        };
    }

    PluginManifest readPathManifest(Path path) throws IOException {
        Path manifestPath;
        if (Files.isDirectory(path)) {
            manifestPath = path.resolve("plugin.json");
        } else {
            manifestPath = path;
        }
        if (!Files.exists(manifestPath)) {
            throw new PluginConfig.ValidationException(
                "manifest not found: " + manifestPath + ". Expected plugin.json in the plugin directory.");
        }
        return ManifestParser.parse(manifestPath);
    }

    PluginManifest readUrlManifest(String url) throws IOException {
        var manifestUrl = url.endsWith("/plugin.json") ? url : url + "/plugin.json";
        try {
            var connection = java.net.URI.create(manifestUrl).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (var is = connection.getInputStream()) {
                return ManifestParser.parse(is);
            }
        } catch (java.net.ConnectException e) {
            throw new PluginConfig.ValidationException(
                "cannot connect to URL: " + manifestUrl + " (" + e.getMessage() + ")");
        }
    }

    record ResolvedSource(PluginConfig.PluginSource source, String location, String localPath) {}
}
