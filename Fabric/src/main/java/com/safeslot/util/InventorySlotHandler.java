package com.safeslot.util;

import com.safeslot.SafeslotMod;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Comprehensive inventory slot handling for all types of inventory slots
 * Includes main inventory, armor, offhand, and modded slots
 */
public class InventorySlotHandler {
    
    // Standard Minecraft inventory slots
    public static final int MAIN_INVENTORY_START = 0;
    public static final int MAIN_INVENTORY_END = 35;
    public static final int ARMOR_SLOT_START = 36;
    public static final int ARMOR_SLOT_END = 39;
    public static final int OFFHAND_SLOT = 40;
    public static final int CRAFTING_SLOTS_START = 41;
    public static final int CRAFTING_SLOTS_END = 44;
    public static final int CRAFTING_RESULT_SLOT = 45;
    
    // Extended slots for mods
    public static final int NEMOS_BACKPACK_SLOT = 46;
    public static final int MAX_SUPPORTED_SLOTS = 64; // Allow for future expansion
    
    /**
     * Create a complete backup of all inventory slots with full NBT preservation
     */
    public static NbtCompound createInventoryBackup(ServerPlayerEntity player, RegistryWrapper.WrapperLookup registries) {
        NbtCompound backup = new NbtCompound();
        PlayerInventory inventory = player.getInventory();
        
        // Store metadata
        backup.putLong("timestamp", System.currentTimeMillis());
        backup.putString("playerName", player.getGameProfile().name());
        backup.putString("playerUuid", player.getUuid().toString());
        backup.putInt("actualInventorySize", inventory.size());
        
        // Store all inventory slots
        NbtList inventorySlots = new NbtList();
        int actualSize = inventory.size();
        int totalItemsBackedUp = 0;
        
        // Backup all slots in the inventory, excluding slot 46 (Nemo's backpack handled separately)
        for (int slot = 0; slot < Math.min(MAX_SUPPORTED_SLOTS, actualSize); slot++) {
            // Skip slot 46 (Nemo's backpack) - handled by separate integration
            if (slot == NEMOS_BACKPACK_SLOT) {
                continue;
            }
            NbtCompound slotData = new NbtCompound();
            slotData.putInt("slot", slot);
            
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                NbtCompound itemNbt = NbtUtil.itemStackToNbt(stack, registries);
                if (itemNbt != null && !itemNbt.isEmpty()) {
                    slotData.put("itemData", itemNbt);
                    slotData.putBoolean("hasItem", true);
                    totalItemsBackedUp++;
                } else {
                    slotData.putBoolean("hasItem", false);
                }
            } else {
                slotData.putBoolean("hasItem", false);
            }
            
            // Store slot metadata for debugging
            slotData.putString("slotType", getSlotTypeName(slot));
            inventorySlots.add(slotData);
        }
        
        backup.put("inventorySlots", inventorySlots);
        backup.putInt("totalItemsBackedUp", totalItemsBackedUp);
        backup.putInt("slotsProcessed", inventorySlots.size());
        
        SafeslotMod.LOGGER.debug("Created inventory backup for {} with {} items across {} slots", 
            player.getGameProfile().name(), totalItemsBackedUp, inventorySlots.size());
        
        return backup;
    }
    
    /**
     * Restore a complete inventory backup with full slot position preservation
     */
    public static boolean restoreInventoryBackup(ServerPlayerEntity player, NbtCompound backup, RegistryWrapper.WrapperLookup registries) {
        if (backup == null || backup.isEmpty()) {
            SafeslotMod.LOGGER.warn("Cannot restore: backup data is null or empty");
            return false;
        }
        
        PlayerInventory inventory = player.getInventory();
        
        try {
            // Clear the entire inventory first
            inventory.clear();
            
            NbtList inventorySlots = NbtUtil.getList(backup, "inventorySlots");
            int restoredItems = 0;
            int failedRestorations = 0;
            
            for (int i = 0; i < inventorySlots.size(); i++) {
                NbtCompound slotData = NbtUtil.getCompound(inventorySlots, i);
                int slot = NbtUtil.getInt(slotData, "slot");
                boolean hasItem = NbtUtil.getBoolean(slotData, "hasItem");
                
                // Skip slot 46 (Nemo's backpack) - handled by separate integration
                if (slot == NEMOS_BACKPACK_SLOT) {
                    continue;
                }
                
                if (hasItem && slotData.contains("itemData")) {
                    NbtCompound itemData = NbtUtil.getCompound(slotData, "itemData");
                    ItemStack restoredStack = NbtUtil.itemStackFromNbt(itemData, registries);
                    
                    if (!restoredStack.isEmpty()) {
                        if (setInventorySlotSafely(inventory, slot, restoredStack)) {
                            restoredItems++;
                        } else {
                            // Try to place in any available slot if the original slot doesn't work
                            if (giveItemToPlayer(inventory, restoredStack)) {
                                restoredItems++;
                                SafeslotMod.LOGGER.debug("Restored item to alternate slot (original slot {} was invalid)", slot);
                            } else {
                                failedRestorations++;
                                SafeslotMod.LOGGER.warn("Failed to restore item from slot {}: {}", slot, restoredStack);
                            }
                        }
                    } else {
                        failedRestorations++;
                        SafeslotMod.LOGGER.warn("Failed to deserialize item from slot {}", slot);
                    }
                }
            }
            
            // Force inventory synchronization
            inventory.markDirty();
            player.currentScreenHandler.sendContentUpdates();
            player.playerScreenHandler.onContentChanged(inventory);
            
            long backupTime = NbtUtil.getLong(backup, "timestamp");
            SafeslotMod.LOGGER.info("Restored {} items for {} (backup from {}), {} failures", 
                restoredItems, player.getGameProfile().name(), 
                new java.util.Date(backupTime), failedRestorations);
            
            return failedRestorations == 0;
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Error restoring inventory for {}: {}", player.getGameProfile().name(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Safely set an inventory slot, handling potential out-of-bounds issues
     */
    private static boolean setInventorySlotSafely(PlayerInventory inventory, int slot, ItemStack stack) {
        try {
            if (slot >= 0 && slot < inventory.size()) {
                inventory.setStack(slot, stack);
                return true;
            } else {
                SafeslotMod.LOGGER.debug("Slot {} is out of bounds for inventory size {}", slot, inventory.size());
                return false;
            }
        } catch (Exception e) {
            SafeslotMod.LOGGER.debug("Failed to set slot {}: {}", slot, e.getMessage());
            return false;
        }
    }
    
    /**
     * Give an item to a player by finding any available slot
     */
    private static boolean giveItemToPlayer(PlayerInventory inventory, ItemStack stack) {
        // Try main inventory first
        for (int i = MAIN_INVENTORY_START; i <= MAIN_INVENTORY_END && i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack);
                return true;
            }
        }
        
        // Try other available slots
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack);
                return true;
            }
        }
        
        return false; // Inventory is full
    }
    
    /**
     * Get a human-readable name for a slot type based on its index
     */
    public static String getSlotTypeName(int slot) {
        if (slot >= MAIN_INVENTORY_START && slot <= MAIN_INVENTORY_END) {
            return "main_inventory";
        } else if (slot >= ARMOR_SLOT_START && slot <= ARMOR_SLOT_END) {
            switch (slot) {
                case 36: return "armor_boots";
                case 37: return "armor_leggings";
                case 38: return "armor_chestplate";
                case 39: return "armor_helmet";
                default: return "armor";
            }
        } else if (slot == OFFHAND_SLOT) {
            return "offhand";
        } else if (slot >= CRAFTING_SLOTS_START && slot <= CRAFTING_SLOTS_END) {
            return "crafting";
        } else if (slot == CRAFTING_RESULT_SLOT) {
            return "crafting_result";
        } else if (slot == NEMOS_BACKPACK_SLOT) {
            return "nemos_backpack";
        } else {
            return "modded_slot_" + slot;
        }
    }
    
    /**
     * Validate a backup to ensure it's complete and not corrupted
     */
    public static boolean validateBackup(NbtCompound backup) {
        if (backup == null || backup.isEmpty()) {
            return false;
        }
        
        // Check required fields
        if (!backup.contains("timestamp") || !backup.contains("inventorySlots")) {
            return false;
        }
        
        // Check timestamp validity
        long timestamp = NbtUtil.getLong(backup, "timestamp");
        if (timestamp <= 0 || timestamp > System.currentTimeMillis()) {
            return false;
        }
        
        // Check inventory slots structure
        NbtList inventorySlots = NbtUtil.getList(backup, "inventorySlots");
        if (inventorySlots.isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Get backup statistics for debugging and display
     */
    public static String getBackupStats(NbtCompound backup) {
        if (!validateBackup(backup)) {
            return "Invalid backup";
        }
        
        long timestamp = NbtUtil.getLong(backup, "timestamp");
        int totalItems = NbtUtil.getInt(backup, "totalItemsBackedUp");
        int slotsProcessed = NbtUtil.getInt(backup, "slotsProcessed");
        String playerName = NbtUtil.getString(backup, "playerName");
        
        return String.format("%s - %d items in %d slots (backup from %s)", 
            playerName, totalItems, slotsProcessed, new java.util.Date(timestamp));
    }
}
