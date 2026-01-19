package de.hytalede.statistics.server;

import de.hytalede.statistics.StatisticsPlugin;
import de.hytalede.statistics.hytale.FunctionalHytaleServerAdapter;
import de.hytalede.statistics.hytale.HytaleServerAdapter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Main entry point for running the statistics plugin as a standalone server component.
 * This main method does NOT terminate - it keeps the JVM alive while the scheduler runs.
 * Use Ctrl+C or SIGTERM to gracefully shut down.
 */
public final class StatisticsServerMain {
    private static final Logger LOGGER = Logger.getLogger(StatisticsServerMain.class.getName());
    private static final CountDownLatch SHUTDOWN_LATCH = new CountDownLatch(1);

    public static void main(String[] args) {
        // Parse config path from args, default to canonical template config
        String configPathStr = args.length > 0 ? args[0] : "config/statistics.json";
        Path configPath = Paths.get(configPathStr);

        LOGGER.info("Starting HytaleDE Statistics Plugin with config: " + configPath);

        // Register shutdown hook for graceful termination
        StatisticsPlugin plugin = createPluginWithTestAdapter(configPath);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown signal received, stopping statistics reporter...");
            plugin.close();
            SHUTDOWN_LATCH.countDown();
        }, "statistics-shutdown-hook"));

        // Start the reporter
        try {
            plugin.start();
            LOGGER.info("Statistics reporter started successfully. Press Ctrl+C to stop.");
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to start statistics plugin", ex);
            System.exit(1);
        }

        // Block main thread indefinitely until shutdown signal arrives
        try {
            SHUTDOWN_LATCH.await();
            LOGGER.info("Statistics plugin shut down gracefully.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Main thread interrupted during shutdown wait.");
        }
    }

    /**
     * Creates a plugin instance with a test adapter that simulates server metrics.
     * In production, replace this with real Hytale server API integration.
     */
    private static StatisticsPlugin createPluginWithTestAdapter(Path configPath) {
        Random random = new Random();

        HytaleServerAdapter adapter = FunctionalHytaleServerAdapter.builder()
                .onlinePlayers(() -> random.nextInt(50)) // Simulate 0-49 players
                .maxPlayers(() -> 50)
                .version(() -> "v1.0.0-alpha")
                .plugins(() -> List.of("ExamplePlugin", "StatisticsPlugin"))
                .build();

        return new StatisticsPlugin(configPath, adapter);
    }
}
