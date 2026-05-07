package com.clawcode.agent.cli.registry;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRegistryTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
    }

    @Test
    void list_returnsBuiltins() {
        var commands = registry.list();

        assertThat(commands).isNotEmpty();
        assertThat(commands.stream().map(CommandDescriptor::name))
            .contains("session", "message", "stream", "auth", "mcp", "plugin", "config", "skills", "repl", "help", "exit");
    }

    @Test
    void find_byName_returnsCommand() {
        var cmd = registry.find("session");

        assertThat(cmd).isPresent();
        assertThat(cmd.get().name()).isEqualTo("session");
        assertThat(cmd.get().origin()).isEqualTo(CommandDescriptor.CommandOrigin.BUILTIN);
    }

    @Test
    void find_caseInsensitive() {
        assertThat(registry.find("SESSION")).isPresent();
        assertThat(registry.find("Session")).isPresent();
    }

    @Test
    void find_byAlias_returnsCommand() {
        registry.register(CommandDescriptor.builtin("logout", Set.of("lo"), "Log out"));

        assertThat(registry.find("lo")).isPresent();
        assertThat(registry.find("lo").get().name()).isEqualTo("logout");
    }

    @Test
    void find_unknownReturnsEmpty() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void isEnabled_builtinCommands() {
        assertThat(registry.isEnabled("session")).isTrue();
        assertThat(registry.isEnabled("auth")).isTrue();
    }

    @Test
    void isEnabled_unknownReturnsFalse() {
        assertThat(registry.isEnabled("nonexistent")).isFalse();
    }

    @Test
    void listEnabled_filtersDisabled() {
        registry.register(new CommandDescriptor("experimental", Set.of(), "Exp", CommandDescriptor.CommandOrigin.BUILTIN, false));

        assertThat(registry.isEnabled("experimental")).isFalse();
        assertThat(registry.listEnabled().stream().map(CommandDescriptor::name))
            .doesNotContain("experimental");
    }

    @Test
    void register_overridesExisting() {
        registry.register(CommandDescriptor.builtin("session", "Updated description"));

        var cmd = registry.find("session");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().description()).isEqualTo("Updated description");
    }

    @Test
    void withEnabled_createsModifiedCopy() {
        var original = CommandDescriptor.builtin("test", "Test cmd");
        var disabled = original.withEnabled(false);

        assertThat(original.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.name()).isEqualTo("test");
    }

    @Test
    void constructorLoadsExtraCommands() {
        var extra = List.of(
            CommandDescriptor.builtin("custom1", "Custom 1"),
            CommandDescriptor.builtin("custom2", "Custom 2")
        );
        var reg = new CommandRegistry(extra);

        assertThat(reg.find("custom1")).isPresent();
        assertThat(reg.find("custom2")).isPresent();
        assertThat(reg.find("session")).isPresent();
    }

    @Test
    void list_preservesInsertionOrder() {
        var commands = registry.list();
        var names = commands.stream().map(CommandDescriptor::name).toList();

        assertThat(names.indexOf("session")).isLessThan(names.indexOf("message"));
        assertThat(names.indexOf("message")).isLessThan(names.indexOf("stream"));
    }

    // ── plugin command discovery ────────────────────────────

    @Test
    void plugin_findableByName() {
        var cmd = registry.find("plugin");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().name()).isEqualTo("plugin");
        assertThat(cmd.get().origin()).isEqualTo(CommandDescriptor.CommandOrigin.BUILTIN);
        assertThat(cmd.get().enabled()).isTrue();
    }

    @Test
    void plugin_findableCaseInsensitive() {
        assertThat(registry.find("PLUGIN")).isPresent();
        assertThat(registry.find("Plugin")).isPresent();
    }

    @Test
    void plugin_descriptionListsSubcommands() {
        var cmd = registry.find("plugin").orElseThrow();
        assertThat(cmd.description()).contains("list");
        assertThat(cmd.description()).contains("install");
        assertThat(cmd.description()).contains("remove");
        assertThat(cmd.description()).contains("enable");
        assertThat(cmd.description()).contains("disable");
    }

    @Test
    void plugin_isEnabled() {
        assertThat(registry.isEnabled("plugin")).isTrue();
    }

    @Test
    void plugin_visibleInHelpListing() {
        var enabled = registry.listEnabled();
        var names = enabled.stream().map(CommandDescriptor::name).toList();
        assertThat(names).contains("plugin");
    }

    @Test
    void plugin_appearsInListInOrder() {
        var commands = registry.list();
        var names = commands.stream().map(CommandDescriptor::name).toList();
        assertThat(names).contains("plugin");
        // plugin comes after mcp, before config
        assertThat(names.indexOf("mcp")).isLessThan(names.indexOf("plugin"));
        assertThat(names.indexOf("plugin")).isLessThan(names.indexOf("config"));
    }
}
