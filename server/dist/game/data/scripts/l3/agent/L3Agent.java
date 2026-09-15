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

import org.l2jmobius.gameserver.entity.actor.Player;

import l3.L3Config;
import l3.L3Debug;

/**
 * One agent's brain state: the controller that sits beside a clientless {@link Player}.
 * <p>
 * <b>Everything mutable about an agent lives here</b>, never in a shared map keyed by player.
 * Mobius's {@code AutoPlayTaskManager} keeps its idle counter in a global
 * {@code ConcurrentHashMap<Player, Integer>}, which is fine for a few offline-play characters and a
 * contention point at thousands. Each field below is touched only by the pool task that owns this
 * agent, so the hot path needs no synchronisation at all.
 * <p>
 * The fields are deliberately primitives and one object reference - at 5000 agents this object's
 * footprint is multiplied 5000 times, so it stays small.
 *
 * @author L3
 */
public class L3Agent
{
	/**
	 * How much simulation this agent currently deserves, decided by how close a real player is.
	 * The population is overwhelmingly {@link #COLD} in normal operation, and that is exactly what
	 * makes thousands of agents affordable.
	 */
	public enum Lod
	{
		/** A human is close enough to watch: simulate properly, line-of-sight checks included. */
		HOT,
		/** Nobody is watching closely: really fight, but skip the expensive geometry checks. */
		WARM,
		/** Nobody anywhere near: no world queries at all, just advance the agent's own numbers. */
		COLD
	}

	private final Player _player;
	private final int _objectId;

	/** Seed for this agent's persona. Kept now so behaviour can vary per agent before the full
	 * trait model exists; the utility/LLM layers will read from it rather than re-rolling. */
	private final long _personaSeed;

	private Lod _lod = Lod.COLD;

	/** Next time this agent is allowed to think, in {@code System.currentTimeMillis()}. The
	 * scheduler ticks fast and every agent gates itself here, so one task set serves every LOD. */
	private long _nextThinkAt;

	/** Next time this agent may run a target scan - the expensive operation we are rationing. */
	private long _nextAcquireAt;

	/** Current scan interval, doubled on every failed scan up to
	 * {@link L3Config#ACQUIRE_BACKOFF_MAX_MS} so agents in empty regions cost almost nothing. */
	private int _acquireBackoffMs = L3Config.ACQUIRE_COOLDOWN_MS;

	/** Cached target as an object id rather than a hard reference, so a dead or despawned creature
	 * cannot be kept alive by this field. */
	private int _targetObjectId;

	/** When the current target was picked, to abandon anything unreachable for too long. */
	private long _targetPickedAt;

	/** Consecutive ticks where we asked for an attack but nothing happened - the agent is probably
	 * wedged on geometry and should be nudged. */
	private int _stuckTicks;
	private L3AgentState _state = L3AgentState.IDLE;
	private L3AgentGoal _goal = L3AgentGoal.REACH_LEVEL_20;

	public L3Agent(Player player)
	{
		_player = player;
		_objectId = player.getObjectId();
		_personaSeed = (((long) player.getObjectId()) * 0x9E3779B97F4A7C15L) ^ System.nanoTime();
		L3Debug.event(this, "REGISTERED", "mind initialized");
	}

	public Player getPlayer()
	{
		return _player;
	}

	public int getObjectId()
	{
		return _objectId;
	}

	public long getPersonaSeed()
	{
		return _personaSeed;
	}

	public L3AgentState getState()
	{
		return _state;
	}

	public L3AgentGoal getGoal()
	{
		return _goal;
	}

	public void setState(L3AgentState state)
	{
		if (state != _state)
		{
			final L3AgentState previous = _state;
			_state = state;
			L3Debug.event(this, "STATE", previous + "->" + state);
		}
	}

	public void setGoal(L3AgentGoal goal)
	{
		if (goal != _goal)
		{
			final L3AgentGoal previous = _goal;
			_goal = goal;
			L3Debug.event(this, "GOAL", previous + "->" + goal);
		}
	}

	public Lod getLod()
	{
		return _lod;
	}

	public void setLod(Lod lod)
	{
		// Coming back into view: think on the next tick rather than waiting out a COLD interval,
		// otherwise an agent can stand frozen for a noticeable moment while a player walks up to it.
		if ((lod != _lod) && (lod == Lod.HOT))
		{
			_nextThinkAt = 0;
		}

		_lod = lod;
	}

	/** How long this agent waits between thoughts at its current level of detail. */
	public int getThinkIntervalMs()
	{
		switch (_lod)
		{
			case HOT:
			{
				return L3Config.HOT_TICK_MS;
			}
			case WARM:
			{
				return L3Config.WARM_TICK_MS;
			}
			default:
			{
				return L3Config.COLD_TICK_MS;
			}
		}
	}

	public boolean isDueToThink(long now)
	{
		return now >= _nextThinkAt;
	}

	public void scheduleNextThink(long now)
	{
		_nextThinkAt = now + L3Config.scaleThinkInterval(getThinkIntervalMs());
	}

	public void wakeNow()
	{
		_nextThinkAt = 0;
	}

	// --- Target acquisition rationing -----------------------------------------------------------

	public boolean mayAcquire(long now)
	{
		return now >= _nextAcquireAt;
	}

	/** Called after a scan that found nothing: back off so empty areas get cheaper over time. */
	public void onAcquireFailed(long now)
	{
		_nextAcquireAt = now + _acquireBackoffMs;
		_acquireBackoffMs = Math.min(_acquireBackoffMs * 2, L3Config.ACQUIRE_BACKOFF_MAX_MS);
	}

	/** Called after a successful scan: reset to the fast interval, since this area has targets. */
	public void onAcquireSucceeded(long now, int targetObjectId)
	{
		_nextAcquireAt = now + L3Config.ACQUIRE_COOLDOWN_MS;
		_acquireBackoffMs = L3Config.ACQUIRE_COOLDOWN_MS;
		_targetObjectId = targetObjectId;
		_targetPickedAt = now;
		_stuckTicks = 0;
	}

	public int getTargetObjectId()
	{
		return _targetObjectId;
	}

	public void clearTarget()
	{
		_targetObjectId = 0;
		_targetPickedAt = 0;
		_stuckTicks = 0;
	}

	public boolean isTargetStale(long now)
	{
		return (_targetObjectId != 0) && ((now - _targetPickedAt) > L3Config.TARGET_TIMEOUT_MS);
	}

	public int getStuckTicks()
	{
		return _stuckTicks;
	}

	public int incrementStuckTicks()
	{
		return ++_stuckTicks;
	}

	public void resetStuckTicks()
	{
		_stuckTicks = 0;
	}

	@Override
	public String toString()
	{
		return "L3Agent[" + _player.getName() + " " + _objectId + " " + _lod + "]";
	}
}
