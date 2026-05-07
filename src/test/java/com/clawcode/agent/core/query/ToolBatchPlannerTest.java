package com.clawcode.agent.core.query;

import com.clawcode.agent.tools.ToolUseRequest;
import com.clawcode.agent.core.query.ToolBatchPlan.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolBatchPlannerTest {

    private static ToolUseRequest req(String toolName) {
        return new ToolUseRequest("call_1", toolName, null);
    }

    private static ToolUseRequest req(String toolName, String callId) {
        return new ToolUseRequest(callId, toolName, null);
    }

    @Test
    void emptyListProducesEmptyPlan() {
        var plan = ToolBatchPlanner.plan(List.of());
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.groups()).isEmpty();
    }

    @Test
    void nullListProducesEmptyPlan() {
        var plan = ToolBatchPlanner.plan(null);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void allReadOnlyToolsMergedIntoSingleParallelGroup() {
        var plan = ToolBatchPlanner.plan(List.of(
            req("file_read"),
            req("file_search"),
            req("file_list")
        ));

        assertThat(plan.groups()).hasSize(1);
        assertThat(plan.groups().getFirst().mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.groups().getFirst().toolCalls()).hasSize(3);
        assertThat(plan.planMode()).isEqualTo(ToolBatchPlan.PlanMode.ALL_PARALLEL_READ_ONLY);
    }

    @Test
    void allSerialToolsEachGetOwnGroup() {
        var plan = ToolBatchPlanner.plan(List.of(
            req("file_write"),
            req("file_edit"),
            req("powershell")
        ));

        assertThat(plan.groups()).hasSize(3);
        for (var group : plan.groups()) {
            assertThat(group.mode()).isEqualTo(Mode.SERIAL);
            assertThat(group.toolCalls()).hasSize(1);
        }
        assertThat(plan.planMode()).isEqualTo(ToolBatchPlan.PlanMode.ALL_SERIAL);
    }

    @Test
    void mixedOrderProducesExpectedGroups() {
        var plan = ToolBatchPlanner.plan(List.of(
            req("file_read", "c1"),
            req("file_search", "c2"),
            req("file_write", "c3"),
            req("file_read", "c4"),
            req("powershell", "c5"),
            req("file_glob", "c6")
        ));

        assertThat(plan.groups()).hasSize(5);

        // Group 0: parallel read-only {c1, c2}
        assertThat(plan.groups().get(0).mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.groups().get(0).toolCalls()).extracting(ToolUseRequest::toolCallId)
            .containsExactly("c1", "c2");

        // Group 1: serial write {c3}
        assertThat(plan.groups().get(1).mode()).isEqualTo(Mode.SERIAL);
        assertThat(plan.groups().get(1).toolCalls()).extracting(ToolUseRequest::toolCallId)
            .containsExactly("c3");

        // Group 2: parallel read-only {c4}
        assertThat(plan.groups().get(2).mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.groups().get(2).toolCalls()).extracting(ToolUseRequest::toolCallId)
            .containsExactly("c4");

        // Group 3: serial powershell {c5}
        assertThat(plan.groups().get(3).mode()).isEqualTo(Mode.SERIAL);
        assertThat(plan.groups().get(3).toolCalls()).extracting(ToolUseRequest::toolCallId)
            .containsExactly("c5");

        // Group 4: parallel read-only {c6}
        assertThat(plan.groups().get(4).mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.groups().get(4).toolCalls()).extracting(ToolUseRequest::toolCallId)
            .containsExactly("c6");

        assertThat(plan.planMode()).isEqualTo(ToolBatchPlan.PlanMode.MIXED);
    }

    @Test
    void unknownToolCreatesSerialBoundary() {
        var plan = ToolBatchPlanner.plan(List.of(
            req("file_read"),
            req("unknown_tool"),
            req("file_read")
        ));

        assertThat(plan.groups()).hasSize(3);
        assertThat(plan.groups().get(0).mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.groups().get(1).mode()).isEqualTo(Mode.SERIAL);
        assertThat(plan.groups().get(1).toolCalls()).extracting(ToolUseRequest::toolName)
            .containsExactly("unknown_tool");
        assertThat(plan.groups().get(2).mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
    }

    @Test
    void singleReadOnlyCallProducesSingleGroup() {
        var plan = ToolBatchPlanner.plan(List.of(req("file_read")));

        assertThat(plan.groups()).hasSize(1);
        assertThat(plan.groups().getFirst().mode()).isEqualTo(Mode.PARALLEL_READ_ONLY);
        assertThat(plan.planMode()).isEqualTo(ToolBatchPlan.PlanMode.ALL_PARALLEL_READ_ONLY);
    }

    @Test
    void singleSerialCallProducesSingleGroup() {
        var plan = ToolBatchPlanner.plan(List.of(req("file_edit")));

        assertThat(plan.groups()).hasSize(1);
        assertThat(plan.groups().getFirst().mode()).isEqualTo(Mode.SERIAL);
        assertThat(plan.planMode()).isEqualTo(ToolBatchPlan.PlanMode.ALL_SERIAL);
    }

    @Test
    void preservesOriginalCallOrderAcrossGroups() {
        var plan = ToolBatchPlanner.plan(List.of(
            req("file_list", "c1"),
            req("file_write", "c2"),
            req("file_edit", "c3")
        ));

        var allCalls = plan.groups().stream()
            .flatMap(g -> g.toolCalls().stream())
            .map(ToolUseRequest::toolCallId)
            .toList();

        assertThat(allCalls).containsExactly("c1", "c2", "c3");
    }
}
