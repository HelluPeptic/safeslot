package com.safeslot.inventory;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/**
 * Data class representing a complete player inventory backup
 * Contains all necessary information for backup and restoration
 */
public class PlayerBackup {
    
    private final UUID playerUuid;
    private final String playerName;
    private final long timestamp;
    private final NbtCompound inventoryData;
    private final NbtCompound backpackData;
    
    /**
     * Create a new player backup
     * 
     * @param playerUuid The UUID of the player
     * @param playerName The name of the player at backup time
     * @param timestamp The timestamp when the backup was created
     * @param inventoryData The complete inventory data in NBT format
     * @param backpackData The Nemo's backpack data in NBT format (may be empty)
     */
    public PlayerBackup(UUID playerUuid, String playerName, long timestamp, 
                       NbtCompound inventoryData, NbtCompound backpackData) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.timestamp = timestamp;
        this.inventoryData = inventoryData != null ? inventoryData.copy() : new NbtCompound();
        this.backpackData = backpackData != null ? backpackData.copy() : new NbtCompound();
    }
    
    /**
     * Get the player's UUID
     */
    public UUID getPlayerUuid() {
        return playerUuid;
    }
    
    /**
     * Get the player's name at the time of backup
     */
    public String getPlayerName() {
        return playerName;
    }
    
    /**
     * Get the timestamp when this backup was created
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get the complete inventory data (read-only copy)
     */
    public NbtCompound getInventoryData() {
        return inventoryData.copy();
    }
    
    /**
     * Get the backpack data (read-only copy)
     */
    public NbtCompound getBackpackData() {
        return backpackData.copy();
    }
    
    /**
     * Check if this backup contains Nemo's backpack data
     */
    public boolean hasBackpackData() {
        return com.safeslot.util.NbtUtil.getBoolean(backpackData, "nemosBackpackPresent");
    }
    
    /**
     * Get a human-readable summary of this backup
     */
    public String getSummary() {
        int totalItems = com.safeslot.util.NbtUtil.getInt(inventoryData, "totalItemsBackedUp");
        boolean hasBackpack = hasBackpackData();
        
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Backup for %s - %d items", playerName, totalItems));
        
        if (hasBackpack) {
            summary.append(" + backpack");
        }
        
        summary.append(String.format(" (from %s)", new java.util.Date(timestamp)));
        
        return summary.toString();
    }
    
    /**
     * Validate that this backup contains all required data
     */
    public boolean isValid() {
        // Check basic fields
        if (playerUuid == null || playerName == null || playerName.isEmpty()) {
            return false;
        }
        
        if (timestamp <= 0 || timestamp > System.currentTimeMillis()) {
            return false;
        }
        
        // Check inventory data
        if (inventoryData == null || inventoryData.isEmpty()) {
            return false;
        }
        
        if (!inventoryData.contains("inventorySlots") || !inventoryData.contains("timestamp")) {
            return false;
        }
        
        // Backpack data can be empty (no backpack is valid), but if present should be valid
        if (backpackData != null && com.safeslot.util.NbtUtil.getBoolean(backpackData, "nemosBackpackPresent")) {
            if (!backpackData.contains("backpackData")) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Get the estimated size of this backup in bytes
     */
    public int getEstimatedSize() {
        int inventorySize = com.safeslot.util.NbtUtil.estimateNbtSize(inventoryData);
        int backpackSize = com.safeslot.util.NbtUtil.estimateNbtSize(backpackData);
        return inventorySize + backpackSize;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        PlayerBackup that = (PlayerBackup) obj;
        
        return timestamp == that.timestamp &&
               playerUuid.equals(that.playerUuid) &&
               playerName.equals(that.playerName);
    }
    
    @Override
    public int hashCode() {
        return java.util.Objects.hash(playerUuid, timestamp);
    }
    
    @Override
    public String toString() {
        return String.format("PlayerBackup{uuid=%s, name='%s', timestamp=%d, hasBackpack=%b}", 
            playerUuid, playerName, timestamp, hasBackpackData());
    }
}