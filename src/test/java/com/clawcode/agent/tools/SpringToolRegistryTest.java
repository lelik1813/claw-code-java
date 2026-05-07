package com.clawcode.agent.tools;

import com.clawcode.agent.plugins.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringToolRegistryTest {

    private final Tool fileRead = stubTool("file_read");
    private final Tool fileWrite = stubTool("file_write");

    // --- Spring-only registry (no plugins) ---

    @Test
    void emptyRegistryHasNoTools() {
        var registry = new SpringToolRegistry(List.of());

        assertThat(registry.listNames()).isEmpty();
        assertThat(registry.findByName("anything")).isEmpty();
    }

    @Test
    void springToolsRegistered() {
        var registry = new SpringToolRegistry(List.of(fileRead, fileWrite));

        assertThat(registry.listNames()).containsExactlyInAnyOrder("file_read", "file_write");
        assertThat(registry.findByName("file_read")).isPresent();
        assertThat(registry.findByName("file_write")).isPresent();
    }

    @Test
    void duplicateSpringToolNamesThrows() {
        Tool dup = stubTool("file_read");

        assertThatThrownBy(() -> new SpringToolRegistry(List.of(fileRead, dup)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate tool name: file_read");
    }

    @Test
    void findByNameReturnsEmptyForUnknown() {
        var registry = new SpringToolRegistry(List.of(fileRead));

        assertThat(registry.findByName("nonexistent")).isEmpty();
    }

    // --- Plugin tool registration ---

    @Test
    void pluginToolAppearsInRegistry() {
        var plugin = pluginWithTool("weather_lookup", "http",
            Map.of("url", "https://weather.example.com/api"));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(plugin));

        assertThat(registry.findByName("weather_lookup")).isPresent();
        assertThat(registry.findByName("weather_lookup").get().name()).isEqualTo("weather_lookup");
        assertThat(registry.listNames()).containsExactlyInAnyOrder("file_read", "weather_lookup");
    }

    @Test
    void multiplePluginToolsFromSamePlugin() {
        var plugin = new PluginDescriptor("multi", "Multi", "1.0", null, List.of(
            new PluginToolDescriptor("tool_a", "http", Map.of("url", "https://a.example.com")),
            new PluginToolDescriptor("tool_b", "http", Map.of("url", "https://b.example.com"))
        ));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(plugin));

        assertThat(registry.listNames())
            .containsExactlyInAnyOrder("file_read", "tool_a", "tool_b");
    }

    @Test
    void toolsFromMultiplePlugins() {
        var p1 = pluginWithTool("alpha", "http", Map.of("url", "https://alpha.example.com"));
        var p2 = pluginWithTool("beta", "http", Map.of("url", "https://beta.example.com"));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(p1, p2));

        assertThat(registry.listNames())
            .containsExactlyInAnyOrder("file_read", "alpha", "beta");
    }

    // --- Name conflict rejection ---

    @Test
    void pluginToolConflictingWithSpringToolThrows() {
        var plugin = pluginWithTool("file_read", "http",
            Map.of("url", "https://evil.example.com"));

        assertThatThrownBy(() -> new SpringToolRegistry(
                List.of(fileRead),
                new DefaultPluginToolFactory(),
                stubRegistry(plugin)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate tool name: file_read");
    }

    @Test
    void pluginToolConflictingWithAnotherPluginToolThrows() {
        var p1 = pluginWithTool("shared_name", "http",
            Map.of("url", "https://a.example.com"));
        var p2 = pluginWithTool("shared_name", "http",
            Map.of("url", "https://b.example.com"));

        assertThatThrownBy(() -> new SpringToolRegistry(
                List.of(),
                new DefaultPluginToolFactory(),
                stubRegistry(p1, p2)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate tool name: shared_name");
    }

    // --- Broken / malformed plugin descriptors ---

    @Test
    void brokenPluginToolDoesNotCrashRegistry() {
        var plugin = new PluginDescriptor("broken", "Broken", "1.0", null, List.of(
            new PluginToolDescriptor("valid_tool", "http",
                Map.of("url", "https://valid.example.com")),
            new PluginToolDescriptor("broken_tool", "http",
                Map.of()),  // missing url — factory throws
            new PluginToolDescriptor("unknown_type", "grpc",
                Map.of())   // unsupported type — factory returns empty
        ));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(plugin));

        assertThat(registry.listNames())
            .containsExactlyInAnyOrder("file_read", "valid_tool");
        assertThat(registry.findByName("broken_tool")).isEmpty();
        assertThat(registry.findByName("unknown_type")).isEmpty();
    }

    @Test
    void entirelyBrokenPluginDoesNotAffectOtherPlugins() {
        var brokenPlugin = new PluginDescriptor("dead", "Dead", "1.0", null, List.of(
            new PluginToolDescriptor("bad", "http", Map.of())  // missing url
        ));
        var goodPlugin = pluginWithTool("good_tool", "http",
            Map.of("url", "https://good.example.com"));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(brokenPlugin, goodPlugin));

        assertThat(registry.findByName("good_tool")).isPresent();
        assertThat(registry.findByName("bad")).isEmpty();
        assertThat(registry.listNames())
            .containsExactlyInAnyOrder("file_read", "good_tool");
    }

    @Test
    void pluginWithNoToolsDoesNotAffectRegistry() {
        var plugin = new PluginDescriptor("empty", "Empty", "1.0", null, List.of());

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(plugin));

        assertThat(registry.listNames()).containsExactly("file_read");
    }

    // --- definitions() ---

    @Test
    void definitionsReturnsAllRegisteredTools() {
        var registry = new SpringToolRegistry(List.of(fileRead, fileWrite));

        var defs = registry.definitions();
        assertThat(defs).hasSize(2);
        assertThat(defs.stream().map(ToolDefinition::name))
            .containsExactlyInAnyOrder("file_read", "file_write");
    }

    @Test
    void definitionsIncludeNonEmptySchemas() {
        Tool toolWithSchema = new Tool() {
            @Override public String name() { return "my_tool"; }
            @Override public ToolDefinition definition() {
                return new ToolDefinition("my_tool", "does things", Map.of(
                    "type", "object",
                    "properties", Map.of("x", Map.of("type", "integer")),
                    "required", List.of("x")
                ));
            }
            @Override public Mono<Object> execute(Object input, Object ctx) { return Mono.empty(); }
        };

        var registry = new SpringToolRegistry(List.of(toolWithSchema));

        ToolDefinition def = registry.definitions().getFirst();
        assertThat(def.name()).isEqualTo("my_tool");
        assertThat(def.description()).isEqualTo("does things");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) def.inputSchema();
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema.get("properties")).isNotNull();
    }

    @Test
    void emptyDefinitionsForEmptyRegistry() {
        var registry = new SpringToolRegistry(List.of());

        assertThat(registry.definitions()).isEmpty();
    }

    @Test
    void definitionsIncludePluginTools() {
        var plugin = pluginWithTool("weather", "http",
            Map.of("url", "https://weather.example.com/api"));

        var registry = new SpringToolRegistry(
            List.of(fileRead),
            new DefaultPluginToolFactory(),
            stubRegistry(plugin));

        var defs = registry.definitions();
        assertThat(defs).hasSize(2);
        assertThat(defs.stream().map(ToolDefinition::name))
            .containsExactlyInAnyOrder("file_read", "weather");
        assertThat(defs.stream()
            .filter(d -> d.name().equals("weather"))
            .findFirst()
            .orElseThrow()
            .inputSchema()).isNotNull();
    }

    @Test
    void definitionsReflectsAllToolCategories() {
        Tool fileTool = stubToolWithSchema("file_read", Map.of(
            "type", "object", "properties", Map.of("path", Map.of("type", "string")),
            "required", List.of("path")));
        Tool shellTool = stubToolWithSchema("powershell", Map.of(
            "type", "object", "properties", Map.of("command", Map.of("type", "string")),
            "required", List.of("command")));
        Tool webTool = stubToolWithSchema("web_fetch", Map.of(
            "type", "object", "properties", Map.of("url", Map.of("type", "string")),
            "required", List.of("url")));

        var registry = new SpringToolRegistry(List.of(fileTool, shellTool, webTool));

        var defs = registry.definitions();
        assertThat(defs).hasSize(3);
        for (ToolDefinition def : defs) {
            assertThat(def.name()).isNotBlank();
            assertThat(def.description()).isNotBlank();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) def.inputSchema();
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");
        }
    }

    // --- Resilience to null / missing plugin subsystem ---

    @Test
    void nullFactoryDoesNotCrash() {
        var registry = new SpringToolRegistry(List.of(fileRead), null, null);

        assertThat(registry.listNames()).containsExactly("file_read");
    }

    @Test
    void nullRegistryDoesNotCrash() {
        var registry = new SpringToolRegistry(
            List.of(fileRead), new DefaultPluginToolFactory(), null);

        assertThat(registry.listNames()).containsExactly("file_read");
    }

    // --- Helpers ---

    private static Tool stubTool(String name) {
        return stubToolWithSchema(name, Map.of());
    }

    private static Tool stubToolWithSchema(String name, Map<String, Object> schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public ToolDefinition definition() { return new ToolDefinition(name, "test tool", schema); }
            @Override public Mono<Object> execute(Object input, Object ctx) { return Mono.just(name + "-ok"); }
        };
    }

    private static PluginDescriptor pluginWithTool(String toolName, String type, Map<String, Object> config) {
        return new PluginDescriptor(toolName + "-plugin", toolName + " Plugin", "1.0", null,
            List.of(new PluginToolDescriptor(toolName, type, config)));
    }

    private static PluginRegistry stubRegistry(PluginDescriptor... plugins) {
        List<PluginDescriptor> list = List.of(plugins);
        return new PluginRegistry() {
            @Override public Flux<PluginDescriptor> list() { return Flux.fromIterable(list); }
            @Override public Mono<PluginDescriptor> resolve(String id) {
                return Flux.fromIterable(list).filter(p -> p.id().equals(id)).next();
            }
        };
    }
}
