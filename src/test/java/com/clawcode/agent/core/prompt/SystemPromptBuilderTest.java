package com.clawcode.agent.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.clawcode.agent.tools.ToolDefinition;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTest {

    private static final Instant NOW = Instant.parse("2026-04-27T12:00:00Z");
    private static final Path CWD = Path.of("/project");
    private static final ToolDefinition FILE_READ = new ToolDefinition("file_read", "Read files", Map.of());
    private static final ToolDefinition FILE_WRITE = new ToolDefinition("file_write", "Write files", Map.of());
    private static final ToolDefinition FILE_EDIT = new ToolDefinition("file_edit", "Edit files", Map.of());
    private static final ToolDefinition POWERSHELL = new ToolDefinition("powershell", "Run commands", Map.of());

    private final SystemPromptBuilder builder = new SystemPromptBuilder();

    private RuntimePromptContext ctx(ToolDefinition... tools) {
        return new RuntimePromptContext(NOW, CWD, List.of(CWD), List.of(tools));
    }

    @Test
    void defaultPromptIsNonBlank() {
        String result = builder.build(null, null, ctx());
        assertThat(result).isNotBlank();
    }

    @Test
    void containsRuntimeSnapshot() {
        String result = builder.build(null, null, ctx());
        assertThat(result).contains("## Environment");
        assertThat(result).contains("- Working directory: " + CWD.toAbsolutePath().normalize());
        assertThat(result).contains("- Allowed roots:");
    }

    @Test
    void containsCurrentTime() {
        String result = builder.build(null, null, ctx());
        assertThat(result).contains("- Current time: ");
    }

    @Test
    void advertisedToolsListed() {
        String result = builder.build(null, null, ctx(FILE_READ));
        assertThat(result).contains("## Available Tools");
        assertThat(result).contains("- **file_read**: Read files");
    }

    @Test
    void noToolsOmitsToolsSection() {
        String result = builder.build(null, null, ctx());
        assertThat(result).doesNotContain("## Available Tools");
    }

    @Test
    void truthfulReportingPresent() {
        String result = builder.build(null, null, ctx());
        assertThat(result).contains("## Behavioral Rules");
        assertThat(result).contains("## Truthful Reporting");
        assertThat(result).contains("Do not claim an action was performed unless a corresponding tool call confirms it");
        assertThat(result).contains("I cannot perform this action in the current mode");
    }

    @Test
    void customPromptAppended() {
        String result = builder.build("Be concise", null, ctx());
        assertThat(result).contains("## Custom Instructions");
        assertThat(result).contains("Be concise");
    }

    @Test
    void blankCustomPromptIgnored() {
        String result = builder.build("   ", null, ctx());
        assertThat(result).doesNotContain("## Custom Instructions");
    }

    @Test
    void nullCustomPromptIgnored() {
        String result = builder.build(null, null, ctx());
        assertThat(result).doesNotContain("## Custom Instructions");
    }

    @Test
    void skillContextAppended() {
        String result = builder.build(null, "review: code review skill", ctx());
        assertThat(result).contains("## Active Skills");
        assertThat(result).contains("review: code review skill");
    }

    @Test
    void blankSkillContextIgnored() {
        String result = builder.build(null, "  ", ctx());
        assertThat(result).doesNotContain("## Active Skills");
    }

    @Test
    void skillsAfterRuntimeContract() {
        String result = builder.build(null, "skill-content", ctx());
        int rulesIndex = result.indexOf("## Behavioral Rules");
        int skillsIndex = result.indexOf("## Active Skills");
        assertThat(rulesIndex).isGreaterThan(0);
        assertThat(skillsIndex).isGreaterThan(rulesIndex);
    }

    @Test
    void customBeforeSkills() {
        String result = builder.build("custom", "skills", ctx());
        int customIndex = result.indexOf("## Custom Instructions");
        int skillsIndex = result.indexOf("## Active Skills");
        assertThat(customIndex).isGreaterThan(0);
        assertThat(skillsIndex).isGreaterThan(customIndex);
    }

    @Test
    void deterministicSectionOrdering() {
        String result = builder.build("custom", "skills", ctx(FILE_READ, FILE_EDIT));
        int role = result.indexOf("You are a coding assistant");
        int env = result.indexOf("## Environment");
        int tools = result.indexOf("## Available Tools");
        int guidance = result.indexOf("## Tool Selection Guidance");
        int rules = result.indexOf("## Behavioral Rules");
        int truthful = result.indexOf("## Truthful Reporting");
        int restrictions = result.indexOf("## Capability Restrictions");
        int custom = result.indexOf("## Custom Instructions");
        int skills = result.indexOf("## Active Skills");

        assertThat(role).isLessThan(env);
        assertThat(env).isLessThan(tools);
        assertThat(tools).isLessThan(guidance);
        assertThat(guidance).isLessThan(rules);
        assertThat(rules).isLessThan(truthful);
        assertThat(truthful).isLessThan(restrictions);
        assertThat(restrictions).isLessThan(custom);
        assertThat(custom).isLessThan(skills);
    }

    @Test
    void capabilityGuards_bothPresent_noRestrictions() {
        String result = builder.build(null, null, ctx(FILE_WRITE, POWERSHELL));
        assertThat(result).doesNotContain("## Capability Restrictions");
    }

    @Test
    void capabilityGuards_noWrite_hasFileWarning() {
        String result = builder.build(null, null, ctx(POWERSHELL));
        assertThat(result).contains("## Capability Restrictions");
        assertThat(result).contains("You cannot edit, create, or delete files");
        assertThat(result).doesNotContain("You cannot run commands");
    }

    @Test
    void capabilityGuards_noShell_hasShellWarning() {
        String result = builder.build(null, null, ctx(FILE_WRITE));
        assertThat(result).contains("## Capability Restrictions");
        assertThat(result).contains("You cannot run commands, builds, tests, or git operations");
        assertThat(result).doesNotContain("You cannot edit, create, or delete files");
    }

    @Test
    void capabilityGuards_neither_bothWarnings() {
        String result = builder.build(null, null, ctx(FILE_READ));
        assertThat(result).contains("## Capability Restrictions");
        assertThat(result).contains("You cannot edit, create, or delete files");
        assertThat(result).contains("You cannot run commands, builds, tests, or git operations");
    }

    @Test
    void toolSelectionGuidancePresentWhenFileEditAdvertised() {
        String result = builder.build(null, null, ctx(FILE_READ, FILE_EDIT, FILE_WRITE));
        assertThat(result).contains("## Tool Selection Guidance");
        assertThat(result).contains("prefer **file_edit**");
        assertThat(result).contains("Use **file_write** only for new files or intentional full replacement");
    }

    @Test
    void toolSelectionGuidanceAbsentWhenFileEditNotAdvertised() {
        String result = builder.build(null, null, ctx(FILE_READ, FILE_WRITE));
        assertThat(result).doesNotContain("## Tool Selection Guidance");
        assertThat(result).doesNotContain("prefer");
    }

    @Test
    void capabilityGuards_fileEditOnly_doesNotSayCannotEdit() {
        String result = builder.build(null, null, ctx(FILE_READ, FILE_EDIT));
        assertThat(result).contains("## Tool Selection Guidance");
        assertThat(result).contains("## Capability Restrictions");
        assertThat(result).doesNotContain("You cannot edit");
        assertThat(result).contains("no file_write capability");
        assertThat(result).contains("Targeted edits via **file_edit** are available");
    }

    @Test
    void capabilityGuards_noFileWriteOrEdit_hasFileWarning() {
        String result = builder.build(null, null, ctx(FILE_READ));
        assertThat(result).contains("## Capability Restrictions");
        assertThat(result).contains("You cannot edit, create, or delete files");
    }
}
