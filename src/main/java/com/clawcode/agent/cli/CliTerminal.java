package com.clawcode.agent.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class CliTerminal {

    private CliTerminal() {}

    static void installUtf8Console() {
        if (isWindows()) {
            try {
                new ProcessBuilder("cmd", "/c", "chcp", "65001", ">", "NUL")
                    .inheritIO()
                    .start()
                    .waitFor();
            } catch (Exception ignored) {
                // Best-effort only; the JVM streams below still use UTF-8.
            }
        }
        System.setOut(new PrintStream(
            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(
            new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    }
}
