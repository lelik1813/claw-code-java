package com.clawcode.agent.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clawcode.agent.tools.ToolDefinition;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimePromptContextTest {

    private static final ToolDefinition FILE_READ = new ToolDefinition("file_read", "read", Map.of());
    private static final ToolDefinition FILE_WRITE = new ToolDefinition("file_write", "write", Map.of());
    private static final ToolDefinition POWERSHELL = new ToolDefinition("powershell", "ps", Map.of());

    @Test
    void defensiveCopyOfAllowedRoots() {
        var roots = new ArrayList<>(List.of(Path.of("/tmp")));
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("/tmp"), roots, List.of());
        roots.add(Path.of("/evil"));
        assertThat(ctx.allowedRoots()).hasSize(1);
    }

    @Test
    void defensiveCopyOfAdvertisedTools() {
        var tools = new ArrayList<>(List.of(FILE_READ));
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), tools);
        tools.add(FILE_WRITE);
        assertThat(ctx.advertisedTools()).hasSize(1);
    }

    @Test
    void hasToolMatchesByName() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), List.of(FILE_READ));
        assertThat(ctx.hasTool("file_read")).isTrue();
        assertThat(ctx.hasTool("file_write")).isFalse();
    }

    @Test
    void hasFileWriteTrue() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), List.of(FILE_WRITE));
        assertThat(ctx.hasFileWrite()).isTrue();
    }

    @Test
    void hasFileWriteFalse() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), List.of(FILE_READ));
        assertThat(ctx.hasFileWrite()).isFalse();
    }

    @Test
    void hasPowerShellTrue() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), List.of(POWERSHELL));
        assertThat(ctx.hasPowerShell()).isTrue();
    }

    @Test
    void hasPowerShellFalse() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(), List.of(FILE_READ));
        assertThat(ctx.hasPowerShell()).isFalse();
    }

    @Test
    void returnedListsAreImmutable() {
        var ctx = new RuntimePromptContext(Instant.now(), Path.of("."), List.of(Path.of("/a")), List.of(FILE_READ));
        assertThatThrownBy(() -> ctx.allowedRoots().add(Path.of("/x")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ctx.advertisedTools().add(FILE_WRITE))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
