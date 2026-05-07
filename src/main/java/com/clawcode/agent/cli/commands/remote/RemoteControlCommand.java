package com.clawcode.agent.cli.commands.remote;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.auth.FileAuthStore;
import com.clawcode.agent.cli.remote.FileRemoteControlStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "remote",
    description = "Remote-control connection.%n  Use: agent-cli remote status | connect | disconnect",
    mixinStandardHelpOptions = true,
    subcommands = {
        RemoteStatusCommand.class,
        RemoteConnectCommand.class,
        RemoteDisconnectCommand.class
    })
public class RemoteControlCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public FileRemoteControlStore store;
    public FileAuthStore authStore;

    public FileRemoteControlStore store() {
        if (store == null) {
            store = new FileRemoteControlStore();
        }
        return store;
    }

    public FileAuthStore authStore() {
        if (authStore == null) {
            authStore = new FileAuthStore();
        }
        return authStore;
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
