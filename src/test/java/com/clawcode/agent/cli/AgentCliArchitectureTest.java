package com.clawcode.agent.cli;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.clawcode.agent.cli.model.CliQueryEvent;
import com.clawcode.agent.cli.model.MessageAck;
import com.clawcode.agent.cli.model.ReplayPage;
import com.clawcode.agent.cli.model.SessionInfo;
import com.clawcode.agent.cli.repl.SlashCommandDispatcher;
import com.clawcode.agent.cli.repl.SlashCommandParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight architecture invariants — prevent silent regression of
 * transport boundaries after refactoring.
 *
 * Rules:
 * 1. Only HttpAgentApiClient may import WebClient.
 * 2. Command classes must NOT import HTTP clients directly
 *    (except McpTestCommand and PluginInstallCommand — see ALLOWED_HTTP_EXCEPTIONS).
 * 3. AgentApiClient methods return typed DTOs, never raw Map or String.
 * 4. Slash dispatch/parser must not reference any HTTP client.
 */
class AgentCliArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/clawcode/agent/cli");

    // ── HTTP client imports that must not leak into command classes ──
    private static final Set<String> FORBIDDEN_HTTP_IMPORTS = Set.of(
        "org.springframework.web.reactive.function.client.WebClient",
        "okhttp3.OkHttpClient",
        "java.net.http.HttpClient",
        "org.apache.http.client.HttpClient"
    );

    /**
     * Commands allowed to bypass AgentApiClient for direct HTTP calls.
     * Each entry documents WHY it is exempt.
     */
    private static final Set<String> ALLOWED_HTTP_EXCEPTIONS = Set.of(
        // Probes external MCP server URLs from user config — not claw-code-java API
        "McpTestCommand.java",
        // Fetches plugin manifests from user-supplied URLs — not claw-code-java API
        "PluginInstallCommand.java",
        // The transport implementation itself — owns WebClient by design
        "HttpAgentApiClient.java"
    );

    // ══════════════════════════════════════════════════════════════
    //  1. NO HTTP CLIENTS IN COMMAND CLASSES
    // ══════════════════════════════════════════════════════════════

    @Nested
    class NoHttpClientsInCommands {

        @Test
        void commandClasses_doNotImportHttpClients() throws IOException {
            List<String> violations = scanSourceFiles(
                path -> path.startsWith(SOURCE_ROOT.resolve("commands")),
                this::checkForbiddenImports
            );

            assertThat(violations)
                .withFailMessage("Commands must not import HTTP clients directly.\nViolations:\n%s\n"
                    + "Allowed exceptions: %s",
                    String.join("\n", violations), ALLOWED_HTTP_EXCEPTIONS)
                .isEmpty();
        }

        private String checkForbiddenImports(Path file) {
            String fileName = file.getFileName().toString();
            if (ALLOWED_HTTP_EXCEPTIONS.contains(fileName)) {
                return null; // explicitly allowed
            }
            try (Stream<String> lines = Files.lines(file)) {
                return lines
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> FORBIDDEN_HTTP_IMPORTS.stream()
                        .anyMatch(imp -> line.contains(imp)))
                    .map(line -> fileName + ": " + line.trim())
                    .findFirst()
                    .orElse(null);
            } catch (IOException e) {
                return fileName + ": [read error: " + e.getMessage() + "]";
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  2. ONLY HttpAgentApiClient OWNS WebClient
    // ══════════════════════════════════════════════════════════════

    @Nested
    class WebClientOwnership {

        @Test
        void onlyHttpAgentApiClient_importsWebClient() throws IOException {
            List<String> violations = scanSourceFiles(
                path -> true, // scan entire cli tree
                file -> {
                    String fileName = file.getFileName().toString();
                    if ("HttpAgentApiClient.java".equals(fileName)) return null;
                    try (Stream<String> lines = Files.lines(file)) {
                        return lines
                            .filter(line -> line.contains("import org.springframework.web.reactive.function.client.WebClient"))
                            .map(line -> fileName + ": imports WebClient")
                            .findFirst()
                            .orElse(null);
                    } catch (IOException e) {
                        return null;
                    }
                }
            );

            assertThat(violations)
                .withFailMessage("Only HttpAgentApiClient may import WebClient.\nViolations:\n%s",
                    String.join("\n", violations))
                .isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  3. AgentApiClient RETURNS TYPED DTOs (never raw Map/String)
    // ══════════════════════════════════════════════════════════════

    @Nested
    class TypedApiReturns {

        @Test
        void agentApiClient_methodsReturnTypedDtos() {
            Set<Class<?>> rawForbidden = Set.of(
                java.util.Map.class,
                java.lang.String.class,
                java.lang.Object.class
            );

            List<String> violations = new ArrayList<>();
            for (Method method : AgentApiClient.class.getDeclaredMethods()) {
                Type returnType = method.getGenericReturnType();

                // Unwrap Mono<X> / Flux<X>
                Class<?> innerType = unwrapReactiveType(returnType);
                if (innerType == null) continue;

                if (rawForbidden.contains(innerType)) {
                    violations.add(String.format(
                        "%s() returns %s — must use a typed DTO",
                        method.getName(), innerType.getSimpleName()));
                }
            }

            assertThat(violations)
                .withFailMessage("AgentApiClient methods must return typed DTOs, not raw types.\nViolations:\n%s",
                    String.join("\n", violations))
                .isEmpty();
        }

        @Test
        void agentApiClient_returnTypes_areInModelOrDtosPackage() {
            for (Method method : AgentApiClient.class.getDeclaredMethods()) {
                Class<?> innerType = unwrapReactiveType(method.getGenericReturnType());
                if (innerType == null) continue;

                Package pkg = innerType.getPackage();
                assertThat(pkg)
                    .withFailMessage("%s() returns %s from package '%s' — expected cli.model or cli package",
                        method.getName(), innerType.getSimpleName(),
                        pkg != null ? pkg.getName() : "<unnamed>")
                    .isNotNull();

                String pkgName = pkg.getName();
                assertThat(pkgName)
                    .withFailMessage("%s() returns %s from unexpected package '%s'",
                        method.getName(), innerType.getSimpleName(), pkgName)
                    .isIn("com.clawcode.agent.cli.model", "com.clawcode.agent.cli");
            }
        }

        private Class<?> unwrapReactiveType(Type genericType) {
            if (genericType instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> c) {
                    return c;
                }
            }
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  4. SLASH DISPATCH/PARSER — NO HTTP REFERENCES
    // ══════════════════════════════════════════════════════════════

    @Nested
    class SlashDispatchPurity {

        @Test
        void slashCommandDispatcher_doesNotImportHttpClients() throws IOException {
            List<String> violations = scanSourceFiles(
                path -> path.getParent().getFileName().toString().equals("repl"),
                file -> {
                    String fileName = file.getFileName().toString();
                    try (Stream<String> lines = Files.lines(file)) {
                        return lines
                            .filter(line -> line.startsWith("import "))
                            .filter(line -> FORBIDDEN_HTTP_IMPORTS.stream()
                                .anyMatch(imp -> line.contains(imp)))
                            .map(line -> fileName + ": " + line.trim())
                            .findFirst()
                            .orElse(null);
                    }
                }
            );

            assertThat(violations)
                .withFailMessage("repl/ classes must not import HTTP clients.\nViolations:\n%s",
                    String.join("\n", violations))
                .isEmpty();
        }

        @Test
        void slashCommandParser_noExternalDependencies() {
            Package pkg = SlashCommandParser.class.getPackage();
            assertThat(pkg.getName()).isEqualTo("com.clawcode.agent.cli.repl");

            // Verify it has no instance fields — only static constants allowed
            List<String> instanceFields = Stream.of(SlashCommandParser.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getName() + ": " + f.getType().getSimpleName())
                .toList();
            assertThat(instanceFields)
                .withFailMessage("SlashCommandParser should have no instance fields (utility class). Found: %s",
                    instanceFields)
                .isEmpty();
        }

        @Test
        void slashCommandDispatcher_noHttpFieldReferences() {
            // Verify SlashCommandDispatcher doesn't hold any HTTP client fields
            List<String> httpFields = Stream.of(SlashCommandDispatcher.class.getDeclaredFields())
                .filter(f -> f.getType().getPackage() != null)
                .filter(f -> {
                    String pkg = f.getType().getPackage().getName();
                    return pkg.contains("http") || pkg.contains("okhttp")
                        || pkg.contains("webclient");
                })
                .map(f -> f.getName() + ": " + f.getType().getName())
                .toList();

            assertThat(httpFields)
                .withFailMessage("SlashCommandDispatcher must not hold HTTP client references.\nFound:\n%s",
                    String.join("\n", httpFields))
                .isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  5. ALLOWED EXCEPTIONS ARE MINIMAL AND DOCUMENTED
    // ══════════════════════════════════════════════════════════════

    @Nested
    class AllowedExceptions {

        @Test
        void allowedExceptions_areDocumentedAndMinimal() throws IOException {
            // McpTestCommand must use java.net.http.HttpClient (external MCP probes)
            String mcpTestSource = Files.readString(
                SOURCE_ROOT.resolve("commands/mcp/McpTestCommand.java"));
            assertThat(mcpTestSource)
                .withFailMessage("McpTestCommand must import java.net.http.HttpClient (probes external MCP servers)")
                .contains("import java.net.http.HttpClient");

            // PluginInstallCommand must use URL connection (fetches external manifests)
            String pluginInstallSource = Files.readString(
                SOURCE_ROOT.resolve("commands/plugin/PluginInstallCommand.java"));
            assertThat(pluginInstallSource)
                .withFailMessage("PluginInstallCommand must use URL.openConnection (fetches external plugin manifests)")
                .contains("openConnection");

            // Verify no other command files bypass the transport layer
            long otherViolations = scanSourceFiles(
                path -> path.startsWith(SOURCE_ROOT.resolve("commands")),
                file -> {
                    String fileName = file.getFileName().toString();
                    if (ALLOWED_HTTP_EXCEPTIONS.contains(fileName)) return null;
                    try (Stream<String> lines = Files.lines(file)) {
                        boolean hasHttpImport = lines
                            .filter(line -> line.startsWith("import "))
                            .anyMatch(line -> FORBIDDEN_HTTP_IMPORTS.stream()
                                .anyMatch(imp -> line.contains(imp)));
                        return hasHttpImport ? fileName : null;
                    } catch (IOException e) {
                        return null;
                    }
                }
            ).size();

            assertThat(otherViolations)
                .withFailMessage("Found %d command files importing HTTP clients beyond the allowed exceptions: %s",
                    otherViolations, ALLOWED_HTTP_EXCEPTIONS)
                .isZero();
        }

        @Test
        void allowedExceptions_dontCallAgentServerApi() throws IOException {
            // McpTestCommand connects to external MCP servers, not claw-code-java
            String mcpTest = Files.readString(
                SOURCE_ROOT.resolve("commands/mcp/McpTestCommand.java"));
            assertThat(mcpTest)
                .withFailMessage("McpTestCommand must not reference AgentApiClient")
                .doesNotContain("AgentApiClient");
            assertThat(mcpTest)
                .withFailMessage("McpTestCommand must not call /api/ endpoints")
                .doesNotContain("/api/sessions");

            // PluginInstallCommand fetches external URLs, not claw-code-java
            String pluginInstall = Files.readString(
                SOURCE_ROOT.resolve("commands/plugin/PluginInstallCommand.java"));
            assertThat(pluginInstall)
                .withFailMessage("PluginInstallCommand must not reference AgentApiClient")
                .doesNotContain("AgentApiClient");
            assertThat(pluginInstall)
                .withFailMessage("PluginInstallCommand must not call /api/ endpoints")
                .doesNotContain("/api/sessions");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface SourceChecker {
        String check(Path file) throws IOException;
    }

    private List<String> scanSourceFiles(
        Predicate<Path> scopeFilter,
        SourceChecker checker
    ) throws IOException {
        List<String> results = new ArrayList<>();
        Files.walkFileTree(SOURCE_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java") && scopeFilter.test(file)) {
                    String result = checker.check(file);
                    if (result != null) {
                        results.add(result);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return results;
    }
}
