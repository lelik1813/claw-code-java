package com.clawcode.agent.cli.commands;

import java.util.List;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.CliExceptionMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "send", description = "Send a message to an active session.%n  Prints 'accepted' or 'rejected'.",
    mixinStandardHelpOptions = true)
public class MessageSendCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication.MessageCommand parent;

    @Parameters(index = "0", paramLabel = "SESSION_ID", description = "Session ID")
    String sessionId;

    @Parameters(index = "1", paramLabel = "CONTENT", description = "Message content")
    String content;

    @Option(names = {"--skill"}, description = "Skill IDs to activate (repeatable)")
    List<String> skills;

    @Override
    public Integer call() {
        if (sessionId.isBlank()) {
            parent.parent.err().println("Error: SESSION_ID must not be blank");
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
        if (content.isBlank()) {
            parent.parent.err().println("Error: CONTENT must not be blank");
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
        try {
            var ack = parent.parent.client().sendMessage(sessionId, content, skills).block();
            if (ack == null) {
                parent.parent.err().println("Error: empty response from server");
                return AgentCliApplication.EXIT_API_ERROR;
            }
            parent.parent.out().println(ack.accepted() ? "accepted" : "rejected");
            return ack.accepted() ? AgentCliApplication.EXIT_OK : AgentCliApplication.EXIT_API_ERROR;
        } catch (Exception e) {
            var exit = CliExceptionMapper.map(e);
            parent.parent.err().println("Error: " + exit.message());
            return exit.code();
        }
    }
}
