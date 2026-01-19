package de.hytalede.statistics.hytale;

import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import de.hytalede.statistics.StatisticsPlugin;
import de.hytalede.statistics.hytale.commands.StatsCommand;
import de.hytalede.statistics.config.JsonStatisticsConfigLoader;
import de.hytalede.statistics.config.StatisticsConfig;
import de.hytalede.statistics.model.PlayerInfo;
import de.hytalede.statistics.model.PluginInfo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Hytale runtime adapter for {@link StatisticsPlugin}.
 *
 * <p>Lifecycle:\n
 * <ul>
 *   <li>{@link #setup()} prepares config + adapter wiring</li>
 *   <li>{@link #start()} starts reporting</li>
 *   <li>{@link #shutdown()} stops reporting</li>
 * </ul>
 * </p>
 */
public final class StatisticsHytalePlugin extends JavaPlugin {
	private static final String DEFAULT_CONFIG_RESOURCE = "/statistics.json";
	private static final String CONFIG_FILENAME = "statistics.json";
	private static final long CACHE_REFRESH_SECONDS = 2;
	private static final long STARTUP_DELAY_SECONDS = 30;

	private StatisticsPlugin core;
	private CachedHytaleServerAdapter cachedAdapter;
	private ScheduledFuture<Void> cacheTask;
	private ScheduledFuture<Void> delayedStartTask;
	private boolean sendPlayerList;
	private boolean sendPluginList;

	public StatisticsHytalePlugin(JavaPluginInit init) {
		super(Objects.requireNonNull(init, "init"));
	}

	@Override
	protected void setup() {
		Path configPath = getDataDirectory().resolve(CONFIG_FILENAME);
		ensureDefaultConfig(configPath);

		this.cachedAdapter = new CachedHytaleServerAdapter();
		startCacheUpdates();

		// Validate config early so a broken JSON doesn't crash later in start(), and the log points to the real cause.
		try {
			StatisticsConfig config = new JsonStatisticsConfigLoader(configPath).load();
			this.sendPlayerList = config.sendPlayerList();
			this.sendPluginList = config.sendPluginList();
		} catch (Exception e) {
			getLogger().at(Level.SEVERE).withCause(e).log(
					"Invalid statistics config (%s). Required fields: endpoint, bearerToken, vanityUrl. Plugin will not start until fixed.",
					configPath.toAbsolutePath().toString()
			);
			this.core = null;
			return;
		}

		this.core = new StatisticsPlugin(configPath, cachedAdapter);

		// /stats ...
		this.getCommandRegistry().registerCommand(new StatsCommand(this));
	}

	@Override
	protected void start() {
		if (core == null) {
			getLogger().at(Level.WARNING).log("Statistics core was not initialized; skipping start()");
			return;
		}

		// Delay first start so we report stable values (maxPlayers/plugins) after the server finished booting.
		if (delayedStartTask != null && !delayedStartTask.isCancelled()) {
			return;
		}

		getLogger().at(Level.INFO).log("Statistics reporting will start in %d seconds...", STARTUP_DELAY_SECONDS);
		@SuppressWarnings("unchecked")
		ScheduledFuture<Void> task = (ScheduledFuture<Void>)(ScheduledFuture<?>) HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
			try {
				StatisticsPlugin c = this.core;
				if (c == null || isDisabled()) {
					return;
				}
				boolean ok = c.startSafely();
				if (ok) {
					getLogger().at(Level.INFO).log("Statistics reporting started.");
				} else {
					getLogger().at(Level.WARNING).log("Statistics reporting failed to start (see previous logs).");
				}
			} catch (Throwable t) {
				getLogger().at(Level.SEVERE).withCause(t).log("Unhandled exception while starting statistics reporting (delayed start)");
			}
		}, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
		this.delayedStartTask = task;
		getTaskRegistry().registerTask(task);
	}

	@Override
	protected void shutdown() {
		if (delayedStartTask != null) {
			delayedStartTask.cancel(false);
			delayedStartTask = null;
		}
		stopCacheUpdates();
		if (core != null) {
			try {
				core.close();
			} catch (Exception e) {
				getLogger().at(Level.WARNING).withCause(e).log("Failed to close statistics core");
			} finally {
				core = null;
			}
		}
		cachedAdapter = null;
	}

	private void startCacheUpdates() {
		stopCacheUpdates();

		@SuppressWarnings("unchecked")
		ScheduledFuture<Void> task = (ScheduledFuture<Void>)(ScheduledFuture<?>) HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
			try {
				// Snapshot adapter reference to avoid races with shutdown().
				CachedHytaleServerAdapter adapter = this.cachedAdapter;
				if (adapter == null || isDisabled()) {
					return;
				}

				Universe universe = Universe.get();
				World world = universe != null ? universe.getDefaultWorld() : null;
				if (world == null) {
					return;
				}

				// Ensure all Hytale API calls happen on the world thread.
				world.execute(() -> {
					try {
						Universe u = Universe.get();
						adapter.setOnlinePlayers(u != null ? u.getPlayerCount() : 0);
						adapter.setMaxPlayers(HytaleServer.get().getConfig().getMaxPlayers());

						String v = ManifestUtil.getImplementationVersion();
						adapter.setServerVersion(v != null && !v.isBlank() ? v : "unknown");

						if (sendPluginList) {
							adapter.setPluginDetails(extractPluginDetails());
						} else {
							// Keep lightweight names list updated even if detailed list is disabled.
							List<String> plugins = PluginManager.get().getPlugins().stream()
									.map(PluginBase::getIdentifier)
									.map(Object::toString)
									.sorted(String.CASE_INSENSITIVE_ORDER)
									.toList();
							adapter.setEnabledPlugins(plugins);
						}

						if (sendPlayerList) {
							adapter.setPlayers(extractPlayers(u));
						}
					} catch (Throwable t) {
						getLogger().at(Level.WARNING).withCause(t).log("Failed to update statistics cache");
					}
				});
			} catch (Throwable t) {
				getLogger().at(Level.SEVERE).withCause(t).log("Unhandled exception in statistics cache scheduler");
			}
		}, 0, CACHE_REFRESH_SECONDS, TimeUnit.SECONDS);
		this.cacheTask = task;

		// Ensure this task is cleaned up when the plugin unloads.
		getTaskRegistry().registerTask(task);
	}

	private void stopCacheUpdates() {
		if (cacheTask != null) {
			cacheTask.cancel(false);
			cacheTask = null;
		}
	}

	private void ensureDefaultConfig(Path configPath) {
		try {
			if (Files.exists(configPath)) {
				return;
			}
			Files.createDirectories(configPath.getParent());
			try (InputStream in = StatisticsHytalePlugin.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
				if (in == null) {
					getLogger().at(Level.WARNING).log("Default config resource missing: %s", DEFAULT_CONFIG_RESOURCE);
					return;
				}
				Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
			}
			getLogger().at(Level.INFO).log("Created default config: %s", configPath.toAbsolutePath().toString());
		} catch (IOException e) {
			getLogger().at(Level.WARNING).withCause(e).log("Failed to create default statistics config at %s", configPath.toAbsolutePath().toString());
		}
	}

	public StatisticsPlugin getCore() {
		return core;
	}

	private static List<PluginInfo> extractPluginDetails() {
		return PluginManager.get().getPlugins().stream()
				.map(StatisticsHytalePlugin::toPluginInfo)
				.filter(Objects::nonNull)
				.sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()))
				.toList();
	}

	private static PluginInfo toPluginInfo(PluginBase plugin) {
		if (plugin == null) {
			return null;
		}
		String name = String.valueOf(plugin.getIdentifier());
		String version = tryInvokeString(plugin, "getVersion");
		if (version == null) {
			version = tryInvokeString(plugin, "getPluginVersion");
		}
		if (version == null) {
			version = tryInvokeString(plugin, "getImplementationVersion");
		}
		if (version == null || version.isBlank()) {
			version = "unknown";
		}
		return new PluginInfo(name, version.trim());
	}

	private static List<PlayerInfo> extractPlayers(Universe universe) {
		if (universe == null) {
			return List.of();
		}

		// Use reflection so we don't rely on a specific API surface; if it doesn't exist, we just return empty.
		Iterable<?> iterable = null;
		Object playersObj = tryInvoke(universe, "getPlayers");
		if (playersObj instanceof Iterable<?> it) {
			iterable = it;
		} else {
			playersObj = tryInvoke(universe, "getOnlinePlayers");
			if (playersObj instanceof Iterable<?> it2) {
				iterable = it2;
			}
		}

		if (iterable == null) {
			return List.of();
		}

		return streamIterable(iterable)
				.map(StatisticsHytalePlugin::toPlayerInfo)
				.filter(Objects::nonNull)
				.toList();
	}

	private static PlayerInfo toPlayerInfo(Object player) {
		if (player == null) {
			return null;
		}

		String uuid = null;
		Object id = tryInvoke(player, "getUuid");
		if (id == null) {
			id = tryInvoke(player, "getUniqueId");
		}
		if (id instanceof UUID u) {
			uuid = u.toString();
		} else if (id != null) {
			uuid = id.toString();
		}

		String name = tryInvokeString(player, "getName");
		if (name == null) {
			name = tryInvokeString(player, "getUsername");
		}

		// joined timestamp: best-effort ISO-8601 UTC string
		String joined = null;
		Object joinedObj = tryInvoke(player, "getJoined");
		if (joinedObj == null) {
			joinedObj = tryInvoke(player, "getJoinedAt");
		}
		if (joinedObj != null) {
			joined = joinedObj.toString();
		}

		if (uuid == null || uuid.isBlank() || name == null || name.isBlank()) {
			return null;
		}
		return new PlayerInfo(uuid, name, joined);
	}

	private static Object tryInvoke(Object target, String methodName) {
		try {
			Method m = target.getClass().getMethod(methodName);
			m.setAccessible(true);
			return m.invoke(target);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String tryInvokeString(Object target, String methodName) {
		Object v = tryInvoke(target, methodName);
		if (v == null) {
			return null;
		}
		String s = v.toString();
		return s == null || s.isBlank() ? null : s;
	}

	private static java.util.stream.Stream<Object> streamIterable(Iterable<?> iterable) {
		return java.util.stream.StreamSupport.stream(iterable.spliterator(), false).map(o -> (Object) o);
	}
}
