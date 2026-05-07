package com.clawcode.agent.cli.commands.plugin;

import java.util.concurrent.Callable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.clawcode.agent.cli.AgentApiDtos.PluginEntry;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.PluginConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list",
    description = "List installed plugins.",
    mixinStandardHelpOptions = true)
public class PluginListCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Option(names = {"--json"}, description = "Output as JSON")
    boolean json;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() {
        var out = parent.out();
        var plugins = parent.store().load();
        if (plugins.isEmpty()) {
            out.println("(no plugins installed)");
            return AgentCliApplication.EXIT_OK;
        }
        if (json) {
            try {
                var entries = plugins.stream().map(PluginListCommand::toEntry).toList();
                out.println(JSON_MAPPER.writeValueAsString(entries));
            } catch (Exception e) {
                out.println("Error serializing plugins: " + e.getMessage());
                return AgentCliApplication.EXIT_API_ERROR;
            }
            return AgentCliApplication.EXIT_OK;
        }
        out.printf("%-20s %-20s %-7s %-10s %-10s%n", "NAME", "ID", "ENABLED", "SOURCE", "VERSION");
        for (var p : plugins) {
            out.printf("%-20s %-20s %-7s %-10s %-10s%n",
                p.name(), p.id(), p.enabled() ? "yes" : "no",
                p.source().name(), p.version() != null ? p.version() : "-");
        }
        return AgentCliApplication.EXIT_OK;
    }

    static PluginEntry toEntry(PluginConfig p) {
        return new PluginEntry(
            p.name(),
            p.id(),
            p.source().name(),
            p.version(),
            p.enabled(),
            p.installedAt() != null ? p.installedAt().toString() : null,
            p.pathOrUrl()
        );
    }
}
