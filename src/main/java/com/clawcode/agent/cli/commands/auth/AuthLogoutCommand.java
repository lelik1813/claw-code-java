package com.clawcode.agent.cli.commands.auth;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.commands.AuthCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "logout",
    description = "Clear stored credentials.",
    mixinStandardHelpOptions = true)
public class AuthLogoutCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AuthCommand parent;

    @Option(names = {"--force"}, description = "Suppress not-found error (exit 0)")
    boolean force;

    @Override
    public Integer call() {
        var out = parent.out();
        var creds = parent.store().load();
        if (creds.isEmpty()) {
            if (force) {
                return AgentCliApplication.EXIT_OK;
            }
            out.println("No credentials to clear");
            return AgentCliApplication.EXIT_API_ERROR;
        }
        parent.store().clear();
        out.println("Credentials cleared");
        return AgentCliApplication.EXIT_OK;
    }
}
