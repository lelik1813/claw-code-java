package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolExecutionClassifier;
import com.clawcode.agent.tools.ToolUseRequest;
import com.clawcode.agent.core.query.ToolBatchPlan.Group;
import com.clawcode.agent.core.query.ToolBatchPlan.Mode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link ToolBatchPlan} from an ordered list of tool call requests.
 *
 * <p>Adjacent read-only calls are merged into a single {@link Mode#PARALLEL_READ_ONLY}
 * group. Each serial-boundary call (write, edit, shell, unknown) starts its own
 * {@link Mode#SERIAL} group. After a serial group a new read-only group may begin.
 */
public final class ToolBatchPlanner {

    public static ToolBatchPlan plan(List<ToolUseRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return new ToolBatchPlan(List.of());
        }

        var groups = new ArrayList<Group>();
        List<ToolUseRequest> pendingReadOnly = null;

        for (var request : requests) {
            if (ToolExecutionClassifier.isReadOnly(request.toolName())) {
                if (pendingReadOnly == null) {
                    pendingReadOnly = new ArrayList<>();
                }
                pendingReadOnly.add(request);
            } else {
                if (pendingReadOnly != null) {
                    groups.add(new Group(Mode.PARALLEL_READ_ONLY, pendingReadOnly));
                    pendingReadOnly = null;
                }
                groups.add(new Group(Mode.SERIAL, List.of(request)));
            }
        }

        if (pendingReadOnly != null) {
            groups.add(new Group(Mode.PARALLEL_READ_ONLY, pendingReadOnly));
        }

        return new ToolBatchPlan(groups);
    }

    private ToolBatchPlanner() {
    }
}
