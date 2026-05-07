package com.clawcode.agent.cli.commands.config;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.commands.ConfigCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "unset",
    description = "Remove a configuration value (revert to default).",
    mixinStandardHelpOptions = true)
public class ConfigUnsetCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    ConfigCommand parent;

    @Parameters(index = "0", paramLabel = "KEY", description = "Configuration key to remove")
    String key;

    @Option(names = {"--force"}, description = "Suppress not-found error (exit 0)")
    boolean force;

    @Override
    public Integer call() {
        var out = parent.out();
        boolean removed = parent.store().unset(key);
        if (!removed) {
            if (force) {
                return AgentCliApplication.EXIT_OK;
            }
            out.printf("Key '%s' not found in configuration%n", key);
            return AgentCliApplication.EXIT_API_ERROR;
        }
        out.printf("Unset '%s' (reverted to default)%n", key);
        return AgentCliApplication.EXIT_OK;
    }
}
