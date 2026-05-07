package com.clawcode.agent.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.skills")
public record SkillsProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("./skills") String root
) {

    public SkillsProperties(boolean enabled, String root) {
        this.enabled = enabled;
        this.root = root != null && !root.isBlank() ? root : "./skills";
    }
}
