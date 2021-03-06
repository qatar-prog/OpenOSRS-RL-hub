/*
 * Copyright (c) 2020, Matsyir <https://github.com/Matsyir>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.pvpperformancetracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.inject.Provides;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat.HitsplatType;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import org.apache.commons.lang3.ArrayUtils;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "PvP Performance Tracker",
	description = "Estimate performance in PvP by tracking various stats.",
	type = PluginType.PVP,
	enabledByDefault = false
)
public class PvpPerformanceTrackerPlugin extends Plugin
{
	public static Image ICON;
	public static SpriteManager SPRITE_MANAGER;
	public static PvpPerformanceTrackerConfig CONFIG;
	public static PvpPerformanceTrackerPlugin PLUGIN;
	public ArrayList<FightPerformance> fightHistory;

	// Last man standing map regions, including lobby
	private static final Set<Integer> LAST_MAN_STANDING_REGIONS = Set.of(13617, 13658, 13659, 13660, 13914, 13915, 13916);


	@Getter(AccessLevel.PACKAGE)
	private NavigationButton navButton;
	private boolean navButtonShown = false;

	@Getter(AccessLevel.PACKAGE)
	private PvpPerformanceTrackerPanel panel;

	@Inject
	private PvpPerformanceTrackerConfig config;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Getter(AccessLevel.PACKAGE)
	@Inject
	private RuneLiteConfig runeliteConfig;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PvpPerformanceTrackerOverlay overlay;

	@Inject
	private ItemManager itemManager;

	@Getter(AccessLevel.PACKAGE)
	private FightPerformance currentFight;

	private Gson gson;

	@Provides
	PvpPerformanceTrackerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PvpPerformanceTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		CONFIG = config;
		PLUGIN = this;
		panel = injector.getInstance(PvpPerformanceTrackerPanel.class);
		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "skull_red.png");
		ICON = new ImageIcon(icon).getImage();
		navButton = NavigationButton.builder()
			.tooltip("PvP Fight History")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		SPRITE_MANAGER = spriteManager;
		fightHistory = new ArrayList<>();

		gson = new GsonBuilder()
			.excludeFieldsWithoutExposeAnnotation()
			.registerTypeAdapter(Double.class, (JsonSerializer<Double>) (value, theType, context) ->
				value.isNaN() ? new JsonPrimitive(0) // Convert NaN to zero, otherwise, return as BigDecimal with scale of 3.
					: new JsonPrimitive(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP))
			).create();

		fightHistory();

		// add the panel's nav button depending on config
		if (config.showFightHistoryPanel() &&
			(!config.restrictToLms() || (client.getGameState() == GameState.LOGGED_IN && isAtLMS())))
		{
			navButtonShown = true;
			clientToolbar.addNavigation(navButton);
		}

		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		fightHistory.sort(FightPerformance::compareTo);
		String fightHistoryDataJson = gson.toJson(fightHistory.toArray(new FightPerformance[0]), FightPerformance[].class);
		configManager.setConfiguration("pvpperformancetracker", "fightHistoryData", fightHistoryDataJson);

		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(overlay);
	}

	// if a player enables the panel or restricts/unrestricts the location to LMS, hide/show the panel accordingly
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("pvpperformancetracker"))
		{
			return;
		}

		switch (event.getKey())
		{
			case "showFightHistoryPanel":
			case "restrictToLms":
				boolean isAtLms = isAtLMS();
				if (!navButtonShown && config.showFightHistoryPanel() &&
					(!config.restrictToLms() || isAtLms))
				{
					SwingUtilities.invokeLater(() -> clientToolbar.addNavigation(navButton));
					navButtonShown = true;
				}
				else if (navButtonShown && (!config.showFightHistoryPanel() || (config.restrictToLms() && !isAtLms)))
				{
					SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(navButton));
					navButtonShown = false;
				}
				break;
			case "useSimpleOverlay":
			case "showOverlayTitle":
			case "showOverlayNames":
			case "showOverlayOffPray":
			case "showOverlayDeservedDmg":
			case "showOverlayDmgDealt":
			case "showOverlayMagicHits":
				overlay.setLines();
				break;
			case "fightHistoryLimit":
				if (config.fightHistoryLimit() > 0 && fightHistory.size() > config.fightHistoryLimit())
				{
					int numToRemove = fightHistory.size() - config.fightHistoryLimit();
					// Remove oldest fightHistory until the size is smaller than the limit.
					// Should only remove one fight in most cases.
					fightHistory.removeIf((FightPerformance f) -> fightHistory.indexOf(f) < numToRemove);
					panel.rebuild();
				}
				break;
		}
	}

	// Keep track of a player's new target using this event.
	// It's worth noting that if you aren't in a fight, all player interactions including
	// trading & following will trigger a new fight and a new opponent. Due to this, set the lastFightTime
	// (in FightPerformance) in the past to only be 5 seconds before the time NEW_FIGHT_DELAY would trigger
	// and unset the opponent, in case the player follows a different player before actually starting
	// a fight or getting attacked. In other words, remain skeptical of the validity of this event.
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (config.restrictToLms() && !isAtLMS())
		{
			return;
		}

		stopFightIfOver();

		// if the client player already has a valid opponent,
		// or the event source/target aren't players, skip any processing.
		if ((hasOpponent() && currentFight.fightStarted())
			|| !(event.getSource() instanceof Player)
			|| !(event.getTarget() instanceof Player))
		{
			return;
		}

		Actor opponent;

		// If the event source is the player, then it is the player interacting with their potential opponent.
		if (event.getSource() == client.getLocalPlayer())
		{
			opponent = event.getTarget();
		}
		else if (event.getTarget() == client.getLocalPlayer())
		{
			opponent = event.getSource();
		}
		else // if neither source or target was the player, skip
		{
			return;
		}

		// start a new fight with the new found opponent, if a new one.
		if (!hasOpponent() || !currentFight.getOpponent().getName().equals(opponent.getName()))
		{
			currentFight = new FightPerformance(client.getLocalPlayer(), (Player) opponent, itemManager);
			overlay.setFight(currentFight);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// hide or show panel depending if config is restricted to LMS and if player is at LMS
		if (config.restrictToLms())
		{
			if (isAtLMS())
			{
				if (!navButtonShown && config.showFightHistoryPanel())
				{
					clientToolbar.addNavigation(navButton);
					navButtonShown = true;
				}
			}
			else
			{
				if (navButtonShown)
				{
					clientToolbar.removeNavigation(navButton);
					navButtonShown = false;
				}
			}
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		stopFightIfOver();

		// We will need to re-do this check later, but do it initially to prevent unnecessarily creating a thread
		// that won't be used
		if (hasOpponent() && event.getActor() != null)
		{
			// delay the animation processing, since we will also want to use equipment information for deserved,
			// damage, and equipment updates are loaded after the animation updates.
			clientThread.invokeLater(() ->
			{
				// must perform null checks again since this occurs a moment after the inital check.
				if (hasOpponent() && event.getActor() != null && event.getActor().getName() != null)
				{
					currentFight.checkForAttackAnimations(event.getActor().getName());
				}
			});
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		HitsplatType hitType = event.getHitsplat().getHitsplatType();
		if (!hasOpponent() || !(event.getActor() instanceof Player) ||
			!(hitType == HitsplatType.DAMAGE_ME || hitType == HitsplatType.DAMAGE_OTHER ||
				hitType == HitsplatType.POISON || hitType == HitsplatType.VENOM))
		{
			return;
		}

		currentFight.addDamageDealt(event.getActor().getName(), event.getHitsplat().getAmount());
	}

	// Returns true if the player has an opponent.
	private boolean hasOpponent()
	{
		return currentFight != null;
	}

	private void stopFightIfOver()
	{
		if (hasOpponent() && currentFight.isFightOver())
		{
			// add fight to fight history if it actually started
			if (currentFight.fightStarted())
			{
				addToFightHistory(currentFight);
			}
			currentFight = null;
		}
	}

	void addToFightHistory(FightPerformance fight)
	{
		if (fight == null)
		{
			return;
		}
		fightHistory.add(fight);
		if (config.fightHistoryLimit() > 0 && fightHistory.size() > config.fightHistoryLimit())
		{
			int numToRemove = fightHistory.size() - config.fightHistoryLimit();
			// Remove oldest fightHistory until the size is equal to the limit.
			// Should only remove one fight in most cases.
			fightHistory.removeIf((FightPerformance f) -> fightHistory.indexOf(f) < numToRemove);
			panel.rebuild();
		}
		else
		{
			panel.addFight(fight);
		}
	}

	void fightHistory()
	{
		List<FightPerformance> savedFights;
		try
		{
			savedFights = Arrays.asList(gson.fromJson(config.fightHistoryData(), FightPerformance[].class));
		}
		catch (Exception e)
		{
			// If an error was detected while deserializing fights, display that as a message dialog.
			createConfirmationModal("Fight History Data Invalid",
				"PvP Performance Tracker: your fight history data was outdated or corrupted, and could not be imported.");
			return;
		}

		// ADD SOME TEST FIGHTS TO THE HISTORY. - for testing UI
//		savedFights = new ArrayList<>();
//		for (int i = 0; i < 500; i++)
//		{
//			savedFights.add(FightPerformance.getTestInstance());
//		}

		savedFights.removeIf(Objects::isNull);
		fightHistory.addAll(savedFights);
		fightHistory.sort(FightPerformance::compareTo);

		// set fight log names since they aren't serialized but are on the parent class
		for (FightPerformance f : fightHistory)
		{
			if (f.getCompetitor().getFightLogEntries() == null || f.getOpponent().getFightLogEntries() == null)
			{
				continue;
			}

			f.getCompetitor().getFightLogEntries().forEach((FightLogEntry l) ->
				l.attackerName = f.getCompetitor().getName());
			f.getOpponent().getFightLogEntries().forEach((FightLogEntry l) ->
				l.attackerName = f.getOpponent().getName());
		}

		if (config.fightHistoryLimit() > 0 && fightHistory.size() > config.fightHistoryLimit())
		{
			int numToRemove = fightHistory.size() - config.fightHistoryLimit();
			// Remove oldest fightHistory until the size is equal to the limit.
			// Should only remove one fight in most cases.
			fightHistory.removeIf((FightPerformance f) -> fightHistory.indexOf(f) < numToRemove);
		}

		panel.rebuild();
	}

	void resetFightHistory()
	{
		fightHistory.clear();
		panel.rebuild();
	}

	void removeFight(FightPerformance fight)
	{
		fightHistory.remove(fight);
		panel.rebuild();
	}

	boolean isAtLMS()
	{
		final int[] mapRegions = client.getMapRegions();

		for (int region : LAST_MAN_STANDING_REGIONS)
		{
			if (ArrayUtils.contains(mapRegions, region))
			{
				return true;
			}
		}

		return false;
	}

	// Send a message to the chat. Send them messages to the trade chat since it is uncommonly
	// used while fighting, but game, public, private, and clan chat have their uses.
	public void createChatMessage(String chatMessage)
	{
		chatMessageManager
			.queue(QueuedMessage.builder()
				.type(ChatMessageType.TRADE)
				.runeLiteFormattedMessage(chatMessage)
				.build());
	}

	// create a simple confirmation modal, using a custom dialog so it can be always on top if the client is, to prevent
	// being stuck under the client.
	public void createConfirmationModal(String title, String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			JOptionPane optionPane = new JOptionPane();
			optionPane.setMessage(message);
			optionPane.setOptionType(JOptionPane.DEFAULT_OPTION);
			JDialog dialog = optionPane.createDialog(panel, title);
			dialog.setAlwaysOnTop(dialog.isAlwaysOnTopSupported() && runeliteConfig.gameAlwaysOnTop());
			dialog.setIconImage(ICON);
			dialog.setVisible(true);
		});
	}

	public void exportFightHistory()
	{
		String fightHistoryDataJson = gson.toJson(fightHistory.toArray(new FightPerformance[0]), FightPerformance[].class);
		configManager.setConfiguration("pvpperformancetracker", "fightHistoryData", fightHistoryDataJson);
		final StringSelection contents = new StringSelection(fightHistoryDataJson);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);

		createConfirmationModal("Fight History Export Succeeded", "Fight history data was copied to the clipboard.");
	}
}