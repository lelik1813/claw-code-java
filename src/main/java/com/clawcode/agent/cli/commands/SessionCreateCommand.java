package com.clawcode.agent.cli.commands;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.CliExceptionMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "create", description = "Create a new agent session and print its ID.",
    mixinStandardHelpOptions = true)
public class SessionCreateCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication.SessionCommand parent;

    @Override
    public Integer call() {
        try {
            var info = parent.parent.client().createSession().block();
            if (info == null) {
                parent.parent.err().println("Error: empty response from server");
                return AgentCliApplication.EXIT_API_ERROR;
            }
            parent.parent.out().println(info.sessionId());
            return AgentCliApplication.EXIT_OK;
        } catch (Exception e) {
            var exit = CliExceptionMapper.map(e);
            parent.parent.err().println("Error: " + exit.message());
            return exit.code();
        }
    }
}
