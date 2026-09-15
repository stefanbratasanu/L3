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

import java.util.logging.Logger;

import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerChat;
import org.l2jmobius.gameserver.mechanics.events.listeners.AbstractEventListener;

import l3.agent.L3Agent;
import l3.agent.L3AgentManager;
import l3.ai.L3ThinkTaskManager;

/**
 * Entry point for the L3 agent system.
 * <p>
 * Mobius's script engine walks the whole scripts folder, compiles what it finds, and invokes any
 * {@code public static void main(String[])} it sees ({@code ScriptExecutor}). So this class is all
 * the registration L3 needs - <b>no upstream file is modified anywhere</b>, which keeps future
 * Mobius merges clean and means the entire system recompiles on every game-server boot. Restarting
 * just the game server (L3-run-game.bat) is therefore the whole edit-test loop.
 * <p>
 * It runs late in startup, after the world and spawns are loaded, which is exactly when it is safe
 * to touch agents.
 *
 * @author L3
 */
public class L3Bootstrap
{
	private static final Logger LOGGER = Logger.getLogger(L3Bootstrap.class.getName());

	public static void main(String[] args)
	{
		Containers.Global().addListener(new AbstractEventListener(Containers.Global(), EventType.ON_PLAYER_CHAT, L3Bootstrap.class)
		{
			@Override
			public <R extends org.l2jmobius.gameserver.mechanics.events.returns.AbstractEventReturn> R executeEvent(org.l2jmobius.gameserver.mechanics.events.holders.IBaseEvent event, Class<R> returnBackClass)
			{
				final OnPlayerChat chat = (OnPlayerChat) event;
				if ((chat.getTarget() != null) && (chat.getChatType() == org.l2jmobius.gameserver.network.enums.ChatType.WHISPER) && "debug".equalsIgnoreCase(chat.getText().trim()))
				{
					final L3Agent agent = L3AgentManager.getInstance().get(chat.getTarget().getObjectId());
					if (agent != null)
					{
						agent.toggleChatDebug();
					}
				}
				return null;
			}
		});
		L3ThinkTaskManager.getInstance().start();
		LOGGER.info("L3: agent system ready. Spawn with //l3spawn, remove with //l3spawn clean.");
	}

	private L3Bootstrap()
	{
	}
}
