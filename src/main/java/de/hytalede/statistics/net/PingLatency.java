package de.hytalede.statistics.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PingLatency {
    private PingLatency() {
    }

    public static long measureMedianMillis(Logger logger,
                                          HttpClient httpClient,
                                          URI pingEndpoint,
                                          Duration timeout,
                                          int attempts) {
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(pingEndpoint, "pingEndpoint");
        Objects.requireNonNull(timeout, "timeout");

        Logger log = logger == null ? Logger.getLogger(PingLatency.class.getName()) : logger;

        int effectiveAttempts = Math.max(1, attempts);
        long[] measurements = new long[effectiveAttempts];

        for (int i = 0; i < effectiveAttempts; i++) {
            int attempt = i + 1;
            try {
                long startTime = System.nanoTime();
                HttpRequest pingRequest = HttpRequest.newBuilder(pingEndpoint)
                        .timeout(timeout)
                        .GET()
                        .build();
                httpClient.send(pingRequest, HttpResponse.BodyHandlers.discarding());
                long endTime = System.nanoTime();
                measurements[i] = (endTime - startTime) / 1_000_000;
            } catch (Exception ex) {
                log.log(Level.WARNING, "Ping measurement {0} failed: {1}", new Object[]{attempt, ex.getMessage()});
                measurements[i] = Long.MAX_VALUE;
            }
        }

        Arrays.sort(measurements);
        long median = measurements[measurements.length / 2];
        return median == Long.MAX_VALUE ? 0 : median;
    }
}
