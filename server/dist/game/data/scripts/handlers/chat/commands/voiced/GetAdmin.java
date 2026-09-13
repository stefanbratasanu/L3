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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.xml.AdminData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * ###########################################################################
 * # TEMPORARY DEV BOOTSTRAP — DELETE BEFORE THIS SERVER IS EVER REACHABLE #
 * # BY ANYONE BUT YOU. ANY player can make themselves a full GM with it. #
 * ###########################################################################
 * <p>
 * Solves the chicken-and-egg problem that every {@code //} command requires GM already
 * ({@code AdminCommandHandler.onCommand} returns silently when {@code !player.isGM()}), so there
 * is no in-game way to grant yourself the first access level.
 * <p>
 * Typed in <b>normal ("All") chat</b>, because Mobius dispatches voiced commands from
 * {@code ChatGeneral} on a leading {@code '.'}. Note it is <b>{@code .getadmin}</b>, not
 * {@code /getadmin} — the L2 client only sends the {@code /} commands it knows about
 * (/loc, /time, …), each with a fixed numeric id, so a custom {@code /} name never reaches
 * the server at all.
 * <ul>
 * <li>{@code .getadmin} — grant yourself access level 100 ("Master", full GM).</li>
 * <li>{@code .getadmin off} — drop back to 0 (a normal player), e.g. to watch agents behave
 * exactly as they would for a real player.</li>
 * </ul>
 * The change applies immediately (no relog) and is also written straight to the database, so it
 * survives even if the character never saves normally.
 * <p>
 * To remove this command later: delete this file and its two lines in {@code MasterHandler.java}.
 *
 * @author L3
 */
public class GetAdmin implements IVoicedCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(GetAdmin.class.getName());

	private static final String[] VOICED_COMMANDS =
	{
		"getadmin"
	};

	/** 100 = "Master" in config/AccessLevels.xml (isGM, inherits every lower level). */
	private static final int GM_LEVEL = 100;
	private static final int PLAYER_LEVEL = 0;

	private static final String UPDATE_ACCESS = "UPDATE characters SET accesslevel=? WHERE charId=?";

	@Override
	public boolean onCommand(String command, Player player, String target)
	{
		if (!"getadmin".equals(command))
		{
			return false;
		}

		final boolean revoke = (target != null) && target.trim().equalsIgnoreCase("off");
		final int level = revoke ? PLAYER_LEVEL : GM_LEVEL;

		if (!AdminData.getInstance().hasAccessLevel(level))
		{
			player.sendMessage("Access level " + level + " is not defined in AccessLevels.xml.");
			return false;
		}

		// Apply in memory: takes effect at once (name/title colour change, // commands unlock).
		player.setAccessLevel(level);

		// Also persist right away. Player saves do include accesslevel, but writing it here means
		// the grant cannot be lost to a crash or a missed save.
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_ACCESS))
		{
			ps.setInt(1, level);
			ps.setInt(2, player.getObjectId());
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "GetAdmin: could not persist access level for " + player.getName(), e);
			player.sendMessage("Access level applied for this session, but saving it failed — see the game log.");
			return true;
		}

		if (revoke)
		{
			player.sendMessage("You are now a normal player (access level 0).");
			LOGGER.warning("GetAdmin: " + player.getName() + " (" + player.getObjectId() + ") dropped to access level 0.");
		}
		else
		{
			player.sendMessage("You are now a Master GM (access level 100). Try //l3spawn");
			LOGGER.warning("GetAdmin: " + player.getName() + " (" + player.getObjectId() + ") GRANTED access level 100 via the temporary .getadmin bootstrap.");
		}

		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
