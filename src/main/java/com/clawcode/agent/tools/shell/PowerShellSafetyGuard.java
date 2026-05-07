package com.clawcode.agent.tools.shell;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PowerShellSafetyGuard {

    private static final Set<String> DESTRUCTIVE_FILESYSTEM_COMMANDS = Set.of(
        "remove-item", "rm", "rmdir", "del", "erase"
    );

    private static final Set<String> INTERACTIVE_COMMANDS = Set.of(
        "powershell", "pwsh", "cmd", "bash", "sh",
        "ssh", "ftp", "telnet",
        "enter-pssession", "read-host", "pause"
    );

    private PowerShellSafetyGuard() {}

    static void rejectUnsafeCommand(String command) {
        rejectUnsafeCommand(command, false);
    }

    static void rejectUnsafeCommand(String command, boolean explicitDestructiveApproval) {
        if (!explicitDestructiveApproval) {
            rejectDestructiveFilesystem(command);
            rejectDestructiveGit(command);
        }
        rejectInteractive(command);
    }

    static void rejectInteractive(String command) {
        for (String segment : commandSegments(command)) {
            String base = PowerShellCommandParser.baseCommand(segment);
            if (base.isBlank()) {
                continue;
            }
            String normalized = base.toLowerCase(Locale.ROOT);
            if (INTERACTIVE_COMMANDS.contains(normalized)) {
                throw denied(base);
            }
            if (("python".equals(normalized) || "node".equals(normalized))
                && !hasNonInteractiveArgs(segment, normalized)) {
                throw denied(base);
            }
        }
    }

    static void rejectDestructiveFilesystem(String command) {
        for (String segment : commandSegments(command)) {
            List<String> tokens = PowerShellCommandTokenizer.tokens(segment);
            int commandIndex = commandTokenIndex(tokens);
            if (commandIndex < 0) {
                continue;
            }

            String base = PowerShellCommandParser.baseCommand(segment);
            if (base.isBlank()) {
                continue;
            }
            String normalized = base.toLowerCase(Locale.ROOT);
            if (DESTRUCTIVE_FILESYSTEM_COMMANDS.contains(normalized)
                || isBroadMoveItem(normalized, tokens, commandIndex)
                || isCmdDestructiveFilesystem(tokens, commandIndex)) {
                throw destructiveDenied(base);
            }
        }
    }

    static void rejectDestructiveGit(String command) {
        for (String segment : commandSegments(command)) {
            List<String> tokens = PowerShellCommandTokenizer.tokens(segment);
            int commandIndex = commandTokenIndex(tokens);
            if (commandIndex < 0) {
                continue;
            }

            String base = PowerShellCommandParser.baseCommand(segment);
            if (!"git".equalsIgnoreCase(base)) {
                continue;
            }
            if (isDestructiveGit(tokens, commandIndex)) {
                throw destructiveGitDenied(segment);
            }
        }
    }

    private static SecurityException denied(String baseCommand) {
        return new SecurityException("Interactive command denied: " + baseCommand);
    }

    private static SecurityException destructiveDenied(String baseCommand) {
        return new SecurityException(
            "Destructive filesystem command denied: " + baseCommand
                + ". Explicit user request is required. Do not retry without explicit approval.");
    }

    private static SecurityException destructiveGitDenied(String segment) {
        return new SecurityException(
            "Destructive git command denied: " + segment.strip()
                + ". Explicit user request is required. Do not retry without explicit approval.");
    }

    private static boolean hasNonInteractiveArgs(String segment, String baseCommand) {
        List<String> tokens = PowerShellCommandTokenizer.tokens(segment);
        int commandIndex = commandTokenIndex(tokens);
        if (commandIndex < 0 || commandIndex + 1 >= tokens.size()) {
            return false;
        }

        String firstArg = tokens.get(commandIndex + 1).toLowerCase(Locale.ROOT);
        if ("python".equals(baseCommand)) {
            return firstArg.equals("-c") || firstArg.equals("-m") || !firstArg.startsWith("-");
        }
        if ("node".equals(baseCommand)) {
            return firstArg.equals("-e") || firstArg.equals("--eval") || !firstArg.startsWith("-");
        }
        return true;
    }

    private static boolean isBroadMoveItem(String normalized, List<String> tokens, int commandIndex) {
        if (!"move-item".equals(normalized)) {
            return false;
        }
        for (int i = commandIndex + 1; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            if (token.contains("*") || token.equals("/") || token.equals("\\")
                || token.matches("[a-z]:\\\\?")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCmdDestructiveFilesystem(List<String> tokens, int commandIndex) {
        String base = tokens.get(commandIndex).toLowerCase(Locale.ROOT);
        if (!"cmd".equals(base) || commandIndex + 2 >= tokens.size()) {
            return false;
        }
        String firstArg = tokens.get(commandIndex + 1).toLowerCase(Locale.ROOT);
        if (!"/c".equals(firstArg) && !"/k".equals(firstArg)) {
            return false;
        }
        String nested = tokens.get(commandIndex + 2).toLowerCase(Locale.ROOT);
        if (nested.equals("del") || nested.equals("erase")) {
            return true;
        }
        if (nested.equals("rd") || nested.equals("rmdir")) {
            return tokens.subList(commandIndex + 3, tokens.size()).stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .anyMatch("/s"::equals);
        }
        return false;
    }

    private static boolean isDestructiveGit(List<String> tokens, int commandIndex) {
        if (commandIndex + 1 >= tokens.size()) {
            return false;
        }

        String subcommand = tokens.get(commandIndex + 1).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "reset" -> hasToken(tokens, commandIndex + 2, "--hard");
            case "clean" -> hasCleanForceDelete(tokens, commandIndex + 2);
            case "checkout" -> hasDoubleDashPath(tokens, commandIndex + 2);
            case "restore", "rebase" -> true;
            case "branch" -> hasToken(tokens, commandIndex + 2, "-D");
            case "push" -> hasForcePush(tokens, commandIndex + 2);
            case "gc" -> hasToken(tokens, commandIndex + 2, "--prune=now");
            default -> false;
        };
    }

    private static boolean hasCleanForceDelete(List<String> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            if (token.equals("-fd") || token.equals("-df")
                || token.equals("-fdx") || token.equals("-fxd")
                || token.equals("-dfx") || token.equals("-dxf")
                || token.equals("-xfd") || token.equals("-xdf")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDoubleDashPath(List<String> tokens, int start) {
        for (int i = start; i < tokens.size() - 1; i++) {
            if ("--".equals(tokens.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForcePush(List<String> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            if (token.equals("--force") || token.equals("--force-with-lease") || token.equals("-f")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToken(List<String> tokens, int start, String expected) {
        for (int i = start; i < tokens.size(); i++) {
            if (tokens.get(i).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static int commandTokenIndex(List<String> tokens) {
        if (tokens.isEmpty()) {
            return -1;
        }
        if ("&".equals(tokens.get(0))) {
            return tokens.size() > 1 ? 1 : -1;
        }
        return 0;
    }

    private static List<String> commandSegments(String command) {
        return PowerShellCommandTokenizer.segments(command);
    }
}
