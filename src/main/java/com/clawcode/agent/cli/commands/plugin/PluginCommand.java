package com.clawcode.agent.cli.commands.plugin;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "plugin",
    description = "Manage plugins.%n  Use: agent-cli plugin list | install | remove | enable | disable | reload",
    mixinStandardHelpOptions = true,
    subcommands = {
        PluginListCommand.class,
        PluginInstallCommand.class,
        PluginRemoveCommand.class,
        PluginEnableCommand.class,
        PluginDisableCommand.class,
        PluginReloadCommand.class
    })
public class PluginCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public FilePluginConfigStore store;

    public FilePluginConfigStore store() {
        if (store == null) {
            store = new FilePluginConfigStore();
        }
        return store;
    }

    public PrintWriter out() {
        if (parent != null) return parent.out();
        return spec.commandLine().getOut();
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(out());
        return AgentCliApplication.EXIT_OK;
    }
}
