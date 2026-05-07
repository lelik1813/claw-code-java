package com.clawcode.agent.cli.commands;

public final class CliToolSummaryFormatter {

    private static final int MAX_SUMMARY = 200;
    private static final java.util.regex.Pattern SEARCH_ENTRY =
        java.util.regex.Pattern.compile("(^|,\\s*)[^,\\s][^,]*?:\\d+:");

    private CliToolSummaryFormatter() {}

    public static String format(String toolName, String rawSummary, boolean isError) {
        if (rawSummary == null || rawSummary.isBlank()) {
            return isError ? "failed" : "ok";
        }
        if (isError) {
            return truncate(rawSummary, MAX_SUMMARY);
        }
        return switch (toolName) {
            case "file_list" -> formatFileList(rawSummary);
            case "file_glob" -> formatFileGlob(rawSummary);
            case "file_search" -> formatFileSearch(rawSummary);
            case "file_read" -> formatFileRead(rawSummary);
            case "file_edit" -> formatFileEdit(rawSummary);
            case "file_write" -> formatFileWrite(rawSummary);
            case "powershell" -> formatPowerShell(rawSummary);
            default -> truncate(rawSummary, MAX_SUMMARY);
        };
    }

    private static String formatFileList(String summary) {
        int entries = countChar(summary, '{');
        return entries + " " + plural(entries, "entry", "entries");
    }

    private static String formatFileGlob(String summary) {
        if (summary.startsWith("[")) {
            int entries = countChar(summary, '{');
            if (entries == 0) return "no matches";
            return entries + " " + plural(entries, "file", "files") + " matched";
        }
        return truncate(summary, MAX_SUMMARY);
    }

    private static String formatFileSearch(String summary) {
        if (summary.startsWith("[")) {
            String trimmed = summary.substring(1).stripLeading();
            if (trimmed.startsWith("]")) return "no matches";
            int entries = countSearchEntries(summary);
            if (entries == 0) entries = 1;
            return entries + " " + plural(entries, "result", "results");
        }
        long lines = summary.lines().count();
        if (lines == 0) return "no matches";
        return lines + " " + plural((int) lines, "result", "results");
    }

    private static int countSearchEntries(String summary) {
        String content = summary;
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1);
        }
        int count = 0;
        var matcher = SEARCH_ENTRY.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String formatFileRead(String summary) {
        long lines = summary.lines().count();
        if (lines <= 1) return truncate(summary, 80);
        return lines + " " + plural((int) lines, "line", "lines");
    }

    private static final java.util.regex.Pattern CREATED_PATTERN =
        java.util.regex.Pattern.compile("^Created\\s.*?\\((\\d+\\s+chars)\\)$");
    private static final java.util.regex.Pattern OVERWROTE_PATTERN =
        java.util.regex.Pattern.compile("^Overwrote\\s.*?\\((\\d+\\s+->\\s+\\d+\\s+chars,\\s+\\d+\\s+changed\\s+lines)\\)$");
    private static final java.util.regex.Pattern EDITED_PATTERN =
        java.util.regex.Pattern.compile("^Edited\\s.*?\\((\\d+\\s+->\\s+\\d+\\s+chars,\\s+\\d+\\s+changed\\s+lines)\\)$");

    private static String formatFileEdit(String summary) {
        var edited = EDITED_PATTERN.matcher(summary);
        if (edited.matches()) {
            return "Edited (" + edited.group(1) + ")";
        }
        return truncate(summary, 80);
    }

    private static String formatFileWrite(String summary) {
        var created = CREATED_PATTERN.matcher(summary);
        if (created.matches()) {
            return "Created (" + created.group(1) + ")";
        }
        var overwrote = OVERWROTE_PATTERN.matcher(summary);
        if (overwrote.matches()) {
            return "Overwrote (" + overwrote.group(1) + ")";
        }
        return truncate(summary, 80);
    }

    private static String formatPowerShell(String summary) {
        PowerShellStreams streams = parsePowerShellStreams(summary);
        if (streams != null) {
            return formatPowerShellStreams(streams);
        }
        long lines = summary.lines().count();
        if (lines <= 1) return truncate(summary, 80);
        return lines + " " + plural((int) lines, "line", "lines");
    }

    private static PowerShellStreams parsePowerShellStreams(String summary) {
        String trimmed = summary.stripLeading();
        if (trimmed.startsWith("PowerShellResult[")) {
            String stdout = recordField(trimmed, "stdout=", ", stderr=");
            String stderr = recordField(trimmed, "stderr=", ", timedOut=");
            if (stdout != null && stderr != null) {
                return new PowerShellStreams(stdout, stderr);
            }
        }
        if (trimmed.startsWith("{") && trimmed.contains("\"stdout\"") && trimmed.contains("\"stderr\"")) {
            String stdout = jsonStringField(trimmed, "stdout");
            String stderr = jsonStringField(trimmed, "stderr");
            if (stdout != null && stderr != null) {
                return new PowerShellStreams(stdout, stderr);
            }
        }
        return null;
    }

    private static String formatPowerShellStreams(PowerShellStreams streams) {
        return formatStream("stdout", streams.stdout()) + "; " + formatStream("stderr", streams.stderr());
    }

    private static String formatStream(String label, String value) {
        if (value == null || value.isBlank()) {
            return label + ": empty";
        }
        String stripped = value.strip();
        long lines = stripped.lines().count();
        if (lines <= 1) {
            return label + ": " + truncate(stripped, 80);
        }
        return label + ": " + lines + " " + plural((int) lines, "line", "lines");
    }

    private static String recordField(String summary, String startToken, String endToken) {
        int start = summary.indexOf(startToken);
        if (start < 0) return null;
        start += startToken.length();
        int end = summary.indexOf(endToken, start);
        if (end < 0) return null;
        return summary.substring(start, end);
    }

    private static String jsonStringField(String summary, String field) {
        int key = summary.indexOf("\"" + field + "\"");
        if (key < 0) return null;
        int colon = summary.indexOf(':', key);
        if (colon < 0) return null;
        int quote = summary.indexOf('"', colon + 1);
        if (quote < 0) return null;

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quote + 1; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (escaped) {
                value.append(unescapeJsonChar(c));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }

    private static char unescapeJsonChar(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> c;
        };
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static String plural(int n, String singular, String plural) {
        return n == 1 ? singular : plural;
    }

    private record PowerShellStreams(String stdout, String stderr) {}
}
