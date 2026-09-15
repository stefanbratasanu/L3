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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.InitialEquipmentData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.holders.InitialEquipment;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

import l3.L3Config;

/**
 * Turns a freshly created character into a plausible player: a level, a class, gear that matches
 * both, its class skills, and a stock of shots it actually uses.
 * <p>
 * Why this matters beyond cosmetics: a naked level-1 agent cannot fight anything, so its AI has
 * nothing to express. Gear and skills are what make the behaviour visible - and soulshots are the
 * difference between an agent that plinks at a monster for a minute and one that fights like a real
 * player.
 * <p>
 * Everything here uses the engine's own mechanisms rather than poking numbers: experience to set
 * level ({@code addExpAndSp}, as {@code //set_level} does), {@code giveAvailableSkills} for the
 * class skill tree, and {@code useEquippableItem} to equip - so an agent ends up in the same state a
 * real character would be.
 *
 * @author L3
 */
public class L3Outfitter
{
	private static final Logger LOGGER = Logger.getLogger(L3Outfitter.class.getName());

	/**
	 * Starting classes, verified from {@code PlayerClass}: id and whether it is a mage line.
	 * Agents keep their base class - Interlude subclass progression needs quests, and a base class
	 * already gives a full skill tree to fight with.
	 */
	private static final int[][] START_CLASSES =
	{
		{
			0,
			0
		}, // Human Fighter
		{
			10,
			1
		}, // Human Mage
		{
			18,
			0
		}, // Elven Fighter
		{
			25,
			1
		}, // Elven Mage
		{
			31,
			0
		}, // Dark Elf Fighter
		{
			38,
			1
		}, // Dark Elf Mage
		{
			44,
			0
		}, // Orc Fighter
		{
			49,
			1
		}, // Orc Mage
		{
			53,
			0
		}, // Dwarven Fighter
	};

	// Grade thresholds and gear, all item ids verified against this datapack.
	// Index order: no-grade, D, C, B, A, S.
	private static final int[] GRADE_MIN_LEVEL =
	{
		1,
		20,
		40,
		52,
		61,
		76
	};

	/** One-handed swords, one per grade: Long Sword, Knight's Sword, Caliburs, Blade of Serenity,
	 * Themis' Tongue, Infinity Blade. */
	private static final int[] SWORDS =
	{
		1295,
		128,
		75,
		136,
		8686,
		6611
	};

	/** Soulshot per grade (physical classes). */
	private static final int[] SOULSHOTS =
	{
		1835,
		1463,
		1464,
		1465,
		1466,
		1467
	};

	/** Blessed spiritshot per grade (mage classes). */
	private static final int[] BLESSED_SPIRITSHOTS =
	{
		3947,
		3948,
		3949,
		3950,
		3951,
		3952
	};

	/** @return a random starting class id */
	public static int randomClassId()
	{
		return START_CLASSES[Rnd.get(START_CLASSES.length)][0];
	}

	/** @return whether the given starting class id is a mage line */
	public static boolean isMageClass(int classId)
	{
		for (int[] entry : START_CLASSES)
		{
			if (entry[0] == classId)
			{
				return entry[1] == 1;
			}
		}

		return false;
	}

	/** @return the equipment grade index (0 = no grade ... 5 = S) appropriate to a level */
	private static int gradeFor(int level)
	{
		int grade = 0;
		for (int i = 0; i < GRADE_MIN_LEVEL.length; i++)
		{
			if (level >= GRADE_MIN_LEVEL[i])
			{
				grade = i;
			}
		}

		return grade;
	}

	/**
	 * Full kit-out: level, gear, skills, shots, and the test immortality.
	 * @param player a freshly created agent character, already in the world
	 * @param classId the class it was created as
	 * @param level the level to bring it to
	 */
	public static void outfit(Player player, int classId, int level)
	{
		try
		{
			setLevel(player, level);
			giveSkills(player);
			giveGearAndShots(player, classId, level);

			if (L3Config.TEST_IMMORTAL)
			{
				makeImmortal(player);
			}

			// Start at full health, and let the engine's own auto-use loop consume the shots we gave
			// it - the same mechanism offline-play characters use, so no bespoke shot code.
			player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
			player.setCurrentCp(player.getMaxCp());
			player.broadcastUserInfo();

			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: failed to outfit " + player.getName(), e);
		}
	}

	/** Applies the same starter skills and equipment used when a Human Fighter is created. */
	public static void outfitStarter(Player player, int classId)
	{
		try
		{
			giveSkills(player);
			final var equipment = InitialEquipmentData.getInstance().getClassEquipment(PlayerClass.getPlayerClass(classId));
			if (equipment != null)
			{
				for (InitialEquipment starter : equipment)
				{
					final Item item = player.addItem(ItemProcessType.REWARD, starter.getId(), starter.getCount(), null, false);
					if ((item != null) && item.isEquipable() && starter.isEquipped())
					{
						player.getInventory().equipItem(item);
					}
				}
			}

			if (L3Config.TEST_IMMORTAL)
			{
				makeImmortal(player);
			}
			player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
			player.setCurrentCp(player.getMaxCp());
			player.broadcastUserInfo();
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "L3: failed to apply starter outfit to " + player.getName(), e);
		}
	}

	/** A level in the configured band, so the population is varied rather than uniform. */
	public static int randomLevel()
	{
		return Rnd.get(L3Config.LEVEL_MIN, L3Config.LEVEL_MAX);
	}

	/**
	 * Brings a character to a level the way the engine does it - by granting the experience for that
	 * level, exactly as {@code //set_level} does, rather than writing the level field and leaving
	 * experience inconsistent with it.
	 */
	private static void setLevel(Player player, int level)
	{
		final long current = player.getExp();
		final long target = ExperienceData.getInstance().getExpForLevel(level);
		if (target > current)
		{
			player.addExpAndSp(target - current, 0);
		}
	}

	/** Learns everything the class skill tree offers at this level. */
	private static void giveSkills(Player player)
	{
		// includeByFs / includeAutoGet / includeRequiredItems: take the lot, since an agent has no
		// way to visit a trainer or buy a skill book.
		final int learned = player.giveAvailableSkills(true, true, true);
		if (learned > 0)
		{
			player.sendSkillList();
		}
	}

	/** A grade-appropriate weapon, equipped, plus a working stock of the right kind of shot. */
	private static void giveGearAndShots(Player player, int classId, int level)
	{
		final int grade = gradeFor(level);

		// Weapon. Equipping it also decides which shots are usable.
		final Item weapon = player.addItem(ItemProcessType.NONE, SWORDS[grade], 1, null, false);
		if (weapon != null)
		{
			player.useEquippableItem(weapon, false);
		}

		giveShots(player, classId, grade);
	}

	/**
	 * Shots matching the class and grade: soulshots for physical classes, blessed spiritshots for
	 * mages. Registered for automatic use so the engine consumes them in combat.
	 */
	public static void giveShots(Player player, int classId, int grade)
	{
		final int shotId = isMageClass(classId) ? BLESSED_SPIRITSHOTS[grade] : SOULSHOTS[grade];
		player.addItem(ItemProcessType.NONE, shotId, L3Config.SHOT_COUNT, null, false);
		player.addAutoSoulShot(shotId);
	}

	/**
	 * Test immortality. This is engine invulnerability rather than a hit-point floor: an agent takes
	 * no damage at all, which is the only way to be certain it survives a long demonstration. A
	 * floor would still lose races against a burst of damage between AI ticks.
	 */
	public static void makeImmortal(Player player)
	{
		player.setInvul(true);
		if (player.getCurrentHp() < L3Config.IMMORTAL_HP_FLOOR)
		{
			player.setCurrentHp(L3Config.IMMORTAL_HP_FLOOR);
		}
	}

	private L3Outfitter()
	{
	}
}
