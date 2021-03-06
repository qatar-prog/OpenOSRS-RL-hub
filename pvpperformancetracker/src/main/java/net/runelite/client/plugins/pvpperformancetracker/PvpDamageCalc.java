/*
 * Copyright (c) 2020, Mazhar <https://twitter.com/maz_rs>
 * Copyright (c) 2020, Matsyir <https://github.com/matsyir>
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

import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.client.game.ItemManager;
import static net.runelite.client.plugins.pvpperformancetracker.AnimationData.AttackStyle;
import static net.runelite.client.plugins.pvpperformancetracker.FightLogEntry.nf;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;
import org.apache.commons.lang3.ArrayUtils;

@Slf4j
public class PvpDamageCalc
{
	private static final int WEAPON_SLOT = 3, CHEST_SLOT = 4, LEG_SLOT = 7,
		STAB_ATTACK = 0, SLASH_ATTACK = 1, CRUSH_ATTACK = 2, MAGIC_ATTACK = 3, RANGE_ATTACK = 4,
		STAB_DEF = 5, SLASH_DEF = 6, CRUSH_DEF = 7, MAGIC_DEF = 8, RANGE_DEF = 9,
		STRENGTH_BONUS = 10, RANGE_STRENGTH = 11, MAGIC_DAMAGE = 12;

	private static PvpPerformanceTrackerConfig config;

	private static final int STANCE_BONUS = 0; // assume they are not in controlled or defensive
	private static final double UNSUCCESSFUL_PRAY_DMG_MODIFIER = 0.6; // modifier for when you unsuccessfully hit off-pray

	// Offensive pray: assume you have valid. Piety for melee, Rigour for range, Augury for mage
	private static final double ATTACK_OFFENSIVE_PRAYER_MODIFIER = 1.2;
	private static final double STRENGTH_OFFENSIVE_PRAYER_MODIFIER = 1.23;
	private static final double MAGIC_OFFENSIVE_PRAYER_MODIFIER = 1.25;
	private static final double RANGE_OFFENSIVE_PRAYER_DMG_MODIFIER = 1.23;
	private static final double RANGE_OFFENSIVE_PRAYER_ATTACK_MODIFIER = 1.2;

	// Defensive pray: Assume you have one of the defensive prays active, but don't assume you have augury
	// while getting maged, since you would likely be planning to range or melee & using rigour/piety instead.
	private static final double MELEE_DEFENSIVE_PRAYER_MODIFIER = 1.25;
	private static final double MAGIC_DEFENSIVE_DEF_PRAYER_MODIFIER = 1.25;
	private static final double MAGIC_DEFENSIVE_MAGE_PRAYER_MODIFIER = 1;
	private static final double RANGE_DEFENSIVE_PRAYER_MODIFIER = 1.25;

	private static final double BALLISTA_SPEC_ACCURACY_MODIFIER = 1.25;
	private static final double BALLISTA_SPEC_DMG_MODIFIER = 1.25;

	private static final int ACB_SPEC_ACCURACY_MODIFIER = 2;

	private static final int DBOW_DMG_MODIFIER = 2;
	private static final int DBOW_SPEC_DMG_MODIFIER = 3;
	private static final int DBOW_SPEC_MIN_HIT = 16;

	private static final double DDS_SPEC_ACCURACY_MODIFIER = 1.25;
	private static final double DDS_SPEC_DMG_MODIFIER = 2.3;

	private static final int AGS_SPEC_ACCURACY_MODIFIER = 2;
	private static final double AGS_SPEC_INITIAL_DMG_MODIFIER = 1.1;
	private static final double AGS_SPEC_FINAL_DMG_MODIFIER = 1.25;

	private static final double VLS_SPEC_DMG_MODIFIER = 1.2;
	private static final double VLS_SPEC_MIN_DMG_MODIFIER = .2;
	private static final double VLS_SPEC_DEFENCE_SCALE = .25;
	private static final double SWH_SPEC_DMG_MODIFIER = 1.25;
	private static final double SWH_SPEC_MIN_DMG_MODIFIER = .25;

	// 0.975x is a simplified brimstone mage def formula, where x = opponent's mage def
	// 25% of attacks ignore 10% of mage def, therefore 25% of attacks are 90% mage def and 75% are the usual 100%.
	// original formula: 0.25(0.9x) + 0.75x ==> 0.975x
	public static final double BRIMSTONE_RING_OPPONENT_DEF_MODIFIER = 0.975;

	private final ItemManager itemManager;

	@Getter(AccessLevel.PACKAGE)
	private double averageHit = 0;
	@Getter(AccessLevel.PACKAGE)
	private double accuracy = 0;
	@Getter(AccessLevel.PACKAGE)
	private int minHit = 0;
	@Getter(AccessLevel.PACKAGE)
	private int maxHit = 0;

	public PvpDamageCalc(ItemManager itemManager)
	{
		config = PvpPerformanceTrackerPlugin.CONFIG;
		this.itemManager = itemManager;
	}

	public void updateDamageStats(Player attacker, Player defender, boolean success, AnimationData animationData)
	{
		// shouldn't be possible, but just in case
		if (attacker == null || defender == null)
		{
			return;
		}

		averageHit = 0;
		accuracy = 0;
		minHit = 0;
		maxHit = 0;

		AnimationData.AttackStyle attackStyle = animationData.attackStyle; // basic style: melee/ranged/magic

		int[] attackerItems = attacker.getPlayerAppearance().getEquipmentIds();
		int[] defenderItems = defender.getPlayerAppearance().getEquipmentIds();
		int[] playerStats = this.calculateBonuses(attackerItems);
		int[] opponentStats = this.calculateBonuses(defenderItems);

		// if it's a special attack, save that as a boolean, but change the animationType to be the root type
		// so that accuracy calculations are properly done. Special attack used will be determined based on the
		// currently used weapon, if its special attack has been implemented.
		boolean isSpecial = animationData.isSpecial;

		int weaponId = attackerItems[WEAPON_SLOT] > 512 ? attackerItems[WEAPON_SLOT] - 512 : attackerItems[WEAPON_SLOT];
		EquipmentData weapon = EquipmentData.getEquipmentDataFor(weaponId);

		if (ArrayUtils.contains(AttackStyle.MELEE_STYLES, attackStyle))
		{
			getMeleeMaxHit(playerStats[STRENGTH_BONUS], isSpecial, weapon);
			getMeleeAccuracy(playerStats, opponentStats, attackStyle, isSpecial, weapon);
		}
		else if (attackStyle == AttackStyle.RANGED)
		{
			getRangedMaxHit(playerStats[RANGE_STRENGTH], isSpecial, weapon);
			getRangeAccuracy(playerStats[RANGE_ATTACK], opponentStats[RANGE_DEF], isSpecial, weapon);
		}
		// this should always be true at this point, but just in case. unknown animation styles won't
		// make it here, they should be stopped in FightPerformance::checkForAttackAnimations
		else if (attackStyle == AttackStyle.MAGIC)
		{
			getMagicMaxHit(playerStats[MAGIC_DAMAGE], animationData.baseSpellDamage);
			getMagicAccuracy(playerStats[MAGIC_ATTACK], opponentStats[MAGIC_DEF]);
		}

		getAverageHit(success, weapon, isSpecial);

		maxHit = (int) (maxHit * (success ? 1 : UNSUCCESSFUL_PRAY_DMG_MODIFIER));

		log.warn("attackStyle: " + attackStyle.toString() + ", avgHit: " + nf.format(averageHit) + ", acc: " + nf.format(accuracy) +
			"\nattacker(" + attacker.getName() + ")stats: " + Arrays.toString(playerStats) +
			"\ndefender(" + defender.getName() + ")stats: " + Arrays.toString(opponentStats));
	}

	private void getAverageHit(boolean success, EquipmentData weapon, boolean usingSpec)
	{
		boolean dbow = weapon == EquipmentData.DARK_BOW;
		boolean ags = weapon == EquipmentData.ARMADYL_GODSWORD;
		boolean claws = weapon == EquipmentData.DRAGON_CLAWS;
		boolean vls = weapon == EquipmentData.VESTAS_LONGSWORD || weapon == EquipmentData.BLIGHTED_VESTAS_LONGSWORD;
		boolean swh = weapon == EquipmentData.STATIUS_WARHAMMER;

		double agsModifier = ags ? AGS_SPEC_FINAL_DMG_MODIFIER : 1;
		double prayerModifier = success ? 1 : UNSUCCESSFUL_PRAY_DMG_MODIFIER;
		double averageSuccessfulHit;
		if (usingSpec && (dbow || vls || swh))
		{
			double accuracyAdjuster = dbow ? accuracy : 1;
			minHit = dbow ? DBOW_SPEC_MIN_HIT : 0;
			minHit = vls ? (int) (maxHit * VLS_SPEC_MIN_DMG_MODIFIER) : minHit;
			minHit = swh ? (int) (maxHit * SWH_SPEC_MIN_DMG_MODIFIER) : minHit;

			int total = 0;

			for (int i = 0; i <= maxHit; i++)
			{
				total += i < minHit ? minHit / accuracyAdjuster : i;
			}

			averageSuccessfulHit = (double) total / maxHit;
		}
		else if (usingSpec && claws)
		{
			double invertedAccuracy = 1 - accuracy;
			double averageSuccessfulRegularHit = maxHit / 2;
			double higherModifierChance = (accuracy + (accuracy * invertedAccuracy));
			double lowerModifierChance = ((accuracy * Math.pow(invertedAccuracy, 2)) + (accuracy * Math.pow(invertedAccuracy, 3)));
			double averageSpecialHit = ((higherModifierChance * 2) + (lowerModifierChance * 1.5)) * averageSuccessfulRegularHit;

			averageHit = averageSpecialHit * prayerModifier;
			accuracy = higherModifierChance + lowerModifierChance;
			maxHit = (maxHit * 2 + 1);
			return;
		}
		else
		{
			averageSuccessfulHit = maxHit / 2.0;
		}

		averageHit = accuracy * averageSuccessfulHit * prayerModifier * agsModifier;
	}

	private void getMeleeMaxHit(int meleeStrength, boolean usingSpec, EquipmentData weapon)
	{
		boolean ags = weapon == EquipmentData.ARMADYL_GODSWORD;
		boolean dds = ArrayUtils.contains(EquipmentData.DRAGON_DAGGERS, weapon);
		boolean vls = weapon == EquipmentData.VESTAS_LONGSWORD || weapon == EquipmentData.BLIGHTED_VESTAS_LONGSWORD;
		boolean swh = weapon == EquipmentData.STATIUS_WARHAMMER;

		int effectiveLevel = (int) Math.floor((config.strengthLevel() * STRENGTH_OFFENSIVE_PRAYER_MODIFIER) + 8 + 3);
		int baseDamage = (int) Math.floor(0.5 + effectiveLevel * (meleeStrength + 64) / 640);
		double modifier = ags && usingSpec ? AGS_SPEC_INITIAL_DMG_MODIFIER : 1;
		modifier = (swh && usingSpec) ? SWH_SPEC_DMG_MODIFIER : modifier;
		modifier = (dds && usingSpec) ? DDS_SPEC_DMG_MODIFIER : modifier;
		modifier = (vls && usingSpec) ? VLS_SPEC_DMG_MODIFIER : modifier;
		maxHit = (int) (modifier * baseDamage);
	}

	private void getRangedMaxHit(int rangeStrength, boolean usingSpec, EquipmentData weapon)
	{
		RangeAmmoData weaponAmmo = EquipmentData.getWeaponAmmo(weapon);
		boolean ballista = weapon == EquipmentData.HEAVY_BALLISTA;
		boolean dbow = weapon == EquipmentData.DARK_BOW;

		int ammoStrength = weaponAmmo == null ? 0 : weaponAmmo.getRangeStr();

		rangeStrength += ammoStrength;

		int effectiveLevel = (int) Math.floor((config.rangedLevel() * RANGE_OFFENSIVE_PRAYER_DMG_MODIFIER) + 8);
		int baseDamage = (int) Math.floor(0.5 + effectiveLevel * (rangeStrength + 64) / 640);

		double modifier = weaponAmmo == null ? 1 : weaponAmmo.getDmgModifier();
		modifier = ballista && usingSpec ? BALLISTA_SPEC_DMG_MODIFIER : modifier;
		modifier = dbow && !usingSpec ? DBOW_DMG_MODIFIER : modifier;
		modifier = dbow && usingSpec ? DBOW_SPEC_DMG_MODIFIER : modifier;
		maxHit = weaponAmmo == null ?
			(int) (modifier * baseDamage) :
			(int) ((modifier * baseDamage) + weaponAmmo.getBonusMaxHit());
	}

	private void getMagicMaxHit(int mageDamageBonus, int baseSpellDamage)
	{
		double magicBonus = 1 + (mageDamageBonus / 100);
		maxHit = (int) (baseSpellDamage * magicBonus);
	}

	private void getMeleeAccuracy(int[] playerStats, int[] opponentStats, AttackStyle attackStyle, boolean usingSpec, EquipmentData weapon)
	{
		boolean vls = weapon == EquipmentData.VESTAS_LONGSWORD || weapon == EquipmentData.BLIGHTED_VESTAS_LONGSWORD;
		boolean ags = weapon == EquipmentData.ARMADYL_GODSWORD;
		boolean dds = weapon == EquipmentData.DRAGON_DAGGER;

		double stabBonusPlayer = playerStats[STAB_ATTACK];
		double slashBonusPlayer = playerStats[SLASH_ATTACK];
		double crushBonusPlayer = playerStats[CRUSH_ATTACK];

		double stabBonusTarget = opponentStats[STAB_DEF];
		double slashBonusTarget = opponentStats[SLASH_DEF];
		double crushBonusTarget = opponentStats[CRUSH_DEF];

		double effectiveLevelPlayer;
		double effectiveLevelTarget;

		double baseChance;
		double attackerChance;
		double defenderChance;

		double accuracyModifier = dds ? DDS_SPEC_ACCURACY_MODIFIER : ags ? AGS_SPEC_ACCURACY_MODIFIER : 1;

		/**
		 * Attacker Chance
		 */
		effectiveLevelPlayer = Math.floor(((config.attackLevel() * ATTACK_OFFENSIVE_PRAYER_MODIFIER) + STANCE_BONUS) + 8);

		final double attackBonus = attackStyle == AttackStyle.STAB ? stabBonusPlayer
			: attackStyle == AttackStyle.SLASH ? slashBonusPlayer : crushBonusPlayer;

		final double targetDefenceBonus = attackStyle == AttackStyle.STAB ? stabBonusTarget
			: attackStyle == AttackStyle.SLASH ? slashBonusTarget : crushBonusTarget;


		baseChance = Math.floor(effectiveLevelPlayer * (attackBonus + 64));
		if (usingSpec)
		{
			baseChance = baseChance * accuracyModifier;
		}

		attackerChance = baseChance;

		/**
		 * Defender Chance
		 */
		effectiveLevelTarget = Math.floor(((config.defenceLevel() * MELEE_DEFENSIVE_PRAYER_MODIFIER) + STANCE_BONUS) + 8);

		if (vls && usingSpec)
		{
			defenderChance = Math.floor((effectiveLevelTarget * (stabBonusTarget + 64)) * VLS_SPEC_DEFENCE_SCALE);
		}
		else
		{
			defenderChance = Math.floor(effectiveLevelTarget * (targetDefenceBonus + 64));
		}
//        log.debug("MELEE ATTACK: " + defenderChance );
		/**
		 * Calculate Accuracy
		 */
		if (attackerChance > defenderChance)
		{
			accuracy = 1 - (defenderChance + 2) / (2 * (attackerChance + 1));
		}
		else
		{
			accuracy = attackerChance / (2 * (defenderChance + 1));
		}
	}

	private void getRangeAccuracy(int playerRangeAtt, int opponentRangeDef, boolean usingSpec, EquipmentData weapon)
	{
		RangeAmmoData weaponAmmo = EquipmentData.getWeaponAmmo(weapon);
		boolean diamonds = ArrayUtils.contains(RangeAmmoData.DIAMOND_BOLTS, weaponAmmo);
		double effectiveLevelPlayer;
		double effectiveLevelTarget;
		double rangeModifier;
		double attackerChance;
		double defenderChance;

		/**
		 * Attacker Chance
		 */
		effectiveLevelPlayer = Math.floor(((config.rangedLevel() * RANGE_OFFENSIVE_PRAYER_ATTACK_MODIFIER) + STANCE_BONUS) + 8);
		rangeModifier = Math.floor(effectiveLevelPlayer * ((double) playerRangeAtt + 64));
		if (usingSpec)
		{
			boolean acb = weapon == EquipmentData.ARMADYL_CROSSBOW;
			boolean ballista = weapon == EquipmentData.HEAVY_BALLISTA;

			double specAccuracyModifier = acb ? ACB_SPEC_ACCURACY_MODIFIER :
				ballista ? BALLISTA_SPEC_ACCURACY_MODIFIER : 1;

			attackerChance = Math.floor(rangeModifier * specAccuracyModifier);
		}
		else
		{
			attackerChance = rangeModifier;
		}

		/**
		 * Defender Chance
		 */
		effectiveLevelTarget = Math.floor(((config.defenceLevel() * RANGE_DEFENSIVE_PRAYER_MODIFIER) + STANCE_BONUS) + 8);
		defenderChance = Math.floor(effectiveLevelTarget * ((double) opponentRangeDef + 64));

		/**
		 * Calculate Accuracy
		 */
		if (attackerChance > defenderChance)
		{
			accuracy = 1 - (defenderChance + 2) / (2 * (attackerChance + 1));
		}
		else
		{
			accuracy = attackerChance / (2 * (defenderChance + 1));
		}

		// diamond bolts accuracy: 10% of attacks are 100% accuracy, so apply avg accuracy as:
		// (90% of normal accuracy) + (10% of 100% accuracy)
		accuracy = diamonds ? (accuracy * .9) + .1 : accuracy;
	}

	private void getMagicAccuracy(int playerMageAtt, int opponentMageDef)
	{
		double effectiveLevelPlayer;

		double reducedDefenceLevelTarget;
		double effectiveMagicDefenceTarget;
		double effectiveMagicLevelTarget;

		double effectiveLevelTarget;

		double magicModifier;

		double attackerChance;
		double defenderChance;

		/**
		 * Attacker Chance
		 */
		effectiveLevelPlayer = Math.floor(((config.magicLevel() * MAGIC_OFFENSIVE_PRAYER_MODIFIER)) + 8);
		magicModifier = Math.floor(effectiveLevelPlayer * ((double) playerMageAtt + 64));
		attackerChance = magicModifier;

		/**
		 * Defender Chance
		 */
		effectiveLevelTarget = Math.floor(((config.defenceLevel() * MAGIC_DEFENSIVE_DEF_PRAYER_MODIFIER) + STANCE_BONUS) + 8);
		effectiveMagicLevelTarget = Math.floor((config.magicLevel() * MAGIC_DEFENSIVE_MAGE_PRAYER_MODIFIER) * 0.70);
		reducedDefenceLevelTarget = Math.floor(effectiveLevelTarget * 0.30);
		effectiveMagicDefenceTarget = effectiveMagicLevelTarget + reducedDefenceLevelTarget;

		// 0.975x is a simplified brimstone accuracy formula, where x = mage def
		defenderChance = config.ringChoice() == RingData.BRIMSTONE_RING ?
			Math.floor(effectiveMagicDefenceTarget * ((BRIMSTONE_RING_OPPONENT_DEF_MODIFIER * opponentMageDef) + 64)) :
			Math.floor(effectiveMagicDefenceTarget * ((double) opponentMageDef + 64));

		/**
		 * Calculate Accuracy
		 */
		if (attackerChance > defenderChance)
		{
			accuracy = 1 - (defenderChance + 2) / (2 * (attackerChance + 1));
		}
		else
		{
			accuracy = attackerChance / (2 * (defenderChance + 1));
		}
	}

	// Retrieve item stats for a single item, returned as an int array so they can be modified.
	// First, try to get the item stats from the item manager. If stats weren't present in the
	// itemManager, try get the 'real' item id from the EquipmentData. If it's not defined in EquipmentData, it will return null
	// and count as 0 stats, but that should be very rare.
	public int[] getItemStats(int itemId)
	{
		ItemStats itemStats = this.itemManager.getItemStats(itemId, false);
		if (itemStats == null)
		{
			EquipmentData itemData = EquipmentData.getEquipmentDataFor(itemId);
			if (itemData != null)
			{
				itemId = itemData.getItemId();
				itemStats = this.itemManager.getItemStats(itemId, false);
			}
		}

		if (itemStats != null)
		{
			final ItemEquipmentStats equipmentStats = itemStats.getEquipment();
			return new int[]{
				equipmentStats.getAstab(),    // 0
				equipmentStats.getAslash(),   // 1
				equipmentStats.getAcrush(),   // 2
				equipmentStats.getAmagic(),   // 3
				equipmentStats.getArange(),   // 4
				equipmentStats.getDstab(),    // 5
				equipmentStats.getDslash(),   // 6
				equipmentStats.getDcrush(),   // 7
				equipmentStats.getDmagic(),   // 8
				equipmentStats.getDrange(),   // 9
				equipmentStats.getStr(),      // 10
				equipmentStats.getRstr(),     // 11
				equipmentStats.getMdmg(),     // 12
			};
		}

		// when combining multiple items' stats, null stats will just be skipped, without affecting
		// the total stats.
		return null;
	}

	// Calculate total equipment bonuses for all given items
	private int[] calculateBonuses(int[] itemIds)
	{
		int[] equipmentBonuses = config.ringChoice() == RingData.NONE ?
			new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0} :
			getItemStats(config.ringChoice().getItemId());

		if (equipmentBonuses == null)
		{
			equipmentBonuses = new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
		}

		//int[] equipmentBonuses = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, PvpPerformanceTrackerPlugin.CONFIG.assumeZerkRing() ? 4 : 0, 0, 0 };
		for (int item : itemIds)
		{
			if (item > 512)
			{
				int[] bonuses = getItemStats(item - 512);

				if (bonuses == null)
				{
					continue;
				}

				for (int id = 0; id < bonuses.length; id++)
				{
					equipmentBonuses[id] += bonuses[id];
				}
			}
		}

		return equipmentBonuses;
	}
}