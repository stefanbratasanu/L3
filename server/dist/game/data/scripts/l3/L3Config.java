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
package l3;

/**
 * Tunables for the L3 agent system, in one place.
 * <p>
 * The numbers here are the whole scaling story, so they are worth understanding rather than
 * tweaking blindly. The target is thousands of agents on one box, and the thing that kills that is
 * <b>world queries</b>: every {@code World.getVisibleObjects*} call walks a 3x3 block of world
 * regions, and Mobius's own {@code AutoPlayTaskManager} does one of those <i>plus two GeoEngine
 * raycasts per candidate</i> for every player on every 700ms tick. At 5000 agents that is roughly
 * 7000 region scans per second with raycasts attached - it does not fit on any single machine.
 * <p>
 * Three ideas keep the cost flat instead:
 * <ol>
 * <li><b>Level of detail.</b> There is only ever a handful of real humans online, so almost every
 * agent has nobody watching it. Those agents do not need real combat simulation, only plausible
 * bookkeeping, so they never touch the world at all ({@link #COLD_TICK_MS}).</li>
 * <li><b>Target caching.</b> An agent scans for a new target only when it has lost the old one, and
 * then not more often than {@link #ACQUIRE_COOLDOWN_MS}. Combat ticks in between are pure
 * arithmetic on a cached reference.</li>
 * <li><b>Per-agent state.</b> No global {@code ConcurrentHashMap} keyed by player (which is what
 * AutoPlayTaskManager does for its idle counter) - at thousands of agents that map becomes a
 * contention point. Everything lives on the agent's own object.</li>
 * </ol>
 *
 * @author L3
 */
public class L3Config
{
	// --- Scheduler ------------------------------------------------------------------------------
	/** Agents per pool. Each pool is one scheduled task, so this bounds how many agents a single
	 * tick walks; the pools are spread across the tick window instead of all firing at once. */
	public static final int POOL_SIZE = 100;

	/** The scheduler's heartbeat. Individual agents are gated by their own LOD on top of this, so
	 * this is the *fastest* anything thinks, not how often every agent thinks. */
	public static final int TICK_MS = 700;

	// --- Level of detail ------------------------------------------------------------------------
	/** Within this distance of a real player an agent is fully simulated - it must look right,
	 * because someone is watching. */
	public static final int HOT_RANGE = 2500;

	/** Out to here an agent still really fights, but with the expensive line-of-sight checks
	 * skipped: nobody can see whether it clipped a corner. */
	public static final int WARM_RANGE = 8000;

	/** How often an agent thinks at each level of detail. HOT is every tick; the others are gated. */
	public static final int HOT_TICK_MS = TICK_MS;
	public static final int WARM_TICK_MS = 2100;
	/** COLD agents do no world queries whatsoever, so this can be slow and cheap. */
	public static final int COLD_TICK_MS = 15000;

	/** How often each agent's level of detail is recomputed. Cheap: distance from every agent to
	 * the few humans online, which is a handful of comparisons each. */
	public static final int LOD_REFRESH_MS = 2000;

	// --- Combat ---------------------------------------------------------------------------------
	/** Radius an agent looks in for something to fight. */
	public static final int HUNT_RANGE = 1600;

	/** Floor on how often an agent may run a target scan. This is the single most important knob
	 * for scale: it decouples "how often an agent acts" from "how often it walks world regions".
	 * With a failed scan the agent also backs off (see {@link #ACQUIRE_BACKOFF_MAX_MS}), so empty
	 * areas cost far less than busy ones. */
	public static final int ACQUIRE_COOLDOWN_MS = 1500;

	/** An agent that keeps finding nothing doubles its scan interval up to this ceiling, so idle
	 * agents in empty regions cost almost nothing. */
	public static final int ACQUIRE_BACKOFF_MAX_MS = 30000;

	/** Melee stop distance; beyond this the agent walks in. */
	public static final int MELEE_RANGE = 60;

	/** Give up on a target that has somehow stayed unreachable this long and pick another. */
	public static final int TARGET_TIMEOUT_MS = 20000;

	// --- Safety ---------------------------------------------------------------------------------
	/** Hard ceiling on live agents, so a bad config or a loop cannot spawn the box to death. */
	public static final int MAX_AGENTS = 5000;

	/** Log a one-line summary of the whole population this often. Set to 0 to disable. */
	public static final int STATS_INTERVAL_MS = 60000;

	private L3Config()
	{
	}
}
