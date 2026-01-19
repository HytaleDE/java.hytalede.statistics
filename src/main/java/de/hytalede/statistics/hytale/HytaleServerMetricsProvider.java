package de.hytalede.statistics.hytale;

import de.hytalede.statistics.ServerMetricsProvider;

import java.util.List;
import java.util.Objects;

/**
 * Bridges the {@link HytaleServerAdapter} to the {@link ServerMetricsProvider} contract.
 */
public final class HytaleServerMetricsProvider implements ServerMetricsProvider {
    private final HytaleServerAdapter adapter;

    public HytaleServerMetricsProvider(HytaleServerAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public StatisticsSnapshot snapshot() {
        int slots = adapter.getMaxPlayers();
        if (slots <= 0) {
            // Hytale can report 0 early during boot; our snapshot contract requires > 0.
            slots = 1;
        }

        int players = adapter.getOnlinePlayerCount();
        if (players < 0) {
            players = 0;
        } else if (players > slots) {
            // Avoid invariant violations if server returns inconsistent values.
            players = slots;
        }

        return new StatisticsSnapshot(
                players,
                slots,
                adapter.getServerVersion(),
                sanitizePluginNames(adapter.getEnabledPlugins())
        );
    }

    private static List<String> sanitizePluginNames(List<String> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return List.of();
        }
        return plugins.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }
}
