package com.clawcode.agent.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PowerShellCommandParserTest {

    @Test
    void parsesGitStatus() {
        assertThat(PowerShellCommandParser.baseCommand("git status")).isEqualTo("git");
    }

    @Test
    void parsesLeadingWhitespace() {
        assertThat(PowerShellCommandParser.baseCommand("   Get-ChildItem src")).isEqualTo("Get-ChildItem");
    }

    @Test
    void parsesDotSlashMvnw() {
        assertThat(PowerShellCommandParser.baseCommand("./mvnw --version")).isEqualTo("mvnw");
    }

    @Test
    void parsesDotBackslashMvnwCmd() {
        assertThat(PowerShellCommandParser.baseCommand(".\\mvnw.cmd test")).isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesUnquotedRelativeMvnwCmdPath() {
        assertThat(PowerShellCommandParser.baseCommand(" .\\mvnw.cmd test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesUnquotedPathAfterCallOperator() {
        assertThat(PowerShellCommandParser.baseCommand("& .\\mvnw.cmd test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesAbsoluteWindowsPath() {
        assertThat(PowerShellCommandParser.baseCommand("C:\\repo\\project\\mvnw.cmd test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesQuotedPath() {
        assertThat(PowerShellCommandParser.baseCommand("\"C:\\repo with spaces\\mvnw.cmd\" test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesQuotedPathAfterCallOperator() {
        assertThat(PowerShellCommandParser.baseCommand("& \"C:\\repo with spaces\\mvnw.cmd\" test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void parsesSingleQuotedPathAfterCallOperator() {
        assertThat(PowerShellCommandParser.baseCommand("& '.\\mvnw.cmd' test"))
            .isEqualTo("mvnw.cmd");
    }

    @Test
    void normalizesExeExtension() {
        assertThat(PowerShellCommandParser.baseCommand("\"C:\\Program Files\\Git\\cmd\\git.exe\" status"))
            .isEqualTo("git");
    }

    @Test
    void normalizesExeExtensionCaseInsensitively() {
        assertThat(PowerShellCommandParser.baseCommand("C:\\Tools\\git.EXE status"))
            .isEqualTo("git");
    }

    @Test
    void preservesUnknownCommand() {
        assertThat(PowerShellCommandParser.baseCommand("Invoke-WebRequest http://example.test"))
            .isEqualTo("Invoke-WebRequest");
    }

    @Test
    void blankCommandReturnsEmptyBase() {
        assertThat(PowerShellCommandParser.baseCommand("   ")).isEmpty();
    }
}
