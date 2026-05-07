package com.clawcode.agent.cli.commands.skills;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.skills.FileSkillStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "skills",
    description = "Manage discovered skills.%n  Use: agent-cli skills list | reload",
    mixinStandardHelpOptions = true,
    subcommands = {
        SkillsListCommand.class,
        SkillsReloadCommand.class
    })
public class SkillsCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication parent;

    @Spec
    CommandSpec spec;

    public FileSkillStore store;

    public FileSkillStore store() {
        if (store == null) {
            store = new FileSkillStore();
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
