package com.clawcode.agent.skills;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SkillsAutoConfiguration {

    @Bean
    SkillContextService skillContextService(
            @Autowired(required = false) SkillRegistry registry) {
        return new SkillContextService(registry);
    }
}
