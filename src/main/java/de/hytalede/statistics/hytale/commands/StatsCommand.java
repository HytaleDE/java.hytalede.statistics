package de.hytalede.statistics.hytale.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import de.hytalede.statistics.hytale.StatisticsHytalePlugin;

import java.util.Objects;

/**
 * /stats ...
 */
public final class StatsCommand extends AbstractCommandCollection {
	private final StatisticsHytalePlugin plugin;

	public StatsCommand(StatisticsHytalePlugin plugin) {
		super("stats", "hytalede.statistics.commands.stats.desc");
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.addSubCommand(new StatsSendCommand(plugin));
	}
}

