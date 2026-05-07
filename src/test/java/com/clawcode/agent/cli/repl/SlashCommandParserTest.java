package com.clawcode.agent.cli.repl;

import com.clawcode.agent.cli.repl.SlashCommandParser.ParseResult;
import com.clawcode.agent.cli.repl.SlashCommandParser.Type;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SlashCommandParser — verifies parsing of plain text,
 * valid slash commands, aliases, invalid slash, and edge cases.
 */
class SlashCommandParserTest {

    // ══════════════════════════════════════════════════════════════
    //  PLAIN TEXT
    // ══════════════════════════════════════════════════════════════

    @Nested
    class PlainText {

        @Test
        void regularText_isPlainText() {
            ParseResult r = SlashCommandParser.parse("hello world");
            assertThat(r.type()).isEqualTo(Type.PLAIN_TEXT);
            assertThat(r.isPlainText()).isTrue();
            assertThat(r.raw()).isEqualTo("hello world");
        }

        @Test
        void textWithLeadingSpace_isPlainText() {
            ParseResult r = SlashCommandParser.parse("  not a slash command  ");
            assertThat(r.isPlainText()).isTrue();
            assertThat(r.raw()).isEqualTo("not a slash command");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  VALID SLASH COMMANDS
    // ══════════════════════════════════════════════════════════════

    @Nested
    class ValidSlash {

        @Test
        void slashWithCommand() {
            ParseResult r = SlashCommandParser.parse("/help");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("help");
            assertThat(r.args()).isEqualTo("");
        }

        @Test
        void slashWithCommandAndArgs() {
            ParseResult r = SlashCommandParser.parse("/session new");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("session");
            assertThat(r.args()).contains("new");
        }

        @Test
        void slashWithMultiWordArgs() {
            ParseResult r = SlashCommandParser.parse("/session abc-123 extra");
            assertThat(r.commandName()).isEqualTo("session");
            assertThat(r.args()).contains("abc-123 extra");
        }

        @Test
        void slashCaseInsensitive() {
            ParseResult r = SlashCommandParser.parse("/HELP");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).contains("help");
        }

        @Test
        void slashMixedCase() {
            ParseResult r = SlashCommandParser.parse("/Session");
            assertThat(r.commandName()).isEqualTo("session");
        }

        @Test
        void slashExtraSpaces() {
            ParseResult r = SlashCommandParser.parse("/  help  me  ");
            assertThat(r.commandName()).contains("help");
            assertThat(r.args()).contains("me");
        }

        @Test
        void allBuiltinCommands() {
            for (String cmd : List.of("help", "exit", "quit", "session", "attach", "history", "clear")) {
                ParseResult r = SlashCommandParser.parse("/" + cmd);
                assertThat(r.isSlash()).as("/" + cmd).isTrue();
                assertThat(r.commandName()).contains(cmd);
            }
        }

        @Test
        void allDispatcherCommands() {
            // Commands handled by SlashCommandDispatcher that are valid names
            for (String cmd : List.of("stream", "replay", "mcp", "plugin")) {
                ParseResult r = SlashCommandParser.parse("/" + cmd);
                assertThat(r.isSlash()).as("/" + cmd).isTrue();
                assertThat(r.commandName()).isEqualTo(cmd);
            }
        }

        @Test
        void mcpWithSubcommandArgs() {
            ParseResult r = SlashCommandParser.parse("/mcp add test --url http://localhost:3000");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("mcp");
            assertThat(r.args()).isEqualTo("add test --url http://localhost:3000");
        }

        @Test
        void pluginWithSubcommandArgs() {
            ParseResult r = SlashCommandParser.parse("/plugin install /some/path");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("plugin");
            assertThat(r.args()).isEqualTo("install /some/path");
        }

        @Test
        void replayWithSessionArg() {
            ParseResult r = SlashCommandParser.parse("/replay s-123");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("replay");
            assertThat(r.args()).isEqualTo("s-123");
        }

        @Test
        void streamWithSessionArg() {
            ParseResult r = SlashCommandParser.parse("/stream s-456");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("stream");
            assertThat(r.args()).isEqualTo("s-456");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ALIASES
    // ══════════════════════════════════════════════════════════════

    @Nested
    class Aliases {

        @Test
        void q_resolvesToExit() {
            ParseResult r = SlashCommandParser.parse("/q");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).contains("exit");
        }

        @Test
        void h_resolvesToHelp() {
            ParseResult r = SlashCommandParser.parse("/h");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).contains("help");
        }

        @Test
        void questionMark_resolvesToHelp() {
            ParseResult r = SlashCommandParser.parse("/?");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).contains("help");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INVALID SLASH
    // ══════════════════════════════════════════════════════════════

    @Nested
    class InvalidSlash {

        @Test
        void bareSlash() {
            ParseResult r = SlashCommandParser.parse("/");
            assertThat(r.isInvalidSlash()).isTrue();
            assertThat(r.hasError()).isTrue();
            assertThat(r.error()).contains("empty command");
        }

        @Test
        void slashOnlySpaces() {
            ParseResult r = SlashCommandParser.parse("/   ");
            assertThat(r.isInvalidSlash()).isTrue();
        }

        @Test
        void unknownSlash_isStillSlashType() {
            ParseResult r = SlashCommandParser.parse("/custom-cmd arg1");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).contains("custom-cmd");
            assertThat(r.args()).contains("arg1");
        }

        @Test
        void slashWithNumberStart_isInvalid() {
            ParseResult r = SlashCommandParser.parse("/1abc");
            assertThat(r.isInvalidSlash()).isTrue();
        }

        @Test
        void slashWithSpecialChar_isInvalid() {
            ParseResult r = SlashCommandParser.parse("/@mention");
            assertThat(r.isInvalidSlash()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ══════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void nullInput_returnsEmpty() {
            ParseResult r = SlashCommandParser.parse(null);
            assertThat(r.type()).isEqualTo(Type.EMPTY);
        }

        @Test
        void blankInput_returnsEmpty() {
            ParseResult r = SlashCommandParser.parse("   ");
            assertThat(r.type()).isEqualTo(Type.EMPTY);
        }

        @Test
        void emptyString_returnsEmpty() {
            ParseResult r = SlashCommandParser.parse("");
            assertThat(r.type()).isEqualTo(Type.EMPTY);
        }

        @Test
        void rawPreserved() {
            ParseResult r = SlashCommandParser.parse("/session   new");
            assertThat(r.raw()).isEqualTo("/session   new");
        }

        @Test
        void slashWithHyphenatedName() {
            ParseResult r = SlashCommandParser.parse("/my-command arg");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("my-command");
            assertThat(r.args()).isEqualTo("arg");
        }

        @Test
        void slashWithUnderscoredName() {
            ParseResult r = SlashCommandParser.parse("/my_command");
            assertThat(r.isSlash()).isTrue();
            assertThat(r.commandName()).isEqualTo("my_command");
        }
    }
}
