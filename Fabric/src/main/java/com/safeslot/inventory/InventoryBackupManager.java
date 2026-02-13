package com.safeslot.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.safeslot.SafeslotMod;
import com.safeslot.integration.NemosBackpackIntegration;
import com.safeslot.mixin.ServerPlayerEntityAccessor;
import com.safeslot.util.InventorySlotHandler;
import com.safeslot.util.NbtUtil;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Main inventory backup management system
 * Handles all backup operations, commands, and file I/O with complete NBT preservation
 */
public class InventoryBackupManager {
    
    // Configuration
    private static final int MAX_BACKUPS_PER_PLAYER = 20;
    private static final Path BACKUP_DIRECTORY = Path.of("config", "safeslot", "backups");
    
    // Thread-safe storage for in-memory backups
    private static final Map<UUID, List<PlayerBackup>> playerBackups = new ConcurrentHashMap<>();
    private static final ReentrantReadWriteLock backupLock = new ReentrantReadWriteLock();
    
    // Async operations
    private static final ExecutorService backupExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Safeslot-Backup-Thread");
        t.setDaemon(true);
        return t;
    });
    
    // Initialization state
    private static boolean initialized = false;
    private static MinecraftServer serverInstance = null;
    
    /**
     * Initialize the backup manager system
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        
        SafeslotMod.LOGGER.info("Initializing Inventory Backup Manager");
        
        // Initialize integrations
        NemosBackpackIntegration.initialize();
        
        // Register commands
        registerCommands();
        
        // Register event handlers
        registerEventHandlers();
        
        // Ensure backup directory exists
        createBackupDirectory();
        
        initialized = true;
        SafeslotMod.LOGGER.info("Inventory Backup Manager initialized successfully (Nemo's backpack: {})", 
            NemosBackpackIntegration.isNemosBackpacksAvailable() ? "enabled" : "disabled");
    }
    
    /**
     * Register all safeslot commands
     */
    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("safeslot")
                .requires(source -> Permissions.check(source, "safeslot.command", 2))
                
                // View backups: /safeslot view <player>
                .then(CommandManager.literal("view")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(InventoryBackupManager::viewBackupsCommand)
                    )
                )
                
                // Manual backup: /safeslot backup <player>
                .then(CommandManager.literal("backup")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(InventoryBackupManager::createManualBackupCommand)
                    )
                )
                
                // Restore backup: /safeslot restore <player> [backup_number]
                .then(CommandManager.literal("restore")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> restoreBackupCommand(ctx, 1))
                        .then(CommandManager.argument("backup_number", IntegerArgumentType.integer(1, MAX_BACKUPS_PER_PLAYER))
                            .executes(ctx -> restoreBackupCommand(ctx, IntegerArgumentType.getInteger(ctx, "backup_number")))
                        )
                    )
                )
                
                // Cleanup old backups: /safeslot cleanup [keep_count]
                .then(CommandManager.literal("cleanup")
                    .executes(ctx -> cleanupBackupsCommand(ctx, 5))
                    .then(CommandManager.argument("keep_count", IntegerArgumentType.integer(1, MAX_BACKUPS_PER_PLAYER))
                        .executes(ctx -> cleanupBackupsCommand(ctx, IntegerArgumentType.getInteger(ctx, "keep_count")))
                    )
                )
                
                // Status command: /safeslot status
                .then(CommandManager.literal("status")
                    .executes(InventoryBackupManager::statusCommand)
                )
            );
        });
    }
    
    /**
     * Register server and player event handlers
     */
    private static void registerEventHandlers() {
        // Server startup
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            loadAllBackupsAsync();
        });
        
        // Server shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveAllBackupsAndShutdown();
        });
        
        // Player disconnect - create backup
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            createPlayerBackupAsync(handler.getPlayer());
        });
    }
    
    /**
     * Create a backup when a player leaves the server
     */
    private static void createPlayerBackupAsync(ServerPlayerEntity player) {
        CompletableFuture.runAsync(() -> {
            try {
                RegistryWrapper.WrapperLookup registries = ((ServerPlayerEntityAccessor) player).getServer().getRegistryManager();
                
                // Create comprehensive backup
                PlayerBackup backup = createPlayerBackup(player, registries);
                
                // Store the backup
                addBackupToPlayer(player.getUuid(), backup);
                
                // Save to disk immediately to prevent data loss
                savePlayerBackupsAsync(player.getUuid());
                
                SafeslotMod.LOGGER.info("Created disconnect backup for player: {}", player.getGameProfile().name());
                
            } catch (Exception e) {
                SafeslotMod.LOGGER.error("Failed to create backup for {}: {}", player.getGameProfile().name(), e.getMessage(), e);
            }
        }, backupExecutor);
    }
    
    /**
     * Create a complete player backup including inventory and Nemo's backpack
     */
    private static PlayerBackup createPlayerBackup(ServerPlayerEntity player, RegistryWrapper.WrapperLookup registries) {
        // Main inventory backup
        NbtCompound inventoryBackup = InventorySlotHandler.createInventoryBackup(player, registries);
        
        // Separate Nemo's backpack backup (uses reflection)
        NbtCompound backpackBackup = NemosBackpackIntegration.backupNemosBackpack(player, registries);
        
        // Create complete backup
        PlayerBackup backup = new PlayerBackup(
            player.getUuid(),
            player.getGameProfile().name(),
            System.currentTimeMillis(),
            inventoryBackup,
            backpackBackup
        );
        
        return backup;
    }
    
    /**
     * Add a backup to a player's backup list (thread-safe)
     */
    private static void addBackupToPlayer(UUID playerUuid, PlayerBackup backup) {
        backupLock.writeLock().lock();
        try {
            List<PlayerBackup> backups = playerBackups.computeIfAbsent(playerUuid, k -> new ArrayList<>());
            backups.add(0, backup); // Add to front (most recent first)
            
            // Remove old backups if we exceed the limit
            while (backups.size() > MAX_BACKUPS_PER_PLAYER) {
                backups.remove(backups.size() - 1);
            }
        } finally {
            backupLock.writeLock().unlock();
        }
    }
    
    /**
     * View backups command handler
     */
    private static int viewBackupsCommand(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        ServerPlayerEntity targetPlayer = findPlayerByName(context.getSource().getServer(), playerName);
        
        if (targetPlayer == null) {
            context.getSource().sendError(Text.literal("§cPlayer '" + playerName + "' not found or not online"));
            return 0;
        }
        
        UUID playerUuid = targetPlayer.getUuid();
        List<PlayerBackup> backups = getPlayerBackups(playerUuid);
        
        if (backups.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§eNo backups found for " + playerName), false);
            return Command.SINGLE_SUCCESS;
        }
        
        context.getSource().sendFeedback(() -> Text.literal("§a=== Backups for " + playerName + " ==="), false);
        
        for (int i = 0; i < backups.size(); i++) {
            PlayerBackup backup = backups.get(i);
            int backupNumber = i + 1;
            
            String backupInfo = String.format("§f[§a%d§f] §7%s §8- §f%s", 
                backupNumber,
                formatTimestamp(backup.getTimestamp()),
                getBackupSummary(backup)
            );
            
            context.getSource().sendFeedback(() -> Text.literal(backupInfo), false);
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Manual backup command handler
     */
    private static int createManualBackupCommand(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        ServerPlayerEntity targetPlayer = findPlayerByName(context.getSource().getServer(), playerName);
        
        if (targetPlayer == null) {
            context.getSource().sendError(Text.literal("§cPlayer '" + playerName + "' not found or not online"));
            return 0;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                RegistryWrapper.WrapperLookup registries = ((ServerPlayerEntityAccessor) targetPlayer).getServer().getRegistryManager();
                PlayerBackup backup = createPlayerBackup(targetPlayer, registries);
                
                addBackupToPlayer(targetPlayer.getUuid(), backup);
                savePlayerBackupsAsync(targetPlayer.getUuid());
                
                context.getSource().sendFeedback(() -> Text.literal("§aManual backup created for " + playerName), false);
                
            } catch (Exception e) {
                context.getSource().sendError(Text.literal("§cFailed to create backup: " + e.getMessage()));
                SafeslotMod.LOGGER.error("Manual backup failed", e);
            }
        }, backupExecutor);
        
        context.getSource().sendFeedback(() -> Text.literal("§eCreating manual backup for " + playerName + "..."), false);
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Restore backup command handler
     */
    private static int restoreBackupCommand(CommandContext<ServerCommandSource> context, int backupNumber) {
        String playerName = StringArgumentType.getString(context, "player");
        ServerPlayerEntity targetPlayer = findPlayerByName(context.getSource().getServer(), playerName);
        
        if (targetPlayer == null) {
            context.getSource().sendError(Text.literal("§cPlayer '" + playerName + "' not found or not online"));
            return 0;
        }
        
        List<PlayerBackup> backups = getPlayerBackups(targetPlayer.getUuid());
        
        if (backups.isEmpty()) {
            context.getSource().sendError(Text.literal("§cNo backups found for " + playerName));
            return 0;
        }
        
        if (backupNumber < 1 || backupNumber > backups.size()) {
            context.getSource().sendError(Text.literal("§cInvalid backup number. Use /safeslot view " + playerName + " to see available backups"));
            return 0;
        }
        
        PlayerBackup backup = backups.get(backupNumber - 1);
        RegistryWrapper.WrapperLookup registries = ((ServerPlayerEntityAccessor) targetPlayer).getServer().getRegistryManager();
        
        try {
            // Restore main inventory (excluding slot 46 to avoid conflicts)
            boolean inventoryRestored = InventorySlotHandler.restoreInventoryBackup(targetPlayer, backup.getInventoryData(), registries);
            
            // Separately restore Nemo's backpack using FleetTools approach
            boolean backpackRestored = NemosBackpackIntegration.restoreNemosBackpack(targetPlayer, backup.getBackpackData(), registries);
            
            if (inventoryRestored && backpackRestored) {
                context.getSource().sendFeedback(() -> Text.literal("§aSuccessfully restored backup #" + backupNumber + " for " + playerName), false);
                SafeslotMod.LOGGER.info("Restored backup #{} for {} (including Nemo's backpack)", backupNumber, playerName);
            } else if (inventoryRestored) {
                context.getSource().sendFeedback(() -> Text.literal("§eInventory restored but backpack may have failed for " + playerName), false);
                SafeslotMod.LOGGER.warn("Partial restore for backup #{} for {} - backpack failed", backupNumber, playerName);
            } else {
                context.getSource().sendError(Text.literal("§cRestore failed - items may not have been restored correctly"));
                SafeslotMod.LOGGER.warn("Restore failure for backup #{} for {}", backupNumber, playerName);
            }
            
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§cRestore failed: " + e.getMessage()));
            SafeslotMod.LOGGER.error("Restore failed for backup #{} for {}: {}", backupNumber, playerName, e.getMessage(), e);
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Cleanup backups command handler
     */
    private static int cleanupBackupsCommand(CommandContext<ServerCommandSource> context, int keepCount) {
        CompletableFuture.runAsync(() -> {
            try {
                int totalCleaned = 0;
                int playersAffected = 0;
                
                backupLock.writeLock().lock();
                try {
                    for (Map.Entry<UUID, List<PlayerBackup>> entry : playerBackups.entrySet()) {
                        List<PlayerBackup> backups = entry.getValue();
                        if (backups.size() > keepCount) {
                            int toRemove = backups.size() - keepCount;
                            // Remove oldest backups (from the end of the list)
                            for (int i = 0; i < toRemove; i++) {
                                backups.remove(backups.size() - 1);
                            }
                            totalCleaned += toRemove;
                            playersAffected++;
                            
                            // Save the cleaned up list
                            savePlayerBackupsAsync(entry.getKey());
                        }
                    }
                } finally {
                    backupLock.writeLock().unlock();
                }
                
                String message = String.format("§aCleanup complete: removed %d old backups for %d players", totalCleaned, playersAffected);
                context.getSource().sendFeedback(() -> Text.literal(message), false);
                
            } catch (Exception e) {
                context.getSource().sendError(Text.literal("§cCleanup failed: " + e.getMessage()));
                SafeslotMod.LOGGER.error("Backup cleanup failed", e);
            }
        }, backupExecutor);
        
        context.getSource().sendFeedback(() -> Text.literal("§eStarting backup cleanup..."), false);
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Status command handler
     */
    private static int statusCommand(CommandContext<ServerCommandSource> context) {
        int totalPlayers = playerBackups.size();
        int totalBackups = playerBackups.values().stream().mapToInt(List::size).sum();
        boolean nemosIntegration = NemosBackpackIntegration.isNemosBackpacksAvailable();
        
        context.getSource().sendFeedback(() -> Text.literal("§a=== Safeslot Status ==="), false);
        context.getSource().sendFeedback(() -> Text.literal("§fPlayers with backups: §a" + totalPlayers), false);
        context.getSource().sendFeedback(() -> Text.literal("§fTotal backups: §a" + totalBackups), false);
        context.getSource().sendFeedback(() -> Text.literal("§fMax per player: §a" + MAX_BACKUPS_PER_PLAYER), false);
        context.getSource().sendFeedback(() -> Text.literal("§fNemo's Backpacks: " + (nemosIntegration ? "§aEnabled" : "§cDisabled")), false);
        context.getSource().sendFeedback(() -> Text.literal("§fBackup directory: §7" + BACKUP_DIRECTORY.toAbsolutePath()), false);
        
        return Command.SINGLE_SUCCESS;
    }
    
    // Utility methods
    private static ServerPlayerEntity findPlayerByName(MinecraftServer server, String name) {
        return server.getPlayerManager().getPlayer(name);
    }
    
    private static List<PlayerBackup> getPlayerBackups(UUID playerUuid) {
        backupLock.readLock().lock();
        try {
            return new ArrayList<>(playerBackups.getOrDefault(playerUuid, Collections.emptyList()));
        } finally {
            backupLock.readLock().unlock();
        }
    }
    
    private static String formatTimestamp(long timestamp) {
        return new Date(timestamp).toString();
    }
    
    private static String getBackupSummary(PlayerBackup backup) {
        int totalItems = NbtUtil.getInt(backup.getInventoryData(), "totalItemsBackedUp");
        boolean hasBackpack = NbtUtil.getBoolean(backup.getBackpackData(), "nemosBackpackPresent");
        
        String summary = totalItems + " items";
        if (hasBackpack) {
            summary += " + backpack";
        }
        return summary;
    }
    
    private static void createBackupDirectory() {
        try {
            Files.createDirectories(BACKUP_DIRECTORY);
        } catch (IOException e) {
            SafeslotMod.LOGGER.error("Failed to create backup directory", e);
        }
    }
    
    // File I/O methods will be implemented in next part...
    // (loadAllBackupsAsync, savePlayerBackupsAsync, saveAllBackupsAndShutdown)
    
    /**
     * Load all backups from disk asynchronously
     */
    private static void loadAllBackupsAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(BACKUP_DIRECTORY)) {
                    return;
                }
                
                backupLock.writeLock().lock();
                try {
                    playerBackups.clear();
                } finally {
                    backupLock.writeLock().unlock();
                }
                
                Files.list(BACKUP_DIRECTORY)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(InventoryBackupManager::loadPlayerBackupFromFile);
                
                SafeslotMod.LOGGER.info("Loaded backups for {} players", playerBackups.size());
                
            } catch (Exception e) {
                SafeslotMod.LOGGER.error("Failed to load backups from disk", e);
            }
        }, backupExecutor);
    }
    
    /**
     * Load a single player's backup file
     */
    private static void loadPlayerBackupFromFile(Path backupFile) {
        try {
            String fileName = backupFile.getFileName().toString();
            String uuidString = fileName.substring(0, fileName.length() - 5); // Remove .json
            UUID playerUuid = UUID.fromString(uuidString);
            
            String jsonContent = Files.readString(backupFile, StandardCharsets.UTF_8);
            List<PlayerBackup> backups = deserializePlayerBackups(jsonContent);
            
            if (!backups.isEmpty()) {
                backupLock.writeLock().lock();
                try {
                    playerBackups.put(playerUuid, backups);
                } finally {
                    backupLock.writeLock().unlock();
                }
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.warn("Failed to load backup file {}: {}", backupFile, e.getMessage());
        }
    }
    
    /**
     * Save a player's backups to disk asynchronously
     */
    private static void savePlayerBackupsAsync(UUID playerUuid) {
        List<PlayerBackup> backupsToSave = getPlayerBackups(playerUuid);
        
        CompletableFuture.runAsync(() -> {
            try {
                String jsonContent = serializePlayerBackups(backupsToSave);
                Path backupFile = BACKUP_DIRECTORY.resolve(playerUuid.toString() + ".json");
                
                Files.writeString(backupFile, jsonContent, StandardCharsets.UTF_8, 
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    
            } catch (Exception e) {
                SafeslotMod.LOGGER.error("Failed to save backups for {}: {}", playerUuid, e.getMessage());
            }
        }, backupExecutor);
    }
    
    /**
     * Save all backups and shutdown the executor
     */
    private static void saveAllBackupsAndShutdown() {
        try {
            // Save all pending backups
            backupLock.readLock().lock();
            Map<UUID, List<PlayerBackup>> backupsToSave;
            try {
                backupsToSave = new HashMap<>(playerBackups);
            } finally {
                backupLock.readLock().unlock();
            }
            
            for (Map.Entry<UUID, List<PlayerBackup>> entry : backupsToSave.entrySet()) {
                try {
                    String jsonContent = serializePlayerBackups(entry.getValue());
                    Path backupFile = BACKUP_DIRECTORY.resolve(entry.getKey().toString() + ".json");
                    Files.writeString(backupFile, jsonContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    SafeslotMod.LOGGER.error("Failed to save backups during shutdown for {}: {}", entry.getKey(), e.getMessage());
                }
            }
            
            // Shutdown executor
            backupExecutor.shutdown();
            if (!backupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                SafeslotMod.LOGGER.warn("Backup executor did not terminate gracefully, forcing shutdown");
                backupExecutor.shutdownNow();
            }
            
            SafeslotMod.LOGGER.info("Safeslot backup manager shutdown complete");
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Error during backup manager shutdown", e);
            backupExecutor.shutdownNow();
        }
    }
    
    /**
     * Serialize player backups to JSON with proper binary NBT encoding
     */
    private static String serializePlayerBackups(List<PlayerBackup> backups) {
        JsonArray jsonArray = new JsonArray();
        
        for (PlayerBackup backup : backups) {
            JsonObject backupJson = new JsonObject();
            backupJson.addProperty("playerUuid", backup.getPlayerUuid().toString());
            backupJson.addProperty("playerName", backup.getPlayerName());
            backupJson.addProperty("timestamp", backup.getTimestamp());
            
            // Serialize NBT data as Base64 to preserve all data
            backupJson.addProperty("inventoryData", com.safeslot.util.NbtUtil.serializeNbtToBase64(backup.getInventoryData()));
            backupJson.addProperty("backpackData", com.safeslot.util.NbtUtil.serializeNbtToBase64(backup.getBackpackData()));
            
            jsonArray.add(backupJson);
        }
        
        return jsonArray.toString();
    }
    
    /**
     * Deserialize player backups from JSON with proper binary NBT decoding
     */
    private static List<PlayerBackup> deserializePlayerBackups(String jsonContent) {
        List<PlayerBackup> backups = new ArrayList<>();
        
        try {
            JsonArray jsonArray = JsonParser.parseString(jsonContent).getAsJsonArray();
            
            for (JsonElement element : jsonArray) {
                JsonObject backupJson = element.getAsJsonObject();
                
                UUID playerUuid = UUID.fromString(backupJson.get("playerUuid").getAsString());
                String playerName = backupJson.get("playerName").getAsString();
                long timestamp = backupJson.get("timestamp").getAsLong();
                
                // Deserialize NBT data from Base64
                NbtCompound inventoryData = com.safeslot.util.NbtUtil.deserializeNbtFromBase64(
                    backupJson.get("inventoryData").getAsString());
                NbtCompound backpackData = com.safeslot.util.NbtUtil.deserializeNbtFromBase64(
                    backupJson.get("backpackData").getAsString());
                
                PlayerBackup backup = new PlayerBackup(playerUuid, playerName, timestamp, inventoryData, backpackData);
                backups.add(backup);
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Failed to deserialize player backups: {}", e.getMessage());
        }
        
        return backups;
    }
    
    /**
     * Check if the backup manager is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
