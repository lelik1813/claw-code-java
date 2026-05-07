package com.clawcode.agent.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.commands.AuthCommand;
import com.clawcode.agent.cli.commands.ConfigCommand;
import com.clawcode.agent.cli.commands.launch.LaunchCommand;
import com.clawcode.agent.cli.commands.MessageSendCommand;
import com.clawcode.agent.cli.commands.SessionCreateCommand;
import com.clawcode.agent.cli.commands.StreamAttachCommand;
import com.clawcode.agent.cli.auth.FileAuthStore;
import com.clawcode.agent.cli.config.CliConfigStore;
import com.clawcode.agent.cli.commands.daemon.DaemonCommand;
import com.clawcode.agent.cli.commands.mcp.McpCommand;
import com.clawcode.agent.cli.commands.plugin.PluginCommand;
import com.clawcode.agent.cli.commands.remote.RemoteControlCommand;
import com.clawcode.agent.cli.commands.repl.ReplCommand;
import com.clawcode.agent.cli.commands.skills.SkillsCommand;
import com.clawcode.agent.cli.registry.CommandRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "agent-cli",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Thin CLI client for claw-code-java.%n"
        + "Run without arguments for interactive REPL mode.%n%n"
        + "Commands:%n"
        + "  session create          Create a new agent session%n"
        + "  message send            Send a message to a session%n"
        + "  stream attach           Attach to an SSE event stream%n"
        + "  auth                    Authentication operations (login|status|logout)%n"
        + "  mcp                     Manage MCP servers (list|add|remove|test|enable|disable)%n"
        + "  plugin                  Plugin operations (list|install|remove|enable|disable|reload)%n"
        + "  config                  Configuration operations (get|set|list|unset)%n"
        + "  skills                  Skill discovery (list|reload)%n"
        + "  daemon                  Background daemon (start|status|stop)%n"
        + "  launch                  Start local daemon and open REPL%n"
        + "  remote                  Remote-control (status|connect|disconnect)%n"
        + "  repl                    Start interactive REPL",
    subcommands = {
        AgentCliApplication.SessionCommand.class,
        AgentCliApplication.MessageCommand.class,
        AgentCliApplication.StreamCommand.class,
        AuthCommand.class,
        McpCommand.class,
        PluginCommand.class,
        ConfigCommand.class,
        SkillsCommand.class,
        DaemonCommand.class,
        LaunchCommand.class,
        RemoteControlCommand.class,
        ReplCommand.class
    }
)
public class AgentCliApplication implements Callable<Integer> {

    public static final int EXIT_OK = 0;
    public static final int EXIT_API_ERROR = 1;
    public static final int EXIT_USAGE_ERROR = 2;

    @Spec
    CommandSpec spec;

    @Option(names = {"--base-url"}, description = "API base URL (default: ${DEFAULT-VALUE})",
        defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"--api-key-header"}, description = "API key header name (default: ${DEFAULT-VALUE})",
        defaultValue = "X-API-Key")
    String apiKeyHeader;

    @Option(names = {"--api-key"}, description = "API key value")
    String apiKey;

    @Option(names = {"--timeout"}, description = "HTTP timeout in ms (default: ${DEFAULT-VALUE})",
        defaultValue = "30000")
    long timeoutMs;

    @Option(names = {"--stream-timeout"}, description = "SSE stream timeout in ms (default: ${DEFAULT-VALUE})",
        defaultValue = "300000")
    long streamTimeoutMs;

    AgentApiClient client;
    public CommandRegistry commandRegistry;
    private boolean clientCreated;

    public CommandRegistry registry() {
        if (commandRegistry == null) {
            commandRegistry = new CommandRegistry();
        }
        return commandRegistry;
    }

    public AgentApiClient client() {
        if (client == null) {
            var props = resolvedCliProperties();
            client = new HttpAgentApiClient(props);
            clientCreated = true;
        }
        return client;
    }

    public void configureClient(String baseUrl, String apiKeyHeader, String apiKey) {
        if (baseUrl != null && !baseUrl.isBlank()) this.baseUrl = baseUrl;
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) this.apiKeyHeader = apiKeyHeader;
        if (apiKey != null && !apiKey.isBlank()) this.apiKey = apiKey;
        this.client = null;
        this.clientCreated = false;
    }

    public boolean clientCreated() {
        return clientCreated;
    }

    public PrintWriter out() { return spec.commandLine().getOut(); }
    public PrintWriter err() { return spec.commandLine().getErr(); }
    public CommandSpec spec() { return spec; }

    private CliProperties resolvedCliProperties() {
        var config = new CliConfigStore();
        var auth = new FileAuthStore().load();
        String resolvedBaseUrl = baseUrl;
        String resolvedApiKeyHeader = apiKeyHeader;
        String resolvedApiKey = apiKey;
        long resolvedTimeoutMs = timeoutMs;
        long resolvedStreamTimeoutMs = streamTimeoutMs;

        if ("http://localhost:8080".equals(baseUrl)) {
            resolvedBaseUrl = config.get("baseUrl").orElse(baseUrl);
        }
        if ("X-API-Key".equals(apiKeyHeader)) {
            resolvedApiKeyHeader = auth.map(a -> a.apiKeyHeader())
                .orElse(config.get("apiKeyHeader").orElse(apiKeyHeader));
        }
        if (apiKey == null || apiKey.isBlank()) {
            resolvedApiKey = auth.map(a -> a.apiKey()).orElse(apiKey);
        }
        if (timeoutMs == 30000L) {
            resolvedTimeoutMs = parseLong(config.get("timeoutMs").orElse("30000"), 30000L);
        }
        if (streamTimeoutMs == 300000L) {
            resolvedStreamTimeoutMs = parseLong(config.get("streamReadTimeoutMs").orElse("300000"), 300000L);
        }
        return new CliProperties(resolvedBaseUrl, resolvedApiKeyHeader,
            resolvedApiKey, resolvedTimeoutMs, resolvedStreamTimeoutMs);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public Integer call() {
        return new ReplRunner(out(), System.in, client(), registry()).run();
    }

    // ── session ──────────────────────────────────────────────

    @Command(name = "session", description = "Session operations.%n  Use: agent-cli session create",
        mixinStandardHelpOptions = true,
        subcommands = { SessionCreateCommand.class })
    public static class SessionCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        public AgentCliApplication parent;

        @Override
        public Integer call() {
            new CommandLine(this).usage(parent.out());
            return EXIT_OK;
        }
    }

    // ── message ──────────────────────────────────────────────

    @Command(name = "message", description = "Message operations.%n  Use: agent-cli message send <SESSION_ID> <CONTENT>",
        mixinStandardHelpOptions = true,
        subcommands = { MessageSendCommand.class })
    public static class MessageCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        public AgentCliApplication parent;

        @Override
        public Integer call() {
            new CommandLine(this).usage(parent.out());
            return EXIT_OK;
        }
    }

    // ── stream ───────────────────────────────────────────────

    @Command(name = "stream", description = "Stream operations.%n  Use: agent-cli stream attach <SESSION_ID>",
        mixinStandardHelpOptions = true,
        subcommands = { StreamAttachCommand.class })
    public static class StreamCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        public AgentCliApplication parent;

        @Override
        public Integer call() {
            new CommandLine(this).usage(parent.out());
            return EXIT_OK;
        }
    }

    public static void main(String[] args) {
        CliTerminal.installUtf8Console();
        int exitCode = new CommandLine(new AgentCliApplication()).execute(args);
        System.exit(exitCode);
    }
}
