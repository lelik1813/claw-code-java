package com.clawcode.agent.cli.repl;

import java.util.Set;

/**
 * Parses raw REPL input into a structured result, distinguishing
 * slash commands from plain text.
 *
 * <p>Usage:
 * <pre>{@code
 * ParseResult result = SlashCommandParser.parse("/session new");
 * if (result.isSlash()) {
 *     String cmd = result.commandName();   // "session"
 *     String args = result.args();         // "new"
 * }
 * }</pre>
 */
public final class SlashCommandParser {

    private static final Set<String> BUILTIN_COMMANDS = Set.of(
        "help", "exit", "quit", "session", "attach", "history", "clear"
    );

    private static final Set<String> ALIASES = Set.of("q", "h", "?");

    private SlashCommandParser() {}

    public static ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParseResult.empty();
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            return ParseResult.plainText(trimmed);
        }

        String body = trimmed.substring(1).trim();
        if (body.isEmpty()) {
            return ParseResult.invalidSlash(trimmed, "empty command after /");
        }

        String[] parts = body.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        String resolved = resolveAlias(name);
        if (resolved != null) {
            name = resolved;
        }

        if (!isValidName(name)) {
            return ParseResult.invalidSlash(trimmed, "invalid command name: " + name);
        }

        return new ParseResult(Type.SLASH, trimmed, name, args, null);
    }

    private static String resolveAlias(String name) {
        return switch (name) {
            case "q" -> "exit";
            case "h", "?" -> "help";
            default -> null;
        };
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty()) return false;
        if (BUILTIN_COMMANDS.contains(name)) return true;
        return name.matches("[a-z][a-z0-9_-]*");
    }

    // ── result type ─────────────────────────────────────────

    public enum Type {
        EMPTY, PLAIN_TEXT, SLASH, INVALID_SLASH
    }

    public record ParseResult(
        Type type,
        String raw,
        String commandName,
        String args,
        String error
    ) {
        static ParseResult empty() {
            return new ParseResult(Type.EMPTY, "", null, null, null);
        }

        static ParseResult plainText(String raw) {
            return new ParseResult(Type.PLAIN_TEXT, raw, null, null, null);
        }

        static ParseResult invalidSlash(String raw, String error) {
            return new ParseResult(Type.INVALID_SLASH, raw, null, null, error);
        }

        public boolean isSlash() { return type == Type.SLASH; }
        public boolean isPlainText() { return type == Type.PLAIN_TEXT; }
        public boolean isInvalidSlash() { return type == Type.INVALID_SLASH; }

        public boolean hasCommand() { return commandName != null; }
        public boolean hasError() { return error != null; }
    }
}
