package de.hytalede.statistics.hytale;

import de.hytalede.statistics.ServerMetricsProvider;
import de.hytalede.statistics.model.PlayerInfo;
import de.hytalede.statistics.model.PluginInfo;

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
                sanitizePlayers(adapter.getOnlinePlayers()),
                sanitizePlugins(adapter.getEnabledPluginsDetailed())
        );
    }

    private static List<PlayerInfo> sanitizePlayers(List<PlayerInfo> players) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        // Keep stable order as provided; just filter obvious invalid entries.
        return players.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.uuid() != null && !p.uuid().isBlank())
                .filter(p -> p.name() != null && !p.name().isBlank())
                .toList();
    }

    private static List<PluginInfo> sanitizePlugins(List<PluginInfo> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return List.of();
        }
        return plugins.stream()
                .filter(Objects::nonNull)
                .map(p -> new PluginInfo(
                        p.name() == null ? "" : p.name().trim(),
                        p.version() == null ? "unknown" : p.version().trim()
                ))
                .filter(p -> !p.name().isEmpty())
                .distinct()
                .toList();
    }
}
