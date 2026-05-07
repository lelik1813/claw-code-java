package com.clawcode.agent.cli.commands.plugin;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "remove",
    description = "Remove an installed plugin.",
    mixinStandardHelpOptions = true)
public class PluginRemoveCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Plugin name")
    String name;

    @Option(names = {"--force"}, description = "Do not error if plugin not found")
    boolean force;

    @Override
    public Integer call() {
        var out = parent.out();
        var removed = parent.store().remove(name);
        if (!removed && !force) {
            out.printf("Plugin '%s' not found.%n", name);
            return AgentCliApplication.EXIT_API_ERROR;
        }
        out.printf("Removed plugin '%s'.%n", name);
        return AgentCliApplication.EXIT_OK;
    }
}
