package com.clawcode.agent.tools.shell;

final class PowerShellCommandParser {

    private PowerShellCommandParser() {}

    static String baseCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }

        int index = skipWhitespace(command, 0);
        Token first = readToken(command, index);
        if (first.value().isEmpty()) {
            return "";
        }
        if ("&".equals(first.value())) {
            first = readToken(command, skipWhitespace(command, first.end()));
        }

        return normalizeBase(first.value());
    }

    private static int skipWhitespace(String command, int index) {
        int i = index;
        while (i < command.length() && Character.isWhitespace(command.charAt(i))) {
            i++;
        }
        return i;
    }

    private static Token readToken(String command, int index) {
        if (index >= command.length()) {
            return new Token("", index);
        }

        char first = command.charAt(index);
        if (first == '"' || first == '\'') {
            return readQuotedToken(command, index + 1, first);
        }
        if (first == '&') {
            return new Token("&", index + 1);
        }

        int i = index;
        StringBuilder token = new StringBuilder();
        while (i < command.length()) {
            char ch = command.charAt(i);
            if (Character.isWhitespace(ch)) {
                break;
            }
            token.append(ch);
            i++;
        }
        return new Token(token.toString(), i);
    }

    private static Token readQuotedToken(String command, int index, char quote) {
        int i = index;
        StringBuilder token = new StringBuilder();
        while (i < command.length()) {
            char ch = command.charAt(i);
            if (ch == quote) {
                return new Token(token.toString(), i + 1);
            }
            token.append(ch);
            i++;
        }
        return new Token(token.toString(), i);
    }

    private static String normalizeBase(String token) {
        String normalized = token.strip();
        if (normalized.isEmpty()) {
            return "";
        }

        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }

        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".exe")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        if (lower.endsWith(".cmd")) {
            return normalized.substring(0, normalized.length() - 4) + ".cmd";
        }
        if (lower.endsWith(".bat")) {
            return normalized.substring(0, normalized.length() - 4) + ".bat";
        }
        return normalized;
    }

    private record Token(String value, int end) {
    }
}
