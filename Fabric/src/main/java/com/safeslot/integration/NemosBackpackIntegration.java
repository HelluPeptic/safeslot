package com.safeslot.integration;

import com.safeslot.SafeslotMod;
import com.safeslot.util.NbtUtil;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Robust integration with Nemo's Backpacks mod
 * Provides comprehensive backpack backup and restoration functionality
 */
public class NemosBackpackIntegration {
    
    private static Boolean isNemosBackpacksPresent = null;
    private static boolean integrationInitialized = false;
    
    // Reflection cache for performance
    private static Class<?> backpackGetterClass = null;
    private static java.lang.reflect.Method getBackpackMethod = null;
    
    /**
     * Check if Nemo's Backpacks mod is available and initialize integration
     */
    public static boolean isNemosBackpacksAvailable() {
        if (isNemosBackpacksPresent != null) {
            return isNemosBackpacksPresent;
        }
        
        try {
            // Check for the main backpack getter interface
            backpackGetterClass = Class.forName("com.nemonotfound.nemos.backpacks.helper.BackpackGetter");
            
            // Cache reflection methods for performance
            getBackpackMethod = backpackGetterClass.getMethod("nemosBackpacks$getBackpack");
            
            isNemosBackpacksPresent = true;
            SafeslotMod.LOGGER.info("Nemo's Backpacks integration enabled");
            
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            isNemosBackpacksPresent = false;
            SafeslotMod.LOGGER.debug("Nemo's Backpacks not detected - integration disabled");
        }
        
        integrationInitialized = true;
        return isNemosBackpacksPresent;
    }
    
    /**
     * Backup the player's Nemo's backpack with full NBT preservation
     */
    public static NbtCompound backupNemosBackpack(ServerPlayerEntity player, RegistryWrapper.WrapperLookup registries) {
        NbtCompound backpackBackup = new NbtCompound();
        
        if (!isNemosBackpacksAvailable()) {
            backpackBackup.putBoolean("nemosBackpackPresent", false);
            return backpackBackup;
        }
        
        try {
            // Check if the player's inventory implements the BackpackGetter interface
            if (backpackGetterClass.isInstance(player.getInventory())) {
                Object backpackGetter = player.getInventory();
                ItemStack backpack = (ItemStack) getBackpackMethod.invoke(backpackGetter);
                
                if (backpack != null && !backpack.isEmpty()) {
                    // Store the complete backpack with all its NBT data
                    NbtCompound backpackNbt = NbtUtil.itemStackToNbt(backpack, registries);
                    
                    if (backpackNbt != null && !backpackNbt.isEmpty()) {
                        backpackBackup.putBoolean("nemosBackpackPresent", true);
                        backpackBackup.put("backpackData", backpackNbt);
                        
                        // Store additional metadata for verification
                        backpackBackup.putLong("backupTimestamp", System.currentTimeMillis());
                        backpackBackup.putString("playerUuid", player.getUuid().toString());
                        
                        // Estimate backup size for logging
                        int estimatedSize = NbtUtil.estimateNbtSize(backpackNbt);
                        backpackBackup.putInt("estimatedSize", estimatedSize);
                        
                        SafeslotMod.LOGGER.debug("Backed up Nemo's backpack for {} (size: ~{} bytes)", 
                            player.getGameProfile().name(), estimatedSize);
                        
                        return backpackBackup;
                    } else {
                        SafeslotMod.LOGGER.warn("Failed to serialize Nemo's backpack NBT for {}", player.getGameProfile().name());
                    }
                } else {
                    SafeslotMod.LOGGER.debug("Player {} has no Nemo's backpack equipped", player.getGameProfile().name());
                }
            } else {
                SafeslotMod.LOGGER.debug("Player {} inventory does not support Nemo's backpacks", player.getGameProfile().name());
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Error backing up Nemo's backpack for {}: {}", 
                player.getGameProfile().name(), e.getMessage(), e);
        }
        
        backpackBackup.putBoolean("nemosBackpackPresent", false);
        return backpackBackup;
    }
    
    /**
     * Restore the player's Nemo's backpack from backup
     */
    public static boolean restoreNemosBackpack(ServerPlayerEntity player, NbtCompound backpackBackup, RegistryWrapper.WrapperLookup registries) {
        if (backpackBackup == null || backpackBackup.isEmpty()) {
            return true; // No backpack to restore is considered success
        }
        
        boolean hasBackpack = NbtUtil.getBoolean(backpackBackup, "nemosBackpackPresent");
        if (!hasBackpack) {
            SafeslotMod.LOGGER.debug("No Nemo's backpack in backup for {}", player.getGameProfile().name());
            return true; // No backpack to restore
        }
        
        if (!isNemosBackpacksAvailable()) {
            SafeslotMod.LOGGER.warn("Cannot restore Nemo's backpack - mod not available");
            return false;
        }
        
        try {
            NbtCompound backpackData = NbtUtil.getCompound(backpackBackup, "backpackData");
            if (backpackData.isEmpty()) {
                SafeslotMod.LOGGER.warn("Empty backpack data in backup for {}", player.getGameProfile().name());
                return false;
            }
            
            // Restore the backpack item with full NBT data
            ItemStack restoredBackpack = NbtUtil.itemStackFromNbt(backpackData, registries);
            
            if (restoredBackpack.isEmpty()) {
                SafeslotMod.LOGGER.error("Failed to restore Nemo's backpack from NBT for {}", player.getGameProfile().name());
                return false;
            }
            
            // Restore backpack to slot 46 - this is where Nemo's backpack is stored
            try {
                player.getInventory().setStack(46, restoredBackpack);
                SafeslotMod.LOGGER.info("Successfully restored Nemo's backpack for {} to slot 46", player.getGameProfile().name());
                return true;
                
            } catch (Exception slotException) {
                SafeslotMod.LOGGER.error("Failed to place Nemo's backpack in slot 46 for {}: {}", 
                    player.getGameProfile().name(), slotException.getMessage());
                return false;
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Error restoring Nemo's backpack for {}: {}", 
                player.getGameProfile().name(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Validate that a backpack backup is valid and complete
     */
    public static boolean isValidBackpackBackup(NbtCompound backpackBackup) {
        if (backpackBackup == null || backpackBackup.isEmpty()) {
            return true; // Empty backup is valid (no backpack)
        }
        
        boolean hasBackpack = NbtUtil.getBoolean(backpackBackup, "nemosBackpackPresent");
        if (!hasBackpack) {
            return true; // No backpack is valid
        }
        
        // Check required fields for backpack backup
        if (!backpackBackup.contains("backpackData") || !backpackBackup.contains("backupTimestamp")) {
            return false;
        }
        
        NbtCompound backpackData = NbtUtil.getCompound(backpackBackup, "backpackData");
        return !backpackData.isEmpty();
    }
    
    /**
     * Get human-readable information about a backpack backup
     */
    public static String getBackpackBackupInfo(NbtCompound backpackBackup) {
        if (backpackBackup == null || backpackBackup.isEmpty()) {
            return "No backpack backup data";
        }
        
        boolean hasBackpack = NbtUtil.getBoolean(backpackBackup, "nemosBackpackPresent");
        if (!hasBackpack) {
            return "No Nemo's backpack in backup";
        }
        
        long timestamp = NbtUtil.getLong(backpackBackup, "backupTimestamp");
        int estimatedSize = NbtUtil.getInt(backpackBackup, "estimatedSize");
        
        return String.format("Nemo's backpack backup (size: ~%d bytes, from: %s)", 
            estimatedSize, new java.util.Date(timestamp));
    }
    
    /**
     * Initialize the integration system
     */
    public static void initialize() {
        if (!integrationInitialized) {
            isNemosBackpacksAvailable(); // This initializes the integration
        }
    }
    
    /**
     * Check if the integration is properly initialized
     */
    public static boolean isIntegrationReady() {
        return integrationInitialized;
    }
}
