package com.clawcode.agent.cli.commands.daemon;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.daemon.DaemonHealthChecker;
import com.clawcode.agent.cli.daemon.DaemonProcessLauncher;
import com.clawcode.agent.cli.daemon.DaemonStateStore;
import com.clawcode.agent.cli.daemon.HttpDaemonHealthChecker;
import com.clawcode.agent.cli.daemon.ProcessHandleProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "daemon",
    description = "Background daemon lifecycle.%n  Use: agent-cli daemon start | status | stop",
    mixinStandardHelpOptions = true,
    subcommands = {
        DaemonStartCommand.class,
        DaemonStatusCommand.class,
        DaemonStopCommand.class
    })
public class DaemonCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public DaemonStateStore store;
    public ProcessHandleProvider processProvider = new ProcessHandleProvider();
    public DaemonProcessLauncher processLauncher = DaemonProcessLauncher.system();
    public DaemonHealthChecker healthChecker = new HttpDaemonHealthChecker();

    public DaemonStateStore store() {
        if (store == null) {
            store = new DaemonStateStore();
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
