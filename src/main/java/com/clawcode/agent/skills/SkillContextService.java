package com.clawcode.agent.skills;

import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class SkillContextService {

    static final int MAX_SKILL_CONTENT_CHARS = 32_000;

    private final SkillRegistry registry;

    public SkillContextService(SkillRegistry registry) {
        this.registry = registry;
    }

    public Mono<String> loadSkillContext(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Mono.just("");
        }
        return Flux.fromIterable(skillIds)
            .flatMap(this::loadSkill)
            .collectList()
            .map(this::assemble);
    }

    private Mono<LoadedSkill> loadSkill(String id) {
        if (registry == null) {
            return Mono.just(new LoadedSkill(id, "[ERROR: skills subsystem not enabled]"));
        }
        return registry.read(id)
            .map(content -> new LoadedSkill(
                content.id(),
                content.body().length() > MAX_SKILL_CONTENT_CHARS
                    ? content.body().substring(0, MAX_SKILL_CONTENT_CHARS) + "\n... [truncated]"
                    : content.body()
            ))
            .onErrorResume(e -> Mono.just(new LoadedSkill(
                id, "[ERROR: skill '" + id + "' not found]"
            )));
    }

    private String assemble(List<LoadedSkill> skills) {
        StringBuilder sb = new StringBuilder();
        for (LoadedSkill skill : skills) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("--- Skill: ").append(skill.id).append(" ---\n");
            sb.append(skill.body);
        }
        return sb.toString();
    }

    private record LoadedSkill(String id, String body) {}
}
