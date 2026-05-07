package com.clawcode.agent.cli;

import java.io.PrintWriter;
import java.nio.file.Path;

final class ReplWelcomeRenderer {

    private static final String VERSION = "0.1.0";
    static final String RESET = "\u001B[0m";
    static final String DIM = "\u001B[2m";
    static final String BOLD = "\u001B[1m";
    static final String ORANGE = "\u001B[38;5;208m";
    static final String BLUE = "\u001B[38;5;39m";
    private static final int DEFAULT_WIDTH = 160;

    private ReplWelcomeRenderer() {}

    static void render(PrintWriter out) {
        String[] art = {
            "▄▌ ▅▀▅ ▀▄▀ ▅▀▅ █",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 ",
            "                 "
        };
        String[] info = {
            BOLD + "Free Code Java Agent" + RESET + " " + DIM + "v" + VERSION + RESET,
            modelLine(),
            "~\\" + cwdLine(),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
        };

        for (int i = 0; i < art.length; i++) {
            out.println("  " + ORANGE + art[i] + RESET + "   " + info[i]);
        }
        out.println();
        out.println(DIM + "  ? for shortcuts" + RESET);
        out.println();
    }

    static String inputRule() {
        return DIM + "─".repeat(terminalWidth()) + RESET;
    }

    private static int terminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                return Math.max(80, Math.min(220, Integer.parseInt(columns) - 2));
            } catch (NumberFormatException ignored) {
                // Fall through to the stable default.
            }
        }
        return DEFAULT_WIDTH;
    }

    private static String modelLine() {
        String model = firstNonBlank(
            System.getenv("ANTHROPIC_DEFAULT_SONNET_MODEL"),
            System.getenv("ANTHROPIC_DEFAULT_MODEL"),
            "deepseek-v4-flash");
        return model + " with low effort";
    }

    private static String cwdLine() {
        return truncateMiddle(Path.of("").toAbsolutePath().normalize().toString(), 72);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        return firstNonBlank(first, firstNonBlank(second, fallback));
    }

    private static String truncateMiddle(String text, int width) {
        if (text == null || text.length() <= width) {
            return text == null ? "" : text;
        }
        int left = Math.max(1, width / 2 - 1);
        int right = Math.max(1, width - left - 1);
        return text.substring(0, left) + "…" + text.substring(text.length() - right);
    }
}
