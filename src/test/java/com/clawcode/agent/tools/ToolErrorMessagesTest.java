package com.clawcode.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolErrorMessagesTest {

    @Test
    void denied_containsToolNameAndGuidance() {
        String msg = ToolErrorMessages.denied("file_read", "not in allowlist");
        assertThat(msg).contains("file_read");
        assertThat(msg).contains("Do not retry the same tool call");
        assertThat(msg).contains("use an advertised tool or explain the limitation to the user");
    }

    @Test
    void denied_includesPolicyReason() {
        String msg = ToolErrorMessages.denied("powershell", "blocked by policy");
        assertThat(msg).contains("blocked by policy");
    }

    @Test
    void denied_withBlankPolicyReason_omitsReason() {
        String msg = ToolErrorMessages.denied("powershell", "");
        assertThat(msg).doesNotContain(": ");
        assertThat(msg).contains("Do not retry the same tool call");
    }

    @Test
    void denied_withNullPolicyReason_omitsReason() {
        String msg = ToolErrorMessages.denied("powershell", null);
        assertThat(msg).doesNotContain(": ");
        assertThat(msg).contains("Do not retry the same tool call");
    }

    @Test
    void unknown_containsToolNameAndGuidance() {
        String msg = ToolErrorMessages.unknown("weird_tool");
        assertThat(msg).contains("weird_tool");
        assertThat(msg).contains("Do not retry the same tool call");
        assertThat(msg).contains("use an advertised tool or explain the limitation to the user");
    }

    @Test
    void isDenied_deniedMessageReturnsTrue() {
        assertThat(ToolErrorMessages.isDenied(
            ToolErrorMessages.denied("file_write", "not in allowlist"))).isTrue();
    }

    @Test
    void isDenied_deniedWithoutReasonReturnsTrue() {
        assertThat(ToolErrorMessages.isDenied(
            ToolErrorMessages.denied("file_write", null))).isTrue();
    }

    @Test
    void isDenied_unknownMessageReturnsFalse() {
        assertThat(ToolErrorMessages.isDenied(
            ToolErrorMessages.unknown("weird_tool"))).isFalse();
    }

    @Test
    void isDenied_readBeforeWriteErrorReturnsFalse() {
        assertThat(ToolErrorMessages.isDenied(
            "Refusing write: read the existing file with file_read first.")).isFalse();
    }

    @Test
    void isDenied_staleEditErrorReturnsFalse() {
        assertThat(ToolErrorMessages.isDenied(
            "Refusing edit: file changed since it was read.")).isFalse();
    }

    @Test
    void isDenied_nullReturnsFalse() {
        assertThat(ToolErrorMessages.isDenied(null)).isFalse();
    }

    @Test
    void isUnknown_unknownMessageReturnsTrue() {
        assertThat(ToolErrorMessages.isUnknown(
            ToolErrorMessages.unknown("weird_tool"))).isTrue();
    }

    @Test
    void isUnknown_deniedMessageReturnsFalse() {
        assertThat(ToolErrorMessages.isUnknown(
            ToolErrorMessages.denied("file_read", "blocked"))).isFalse();
    }

    @Test
    void isUnknown_nullReturnsFalse() {
        assertThat(ToolErrorMessages.isUnknown(null)).isFalse();
    }

    @Test
    void isPermissionDenial_deniedReturnsTrue() {
        assertThat(ToolErrorMessages.isPermissionDenial(
            ToolErrorMessages.denied("file_write", "not allowed"))).isTrue();
    }

    @Test
    void isPermissionDenial_unknownReturnsTrue() {
        assertThat(ToolErrorMessages.isPermissionDenial(
            ToolErrorMessages.unknown("weird_tool"))).isTrue();
    }

    @Test
    void isPermissionDenial_readBeforeWriteReturnsFalse() {
        assertThat(ToolErrorMessages.isPermissionDenial(
            "Refusing write: read the existing file with file_read first.")).isFalse();
    }

    @Test
    void isPermissionDenial_staleEditReturnsFalse() {
        assertThat(ToolErrorMessages.isPermissionDenial(
            "Refusing edit: file changed since it was read.")).isFalse();
    }

    @Test
    void isPermissionDenial_nullReturnsFalse() {
        assertThat(ToolErrorMessages.isPermissionDenial(null)).isFalse();
    }

    @Test
    void asciiOnly() {
        assertThat(ToolErrorMessages.denied("file_read", "some reason"))
            .matches("\\A\\p{ASCII}*\\z");
        assertThat(ToolErrorMessages.denied("file_read", ""))
            .matches("\\A\\p{ASCII}*\\z");
        assertThat(ToolErrorMessages.denied("file_read", null))
            .matches("\\A\\p{ASCII}*\\z");
        assertThat(ToolErrorMessages.unknown("weird_tool"))
            .matches("\\A\\p{ASCII}*\\z");
    }
}
