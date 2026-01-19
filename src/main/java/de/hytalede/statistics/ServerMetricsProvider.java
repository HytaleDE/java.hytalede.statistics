package de.hytalede.statistics;

import java.util.List;
import java.util.Objects;

/**
 * Provides live server metrics that will be reported to the remote API.
 */
public interface ServerMetricsProvider {

    /**
     * @return a snapshot of the current server state
     */
    StatisticsSnapshot snapshot();

    /**
     * Immutable carrier for runtime metrics.
     */
    record StatisticsSnapshot(int players, int slots, String version, List<String> plugins) {
        public StatisticsSnapshot {
            if (players < 0) {
                throw new IllegalArgumentException("players must be >= 0");
            }
            if (slots <= 0) {
                throw new IllegalArgumentException("slots must be > 0");
            }
            version = Objects.requireNonNullElse(version, "unknown");
            plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        }
    }
}
