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
package l3.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.mechanics.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.network.GameClient;

import l3.L3Config;
import l3.L3Locations;
import l3.L3Names;
import l3.agent.L3Agent.Lod;
import l3.agent.L3AgentGoal;
import l3.ai.L3ThinkTaskManager;

/**
 * The registry of live L3 agents, their persistence, and the owner of their level-of-detail
 * decisions.
 * <p>
 * <b>Why a registry instead of a flag on {@code Player}:</b> telling agents apart from humans could
 * be a boolean field on {@code Player}, but that means editing an upstream file and carrying the
 * change through every future Mobius sync. An {@code objectId -> agent} hash is an O(1) lookup and
 * keeps the upstream tree untouched, so L3 lives entirely in the scripts folder - which is also what
 * lets it recompile on every game-server boot.
 * <p>
 * <b>The level-of-detail trick.</b> There are only ever a few real humans online, so instead of
 * asking "which agents can see a player" (a world query per agent) we ask "how far is each agent
 * from the nearest human" - a handful of integer comparisons per agent against a cached list.
 * Refreshing thousands of agents costs a few thousand comparisons every couple of seconds, and it
 * tells us which small subset actually needs full simulation.
 * <p>
 * <b>Persistence.</b> Agents are real characters on the {@link L3Config#AGENT_ACCOUNT} account, so
 * they survive restarts with full fidelity - level, gear, inventory - exactly like Mobius's own
 * offline-play characters. On boot their ids are queued and restored gradually rather than all at
 * once, because a thousand {@code Player.load} calls in a tight loop would stall startup and make
 * the first run look like a hang.
 *
 * @author L3
 */
public class L3AgentManager
{
	private static final Logger LOGGER = Logger.getLogger(L3AgentManager.class.getName());

	private static final String SELECT_AGENT_IDS = "SELECT charId FROM characters WHERE account_name=?";
	private static final String SELECT_AGENT_BY_NAME = "SELECT charId FROM characters WHERE account_name=? AND char_name=?";

	/** Matches the agent account and its variants (e.g. the turbo account), for a full purge. */
	private static final String SELECT_AGENT_IDS_LIKE = "SELECT charId FROM characters WHERE account_name LIKE ?";

	/** Marker used as the owner of turbo stat functions. */
	private static final String TURBO_OWNER = "l3-turbo";

	/** objectId -> agent. Concurrent because pool tasks read it while spawns mutate it. */
	private static final Map<Integer, L3Agent> AGENTS = new ConcurrentHashMap<>();

	/** Agent characters known in the database but not yet put back into the world. */
	private static final Queue<Integer> PENDING_RESTORE = new ConcurrentLinkedQueue<>();

	/** Cached list of real (non-agent) players, refreshed on the LOD cadence rather than per agent. */
	private static volatile List<Player> HUMANS = List.of();

	private static volatile boolean _restoreScanned;

	protected L3AgentManager()
	{
	}

	// --- Registry -------------------------------------------------------------------------------

	/**
	 * Brings an already-spawned clientless player under L3 control and starts it thinking.
	 * @return the controller, or {@code null} if refused (duplicate, or at the population ceiling)
	 */
	public L3Agent register(Player player)
	{
		if (player == null)
		{
			return null;
		}

		final L3Agent existing = AGENTS.get(player.getObjectId());
		if (existing != null)
		{
			return existing;
		}
		if (AGENTS.size() >= L3Config.MAX_AGENTS)
		{
			return null;
		}

		final L3Agent agent = new L3Agent(player);
		if (AGENTS.putIfAbsent(player.getObjectId(), agent) != null)
		{
			return null;
		}

		L3ThinkTaskManager.getInstance().add(agent);
		return agent;
	}

	public boolean isGeneralHunting(L3Agent agent)
	{
		return agent != null && (agent.getGoal() == L3AgentGoal.REACH_LEVEL_20);
	}

	public void unregister(int objectId)
	{
		final L3Agent agent = AGENTS.remove(objectId);
		if (agent != null)
		{
			L3ThinkTaskManager.getInstance().remove(agent);
		}
	}

	public boolean isAgent(Player player)
	{
		return (player != null) && AGENTS.containsKey(player.getObjectId());
	}

	public boolean isAgent(int objectId)
	{
		return AGENTS.containsKey(objectId);
	}

	public L3Agent get(int objectId)
	{
		return AGENTS.get(objectId);
	}

	public Collection<L3Agent> getAgents()
	{
		return AGENTS.values();
	}

	public int size()
	{
		return AGENTS.size();
	}

	public int pendingRestoreCount()
	{
		return PENDING_RESTORE.size();
	}

	// --- Spawning -------------------------------------------------------------------------------

	/**
	 * Creates a brand new agent character and puts it in the world.
	 * @param location where to place it
	 * @param name a specific name, or {@code null} to generate one
	 * @param turbo whether to apply the overpowered test stats
	 * @return the new agent, or {@code null} if creation failed
	 */
	public L3Agent spawnNew(Location location, String name, boolean turbo)
	{
		return spawnNew(location, name, turbo, L3Outfitter.randomClassId());
	}

	/**
	 * Creates an agent with an explicit starting class for deterministic behavior tests.
	 */
	public L3Agent spawnNew(Location location, String name, boolean turbo, int classId)
	{
		if (AGENTS.size() >= L3Config.POPULATION_CAP)
		{
			return null;
		}

		// A varied population normally uses a random class; named test agents may choose one.
		final boolean prius = "Prius".equalsIgnoreCase(name);
		final int level = prius ? 1 : L3Outfitter.randomLevel();

		if (name != null)
		{
			final Integer existingId = findPersistedAgent(name, turbo);
			if (existingId != null)
			{
				final Player alreadyLoaded = World.getPlayer(existingId);
				if (alreadyLoaded != null)
				{
					final L3Agent registered = AGENTS.get(existingId);
					if (registered != null)
					{
						return registered;
					}

					return register(alreadyLoaded);
				}

				final Player existing = Player.load(existingId);
				if ((existing == null) || !placeInWorld(existing, location))
				{
					LOGGER.warning("L3: could not restore existing agent " + name + " (" + existingId + ").");
					return null;
				}

				final L3Agent registered = register(existing);
				if (registered == null)
				{
					LOGGER.warning("L3: existing agent " + name + " could not be registered.");
				}
				return registered;
			}
		}

		final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(classId);
		if (template == null)
		{
			LOGGER.warning("L3: no player template for classId " + classId);
			return null;
		}

		// Turbo agents live on a separate account so they are never picked up as part of the
		// permanent population: their stat functions are runtime-only and would not survive a
		// restart anyway, which would leave a confusingly ordinary "turbo" agent behind.
		final String account = turbo ? (L3Config.AGENT_ACCOUNT + "_turbo") : L3Config.AGENT_ACCOUNT;

		Player player = null;
		if (name != null)
		{
			player = Player.create(template, account, name, new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
		}
		else
		{
			// char_name is unique, so a generated name can collide; try a few before giving up.
			for (int attempt = 0; (attempt < 5) && (player == null); attempt++)
			{
				player = Player.create(template, account, L3Names.random(), new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
			}
		}

		if (player == null)
		{
			return null;
		}

		if (!placeInWorld(player, location))
		{
			return null;
		}

		// Level, class skills, grade-appropriate weapon, shots, and test immortality.
		if (prius)
		{
			L3Outfitter.outfitStarter(player, classId);
		}
		else
		{
			L3Outfitter.outfit(player, classId, level);
		}

		if (turbo)
		{
			applyTurbo(player);
		}

		final L3Agent agent = register(player);
		if (agent == null)
		{
			LOGGER.warning("L3: created " + player.getName() + " but could not register it.");
		}

		return agent;
	}

	private Integer findPersistedAgent(String name, boolean turbo)
	{
		final String account = turbo ? (L3Config.AGENT_ACCOUNT + "_turbo") : L3Config.AGENT_ACCOUNT;
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_AGENT_BY_NAME))
		{
			ps.setString(1, account);
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getInt("charId") : null;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: could not check for existing agent " + name + ".", e);
			return null;
		}
	}

	/**
	 * The clientless in-world sequence, mirroring {@code OfflinePlayTable.restoreOfflinePlayers()}:
	 * a full {@link Player} with no {@code GameClient} and no socket.
	 * @param location where to put it, or {@code null} to use the character's stored position
	 */
	private boolean placeInWorld(Player player, Location location)
	{
		try
		{
			player.setOnlineStatus(true, false);

			final int x = (location == null) ? player.getX() : location.getX();
			final int y = (location == null) ? player.getY() : location.getY();
			final int z = (location == null) ? player.getZ() : location.getZ();

			player.setXYZ(x, y, z);
			player.spawnMe(x, y, z); // inserts into World + region grid = in-world

			player.setOnlineStatus(true, true);
			player.setRunning();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: failed to place " + player.getName() + " in the world.", e);
			return false;
		}
	}

	/**
	 * Overpowered stats for a test agent, so behaviour can be watched without the agent dying
	 * mid-demonstration. Applied as flat {@link FuncAdd} stat functions, which is how the engine
	 * itself layers bonuses - so these are additions on top of base values, not absolute settings.
	 */
	private void applyTurbo(Player player)
	{
		player.addStatFunc(new FuncAdd(Stat.MOVE_SPEED, 0x40, TURBO_OWNER, L3Config.TURBO_MOVE_SPEED, null));
		player.addStatFunc(new FuncAdd(Stat.POWER_ATTACK_SPEED, 0x40, TURBO_OWNER, L3Config.TURBO_ATTACK_SPEED, null));
		player.addStatFunc(new FuncAdd(Stat.POWER_ATTACK, 0x40, TURBO_OWNER, L3Config.TURBO_PHYSICAL_ATTACK, null));

		// Heal to the new maximum and tell nearby clients about the changed speed.
		player.setCurrentHp(player.getMaxHp());
		player.setCurrentMp(player.getMaxMp());
		player.broadcastUserInfo();
	}

	// --- Permanent population -------------------------------------------------------------------

	/**
	 * Reads the ids of every existing agent character once, so they can be restored gradually.
	 * Cheap: one indexed query, no object loading.
	 */
	public void scanForRestore()
	{
		if (_restoreScanned)
		{
			return;
		}

		_restoreScanned = true;

		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_AGENT_IDS))
		{
			ps.setString(1, L3Config.AGENT_ACCOUNT);

			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					PENDING_RESTORE.add(rs.getInt("charId"));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: could not scan for existing agents.", e);
		}

		LOGGER.info("L3: " + PENDING_RESTORE.size() + " existing agent characters found; restoring gradually (cap " + L3Config.POPULATION_CAP + ").");
	}

	/**
	 * One restore pass: puts existing agent characters back into the world, a batch at a time.
	 * <p>
	 * Note what this deliberately does <b>not</b> do: create agents. Nothing spawns automatically -
	 * the population only grows when you ask for it with {@code //populate X}. Agents you have
	 * already made are permanent and come back here after every restart.
	 */
	public void maintainPopulation()
	{
		int budget = L3Config.POPULATION_BATCH;

		while ((budget > 0) && !PENDING_RESTORE.isEmpty() && (AGENTS.size() < L3Config.POPULATION_CAP))
		{
			final Integer charId = PENDING_RESTORE.poll();
			if (charId == null)
			{
				break;
			}

			if (AGENTS.containsKey(charId) || (World.getPlayer(charId) != null))
			{
				continue; // Already in world.
			}

			budget--;

			try
			{
				final Player player = Player.load(charId);
				if (player == null)
				{
					continue;
				}

				if (placeInWorld(player, null) && (register(player) == null))
				{
					LOGGER.warning("L3: restored " + player.getName() + " but could not register it.");
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3: failed to restore agent charId " + charId, e);
			}
		}

	}

	/**
	 * Creates a batch of agents spread across the world - mostly hunting grounds, some towns, since
	 * fields are where there is anything to do. This is what {@code //populate X} calls.
	 * @return how many were actually created
	 */
	public int populate(int count)
	{
		int made = 0;
		for (int i = 0; i < count; i++)
		{
			if (AGENTS.size() >= L3Config.POPULATION_CAP)
			{
				break;
			}

			if (spawnNew(L3Locations.randomSpawnPoint(L3Config.POPULATE_TOWN_PERCENT, L3Config.SPAWN_SCATTER), null, false) != null)
			{
				made++;
			}
		}

		return made;
	}

	// --- Purge ----------------------------------------------------------------------------------

	/**
	 * Deletes every agent character - and <b>only</b> agent characters - from the world and the
	 * database.
	 * <p>
	 * Scope matters here. An earlier version of the wipe rebuilt the whole schema, which also
	 * destroyed the human account and its GM access level: far more than anyone asked for. This
	 * instead selects the characters on the agent accounts and removes each one through
	 * {@code GameClient.deleteCharByObjId}, the same call the game uses when a player deletes a
	 * character, so dependent rows (items, skills, variables) go with it. Nothing else is touched.
	 * @return how many characters were deleted
	 */
	public int purgeAgentCharacters()
	{
		// Live agents first: out of the thinking pools and out of the world.
		for (L3Agent agent : new ArrayList<>(AGENTS.values()))
		{
			try
			{
				unregister(agent.getObjectId());
				final Player player = agent.getPlayer();
				if ((player != null) && (World.getPlayer(agent.getObjectId()) != null))
				{
					player.deleteMe();
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3: could not remove agent " + agent.getObjectId() + " from the world.", e);
			}
		}

		PENDING_RESTORE.clear();

		// Then every agent character in the database, whether it was loaded or not.
		final List<Integer> ids = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(SELECT_AGENT_IDS_LIKE))
		{
			ps.setString(1, L3Config.AGENT_ACCOUNT + "%");

			try (ResultSet rs = ps.executeQuery())
			{
				while (rs.next())
				{
					ids.add(rs.getInt("charId"));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: could not list agent characters to purge.", e);
			return 0;
		}

		int deleted = 0;
		for (int charId : ids)
		{
			try
			{
				GameClient.deleteCharByObjId(charId);
				deleted++;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3: could not delete agent charId " + charId, e);
			}
		}

		// Allow a later restore scan to run again against the now-empty set.
		_restoreScanned = false;

		LOGGER.warning("L3: purged " + deleted + " agent characters (of " + ids.size() + " found). Human accounts untouched.");
		return deleted;
	}

	// --- Level of detail ------------------------------------------------------------------------

	/**
	 * Recomputes every agent's level of detail, and drops agents whose player has gone away.
	 * Called on a slow cadence by {@code L3ThinkTaskManager}, never from the combat path.
	 */
	public void refreshLod()
	{
		// Refresh the human list first: everything below is relative to it.
		final List<Player> humans = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if ((player != null) && player.isOnline() && !AGENTS.containsKey(player.getObjectId()))
			{
				humans.add(player);
			}
		}
		HUMANS = List.copyOf(humans);

		// Squared thresholds so the inner loop needs no square roots.
		final long hotSq = (long) L3Config.HOT_RANGE * L3Config.HOT_RANGE;
		final long warmSq = (long) L3Config.WARM_RANGE * L3Config.WARM_RANGE;

		for (L3Agent agent : AGENTS.values())
		{
			final Player player = agent.getPlayer();

			// Reap agents whose player is gone, so the registry cannot leak.
			if ((player == null) || !player.isOnline() || (World.getPlayer(agent.getObjectId()) == null))
			{
				unregister(agent.getObjectId());
				continue;
			}

			if (humans.isEmpty())
			{
				agent.setLod(Lod.COLD);
				continue;
			}

			long nearestSq = Long.MAX_VALUE;
			final int ax = player.getX();
			final int ay = player.getY();
			for (Player human : humans)
			{
				final long dx = ax - human.getX();
				final long dy = ay - human.getY();
				final long dSq = (dx * dx) + (dy * dy);
				if (dSq < nearestSq)
				{
					nearestSq = dSq;
					if (nearestSq <= hotSq)
					{
						break; // Already HOT; no closer human can change the outcome.
					}
				}
			}

			agent.setLod(nearestSq <= hotSq ? Lod.HOT : (nearestSq <= warmSq ? Lod.WARM : Lod.COLD));
		}
	}

	/** The real players online, as of the last LOD refresh. */
	public List<Player> getHumans()
	{
		return HUMANS;
	}

	/** Population counts by level of detail, for the periodic stats line. */
	public int[] countByLod()
	{
		int hot = 0;
		int warm = 0;
		int cold = 0;
		for (L3Agent agent : AGENTS.values())
		{
			switch (agent.getLod())
			{
				case HOT:
				{
					hot++;
					break;
				}
				case WARM:
				{
					warm++;
					break;
				}
				default:
				{
					cold++;
					break;
				}
			}
		}

		return new int[]
		{
			hot,
			warm,
			cold
		};
	}

	public static L3AgentManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final L3AgentManager INSTANCE = new L3AgentManager();
	}
}
