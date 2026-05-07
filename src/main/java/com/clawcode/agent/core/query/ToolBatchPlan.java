package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolUseRequest;
import java.util.List;

/**
 * Immutable batch execution plan partitioning tool calls into ordered groups.
 *
 * <p>Each group runs in one of two modes:
 * <ul>
 *   <li>{@link Mode#PARALLEL_READ_ONLY} -- all calls are read-only and may execute concurrently
 *   <li>{@link Mode#SERIAL} -- calls must execute one at a time, preserving group order
 * </ul>
 *
 * <p>Groups are ordered. Within a group, tool call order is preserved.
 * This record models the plan only -- it does not execute tools.
 */
public record ToolBatchPlan(List<Group> groups) {

    public enum Mode {
        PARALLEL_READ_ONLY,
        SERIAL
    }

    public record Group(Mode mode, List<ToolUseRequest> toolCalls) {

        public Group {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public ToolBatchPlan {
        groups = List.copyOf(groups);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public int totalCalls() {
        return groups.stream().mapToInt(g -> g.toolCalls().size()).sum();
    }

    /**
     * Returns the overall execution mode for this plan.
     */
    public PlanMode planMode() {
        if (groups.isEmpty()) return PlanMode.EMPTY;
        boolean allParallel = groups.stream().allMatch(g -> g.mode() == Mode.PARALLEL_READ_ONLY);
        boolean allSerial = groups.stream().allMatch(g -> g.mode() == Mode.SERIAL);
        if (allParallel) return PlanMode.ALL_PARALLEL_READ_ONLY;
        if (allSerial) return PlanMode.ALL_SERIAL;
        return PlanMode.MIXED;
    }

    public enum PlanMode {
        EMPTY,
        ALL_PARALLEL_READ_ONLY,
        ALL_SERIAL,
        MIXED
    }
}
