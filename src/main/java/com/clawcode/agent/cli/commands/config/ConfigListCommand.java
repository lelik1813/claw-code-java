package com.clawcode.agent.cli.commands.config;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.commands.ConfigCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list",
    description = "List all configuration values.",
    mixinStandardHelpOptions = true)
public class ConfigListCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    ConfigCommand parent;

    @Option(names = {"--show-defaults"}, description = "Show default values for unset keys")
    boolean showDefaults;

    @Override
    public Integer call() {
        var out = parent.out();
        var stored = parent.store().listStored();
        if (!showDefaults && stored.isEmpty()) {
            out.println("(no config set)");
            return AgentCliApplication.EXIT_OK;
        }
        if (showDefaults) {
            var all = parent.store().list();
            out.printf("%-25s %-40s %-10s%n", "KEY", "VALUE", "SOURCE");
            for (var entry : all.entrySet()) {
                String source = stored.containsKey(entry.getKey()) ? "config" : "default";
                out.printf("%-25s %-40s %-10s%n", entry.getKey(), entry.getValue(), source);
            }
        } else {
            out.printf("%-25s %-40s%n", "KEY", "VALUE");
            for (var entry : stored.entrySet()) {
                out.printf("%-25s %-40s%n", entry.getKey(), entry.getValue());
            }
        }
        return AgentCliApplication.EXIT_OK;
    }
}
