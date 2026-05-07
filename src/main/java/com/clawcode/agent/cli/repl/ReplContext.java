package com.clawcode.agent.cli.repl;

import com.clawcode.agent.cli.AgentApiClient;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Context provided by the REPL to slash command handlers.
 * Decouples the dispatcher from ReplRunner internals.
 */
public interface ReplContext {

    PrintWriter out();
    AgentApiClient client();
    String currentSessionId();
    void setSessionId(String sessionId);
    List<String> history();
    void requestExit();
}
