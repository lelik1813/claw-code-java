package com.clawcode.agent.tools;

import com.clawcode.agent.shared.message.Message;
import com.clawcode.agent.shared.message.UserMessage;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookContext;
import com.clawcode.agent.tools.hooks.ToolPermissionDeniedHookResult;
import com.clawcode.agent.tools.hooks.ToolPostHookContext;
import com.clawcode.agent.tools.hooks.ToolPostHookResult;
import com.clawcode.agent.tools.hooks.ToolPreHookContext;
import com.clawcode.agent.tools.hooks.ToolPreHookResult;
import com.clawcode.agent.tools.hooks.ToolStopHookContext;
import com.clawcode.agent.tools.hooks.ToolStopHookResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolHookDecisionTest {

    private final ToolUseRequest request = new ToolUseRequest("call-1", "echo", "input");
    private final ToolExecutionContext executionContext =
        new ToolExecutionContext("turn-1", "session-1", "model-1", "system");
    private final ToolResult result = ToolResult.success("call-1", "echo", "ok");

    @Test
    void contextsDefensivelyCopyMessages() {
        List<Message> messages = new ArrayList<>();
        Message first = message("first");
        messages.add(first);

        ToolPreHookContext pre = new ToolPreHookContext(request, executionContext, messages);
        ToolPostHookContext post = new ToolPostHookContext(request, executionContext, result, messages);
        ToolPermissionDeniedHookContext denied =
            new ToolPermissionDeniedHookContext(request, executionContext, "denied", messages);
        ToolStopHookContext stop = new ToolStopHookContext("max_tokens", messages);

        messages.add(message("later"));

        assertThat(pre.messages()).containsExactly(first);
        assertThat(post.messages()).containsExactly(first);
        assertThat(denied.messages()).containsExactly(first);
        assertThat(stop.messages()).containsExactly(first);
        assertThatThrownBy(() -> pre.messages().add(message("blocked")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultsDefensivelyCopyMessages() {
        List<Message> messages = new ArrayList<>();
        Message first = message("first");
        messages.add(first);

        ToolPreHookResult pre = ToolPreHookResult.continueWith(request, messages);
        ToolPostHookResult post = ToolPostHookResult.continueWith(result, messages);
        ToolPermissionDeniedHookResult denied = ToolPermissionDeniedHookResult.continueWith(messages);
        ToolStopHookResult retry = ToolStopHookResult.retry(messages);

        messages.add(message("later"));

        assertThat(pre.messages()).containsExactly(first);
        assertThat(post.messages()).containsExactly(first);
        assertThat(denied.messages()).containsExactly(first);
        assertThat(retry.messages()).containsExactly(first);
        assertThatThrownBy(() -> retry.messages().add(message("blocked")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preHookContinueCanReplaceRequestAndAddMessages() {
        ToolUseRequest modified = new ToolUseRequest("call-1", "echo", Map.of("text", "modified"));
        Message contextMessage = message("context");

        ToolPreHookResult result = ToolPreHookResult.continueWith(modified, List.of(contextMessage));

        assertThat(result.decision()).isEqualTo(ToolPreHookResult.Decision.CONTINUE);
        assertThat(result.request()).isSameAs(modified);
        assertThat(result.denyReason()).isNull();
        assertThat(result.messages()).containsExactly(contextMessage);
    }

    @Test
    void preHookDenyRequiresNonBlankReason() {
        assertThat(ToolPreHookResult.deny("blocked", List.of()).decision())
            .isEqualTo(ToolPreHookResult.Decision.DENY);

        assertThatThrownBy(() -> ToolPreHookResult.deny(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("denyReason");
        assertThatThrownBy(() -> ToolPreHookResult.deny(" ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("denyReason");
    }

    @Test
    void stopHookFactoriesExposeDefaultRetryAndFailDecisions() {
        Message retryMessage = message("retry");
        Message failMessage = message("fail");

        ToolStopHookResult defaultResult = ToolStopHookResult.continueDefault();
        ToolStopHookResult retryResult = ToolStopHookResult.retry(List.of(retryMessage));
        ToolStopHookResult failResult =
            ToolStopHookResult.fail("model stopped", "max_tokens", List.of(failMessage));

        assertThat(defaultResult.decision()).isEqualTo(ToolStopHookResult.Decision.CONTINUE_DEFAULT);
        assertThat(defaultResult.messages()).isEmpty();
        assertThat(retryResult.decision()).isEqualTo(ToolStopHookResult.Decision.RETRY);
        assertThat(retryResult.messages()).containsExactly(retryMessage);
        assertThat(failResult.decision()).isEqualTo(ToolStopHookResult.Decision.FAIL);
        assertThat(failResult.message()).isEqualTo("model stopped");
        assertThat(failResult.stopReason()).isEqualTo("max_tokens");
        assertThat(failResult.messages()).containsExactly(failMessage);
    }

    @Test
    void stopHookFailRequiresNonBlankMessageAndStopReason() {
        assertThatThrownBy(() -> ToolStopHookResult.fail(null, "max_tokens", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message");
        assertThatThrownBy(() -> ToolStopHookResult.fail(" ", "max_tokens", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message");
        assertThatThrownBy(() -> ToolStopHookResult.fail("failed", null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stopReason");
        assertThatThrownBy(() -> ToolStopHookResult.fail("failed", " ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stopReason");
    }

    @Test
    void permissionDeniedDefaultFactoryHasNoExtraMessages() {
        ToolPermissionDeniedHookResult result = ToolPermissionDeniedHookResult.continueDefault();

        assertThat(result.overrideReason()).isNull();
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void permissionDeniedOverrideFactoryStoresReasonAndMessages() {
        Message message = message("policy context");

        ToolPermissionDeniedHookResult result =
            ToolPermissionDeniedHookResult.overrideReason("custom denial", List.of(message));

        assertThat(result.overrideReason()).isEqualTo("custom denial");
        assertThat(result.messages()).containsExactly(message);
    }

    @Test
    void permissionDeniedOverrideReasonMustBeNonBlank() {
        assertThatThrownBy(() -> ToolPermissionDeniedHookResult.overrideReason(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overrideReason");
        assertThatThrownBy(() -> ToolPermissionDeniedHookResult.overrideReason(" ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overrideReason");
        assertThatThrownBy(() -> new ToolPermissionDeniedHookResult(" ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overrideReason");
    }

    private static Message message(String content) {
        return new UserMessage(UUID.randomUUID(), Instant.now(), content);
    }
}
