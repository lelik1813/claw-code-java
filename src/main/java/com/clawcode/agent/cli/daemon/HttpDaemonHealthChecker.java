package com.clawcode.agent.cli.daemon;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpDaemonHealthChecker implements DaemonHealthChecker {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final String HEALTH_PATH = "/actuator/health";

    private final HttpClient client;

    public HttpDaemonHealthChecker() {
        this(HttpClient.newHttpClient());
    }

    HttpDaemonHealthChecker(HttpClient client) {
        this.client = client;
    }

    @Override
    public boolean waitUntilReady(int port, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        do {
            if (isHealthy(port, Duration.ofSeconds(2))) {
                return true;
            }
            sleepUntilNextPoll(deadline);
        } while (System.nanoTime() < deadline);
        return isHealthy(port, Duration.ofSeconds(2));
    }

    @Override
    public boolean isHealthy(int port, Duration requestTimeout) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + HEALTH_PATH))
                .timeout(requestTimeout)
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200
                && response.statusCode() < 300
                && looksUp(response.body());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean looksUp(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        return body.contains("UP") && !body.contains("DOWN");
    }

    private void sleepUntilNextPoll(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            return;
        }
        long sleepMillis = Math.min(POLL_INTERVAL.toMillis(), Duration.ofNanos(remainingNanos).toMillis());
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
