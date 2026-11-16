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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A compact character-grid blueprint stored in the mod's data resources (supports 2D layout and 3D layers). */
public record GridBlueprint(String name, int width, int depth, int height, List<List<String>> layers, Map<Character, String> legend) {
    public GridBlueprint(String name, int width, int depth, List<String> rows) {
        this(name, width, depth, 1, List.of(rows), Map.of());
    }

    public GridBlueprint {
        layers = layers.stream().map(List::copyOf).toList();
        legend = Map.copyOf(legend);
        if (width <= 0 || depth <= 0 || height <= 0 || layers.size() != height) {
            throw new IllegalArgumentException("Invalid blueprint dimensions for " + name);
        }
        for (List<String> layer : layers) {
            if (layer.size() != depth || layer.stream().anyMatch(row -> row.length() != width)) {
                throw new IllegalArgumentException("Blueprint rows must all be " + width + "x" + depth + ": " + name);
            }
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
            Map<Character, String> legend = new HashMap<>();
            if (json.has("legend")) {
                for (var entry : json.getAsJsonObject("legend").entrySet()) {
                    if (!entry.getKey().isEmpty()) {
                        legend.put(entry.getKey().charAt(0), entry.getValue().getAsString());
                    }
                }
            }
            List<List<String>> layers = new ArrayList<>();
            if (json.has("layers")) {
                for (JsonElement layerEl : json.getAsJsonArray("layers")) {
                    List<String> rows = new ArrayList<>(depth);
                    for (JsonElement row : layerEl.getAsJsonArray()) rows.add(row.getAsString());
                    layers.add(rows);
                }
            } else {
                JsonArray layout = json.getAsJsonArray("layout");
                List<String> rows = new ArrayList<>(layout.size());
                for (JsonElement row : layout) rows.add(row.getAsString());
                layers.add(rows);
            }
            return new GridBlueprint(name, width, depth, layers.size(), layers, legend);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not load blueprint " + resourceId, exception);
        }
    }

    public List<String> rows() {
        return layers.get(0);
    }

    public char cellAt(int x, int z) {
        return cellAt(x, 0, z);
    }

    public char cellAt(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) return ' ';
        return layers.get(y).get(z).charAt(x);
    }

    public String blockIdFor(char symbol) {
        String id = legend.get(symbol);
        if (id == null || id.equals("empty") || id.equals("minecraft:air")) return null;
        return id;
    }
}
