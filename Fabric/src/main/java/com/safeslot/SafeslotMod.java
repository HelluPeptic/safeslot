package com.safeslot;

import com.safeslot.features.inventoryrestore.InventoryRestoreFeature;

import net.fabricmc.api.ModInitializer;

public class SafeslotMod implements ModInitializer {
    @Override
    public void onInitialize() {
        InventoryRestoreFeature.register();
    }
}
