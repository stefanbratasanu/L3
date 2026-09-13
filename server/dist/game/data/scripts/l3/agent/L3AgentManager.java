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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;

import l3.L3Config;
import l3.agent.L3Agent.Lod;

/**
 * The registry of live L3 agents, and the owner of their level-of-detail decisions.
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
 * Refreshing all 5000 agents costs a few thousand comparisons every couple of seconds, which is
 * nothing, and it tells us which small subset actually needs full simulation.
 *
 * @author L3
 */
public class L3AgentManager
{
	/** objectId -> agent. Concurrent because pool tasks read it while spawns mutate it. */
	private static final Map<Integer, L3Agent> AGENTS = new ConcurrentHashMap<>();

	/** Cached list of real (non-agent) players, refreshed on the LOD cadence rather than per agent. */
	private static volatile List<Player> HUMANS = List.of();

	protected L3AgentManager()
	{
	}

	// --- Registry -------------------------------------------------------------------------------

	/**
	 * Brings an already-spawned clientless player under L3 control.
	 * @param player a player that is already in the world
	 * @return the controller, or {@code null} if refused (duplicate, or at the population ceiling)
	 */
	public L3Agent register(Player player)
	{
		if (player == null)
		{
			return null;
		}

		if (AGENTS.size() >= L3Config.MAX_AGENTS)
		{
			return null;
		}

		final L3Agent agent = new L3Agent(player);
		return AGENTS.putIfAbsent(player.getObjectId(), agent) == null ? agent : null;
	}

	public void unregister(int objectId)
	{
		AGENTS.remove(objectId);
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
				AGENTS.remove(agent.getObjectId());
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
