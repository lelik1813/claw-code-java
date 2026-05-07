package com.clawcode.agent.cli.commands.plugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
import com.clawcode.agent.cli.plugin.PluginConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCommandTest {

    @TempDir
    Path tempDir;

    private StringWriter outWriter;
    private StringWriter errWriter;
    private CommandLine cmd;
    private Path pluginConfigFile;

    @BeforeEach
    void setUp() {
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        var app = new AgentCliApplication();
        cmd = new CommandLine(app);
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));

        pluginConfigFile = tempDir.resolve("plugins.json");
        var pluginCmdLine = cmd.getSubcommands().get("plugin");
        var pluginCmd = (PluginCommand) pluginCmdLine.getCommand();
        pluginCmd.store = new FilePluginConfigStore(pluginConfigFile);
    }

    private String out() { return outWriter.toString().trim(); }

    private void clearOutput() {
        outWriter.getBuffer().setLength(0);
    }

    private Path writeManifest(String name, String id, String version) throws IOException {
        var dir = tempDir.resolve("plugin-" + name);
        Files.createDirectories(dir);
        var json = """
            {
              "name": "%s",
              "id": "%s",
              "version": "%s"
            }
            """.formatted(name, id, version);
        Files.writeString(dir.resolve("plugin.json"), json);
        return dir;
    }

    private Path writeManifestFile(String name, String id) throws IOException {
        var json = """
            {
              "name": "%s",
              "id": "%s"
            }
            """.formatted(name, id);
        var file = tempDir.resolve("direct-manifest.json");
        Files.writeString(file, json);
        return file;
    }

    private Path writeBadManifest(String content) throws IOException {
        var dir = tempDir.resolve("bad-plugin");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"), content);
        return dir;
    }

    // ── group routing ───────────────────────────────────────

    @Nested
    class GroupRouting {

        @Test
        void pluginWithoutSubcommand_showsUsage() {
            int exit = cmd.execute("plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("list");
            assertThat(out()).contains("install");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("enable");
            assertThat(out()).contains("disable");
        }

        @Test
        void pluginHelp_showsSubcommands() {
            int exit = cmd.execute("plugin", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Manage plugins");
            assertThat(out()).contains("list");
            assertThat(out()).contains("install");
            assertThat(out()).contains("remove");
            assertThat(out()).contains("enable");
            assertThat(out()).contains("disable");
        }
    }

    // ── list ────────────────────────────────────────────────

    @Nested
    class ListCommand {

        @Test
        void listHelp_showsDescriptionAndJsonOption() {
            int exit = cmd.execute("plugin", "list", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("List installed plugins");
            assertThat(out()).contains("--json");
        }

        @Test
        void listEmpty_printsNoPlugins() {
            int exit = cmd.execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("(no plugins installed)");
        }

        @Test
        void listAfterInstall_tableOutput() throws IOException {
            var pluginDir = writeManifest("alpha", "alpha-v1", "1.0.0");
            cmd.execute("plugin", "install", pluginDir.toString());
            var pluginDir2 = tempDir.resolve("plugin-beta");
            Files.createDirectories(pluginDir2);
            Files.writeString(pluginDir2.resolve("plugin.json"), """
                { "name": "beta", "id": "beta-v2", "version": "2.0.0" }
                """);
            cmd.execute("plugin", "install", pluginDir2.toString(), "--disabled");
            clearOutput();

            int exit = cmd.execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("ID");
            assertThat(out()).contains("ENABLED");
            assertThat(out()).contains("SOURCE");
            assertThat(out()).contains("VERSION");
            assertThat(out()).contains("alpha");
            assertThat(out()).contains("alpha-v1");
            assertThat(out()).contains("yes");
            assertThat(out()).contains("beta");
            assertThat(out()).contains("beta-v2");
            assertThat(out()).contains("no");
        }

        @Test
        void listJsonOutput() throws IOException {
            var pluginDir = writeManifest("gamma", "gamma-v1", "3.0.0");
            cmd.execute("plugin", "install", pluginDir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"gamma\"");
            assertThat(out()).contains("\"id\" : \"gamma-v1\"");
            assertThat(out()).contains("\"source\" : \"PATH\"");
        }
    }

    // ── install ─────────────────────────────────────────────

    @Nested
    class InstallCommand {

        @Test
        void installHelp_showsSourceAndOptions() {
            int exit = cmd.execute("plugin", "install", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("SOURCE");
            assertThat(out()).contains("--name");
            assertThat(out()).contains("--disabled");
        }

        @Test
        void installFromDirectory_happyPath() throws IOException {
            var pluginDir = writeManifest("my-plugin", "my-plugin-v1", "1.0.0");

            int exit = cmd.execute("plugin", "install", pluginDir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'my-plugin'");
            assertThat(out()).contains("id=my-plugin-v1");
        }

        @Test
        void installFromDirectory_immediatelyVisibleInList() throws IOException {
            var pluginDir = writeManifest("visible", "visible-v1", "1.0.0");
            cmd.execute("plugin", "install", pluginDir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("visible");
            assertThat(out()).contains("visible-v1");
            assertThat(out()).contains("yes");
        }

        @Test
        void installFromManifestFile_directPath() throws IOException {
            var manifestFile = writeManifestFile("direct-plugin", "direct-v1");

            int exit = cmd.execute("plugin", "install", manifestFile.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'direct-plugin'");
        }

        @Test
        void installWithDisabled_flag() throws IOException {
            var pluginDir = writeManifest("off-plugin", "off-v1", "1.0.0");

            int exit = cmd.execute("plugin", "install", pluginDir.toString(), "--disabled");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);

            clearOutput();
            cmd.execute("plugin", "list");
            assertThat(out()).contains("off-plugin");
            assertThat(out()).contains("no");
        }

        @Test
        void installWithNameOverride() throws IOException {
            var pluginDir = writeManifest("original", "orig-v1", "1.0.0");

            int exit = cmd.execute("plugin", "install", pluginDir.toString(), "--name", "renamed");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Installed plugin 'renamed'");
        }

        @Test
        void installDuplicate_rejected() throws IOException {
            var pluginDir = writeManifest("dup", "dup-v1", "1.0.0");
            cmd.execute("plugin", "install", pluginDir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "install", pluginDir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
        }

        @Test
        void installDuplicateId_rejected() throws IOException {
            var dir1 = writeManifest("name-a", "shared-id", "1.0.0");
            cmd.execute("plugin", "install", dir1.toString());
            clearOutput();

            var dir2 = tempDir.resolve("plugin-name-b");
            Files.createDirectories(dir2);
            Files.writeString(dir2.resolve("plugin.json"), """
                { "name": "name-b", "id": "shared-id", "version": "2.0.0" }
                """);
            int exit = cmd.execute("plugin", "install", dir2.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("already exists");
            assertThat(out()).contains("shared-id");
        }

        @Test
        void installPathNotExist_usageError() {
            int exit = cmd.execute("plugin", "install", "/no/such/path");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("path does not exist");
        }

        @Test
        void installMissingManifest_usageError() throws IOException {
            var emptyDir = tempDir.resolve("empty-plugin");
            Files.createDirectories(emptyDir);

            int exit = cmd.execute("plugin", "install", emptyDir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest not found");
        }

        @Test
        void installManifestMissingName_validationError() throws IOException {
            var dir = writeBadManifest("""
                { "id": "no-name-v1", "version": "1.0.0" }
                """);

            int exit = cmd.execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest missing required field: name");
        }

        @Test
        void installManifestMissingId_validationError() throws IOException {
            var dir = writeBadManifest("""
                { "name": "no-id", "version": "1.0.0" }
                """);

            int exit = cmd.execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("manifest missing required field: id");
        }

        @Test
        void installManifestInvalidName_validationError() throws IOException {
            var dir = writeBadManifest("""
                { "name": "1bad", "id": "bad-name-v1", "version": "1.0.0" }
                """);

            int exit = cmd.execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
            assertThat(out()).contains("Validation error");
        }

        @Test
        void installManifestInvalidJson_apiError() throws IOException {
            var dir = writeBadManifest("this is not json {{{");

            int exit = cmd.execute("plugin", "install", dir.toString());
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("Install error");
        }

        @Test
        void installMissingSource_usageError() {
            int exit = cmd.execute("plugin", "install");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }

        @Test
        void installThenListJson_roundTrip() throws IOException {
            var dir = writeManifest("roundtrip", "rt-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "list", "--json");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("\"name\" : \"roundtrip\"");
            assertThat(out()).contains("\"id\" : \"rt-v1\"");
            assertThat(out()).contains("\"version\" : \"1.0.0\"");
            assertThat(out()).contains("\"source\" : \"PATH\"");
            assertThat(out()).contains("\"enabled\" : true");
        }
    }

    // ── remove ──────────────────────────────────────────────

    @Nested
    class RemoveCommand {

        @Test
        void removeHelp_showsNameAndForce() {
            int exit = cmd.execute("plugin", "remove", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
            assertThat(out()).contains("--force");
        }

        @Test
        void removeExisting_succeeds() throws IOException {
            var dir = writeManifest("to-remove", "rm-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "remove", "to-remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed plugin 'to-remove'");
        }

        @Test
        void removeThenList_gone() throws IOException {
            var dir1 = writeManifest("keep", "keep-v1", "1.0.0");
            var dir2 = tempDir.resolve("plugin-rm");
            Files.createDirectories(dir2);
            Files.writeString(dir2.resolve("plugin.json"), """
                { "name": "rm-me", "id": "rm-v1", "version": "1.0.0" }
                """);
            cmd.execute("plugin", "install", dir1.toString());
            cmd.execute("plugin", "install", dir2.toString());
            clearOutput();

            cmd.execute("plugin", "remove", "rm-me");
            clearOutput();

            int exit = cmd.execute("plugin", "list");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("keep");
            assertThat(out()).doesNotContain("rm-me");
        }

        @Test
        void removeTwice_secondFails() throws IOException {
            var dir = writeManifest("once", "once-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            cmd.execute("plugin", "remove", "once");
            clearOutput();

            int exit = cmd.execute("plugin", "remove", "once");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_returnsApiError() {
            int exit = cmd.execute("plugin", "remove", "no-such-plugin");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void removeNotFound_force_exitsOk() {
            int exit = cmd.execute("plugin", "remove", "no-such-plugin", "--force");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Removed plugin 'no-such-plugin'");
        }

        @Test
        void removeMissingName_usageError() {
            int exit = cmd.execute("plugin", "remove");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }

    // ── enable ──────────────────────────────────────────────

    @Nested
    class EnableCommand {

        @Test
        void enableHelp_showsName() {
            int exit = cmd.execute("plugin", "enable", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
        }

        @Test
        void enableDisabledPlugin_succeeds() throws IOException {
            var dir = writeManifest("toggle", "toggle-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString(), "--disabled");
            clearOutput();

            int exit = cmd.execute("plugin", "enable", "toggle");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Enabled plugin 'toggle'");
        }

        @Test
        void enableReflectedInList() throws IOException {
            var dir = writeManifest("on-off", "on-off-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString(), "--disabled");
            cmd.execute("plugin", "enable", "on-off");
            clearOutput();

            cmd.execute("plugin", "list");
            assertThat(out()).contains("on-off");
            assertThat(out()).contains("yes");
        }

        @Test
        void enableAlreadyEnabled_idempotent() throws IOException {
            var dir = writeManifest("already-on", "already-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "enable", "already-on");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Enabled plugin 'already-on'");
        }

        @Test
        void enableNotFound_apiError() {
            int exit = cmd.execute("plugin", "enable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void enableMissingName_usageError() {
            int exit = cmd.execute("plugin", "enable");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }

    // ── disable ─────────────────────────────────────────────

    @Nested
    class DisableCommand {

        @Test
        void disableHelp_showsName() {
            int exit = cmd.execute("plugin", "disable", "--help");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("NAME");
        }

        @Test
        void disableEnabledPlugin_succeeds() throws IOException {
            var dir = writeManifest("shut-off", "shut-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            clearOutput();

            int exit = cmd.execute("plugin", "disable", "shut-off");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disabled plugin 'shut-off'");
        }

        @Test
        void disableReflectedInList() throws IOException {
            var dir = writeManifest("now-off", "off-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            cmd.execute("plugin", "disable", "now-off");
            clearOutput();

            cmd.execute("plugin", "list");
            assertThat(out()).contains("now-off");
            assertThat(out()).contains("no");
        }

        @Test
        void disableAlreadyDisabled_idempotent() throws IOException {
            var dir = writeManifest("already-off", "off-v2", "1.0.0");
            cmd.execute("plugin", "install", dir.toString(), "--disabled");
            clearOutput();

            int exit = cmd.execute("plugin", "disable", "already-off");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_OK);
            assertThat(out()).contains("Disabled plugin 'already-off'");
        }

        @Test
        void disableNotFound_apiError() {
            int exit = cmd.execute("plugin", "disable", "ghost");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_API_ERROR);
            assertThat(out()).contains("not found");
        }

        @Test
        void disableMissingName_usageError() {
            int exit = cmd.execute("plugin", "disable");
            assertThat(exit).isEqualTo(AgentCliApplication.EXIT_USAGE_ERROR);
        }
    }

    // ── enable/disable lifecycle ────────────────────────────

    @Nested
    class EnableDisableLifecycle {

        @Test
        void enableDisableCycle_reflectedInList() throws IOException {
            var dir = writeManifest("cycle", "cycle-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());

            // disable → list shows no
            cmd.execute("plugin", "disable", "cycle");
            clearOutput();
            cmd.execute("plugin", "list");
            assertThat(out()).contains("no");

            // enable → list shows yes
            cmd.execute("plugin", "enable", "cycle");
            clearOutput();
            cmd.execute("plugin", "list");
            assertThat(out()).contains("yes");
        }

        @Test
        void enableDisable_preservesOtherFields() throws IOException {
            var dir = writeManifest("preserve", "pres-v1", "2.5.0");
            cmd.execute("plugin", "install", dir.toString());
            cmd.execute("plugin", "disable", "preserve");
            cmd.execute("plugin", "enable", "preserve");
            clearOutput();

            cmd.execute("plugin", "list", "--json");
            assertThat(out()).contains("\"name\" : \"preserve\"");
            assertThat(out()).contains("\"id\" : \"pres-v1\"");
            assertThat(out()).contains("\"version\" : \"2.5.0\"");
            assertThat(out()).contains("\"source\" : \"PATH\"");
        }

        @Test
        void enableDisable_survivesRestart() throws IOException {
            var dir = writeManifest("persist", "persist-v1", "1.0.0");
            cmd.execute("plugin", "install", dir.toString());
            cmd.execute("plugin", "disable", "persist");

            // simulate restart: new store pointing to same config file
            var pluginCmdLine = cmd.getSubcommands().get("plugin");
            var pluginCmd = (PluginCommand) pluginCmdLine.getCommand();
            var restartedStore = new FilePluginConfigStore(pluginConfigFile);
            pluginCmd.store = restartedStore;

            clearOutput();
            cmd.execute("plugin", "list");
            assertThat(out()).contains("persist");
            assertThat(out()).contains("no");

            cmd.execute("plugin", "enable", "persist");
            pluginCmd.store = new FilePluginConfigStore(pluginConfigFile);
            clearOutput();
            cmd.execute("plugin", "list");
            assertThat(out()).contains("yes");
        }
    }
}
