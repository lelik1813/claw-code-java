package com.clawcode.agent.cli.skills;

import java.nio.file.Path;

/**
 * Typed CLI-side model for a discovered skill.
 * Parsed from a {@code SKILL.md} file in a skill directory.
 */
public record SkillDescriptor(
    String name,
    String description,
    Path skillPath,
    boolean enabled,
    boolean valid,
    String validationError
) {

    public static SkillDescriptor valid(String name, String description, Path skillPath) {
        return new SkillDescriptor(name, description, skillPath, true, true, null);
    }

    public static SkillDescriptor invalid(String name, Path skillPath, String error) {
        return new SkillDescriptor(name, null, skillPath, false, false, error);
    }

    public SkillDescriptor withEnabled(boolean enabled) {
        return new SkillDescriptor(name, description, skillPath, enabled, valid, validationError);
    }
}
