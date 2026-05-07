package com.clawcode.agent.tools;

import com.clawcode.agent.plugins.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringToolRegistryPluginMergeTest {

    private final Tool builtIn = new Tool() {
        @Override public String name() { return "built_in"; }
        @Override public ToolDefinition definition() { return new ToolDefinition(name(), "test tool", Map.of()); }
        @Override public Mono<Object> execute(Object input, Object ctx) { return Mono.just("builtin-ok"); }
    };

    @Test
    void pluginToolsMergeWithSpringTools() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(new PluginToolDescriptor("plugin_action", "http",
                Map.of("url", "https://api.example.com/act"))));

        var registry = new SpringToolRegistry(
            List.of(builtIn),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginDesc));

        assertThat(registry.listNames()).containsExactlyInAnyOrder("built_in", "plugin_action");
        assertThat(registry.findByName("built_in")).isPresent();
        assertThat(registry.findByName("plugin_action")).isPresent();
    }

    @Test
    void pluginToolsVisibleViaFindByName() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(new PluginToolDescriptor("remote_call", "http",
                Map.of("url", "https://example.com/api"))));

        var registry = new SpringToolRegistry(
            List.of(),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginDesc));

        Optional<Tool> tool = registry.findByName("remote_call");
        assertThat(tool).isPresent();
        assertThat(tool.get().name()).isEqualTo("remote_call");
    }

    @Test
    void duplicateNameAcrossSpringAndPluginThrows() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(new PluginToolDescriptor("built_in", "http",
                Map.of("url", "https://example.com"))));

        assertThatThrownBy(() -> new SpringToolRegistry(
                List.of(builtIn),
                new DefaultPluginToolFactory(),
                new StubPluginRegistry(pluginDesc)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate tool name: built_in");
    }

    @Test
    void nullFactoryAndRegistryYieldsOnlySpringTools() {
        var registry = new SpringToolRegistry(List.of(builtIn), null, null);

        assertThat(registry.listNames()).containsExactly("built_in");
    }

    @Test
    void nullFactoryYieldsOnlySpringTools() {
        var registry = new SpringToolRegistry(List.of(builtIn), null, new StubPluginRegistry());

        assertThat(registry.listNames()).containsExactly("built_in");
    }

    @Test
    void nullRegistryYieldsOnlySpringTools() {
        var registry = new SpringToolRegistry(List.of(builtIn), new DefaultPluginToolFactory(), null);

        assertThat(registry.listNames()).containsExactly("built_in");
    }

    @Test
    void multiplePluginsMergeAllTools() {
        var pluginA = new PluginDescriptor("a", "A", "1.0", null,
            List.of(new PluginToolDescriptor("tool_a", "http",
                Map.of("url", "https://a.example.com"))));
        var pluginB = new PluginDescriptor("b", "B", "1.0", null,
            List.of(new PluginToolDescriptor("tool_b", "http",
                Map.of("url", "https://b.example.com"))));

        var registry = new SpringToolRegistry(
            List.of(builtIn),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginA, pluginB));

        assertThat(registry.listNames())
            .containsExactlyInAnyOrder("built_in", "tool_a", "tool_b");
    }

    @Test
    void pluginWithUnsupportedToolTypeSkipsGracefully() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(
                new PluginToolDescriptor("good", "http", Map.of("url", "https://example.com")),
                new PluginToolDescriptor("unknown_type", "grpc", Map.of())
            ));

        var registry = new SpringToolRegistry(
            List.of(builtIn),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginDesc));

        assertThat(registry.listNames()).containsExactlyInAnyOrder("built_in", "good");
        assertThat(registry.findByName("unknown_type")).isEmpty();
    }

    @Test
    void deterministicOrderSpringToolsFirstThenPlugins() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(new PluginToolDescriptor("z_plugin", "http",
                Map.of("url", "https://example.com"))));

        var registry = new SpringToolRegistry(
            List.of(builtIn),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginDesc));

        String[] names = registry.listNames().toArray(new String[0]);
        assertThat(names[0]).isEqualTo("built_in");
        assertThat(names[1]).isEqualTo("z_plugin");
    }

    @Test
    void pluginToolExecutes() {
        var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
            List.of(new PluginToolDescriptor("echo_plugin", "http",
                Map.of("url", "https://example.com/echo"))));

        var registry = new SpringToolRegistry(
            List.of(),
            new DefaultPluginToolFactory(),
            new StubPluginRegistry(pluginDesc));

        Tool tool = registry.findByName("echo_plugin").orElseThrow();
        assertThat(tool.name()).isEqualTo("echo_plugin");
        // execute returns Mono — tool exists and is callable
        assertThat(tool.execute("test", null)).isNotNull();
    }

    // --- definitions() includes plugin tools ---

    @Nested
    class DefinitionsPublication {

        @Test
        void definitionsIncludesBuiltInAndPluginTools() {
            var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
                List.of(new PluginToolDescriptor("plugin_action", "http",
                    Map.of("url", "https://api.example.com/act"))));

            var registry = new SpringToolRegistry(
                List.of(builtIn),
                new DefaultPluginToolFactory(),
                new StubPluginRegistry(pluginDesc));

            var defs = registry.definitions();
            assertThat(defs).hasSize(2);
            assertThat(defs.stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("built_in", "plugin_action");
        }

        @Test
        void pluginToolDefinitionHasNonEmptySchema() {
            var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
                List.of(new PluginToolDescriptor("my_plugin", "http",
                    Map.of("url", "https://example.com/api"))));

            var registry = new SpringToolRegistry(
                List.of(),
                new DefaultPluginToolFactory(),
                new StubPluginRegistry(pluginDesc));

            ToolDefinition def = registry.definitions().getFirst();
            assertThat(def.name()).isEqualTo("my_plugin");
            assertThat(def.description()).isNotBlank();
            assertThat(def.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) def.inputSchema();
            assertThat(schema).containsKey("type");
        }

        @Test
        void definitionsPreservesRegistrationOrder() {
            var pluginDesc = new PluginDescriptor("p1", "P1", "1.0", null,
                List.of(new PluginToolDescriptor("z_plugin", "http",
                    Map.of("url", "https://example.com"))));

            var registry = new SpringToolRegistry(
                List.of(builtIn),
                new DefaultPluginToolFactory(),
                new StubPluginRegistry(pluginDesc));

            var defs = registry.definitions();
            assertThat(defs.get(0).name()).isEqualTo("built_in");
            assertThat(defs.get(1).name()).isEqualTo("z_plugin");
        }
    }

    private static class StubPluginRegistry implements PluginRegistry {
        private final List<PluginDescriptor> plugins;

        StubPluginRegistry(PluginDescriptor... plugins) {
            this.plugins = List.of(plugins);
        }

        @Override
        public reactor.core.publisher.Flux<PluginDescriptor> list() {
            return reactor.core.publisher.Flux.fromIterable(plugins);
        }

        @Override
        public reactor.core.publisher.Mono<PluginDescriptor> resolve(String pluginId) {
            return reactor.core.publisher.Flux.fromIterable(plugins)
                .filter(p -> p.id().equals(pluginId))
                .next();
        }
    }
}
