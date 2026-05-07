package com.clawcode.agent.cli.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliToolSummaryFormatterTest {

    @Test
    void fileList_success() {
        String summary = "[{path=src, type=directory, name=src}, {path=pom.xml, type=file, name=pom.xml}]";
        assertThat(CliToolSummaryFormatter.format("file_list", summary, false))
            .isEqualTo("2 entries");
    }

    @Test
    void fileList_singleEntry() {
        assertThat(CliToolSummaryFormatter.format("file_list", "[{path=a.txt, type=file, name=a.txt}]", false))
            .isEqualTo("1 entry");
    }

    @Test
    void fileGlob_success() {
        String summary = "[{path=src/App.java}, {path=src/Util.java}, {path=README.md}]";
        assertThat(CliToolSummaryFormatter.format("file_glob", summary, false))
            .isEqualTo("3 files matched");
    }

    @Test
    void fileGlob_noMatches() {
        assertThat(CliToolSummaryFormatter.format("file_glob", "[]", false))
            .isEqualTo("no matches");
    }

    @Test
    void fileSearch_success() {
        String summary = "[src/App.java:10:TODO, src/Util.java:5:FIXME]";
        assertThat(CliToolSummaryFormatter.format("file_search", summary, false))
            .isEqualTo("2 results");
    }

    @Test
    void fileSearch_noMatches() {
        assertThat(CliToolSummaryFormatter.format("file_search", "[]", false))
            .isEqualTo("no matches");
    }

    @Test
    void fileSearch_newlineResults() {
        String summary = "src/App.java:10:TODO\nsrc/App.java:20:HACK\nsrc/Util.java:5:FIXME";
        assertThat(CliToolSummaryFormatter.format("file_search", summary, false))
            .isEqualTo("3 results");
    }

    @Test
    void fileSearchBracketSummaryWithCommaInMatchCountsEntriesNotCommas() {
        String summary = "[src/App.java:10:foo, bar, src/B.java:2:baz]";
        assertThat(CliToolSummaryFormatter.format("file_search", summary, false))
            .isEqualTo("2 results");
    }

    @Test
    void fileSearchBracketSummaryWithCommaAndColonInMatchCountsEntriesNotFragments() {
        String summary = "[src/App.java:10:foo, bar: baz, src/B.java:2:baz]";
        assertThat(CliToolSummaryFormatter.format("file_search", summary, false))
            .isEqualTo("2 results");
    }

    @Test
    void fileSearchBracket_singleResult() {
        assertThat(CliToolSummaryFormatter.format("file_search", "[src/App.java:10:TODO]", false))
            .isEqualTo("1 result");
    }

    @Test
    void fileRead_shortContent() {
        assertThat(CliToolSummaryFormatter.format("file_read", "hello world", false))
            .isEqualTo("hello world");
    }

    @Test
    void fileRead_multiline() {
        String content = "line1\nline2\nline3\nline4\nline5";
        assertThat(CliToolSummaryFormatter.format("file_read", content, false))
            .isEqualTo("5 lines");
    }

    @Test
    void fileRead_singleLine() {
        assertThat(CliToolSummaryFormatter.format("file_read", "line1", false))
            .isEqualTo("line1");
    }

    @Test
    void fileWrite_createSummary() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Created target/out.txt (42 chars)", false))
            .isEqualTo("Created (42 chars)");
    }

    @Test
    void fileWrite_overwriteSummary() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Overwrote src/main.java (15 -> 23 chars, 4 changed lines)", false))
            .isEqualTo("Overwrote (15 -> 23 chars, 4 changed lines)");
    }

    @Test
    void fileWrite_createWithLongPath() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Created /a/very/long/path/to/the/destination/file.txt (9999 chars)", false))
            .isEqualTo("Created (9999 chars)");
    }

    @Test
    void fileWrite_overwriteZeroChars() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Overwrote /dev/null (10 -> 0 chars, 1 changed lines)", false))
            .isEqualTo("Overwrote (10 -> 0 chars, 1 changed lines)");
    }

    @Test
    void fileWrite_unexpectedFormat_fallsBackToTruncated() {
        assertThat(CliToolSummaryFormatter.format("file_write", "something unexpected here", false))
            .isEqualTo("something unexpected here");
    }

    @Test
    void fileEdit_success() {
        assertThat(CliToolSummaryFormatter.format("file_edit",
            "Edited src/Main.java (15 -> 23 chars, 4 changed lines)", false))
            .isEqualTo("Edited (15 -> 23 chars, 4 changed lines)");
    }

    @Test
    void fileEdit_noMatchError() {
        assertThat(CliToolSummaryFormatter.format("file_edit",
            "Refusing edit 'target/file.txt': old_text was not found. Read the file and provide an exact snippet.",
            true))
            .contains("old_text was not found");
    }

    @Test
    void fileEdit_multipleMatchError() {
        assertThat(CliToolSummaryFormatter.format("file_edit",
            "Refusing edit 'target/file.txt': old_text matched 3 times. Provide a more specific snippet.",
            true))
            .contains("old_text matched 3 times");
    }

    @Test
    void fileEdit_staleDenied() {
        assertThat(CliToolSummaryFormatter.format("file_edit",
            "Refusing stale edit 'target/file.txt': file changed since it was read. Read it again with file_read before editing.",
            true))
            .contains("Refusing stale edit");
    }

    @Test
    void fileEdit_unexpectedFormat_fallsBackToTruncated() {
        assertThat(CliToolSummaryFormatter.format("file_edit", "something unexpected here", false))
            .isEqualTo("something unexpected here");
    }

    @Test
    void fileEdit_nullSummary() {
        assertThat(CliToolSummaryFormatter.format("file_edit", null, false))
            .isEqualTo("ok");
    }

    @Test
    void fileEdit_error_null() {
        assertThat(CliToolSummaryFormatter.format("file_edit", null, true))
            .isEqualTo("failed");
    }

    @Test
    void fileEdit_error_truncated() {
        String longError = "e".repeat(300);
        String result = CliToolSummaryFormatter.format("file_edit", longError, true);
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }

    @Test
    void fileEdit_longSuccess_truncatedFallback() {
        String longUnexpected = "x".repeat(200);
        String result = CliToolSummaryFormatter.format("file_edit", longUnexpected, false);
        assertThat(result).hasSize(83);
        assertThat(result).endsWith("...");
    }

    @Test
    void powershell_singleLine() {
        assertThat(CliToolSummaryFormatter.format("powershell", "Hello World", false))
            .isEqualTo("Hello World");
    }

    @Test
    void powershell_multiline() {
        assertThat(CliToolSummaryFormatter.format("powershell", "line1\nline2\nline3", false))
            .isEqualTo("3 lines");
    }

    @Test
    void powershell_structuredJsonShowsStdoutAndStderrSeparately() {
        String summary = """
            {"command":"git status","exitCode":0,"stdout":"line1\\nline2","stderr":"warning","timedOut":false}
            """.strip();

        assertThat(CliToolSummaryFormatter.format("powershell", summary, false))
            .isEqualTo("stdout: 2 lines; stderr: warning");
    }

    @Test
    void powershell_structuredRecordShowsStdoutAndStderrSeparately() {
        String summary = "PowerShellResult[command=git status, exitCode=0, stdout=clean, stderr=warning, timedOut=false]";

        assertThat(CliToolSummaryFormatter.format("powershell", summary, false))
            .isEqualTo("stdout: clean; stderr: warning");
    }

    @Test
    void powershell_structuredEmptyStreamsRemainLabeled() {
        String summary = "PowerShellResult[command=git status, exitCode=0, stdout=, stderr=, timedOut=false]";

        assertThat(CliToolSummaryFormatter.format("powershell", summary, false))
            .isEqualTo("stdout: empty; stderr: empty");
    }

    @Test
    void powershell_empty() {
        assertThat(CliToolSummaryFormatter.format("powershell", "", false))
            .isEqualTo("ok");
    }

    @Test
    void fileWrite_error_staysGeneric() {
        assertThat(CliToolSummaryFormatter.format("file_write", "Permission denied", true))
            .isEqualTo("Permission denied");
    }

    @Test
    void powershell_error_staysGeneric() {
        assertThat(CliToolSummaryFormatter.format("powershell", "Exit code 1", true))
            .isEqualTo("Exit code 1");
    }

    @Test
    void fileWrite_deniedOverwriteWithoutRead() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Refusing to overwrite 'target/file.txt': read the existing file with file_read first, then retry with an updated plan.",
            true))
            .contains("Refusing to overwrite");
    }

    @Test
    void fileWrite_staleDenied() {
        assertThat(CliToolSummaryFormatter.format("file_write",
            "Refusing stale overwrite 'target/file.txt': file changed since it was read. Read it again with file_read before writing.",
            true))
            .contains("Refusing stale overwrite");
    }

    @Test
    void fileWrite_truncatedFallback() {
        String longUnexpected = "x".repeat(200);
        String result = CliToolSummaryFormatter.format("file_write", longUnexpected, false);
        assertThat(result).hasSize(83);
        assertThat(result).endsWith("...");
    }

    @Test
    void powershell_gitStatusOutput() {
        String summary = "On branch master\nYour branch is up to date\n\nnothing to commit";
        assertThat(CliToolSummaryFormatter.format("powershell", summary, false))
            .isEqualTo("4 lines");
    }

    @Test
    void powershell_getDateOutput() {
        // Single-line output like Get-Date produces
        assertThat(CliToolSummaryFormatter.format("powershell", "Thursday, April 29, 2026 3:22:50 PM", false))
            .isEqualTo("Thursday, April 29, 2026 3:22:50 PM");
    }

    @Test
    void powershell_longSingleLine_truncated() {
        String longLine = "a".repeat(200);
        String result = CliToolSummaryFormatter.format("powershell", longLine, false);
        assertThat(result).hasSize(83);
        assertThat(result).endsWith("...");
    }

    @Test
    void powershell_manyLines() {
        String summary = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10";
        assertThat(CliToolSummaryFormatter.format("powershell", summary, false))
            .isEqualTo("10 lines");
    }

    @Test
    void powershell_outputEndingWithNewline() {
        // Tools sometimes return output with trailing newline; lines.count() still works
        assertThat(CliToolSummaryFormatter.format("powershell", "line1\nline2\n", false))
            .isEqualTo("2 lines");
    }

    @Test
    void fileWrite_error_truncated() {
        String longError = "e".repeat(300);
        String result = CliToolSummaryFormatter.format("file_write", longError, true);
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }

    @Test
    void powershell_error_truncated() {
        String longError = "e".repeat(300);
        String result = CliToolSummaryFormatter.format("powershell", longError, true);
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }

    @Test
    void fileWrite_error_null() {
        assertThat(CliToolSummaryFormatter.format("file_write", null, true))
            .isEqualTo("failed");
    }

    @Test
    void powershell_error_null() {
        assertThat(CliToolSummaryFormatter.format("powershell", null, true))
            .isEqualTo("failed");
    }

    @Test
    void fileWrite_success_null() {
        assertThat(CliToolSummaryFormatter.format("file_write", null, false))
            .isEqualTo("ok");
    }

    @Test
    void powershell_success_null() {
        assertThat(CliToolSummaryFormatter.format("powershell", null, false))
            .isEqualTo("ok");
    }

    @Test
    void error_nullSummary() {
        assertThat(CliToolSummaryFormatter.format("file_read", null, true))
            .isEqualTo("failed");
    }

    @Test
    void success_nullSummary() {
        assertThat(CliToolSummaryFormatter.format("file_read", null, false))
            .isEqualTo("ok");
    }

    @Test
    void unknownTool_usesGenericFormat() {
        assertThat(CliToolSummaryFormatter.format("custom_tool", "some output", false))
            .isEqualTo("some output");
    }

    @Test
    void longSummary_truncated() {
        String longText = "x".repeat(300);
        String result = CliToolSummaryFormatter.format("custom_tool", longText, false);
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }

    @Test
    void longErrorSummary_truncated() {
        String longText = "e".repeat(300);
        String result = CliToolSummaryFormatter.format("file_read", longText, true);
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }
}
