package com.safeslot;

import com.safeslot.inventory.InventoryBackupManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafeslotMod implements ModInitializer {
    public static final String MOD_ID = "safeslot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Safeslot - Player Inventory Backup System");
        
        // Initialize the inventory backup manager
        InventoryBackupManager.initialize();
        
        LOGGER.info("Safeslot initialization complete");
    }
}