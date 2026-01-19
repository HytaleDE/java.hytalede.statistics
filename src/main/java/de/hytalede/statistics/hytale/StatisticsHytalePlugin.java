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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
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
			new JsonStatisticsConfigLoader(configPath).load();
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

						List<String> plugins = PluginManager.get().getPlugins().stream()
								.map(PluginBase::getIdentifier)
								.map(Object::toString)
								.sorted(String.CASE_INSENSITIVE_ORDER)
								.toList();
						adapter.setEnabledPlugins(plugins);
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
}
