package com.clawcode.agent.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.clawcode.agent.cli.commands.CliTurnRenderer;
import com.clawcode.agent.cli.model.CliQueryEvent;
import com.clawcode.agent.cli.repl.ReplContext;
import com.clawcode.agent.cli.repl.SlashCommandDispatcher;
import com.clawcode.agent.cli.repl.SlashCommandParser;
import com.clawcode.agent.cli.repl.SlashCommandParser.ParseResult;
import com.clawcode.agent.cli.registry.CommandRegistry;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

public class ReplRunner implements ReplContext {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(5);

    private final PrintWriter out;
    private final BufferedReader reader;
    private final AgentApiClient client;
    private final CommandRegistry registry;
    private final SlashCommandDispatcher dispatcher;

    private String currentSessionId;
    private final List<String> history = new ArrayList<>();
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public ReplRunner(PrintWriter out, InputStream in, AgentApiClient client, CommandRegistry registry) {
        this.out = out;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.client = client;
        this.registry = registry;
        this.dispatcher = new SlashCommandDispatcher(registry);
    }

    @Override public PrintWriter out() { return out; }
    @Override public AgentApiClient client() { return client; }
    @Override public String currentSessionId() { return currentSessionId; }
    @Override public void setSessionId(String id) { this.currentSessionId = id; }
    @Override public List<String> history() { return List.copyOf(history); }
    @Override public void requestExit() { interrupted.set(true); }

    public int run() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            interrupted.set(true);
            try { reader.close(); } catch (IOException ignored) {}
        }));

        ReplWelcomeRenderer.render(out);
        out.flush();

        try {
            String line;
            while (!interrupted.get()) {
                out.println(ReplWelcomeRenderer.inputRule());
                out.print(currentSessionId != null
                    ? "❯ [" + shortId(currentSessionId) + "] " : "❯ ");
                out.flush();

                line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                out.println(ReplWelcomeRenderer.inputRule());
                out.flush();

                history.add(line);

                ParseResult parsed = SlashCommandParser.parse(line);
                if (parsed.isSlash()) {
                    dispatcher.dispatch(parsed.commandName(), parsed.args(), this);
                } else if (parsed.isPlainText()) {
                    dispatchText(parsed.raw());
                } else if (parsed.isInvalidSlash()) {
                    out.println(parsed.error() != null ? parsed.error() : "Invalid slash command");
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (!interrupted.get()) {
                out.println("REPL error: " + e.getMessage());
            }
        }
        out.println("Bye.");
        out.flush();
        return AgentCliApplication.EXIT_OK;
    }

    private void dispatchText(String text) {
        if (currentSessionId == null) {
            try {
                var info = client.createSession().block();
                if (info != null) {
                    currentSessionId = info.sessionId();
                    out.printf("Auto-created session: %s%n", currentSessionId);
                }
            } catch (Exception e) {
                out.println("Error creating session: " + e.getMessage());
                return;
            }
        }
        try {
            streamResponseFor(text);
        } catch (Exception e) {
            out.println("Error: " + e.getMessage());
        }
    }

    private void streamResponseFor(String text) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch replayDrained = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        AtomicBoolean acceptingEvents = new AtomicBoolean(false);
        AtomicBoolean turnEnded = new AtomicBoolean(false);
        CliTurnRenderer turnRenderer = new CliTurnRenderer();

        Disposable subscription = client.attachStream(currentSessionId)
            .doOnNext(event -> {
                if (!acceptingEvents.get()) {
                    if (event instanceof CliQueryEvent.Completed) {
                        replayDrained.countDown();
                        acceptingEvents.set(true);
                    }
                    return;
                }
                if (event instanceof CliQueryEvent.StopReason sr) {
                    if (!"tool_use".equals(sr.reason())) {
                        turnEnded.set(true);
                    }
                }
                String rendered = turnRenderer.render(event);
                if (rendered != null && !rendered.isEmpty()) {
                    out.print(rendered);
                    out.flush();
                }
                if (event instanceof CliQueryEvent.Completed && turnEnded.get()) {
                    done.countDown();
                }
                if (event instanceof CliQueryEvent.Error) {
                    done.countDown();
                }
            })
            .doOnError(e -> {
                streamError.set(e);
                out.println("Stream error: " + e.getMessage());
                out.flush();
            })
            .onErrorResume(e -> Mono.empty())
            .doFinally(signal -> done.countDown())
            .subscribe();

        try {
            replayDrained.await(200, TimeUnit.MILLISECONDS);
            acceptingEvents.set(true);
            client.sendMessage(currentSessionId, text, null).block();
            if (!done.await(TURN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                out.println("Stream timed out.");
            }
            if (streamError.get() != null) {
                return;
            }
        } finally {
            subscription.dispose();
        }
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) + "..." : id;
    }
}
