package com.safeslot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import com.safeslot.features.inventoryrestore.InventoryRestoreFeature;

public class SafeslotMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[SAFESLOT] Initializing Safeslot mod");
        InventoryRestoreFeature.register();
        System.out.println("[SAFESLOT] Safeslot mod initialized");
    }
}
