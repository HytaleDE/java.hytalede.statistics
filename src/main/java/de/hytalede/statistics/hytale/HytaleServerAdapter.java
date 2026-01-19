package de.hytalede.statistics.hytale;

import java.util.List;

/**
 * Minimal abstraction over the Hytale server runtime so metrics can be collected without
 * depending on a particular server API in this module.
 */
public interface HytaleServerAdapter {
    int getOnlinePlayerCount();

    int getMaxPlayers();

    String getServerVersion();

    List<String> getEnabledPlugins();
}
