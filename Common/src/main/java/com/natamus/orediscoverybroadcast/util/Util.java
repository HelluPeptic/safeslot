package com.natamus.orediscoverybroadcast.util;

import com.natamus.orediscoverybroadcast.data.Constants;
import com.natamus.orediscoverybroadcast.data.Variables;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Util {
	public static final WeakHashMap<Level, List<BlockPos>> ignoredOreBlockPositions = new WeakHashMap<Level, List<BlockPos>>();

	public static List<Block> blockBlacklist = new ArrayList<Block>();

	private static final HashMap<Block, ChatFormatting> blockColourMap = new HashMap<Block, ChatFormatting>();
	private static final HashMap<Block, ChatFormatting> defaultColourMap = new HashMap<Block, ChatFormatting>();

	private static final String dirpath = System.getProperty("user.dir") + File.separator + "config" + File.separator
			+ "orediscoverybroadcast";
	private static final File dir = new File(dirpath);
	private static final File blacklistFile = new File(dirpath + File.separator + "blacklist.txt");
	private static final File colourMapFile = new File(dirpath + File.separator + "colourmap.txt");

	private static final Set<Block> allowedOres = new HashSet<>(Arrays.asList(
			Blocks.DIAMOND_ORE,
			Blocks.DEEPSLATE_DIAMOND_ORE,
			Blocks.ANCIENT_DEBRIS));

	public static void attemptConfigProcessing(Level level) {
		if (!Variables.processedConfig) {
			System.out.println("[ODB DEBUG] attemptConfigProcessing called");
			try {
				processConfig(level);
				Variables.processedConfig = true;
				System.out.println("[ODB DEBUG] Config processed successfully");
			} catch (Exception ex) {
				Constants.logger.warn("[" + Reference.NAME + "] Error: Unable to generate config.");
				ex.printStackTrace();
			}
		}
	}

	public static void processConfig(Level level) throws IOException {
		System.out.println("[ODB DEBUG] processConfig called");
		setDefaultColourMap();
		Registry<Block> blockRegistry = level.registryAccess().registryOrThrow(Registries.BLOCK);
		// Skip all config file creation and reading. Only set up defaultColourMap and
		// allowedOres.
		System.out.println("[ODB DEBUG] Skipping config file creation and reading.");
	}

	private static void setDefaultColourMap() {
		defaultColourMap.put(Blocks.ANCIENT_DEBRIS, ChatFormatting.DARK_PURPLE);
		defaultColourMap.put(Blocks.COAL_ORE, ChatFormatting.DARK_GRAY);
		defaultColourMap.put(Blocks.DEEPSLATE_COAL_ORE, ChatFormatting.DARK_GRAY);
		defaultColourMap.put(Blocks.COPPER_ORE, ChatFormatting.YELLOW);
		defaultColourMap.put(Blocks.DEEPSLATE_COPPER_ORE, ChatFormatting.YELLOW);
		defaultColourMap.put(Blocks.DIAMOND_ORE, ChatFormatting.AQUA);
		defaultColourMap.put(Blocks.DEEPSLATE_DIAMOND_ORE, ChatFormatting.AQUA);
		defaultColourMap.put(Blocks.EMERALD_ORE, ChatFormatting.DARK_GREEN);
		defaultColourMap.put(Blocks.DEEPSLATE_EMERALD_ORE, ChatFormatting.DARK_GREEN);
		defaultColourMap.put(Blocks.GOLD_ORE, ChatFormatting.GOLD);
		defaultColourMap.put(Blocks.DEEPSLATE_GOLD_ORE, ChatFormatting.GOLD);
		defaultColourMap.put(Blocks.NETHER_GOLD_ORE, ChatFormatting.GOLD);
		defaultColourMap.put(Blocks.IRON_ORE, ChatFormatting.GRAY);
		defaultColourMap.put(Blocks.DEEPSLATE_IRON_ORE, ChatFormatting.GRAY);
		defaultColourMap.put(Blocks.LAPIS_ORE, ChatFormatting.BLUE);
		defaultColourMap.put(Blocks.NETHER_QUARTZ_ORE, ChatFormatting.WHITE);
		defaultColourMap.put(Blocks.REDSTONE_ORE, ChatFormatting.RED);
	}

	public static boolean isOre(BlockState blockState) {
		return isOre(blockState, blockState.getBlock());
	}

	public static boolean isOre(Block block) {
		return isOre(block.defaultBlockState(), block);
	}

	public static boolean isOre(BlockState blockState, Block block) {
		boolean result = allowedOres.contains(block);
		if (result) {
		}
		return result;
	}

	public static ChatFormatting getBroadcastColour(Block block) {
		if (block == Blocks.ANCIENT_DEBRIS) {
			return ChatFormatting.DARK_RED;
		} else if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
			return ChatFormatting.AQUA;
		}
		return ChatFormatting.YELLOW;
	}

	// Returns the color code string for the ore name in the broadcast message
	public static String getOreColorCode(Block block) {
		if (block == Blocks.ANCIENT_DEBRIS) {
			return "§4"; // dark red
		} else if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
			return "§b"; // aqua
		}
		return "§e"; // yellow (fallback)
	}

	public static boolean shouldBeIgnored(Level level, BlockPos blockPos) {
		boolean shouldIgnore = ignoredOreBlockPositions.computeIfAbsent(level, k -> new ArrayList<BlockPos>())
				.contains(blockPos);
		ignoredOreBlockPositions.get(level).remove(blockPos);
		return shouldIgnore;
	}

	public static int getOreCount(Level level, BlockPos blockPos, Block block) {
		if (!ignoredOreBlockPositions.containsKey(level)) {
			ignoredOreBlockPositions.put(level, new ArrayList<BlockPos>());
		}

		List<BlockPos> connectedOres = getBlocksNextToEachOther(level, blockPos, Arrays.asList(block));
		for (BlockPos connectedPos : connectedOres) {
			if (!ignoredOreBlockPositions.get(level).contains(connectedPos)) {
				ignoredOreBlockPositions.get(level).add(connectedPos);
			}
		}

		return connectedOres.size();
	}

	private static List<BlockPos> getBlocksNextToEachOther(Level level, BlockPos blockPos, List<Block> blocks) {
		List<BlockPos> connectedBlocks = new ArrayList<>();
		Queue<BlockPos> toCheck = new LinkedList<>();
		Set<BlockPos> checked = new HashSet<>();

		toCheck.add(blockPos);

		while (!toCheck.isEmpty()) {
			BlockPos currentPos = toCheck.poll();
			if (checked.contains(currentPos)) {
				continue;
			}

			checked.add(currentPos);
			connectedBlocks.add(currentPos);

			for (BlockPos neighborPos : getNeighbors(currentPos)) {
				if (!checked.contains(neighborPos) && blocks.contains(level.getBlockState(neighborPos).getBlock())) {
					toCheck.add(neighborPos);
				}
			}
		}

		return connectedBlocks;
	}

	private static List<BlockPos> getNeighbors(BlockPos pos) {
		List<BlockPos> neighbors = new ArrayList<>();
		neighbors.add(pos.north());
		neighbors.add(pos.south());
		neighbors.add(pos.east());
		neighbors.add(pos.west());
		neighbors.add(pos.above());
		neighbors.add(pos.below());
		return neighbors;
	}

	private static boolean isNumeric(String str) {
		if (str == null || str.isEmpty())
			return false;
		for (char c : str.toCharArray()) {
			if (!Character.isDigit(c))
				return false;
		}
		return true;
	}
}
