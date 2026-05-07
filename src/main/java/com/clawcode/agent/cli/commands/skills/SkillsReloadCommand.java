package com.clawcode.agent.cli.commands.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.registry.CommandDescriptor;
import com.clawcode.agent.cli.skills.SkillDescriptor;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "reload",
    description = "Reload skills from skill directories, updating the command registry.",
    mixinStandardHelpOptions = true)
public class SkillsReloadCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    SkillsCommand parent;

    @Override
    public Integer call() {
        var out = parent.out();
        var skills = parent.store().load();

        if (skills.isEmpty()) {
            out.println("No skills found — nothing to reload.");
            return AgentCliApplication.EXIT_OK;
        }

        int ok = 0;
        int skipped = 0;
        var warnings = new ArrayList<String>();

        for (var skill : skills) {
            if (!skill.valid()) {
                skipped++;
                warnings.add(skill.name() + ": " + skill.validationError());
                continue;
            }
            if (!skill.enabled()) {
                skipped++;
                continue;
            }
            ok++;
        }

        updateRegistry(skills);

        out.printf("Reloaded %d skill(s)%n", ok);
        if (skipped > 0) {
            out.printf("  skipped: %d (invalid or disabled)%n", skipped);
        }
        for (var w : warnings) {
            out.printf("  warning: %s%n", w);
        }

        return warnings.isEmpty() ? AgentCliApplication.EXIT_OK : AgentCliApplication.EXIT_API_ERROR;
    }

    private void updateRegistry(List<SkillDescriptor> skills) {
        if (parent.parent == null) return;
        var registry = parent.parent.registry();

        // Remove all existing SKILL-origin descriptors
        registry.list().stream()
            .filter(d -> d.origin() == CommandDescriptor.CommandOrigin.SKILL)
            .map(CommandDescriptor::name)
            .forEach(registry::unregister);

        // Re-register valid enabled skills
        for (var skill : skills) {
            if (skill.valid() && skill.enabled()) {
                registry.register(new CommandDescriptor(
                    "skill:" + skill.name(),
                    java.util.Set.of(),
                    skill.description() != null ? skill.description() : skill.name(),
                    CommandDescriptor.CommandOrigin.SKILL,
                    true
                ));
            }
        }
    }
}
