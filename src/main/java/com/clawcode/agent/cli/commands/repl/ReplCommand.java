package com.clawcode.agent.cli.commands.repl;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.ReplRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "repl",
    description = "Start an interactive REPL session.",
    mixinStandardHelpOptions = true)
public class ReplCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Override
    public Integer call() {
        return new ReplRunner(
            parent.out(),
            System.in,
            parent.client(),
            parent.registry()
        ).run();
    }
}
