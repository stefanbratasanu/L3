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
package l3.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.GameTime;

import l3.L3Config;
import l3.agent.L3Agent;
import l3.agent.L3Agent.Lod;
import l3.agent.L3AgentManager;

/**
 * The scheduler that drives every agent's thinking.
 * <p>
 * <b>Shape of the thing.</b> Agents are partitioned into pools of {@link L3Config#POOL_SIZE}, and
 * each pool is one fixed-rate task. Two properties matter at scale:
 * <ul>
 * <li>No single task ever walks the whole population, so one slow agent cannot stall everyone.</li>
 * <li>Pools are <b>staggered</b> across the tick window. Scheduling 50 pools at the same instant
 * would produce a 50x CPU spike every 700ms and idle time in between; offsetting them spreads the
 * same work into a flat line, which is what keeps frame-time smooth for the human watching.</li>
 * </ul>
 * <b>One heartbeat, three tiers.</b> Rather than separate task sets per level of detail, every pool
 * ticks at the fastest cadence and each agent decides for itself whether it is due
 * ({@link L3Agent#isDueToThink}). A COLD agent visited 21 times does nothing 20 of them, at the cost
 * of one integer comparison each - far cheaper than maintaining membership in three moving sets as
 * agents change level of detail constantly.
 * <p>
 * <b>Thread-safety.</b> An agent belongs to exactly one pool, so its {@link L3Agent} fields are
 * effectively single-threaded and need no locks on the hot path. Only the registry itself is
 * concurrent.
 *
 * @author L3
 */
public class L3ThinkTaskManager
{
	private static final Logger LOGGER = Logger.getLogger(L3ThinkTaskManager.class.getName());

	/** Pools of agents; each is ticked by its own scheduled task. */
	private static final List<List<L3Agent>> POOLS = new ArrayList<>();

	private static boolean _started;

	protected L3ThinkTaskManager()
	{
	}

	/** Starts the LOD refresher and the stats line. Pool tasks are created on demand as agents join. */
	public synchronized void start()
	{
		if (_started)
		{
			return;
		}

		_started = true;

		// Level of detail is recomputed centrally and slowly: it is a property of the population,
		// not of any one agent, and it must never run on the combat path.
		ThreadPool.scheduleAtFixedRate(() ->
		{
			try
			{
				L3AgentManager.getInstance().refreshLod();
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3: LOD refresh failed.", e);
			}
		}, L3Config.LOD_REFRESH_MS, L3Config.LOD_REFRESH_MS);

		// Restoring the existing population at boot is OPT-IN and off by default. With it on, a boot
		// after a large populate spends minutes dragging characters back in, and it kept hammering
		// the database during shutdown ("Failed loading character"). Populate on demand instead.
		if (L3Config.RESTORE_ON_BOOT)
		{
			L3AgentManager.getInstance().scanForRestore();
			ThreadPool.scheduleAtFixedRate(() ->
			{
				try
				{
					L3AgentManager.getInstance().maintainPopulation();
				}
				catch (Exception e)
				{
					LOGGER.log(Level.WARNING, "L3: population maintenance failed.", e);
				}
			}, L3Config.POPULATION_MAINTAIN_MS, L3Config.POPULATION_MAINTAIN_MS);
		}

		if (L3Config.STATS_INTERVAL_MS > 0)
		{
			ThreadPool.scheduleAtFixedRate(this::logStats, L3Config.STATS_INTERVAL_MS, L3Config.STATS_INTERVAL_MS);
		}

		LOGGER.info("L3ThinkTaskManager: started (tick " + L3Config.TICK_MS + "ms, pool size " + L3Config.POOL_SIZE + ").");
	}

	/**
	 * Adds an agent to a pool, creating and scheduling a new pool if the existing ones are full.
	 */
	public synchronized void add(L3Agent agent)
	{
		for (List<L3Agent> pool : POOLS)
		{
			if (pool.size() < L3Config.POOL_SIZE)
			{
				synchronized (pool)
				{
					pool.add(agent);
				}

				return;
			}
		}

		// New pool. Offset its start so pools do not all fire on the same millisecond: with N pools
		// the k-th starts a fraction k/N into the tick window, flattening the load curve.
		final List<L3Agent> pool = new ArrayList<>(L3Config.POOL_SIZE);
		pool.add(agent);
		POOLS.add(pool);

		final int index = POOLS.size() - 1;
		final long offset = (long) ((L3Config.TICK_MS / (double) Math.max(1, L3Config.POOL_SIZE)) * index) % L3Config.TICK_MS;

		ThreadPool.scheduleAtFixedRate(() -> runPool(pool), L3Config.TICK_MS + offset, L3Config.TICK_MS);
		LOGGER.info("L3ThinkTaskManager: pool " + index + " scheduled (offset " + offset + "ms).");
	}

	public synchronized void remove(L3Agent agent)
	{
		for (List<L3Agent> pool : POOLS)
		{
			synchronized (pool)
			{
				if (pool.remove(agent))
				{
					return;
				}
			}
		}
	}

	/** One tick of one pool. */
	private void runPool(List<L3Agent> pool)
	{
		final long now = GameTime.currentTimeMillis();

		// Snapshot under the pool lock, then think outside it: thinking can take a while and must
		// not block spawns joining the pool.
		final List<L3Agent> snapshot;
		synchronized (pool)
		{
			if (pool.isEmpty())
			{
				return;
			}

			snapshot = new ArrayList<>(pool);
		}

		for (L3Agent agent : snapshot)
		{
			if (!agent.isDueToThink(now))
			{
				continue;
			}

			agent.scheduleNextThink(now);

			try
			{
				// One misbehaving agent must never take its pool down with it.
				if (agent.getLod() == Lod.COLD)
				{
					L3Reflex.tickCold(agent, now);
				}
				else
				{
					L3Reflex.tick(agent, now);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "L3: think failed for " + agent, e);
			}
		}
	}

	private void logStats()
	{
		final L3AgentManager manager = L3AgentManager.getInstance();
		final int total = manager.size();
		if (total == 0)
		{
			return;
		}

		final int[] lod = manager.countByLod();
		LOGGER.info("L3: " + total + "/" + L3Config.POPULATION_CAP + " agents in " + POOLS.size() + " pools - HOT " + lod[0] + ", WARM " + lod[1] + ", COLD " + lod[2] + "; pending restore " + manager.pendingRestoreCount() + "; humans online: " + manager.getHumans().size() + ".");
	}

	public static L3ThinkTaskManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final L3ThinkTaskManager INSTANCE = new L3ThinkTaskManager();
	}
}
