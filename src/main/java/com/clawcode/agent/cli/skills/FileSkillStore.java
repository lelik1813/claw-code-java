package com.clawcode.agent.cli.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discovers skills by scanning directories for {@code SKILL.md} files.
 * Default scan path: {@code ~/.agent-cli/skills/}.
 *
 * <p>Each subdirectory under the skills root is a candidate skill.
 * A valid skill directory contains a {@code SKILL.md} file whose first
 * markdown header ({@code # ...}) becomes the skill name, and the
 * remaining content becomes the description.
 */
public class FileSkillStore {

    private static final String CONFIG_DIR_NAME = ".agent-cli";
    private static final String SKILLS_DIR_NAME = "skills";
    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsRoot;

    public FileSkillStore() {
        this(defaultSkillsRoot());
    }

    public FileSkillStore(Path skillsRoot) {
        this.skillsRoot = skillsRoot;
    }

    public List<SkillDescriptor> load() {
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        var result = new ArrayList<SkillDescriptor>();
        try (var entries = Files.list(skillsRoot)) {
            entries.filter(Files::isDirectory)
                .sorted()
                .forEach(dir -> result.add(parseSkill(dir)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan skills directory: " + skillsRoot, e);
        }
        return List.copyOf(result);
    }

    public Optional<SkillDescriptor> find(String name) {
        return load().stream()
            .filter(s -> s.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public Path skillsRoot() {
        return skillsRoot;
    }

    private SkillDescriptor parseSkill(Path dir) {
        var skillFile = dir.resolve(SKILL_FILE);
        var dirName = dir.getFileName().toString();

        if (!Files.exists(skillFile)) {
            return SkillDescriptor.invalid(dirName, dir, "no " + SKILL_FILE + " found");
        }

        try {
            var content = Files.readString(skillFile);
            return parseContent(dirName, dir, content);
        } catch (IOException e) {
            return SkillDescriptor.invalid(dirName, dir, "cannot read " + SKILL_FILE + ": " + e.getMessage());
        }
    }

    SkillDescriptor parseContent(String dirName, Path dir, String content) {
        if (content.isBlank()) {
            return SkillDescriptor.invalid(dirName, dir, SKILL_FILE + " is empty");
        }

        var lines = content.lines().toList();
        String name = dirName;
        String description = content.strip();

        // Extract name from first markdown header (# Title)
        for (var line : lines) {
            var stripped = line.strip();
            if (stripped.startsWith("# ")) {
                name = stripped.substring(2).strip();
                // Description is everything after the header
                var descLines = new ArrayList<String>();
                boolean pastHeader = false;
                for (var l : lines) {
                    if (!pastHeader) {
                        if (l.strip().startsWith("# ")) pastHeader = true;
                        continue;
                    }
                    if (!l.strip().isEmpty() || !descLines.isEmpty()) {
                        descLines.add(l);
                    }
                }
                var sb = new StringBuilder();
                for (var l : descLines) {
                    sb.append(l).append('\n');
                }
                description = sb.toString().strip();
                if (description.isEmpty()) {
                    description = name;
                }
                break;
            }
        }

        return SkillDescriptor.valid(name, description, dir);
    }

    private static Path defaultSkillsRoot() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR_NAME, SKILLS_DIR_NAME);
    }
}
