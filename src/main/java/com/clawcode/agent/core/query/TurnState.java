package com.clawcode.agent.core.query;

import com.clawcode.agent.shared.message.Message;
import java.util.ArrayList;
import java.util.List;

public final class TurnState {
    private final String turnId;
    private final String sessionId;
    private final String model;
    private final String systemPrompt;
    private final List<Message> history;
    private final List<Message> modelHistory;
    private final int persistFromIndex;
    private int transcriptUpdateFromIndex;
    private final long startedAt;

    private Long latestInputTokens;
    private Long latestOutputTokens;
    private int roundCount;
    private int permissionDenialCount;
    private int maxOutputRecoveryAttempts;
    private int stopHookRetries;
    private String stopReason;
    private final StringBuilder recoveredPartialText = new StringBuilder();

    public TurnState(
        String turnId,
        String sessionId,
        String model,
        String systemPrompt,
        List<Message> history
    ) {
        this(turnId, sessionId, model, systemPrompt, history,
            history == null ? 0 : history.size());
    }

    public TurnState(
        String turnId,
        String sessionId,
        String model,
        String systemPrompt,
        List<Message> history,
        int persistFromIndex
    ) {
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.history = new ArrayList<>(history);
        this.modelHistory = new ArrayList<>(history);
        this.persistFromIndex = persistFromIndex;
        this.transcriptUpdateFromIndex = persistFromIndex;
        this.startedAt = System.nanoTime();
    }

    public String turnId() {
        return turnId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String model() {
        return model;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public List<Message> history() {
        return List.copyOf(history);
    }

    public List<Message> modelHistory() {
        return List.copyOf(modelHistory);
    }

    public void addMessage(Message message) {
        history.add(message);
        modelHistory.add(message);
    }

    public void replaceModelHistory(List<Message> messages) {
        modelHistory.clear();
        if (messages != null) {
            modelHistory.addAll(messages);
        }
    }

    public void addModelOnlyMessage(Message message) {
        modelHistory.add(message);
    }

    public long startedAt() {
        return startedAt;
    }

    public int persistFromIndex() {
        return persistFromIndex;
    }

    public List<Message> turnDelta() {
        return List.copyOf(history.subList(persistFromIndex, history.size()));
    }

    public List<Message> takeUnpersistedDelta() {
        int from = transcriptUpdateFromIndex;
        transcriptUpdateFromIndex = history.size();
        return List.copyOf(history.subList(from, history.size()));
    }

    public void recordUsage(Long inputTokens, Long outputTokens) {
        this.latestInputTokens = inputTokens;
        this.latestOutputTokens = outputTokens;
    }

    public void recordRound() {
        this.roundCount++;
    }

    public void recordPermissionDenial() {
        this.permissionDenialCount++;
    }

    public void recordMaxOutputRecoveryAttempt() {
        this.maxOutputRecoveryAttempts++;
    }

    public void recordStopHookRetry() {
        this.stopHookRetries++;
    }

    public void appendRecoveredPartialText(String text) {
        if (text != null) {
            recoveredPartialText.append(text);
        }
    }

    public String consumeRecoveredPartialText() {
        String text = recoveredPartialText.toString();
        recoveredPartialText.setLength(0);
        return text;
    }

    public void stopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public long finishDurationMs() {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public Long latestInputTokens() {
        return latestInputTokens;
    }

    public Long latestOutputTokens() {
        return latestOutputTokens;
    }

    public int roundCount() {
        return roundCount;
    }

    public int permissionDenialCount() {
        return permissionDenialCount;
    }

    public int maxOutputRecoveryAttempts() {
        return maxOutputRecoveryAttempts;
    }

    public int stopHookRetries() {
        return stopHookRetries;
    }

    public String stopReason() {
        return stopReason;
    }
}
