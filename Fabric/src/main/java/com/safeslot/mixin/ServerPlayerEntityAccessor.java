package com.safeslot.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin to access the server instance from ServerPlayerEntity
 */
@Mixin(ServerPlayerEntity.class)
public interface ServerPlayerEntityAccessor {
    
    @Accessor("server")
    MinecraftServer getServer();
}