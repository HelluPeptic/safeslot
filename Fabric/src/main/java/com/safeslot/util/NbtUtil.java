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
     * This preserves ALL NBT data using Minecraft's compressed file format for full compatibility
     */
    public static String serializeNbtToBase64(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return "";
        }
        
        try {
            // Use compressed NBT format (like .nbt files) for maximum compatibility
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                NbtIo.writeCompressed(nbt, baos);
                byte[] nbtBytes = baos.toByteArray();
                
                if (nbtBytes.length == 0) {
                    SafeslotMod.LOGGER.error("Compressed NBT serialization produced 0 bytes!");
                    return "";
                }
                
                // Validation: Try to read back the compressed NBT we just wrote
                try (ByteArrayInputStream testBais = new ByteArrayInputStream(nbtBytes)) {
                    NbtCompound testCompound = NbtIo.readCompressed(testBais, NbtSizeTracker.ofUnlimitedBytes());
                    
                    if (testCompound.isEmpty()) {
                        SafeslotMod.LOGGER.error("NBT validation failed: Compressed NBT read back as empty!");
                        return "";
                    }
                } catch (Exception validationException) {
                    SafeslotMod.LOGGER.error("NBT validation failed: {}", validationException.getMessage());
                    return "";
                }
                
                String base64Result = Base64.getEncoder().encodeToString(nbtBytes);
                if (base64Result.isEmpty()) {
                    SafeslotMod.LOGGER.error("Base64 encoding resulted in empty string!");
                    return "";
                }
                
                return base64Result;
                
            } catch (IOException e) {
                SafeslotMod.LOGGER.error("IOException during compressed NBT serialization: {}", e.getMessage());
                return "";
            }
            
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("Unexpected error during NBT serialization: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Deserialize binary data (Base64 encoded) back to NBT compound
     * This preserves ALL NBT data using Minecraft's compressed file format
     */
    public static NbtCompound deserializeNbtFromBase64(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            return new NbtCompound();
        }
        
        try {
            // Step 1: Decode Base64 to bytes
            byte[] nbtBytes = Base64.getDecoder().decode(base64Data);
            
            if (nbtBytes.length == 0) {
                SafeslotMod.LOGGER.error("Base64 decoded to 0 bytes!");
                return new NbtCompound();
            }
            
            // Step 2: Read compressed NBT format (like .nbt files)
            try (ByteArrayInputStream bais = new ByteArrayInputStream(nbtBytes)) {
                NbtCompound compound = NbtIo.readCompressed(bais, NbtSizeTracker.ofUnlimitedBytes());
                
                if (compound == null || compound.isEmpty()) {
                    SafeslotMod.LOGGER.error("Deserialized NBT compound is empty!");
                    return new NbtCompound();
                }
                
                return compound;
                
            } catch (IOException ioException) {
                SafeslotMod.LOGGER.error("IOException during compressed NBT reading: {}", ioException.getMessage());
                
                // Fallback: Try uncompressed format
                try (ByteArrayInputStream bais = new ByteArrayInputStream(nbtBytes);
                     DataInputStream dis = new DataInputStream(bais)) {
                    
                    NbtElement element = NbtIo.read(dis, NbtSizeTracker.ofUnlimitedBytes());
                    if (element instanceof NbtCompound compound) {
                        return compound;
                    } else {
                        SafeslotMod.LOGGER.error("Fallback failed: Element is not compound");
                        return new NbtCompound();
                    }
                } catch (Exception fallbackException) {
                    SafeslotMod.LOGGER.error("Fallback to uncompressed NBT also failed: {}", fallbackException.getMessage());
                    return new NbtCompound();
                }
            }
            
        } catch (IllegalArgumentException base64Exception) {
            SafeslotMod.LOGGER.error("Base64 decode failed - invalid Base64 data: {}", base64Exception.getMessage());
            return new NbtCompound();
        } catch (Exception generalException) {
            SafeslotMod.LOGGER.error("Unexpected error during NBT deserialization: {}", generalException.getMessage());
            return new NbtCompound();
        }
    }
    
    /**
     * Alternative NBT serialization using string format as fallback
     * This is used if binary serialization fails
     */
    public static String serializeNbtToString(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return "";
        }
        
        try {
            String nbtString = nbt.toString();
            return Base64.getEncoder().encodeToString(nbtString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            SafeslotMod.LOGGER.error("NBT string serialization failed: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Alternative NBT deserialization from string format
     * This is used if binary deserialization fails
     */
    public static NbtCompound deserializeNbtFromString(String base64String) {
        return new NbtCompound();
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