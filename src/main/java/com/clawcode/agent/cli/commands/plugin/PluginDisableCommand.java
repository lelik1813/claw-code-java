package com.clawcode.agent.cli.commands.plugin;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.PluginConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "disable",
    description = "Disable an enabled plugin.",
    mixinStandardHelpOptions = true)
public class PluginDisableCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Plugin name")
    String name;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            var updated = parent.store().updateEnabled(name, false);
            out.printf("Disabled plugin '%s'.%n", updated.name());
            return AgentCliApplication.EXIT_OK;
        } catch (PluginConfig.ValidationException e) {
            out.println(e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }
}
