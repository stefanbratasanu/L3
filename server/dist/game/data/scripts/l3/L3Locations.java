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

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.entity.Location;

/**
 * Places in the world to put agents.
 * <p>
 * These are the town and village centres of Interlude, and they are not invented: every coordinate
 * below was extracted from this datapack's own GM teleport pages
 * ({@code data/html/admin/teleports/TownAreas/*.htm}), so they are exactly where the server itself
 * sends a GM who asks to go to that town. That matters - a hand-typed coordinate that happens to
 * land inside a rock or under the floor produces agents that look broken for reasons that have
 * nothing to do with their AI.
 *
 * @author L3
 */
public class L3Locations
{
	/** Town and village centres, spread across the whole map. */
	public static final Location[] TOWNS =
	{
		new Location(147450, 27064, -2208), // Aden
		new Location(12428, 16551, -4588), // Dark Elf village
		new Location(18748, 145437, -3132), // Dion
		new Location(116551, -182493, -1525), // Dwarven village
		new Location(45873, 49288, -3064), // Elven village
		new Location(17144, 170156, -3502), // Floran
		new Location(82698, 148638, -3473), // Giran
		new Location(-83063, 150791, -3133), // Gludin
		new Location(-14225, 123540, -3121), // Gludio
		new Location(147725, -56517, -2780), // Goddard
		new Location(111115, 219017, -3547), // Heine
		new Location(116589, 76268, -2734), // Hunters Village
		new Location(-44133, -113911, -244), // Orc village
		new Location(82321, 55139, -1529), // Oren
		new Location(44070, -50243, -796), // Rune
		new Location(87358, -141982, -1341), // Schuttgart
		new Location(-82687, 243157, -3734), // Talking Island
	};

	/**
	 * Open hunting grounds - the places with monsters in them, which is where agents actually have
	 * something to do. Same provenance as {@link #TOWNS}: extracted from the datapack's own GM
	 * teleport pages ({@code teleports/WorldAreas/*.htm}), with towns, castles, fortresses and
	 * instanced areas filtered out.
	 */
	public static final Location[] FARM_AREAS =
	{
		new Location(155310, -16339, -3320), // Blazing Swamp
		new Location(188611, 20588, -3696), // The Forbidden Gateway
		new Location(124904, 61992, -3973), // The Enchanted Valley
		new Location(167047, 20304, -3328), // The Cemetery
		new Location(142065, 81300, -3000), // The Forest of Mirrors
		new Location(106517, -2871, -3454), // Ancient Battleground
		new Location(168217, 37990, -4072), // Forsaken Plains
		new Location(170838, 55776, -5280), // Silent Valley
		new Location(114306, 86573, -3112), // Hunters Valley
		new Location(135580, 19467, -3424), // Plains of Glory
		new Location(183543, -14974, -2768), // Fields of Massacre
		new Location(156898, 11217, -4032), // War-Torn Plains
		new Location(181737, 46469, -4276), // The Giant's Cave
		new Location(166182, 91560, -3168), // Anghel Waterfall
		new Location(50568, 152408, -2656), // Execution Grounds
		new Location(50081, 116859, -2176), // Partisan's Hideaway
		new Location(5106, 126916, -3664), // Cruma Marshlands
		new Location(38291, 148029, -3696), // Mandragora Farm
		new Location(58316, 163851, -2816), // Tanor Canyon
		new Location(34475, 188095, -2976), // Bee Hive
		new Location(29928, 151415, -2392), // Dion Hills
		new Location(10610, 156322, -2472), // Floran Agricultural Area
		new Location(630, 179184, -3720), // Plains of Dion
		new Location(73024, 118485, -3720), // Dragon Valley
		new Location(67933, 117045, -3544), // Death Pass
		new Location(85546, 131328, -3672), // Breka's Stronghold
		new Location(113553, 134813, -3540), // Gorgon Flower Garden
		new Location(43408, 206881, -3752), // Devil's Isle
		new Location(105918, 109759, -3170), // Hardin's Academy
		new Location(41528, 198358, -4648), // Pirate Tunnel
	};

	/** A random town centre. */
	public static Location randomTown()
	{
		return TOWNS[Rnd.get(TOWNS.length)];
	}

	/** A random hunting ground. */
	public static Location randomFarmArea()
	{
		return FARM_AREAS[Rnd.get(FARM_AREAS.length)];
	}

	/**
	 * A spawn point for a new agent: mostly hunting grounds, sometimes a town, scattered so a batch
	 * does not stack into one pillar of bodies.
	 * @param townPercent chance of picking a town instead of a field
	 * @param spread maximum offset on each axis
	 */
	public static Location randomSpawnPoint(int townPercent, int spread)
	{
		final Location base = (Rnd.get(100) < townPercent) ? randomTown() : randomFarmArea();
		return new Location(base.getX() + Rnd.get(-spread, spread), base.getY() + Rnd.get(-spread, spread), base.getZ());
	}

	/**
	 * A random town, scattered a little so a batch of agents does not stack into one pillar of
	 * bodies on the exact same coordinate.
	 * @param spread maximum offset on each axis
	 */
	public static Location randomTownScattered(int spread)
	{
		final Location town = randomTown();
		return new Location(town.getX() + Rnd.get(-spread, spread), town.getY() + Rnd.get(-spread, spread), town.getZ());
	}

	private L3Locations()
	{
	}
}
