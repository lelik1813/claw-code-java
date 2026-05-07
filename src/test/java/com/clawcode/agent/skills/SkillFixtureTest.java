package com.clawcode.agent.skills;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFixtureTest {

    private static final String SKILLS_ROOT = "src/test/resources/skills";

    @Test
    void loadsTranslatorSkill() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));

        StepVerifier.create(registry.read("translator"))
            .assertNext(content -> {
                assertThat(content.id()).isEqualTo("translator");
                assertThat(content.body()).contains("translation assistant");
                assertThat(content.body()).contains("French");
            })
            .verifyComplete();
    }

    @Test
    void loadsSummarizerSkill() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));

        StepVerifier.create(registry.read("summarizer"))
            .assertNext(content -> {
                assertThat(content.id()).isEqualTo("summarizer");
                assertThat(content.body()).contains("summarization assistant");
            })
            .verifyComplete();
    }

    @Test
    void listsAllSkills() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));

        StepVerifier.create(registry.list().collectList())
            .assertNext(list -> {
                assertThat(list).hasSize(3);
                assertThat(list.stream().map(SkillDefinition::id).toList())
                    .containsExactlyInAnyOrder("broken", "summarizer", "translator");
            })
            .verifyComplete();
    }

    @Test
    void missingSkillReturnsError() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));

        StepVerifier.create(registry.read("nonexistent"))
            .expectError()
            .verify();
    }

    @Test
    void skillContextServiceAssemblesMultipleSkills() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));
        var service = new SkillContextService(registry);

        StepVerifier.create(service.loadSkillContext(List.of("translator", "summarizer")))
            .assertNext(context -> {
                assertThat(context).contains("--- Skill: translator ---");
                assertThat(context).contains("--- Skill: summarizer ---");
                assertThat(context).contains("translation assistant");
                assertThat(context).contains("summarization assistant");
            })
            .verifyComplete();
    }

    @Test
    void skillContextServiceHandlesUnknownSkill() {
        var registry = new FileSystemSkillRegistry(
            new SkillsProperties(true, SKILLS_ROOT));
        var service = new SkillContextService(registry);

        StepVerifier.create(service.loadSkillContext(List.of("translator", "nonexistent")))
            .assertNext(context -> {
                assertThat(context).contains("--- Skill: translator ---");
                assertThat(context).contains("[ERROR: skill 'nonexistent' not found]");
            })
            .verifyComplete();
    }
}
