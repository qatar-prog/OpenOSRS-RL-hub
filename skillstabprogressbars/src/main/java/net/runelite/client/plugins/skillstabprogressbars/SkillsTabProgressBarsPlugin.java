package net.runelite.client.plugins.skillstabprogressbars;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Skills Progress Bars",
	description = "Adds progress bars to the skills tab to show how close the next level ups are",
	tags = {"skills", "stats", "levels", "progress", "bars"},
	type = PluginType.MISCELLANEOUS,
	enabledByDefault = false
)
@Slf4j
public class SkillsTabProgressBarsPlugin extends Plugin
{
	static final int MINIMUM_BAR_HEIGHT = 1;
	static final int MAXIMUM_BAR_HEIGHT = 32;

	@Inject
	private Client client;

	@Inject
	private SkillsTabProgressBarsPlugin plugin;

	@Inject
	private SkillsTabProgressBarsOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);

		// If user already logged in, we must manually get the first xp state
		// Otherwise, it would only show bars for skills being trained, or need a world hop/relog to show all
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			calculateAndStoreProgressForAllSkillsToLevel();
			attachHoverListeners();
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}

	@Provides
	SkillsTabProgressBarsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SkillsTabProgressBarsConfig.class);
	}

	final Map<Skill, Double> progressToLevelNormalised = new HashMap<>();

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		calculateAndStoreProgressToLevel(statChanged.getSkill(), statChanged.getXp(), statChanged.getLevel());
	}

	private void calculateAndStoreProgressForAllSkillsToLevel()
	{
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				// No calculation done for total level
				continue;
			}
			calculateAndStoreProgressToLevel(skill, client.getSkillExperience(skill), client.getRealSkillLevel(skill));
		}
	}

	private void calculateAndStoreProgressToLevel(Skill skill, int currentXp, int currentLevel)
	{
		double progressToLevelNormalised = 1d;
		if (currentLevel > 0 && currentLevel < Experience.MAX_REAL_LEVEL)
		{
			final int xpForCurrentLevel = Experience.getXpForLevel(currentLevel);
			progressToLevelNormalised =
				(1d * (currentXp - xpForCurrentLevel)) /
					(Experience.getXpForLevel(currentLevel + 1) - xpForCurrentLevel);
		}
		this.progressToLevelNormalised.put(skill, progressToLevelNormalised);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widget)
	{
		if (widget.getGroupId() == WidgetInfo.SKILLS_CONTAINER.getGroupId())
		{
			attachHoverListeners();
		}
	}

	static Skill hoveredSkill = null;

	private void attachHoverListeners()
	{
		Widget skillsContainer = client.getWidget(WidgetInfo.SKILLS_CONTAINER);
		if (skillsContainer == null)
		{
			return;
		}

		for (Widget skillWidget : skillsContainer.getStaticChildren())
		{
			final Skill skill = plugin.skillFromWidgetID(skillWidget.getId());
			if (skill != null)
			{ /* skip invalid skill widgets (such as the side stone) */
				skillWidget.setOnMouseOverListener((JavaScriptCallback) event -> hoveredSkill = skill);
			}
		}
		skillsContainer.setOnMouseLeaveListener((JavaScriptCallback) event -> hoveredSkill = null);
	}

	Skill skillFromWidgetID(int widgetID)
	{
		// RuneLite provides no mapping for widget IDs -> Skill, so this is required */
		switch (widgetID)
		{
			case 20971521:
				return Skill.ATTACK;
			case 20971522:
				return Skill.STRENGTH;
			case 20971523:
				return Skill.DEFENCE;
			case 20971524:
				return Skill.RANGED;
			case 20971525:
				return Skill.PRAYER;
			case 20971526:
				return Skill.MAGIC;
			case 20971527:
				return Skill.RUNECRAFT;
			case 20971528:
				return Skill.CONSTRUCTION;
			case 20971529:
				return Skill.HITPOINTS;
			case 20971530:
				return Skill.AGILITY;
			case 20971531:
				return Skill.HERBLORE;
			case 20971532:
				return Skill.THIEVING;
			case 20971533:
				return Skill.CRAFTING;
			case 20971534:
				return Skill.FLETCHING;
			case 20971535:
				return Skill.SLAYER;
			case 20971536:
				return Skill.HUNTER;
			case 20971537:
				return Skill.MINING;
			case 20971538:
				return Skill.SMITHING;
			case 20971539:
				return Skill.FISHING;
			case 20971540:
				return Skill.COOKING;
			case 20971541:
				return Skill.FIREMAKING;
			case 20971542:
				return Skill.WOODCUTTING;
			case 20971543:
				return Skill.FARMING;
			case 20971544:
				return Skill.OVERALL;
			default:
				return null;
		}
	}
}
