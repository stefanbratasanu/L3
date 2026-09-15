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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.commons.time.GameTime;
import org.l2jmobius.gameserver.Shutdown;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

import l3.L3Config;
import l3.L3Locations;
import l3.agent.L3Agent;
import l3.agent.L3AgentManager;
import l3.L3Debug;

/**
 * Admin tools for spawning and inspecting L3 agents.
 * <ul>
 * <li>{@code //l3spawn} - one agent in front of you.</li>
 * <li>{@code //l3spawn 25} - that many, scattered around you.</li>
 * <li>{@code //l3spawnname Aldric} - one agent with a specific name.</li>
 * <li>{@code //l3spawnturbo} - one deliberately overpowered test agent, so you can watch behaviour
 * without it dying halfway through.</li>
 * <li>{@code //l3spawnworld 200} - that many spread randomly across the towns of the world.</li>
 * <li>{@code //gotonext} - teleport to the next agent, cycling through the population.</li>
 * <li>{@code //l3spawn clean} - delete the agents spawned by these commands this session.</li>
 * </ul>
 * Agents created here are part of the <b>permanent</b> population: real characters that survive
 * restarts, up to {@link L3Config#POPULATION_CAP}. Turbo agents are the
 * exception - their stats are runtime-only, so they live on a separate throwaway account.
 *
 * @author L3
 */
public class AdminL3Spawn implements IAdminCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(AdminL3Spawn.class.getName());

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_l3spawn",
		"admin_l3spawnname",
		"admin_l3spawnturbo",
		"admin_l3spawnworld",
		"admin_populate",
		"admin_npcspawn",
		"admin_l3info",
		"admin_l3clean",
		"admin_l3wipe",
		"admin_sdwipedb",
		"admin_gotonext",
		"admin_l3debug",
		"admin_l3agent",
		"admin_l3spawnprius",
		"admin_safepoint",
		"admin_gamespeed"
	};

	/** Distance in front of the observer to place a single agent. */
	private static final int SPAWN_OFFSET = 150;

	/** Sanity limit for one command, so a typo cannot ask for a million agents. */
	private static final int MAX_PER_COMMAND = 1000;

	/** Object ids spawned by these commands this session, so 'clean' can undo exactly those. */
	private static final Set<Integer> SPAWNED = Collections.synchronizedSet(new HashSet<>());

	/** Cycle position for //gotonext. */
	private static int _gotoIndex;

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (activeChar == null)
		{
			return false;
		}

		final String[] parts = command.split(" ");
		final String cmd = parts[0];
		final String arg = (parts.length > 1) ? command.substring(cmd.length()).trim() : "";

		switch (cmd)
		{
			case "admin_l3debug":
			{
				showDebug(activeChar);
				return true;
			}
			case "admin_l3agent":
			{
				showAgent(activeChar, arg);
				return true;
			}
			case "admin_l3spawnprius":
			{
				return spawnPrius(activeChar);
			}
			case "admin_safepoint":
			{
				if (arg.isEmpty())
				{
					activeChar.sendSysMessage("Usage: //safepoint <text>");
					return false;
				}

				L3Debug.marker(activeChar.getName(), arg);
				activeChar.sendSysMessage("L3: safe point recorded: " + arg);
				return true;
			}
			case "admin_gamespeed":
			{
				return setGameSpeed(activeChar, arg);
			}
			case "admin_gotonext":
			{
				return gotoNext(activeChar);
			}
			case "admin_npcspawn":
			{
				showPanel(activeChar);
				return true;
			}
			case "admin_l3info":
			{
				showInfo(activeChar);
				return true;
			}
			case "admin_populate":
			{
				return populate(activeChar, parseCount(arg, 10));
			}
			case "admin_l3clean":
			{
				cleanup(activeChar);
				return true;
			}
			case "admin_sdwipedb":
			{
				return shutdownAndWipe(activeChar);
			}
			case "admin_l3wipe":
			{
				return wipeAgents(activeChar);
			}
			case "admin_l3spawnturbo":
			{
				return spawnTurbo(activeChar);
			}
			case "admin_l3spawnname":
			{
				if (arg.isEmpty())
				{
					activeChar.sendSysMessage("Usage: //l3spawnname <name>");
					return false;
				}

				return spawnNamed(activeChar, arg);
			}
			case "admin_l3spawnworld":
			{
				return spawnWorld(activeChar, parseCount(arg, 1));
			}
			default: // admin_l3spawn
			{
				if (arg.equalsIgnoreCase("clean"))
				{
					cleanup(activeChar);
					return true;
				}

				return spawnHere(activeChar, parseCount(arg, 1));
			}
		}
	}

		private void showDebug(Player observer)
		{
			observer.sendSysMessage("=== L3 debug ===");
			observer.sendSysMessage("Records: " + L3Config.DEBUG_LOG_DIRECTORY + "/agents.jsonl");
			observer.sendSysMessage(L3Debug.recent(8).replace('\n', ' '));
		}

		private void showAgent(Player observer, String name)
		{
			for (L3Agent agent : L3AgentManager.getInstance().getAgents())
			{
				if (name.isEmpty() || agent.getPlayer().getName().equalsIgnoreCase(name))
				{
					observer.sendSysMessage("L3: " + agent.getPlayer().getName() + " id=" + agent.getObjectId() + " level=" + agent.getPlayer().getLevel() + " lod=" + agent.getLod() + " goal=" + agent.getGoal() + " state=" + agent.getState() + " target=" + agent.getTargetObjectId());
					return;
				}
			}
			observer.sendSysMessage("L3: agent not found: " + name);
		}

		private boolean spawnPrius(Player observer)
		{
			final L3Agent agent = L3AgentManager.getInstance().spawnNew(inFrontOf(observer), "Prius", false, 0);
			if (agent == null)
			{
				observer.sendSysMessage("L3: Prius could not be created (name may already exist or the population cap is full).");
				return false;
			}

			SPAWNED.add(agent.getObjectId());
			observer.sendSysMessage("L3: Prius spawned as a level 1 Human Fighter with standard starter gear.");
			LOGGER.info("L3Spawn: " + observer.getName() + " spawned deterministic test agent Prius.");
			return true;
		}

	private boolean setGameSpeed(Player observer, String arg)
	{
		if (arg.isEmpty())
		{
			observer.sendSysMessage("Game speed: " + GameTime.getSpeed() + "x (0 pauses game-time progression).");
			return true;
		}

		try
		{
			final double speed = Double.parseDouble(arg);
			if ((speed < 0) || (speed > 20))
			{
				observer.sendSysMessage("Usage: //gamespeed <0-20> (0 pauses game-time progression).");
				return false;
			}

			GameTime.setSpeed(speed);
			L3Debug.marker(observer.getName(), "gamespeed=" + speed);
			observer.sendSysMessage("Game speed set to " + speed + "x.");
			return true;
		}
		catch (NumberFormatException e)
		{
			observer.sendSysMessage("Usage: //gamespeed <0-20> (0 pauses game-time progression).");
			return false;
		}
	}

	private static int parseCount(String arg, int fallback)
	{
		if (arg.isEmpty())
		{
			return fallback;
		}

		try
		{
			return Math.max(1, Math.min(MAX_PER_COMMAND, Integer.parseInt(arg)));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	// --- spawn variants -------------------------------------------------------------------------

	/** One or more agents around the observer. */
	private boolean spawnHere(Player observer, int count)
	{
		int made = 0;
		for (int i = 0; i < count; i++)
		{
			// A single agent goes right in front; a batch scatters so they do not stack up.
			final Location location = (count == 1) ? inFrontOf(observer) : new Location(observer.getX() + Rnd.get(-250, 250), observer.getY() + Rnd.get(-250, 250), observer.getZ());

			if (spawn(observer, location, null, false) != null)
			{
				made++;
			}
		}

		report(observer, made, count);
		return made > 0;
	}

	private boolean spawnNamed(Player observer, String name)
	{
		// Mobius validates names on creation; a clash or an illegal name simply returns null.
		if (spawn(observer, inFrontOf(observer), name, false) == null)
		{
			observer.sendSysMessage("L3: could not create '" + name + "'. Name taken, too long, or invalid characters?");
			return false;
		}

		observer.sendSysMessage("L3: spawned '" + name + "'. Agents: " + L3AgentManager.getInstance().size());
		return true;
	}

	private boolean spawnTurbo(Player observer)
	{
		final L3Agent agent = spawn(observer, inFrontOf(observer), null, true);
		if (agent == null)
		{
			observer.sendSysMessage("L3: turbo spawn failed (see the game log).");
			return false;
		}

		observer.sendSysMessage("L3: turbo agent '" + agent.getPlayer().getName() + "' spawned (+" + L3Config.TURBO_MOVE_SPEED + " speed, +" + L3Config.TURBO_ATTACK_SPEED + " atk.spd, +" + L3Config.TURBO_PHYSICAL_ATTACK + " p.atk).");
		observer.sendSysMessage("L3: it is a throwaway test agent - it will not come back after a restart.");
		return true;
	}

	/**
	 * The bulk world-populate: agents spread over hunting grounds and towns. This is the one to use
	 * for building a living world, and it is what {@code //populate} and {@code //l3spawnworld} both
	 * do - most agents land in fields, because that is where there is anything to do.
	 */
	private boolean populate(Player observer, int count)
	{
		final L3AgentManager manager = L3AgentManager.getInstance();
		final int before = manager.size();
		final int made = manager.populate(count);

		// Track them so //l3clean can undo a populate run.
		for (L3Agent agent : manager.getAgents())
		{
			SPAWNED.add(agent.getObjectId());
		}

		observer.sendSysMessage("L3: created " + made + " of " + count + " agents (" + before + " -> " + manager.size() + ", cap " + L3Config.POPULATION_CAP + ").");
		observer.sendSysMessage("L3: spread over " + L3Locations.FARM_AREAS.length + " hunting grounds and " + L3Locations.TOWNS.length + " towns. //gotonext to visit.");
		LOGGER.info("L3Spawn: " + observer.getName() + " populated " + made + " agents.");
		return made > 0;
	}

	/** Kept as an alias of //populate, since it does exactly the same thing. */
	private boolean spawnWorld(Player observer, int count)
	{
		return populate(observer, count);
	}

	private L3Agent spawn(Player observer, Location location, String name, boolean turbo)
	{
		try
		{
			final L3Agent agent = L3AgentManager.getInstance().spawnNew(location, name, turbo);
			if (agent != null)
			{
				SPAWNED.add(agent.getObjectId());
			}

			return agent;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3Spawn: spawn failed", e);
			observer.sendSysMessage("L3: spawn FAILED - " + e.getClass().getSimpleName() + ": " + e.getMessage());
			return null;
		}
	}

	private static Location inFrontOf(Player observer)
	{
		final double angle = Math.toRadians(observer.getHeading() / 182.044); // client heading -> degrees
		return new Location((int) (observer.getX() + (Math.cos(angle) * SPAWN_OFFSET)), (int) (observer.getY() + (Math.sin(angle) * SPAWN_OFFSET)), observer.getZ());
	}

	private void report(Player observer, int made, int asked)
	{
		if (made == asked)
		{
			observer.sendSysMessage("L3: spawned " + made + ". Agents: " + L3AgentManager.getInstance().size());
		}
		else
		{
			observer.sendSysMessage("L3: spawned " + made + " of " + asked + " (population cap or name clashes). Agents: " + L3AgentManager.getInstance().size());
		}

		LOGGER.info("L3Spawn: " + observer.getName() + " spawned " + made + "/" + asked + " agents.");
	}

	// --- panel & info ---------------------------------------------------------------------------

	/** Opens the L3 control panel (data/html/admin/l3.htm), the same way //spawn opens its window. */
	private void showPanel(Player observer)
	{
		final NpcHtmlMessage html = new NpcHtmlMessage(0, 1);
		html.setFile(observer, "data/html/admin/l3.htm");
		html.replace("%agents%", String.valueOf(L3AgentManager.getInstance().size()));
		html.replace("%cap%", String.valueOf(L3Config.POPULATION_CAP));
		observer.sendPacket(html);
	}

	/** A population readout, so the panel can show live numbers without reading the server log. */
	private void showInfo(Player observer)
	{
		final L3AgentManager manager = L3AgentManager.getInstance();
		final int[] lod = manager.countByLod();
		observer.sendSysMessage("=== L3 population ===");
		observer.sendSysMessage("Agents: " + manager.size() + " / " + L3Config.POPULATION_CAP + " (pending restore: " + manager.pendingRestoreCount() + ")");
		observer.sendSysMessage("Detail: HOT " + lod[0] + " | WARM " + lod[1] + " | COLD " + lod[2]);
		observer.sendSysMessage("Humans online: " + manager.getHumans().size() + " | levels " + L3Config.LEVEL_MIN + "-" + L3Config.LEVEL_MAX + (L3Config.TEST_IMMORTAL ? " | IMMORTAL (test)" : ""));
	}

	// --- shutdown + wipe ------------------------------------------------------------------------

	/**
	 * Deletes every agent character, leaving human accounts alone.
	 * <p>
	 * This replaces an earlier version that rebuilt the entire database schema. That was the wrong
	 * scope: it also destroyed the human account and its GM access level, which is not what "wipe the
	 * NPC characters" means. Removing our own rows needs no database teardown at all, so it now runs
	 * safely in-game with the server up.
	 */
	private boolean wipeAgents(Player observer)
	{
		final int deleted = L3AgentManager.getInstance().purgeAgentCharacters();
		SPAWNED.clear();
		observer.sendSysMessage("L3: deleted " + deleted + " agent character(s). Your account and characters are untouched.");
		return true;
	}

	/** Wipe the agents, then shut down so the database sync commits the cleaned state. */
	private boolean shutdownAndWipe(Player observer)
	{
		wipeAgents(observer);
		observer.sendSysMessage("L3: shutting down now.");
		LOGGER.warning("L3: agent wipe + shutdown requested by " + observer.getName() + " (" + observer.getObjectId() + ").");
		Shutdown.getInstance().startShutdown(observer, 0, false);
		return true;
	}

	// --- navigation -----------------------------------------------------------------------------

	/** Teleports to the next agent in the population, cycling round. */
	private boolean gotoNext(Player observer)
	{
		final List<L3Agent> agents = new ArrayList<>(L3AgentManager.getInstance().getAgents());
		if (agents.isEmpty())
		{
			observer.sendSysMessage("L3: there are no agents to go to.");
			return false;
		}

		// The registry is a hash map, so iteration order is arbitrary but stable enough to walk;
		// sorting by object id makes //gotonext advance predictably instead of jumping about.
		agents.sort((a, b) -> Integer.compare(a.getObjectId(), b.getObjectId()));

		if (_gotoIndex >= agents.size())
		{
			_gotoIndex = 0;
		}

		final L3Agent agent = agents.get(_gotoIndex);
		_gotoIndex = (_gotoIndex + 1) % agents.size();

		final Player target = agent.getPlayer();
		observer.teleToLocation(new Location(target.getX() + 60, target.getY() + 60, target.getZ()));
		observer.sendSysMessage("L3: -> " + target.getName() + " (" + (_gotoIndex == 0 ? agents.size() : _gotoIndex) + "/" + agents.size() + ") level " + target.getLevel() + ", " + agent.getLod() + ".");
		return true;
	}

	// --- cleanup --------------------------------------------------------------------------------

	/**
	 * Deletes the agents these commands created this session, world object and database rows alike.
	 * Note the permanent population will simply top itself back up afterwards.
	 */
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
			observer.sendSysMessage("L3: no puppets from this session to clean.");
			return;
		}

		int removed = 0;
		for (int objId : ids)
		{
			try
			{
				// Take it out of the thinking pools first, so no tick can touch a deleted player.
				L3AgentManager.getInstance().unregister(objId);

				final Player puppet = World.getPlayer(objId);
				if (puppet != null)
				{
					puppet.deleteMe();
				}

				GameClient.deleteCharByObjId(objId);
				removed++;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3Spawn: failed to clean puppet " + objId, e);
			}
		}

		observer.sendSysMessage("L3: cleaned " + removed + " of " + ids.length + ". Cap is " + L3Config.POPULATION_CAP + ".");
		LOGGER.info("L3Spawn: " + observer.getName() + " cleaned " + removed + " agent(s).");
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
