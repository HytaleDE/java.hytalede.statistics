package de.hytalede.statistics.hytale;

import de.hytalede.statistics.model.PlayerInfo;
import de.hytalede.statistics.model.PluginInfo;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe {@link HytaleServerAdapter} implementation backed by atomics.
 *
 * <p>Important: This adapter is designed so that the {@link de.hytalede.statistics.StatisticsReporter}
 * can run on its own scheduler thread without calling into potentially thread-affine Hytale APIs.
 * A host (plugin) should periodically update the atomics from a safe server thread.</p>
 */
public final class CachedHytaleServerAdapter implements HytaleServerAdapter {
	private final AtomicInteger onlinePlayers = new AtomicInteger(0);
	private final AtomicInteger maxPlayers = new AtomicInteger(0);
	private final AtomicReference<String> serverVersion = new AtomicReference<>("unknown");
	private final AtomicReference<List<String>> enabledPlugins = new AtomicReference<>(List.of());
	private final AtomicReference<List<PlayerInfo>> players = new AtomicReference<>(List.of());
	private final AtomicReference<List<PluginInfo>> pluginDetails = new AtomicReference<>(List.of());

	public void setOnlinePlayers(int value) {
		onlinePlayers.set(Math.max(0, value));
	}

	public void setMaxPlayers(int value) {
		// Snapshot contract requires slots > 0; clamp early so callers don't have to.
		maxPlayers.set(Math.max(1, value));
	}

	public void setServerVersion(String value) {
		serverVersion.set(Objects.requireNonNullElse(value, "unknown"));
	}

	public void setEnabledPlugins(List<String> plugins) {
		enabledPlugins.set(plugins == null ? List.of() : List.copyOf(plugins));
	}

	public void setPlayers(List<PlayerInfo> value) {
		players.set(value == null ? List.of() : List.copyOf(value));
	}

	public void setPluginDetails(List<PluginInfo> value) {
		pluginDetails.set(value == null ? List.of() : List.copyOf(value));
		// Keep string list in sync for callers that only need names.
		setEnabledPlugins(value == null ? List.of() : value.stream().map(PluginInfo::name).toList());
	}

	@Override
	public int getOnlinePlayerCount() {
		return onlinePlayers.get();
	}

	@Override
	public int getMaxPlayers() {
		return maxPlayers.get();
	}

	@Override
	public String getServerVersion() {
		return serverVersion.get();
	}

	@Override
	public List<String> getEnabledPlugins() {
		return enabledPlugins.get();
	}

	@Override
	public List<PlayerInfo> getOnlinePlayers() {
		return players.get();
	}

	@Override
	public List<PluginInfo> getEnabledPluginsDetailed() {
		return pluginDetails.get();
	}
}

