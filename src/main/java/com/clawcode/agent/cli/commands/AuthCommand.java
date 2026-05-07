package com.clawcode.agent.cli.commands;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.auth.FileAuthStore;
import com.clawcode.agent.cli.commands.auth.AuthLoginCommand;
import com.clawcode.agent.cli.commands.auth.AuthLogoutCommand;
import com.clawcode.agent.cli.commands.auth.AuthStatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "auth",
    description = "Authentication operations.%n  Use: agent-cli auth login | status | logout",
    mixinStandardHelpOptions = true,
    subcommands = {
        AuthLoginCommand.class,
        AuthStatusCommand.class,
        AuthLogoutCommand.class
    })
public class AuthCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public FileAuthStore store;

    public FileAuthStore store() {
        if (store == null) {
            store = new FileAuthStore();
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
