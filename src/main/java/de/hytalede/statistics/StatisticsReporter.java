package de.hytalede.statistics;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.hytalede.statistics.ServerMetricsProvider.StatisticsSnapshot;
import de.hytalede.statistics.config.StatisticsConfig;
import de.hytalede.statistics.net.HttpIo;
import de.hytalede.statistics.net.PingLatency;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically pushes statistics payloads to the remote API endpoint.
 */
public final class StatisticsReporter implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(StatisticsReporter.class.getName());
    private static final int MAX_LOG_BODY_CHARS = 4_096;
    private static final int PING_ATTEMPTS = 3;

    private final StatisticsConfig config;
    private final ServerMetricsProvider metricsProvider;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private ScheduledFuture<?> scheduledFuture;
    private boolean closed;

    public StatisticsReporter(StatisticsConfig config, ServerMetricsProvider metricsProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.metricsProvider = Objects.requireNonNull(metricsProvider, "metricsProvider");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(newReporterThreadFactory());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public synchronized void start() {
        if (closed) {
            LOGGER.warning("StatisticsReporter.start() called after close(); ignoring");
            return;
        }
        if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
            return;
        }
        long intervalMillis = config.interval().toMillis();
        try {
            scheduledFuture = scheduler.scheduleAtFixedRate(this::dispatchSafely, 0L, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            LOGGER.log(Level.WARNING, "Statistics scheduler rejected start()", ex);
        }
    }

    private void dispatchSafely() {
        try {
            SendResult result = sendOnce();
            if (result.statusCode() == 204) {
                LOGGER.info("Telemetry accepted (204 No Content)");
            } else {
                int status = result.statusCode();
                StringBuilder message = new StringBuilder("Telemetry rejected with HTTP ")
                        .append(result.statusCode())
                        .append(" (endpoint=")
                        .append(config.telemetryEndpoint())
                        .append(")");
                String responseBody = result.responseBody();
                if (responseBody != null && !responseBody.isBlank()) {
                    message.append(": ").append(responseBody);
                    if (result.responseBodyTruncated()) {
                        message.append("... (truncated)");
                    }
                }

                // Add high-signal hints for common failure modes.
                if (status == 401 || status == 403) {
                    message.append(" | Hint: check bearerToken (unauthorized/forbidden).");
                    LOGGER.severe(message::toString);
                } else if (status == 400) {
                    message.append(" | Hint: check endpoint (/api/v1/) and vanityUrl format.");
                    LOGGER.warning(message::toString);
                } else if (status == 429) {
                    message.append(" | Hint: rate limited; consider increasing interval.");
                    LOGGER.warning(message::toString);
                } else if (status >= 500) {
                    message.append(" | Hint: server error; try again later.");
                    LOGGER.warning(message::toString);
                } else {
                    LOGGER.warning(message::toString);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Statistics endpoint unreachable ({0}): {1}", new Object[]{config.telemetryEndpoint(), ex.getMessage()});
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Statistics dispatch interrupted", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Unexpected statistics dispatch failure", ex);
        }
    }

    /**
     * Sends one telemetry payload immediately.
     *
     * <p>This is useful for integration checks (e.g. from a JUnit test) without starting the scheduler.
     *
     * @return response status and (limited) response body
     */
    public SendResult sendOnce() throws IOException, InterruptedException {
        long latencyMs = PingLatency.measureMedianMillis(LOGGER, httpClient, config.pingEndpoint(), config.readTimeout(), PING_ATTEMPTS);
        
        StatisticsSnapshot snapshot = metricsProvider.snapshot();
        StatisticsPayload payload = new StatisticsPayload(
            config.vanityUrl(),
            null,
            null,
            snapshot.players(),
            snapshot.slots(),
            null,
            latencyMs,
            null,
            null,
            null
        );

        String body = objectMapper.writeValueAsString(payload);

        LOGGER.info(() -> "Sending telemetry: endpoint=" + config.telemetryEndpoint()
            + ", vanityUrl=" + payload.vanityUrl()
            + ", payload=" + body);

        HttpRequest request = HttpRequest.newBuilder(config.telemetryEndpoint())
                .timeout(config.readTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.bearerToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        HttpIo.LimitedText limited = HttpIo.readUtf8Limited(response.body(), MAX_LOG_BODY_CHARS);
        return new SendResult(status, limited.text(), limited.truncated());
    }

    public record SendResult(int statusCode, String responseBody, boolean responseBodyTruncated) {
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory newReporterThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "statistics-reporter");
            thread.setDaemon(true); // safer for embedding; standalone runner keeps JVM alive explicitly
            thread.setUncaughtExceptionHandler((t, ex) -> LOGGER.log(Level.SEVERE, "Uncaught exception in " + t.getName(), ex));
            return thread;
        };
    }
}
