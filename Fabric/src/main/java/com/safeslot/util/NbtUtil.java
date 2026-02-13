package com.safeslot.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

import com.safeslot.SafeslotMod;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;

/**
 * Robust NBT utilities for Minecraft 1.21.11 with proper binary serialization
 * Fixes the data loss issues from string-based NBT conversion
 */
public class NbtUtil {
    
    /**
     * Convert ItemStack to NBT using the modern codec system
     */
    public static NbtCompound itemStackToNbt(ItemStack stack, RegistryWrapper.WrapperLookup registries) {
        if (stack == null || stack.isEmpty()) {
            return new NbtCompound();
        }
        
        try {
            return (NbtCompound) ItemStack.CODEC.encodeStart(
                registries.getOps(NbtOps.INSTANCE), stack
            ).getOrThrow();
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Failed to serialize ItemStack to NBT: {}", e.getMessage());
            return new NbtCompound();
        }
    }
    
    /**
     * Convert NBT to ItemStack using the modern codec system
     */
    public static ItemStack itemStackFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        if (nbt == null || nbt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        
        try {
            return ItemStack.CODEC.parse(
                registries.getOps(NbtOps.INSTANCE), nbt
            ).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Failed to deserialize NBT to ItemStack: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }
    
    /**
     * Serialize NBT compound to binary data (Base64 encoded for storage)
     * This preserves ALL NBT data without any conversion losses
     */
    public static String serializeNbtToBase64(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return "";
        }
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            NbtIo.write(nbt, dos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
            
        } catch (IOException e) {
            SafeslotMod.LOGGER.error("Failed to serialize NBT to binary: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Deserialize binary data (Base64 encoded) back to NBT compound
     * This preserves ALL NBT data without any conversion losses
     */
    public static NbtCompound deserializeNbtFromBase64(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return new NbtCompound();
        }
        
        try {
            byte[] data = Base64.getDecoder().decode(base64Data);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                 DataInputStream dis = new DataInputStream(bais)) {
                
                NbtElement element = NbtIo.read(dis, NbtSizeTracker.ofUnlimitedBytes());
                if (element instanceof NbtCompound) {
                    return (NbtCompound) element;
                }
                return new NbtCompound();
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Failed to deserialize binary data to NBT: {}", e.getMessage());
            return new NbtCompound();
        }
    }
    
    /**
     * Deep copy an NBT compound to avoid reference issues
     */
    public static NbtCompound deepCopy(NbtCompound original) {
        if (original == null) {
            return new NbtCompound();
        }
        return original.copy();
    }
    
    /**
     * Check if two NBT compounds are effectively the same
     */
    public static boolean areEqual(NbtCompound first, NbtCompound second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.equals(second);
    }
    
    /**
     * Get the size of an NBT compound in bytes (approximate)
     */
    public static int estimateNbtSize(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return 0;
        }
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            
            NbtIo.write(nbt, dos);
            return baos.size();
            
        } catch (IOException e) {
            return 0;
        }
    }
    
    // Helper methods for 1.21.11 NBT API with Optional returns
    
    /**
     * Get boolean from NBT safely
     */
    public static boolean getBoolean(NbtCompound nbt, String key) {
        return nbt.contains(key) && nbt.getBoolean(key).orElse(false);
    }
    
    /**
     * Get int from NBT safely
     */
    public static int getInt(NbtCompound nbt, String key) {
        return nbt.getInt(key).orElse(0);
    }
    
    /**
     * Get long from NBT safely
     */
    public static long getLong(NbtCompound nbt, String key) {
        return nbt.getLong(key).orElse(0L);
    }
    
    /**
     * Get string from NBT safely
     */
    public static String getString(NbtCompound nbt, String key) {
        return nbt.getString(key).orElse("");
    }
    
    /**
     * Get compound from NBT safely
     */
    public static NbtCompound getCompound(NbtCompound nbt, String key) {
        return nbt.getCompound(key).orElse(new NbtCompound());
    }
    
    /**
     * Get list from NBT safely
     */
    public static NbtList getList(NbtCompound nbt, String key) {
        return nbt.getList(key).orElse(new NbtList());
    }
    
    /**
     * Get compound from list safely
     */
    public static NbtCompound getCompound(NbtList list, int index) {
        if (index < 0 || index >= list.size()) return new NbtCompound();
        return list.getCompound(index).orElse(new NbtCompound());
    }
}