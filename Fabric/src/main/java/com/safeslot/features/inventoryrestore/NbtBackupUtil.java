package com.safeslot.features.inventoryrestore;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;

public class NbtBackupUtil {
    // Serialize a list of NbtCompound (backups) to JSON with error handling
    public static String serializeBackups(List<NbtCompound> backups) {
        JsonArray arr = new JsonArray();
        
        for (NbtCompound nbt : backups) {
            try {
                String nbtStr = nbt.toString();
                if (nbtStr != null && !nbtStr.isEmpty()) {
                    arr.add(nbtStr);
                }
            } catch (Exception e) {
                // Skip failed serialization
            }
        }
        
        return arr.toString();
    }

    // Deserialize a JSON string to a list of NbtCompound (backups) with error handling
    public static List<NbtCompound> deserializeBackups(String json) {
        List<NbtCompound> result = new ArrayList<>();
        
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            
            for (JsonElement el : arr) {
                try {
                    String nbtStr = el.getAsString();
                    NbtCompound nbt = parseNbt(nbtStr);
                    if (nbt != null && !nbt.isEmpty()) {
                        result.add(nbt);
                    }
                } catch (Exception e) {
                    // Skip failed deserialization
                }
            }
        } catch (Exception e) {
            // Return empty list on parse failure
        }
        
        return result;
    }

    // Parse NBT from string (vanilla format) - updated for 1.21.11
    private static NbtCompound parseNbt(String nbtStr) {
        if (nbtStr == null || nbtStr.trim().isEmpty()) {
            return new NbtCompound();
        }
        
        try {
            NbtElement element = NbtHelper.fromNbtProviderString(nbtStr);
            if (element instanceof NbtCompound) {
                return (NbtCompound) element;
            }
            return new NbtCompound();
        } catch (Exception e) {
            return new NbtCompound();
        }
    }
}
