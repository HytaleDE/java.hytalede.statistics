package de.hytalede.statistics.hytale.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import de.hytalede.statistics.StatisticsPlugin;
import de.hytalede.statistics.StatisticsReporter;
import de.hytalede.statistics.hytale.StatisticsHytalePlugin;

import java.awt.Color;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * /stats send
 */
public final class StatsSendCommand extends CommandBase {
	private final StatisticsHytalePlugin plugin;

	public StatsSendCommand(StatisticsHytalePlugin plugin) {
		super("send", "hytalede.statistics.commands.stats.send.desc");
		this.plugin = Objects.requireNonNull(plugin, "plugin");
	}

	@Override
	protected void executeSync(CommandContext context) {
		StatisticsPlugin core = plugin.getCore();
		if (core == null) {
			context.sendMessage(Message.raw("Statistics plugin is not initialized yet.").color(Color.RED));
			return;
		}

		context.sendMessage(Message.raw("Triggering telemetry send...").color(Color.YELLOW));

		// Run async; sending does network IO.
		core.sendOnceNowAsync().whenComplete((result, err) -> {
			if (err != null) {
				Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				plugin.getLogger().at(Level.WARNING).withCause(cause).log("Manual /stats send failed");
				context.sendMessage(Message.raw("Telemetry send failed: " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()))
						.color(Color.RED));
				return;
			}

			StatisticsReporter.SendResult r = result;
			if (r.statusCode() == 204) {
				context.sendMessage(Message.raw("Telemetry accepted (204).").color(Color.GREEN));
			} else {
				String msg = "Telemetry returned HTTP " + r.statusCode();
				if (r.responseBody() != null && !r.responseBody().isBlank()) {
					msg += ": " + r.responseBody() + (r.responseBodyTruncated() ? "... (truncated)" : "");
				}
				context.sendMessage(Message.raw(msg).color(Color.YELLOW));
			}
		});
	}
}

