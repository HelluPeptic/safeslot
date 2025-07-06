package com.safeslot.features.inventoryrestore;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;

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

    // Parse NBT from string (vanilla format)
    private static NbtCompound parseNbt(String nbtStr) {
        try {
            return StringNbtReader.parse(nbtStr);
        } catch (Exception e) {
            return new NbtCompound();
        }
    }
}
