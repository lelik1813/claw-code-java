package com.clawcode.agent.cli.commands.config;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.config.ConfigKeySpec;
import com.clawcode.agent.cli.commands.ConfigCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "set",
    description = "Set a configuration value.",
    mixinStandardHelpOptions = true)
public class ConfigSetCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    ConfigCommand parent;

    @Parameters(index = "0", paramLabel = "KEY", description = "Configuration key")
    String key;

    @Parameters(index = "1", paramLabel = "VALUE", description = "Configuration value")
    String value;

    @Override
    public Integer call() {
        var out = parent.out();
        try {
            parent.store().set(key, value);
            out.printf("%s = %s%n", key, value.strip());
            return AgentCliApplication.EXIT_OK;
        } catch (ConfigKeySpec.ValidationException e) {
            out.println("Validation error: " + e.getMessage());
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
    }
}
