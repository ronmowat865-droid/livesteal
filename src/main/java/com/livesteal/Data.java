package com.livesteal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Saved to <world>/livesteal.json. Delete an id from "crafted" to let that item be crafted again. */
public class Data {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Set<String> crafted = new HashSet<>();
    public Map<String, Integer> ranks = new HashMap<>();   // player uuid -> rank (1 = highest)
    public Map<String, Integer> hearts = new HashMap<>();  // player uuid -> extra hearts used
    public int nextRank = 1;

    private transient Path file;

    public static Data load(MinecraftServer server) {
        Path file = server.getSavePath(WorldSavePath.ROOT).resolve("livesteal.json");
        Data d = null;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                d = GSON.fromJson(r, Data.class);
            } catch (Exception e) {
                LivestealMod.LOGGER.error("Could not read livesteal.json", e);
            }
        }
        if (d == null) d = new Data();
        if (d.crafted == null) d.crafted = new HashSet<>();
        if (d.ranks == null) d.ranks = new HashMap<>();
        if (d.hearts == null) d.hearts = new HashMap<>();
        d.file = file;
        return d;
    }

    public void save() {
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            LivestealMod.LOGGER.error("Could not save livesteal.json", e);
        }
    }
}
