package com.clawcode.agent.cli.commands;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.config.CliConfigStore;
import com.clawcode.agent.cli.commands.config.ConfigGetCommand;
import com.clawcode.agent.cli.commands.config.ConfigSetCommand;
import com.clawcode.agent.cli.commands.config.ConfigListCommand;
import com.clawcode.agent.cli.commands.config.ConfigUnsetCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "config",
    description = "Configuration operations.%n  Use: agent-cli config get | set | list | unset",
    mixinStandardHelpOptions = true,
    subcommands = {
        ConfigGetCommand.class,
        ConfigSetCommand.class,
        ConfigListCommand.class,
        ConfigUnsetCommand.class
    })
public class ConfigCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public CliConfigStore store;

    public CliConfigStore store() {
        if (store == null) {
            store = new CliConfigStore();
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
