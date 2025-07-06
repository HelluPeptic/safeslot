package com.natamus.orediscoverybroadcast.config;

import com.natamus.orediscoverybroadcast.util.Reference;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class ConfigHandler {
	public static HashMap<String, List<String>> configMetaData = new HashMap<String, List<String>>();

	public static boolean onlyRunOnDedicatedServers = true;
	public static boolean ignorePlacedOreBlocks = true;
	public static int tickDelayBetweenSameOreBroastcasts = 100;
	public static String messageFormat = "%player% has found %ore%!";
	public static boolean lowercaseOreName = true;
	public static boolean addOreCountToMessage = false;
	public static boolean hideDeepslateFromName = true;
	public static boolean ignoreCreativePlayers = true;
	public static boolean ignoreFakePlayers = true;

	public static void initConfig() {
		configMetaData.put("onlyRunOnDedicatedServers", Arrays.asList(
			"If the mod should only run on dedicated servers. When enabled it's not sent when in a singleplayer world."
		));
		configMetaData.put("ignorePlacedOreBlocks", Arrays.asList(
			"If ore blocks placed by players should be ignored for broadcasts."
		));
		configMetaData.put("tickDelayBetweenSameOreBroastcasts", Arrays.asList(
			"How many ticks in between ore discoveries before another broadcast is sent. Resets when the same block is mined. 20 ticks = 1 second"
		));
		configMetaData.put("messageFormat", Arrays.asList(
			"The format of the broadcasted message. %player% = player name, %ore% = ore name"
		));
		configMetaData.put("lowercaseOreName", Arrays.asList(
			"Whether the ore name should be displayed in lowercase characters."
		));
		configMetaData.put("addOreCountToMessage", Arrays.asList(
			"If the broadcasted message should contain how big the ore vein is. Will be included in %ore%."
		));
		configMetaData.put("hideDeepslateFromName", Arrays.asList(
			"Whether the ore name should have 'deepslate' hidden if it exists."
		));
		configMetaData.put("ignoreCreativePlayers", Arrays.asList(
			"If enabled, ore discoveries won't be announced when a player is in creative mode."
		));
		configMetaData.put("ignoreFakePlayers", Arrays.asList(
			"If enabled, ore discoveries won't be announced when it is broken by a simulated fake player."
		));
	}
}