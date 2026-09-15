package l3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

import l3.agent.L3Agent;

/**
 * Small, bounded observability layer for overnight agent runs.
 * <p>
 * TICK events are sampled and kept in memory only. State and goal events are written as compact
 * JSON-lines records so a long run remains useful without producing one log entry per scheduler
 * tick.
 */
public final class L3Debug
{
	private static final Logger LOGGER = Logger.getLogger(L3Debug.class.getName());
	private static final Deque<String> RECENT = new ArrayDeque<>(L3Config.DEBUG_RING_SIZE);
	private static long _lastTickLog;

	private L3Debug()
	{
	}

	public static synchronized void event(L3Agent agent, String type, String detail)
	{
		event(agent, categoryFor(type), type, detail);
	}

	public static synchronized void event(L3Agent agent, String category, String type, String detail)
	{
		final String record = "{\"at\":\"" + Instant.now() + "\",\"charId\":" + agent.getPlayer().getObjectId() + ",\"name\":\"" + escape(agent.getPlayer().getName()) + "\",\"level\":" + agent.getPlayer().getLevel() + ",\"lod\":\"" + agent.getLod() + "\",\"goal\":\"" + agent.getGoal() + "\",\"state\":\"" + agent.getState() + "\",\"category\":\"" + escape(category) + "\",\"type\":\"" + escape(type) + "\",\"detail\":\"" + escape(detail) + "\"}";
		write(record, "TICK".equals(type));
	}

	private static String categoryFor(String type)
	{
		switch (type)
		{
			case "TARGET":
			case "TICK":
				return "PERCEPTION";
			case "STATE":
			case "GOAL":
				return "DECISION";
			case "REGISTERED":
				return "LIFECYCLE";
			default:
				return "ACTION";
		}
	}

	public static synchronized void marker(String author, String detail)
	{
		final String record = "{\"at\":\"" + Instant.now() + "\",\"author\":\"" + escape(author) + "\",\"type\":\"SAFEPOINT\",\"detail\":\"" + escape(detail) + "\"}";
		write(record, false);
	}

	private static void write(String record, boolean sampled)
	{
		if (RECENT.size() >= L3Config.DEBUG_RING_SIZE)
		{
			RECENT.removeFirst();
		}
		RECENT.addLast(record);

		if (!L3Config.DEBUG_ENABLED || (sampled && !shouldLogTick()))
		{
			return;
		}

		try
		{
			Files.createDirectories(Path.of(L3Config.DEBUG_LOG_DIRECTORY));
			Files.writeString(Path.of(L3Config.DEBUG_LOG_DIRECTORY, "agents.jsonl"), record + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "L3: could not write debug record.", e);
		}
	}

	public static synchronized String recent(int limit)
	{
		final StringBuilder result = new StringBuilder();
		int skipped = Math.max(0, RECENT.size() - limit);
		for (String record : RECENT)
		{
			if (skipped-- > 0)
			{
				continue;
			}
			result.append(record).append('\n');
		}
		return result.toString();
	}

	private static boolean shouldLogTick()
	{
		final long now = System.currentTimeMillis();
		if ((now - _lastTickLog) < L3Config.DEBUG_TICK_SAMPLE_MS)
		{
			return false;
		}
		_lastTickLog = now;
		return true;
	}

	private static String escape(String value)
	{
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
