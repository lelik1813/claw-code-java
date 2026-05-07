package com.clawcode.agent.core.prompt;

import com.clawcode.agent.tools.ToolDefinition;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SystemPromptBuilder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public String build(String customPrompt, String skillContext, RuntimePromptContext ctx) {
        StringBuilder sb = new StringBuilder();

        appendRole(sb);
        appendRuntimeSnapshot(sb, ctx);
        appendAdvertisedTools(sb, ctx);
        appendToolSelectionGuidance(sb, ctx);
        appendTruthfulReporting(sb);
        appendCapabilityGuards(sb, ctx);
        appendCustomPrompt(sb, customPrompt);
        appendSkillContext(sb, skillContext);

        return sb.toString();
    }

    private void appendRole(StringBuilder sb) {
        sb.append("You are a coding assistant running inside an agent server.\n");
        sb.append("You receive user messages, reason about them, and use tools when needed.\n\n");
    }

    private void appendRuntimeSnapshot(StringBuilder sb, RuntimePromptContext ctx) {
        sb.append("## Environment\n\n");
        sb.append("- Current time: ").append(TS.format(ctx.now().atZone(ZoneId.systemDefault()))).append('\n');
        sb.append("- Working directory: ").append(ctx.cwd().toAbsolutePath().normalize()).append('\n');
        sb.append("- Allowed roots:\n");
        for (Path root : ctx.allowedRoots()) {
            sb.append("  - ").append(root).append('\n');
        }
        sb.append('\n');
    }

    private void appendAdvertisedTools(StringBuilder sb, RuntimePromptContext ctx) {
        if (ctx.advertisedTools().isEmpty()) {
            return;
        }
        sb.append("## Available Tools\n\n");
        for (ToolDefinition tool : ctx.advertisedTools()) {
            sb.append("- **").append(tool.name()).append("**: ")
              .append(tool.description()).append('\n');
        }
        sb.append('\n');
    }

    private void appendToolSelectionGuidance(StringBuilder sb, RuntimePromptContext ctx) {
        if (!ctx.hasFileEdit()) {
            return;
        }
        sb.append("## Tool Selection Guidance\n\n");
        sb.append("When you need to modify an existing file, prefer **file_edit** for targeted ");
        sb.append("changes after reading it with **file_read**. ");
        sb.append("Use **file_write** only for new files or intentional full replacement.\n\n");
    }

    private void appendTruthfulReporting(StringBuilder sb) {
        sb.append("## Behavioral Rules\n\n");
        sb.append("Report tool results truthfully. Never fabricate or infer outcomes.\n\n");
        sb.append("## Truthful Reporting\n\n");
        sb.append("Do not claim an action was performed unless a corresponding tool call confirms it.\n");
        sb.append("This applies to file edits, test execution, builds, git operations, and shell commands.\n");
        sb.append("If you cannot perform an action in the current mode, state explicitly:\n");
        sb.append("\"I cannot perform this action in the current mode.\"\n");
        sb.append("Then offer a concrete alternative: a patch, a command the user can run, or a next step.\n\n");
    }

    private void appendCapabilityGuards(StringBuilder sb, RuntimePromptContext ctx) {
        boolean hasWrite = ctx.hasFileWrite();
        boolean hasEdit = ctx.hasFileEdit();
        boolean hasShell = ctx.hasPowerShell();

        if (hasWrite && hasShell) {
            return;
        }

        sb.append("## Capability Restrictions\n\n");
        if (!hasWrite && !hasEdit) {
            sb.append("- You cannot edit, create, or delete files. Do not claim file changes were made.\n");
        } else if (hasEdit && !hasWrite) {
            sb.append("- You cannot create, fully overwrite, or delete files (no file_write capability). ")
              .append("Targeted edits via **file_edit** are available.\n");
        }
        if (!hasShell) {
            sb.append("- You cannot run commands, builds, tests, or git operations. Do not claim they were executed.\n");
        }
        sb.append('\n');
    }

    private void appendCustomPrompt(StringBuilder sb, String customPrompt) {
        if (customPrompt == null || customPrompt.isBlank()) {
            return;
        }
        sb.append("## Custom Instructions\n\n").append(customPrompt).append("\n\n");
    }

    private void appendSkillContext(StringBuilder sb, String skillContext) {
        if (skillContext == null || skillContext.isBlank()) {
            return;
        }
        sb.append("## Active Skills\n\n").append(skillContext).append('\n');
    }
}
