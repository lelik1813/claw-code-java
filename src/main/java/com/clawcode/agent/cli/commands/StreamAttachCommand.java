package com.clawcode.agent.cli.commands;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import com.clawcode.agent.cli.AgentCliApplication;
import com.clawcode.agent.cli.CliExceptionMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "attach", description = "Attach to an SSE event stream and print events to stdout.%n  Blocks until the stream closes or times out.%n%n  Use --after-cursor to resume from a previous position: first replays%n  missed messages, then attaches to the live stream.",
    mixinStandardHelpOptions = true)
public class StreamAttachCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    AgentCliApplication.StreamCommand parent;

    @Parameters(index = "0", paramLabel = "SESSION_ID", description = "Session ID")
    String sessionId;

    @Option(names = {"--after-cursor"}, description = "Replay messages after this sequence number, then attach live")
    int afterCursor = -1;

    @Override
    public Integer call() {
        if (sessionId.isBlank()) {
            parent.parent.err().println("Error: SESSION_ID must not be blank");
            return AgentCliApplication.EXIT_USAGE_ERROR;
        }
        try {
            PrintWriter out = parent.parent.out();
            int cursor = afterCursor;

            if (cursor >= 0) {
                cursor = replayHistory(cursor, out);
            }

            CliTurnRenderer turnRenderer = new CliTurnRenderer();

            parent.parent.client().attachStream(sessionId)
                .doOnNext(event -> {
                    String rendered = turnRenderer.render(event);
                    if (rendered != null && !rendered.isEmpty()) {
                        out.print(rendered);
                        out.flush();
                    }
                })
                .blockLast();
            return AgentCliApplication.EXIT_OK;
        } catch (Exception e) {
            var exit = CliExceptionMapper.map(e);
            parent.parent.err().println("Error: " + exit.message());
            return exit.code();
        }
    }

    private int replayHistory(int after, PrintWriter out) {
        int cursor = after;
        boolean hasMore;
        do {
            var page = parent.parent.client()
                .replay(sessionId, cursor, 100)
                .block();
            if (page == null || page.messages().isEmpty()) {
                break;
            }
            for (var msg : page.messages()) {
                out.printf("[%s] %s%n", msg.role(), msg.content());
            }
            cursor = page.nextCursor();
            hasMore = page.hasMore();
        } while (hasMore);
        return cursor;
    }
}
