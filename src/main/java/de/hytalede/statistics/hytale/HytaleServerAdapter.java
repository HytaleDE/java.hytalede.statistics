package de.hytalede.statistics.hytale;

import de.hytalede.statistics.model.PlayerInfo;
import de.hytalede.statistics.model.PluginInfo;

import java.util.List;

/**
 * Minimal abstraction over the Hytale server runtime so metrics can be collected without
 * depending on a particular server API in this module.
 */
public interface HytaleServerAdapter {
    int getOnlinePlayerCount();

    int getMaxPlayers();

    String getServerVersion();

    /**
     * @return list of enabled plugin identifiers (names). This is always available.
     */
    List<String> getEnabledPlugins();

    /**
     * @return optional list of players for telemetry when enabled. Default: empty.
     */
    default List<PlayerInfo> getOnlinePlayers() {
        return List.of();
    }

    /**
     * @return optional list of plugins with versions for telemetry when enabled.
     *
     * <p>Default: derive from {@link #getEnabledPlugins()} and use {@code "unknown"} for the version.</p>
     */
    default List<PluginInfo> getEnabledPluginsDetailed() {
        return getEnabledPlugins().stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .distinct()
                .map(name -> new PluginInfo(name, "unknown"))
                .toList();
    }
}
