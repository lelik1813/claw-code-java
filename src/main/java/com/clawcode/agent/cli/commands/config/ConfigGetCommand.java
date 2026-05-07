package com.clawcode.agent.cli.commands.config;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.commands.ConfigCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "get",
    description = "Get a configuration value.",
    mixinStandardHelpOptions = true)
public class ConfigGetCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    ConfigCommand parent;

    @Parameters(index = "0", paramLabel = "KEY", description = "Configuration key")
    String key;

    @Override
    public Integer call() {
        var out = parent.out();
        var value = parent.store().get(key);
        if (value.isEmpty()) {
            out.printf("Key '%s' is not set%n", key);
            return AgentCliApplication.EXIT_OK;
        }
        out.println(value.get());
        return AgentCliApplication.EXIT_OK;
    }
}
