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
package handlers.chat.commands.admin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.network.GameClient;

import l3.agent.L3Agent;
import l3.agent.L3AgentManager;
import l3.ai.L3ThinkTaskManager;

/**
 * L3 v1 spawn primitive test.<br>
 * Proves the clientless-Player mechanism (the same one {@code OfflinePlayTable} uses) works on
 * real hardware: synthesizes a fresh character with NO {@link GameClient} / socket and inserts it
 * into the world next to the observer. It just stands there — no AI yet. This is the foundation the
 * real {@code L3AgentManager} (Milestone 2) is built on.
 * <p>
 * Commands:
 * <ul>
 * <li>{@code //l3spawn} — mint one puppet (Human Fighter) ~150 units in front of you.</li>
 * <li>{@code //l3spawn clean} — remove every puppet this command created (world + DB rows).</li>
 * </ul>
 * Puppets are real character rows (via {@link Player#create}), exactly like future agents, so
 * {@code clean} deletes both the in-world object and its DB record to keep the synced snapshot tidy.
 *
 * @author L3
 */
public class AdminL3Spawn implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminL3Spawn.class.getName());

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_l3spawn"
	};

	// classId 0 = Human Fighter (a valid Interlude starting class → template always present).
	private static final int PUPPET_CLASS_ID = 0;
	// Throwaway account name shared by all puppets (real players use their login account name).
	private static final String PUPPET_ACCOUNT = "l3agents";
	// Distance in front of the observer to place the puppet.
	private static final int SPAWN_OFFSET = 150;

	// Object ids of every puppet we created, so //l3spawn clean can remove exactly those.
	private static final Set<Integer> SPAWNED = Collections.synchronizedSet(new HashSet<>());

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (activeChar == null)
		{
			return false;
		}

		final String args = command.length() > "admin_l3spawn".length() ? command.substring("admin_l3spawn".length()).trim() : "";

		if (args.equalsIgnoreCase("clean"))
		{
			cleanup(activeChar);
			return true;
		}

		spawnOne(activeChar);
		return true;
	}

	private void spawnOne(Player observer)
	{
		Player puppet = null;
		try
		{
			final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(PUPPET_CLASS_ID);
			if (template == null)
			{
				observer.sendSysMessage("L3: no player template for classId " + PUPPET_CLASS_ID + ".");
				return;
			}

			// Fresh, unique-ish name. characters.char_name is unique, so retry-proof enough for a test.
			final String name = "L3Agent" + (Rnd.get(100000, 999999));

			// face / hairColor / hairStyle / isFemale — appearance is cosmetic; zeros are valid.
			final PlayerAppearance appearance = new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false);

			// The real synthesis path: builds a Player with a null GameClient and writes its DB row.
			puppet = Player.create(template, PUPPET_ACCOUNT, name, appearance);
			if (puppet == null)
			{
				observer.sendSysMessage("L3: Player.create returned null (name clash? DB error?). Try again.");
				return;
			}

			// --- The clientless in-world sequence, mirroring OfflinePlayTable.restoreOfflinePlayers() ---
			puppet.setOnlineStatus(true, false);

			// Place it a short distance in front of the observer (same Z/instance, no client involved).
			final double angle = Math.toRadians(observer.getHeading() / 182.044); // client heading → degrees
			final int x = observer.getX() + (int) (Math.cos(angle) * SPAWN_OFFSET);
			final int y = observer.getY() + (int) (Math.sin(angle) * SPAWN_OFFSET);
			final int z = observer.getZ();
			puppet.setXYZ(x, y, z);
			puppet.spawnMe(x, y, z); // inserts into World + region grid = visible/in-world

			puppet.setOnlineStatus(true, true);
			puppet.setRunning();
			// -------------------------------------------------------------------------------------------

			SPAWNED.add(puppet.getObjectId());

			// Hand the body to the brain: register it and put it in a thinking pool. Without this
			// the puppet just stands there (which is all v1 did).
			final L3Agent agent = L3AgentManager.getInstance().register(puppet);
			if (agent == null)
			{
				observer.sendSysMessage("L3: spawned " + name + " but could NOT register it (population ceiling?). It will not act.");
			}
			else
			{
				L3ThinkTaskManager.getInstance().add(agent);
			}

			observer.sendSysMessage("L3: spawned " + name + " (objId " + puppet.getObjectId() + "). Agents: " + L3AgentManager.getInstance().size());
			LOGGER.info("L3Spawn: " + observer.getName() + " spawned clientless puppet " + name + " (" + puppet.getObjectId() + ") at " + x + "," + y + "," + z);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3Spawn: failed to spawn puppet", e);
			observer.sendSysMessage("L3: spawn FAILED — " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (see game log).");
			// Best-effort: if it half-created, get it out of the world.
			if (puppet != null)
			{
				try
				{
					puppet.deleteMe();
				}
				catch (Exception ignored)
				{
					// nothing more we can do here
				}
			}
		}
	}

	private void cleanup(Player observer)
	{
		final Integer[] ids;
		synchronized (SPAWNED)
		{
			ids = SPAWNED.toArray(new Integer[0]);
			SPAWNED.clear();
		}

		if (ids.length == 0)
		{
			observer.sendSysMessage("L3: no puppets to clean.");
			return;
		}

		int removed = 0;
		for (int objId : ids)
		{
			try
			{
				// Take it out of the thinking pools first, so no tick can touch a deleted player.
				final L3Agent agent = L3AgentManager.getInstance().get(objId);
				if (agent != null)
				{
					L3ThinkTaskManager.getInstance().remove(agent);
					L3AgentManager.getInstance().unregister(objId);
				}

				final Player puppet = World.getPlayer(objId);
				if (puppet != null)
				{
					puppet.deleteMe(); // remove from world
				}
				GameClient.deleteCharByObjId(objId); // remove the DB row(s) so the snapshot stays clean
				removed++;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3Spawn: failed to clean puppet " + objId, e);
			}
		}

		observer.sendSysMessage("L3: cleaned " + removed + " of " + ids.length + " puppet(s).");
		LOGGER.info("L3Spawn: " + observer.getName() + " cleaned " + removed + " puppet(s).");
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
