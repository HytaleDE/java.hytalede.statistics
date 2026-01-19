package de.hytalede.statistics.server;

import de.hytalede.statistics.StatisticsReporter;
import de.hytalede.statistics.config.JsonStatisticsConfigLoader;
import de.hytalede.statistics.config.StatisticsConfig;
import de.hytalede.statistics.hytale.FunctionalHytaleServerAdapter;
import de.hytalede.statistics.hytale.HytaleServerAdapter;
import de.hytalede.statistics.hytale.HytaleServerMetricsProvider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-shot runner for manual/live checks: sends exactly one telemetry payload and exits.
 */
public final class StatisticsSendOnceMain {
    private static final Logger LOGGER = Logger.getLogger(StatisticsSendOnceMain.class.getName());

    private StatisticsSendOnceMain() {
    }

    public static void main(String[] args) {
        String configPathStr = args.length > 0 ? args[0] : "config/statistics.local.json";
        Path configPath = Paths.get(configPathStr);

        try {
            StatisticsConfig config = new JsonStatisticsConfigLoader(configPath).load();

            Random random = new Random();
            HytaleServerAdapter adapter = FunctionalHytaleServerAdapter.builder()
                    .onlinePlayers(() -> random.nextInt(50))
                    .maxPlayers(() -> 50)
                    .version(() -> "send-once")
                    .plugins(() -> List.of("StatisticsPlugin"))
                    .build();

            try (StatisticsReporter reporter = new StatisticsReporter(config, new HytaleServerMetricsProvider(adapter))) {
                StatisticsReporter.SendResult result = reporter.sendOnce();
                LOGGER.info(() -> "Send-once finished with HTTP " + result.statusCode());
                if (result.responseBody() != null && !result.responseBody().isBlank()) {
                    LOGGER.info(() -> "Response body: " + result.responseBody()
                            + (result.responseBodyTruncated() ? "... (truncated)" : ""));
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Send-once failed (config=" + configPath.toAbsolutePath() + ")", ex);
            System.exit(1);
        }
    }
}

