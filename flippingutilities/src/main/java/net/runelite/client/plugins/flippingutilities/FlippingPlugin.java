/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.flippingutilities;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import static net.runelite.api.VarPlayer.CURRENT_GE_ITEM;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetHiddenChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.account.AccountSession;
import net.runelite.client.account.SessionManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.SessionClose;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.ge.GrandExchangeTrade;
import net.runelite.http.api.item.ItemStats;

@Slf4j
@PluginDescriptor(
	name = "Flipping Utilities",
	description = "Provides utilities for GE flipping"
)

public class FlippingPlugin extends Plugin
{
	//Limit the amount of trades every item holds.
	private static final int TRADE_HISTORY_MAX_SIZE = 20;
	//Limit the amount of items stored.
	private static final int TRADES_LIST_MAX_SIZE = 200;

	private static final int GE_HISTORY_TAB_WIDGET_ID = 149;
	private static final int GE_BACK_BUTTON_WIDGET_ID = 30474244;

	public static final String CONFIG_GROUP = "flipping";
	public static final String CONFIG_KEY = "items";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> timeUpdateFuture;
	@Inject
	private ClientToolbar clientToolbar;
	private NavigationButton navButton;

	@Inject
	private ConfigManager configManager;
	@Inject
	private SessionManager sessionManager;
	@Inject
	@Getter
	private FlippingConfig config;

	@Inject
	private ItemManager itemManager;

	private FlippingPanel panel;

	//Stores all bought or sold trades.
	@Getter
	private List<FlippingItem> tradesList = new ArrayList<>();

	//Ensures we don't rebuild constantly when highlighting
	@Setter
	private int prevHighlight;

	//To make sure we only record an offer once.
	GrandExchangeOffer savedOffer;
	int savedOfferSlot;

	@Override
	protected void startUp()
	{
		//Main visuals.
		panel = new FlippingPanel(this, itemManager, clientThread);

		// I wanted to put it below the GE plugin, but can't as the GE and world switcher buttonhave the same priority...
		navButton = NavigationButton.builder()
			.tooltip("Flipping Plugin")
			.icon(ImageUtil.getResourceStreamFromClass(getClass(), "graph-icon-green.png"))
			.priority(3)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(() ->
		{
			switch (client.getGameState())
			{
				case STARTING:
				case UNKNOWN:
					return false;
			}
			//Loads tradesList with data from previous sessions.
			if (config.storeTradeHistory())
			{
				loadConfig();
			}

			executor.submit(() -> clientThread.invokeLater(() -> SwingUtilities.invokeLater(() ->
			{
				if (tradesList != null)
				{
					for (FlippingItem flippingItem : tradesList)
					{
						flippingItem.updateGELimitReset();
					}
					panel.rebuildFlippingPanel(tradesList);
				}
			})));
			return true;
		});

		//Ensures the panel timers are updated at 10 times per second.
		timeUpdateFuture = executor.scheduleAtFixedRate(() ->
		{
			panel.updateTimes();
			updateGELimitRemaining();
		}, 100, 100, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (timeUpdateFuture != null)
		{
			//Stop all timers
			timeUpdateFuture.cancel(true);
			timeUpdateFuture = null;
		}

		clientToolbar.removeNavigation(navButton);
	}

	@Subscribe
	public void onSessionOpen(SessionOpen event)
	{
		//Load new account config
		final AccountSession session = sessionManager.getAccountSession();
		if (session != null && session.getUsername() != null)
		{
			clientThread.invokeLater(() ->
			{
				loadConfig();
				SwingUtilities.invokeLater(() -> panel.rebuildFlippingPanel(tradesList));
				return true;
			});
		}
	}

	@Subscribe
	public void onSessionClose(SessionClose event)
	{
		//Config is now locally stored
		clientThread.invokeLater(() ->
		{
			loadConfig();
			SwingUtilities.invokeLater(() -> panel.rebuildFlippingPanel(tradesList));
			return true;
		});
	}

	//When flipping via margin checking, we look for the lowest instant buy price
	// to determine what our sell price should be to undercut existing offers, and vice versa for our buy price.
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged newOfferEvent)
	{
		//Check for login screen and empty offers.
		if (newOfferEvent.getOffer().getItemId() == 0 || client.getWidget(WidgetInfo.LOGIN_CLICK_TO_PLAY_SCREEN) != null)
		{
			return;
		}

		final GrandExchangeOffer newOffer = newOfferEvent.getOffer();
		final GrandExchangeOfferState newOfferState = newOffer.getState();

		if ((newOfferState != GrandExchangeOfferState.BOUGHT && newOfferState != GrandExchangeOfferState.SOLD
			&& newOfferState != GrandExchangeOfferState.CANCELLED_BUY) || newOffer.getQuantitySold() == 0)
		{
			return;
		}

		//Cancelled buy offers updates 4 times for some reason.
		if (newOfferState == GrandExchangeOfferState.CANCELLED_BUY)
		{
			if (savedOffer != null && savedOffer.getState() == newOfferState && savedOfferSlot == newOfferEvent.getSlot())
			{
				return;
			}

			savedOffer = newOffer;
			savedOfferSlot = newOfferEvent.getSlot();
		}

		//Offer is a margin check.
		//May change this in the future to be able to update prices independent of quantity.
		//Perhaps some way of timing the state change from buying to bought such that we know it was "instantly" bought/sold?
		if ((newOfferState == GrandExchangeOfferState.BOUGHT || newOfferState == GrandExchangeOfferState.SOLD) && newOffer.getQuantitySold() == 1)
		{
			addFlipTrade(tradeConstructor(newOffer));
			panel.rebuildFlippingPanel(tradesList);
		}
		//Record the trade to keep track of GE limit.
		else if (newOffer.getQuantitySold() > 0 && newOfferState != GrandExchangeOfferState.SOLD)
		{
			addFlipTrade(tradeConstructor(newOffer));
		}

		updateConfig();
		panel.updateGELimit();
	}

	private GrandExchangeTrade tradeConstructor(GrandExchangeOffer offer)
	{
		GrandExchangeTrade result = new GrandExchangeTrade();
		result.setBuy(offer.getState() == GrandExchangeOfferState.BOUGHT || offer.getState() == GrandExchangeOfferState.CANCELLED_BUY);
		result.setItemId(offer.getItemId());
		result.setPrice(offer.getSpent() / offer.getQuantitySold());
		result.setQuantity(offer.getQuantitySold());
		result.setTime(Instant.now());

		return result;
	}

	//Adds GE trade data to the trades list.
	public void addFlipTrade(GrandExchangeTrade trade)
	{
		if (tradesList == null)
		{
			addToTradesList(trade);
			return;
		}
		//Check if item is already present
		final List<FlippingItem> matchingItem = tradesList.stream()
			.filter((item) -> item.getItemId() == trade.getItemId())
			.collect(Collectors.toList());

		//No match found
		if (matchingItem.size() == 0)
		{
			addToTradesList(trade);
		}
		else if (trade.getQuantity() == 1)
		{
			//Found a match, update the existing flipping item.
			updateFlip(matchingItem.get(0), trade);

			//Move item to top
			tradesList.remove(matchingItem.get(0));
			tradesList.add(0, matchingItem.get(0));
		}
		else
		{
			//Trade isn't a margin check, exclude updating prices.
			matchingItem.get(0).addTradeHistory(trade);
			matchingItem.get(0).updateGELimitReset();
		}
	}

	//Constructs a FlippingItem and adds it to the tradeList.
	private void addToTradesList(GrandExchangeTrade trade)
	{
		int tradeItemId = trade.getItemId();
		String itemName = itemManager.getItemDefinition(tradeItemId).getName();

		ItemStats itemStats = itemManager.getItemStats(tradeItemId, false);
		int tradeGELimit = itemStats != null ? itemStats.getGeLimit() : 0;
		List<GrandExchangeTrade> tradeHistory = new ArrayList<>()
		{{
			add(trade);
		}};

		int tradeBuyPrice = 0;
		int tradeSellPrice = 0;

		Instant tradeBuyTime = null;
		Instant tradeSellTime = null;

		if (trade.getQuantity() == 1)
		{
			tradeBuyPrice = !trade.isBuy() ? trade.getPrice() : 0;
			tradeSellPrice = trade.isBuy() ? trade.getPrice() : 0;

			tradeBuyTime = !trade.isBuy() ? trade.getTime() : null;
			tradeSellTime = trade.isBuy() ? trade.getTime() : null;
		}

		FlippingItem flippingItem = new FlippingItem(tradeHistory, tradeItemId, itemName, tradeGELimit, 0,
			tradeBuyPrice, tradeSellPrice, tradeBuyTime, tradeSellTime, null);

		flippingItem.updateGELimitReset();

		tradesList.add(0, flippingItem);

		//Make sure we don't have too much data.
		if (tradesList.size() > TRADES_LIST_MAX_SIZE)
		{
			tradesList.remove(tradesList.size() - 1);
		}
	}

	//Updates the latest margins writes history for a Flipping Item
	private void updateFlip(FlippingItem flippingItem, GrandExchangeTrade trade)
	{
		boolean tradeBuyState = trade.isBuy();
		int tradePrice = trade.getPrice();
		Instant tradeTime = trade.getTime();

		flippingItem.addTradeHistory(trade);
		//Make sure the individual item objects aren't massive.
		if (flippingItem.getTradeHistory().size() > TRADE_HISTORY_MAX_SIZE)
		{
			flippingItem.getTradeHistory().remove(flippingItem.getTradeHistory().size() - 1);
		}
		//Bought
		if (tradeBuyState)
		{
			flippingItem.setLatestSellPrice(tradePrice);
			flippingItem.setLatestSellTime(tradeTime);
		}
		else
		{
			flippingItem.setLatestBuyPrice(tradePrice);
			flippingItem.setLatestBuyTime(tradeTime);
		}
		flippingItem.updateGELimitReset();
	}

	@Provides
	FlippingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlippingConfig.class);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getIndex() == CURRENT_GE_ITEM.getId() &&
			client.getVar(CURRENT_GE_ITEM) != -1 && client.getVar(CURRENT_GE_ITEM) != 0)
		{
			highlightOffer();
		}
	}

	@Subscribe
	public void onWidgetHiddenChanged(WidgetHiddenChanged event)
	{
		Widget widget = event.getWidget();
		// If the back button is no longer visible, we know we aren't in the offer setup.
		if (panel.isItemHighlighted() && widget.isHidden() && widget.getId() == GE_BACK_BUTTON_WIDGET_ID)
		{
			panel.dehighlightItem();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// The player opens the trade history tab. Necessary since the back button isn't considered hidden here.
		if (event.getGroupId() == GE_HISTORY_TAB_WIDGET_ID && panel.isItemHighlighted())
		{
			panel.dehighlightItem();
		}
	}

	//TODO: Refactor this with a search on the search bar
	private void highlightOffer()
	{
		int currentGEItemId = client.getVar(CURRENT_GE_ITEM);
		if (currentGEItemId == prevHighlight || panel.isItemHighlighted())
		{
			return;
		}
		prevHighlight = currentGEItemId;
		panel.highlightItem(currentGEItemId);
	}

	private void updateGELimitRemaining()
	{
		long unitTime = Instant.now().toEpochMilli() / 100;

		if (unitTime % 50 == 0)
		{
			for (FlippingItem flippingItem : tradesList)
			{
				panel.updateGELimit();
			}
		}
	}

	//Functionality to the top right reset button.
	public void resetTradeHistory()
	{
		for (FlippingItem trade : tradesList)
		{
			trade = null;
		}
		tradesList.clear();
		panel.getPreHighlightList().clear();
		panel.setItemHighlighted(false);
		configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY);
		panel.cardLayout.show(panel.getCenterPanel(), FlippingPanel.getWELCOME_PANEL());
		panel.rebuildFlippingPanel(tradesList);
	}

	//Stores all the session trade data in config.
	public void updateConfig()
	{
		if (tradesList.isEmpty())
		{
			return;
		}
		final Gson gson = new Gson();
		executor.submit(() ->
		{
			final String json = gson.toJson(tradesList);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, json);
		});
	}

	//Loads previous session data to tradeList.
	public void loadConfig()
	{
		log.info("Loading flipping config");
		final String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);

		if (json == null)
		{
			return;
		}

		try
		{
			final Gson gson = new Gson();
			Type type = new TypeToken<List<FlippingItem>>()
			{

			}.getType();
			tradesList = gson.fromJson(json, type);
		}
		catch (Exception e)
		{
			log.info("Error loading flipping data: " + e);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CONFIG_GROUP))
		{
			//Ensure that user configs are updated after being changed
			switch (event.getKey())
			{
				case ("storeTradeHistory"):
				case ("outOfDateWarning"):
				case ("roiGradientMax"):
				case ("marginCheckLoss"):
				case ("twelveHourFormat"):
					panel.rebuildFlippingPanel(tradesList);
					break;
				default:
					break;
			}
		}
	}
}