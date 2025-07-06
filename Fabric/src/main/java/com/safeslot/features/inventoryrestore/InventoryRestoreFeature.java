package com.safeslot.features.inventoryrestore;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.ArrayList;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

public class InventoryRestoreFeature {
    private static final Map<UUID, List<NbtCompound>> playerBackups = new HashMap<>();
    private static final int MAX_BACKUPS = 20;
    private static final Path BACKUP_DIR = Path.of("config", "Safeslot", "inventorybackups");

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("safeslot")
                .then(CommandManager.literal("inventoryrestore")
                    .requires(source -> Permissions.check(source, "safeslot.command.view") || source.hasPermissionLevel(2))
                    .then(CommandManager.literal("view")
                        .requires(source -> Permissions.check(source, "safeslot.command.view") || source.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(InventoryRestoreFeature::viewBackups)
                        )
                    )
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(InventoryRestoreFeature::viewBackups)
                    )
                    .then(CommandManager.literal("save")
                        .requires(source -> Permissions.check(source, "safeslot.command.manualbackup") || source.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(InventoryRestoreFeature::manualBackup)
                        )
                    )
                    .then(CommandManager.literal("restore")
                        .requires(source -> Permissions.check(source, "safeslot.command.restore") || source.hasPermissionLevel(2))
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
                        .requires(source -> Permissions.check(source, "safeslot.command.cleanup") || source.hasPermissionLevel(2))
                        .executes(InventoryRestoreFeature::cleanupBackups)
                    )
                )
            );
        });
        // Register event listeners for backup triggers
        ServerLifecycleEvents.SERVER_STARTED.register(server -> loadBackups());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveBackups());
        // Only backup on disconnect (leave), not on join or death
        // ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> backupPlayerInventory(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> backupPlayerInventory(handler.getPlayer()));
        // Player death: fallback to tick-based check for MVP (disabled for now)
        // (Ideally, use a mixin or a custom event for onDeath)
    }

    // Trinkets support is now optional and uses reflection to avoid compile errors if the mod is not present.
    private static void backupPlayerInventory(ServerPlayerEntity player) {
        NbtCompound backup = new NbtCompound();
        NbtList items = new NbtList();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                NbtCompound itemNbt = new NbtCompound();
                stack.writeNbt(itemNbt);
                itemNbt.putInt("Slot", i);
                items.add(itemNbt);
            }
        }
        backup.put("items", items);
        backup.putLong("timestamp", System.currentTimeMillis());
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
                // Debug: print trinket NBT to server log
                System.out.println("[Safeslot] Trinket NBT for backup: " + trinkets);
            }
        } catch (Throwable ignored) {}
        backup.put("trinkets", trinkets);
        UUID uuid = player.getUuid();
        playerBackups.computeIfAbsent(uuid, k -> new LinkedList<>());
        List<NbtCompound> backups = playerBackups.get(uuid);
        backups.add(0, backup);
        while (backups.size() > MAX_BACKUPS) backups.remove(backups.size() - 1);
        savePlayerBackups(uuid, backups); // Save after each backup
    }

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
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void saveBackups() {
        try {
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            for (Map.Entry<UUID, List<NbtCompound>> entry : playerBackups.entrySet()) {
                savePlayerBackups(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {}
    }

    private static void savePlayerBackups(UUID uuid, List<NbtCompound> backups) {
        try {
            if (!Files.exists(BACKUP_DIR)) Files.createDirectories(BACKUP_DIR);
            String json = NbtBackupUtil.serializeBackups(backups);
            Path file = BACKUP_DIR.resolve(uuid.toString() + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
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
        List<NbtCompound> backups = playerBackups.get(uuid);
        if (backups == null || backups.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[Safeslot] No backups found for " + playerName), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Backups for " + playerName + ":"), false);
        int idx = 1;
        for (NbtCompound backup : backups) {
            long ts = backup.getLong("timestamp");
            final int displayIdx = idx;
            context.getSource().sendFeedback(() -> Text.literal("  [" + displayIdx + "] " + new Date(ts)), false);
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
        List<NbtCompound> backups = playerBackups.get(uuid);
        if (backups == null || backups.isEmpty()) {
            context.getSource().sendError(Text.literal("[Safeslot] No backups found for " + playerName));
            return 0;
        }
        if (backupNum < 1 || backupNum > backups.size()) {
            context.getSource().sendError(Text.literal("[Safeslot] Invalid backup number. Use /safeslot inventoryrestore view <player> to see available backups."));
            return 0;
        }
        NbtCompound backup = backups.get(backupNum - 1);
        NbtList items = backup.getList("items", 10); // 10 = NbtCompound
        player.getInventory().clear();
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = items.getCompound(i);
            int slot = itemNbt.getInt("Slot");
            ItemStack stack = ItemStack.fromNbt(itemNbt);
            player.getInventory().setStack(slot, stack);
        }
        // Trinket support: restore trinket slots if Trinkets mod is present (reflection)
        NbtCompound trinkets = backup.getCompound("trinkets");
        try {
            Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            java.lang.reflect.Method getTrinketComponent = trinketsApi.getMethod("getTrinketComponent", ServerPlayerEntity.class);
            java.util.Optional<?> comp = (java.util.Optional<?>) getTrinketComponent.invoke(null, player);
            if (comp.isPresent()) {
                Object trinketComponent = comp.get();
                if (!trinkets.isEmpty()) {
                    java.lang.reflect.Method readFromNbt = trinketComponent.getClass().getMethod("readFromNbt", NbtCompound.class);
                    readFromNbt.invoke(trinketComponent, trinkets);
                } else {
                    context.getSource().sendFeedback(() -> Text.literal("[Safeslot] No trinket data found in backup for " + playerName), false);
                }
            }
        } catch (Throwable ignored) {}
        player.currentScreenHandler.sendContentUpdates();
        context.getSource().sendFeedback(() -> Text.literal("[Safeslot] Restored backup #" + backupNum + " for " + playerName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cleanupBackups(CommandContext<ServerCommandSource> context) {
        int totalPlayers = 0;
        int totalDeleted = 0;
        int keepCount = 3;
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
        String msg = "[Safeslot] Cleanup complete: " + totalDeleted + " old backups removed for " + totalPlayers + " player(s).";
        context.getSource().sendFeedback(() -> Text.literal(msg), false);
        return Command.SINGLE_SUCCESS;
    }
}
