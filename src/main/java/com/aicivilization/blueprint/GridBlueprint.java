package com.aicivilization.blueprint;

import com.aicivilization.AICivilizationMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** A compact character-grid blueprint stored in the mod's data resources. */
public record GridBlueprint(String name, int width, int depth, List<String> rows) {
    public GridBlueprint {
        rows = List.copyOf(rows);
        if (width <= 0 || depth <= 0 || rows.size() != depth) {
            throw new IllegalArgumentException("Invalid blueprint dimensions for " + name);
        }
        if (rows.stream().anyMatch(row -> row.length() != width)) {
            throw new IllegalArgumentException("Blueprint rows must all be " + width + " cells wide: " + name);
        }
    }

    public static GridBlueprint load(ServerLevel level, String name) {
        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
            AICivilizationMod.MOD_ID, "blueprints/" + name + ".json");
        Resource resource = level.getServer().getResourceManager().getResource(resourceId)
            .orElseThrow(() -> new IllegalStateException("Missing blueprint resource " + resourceId));
        try (Reader reader = resource.openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            int width = json.get("width").getAsInt();
            int depth = json.get("depth").getAsInt();
            JsonArray layout = json.getAsJsonArray("layout");
            List<String> rows = new ArrayList<>(layout.size());
            for (JsonElement row : layout) rows.add(row.getAsString());
            return new GridBlueprint(name, width, depth, rows);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not load blueprint " + resourceId, exception);
        }
    }

    public char cellAt(int x, int z) {
        if (x < 0 || x >= width || z < 0 || z >= depth) return ' ';
        return rows.get(z).charAt(x);
    }
}
