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
    // Serialize a list of NbtCompound (backups) to JSON
    public static String serializeBackups(List<NbtCompound> backups) {
        JsonArray arr = new JsonArray();
        for (NbtCompound nbt : backups) {
            arr.add(nbt.toString());
        }
        return arr.toString();
    }

    // Deserialize a JSON string to a list of NbtCompound (backups)
    public static List<NbtCompound> deserializeBackups(String json) {
        List<NbtCompound> result = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            String nbtStr = el.getAsString();
            NbtCompound nbt = parseNbt(nbtStr);
            if (nbt != null) result.add(nbt);
        }
        return result;
    }

    // Parse NBT from string (vanilla format) - updated for 1.21.11
    private static NbtCompound parseNbt(String nbtStr) {
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
