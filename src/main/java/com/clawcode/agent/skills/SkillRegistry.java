package com.clawcode.agent.skills;

import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SkillRegistry {

    Flux<SkillDefinition> list();

    Mono<SkillContent> read(String skillId);
}
