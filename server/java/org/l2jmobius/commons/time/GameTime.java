/*
 * Copyright (c) 2026 L3 Project
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 */
package org.l2jmobius.commons.time;

/**
 * Virtual game clock. Wall-clock time remains available for infrastructure, while game systems can
 * advance at a configurable rate.
 */
public final class GameTime
{
	private static final long MAX_DELAY_MS = 3155695200000L;
	private static volatile ClockState _state = new ClockState(System.currentTimeMillis(), System.currentTimeMillis(), 1.0);

	private GameTime()
	{
	}

	public static synchronized void setSpeed(double speed)
	{
		if ((speed < 0) || (speed > 20))
		{
			throw new IllegalArgumentException("Game speed must be between 0 and 20.");
		}

		final long wallNow = System.currentTimeMillis();
		final ClockState state = _state;
		final long gameNow = state._gameOrigin + Math.round((wallNow - state._wallOrigin) * state._speed);
		_state = new ClockState(wallNow, gameNow, speed);
	}

	public static double getSpeed()
	{
		return _state._speed;
	}

	public static long currentTimeMillis()
	{
		final ClockState state = _state;
		return state._gameOrigin + Math.round((System.currentTimeMillis() - state._wallOrigin) * state._speed);
	}

	public static long scaleDelay(long delay)
	{
		if (delay <= 0)
		{
			return delay;
		}
		if (_state._speed <= 0)
		{
			return MAX_DELAY_MS;
		}
		return Math.max(1, Math.round(delay / _state._speed));
	}

	private record ClockState(long _wallOrigin, long _gameOrigin, double _speed)
	{
	}
}
