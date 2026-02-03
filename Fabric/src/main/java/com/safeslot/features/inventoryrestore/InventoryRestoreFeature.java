package com.safeslot.features.inventoryrestore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.safeslot.mixin.ServerPlayerEntityAccessor;
import com.safeslot.util.NbtCompatHelper;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class InventoryRestoreFeature {
    // Thread-safe collections for concurrent access
    private static final Map<UUID, List<NbtCompound>> playerBackups = new ConcurrentHashMap<>();
    private static final int MAX_BACKUPS = 20;
    private static final Path BACKUP_DIR = Path.of("config", "Safeslot", "inventorybackups");
    
    // Threading infrastructure
    private static final ExecutorService BACKUP_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Safeslot-Backup-Thread");
        t.setDaemon(true);
        return t;
    });
    
    private static final ReentrantReadWriteLock backupLock = new ReentrantReadWriteLock();
    
    // Cache for pending backup operations to avoid duplicate saves
    private static final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    
    // Batching system to reduce file I/O operations
    private static final Map<UUID, Long> pendingBackupWrites = new ConcurrentHashMap<>();
    private static final long BATCH_DELAY_MS = 5000; // 5 seconds delay before writing to disk
    
    // Memory optimization: limit the size of backups in memory
    private static final int MAX_MEMORY_BACKUPS = 10; // Only keep 10 most recent in memory per player

    // BattleCore compatibility
    private static Boolean battleCorePresent = null;
    
    /**
     * Check if BattleCore mod is present
     */
    private static boolean isBattleCorePresent() {
        if (battleCorePresent == null) {
            try {
                Class.forName("com.battlecore.battle.Battle");
                battleCorePresent = true;
            } catch (ClassNotFoundException e) {
                battleCorePresent = false;
            }
        }
        return battleCorePresent;
    }
    
    /**
     * Check if player is in an active BattleCore battle
     */
    private static boolean isPlayerInBattle(ServerPlayerEntity player) {
        if (!isBattleCorePresent()) {
            return false;
        }
        try {
            Class<?> battleClass = Class.forName("com.battlecore.battle.Battle");
            Object battle = battleClass.getMethod("getInstance").invoke(null);
            Object state = battleClass.getMethod("getState").invoke(battle);
            
            // Check if battle is active (not INACTIVE)
            if (!state.toString().equals("INACTIVE")) {
                Boolean inBattle = (Boolean) battleClass.getMethod("isPlayerInBattle", java.util.UUID.class)
                    .invoke(battle, player.getUuid());
                return inBattle != null && inBattle;
            }
        } catch (Exception e) {
            // If we can't check, assume not in battle
        }
        return false;
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("safeslot")
                .requires(source -> Permissions.check(source, "safeslot.command.view", 2))
                .then(CommandManager.literal("view")
                    .requires(source -> Permissions.check(source, "safeslot.command.view", 2))
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(InventoryRestoreFeature::viewBackups)
                    )
                )
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(InventoryRestoreFeature::viewBackups)
                )
                .then(CommandManager.literal("save")
                    .requires(source -> Permissions.check(source, "safeslot.command.manualbackup", 2))
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(InventoryRestoreFeature::manualBackup)
                    )
                )
                .then(CommandManager.literal("restore")
                    .requires(source -> Permissions.check(source, "safeslot.command.restore", 2))
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> restoreBackup(ctx, 1))
                        .then(CommandManager.argument("backup", StringArgumentType.word())
                            .executes(ctx -> {
                                String backupStr = StringArgumentType.getString(ctx, "backup");
                                int backupNum = 1;
                                try {
                                    backupNum = Integer.parseInt(backupStr);
                                } catch (NumberFormatException ignored) {}
                                return restoreBackup(ctx, backupNum);
                            })
                        )
                    )
                )
                .then(CommandManager.literal("cleanup")
                    .requires(source -> Permissions.check(source, "safeslot.command.cleanup", 2))
                    .executes(InventoryRestoreFeature::cleanupBackups)
                )
            );
        });
        // Register event listeners for backup triggers
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadBackupsAsync();
            // Start periodic memory cleanup
            startPeriodicCleanup();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveBackupsAsync();
            shutdownExecutor();
        });
        // Only backup on disconnect (leave), not on join or death
        // ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> backupPlayerInventory(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> backupPlayerInventoryAsync(handler.getPlayer()));
        // Player death: fallback to tick-based check for MVP (disabled for now)
        // (Ideally, use a mixin or a custom event for onDeath)
    }

    /**
     * Asynchronous version of backupPlayerInventory for use during disconnect events
     */
    private static void backupPlayerInventoryAsync(ServerPlayerEntity player) {
        // Don't backup if player is in BattleCore battle - let BattleCore handle inventory
        if (isPlayerInBattle(player)) {
            return;
        }
        
        // Create backup data on main thread first (since we need access to player data)
        final UUID uuid = player.getUuid();
        final NbtCompound backupData = createBackupData(player);
        
        // Process the backup asynchronously to avoid blocking server thread
        CompletableFuture<Void> saveTask = CompletableFuture.runAsync(() -> {
            try {
                processBackupAsync(uuid, backupData);
            } catch (Exception e) {
                // Log error but don't crash server
                System.err.println("[Safeslot] Error saving backup for player " + uuid + ": " + e.getMessage());
            }
        }, BACKUP_EXECUTOR);
        
        // Track pending save to avoid duplicate operations
        pendingSaves.put(uuid, saveTask);
        
        // Clean up completed saves after a timeout
        saveTask.whenComplete((result, throwable) -> {
            pendingSaves.remove(uuid);
        });
    }
    
    /**
     * Synchronous version for manual backups and commands
     */
    private static void backupPlayerInventory(ServerPlayerEntity player) {
        // Don't backup if player is in BattleCore battle - let BattleCore handle inventory
        if (isPlayerInBattle(player)) {
            return;
        }
        
        final UUID uuid = player.getUuid();
        final NbtCompound backupData = createBackupData(player);
        processBackupSync(uuid, backupData);
    }
    
    /**
     * Creates backup data from player on main thread (must access player data on main thread)
     */
    private static NbtCompound createBackupData(ServerPlayerEntity player) {
        NbtCompound backup = new NbtCompound();
        RegistryWrapper.WrapperLookup registryManager = ((ServerPlayerEntityAccessor)player).getServer().getRegistryManager();
        
        // Store inventory size for validation during restore
        int inventorySize = player.getInventory().size();
        backup.putInt("inventorySize", inventorySize);
        
        // Backup ALL slots including empty ones to preserve slot positioning
        NbtList items = new NbtList();
        int itemCount = 0;
        
        for (int i = 0; i < inventorySize; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            NbtCompound itemNbt = new NbtCompound();
            itemNbt.putInt("Slot", i);
            
            // Store item data even for empty slots to maintain slot integrity
            if (!stack.isEmpty()) {
                NbtCompound stackNbt = NbtCompatHelper.itemStackToNbt(stack, registryManager);
                itemNbt.put("ItemData", stackNbt);
                itemNbt.putBoolean("HasItem", true);
                itemCount++;
            } else {
                itemNbt.putBoolean("HasItem", false);
            }
            items.add(itemNbt);
        }
        
        backup.put("items", items);
        backup.putLong("timestamp", System.currentTimeMillis());
        
        // NEMO'S BACKPACKS: Save the backpack separately if present
        try {
            Class<?> backpackGetterClass = Class.forName("com.nemonotfound.nemos.backpacks.helper.BackpackGetter");
            if (backpackGetterClass.isInstance(player.getInventory())) {
                Object backpackGetter = player.getInventory();
                java.lang.reflect.Method getBackpackMethod = backpackGetterClass.getMethod("nemosBackpacks$getBackpack");
                ItemStack backpack = (ItemStack) getBackpackMethod.invoke(backpackGetter);
                
                if (backpack != null && !backpack.isEmpty()) {
                    NbtCompound backpackNbt = NbtCompatHelper.itemStackToNbt(backpack, registryManager);
                    backup.put("nemosBackpack", backpackNbt);
                }
            }
        } catch (Exception e) {
            // Nemo's Backpacks not installed or error accessing backpack
        }
        
        // Trinket support: save trinket slots if Trinkets mod is present (reflection)
        NbtCompound trinkets = new NbtCompound();
        try {
            Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            java.lang.reflect.Method getTrinketComponent = trinketsApi.getMethod("getTrinketComponent", ServerPlayerEntity.class);
            java.util.Optional<?> comp = (java.util.Optional<?>) getTrinketComponent.invoke(null, player);
            if (comp.isPresent()) {
                Object trinketComponent = comp.get();
                java.lang.reflect.Method writeToNbt = trinketComponent.getClass().getMethod("writeToNbt", NbtCompound.class);
                writeToNbt.invoke(trinketComponent, trinkets);
            }
        } catch (Throwable ignored) {}
        backup.put("trinkets", trinkets);
        backup.putLong("timestamp", System.currentTimeMillis());
        
        return backup;
    }
    
    /**
     * Process backup data synchronously (for commands)
     */
    private static void processBackupSync(UUID uuid, NbtCompound backup) {
        backupLock.writeLock().lock();
        try {
            playerBackups.computeIfAbsent(uuid, k -> new LinkedList<>());
            List<NbtCompound> backups = playerBackups.get(uuid);
            backups.add(0, backup);
            while (backups.size() > MAX_BACKUPS) backups.remove(backups.size() - 1);
            
            // Save immediately for sync operations
            savePlayerBackups(uuid, backups);
        } finally {
            backupLock.writeLock().unlock();
        }
    }
    
    /**
     * Process backup data asynchronously (for disconnect events) with batching optimization
     */
    private static void processBackupAsync(UUID uuid, NbtCompound backup) {
        backupLock.writeLock().lock();
        try {
            playerBackups.computeIfAbsent(uuid, k -> new LinkedList<>());
            List<NbtCompound> backups = playerBackups.get(uuid);
            backups.add(0, backup);
            while (backups.size() > MAX_BACKUPS) backups.remove(backups.size() - 1);
            
            // Memory optimization: if we have too many in memory, save to disk and keep only recent ones
            if (backups.size() > MAX_MEMORY_BACKUPS) {
                savePlayerBackups(uuid, new ArrayList<>(backups));
                // Keep only the most recent backups in memory
                while (backups.size() > MAX_MEMORY_BACKUPS / 2) {
                    backups.remove(backups.size() - 1);
                }
            }
            
            // Schedule batched write to disk (don't write immediately for every backup)
            scheduleBatchedWrite(uuid, backups);
        } finally {
            backupLock.writeLock().unlock();
        }
    }
    
    /**
     * Schedule a batched write operation to reduce I/O stress
     */
    private static void scheduleBatchedWrite(UUID uuid, List<NbtCompound> backups) {
        long currentTime = System.currentTimeMillis();
        Long lastWriteTime = pendingBackupWrites.get(uuid);
        
        // If no recent write scheduled, schedule one
        if (lastWriteTime == null || currentTime - lastWriteTime > BATCH_DELAY_MS) {
            pendingBackupWrites.put(uuid, currentTime);
            
            CompletableFuture.runAsync(() -> {
                try {
                    // Wait for batching delay
                    Thread.sleep(BATCH_DELAY_MS);
                    
                    // Get current state and save
                    backupLock.readLock().lock();
                    List<NbtCompound> currentBackups;
                    try {
                        currentBackups = new ArrayList<>(playerBackups.getOrDefault(uuid, new ArrayList<>()));
                    } finally {
                        backupLock.readLock().unlock();
                    }
                    
                    if (!currentBackups.isEmpty()) {
                        savePlayerBackups(uuid, currentBackups);
                    }
                    pendingBackupWrites.remove(uuid);
                } catch (Exception e) {
                    pendingBackupWrites.remove(uuid);
                    System.err.println("[Safeslot] Error in batched write for player " + uuid + ": " + e.getMessage());
                }
            }, BACKUP_EXECUTOR);
        }
    }

    /**
     * Load backups asynchronously during server startup
     */
    private static void loadBackupsAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
                
                backupLock.writeLock().lock();
                try {
                    playerBackups.clear();
                } finally {
                    backupLock.writeLock().unlock();
                }
                
                Files.list(BACKUP_DIR).filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                    try {
                        String json = Files.readString(path, StandardCharsets.UTF_8);
                        List<NbtCompound> backups = NbtBackupUtil.deserializeBackups(json);
                        String fileName = path.getFileName().toString();
                        String uuidStr = fileName.substring(0, fileName.length() - 5); // remove .json
                        UUID uuid = UUID.fromString(uuidStr);
                        
                        backupLock.writeLock().lock();
                        try {
                            playerBackups.put(uuid, backups);
                        } finally {
                            backupLock.writeLock().unlock();
                        }
                    } catch (Exception e) {
                        // Skip corrupted backup file
                        System.err.println("[Safeslot] Error loading backup file " + path + ": " + e.getMessage());
                    }
                });
                System.out.println("[Safeslot] Loaded backups for " + playerBackups.size() + " players");
            } catch (Exception e) {
                System.err.println("[Safeslot] Error loading backups: " + e.getMessage());
            }
        }, BACKUP_EXECUTOR);
    }
    
    /**
     * Synchronous backup loading for compatibility
     */
    private static void loadBackups() {
        try {
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            playerBackups.clear();
            
            Files.list(BACKUP_DIR).filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    String json = Files.readString(path, StandardCharsets.UTF_8);
                    List<NbtCompound> backups = NbtBackupUtil.deserializeBackups(json);
                    String fileName = path.getFileName().toString();
                    String uuidStr = fileName.substring(0, fileName.length() - 5); // remove .json
                    UUID uuid = UUID.fromString(uuidStr);
                    playerBackups.put(uuid, backups);
                } catch (Exception e) {
                    // Skip corrupted backup file
                }
            });
        } catch (Exception e) {
            // Return empty list on error
        }
    }

    /**
     * Save all backups asynchronously during server shutdown
     */
    private static void saveBackupsAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
                
                backupLock.readLock().lock();
                Map<UUID, List<NbtCompound>> backupSnapshot;
                try {
                    backupSnapshot = new HashMap<>(playerBackups);
                } finally {
                    backupLock.readLock().unlock();
                }
                
                for (Map.Entry<UUID, List<NbtCompound>> entry : backupSnapshot.entrySet()) {
                    savePlayerBackups(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                System.err.println("[Safeslot] Error saving backups during shutdown: " + e.getMessage());
            }
        }, BACKUP_EXECUTOR);
    }
    
    /**
     * Synchronous backup saving for compatibility
     */
    private static void saveBackups() {
        try {
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            
            for (Map.Entry<UUID, List<NbtCompound>> entry : playerBackups.entrySet()) {
                savePlayerBackups(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            // Ignore save failure
        }
    }

    private static void savePlayerBackups(UUID uuid, List<NbtCompound> backups) {
        try {
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            
            // Optimize memory: only save what's necessary and compress large backups
            List<NbtCompound> optimizedBackups = new ArrayList<>();
            for (NbtCompound backup : backups) {
                if (backup != null && !backup.isEmpty()) {
                    optimizedBackups.add(backup);
                }
            }
            
            String json = NbtBackupUtil.serializeBackups(optimizedBackups);
            Path file = BACKUP_DIR.resolve(uuid.toString() + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Don't re-throw the exception, just log it to prevent breaking other saves
            System.err.println("[Safeslot] Failed to save backups for " + uuid + ": " + e.getMessage());
        }
    }

    private static int viewBackups(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            context.getSource().sendError(Text.literal("[Safeslot] Player not found or not online."));
            return 0;
        }
        UUID uuid = player.getUuid();
        
        backupLock.readLock().lock();
        List<NbtCompound> backups;
        try {
            backups = playerBackups.get(uuid);
        } finally {
            backupLock.readLock().unlock();
        }
        if (backups == null || backups.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[Safeslot] No backups found for " + playerName), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Backups for " + playerName + ":"), false);
        int idx = 1;
        for (NbtCompound backup : backups) {
            long ts = backup.getLong("timestamp").orElse(0L);
            boolean isLegacy = !backup.contains("inventorySize");
            int inventorySize = backup.contains("inventorySize") ? backup.getInt("inventorySize").orElse(-1) : -1;
            boolean hasNemosBackpack = backup.contains("nemosBackpack");
            boolean hasTrinkets = backup.contains("trinkets") && !backup.getCompound("trinkets").isEmpty();
            
            final int displayIdx = idx;
            StringBuilder backupInfo = new StringBuilder();
            backupInfo.append("  [").append(displayIdx).append("] ").append(new Date(ts));
            
            if (isLegacy) {
                backupInfo.append(" (Legacy)");
            } else {
                backupInfo.append(" (Enhanced - ").append(inventorySize).append(" slots)");
            }
            
            if (hasNemosBackpack) {
                backupInfo.append(" [Backpack]");
            }
            
            if (hasTrinkets) {
                backupInfo.append(" [Trinkets]");
            }
            
            context.getSource().sendFeedback(() -> Text.literal(backupInfo.toString()), false);
            idx++;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int manualBackup(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            context.getSource().sendError(Text.literal("[Safeslot] Player not found or not online."));
            return 0;
        }
        backupPlayerInventory(player);
        context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Manual backup created for " + playerName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int restoreBackup(CommandContext<ServerCommandSource> context, int backupNum) {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            context.getSource().sendError(Text.literal("[Safeslot] Player not found or not online."));
            return 0;
        }
        UUID uuid = player.getUuid();
        
        backupLock.readLock().lock();
        List<NbtCompound> backups;
        try {
            backups = playerBackups.get(uuid);
        } finally {
            backupLock.readLock().unlock();
        }
        
        if (backups == null || backups.isEmpty()) {
            context.getSource().sendError(Text.literal("[Safeslot] No backups found for " + playerName));
            return 0;
        }
        if (backupNum < 1 || backupNum > backups.size()) {
            context.getSource().sendError(Text.literal("[Safeslot] Invalid backup number. Use /safeslot inventoryrestore view <player> to see available backups."));
            return 0;
        }
        NbtCompound backup = backups.get(backupNum - 1);
        RegistryWrapper.WrapperLookup registryManager = ((ServerPlayerEntityAccessor)player).getServer().getRegistryManager();
        
        // Check if this is a legacy backup format or enhanced format
        boolean isLegacyFormat = !backup.contains("inventorySize");
        
        if (isLegacyFormat) {
            // Handle legacy backup format for backwards compatibility
            NbtList items = backup.getList("items").orElse(new NbtList());
            player.getInventory().clear();
            
            for (int i = 0; i < items.size(); i++) {
                NbtCompound itemNbt = items.getCompound(i).orElse(new NbtCompound());
                int slot = itemNbt.getInt("Slot").orElse(0);
                
                // In legacy format, the ItemStack NBT is stored directly in the compound (minus the "Slot" field)
                // Create a clean copy without the "Slot" field for ItemStack deserialization
                NbtCompound cleanItemNbt = itemNbt.copy();
                cleanItemNbt.remove("Slot");
                
                if (!cleanItemNbt.isEmpty()) {
                    ItemStack stack = NbtCompatHelper.itemStackFromNbt(cleanItemNbt, registryManager);
                    if (slot >= 0 && slot <= 46) {
                        try {
                            player.getInventory().setStack(slot, stack);
                        } catch (IndexOutOfBoundsException e) {
                            // If slot doesn't exist, try to place in main inventory
                            for (int fallbackSlot = 0; fallbackSlot < 36; fallbackSlot++) {
                                if (player.getInventory().getStack(fallbackSlot).isEmpty()) {
                                    player.getInventory().setStack(fallbackSlot, stack);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Restored legacy backup #" + backupNum + " for " + playerName), false);
        } else {
            // Handle enhanced backup format with full slot preservation
            int savedInventorySize = backup.getInt("inventorySize").orElse(player.getInventory().size());
            int currentInventorySize = player.getInventory().size();
            NbtList items = backup.getList("items").orElse(new NbtList());
            
            // Clear current inventory
            player.getInventory().clear();
            
            // Restore items with expanded slot validation
            for (int i = 0; i < items.size(); i++) {
                NbtCompound itemNbt = items.getCompound(i).orElse(new NbtCompound());
                int slot = itemNbt.getInt("Slot").orElse(0);
                boolean hasItem = itemNbt.getBoolean("HasItem").orElse(false);
                
                // Allow all reasonable slots including armor (36-39), offhand (40), and modded slots (up to 46 for Nemo's backpack)
                if (slot >= 0 && slot <= 46) {
                    if (hasItem && itemNbt.contains("ItemData")) {
                        NbtCompound stackNbt = itemNbt.getCompound("ItemData").orElse(new NbtCompound());
                        ItemStack stack = NbtCompatHelper.itemStackFromNbt(stackNbt, registryManager);
                        if (!stack.isEmpty()) {
                            try {
                                player.getInventory().setStack(slot, stack);
                            } catch (IndexOutOfBoundsException e) {
                                // If slot doesn't exist in this inventory, try to place in main inventory
                                for (int fallbackSlot = 0; fallbackSlot < 36; fallbackSlot++) {
                                    if (player.getInventory().getStack(fallbackSlot).isEmpty()) {
                                        player.getInventory().setStack(fallbackSlot, stack);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    // Empty slots are automatically handled by clear() above
                }
            }
            
            // NEMO'S BACKPACKS: Restore the backpack if present in backup
            if (backup.contains("nemosBackpack")) {
                try {
                    NbtCompound backpackNbt = backup.getCompound("nemosBackpack").orElse(new NbtCompound());
                    ItemStack backpack = NbtCompatHelper.itemStackFromNbt(backpackNbt, registryManager);
                    
                    if (!backpack.isEmpty()) {
                        // Use the same approach as FleetTools - setStack to slot 46 should trigger Nemo's mixin
                        try {
                            player.getInventory().setStack(46, backpack);
                        } catch (Exception slotEx) {
                            // Fallback: Try to give it to the player in regular inventory
                            boolean placed = false;
                            for (int i = 0; i < player.getInventory().size(); i++) {
                                if (player.getInventory().getStack(i).isEmpty()) {
                                    player.getInventory().setStack(i, backpack);
                                    placed = true;
                                    context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Nemo's Backpack restored to inventory - please equip it manually"), false);
                                    break;
                                }
                            }
                            
                            if (!placed) {
                                // Just log that we couldn't restore it - avoid the complexity of dropping items
                                context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Could not restore Nemo's Backpack - inventory was full"), false);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore Nemo's Backpack errors
                }
            }
            
            context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Restored enhanced backup #" + backupNum + " for " + playerName), false);
        }
        
        // Trinket support: restore trinket slots if Trinkets mod is present (reflection)
        NbtCompound trinkets = backup.getCompound("trinkets").orElse(new NbtCompound());
        if (trinkets != null && !trinkets.isEmpty()) {
            try {
                Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
                java.lang.reflect.Method getTrinketComponent = trinketsApi.getMethod("getTrinketComponent", ServerPlayerEntity.class);
                java.util.Optional<?> comp = (java.util.Optional<?>) getTrinketComponent.invoke(null, player);
                if (comp.isPresent()) {
                    Object trinketComponent = comp.get();
                    java.lang.reflect.Method readFromNbt = trinketComponent.getClass().getMethod("readFromNbt", NbtCompound.class);
                    readFromNbt.invoke(trinketComponent, trinkets);
                    context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Also restored trinket data for " + playerName), false);
                }
            } catch (Throwable ignored) {}
        }
        
        // Force inventory refresh to ensure client synchronization
        player.currentScreenHandler.sendContentUpdates();
        player.playerScreenHandler.onContentChanged(player.getInventory());
        player.sendAbilitiesUpdate();
        
        return Command.SINGLE_SUCCESS;
    }

    private static int cleanupBackups(CommandContext<ServerCommandSource> context) {
        // Run cleanup asynchronously to avoid blocking server thread
        CompletableFuture.runAsync(() -> {
            try {
                int totalPlayers = 0;
                int totalDeleted = 0;
                int keepCount = 3;
                
                backupLock.writeLock().lock();
                try {
                    for (Map.Entry<UUID, List<NbtCompound>> entry : playerBackups.entrySet()) {
                        List<NbtCompound> backups = entry.getValue();
                        if (backups.size() > keepCount) {
                            int toDelete = backups.size() - keepCount;
                            // Keep only the most recent backups
                            List<NbtCompound> mostRecent = new ArrayList<>(backups.subList(0, keepCount));
                            backups.clear();
                            backups.addAll(mostRecent);
                            savePlayerBackups(entry.getKey(), backups);
                            totalDeleted += toDelete;
                            totalPlayers++;
                        }
                    }
                } finally {
                    backupLock.writeLock().unlock();
                }
                
                String msg = "[Safeslot] Cleanup complete: " + totalDeleted + " old backups removed for " + totalPlayers + " player(s).";
                context.getSource().sendFeedback(() -> Text.literal(msg), false);
            } catch (Exception e) {
                context.getSource().sendError(Text.literal("[Safeslot] Error during cleanup: " + e.getMessage()));
            }
        }, BACKUP_EXECUTOR);
        
        context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Cleanup started in background..."), false);
        return Command.SINGLE_SUCCESS;
    }
    
    /**
     * Start periodic cleanup to manage memory usage
     */
    private static void startPeriodicCleanup() {
        CompletableFuture.runAsync(() -> {
            try {
                while (!BACKUP_EXECUTOR.isShutdown()) {
                    Thread.sleep(300000); // 5 minutes
                    performMemoryCleanup();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, BACKUP_EXECUTOR);
    }
    
    /**
     * Perform memory cleanup by removing old backups from memory
     */
    private static void performMemoryCleanup() {
        backupLock.writeLock().lock();
        try {
            for (Map.Entry<UUID, List<NbtCompound>> entry : playerBackups.entrySet()) {
                List<NbtCompound> backups = entry.getValue();
                if (backups.size() > MAX_MEMORY_BACKUPS) {
                    // Save all backups to disk first
                    savePlayerBackups(entry.getKey(), new ArrayList<>(backups));
                    
                    // Keep only recent ones in memory
                    List<NbtCompound> recentBackups = new ArrayList<>(
                        backups.subList(0, Math.min(MAX_MEMORY_BACKUPS / 2, backups.size()))
                    );
                    backups.clear();
                    backups.addAll(recentBackups);
                }
            }
        } finally {
            backupLock.writeLock().unlock();
        }
    }
    
    /**
     * Gracefully shutdown the executor service
     */
    private static void shutdownExecutor() {
        try {
            // Wait for pending backup operations to complete
            for (CompletableFuture<Void> pending : pendingSaves.values()) {
                pending.join();
            }
            pendingSaves.clear();
            
            BACKUP_EXECUTOR.shutdown();
            if (!BACKUP_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("[Safeslot] Backup executor did not shut down gracefully, forcing shutdown");
                BACKUP_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            BACKUP_EXECUTOR.shutdownNow();
        }
    }
}
