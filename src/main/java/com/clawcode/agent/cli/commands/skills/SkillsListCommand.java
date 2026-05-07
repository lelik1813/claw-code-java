package com.clawcode.agent.cli.commands.skills;

import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list",
    description = "List discovered skills.",
    mixinStandardHelpOptions = true)
public class SkillsListCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    SkillsCommand parent;

    @Option(names = {"--show-invalid"}, description = "Also show invalid/broken skills")
    boolean showInvalid;

    @Override
    public Integer call() {
        var out = parent.out();
        var skills = parent.store().load();
        if (skills.isEmpty()) {
            out.println("(no skills found)");
            return AgentCliApplication.EXIT_OK;
        }

        var valid = skills.stream().filter(s -> s.valid()).toList();
        var invalid = skills.stream().filter(s -> !s.valid()).toList();

        if (valid.isEmpty() && !showInvalid) {
            out.println("(no valid skills found)");
            return AgentCliApplication.EXIT_OK;
        }

        out.printf("%-20s %-8s %-50s%n", "NAME", "STATUS", "DESCRIPTION");
        for (var s : valid) {
            String desc = s.description() != null && s.description().length() > 48
                ? s.description().substring(0, 47) + "..." : s.description();
            out.printf("%-20s %-8s %-50s%n", s.name(), "ok", desc);
        }
        if (showInvalid) {
            for (var s : invalid) {
                out.printf("%-20s %-8s %-50s%n", s.name(), "invalid", s.validationError());
            }
        }
        return AgentCliApplication.EXIT_OK;
    }
}
