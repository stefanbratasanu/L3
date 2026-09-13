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

	/** A random town centre. */
	public static Location randomTown()
	{
		return TOWNS[Rnd.get(TOWNS.length)];
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
