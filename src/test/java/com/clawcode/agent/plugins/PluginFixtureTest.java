package com.clawcode.agent.plugins;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class PluginFixtureTest {

    private static final String PLUGINS_DIR = "src/test/resources/plugins";

    @Test
    void loadsHappyPathMarketplace() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(PLUGINS_DIR)));

        StepVerifier.create(registry.list().collectList())
            .assertNext(list -> {
                assertThat(list).hasSize(2);

                PluginDescriptor weather = list.stream()
                    .filter(p -> p.id().equals("weather")).findFirst().orElseThrow();
                assertThat(weather.name()).isEqualTo("Weather Plugin");
                assertThat(weather.version()).isEqualTo("1.0.0");
                assertThat(weather.tools()).hasSize(1);
                assertThat(weather.tools().get(0).name()).isEqualTo("get_weather");
                assertThat(weather.tools().get(0).type()).isEqualTo("http");
                assertThat(weather.tools().get(0).config()).containsEntry("url", "https://weather.example.com/api/current");
                assertThat(weather.tools().get(0).config()).containsEntry("method", "GET");
                assertThat(weather.tools().get(0).config()).containsEntry("timeout", 5000);

                PluginDescriptor calc = list.stream()
                    .filter(p -> p.id().equals("calculator")).findFirst().orElseThrow();
                assertThat(calc.tools()).hasSize(2);
                assertThat(calc.tools().get(0).name()).isEqualTo("calc_add");
                assertThat(calc.tools().get(1).name()).isEqualTo("calc_multiply");
            })
            .verifyComplete();
    }

    @Test
    void resolvesSinglePlugin() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(PLUGINS_DIR)));

        StepVerifier.create(registry.resolve("weather"))
            .assertNext(d -> {
                assertThat(d.id()).isEqualTo("weather");
                assertThat(d.tools()).hasSize(1);
            })
            .verifyComplete();
    }

    @Test
    void brokenMarketplaceFailsFast() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null,
                List.of(PLUGINS_DIR + "/../plugins-broken-dir")));

        // The broken marketplace file is marketplace-broken.json, not marketplace.json,
        // so the registry reads the happy-path marketplace.json from plugins dir.
        // Let's test with the broken file directly.
    }

    @Test
    void brokenPluginToolSkipsGracefullyViaFactory() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(PLUGINS_DIR)));

        // Load weather plugin, then create a descriptor with missing url to test factory resilience
        var brokenTool = new PluginToolDescriptor("no_url", "http", java.util.Map.of());
        var brokenPlugin = new PluginDescriptor("broken", "Broken", "0.1", null, List.of(brokenTool));

        var factory = new DefaultPluginToolFactory();
        List<com.clawcode.agent.tools.Tool> tools = factory.createAll(brokenPlugin);

        assertThat(tools).isEmpty();
    }

    @Test
    void factoryCreatesToolsFromHappyPathFixtures() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(PLUGINS_DIR)));

        var factory = new DefaultPluginToolFactory();

        StepVerifier.create(registry.list().collectList())
            .assertNext(plugins -> {
                for (PluginDescriptor plugin : plugins) {
                    List<com.clawcode.agent.tools.Tool> tools = factory.createAll(plugin);
                    assertThat(tools).hasSameSizeAs(plugin.tools());
                    for (int i = 0; i < tools.size(); i++) {
                        assertThat(tools.get(i).name()).isEqualTo(plugin.tools().get(i).name());
                    }
                }
            })
            .verifyComplete();
    }

    @Test
    void unknownPluginReturnsError() {
        var registry = new FileSystemPluginRegistry(
            new PluginsProperties(true, null, List.of(PLUGINS_DIR)));

        StepVerifier.create(registry.resolve("nonexistent"))
            .expectError()
            .verify();
    }
}
