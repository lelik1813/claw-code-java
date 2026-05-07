package com.clawcode.agent.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "app.skills", name = "enabled", havingValue = "true")
public class FileSystemSkillRegistry implements SkillRegistry {

    private static final String SKILL_FILE = "SKILL.md";

    private final Path root;

    public FileSystemSkillRegistry(SkillsProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    @Override
    public Flux<SkillDefinition> list() {
        return Flux.defer(() -> {
            if (Files.notExists(root)) {
                return Flux.empty();
            }
            try (Stream<Path> dirs = Files.list(root)) {
                var skills = dirs
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve(SKILL_FILE))
                    .filter(Files::isRegularFile)
                    .map(this::toDefinition)
                    .sorted(Comparator.comparing(SkillDefinition::id))
                    .toList();
                return Flux.fromIterable(skills);
            } catch (IOException e) {
                return Flux.error(new RuntimeException(
                    "Failed to list skills in " + root, e));
            }
        });
    }

    @Override
    public Mono<SkillContent> read(String skillId) {
        return Mono.fromCallable(() -> {
            if (skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("skillId is required");
            }
            Path safePath = resolveSafe(skillId);
            if (Files.notExists(safePath)) {
                throw new NoSuchFileException("Skill not found: " + skillId);
            }
            String body = Files.readString(safePath);
            String name = safePath.getParent().getFileName().toString();
            return new SkillContent(skillId, name, body);
        });
    }

    private SkillDefinition toDefinition(Path skillFile) {
        String id = skillFile.getParent().getFileName().toString();
        return new SkillDefinition(id, id, skillFile.toUri());
    }

    private Path resolveSafe(String skillId) {
        Path resolved = root.resolve(skillId).resolve(SKILL_FILE).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Access denied: skill path escapes root");
        }
        return resolved;
    }
}
