package com.clawcode.agent.skills;

import java.net.URI;

public record SkillDefinition(
    String id,
    String name,
    URI location
) {}
