package com.clawcode.agent.tools;

public sealed interface ToolPermissionDecision {

    record Allow() implements ToolPermissionDecision {}

    record Deny(String reason) implements ToolPermissionDecision {
        public Deny() {
            this("Tool execution denied by policy");
        }
    }
}
