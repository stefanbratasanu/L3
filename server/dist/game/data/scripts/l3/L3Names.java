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

/**
 * Plausible player names for agents.
 * <p>
 * This exists for immersion, which is the entire point of the project. A world populated by
 * {@code L3Agent148575} is obviously fake the instant you read a name over someone's head, no matter
 * how good the behaviour underneath is. Syllable-built names look like what real players type, and
 * occasionally carry the digits and doubled letters that real names carry too.
 * <p>
 * Names must be unique - {@code characters.char_name} is a unique column - so callers should be
 * ready to ask for another one if creation is refused.
 *
 * @author L3
 */
public class L3Names
{
	private static final String[] STARTS =
	{
		"Al", "Ber", "Cal", "Dar", "El", "Fen", "Gar", "Hal", "Ith", "Jor",
		"Kal", "Lyr", "Mor", "Nev", "Orin", "Pel", "Quen", "Rav", "Syl", "Tor",
		"Ul", "Val", "Wyn", "Xan", "Yor", "Zed", "Bran", "Cor", "Dra", "Eld",
		"Fal", "Gil", "Hel", "Ira", "Kyr", "Lun", "Mel", "Nyx", "Oth", "Per"
	};

	private static final String[] MIDDLES =
	{
		"", "", "", "a", "e", "i", "o", "ar", "en", "in", "or", "ul", "an", "el"
	};

	private static final String[] ENDS =
	{
		"an", "ar", "as", "el", "en", "eth", "ia", "ic", "ik", "il",
		"in", "is", "on", "or", "os", "us", "wyn", "ra", "th", "ys"
	};

	/** A fresh random name, occasionally with a numeric suffix like a real player would use. */
	public static String random()
	{
		final StringBuilder sb = new StringBuilder(16);
		sb.append(STARTS[Rnd.get(STARTS.length)]);
		sb.append(MIDDLES[Rnd.get(MIDDLES.length)]);
		sb.append(ENDS[Rnd.get(ENDS.length)]);

		// A minority of real names carry digits; too many and the world looks like a bot farm.
		if (Rnd.get(100) < 18)
		{
			sb.append(Rnd.get(10, 99));
		}

		// Mobius enforces a maximum name length; stay comfortably inside it.
		final String name = sb.toString();
		return name.length() > 16 ? name.substring(0, 16) : name;
	}

	private L3Names()
	{
	}
}
