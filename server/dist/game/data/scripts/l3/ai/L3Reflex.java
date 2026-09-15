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

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.commons.util.Rnd;

import l3.L3Config;
import l3.L3Debug;
import l3.agent.L3Agent;
import l3.agent.L3AgentManager;
import l3.agent.L3AgentState;
import l3.agent.L3Agent.Lod;

/**
 * Tier 0: the combat reflex. Find something to hit, walk to it, hit it.
 * <p>
 * This is knowingly <b>not</b> a copy of {@code AutoPlayTaskManager}, and the difference is the
 * whole point. That class re-runs target acquisition on <i>every</i> 700ms tick - a world-region
 * scan whose filter calls {@code GeoEngine.canSeeTarget} and {@code canMoveToTarget} on every
 * candidate. Correct for a handful of offline-play characters, ruinous at thousands.
 * <p>
 * Here the expensive work is rationed instead:
 * <ul>
 * <li>A target is <b>cached</b> on the agent. While it lives, ticks are arithmetic on a cached
 * reference - no region walk, no raycast.</li>
 * <li>A scan happens only when there is no target, and never more often than
 * {@link L3Config#ACQUIRE_COOLDOWN_MS}; repeated failures back off toward
 * {@link L3Config#ACQUIRE_BACKOFF_MAX_MS}, so an agent in an empty field costs almost nothing.</li>
 * <li>Line-of-sight raycasts run only for {@link Lod#HOT} agents, because they are the only ones
 * anybody can actually watch. A WARM agent clipping a rock is invisible and therefore free.</li>
 * </ul>
 * Nothing here is "smart" - deliberately. Intelligence is the job of the tiers above: goal choice,
 * persona, and the language model. This layer only has to make the body work.
 *
 * @author L3
 */
public class L3Reflex
{
	/**
	 * Runs one reflex tick for an agent that is HOT or WARM.
	 * @param agent the agent
	 * @param now current time in millis, passed in so a whole pool shares one clock reading
	 */
	public static void tick(L3Agent agent, long now)
	{
		final Player player = agent.getPlayer();
		agent.observeProgress();

		if (player.isDead())
		{
			agent.setState(L3AgentState.DEAD);
			if (!agent.isRevivePending() && player.canRevive())
			{
				agent.setRevivePending(true);
				agent.shout("accepting resurrection");
				org.l2jmobius.commons.threads.ThreadPool.schedule(() ->
				{
					if (player.isDead() && player.canRevive())
					{
						player.doRevive();
						agent.shout("resurrected");
					}
					agent.setRevivePending(false);
				}, Rnd.get(1000, 3000));
			}
			return;
		}
		agent.setRevivePending(false);
		if (player.getCurrentHp() < (player.getMaxHp() * 0.35))
		{
			agent.setState(L3AgentState.RECOVERING);
		}

		// Busy or unable to act: nothing to decide this tick.
		if (player.isDead() || player.isSitting() || player.isCastingNow() || player.isDisabled())
		{
			return;
		}

		if (useHealingPotion(agent))
		{
			return;
		}

		if (player.getCurrentHp() < (player.getMaxHp() * 0.35))
		{
			player.sitDown();
			agent.setState(L3AgentState.RECOVERING);
			return;
		}

		if (pickupNearby(agent, now))
		{
			return;
		}

		final boolean checkGeo = (agent.getLod() == Lod.HOT);

		// --- 1. Do we still have a usable target? ---------------------------------------------
		Creature target = resolveTarget(agent, player);

		if ((target != null) && agent.isTargetStale(now))
		{
			// Unreachable for too long - almost always geometry we cannot path around.
			dropTarget(agent, player);
			target = null;
		}

		// --- 2. No target: maybe scan for one (the rationed, expensive path) ------------------
		if (target == null)
		{
			agent.setState(L3AgentState.IDLE);
			if (!agent.mayAcquire(now))
			{
				return; // Still on cooldown: cost nothing this tick.
			}

			target = acquire(player, checkGeo);
			if (target == null)
			{
				agent.onAcquireFailed(now);
				return;
			}

			agent.onAcquireSucceeded(now, target.getObjectId());
			agent.setState(L3AgentState.HUNTING);
			L3Debug.event(agent, "TARGET", "monster=" + target.getObjectId());
			player.setTarget(target);
		}

		// --- 3. Engage -------------------------------------------------------------------------
		final double distance = player.calculateDistance2D(target);

		if (distance > L3Config.MELEE_RANGE)
		{
			agent.setState(L3AgentState.TRAVELING);
			// Re-issuing MoveTo every tick would spam the movement system; only nudge when idle.
			if (!player.isMoving() && player.hasAI())
			{
				player.getAI().setIntentionMoveTo(target);
			}

			return;
		}

		if (player.hasAI() && !player.isAttackingNow() && !player.isMoving())
		{
			agent.setState(L3AgentState.HUNTING);
			if ((player.getCurrentMp() > (player.getMaxMp() * 0.20)) && useAttackSkill(agent, target, now))
			{
				return;
			}
			if (player.getAI().getIntention() != Intention.ATTACK)
			{
				player.getAI().setIntentionAttack(target);
				agent.resetStuckTicks();
				return;
			}

			// Already "attacking" but visibly doing nothing: give it a few ticks, then re-pick.
			// AutoPlayTaskManager solves this with a side-step; a fresh target is simpler and, for
			// an agent nobody is watching, indistinguishable.
			if (agent.incrementStuckTicks() > 5)
			{
				dropTarget(agent, player);
			}
		}
	}

		private static boolean useAttackSkill(L3Agent agent, Creature target, long now)
		{
			final Player player = agent.getPlayer();
			if (!agent.mayUseSkill(now))
			{
				return false;
			}
			for (Skill skill : player.getSkills().values())
			{
				if (skill.isDamage() && !skill.isPassive() && !skill.isToggle() && !player.hasSkillReuse(skill.getReuseHashCode()) && skill.checkCondition(player, target, false))
				{
					player.setTarget(target);
					player.useMagic(skill, true, false);
					agent.cooldownSkill(now, 1000);
					L3Debug.event(agent, "COMBAT", "SKILL", skill.getName());
					agent.shoutDebug("casting " + skill.getName());
					return true;
				}
			}
			return false;
		}

		private static boolean useHealingPotion(L3Agent agent)
		{
			final Player player = agent.getPlayer();
			final long now = org.l2jmobius.commons.time.GameTime.currentTimeMillis();
			if (!agent.mayUsePotion(now))
			{
				return false;
			}
			if (player.getCurrentHp() >= (player.getMaxHp() * 0.60))
			{
				return false;
			}

			for (Item item : player.getInventory().getItems())
			{
				if ((item.getTemplate().getSkills() == null) || (item.getTemplate().getSkills().length == 0))
				{
					continue;
				}

				for (var holder : item.getTemplate().getSkills())
				{
					final Skill skill = holder.getSkill();
					if (skill.isHealingPotionSkill() && (player.getItemRemainingReuseTime(item.getObjectId()) <= 0))
					{
						final var handler = ItemHandler.getInstance().getHandler(item.getEtcItem());
						if ((handler != null) && handler.onItemUse(player, item, false))
						{
							if (item.getReuseDelay() > 0)
							{
								player.addTimeStampItem(item, item.getReuseDelay());
							}
							agent.shoutDebug("used a healing potion");
							agent.cooldownPotion(now, Math.max(1000, item.getReuseDelay()));
							L3Debug.event(agent, "SURVIVAL", "POTION", item.getTemplate().getName());
							return true;
						}
					}
				}
			}
			return false;
		}

		private static boolean pickupNearby(L3Agent agent, long now)
		{
			final Player player = agent.getPlayer();
			if (!agent.mayPickup(now))
			{
				return false;
			}
			final Item item = World.getFirstVisibleObjectInRange(player, Item.class, 200, dropped ->
			{
				if (!dropped.isSpawned())
				{
					return false;
				}

				if (!dropped.isProtected() || (dropped.getOwnerId() == player.getObjectId()))
				{
					return true;
				}

				// Items deliberately dropped by a nearby human are valid hand-offs to an AI agent.
				final Player owner = World.getPlayer(dropped.getOwnerId());
				return (owner != null) && !L3AgentManager.getInstance().isAgent(owner) && (owner.calculateDistance2D(player) <= 250);
			});
			if (item == null)
			{
				agent.cooldownPickup(now, 1000);
				return false;
			}
			if (player.calculateDistance2D(item) > 20)
			{
				if (!player.isMoving())
				{
					player.getAI().setIntentionMoveTo(item);
				}
			}
			else
			{
				if (item.getOwnerId() != 0 && (item.getOwnerId() != player.getObjectId()))
				{
					item.setOwnerId(0);
				}
				player.doPickupItem(item);
				agent.shoutDebug("picked up an item");
				L3Debug.event(agent, "SURVIVAL", "PICKUP", item.getTemplate().getName());
			}
			agent.cooldownPickup(now, 500);
			return true;
		}

		/**
	 * A COLD tick: nobody is anywhere near this agent, so it must not touch the world.
	 * <p>
	 * For now this only keeps the agent tidy (it should not be stuck in a combat intention while
	 * unobserved). This is the hook where the population's *abstract* progress will live - advancing
	 * experience, loot and inventory arithmetically, so thousands of unobserved agents keep
	 * developing believably without a single region scan or raycast between them.
	 */
	public static void tickCold(L3Agent agent, long now)
	{
		final Player player = agent.getPlayer();
		agent.setState(player.isDead() ? L3AgentState.DEAD : L3AgentState.IDLE);
		L3Debug.event(agent, "TICK", "cold");
		if (agent.getTargetObjectId() != 0)
		{
			dropTarget(agent, player);
		}
	}

	// --- helpers ------------------------------------------------------------------------------

	/** Resolves the cached target id, clearing it if the creature is gone or dead. */
	private static Creature resolveTarget(L3Agent agent, Player player)
	{
		final int targetId = agent.getTargetObjectId();
		if (targetId == 0)
		{
			return null;
		}

		final WorldObject object = World.findObject(targetId);
		if ((object == null) || !object.isCreature())
		{
			dropTarget(agent, player);
			return null;
		}

		final Creature creature = object.asCreature();
		if (creature.isAlikeDead() || !creature.isSpawned())
		{
			dropTarget(agent, player);
			return null;
		}

		return creature;
	}

	private static void dropTarget(L3Agent agent, Player player)
	{
		agent.clearTarget();
		if (player.getTarget() != null)
		{
			player.setTarget(null);
		}
	}

	/**
	 * The one expensive operation in this class: a region scan for something to fight.
	 * @param checkGeo whether to spend line-of-sight raycasts (HOT agents only)
	 */
	private static Creature acquire(Player player, boolean checkGeo)
	{
		return World.getNearestVisibleObjectInRange(player, Monster.class, L3Config.HUNT_RANGE, monster ->
		{
			if (monster.isAlikeDead() || monster.isRaid() || !monster.isTargetable() || !monster.isAutoAttackable(player))
			{
				return false;
			}

			// Leave anything another creature is already fighting; agents stealing each other's
			// mobs looks bad and wastes work.
			final WorldObject theirTarget = monster.getTarget();
			if ((theirTarget != null) && (theirTarget != player))
			{
				return false;
			}

			// Cheap vertical sanity check stands in for pathing on the WARM path.
			if (Math.abs(player.getZ() - monster.getZ()) > 300)
			{
				return false;
			}

			if (!checkGeo)
			{
				return true;
			}

			return GeoEngine.getInstance().canSeeTarget(player, monster) //
				&& GeoEngine.getInstance().canMoveToTarget(player.getX(), player.getY(), player.getZ(), monster.getX(), monster.getY(), monster.getZ(), player.getInstanceId());
		});
	}

	private L3Reflex()
	{
	}
}
