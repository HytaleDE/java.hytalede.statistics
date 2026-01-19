package de.hytalede.statistics;

import de.hytalede.statistics.config.StatisticsConfig;
import de.hytalede.statistics.config.JsonStatisticsConfigLoader;
import de.hytalede.statistics.hytale.HytaleServerAdapter;
import de.hytalede.statistics.hytale.HytaleServerMetricsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point that wires configuration and reporting.
 */
public final class StatisticsPlugin implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(StatisticsPlugin.class.getName());

    private final Path configPath;
    private final ServerMetricsProvider metricsProvider;
    private StatisticsReporter reporter;
    private final ExecutorService asyncExecutor;

    public StatisticsPlugin(Path configPath, ServerMetricsProvider metricsProvider) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.metricsProvider = Objects.requireNonNull(metricsProvider, "metricsProvider");
        this.asyncExecutor = Executors.newSingleThreadExecutor(newAsyncThreadFactory());
    }

    public StatisticsPlugin(Path configPath, HytaleServerAdapter serverAdapter) {
        this(configPath, new HytaleServerMetricsProvider(serverAdapter));
    }

    public synchronized void start() throws IOException {
        if (reporter != null) {
            return;
        }
        StatisticsConfig config = new JsonStatisticsConfigLoader(configPath).load();
        reporter = new StatisticsReporter(config, metricsProvider);
        reporter.start();
    }

    /**
     * Sends one telemetry payload immediately.
     *
     * <p>If the reporter is running, it reuses it. Otherwise, it loads the config and sends once
     * without starting the periodic scheduler (and cleans up resources afterwards).
     */
    public synchronized StatisticsReporter.SendResult sendOnceNow() throws Exception {
        if (reporter != null) {
            return reporter.sendOnce();
        }

        StatisticsConfig config = new JsonStatisticsConfigLoader(configPath).load();
        StatisticsReporter oneShot = new StatisticsReporter(config, metricsProvider);
        try {
            return oneShot.sendOnce();
        } finally {
            oneShot.close();
        }
    }

    /**
     * Async wrapper around {@link #sendOnceNow()} for embedding into command handlers.
     */
    public CompletableFuture<StatisticsReporter.SendResult> sendOnceNowAsync(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendOnceNow();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public CompletableFuture<StatisticsReporter.SendResult> sendOnceNowAsync() {
        return sendOnceNowAsync(asyncExecutor);
    }

    /**
     * Starts the plugin but never throws. Intended for embedding into server runtimes
     * where configuration/IO problems must not crash the host.
     *
     * @return true if reporting was started (or already running), false otherwise
     */
    public synchronized boolean startSafely() {
        if (reporter != null) {
            return true;
        }
        try {
            start();
            return true;
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to start statistics plugin (config=" + configPath.toAbsolutePath() + ")", ex);
            reporter = null;
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (reporter != null) {
            try {
                reporter.close();
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to close statistics reporter", ex);
            }
            reporter = null;
        }
        asyncExecutor.shutdownNow();
    }

    private static ThreadFactory newAsyncThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "statistics-async");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, ex) -> LOGGER.log(Level.SEVERE, "Uncaught exception in " + t.getName(), ex));
            return thread;
        };
    }
}
