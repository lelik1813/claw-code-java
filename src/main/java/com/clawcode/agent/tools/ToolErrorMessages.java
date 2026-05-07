package com.clawcode.agent.tools;

public final class ToolErrorMessages {

    private static final String GUIDANCE =
        " Do not retry the same tool call; use an advertised tool or explain the limitation to the user.";

    private ToolErrorMessages() {}

    public static String denied(String toolName, String policyReason) {
        String base = "Tool '" + toolName + "' is denied";
        if (policyReason != null && !policyReason.isBlank()) {
            base += ": " + policyReason;
        }
        return base + "." + GUIDANCE;
    }

    public static String deniedByHook(String toolName, String hookReason) {
        String base = "Tool '" + toolName + "' is denied by hook";
        if (hookReason != null && !hookReason.isBlank()) {
            base += ": " + hookReason;
        }
        return base + "." + GUIDANCE;
    }

    public static String unknown(String toolName) {
        return "Unknown tool: '" + toolName + "'." + GUIDANCE;
    }

    public static boolean isDenied(String message) {
        return message != null && message.startsWith("Tool '") && message.contains("' is denied");
    }

    public static boolean isUnknown(String message) {
        return message != null && message.startsWith("Unknown tool: '");
    }

    public static boolean isPermissionDenial(String message) {
        return isDenied(message) || isUnknown(message);
    }
}
