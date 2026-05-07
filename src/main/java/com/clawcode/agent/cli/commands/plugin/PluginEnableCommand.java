package com.clawcode.agent.cli.commands.plugin;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.PluginConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "enable",
    description = "Enable a disabled plugin.",
    mixinStandardHelpOptions = true)
public class PluginEnableCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    PluginCommand parent;

    @Parameters(index = "0", paramLabel = "NAME", description = "Plugin name")
    String name;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            var updated = parent.store().updateEnabled(name, true);
            out.printf("Enabled plugin '%s'.%n", updated.name());
            return AgentCliApplication.EXIT_OK;
        } catch (PluginConfig.ValidationException e) {
            out.println(e.getMessage());
            return AgentCliApplication.EXIT_API_ERROR;
        }
    }
}
