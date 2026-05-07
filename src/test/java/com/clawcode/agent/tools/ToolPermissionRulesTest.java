package com.clawcode.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPermissionRulesTest {

    @Test
    void disabled_allowsAllTools() {
        var props = new ToolPermissionProperties(false, ToolPermissionProperties.Mode.ALLOWLIST, Set.of("file_read"));
        assertThat(ToolPermissionRules.isAllowed(props, "powershell")).isTrue();
        assertThat(ToolPermissionRules.isAllowed(props, "unknown")).isTrue();
    }

    @Test
    void allowlist_listedToolIsAllowed() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST, Set.of("file_read", "file_list"));
        assertThat(ToolPermissionRules.isAllowed(props, "file_read")).isTrue();
        assertThat(ToolPermissionRules.isAllowed(props, "file_list")).isTrue();
    }

    @Test
    void allowlist_unlistedToolIsDenied() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST, Set.of("file_read"));
        assertThat(ToolPermissionRules.isAllowed(props, "powershell")).isFalse();
        assertThat(ToolPermissionRules.isAllowed(props, "file_write")).isFalse();
    }

    @Test
    void denylist_listedToolIsDenied() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST, Set.of("powershell", "file_write"));
        assertThat(ToolPermissionRules.isAllowed(props, "powershell")).isFalse();
        assertThat(ToolPermissionRules.isAllowed(props, "file_write")).isFalse();
    }

    @Test
    void denylist_unlistedToolIsAllowed() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST, Set.of("powershell"));
        assertThat(ToolPermissionRules.isAllowed(props, "file_read")).isTrue();
        assertThat(ToolPermissionRules.isAllowed(props, "file_list")).isTrue();
    }

    @Test
    void nullAllowedToolsIsTreatedAsEmptySet() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST, null);
        assertThat(ToolPermissionRules.isAllowed(props, "anything")).isFalse();

        var propsDeny = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST, null);
        assertThat(ToolPermissionRules.isAllowed(propsDeny, "anything")).isTrue();
    }

    @Test
    void emptyAllowedToolsInAllowlistDeniesAll() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.ALLOWLIST, Set.of());
        assertThat(ToolPermissionRules.isAllowed(props, "file_read")).isFalse();
        assertThat(ToolPermissionRules.isAllowed(props, "file_write")).isFalse();
    }

    @Test
    void emptyAllowedToolsInDenylistAllowsAll() {
        var props = new ToolPermissionProperties(true, ToolPermissionProperties.Mode.DENYLIST, Set.of());
        assertThat(ToolPermissionRules.isAllowed(props, "file_read")).isTrue();
        assertThat(ToolPermissionRules.isAllowed(props, "file_write")).isTrue();
    }
}
