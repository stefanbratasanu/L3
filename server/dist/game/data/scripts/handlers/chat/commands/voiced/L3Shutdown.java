/*
 * Copyright (c) 2025 L3 Project
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package handlers.chat.commands.voiced;

import java.util.logging.Logger;

import org.l2jmobius.gameserver.Shutdown;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * {@code .sd} — shut the server down from inside the game, so a test session ends the same way it
 * would if you closed the windows: {@code L3-run.ps1} sees the process exit, dumps the database,
 * commits it and pushes, keeping the build box in sync.
 * <p>
 * This is a graceful Mobius shutdown ({@code GM_SHUTDOWN}), so characters — and later the L3
 * agents — are saved <b>before</b> the database is dumped. Killing the window instead risks
 * losing whatever had not been auto-saved yet.
 * <p>
 * Note this stops the <b>game</b> server; the login server is a separate process.
 * {@code L3-run.ps1} step 7 watches for either one to exit and then stops the other, so a single
 * {@code .sd} still ends the whole session cleanly.
 * <ul>
 * <li>{@code .sd} — shut down immediately.</li>
 * <li>{@code .sd 30} — shut down after 30 seconds (use {@code .sd abort} to cancel).</li>
 * <li>{@code .sd abort} — cancel a countdown already running.</li>
 * </ul>
 * GM only. It is a voiced command (leading dot, typed in normal "All" chat) because voiced
 * commands have no access-level gate of their own — hence the explicit {@code isGM()} check.
 *
 * @author L3
 */
public class L3Shutdown implements IVoicedCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(L3Shutdown.class.getName());

	private static final String[] VOICED_COMMANDS =
	{
		"sd"
	};

	/**
	 * Instant by default: this is a private dev box, so waiting out a countdown is pure friction.
	 * Use {@code .sd <seconds>} when you actually want a delay.
	 */
	private static final int DEFAULT_DELAY_SECONDS = 0;

	@Override
	public boolean onCommand(String command, Player player, String target)
	{
		if (!"sd".equals(command))
		{
			return false;
		}

		// Voiced commands are open to every player, so gate this one ourselves.
		if (!player.isGM())
		{
			player.sendMessage("Only a GM can shut the server down.");
			return false;
		}

		final String arg = (target == null) ? "" : target.trim().toLowerCase();

		if (arg.equals("abort"))
		{
			Shutdown.getInstance().abort(player);
			player.sendMessage("Shutdown aborted.");
			return true;
		}

		int seconds = DEFAULT_DELAY_SECONDS;
		if (arg.equals("now"))
		{
			seconds = 0;
		}
		else if (!arg.isEmpty())
		{
			try
			{
				seconds = Integer.parseInt(arg);
			}
			catch (NumberFormatException e)
			{
				player.sendMessage("Usage: .sd | .sd now | .sd <seconds> | .sd abort");
				return false;
			}

			if (seconds < 0)
			{
				player.sendMessage("Usage: .sd | .sd now | .sd <seconds> | .sd abort");
				return false;
			}
		}

		if (seconds == 0)
		{
			player.sendMessage("Shutting down now. The database and logs will be committed and pushed.");
		}
		else
		{
			player.sendMessage("Server shutting down in " + seconds + "s. DB and logs will be committed and pushed. (.sd abort to cancel)");
		}
		LOGGER.warning("L3Shutdown: " + player.getName() + " (" + player.getObjectId() + ") requested shutdown in " + seconds + "s via .sd");

		Shutdown.getInstance().startShutdown(player, seconds, false);
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
