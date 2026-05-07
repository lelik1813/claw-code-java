package com.clawcode.agent.tools.shell;

import java.util.ArrayList;
import java.util.List;

final class PowerShellCommandTokenizer {

    private PowerShellCommandTokenizer() {}

    static List<String> segments(String command) {
        List<String> segments = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return segments;
        }

        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                current.append(ch);
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (ch == ';' || ch == '|') {
                addSegment(segments, current);
                continue;
            }
            current.append(ch);
        }
        addSegment(segments, current);
        return segments;
    }

    static List<String> tokens(String command) {
        List<String> tokens = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return tokens;
        }

        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                addToken(tokens, current);
                continue;
            }
            if (ch == '&') {
                addToken(tokens, current);
                tokens.add("&");
                continue;
            }
            current.append(ch);
        }
        addToken(tokens, current);
        return tokens;
    }

    private static void addSegment(List<String> segments, StringBuilder current) {
        String segment = current.toString().strip();
        if (!segment.isEmpty()) {
            segments.add(segment);
        }
        current.setLength(0);
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
