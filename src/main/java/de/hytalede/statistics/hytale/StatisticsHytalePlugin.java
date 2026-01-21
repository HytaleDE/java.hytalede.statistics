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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
	// Delay first start a bit so we report stable values (maxPlayers/plugins) after the server finished booting.
	private static final long STARTUP_DELAY_SECONDS = 15;
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Map<String, String> PLUGIN_VERSION_BY_JAR = new ConcurrentHashMap<>();

	private StatisticsPlugin core;
	private CachedHytaleServerAdapter cachedAdapter;
	private ScheduledFuture<Void> cacheTask;
	private ScheduledFuture<Void> delayedStartTask;
	private boolean sendPlayerList;
	private boolean sendPluginList;
	/**
	 * Best-effort "joined" timestamp cache. If the Hytale API doesn't expose a join time, we fall back
	 * to the moment we first observe a player in the online list.
	 */
	private final Map<String, String> joinedByUuid = new ConcurrentHashMap<>();

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
							adapter.setPlayers(extractPlayers(u, joinedByUuid));
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
		String technicalId = String.valueOf(plugin.getIdentifier());
		if (HytaleServerAdapter.isIgnoredPluginName(technicalId)) {
			return null;
		}

		// Prefer a user-facing name if the API provides one, otherwise fall back to the technical identifier.
		String displayName = tryInvokeString(plugin, "getDisplayName");
		if (displayName == null) {
			displayName = tryInvokeString(plugin, "getName");
		}
		if (displayName == null) {
			displayName = tryInvokeString(plugin, "getTitle");
		}
		String name = (displayName == null || displayName.isBlank()) ? technicalId : displayName.trim();

		String version = resolvePluginVersion(plugin);
		return new PluginInfo(name, version);
	}

	private static String resolvePluginVersion(PluginBase plugin) {
		String version = tryInvokeString(plugin, "getVersion");
		if (version == null) {
			version = tryInvokeString(plugin, "getPluginVersion");
		}
		if (version == null) {
			version = tryInvokeString(plugin, "getImplementationVersion");
		}
		if (version == null) {
			// Some APIs expose a manifest/descriptor object
			Object manifest = tryInvoke(plugin, "getManifest");
			if (manifest != null) {
				version = tryInvokeString(manifest, "getVersion");
				if (version == null) {
					version = tryInvokeString(manifest, "getPluginVersion");
				}
			}
		}
		if (version == null) {
			version = resolveVersionFromJarManifest(plugin);
		}
		if (version == null || version.isBlank()) {
			return "unknown";
		}
		return version.trim();
	}

	/**
	 * Reads the plugin {@code manifest.json} from the jar and extracts {@code Version}.
	 *
	 * <p>This is the most reliable source because most Hytale mods ship this file.</p>
	 */
	private static String resolveVersionFromJarManifest(PluginBase plugin) {
		try {
			URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
			if (location == null) {
				return null;
			}
			Path jarPath = Paths.get(location.toURI());
			String key = jarPath.toAbsolutePath().toString();

			return PLUGIN_VERSION_BY_JAR.computeIfAbsent(key, ignored -> {
				String v = readManifestVersionFromJar(jarPath);
				if (v != null) {
					return v;
				}
				// Fallback: guess from jar file name (e.g. name-1.2.3.jar)
				return guessVersionFromJarFileName(jarPath.getFileName().toString());
			});
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String readManifestVersionFromJar(Path jarPath) {
		try {
			if (jarPath == null || !Files.exists(jarPath)) {
				return null;
			}
			try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
				java.util.jar.JarEntry entry = jar.getJarEntry("manifest.json");
				if (entry == null) {
					return null;
				}
				try (InputStream in = jar.getInputStream(entry)) {
					JsonNode root = JSON.readTree(in);
					if (root == null) {
						return null;
					}
					JsonNode v = root.get("Version");
					if (v == null || v.isNull()) {
						v = root.get("version");
					}
					if (v == null || v.isNull()) {
						return null;
					}
					String s = v.asText(null);
					return (s == null || s.isBlank()) ? null : s.trim();
				}
			}
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String guessVersionFromJarFileName(String fileName) {
		if (fileName == null) {
			return null;
		}
		String name = fileName.trim();
		if (!name.toLowerCase().endsWith(".jar")) {
			return null;
		}
		name = name.substring(0, name.length() - 4);
		// naive: last '-' segment that starts with a digit
		int idx = name.lastIndexOf('-');
		if (idx < 0 || idx == name.length() - 1) {
			return null;
		}
		String tail = name.substring(idx + 1);
		if (tail.isEmpty() || !Character.isDigit(tail.charAt(0))) {
			return null;
		}
		return tail;
	}

	private static List<PlayerInfo> extractPlayers(Universe universe, Map<String, String> joinedByUuid) {
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

		List<PlayerInfo> players = streamIterable(iterable)
				.map(p -> toPlayerInfo(p, joinedByUuid))
				.filter(Objects::nonNull)
				.toList();

		// Keep map bounded: drop entries for players that are no longer online.
		if (joinedByUuid != null && !joinedByUuid.isEmpty()) {
			java.util.Set<String> online = players.stream().map(PlayerInfo::uuid).collect(java.util.stream.Collectors.toSet());
			joinedByUuid.keySet().removeIf(uuid -> !online.contains(uuid));
		}

		return players;
	}

	private static PlayerInfo toPlayerInfo(Object player, Map<String, String> joinedByUuid) {
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
		String joined = extractJoinedUtcIso(player);
		if ((joined == null || joined.isBlank()) && joinedByUuid != null && uuid != null && !uuid.isBlank()) {
			joined = joinedByUuid.computeIfAbsent(uuid, ignored -> java.time.Instant.now().toString());
		}

		if (uuid == null || uuid.isBlank() || name == null || name.isBlank()) {
			return null;
		}
		return new PlayerInfo(uuid, name, joined);
	}

	/**
	 * Best-effort conversion to an ISO-8601 UTC timestamp (ending with {@code Z}).
	 *
	 * <p>We intentionally keep this tolerant because the Hytale API surface may evolve and can return
	 * different time representations.</p>
	 */
	private static String extractJoinedUtcIso(Object player) {
		Object joinedObj = null;
		for (String method : List.of(
				"getJoinedAt",
				"getJoined",
				"getJoinTime",
				"getLoginTime",
				"getConnectedAt",
				"getConnectedSince",
				"getSessionStart",
				"getSessionStartTime",
				"getSessionStartMillis",
				"getFirstJoinAt"
		)) {
			joinedObj = tryInvoke(player, method);
			if (joinedObj != null) {
				break;
			}
		}
		if (joinedObj == null) {
			return null;
		}

		try {
			// java.time types
			if (joinedObj instanceof java.time.Instant instant) {
				return instant.toString();
			}
			if (joinedObj instanceof java.time.OffsetDateTime odt) {
				return odt.toInstant().toString();
			}
			if (joinedObj instanceof java.time.ZonedDateTime zdt) {
				return zdt.toInstant().toString();
			}
			if (joinedObj instanceof java.time.LocalDateTime ldt) {
				return ldt.atOffset(java.time.ZoneOffset.UTC).toInstant().toString();
			}

			// java.util.Date
			if (joinedObj instanceof java.util.Date date) {
				return date.toInstant().toString();
			}

			// epoch timestamps
			if (joinedObj instanceof Number n) {
				long v = n.longValue();
				// Heuristic: >= 10^12 is likely epoch millis; otherwise treat as epoch seconds.
				java.time.Instant instant = v >= 1_000_000_000_000L
						? java.time.Instant.ofEpochMilli(v)
						: java.time.Instant.ofEpochSecond(v);
				return instant.toString();
			}

			// If it's already a string-like ISO timestamp, pass through.
			String s = joinedObj.toString();
			if (s == null) {
				return null;
			}
			s = s.trim();
			if (s.isBlank()) {
				return null;
			}
			// Try parsing as Instant; if it works, normalize to UTC.
			try {
				return java.time.Instant.parse(s).toString();
			} catch (Exception ignored) {
				// fall through
			}
			return null;
		} catch (Exception ignored) {
			return null;
		}
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
