package com.clawcode.agent.cli.repl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import com.clawcode.agent.cli.CliExceptionMapper;
import com.clawcode.agent.cli.commands.CliTurnRenderer;
import com.clawcode.agent.cli.commands.daemon.DaemonCommand;
import com.clawcode.agent.cli.commands.mcp.McpCommand;
import com.clawcode.agent.cli.commands.remote.RemoteControlCommand;
import com.clawcode.agent.cli.commands.plugin.PluginCommand;
import com.clawcode.agent.cli.commands.skills.SkillsCommand;
import com.clawcode.agent.cli.mcp.FileMcpConfigStore;
import com.clawcode.agent.cli.plugin.FilePluginConfigStore;
import com.clawcode.agent.cli.registry.CommandRegistry;
import com.clawcode.agent.cli.skills.FileSkillStore;
import picocli.CommandLine;

/**
 * Dispatches parsed slash commands to the appropriate handler.
 * Uses {@link CommandRegistry} for discovery and {@link ReplContext}
 * for state access.
 */
public class SlashCommandDispatcher {

    private final CommandRegistry registry;
    private final Map<String, SlashHandler> handlers = new LinkedHashMap<>();
    private FileMcpConfigStore mcpStore;
    private FilePluginConfigStore pluginStore;
    private FileSkillStore skillStore;
    private com.clawcode.agent.cli.daemon.DaemonStateStore daemonStore;
    private com.clawcode.agent.cli.remote.FileRemoteControlStore remoteStore;
    private com.clawcode.agent.cli.auth.FileAuthStore authStore;

    public SlashCommandDispatcher(CommandRegistry registry) {
        this.registry = registry;
        registerBuiltins();
    }

    public void setMcpStore(FileMcpConfigStore store) {
        this.mcpStore = store;
    }

    public void setPluginStore(FilePluginConfigStore store) {
        this.pluginStore = store;
    }

    public void setSkillStore(FileSkillStore store) {
        this.skillStore = store;
    }

    public void setDaemonStore(com.clawcode.agent.cli.daemon.DaemonStateStore store) {
        this.daemonStore = store;
    }

    public void setRemoteStore(com.clawcode.agent.cli.remote.FileRemoteControlStore store) {
        this.remoteStore = store;
    }

    public void setAuthStore(com.clawcode.agent.cli.auth.FileAuthStore store) {
        this.authStore = store;
    }

    public void dispatch(String commandName, String args, ReplContext ctx) {
        var handler = handlers.get(commandName);
        if (handler != null) {
            handler.handle(args, ctx);
        } else if (registry.find(commandName).isPresent()) {
            ctx.out().printf("Command /%s is registered but not yet implemented.%n", commandName);
        } else {
            ctx.out().printf("Unknown command: /%s. Type /help for available commands.%n", commandName);
        }
        ctx.out().flush();
    }

    public void register(String name, SlashHandler handler) {
        handlers.put(name, handler);
    }

    private void registerBuiltins() {
        register("help", this::handleHelp);
        register("exit", (args, ctx) -> ctx.requestExit());
        register("quit", (args, ctx) -> ctx.requestExit());
        register("session", this::handleSession);
        register("stream", this::handleStream);
        register("replay", this::handleReplay);
        register("history", this::handleHistory);
        register("clear", this::handleClear);
        register("mcp", this::handleMcp);
        register("plugin", this::handlePlugin);
        register("skills", this::handleSkills);
        register("daemon", this::handleDaemon);
        register("remote", this::handleRemote);
        register("reload", this::handleReload);
    }

    private void handleMcp(String args, ReplContext ctx) {
        if (args.isBlank()) {
            ctx.out().println("Usage: /mcp <list|add|remove|test> [args]");
            ctx.out().flush();
            return;
        }
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var mcpRoot = new McpCommand();
        if (mcpStore != null) {
            mcpRoot.store = mcpStore;
        }
        var mcpCmd = new CommandLine(mcpRoot);
        mcpCmd.setOut(pw);
        mcpCmd.setErr(pw);
        String[] parts = args.split("\\s+");
        mcpCmd.execute(parts);
        ctx.out().print(sw.toString());
        ctx.out().flush();
    }

    private void handlePlugin(String args, ReplContext ctx) {
        if (args.isBlank()) {
            ctx.out().println("Usage: /plugin <list|install|remove|enable|disable|reload> [args]");
            ctx.out().flush();
            return;
        }
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var pluginRoot = new PluginCommand();
        if (pluginStore != null) {
            pluginRoot.store = pluginStore;
        }
        var pluginCmd = new CommandLine(pluginRoot);
        pluginCmd.setOut(pw);
        pluginCmd.setErr(pw);
        String[] parts = args.split("\\s+");
        pluginCmd.execute(parts);
        ctx.out().print(sw.toString());
        ctx.out().flush();
    }

    private void handleSkills(String args, ReplContext ctx) {
        if (args.isBlank()) {
            ctx.out().println("Usage: /skills <list|reload> [args]");
            ctx.out().flush();
            return;
        }
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var skillsRoot = new SkillsCommand();
        if (skillStore != null) {
            skillsRoot.store = skillStore;
        }
        var skillsCmd = new CommandLine(skillsRoot);
        skillsCmd.setOut(pw);
        skillsCmd.setErr(pw);
        String[] parts = args.split("\\s+");
        skillsCmd.execute(parts);
        ctx.out().print(sw.toString());
        ctx.out().flush();
    }

    private void handleDaemon(String args, ReplContext ctx) {
        if (args.isBlank()) {
            ctx.out().println("Usage: /daemon <start|status|stop> [args]");
            ctx.out().flush();
            return;
        }
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var daemonRoot = new DaemonCommand();
        if (daemonStore != null) {
            daemonRoot.store = daemonStore;
        }
        var daemonCmd = new CommandLine(daemonRoot);
        daemonCmd.setOut(pw);
        daemonCmd.setErr(pw);
        String[] parts = args.split("\\s+");
        daemonCmd.execute(parts);
        ctx.out().print(sw.toString());
        ctx.out().flush();
    }

    private void handleRemote(String args, ReplContext ctx) {
        if (args.isBlank()) {
            ctx.out().println("Usage: /remote <status|connect|disconnect> [args]");
            ctx.out().flush();
            return;
        }
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var remoteRoot = new RemoteControlCommand();
        if (remoteStore != null) {
            remoteRoot.store = remoteStore;
        }
        if (authStore != null) {
            remoteRoot.authStore = authStore;
        }
        var remoteCmd = new CommandLine(remoteRoot);
        remoteCmd.setOut(pw);
        remoteCmd.setErr(pw);
        String[] parts = args.split("\\s+");
        remoteCmd.execute(parts);
        ctx.out().print(sw.toString());
        ctx.out().flush();
    }

    private void handleReload(String args, ReplContext ctx) {
        ctx.out().println("Reloading plugins...");
        ctx.out().flush();

        // Delegate to plugin reload
        var pluginSw = new StringWriter();
        var pluginPw = new PrintWriter(pluginSw, true);
        var pluginRoot = new PluginCommand();
        if (pluginStore != null) {
            pluginRoot.store = pluginStore;
        }
        var pluginCmd = new CommandLine(pluginRoot);
        pluginCmd.setOut(pluginPw);
        pluginCmd.setErr(pluginPw);
        pluginCmd.execute("reload");
        ctx.out().print(pluginSw.toString());

        ctx.out().println("Reloading skills...");
        ctx.out().flush();

        // Delegate to skills reload
        var skillsSw = new StringWriter();
        var skillsPw = new PrintWriter(skillsSw, true);
        var skillsRoot = new SkillsCommand();
        if (skillStore != null) {
            skillsRoot.store = skillStore;
        }
        var skillsCmd = new CommandLine(skillsRoot);
        skillsCmd.setOut(skillsPw);
        skillsCmd.setErr(skillsPw);
        skillsCmd.execute("reload");
        ctx.out().print(skillsSw.toString());

        ctx.out().println("Reload complete.");
        ctx.out().flush();
    }

    private void handleHelp(String args, ReplContext ctx) {
        ctx.out().println("Slash commands:");
        for (var cmd : registry.listEnabled()) {
            if (!cmd.name().equals("exit") && !cmd.name().equals("help")) {
                ctx.out().printf("  /%-16s %s%n", cmd.name(), cmd.description());
            }
        }
        ctx.out().println("  /help              Show this help");
        ctx.out().println("  /exit              Exit REPL");
        ctx.out().println();
        ctx.out().println("Any other text is sent as a message to the current session.");
    }

    private void handleSession(String args, ReplContext ctx) {
        if (args.isEmpty() || "new".equalsIgnoreCase(args)) {
            try {
                var info = ctx.client().createSession().block();
                if (info != null) {
                    ctx.setSessionId(info.sessionId());
                    ctx.out().printf("Session created: %s%n", info.sessionId());
                }
            } catch (Exception e) {
                ctx.out().println("Error: " + CliExceptionMapper.map(e).message());
            }
        } else {
            ctx.setSessionId(args);
            ctx.out().printf("Switched to session: %s%n", args);
        }
    }

    private void handleHistory(String args, ReplContext ctx) {
        var hist = ctx.history();
        if (hist.isEmpty()) {
            ctx.out().println("(no history)");
            return;
        }
        for (int i = 0; i < hist.size(); i++) {
            ctx.out().printf("  %4d  %s%n", i + 1, hist.get(i));
        }
    }

    private void handleStream(String args, ReplContext ctx) {
        String sessionId = resolveSession(args, ctx);
        if (sessionId == null) return;

        try {
            CliTurnRenderer turnRenderer = new CliTurnRenderer();
            ctx.client().attachStream(sessionId)
                .doOnNext(event -> {
                    String rendered = turnRenderer.render(event);
                    if (rendered != null && !rendered.isEmpty()) {
                        ctx.out().print(rendered);
                        ctx.out().flush();
                    }
                })
                .blockLast();
        } catch (Exception e) {
            ctx.out().println("Error: " + CliExceptionMapper.map(e).message());
        }
    }

    private void handleReplay(String args, ReplContext ctx) {
        String sessionId = resolveSession(args, ctx);
        if (sessionId == null) return;

        try {
            var page = ctx.client().replay(sessionId, 0, 100).block();
            if (page == null || page.messages().isEmpty()) {
                ctx.out().println("(no messages)");
                return;
            }
            for (var msg : page.messages()) {
                ctx.out().printf("[%s] %s%n", msg.role(), msg.content());
            }
            if (page.hasMore()) {
                ctx.out().printf("... (cursor %d, more available)%n", page.nextCursor());
            }
        } catch (Exception e) {
            ctx.out().println("Error: " + CliExceptionMapper.map(e).message());
        }
    }

    private String resolveSession(String args, ReplContext ctx) {
        if (!args.isBlank()) return args;
        String current = ctx.currentSessionId();
        if (current != null && !current.isBlank()) return current;
        ctx.out().println("Error: no active session. Use /session <id> or /session new");
        return null;
    }

    private void handleClear(String args, ReplContext ctx) {
        ctx.out().print("\033[H\033[2J");
        ctx.out().flush();
    }

    @FunctionalInterface
    public interface SlashHandler {
        void handle(String args, ReplContext ctx);
    }
}
