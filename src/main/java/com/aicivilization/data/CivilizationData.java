package com.aicivilization.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent world data for citizen nations and named locations. */
public final class CivilizationData extends SavedData {
    public static final int NATION_RADIUS_BLOCKS = 128;
    private static final List<String> COUNTRY_NAMES = List.of(
        "Brasil", "Portugal", "Argentina", "Chile", "México", "Canadá", "Japão", "Egito",
        "Noruega", "Austrália", "Marrocos", "Índia", "Grécia", "Quênia", "Islândia", "Peru",
        "Suécia", "Tailândia", "Colômbia", "Nova Zelândia", "Coreia", "Turquia", "Cuba", "Angola"
    );
    private static final Codec<SavedLocation> LOCATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(SavedLocation::name),
        Codec.INT.fieldOf("x").forGetter(SavedLocation::x),
        Codec.INT.fieldOf("y").forGetter(SavedLocation::y),
        Codec.INT.fieldOf("z").forGetter(SavedLocation::z),
        ResourceLocation.CODEC.fieldOf("dimension").forGetter(SavedLocation::dimension)
    ).apply(instance, SavedLocation::new));
    private static final Codec<Nation> NATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(Nation::id),
        Codec.STRING.fieldOf("name").forGetter(Nation::name),
        UUIDUtil.CODEC.listOf().fieldOf("citizens").forGetter(Nation::citizens),
        Codec.INT.optionalFieldOf("x", 0).forGetter(Nation::x),
        Codec.INT.optionalFieldOf("y", 64).forGetter(Nation::y),
        Codec.INT.optionalFieldOf("z", 0).forGetter(Nation::z),
        ResourceLocation.CODEC.optionalFieldOf("dimension", Level.OVERWORLD.location()).forGetter(Nation::dimension)
    ).apply(instance, Nation::new));
    private static final Codec<Warehouse> WAREHOUSE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("nation").forGetter(Warehouse::nationId),
        Codec.INT.fieldOf("x").forGetter(Warehouse::x),
        Codec.INT.fieldOf("y").forGetter(Warehouse::y),
        Codec.INT.fieldOf("z").forGetter(Warehouse::z),
        Codec.INT.fieldOf("radius").forGetter(Warehouse::radius),
        ResourceLocation.CODEC.fieldOf("dimension").forGetter(Warehouse::dimension),
        Codec.BOOL.fieldOf("hasInbox").forGetter(Warehouse::hasInbox),
        Codec.INT.fieldOf("inboxX").forGetter(Warehouse::inboxX),
        Codec.INT.fieldOf("inboxY").forGetter(Warehouse::inboxY),
        Codec.INT.fieldOf("inboxZ").forGetter(Warehouse::inboxZ)
    ).apply(instance, Warehouse::new));
    private static final Codec<StorageRequest> STORAGE_REQUEST_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(StorageRequest::id),
        UUIDUtil.CODEC.fieldOf("nation").forGetter(StorageRequest::nationId),
        UUIDUtil.CODEC.fieldOf("requester").forGetter(StorageRequest::requesterId),
        Codec.STRING.fieldOf("item").forGetter(StorageRequest::itemId),
        Codec.INT.fieldOf("count").forGetter(StorageRequest::count),
        Codec.BOOL.fieldOf("fulfilled").forGetter(StorageRequest::fulfilled)
    ).apply(instance, StorageRequest::new));
    private static final Codec<StoragerClaim> STORAGER_CLAIM_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("nation").forGetter(StoragerClaim::nationId),
        UUIDUtil.CODEC.fieldOf("citizen").forGetter(StoragerClaim::citizenId)
    ).apply(instance, StoragerClaim::new));
    private static final Codec<MineSite> MINE_SITE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("nation").forGetter(MineSite::nationId),
        Codec.INT.fieldOf("x").forGetter(MineSite::x),
        Codec.INT.fieldOf("y").forGetter(MineSite::y),
        Codec.INT.fieldOf("z").forGetter(MineSite::z),
        Codec.INT.fieldOf("direction2D").forGetter(MineSite::direction2D),
        ResourceLocation.CODEC.fieldOf("dimension").forGetter(MineSite::dimension)
    ).apply(instance, MineSite::new));
    private static final Codec<CivilizationData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        LOCATION_CODEC.listOf().fieldOf("locations").forGetter(data -> data.locations.values().stream().toList()),
        NATION_CODEC.listOf().fieldOf("nations").forGetter(data -> data.nations.values().stream().toList()),
        WAREHOUSE_CODEC.listOf().optionalFieldOf("warehouses", List.of()).forGetter(data -> data.warehouses.values().stream().toList()),
        STORAGE_REQUEST_CODEC.listOf().optionalFieldOf("storageRequests", List.of()).forGetter(data -> data.storageRequests.values().stream().toList()),
        STORAGER_CLAIM_CODEC.listOf().optionalFieldOf("storagerClaims", List.of()).forGetter(data -> data.storagerClaims.values().stream().toList()),
        MINE_SITE_CODEC.listOf().optionalFieldOf("mineSites", List.of()).forGetter(data -> data.mineSites.values().stream().toList())
    ).apply(instance, CivilizationData::new));
    public static final SavedDataType<CivilizationData> TYPE = new SavedDataType<>(
        "ai_civilization", CivilizationData::new, CODEC, null
    );

    private final Map<String, SavedLocation> locations = new LinkedHashMap<>();
    private final Map<UUID, Nation> nations = new LinkedHashMap<>();
    private final Map<UUID, Warehouse> warehouses = new LinkedHashMap<>();
    private final Map<UUID, StorageRequest> storageRequests = new LinkedHashMap<>();
    private final Map<UUID, StoragerClaim> storagerClaims = new LinkedHashMap<>();
    private final Map<UUID, MineSite> mineSites = new LinkedHashMap<>();

    private CivilizationData() {}

    private CivilizationData(List<SavedLocation> locations, List<Nation> nations,
                             List<Warehouse> warehouses, List<StorageRequest> storageRequests,
                             List<StoragerClaim> storagerClaims, List<MineSite> mineSites) {
        locations.forEach(location -> this.locations.put(key(location.name()), location));
        nations.forEach(nation -> this.nations.put(nation.id(), nation));
        warehouses.forEach(warehouse -> this.warehouses.put(warehouse.nationId(), warehouse));
        storageRequests.forEach(request -> this.storageRequests.put(request.id(), request));
        storagerClaims.forEach(claim -> this.storagerClaims.put(claim.nationId(), claim));
        mineSites.forEach(site -> this.mineSites.put(site.nationId(), site));
    }

    public static CivilizationData get(ServerLevel level) {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public SavedLocation setLocation(String name, BlockPos pos, ResourceKey<Level> dimension) {
        SavedLocation location = new SavedLocation(name, pos.getX(), pos.getY(), pos.getZ(), dimension.location());
        locations.put(key(name), location);
        setDirty();
        return location;
    }

    public SavedLocation getLocation(String name) {
        return locations.get(key(name));
    }

    public boolean removeLocation(String name) {
        if (locations.remove(key(name)) == null) return false;
        setDirty();
        return true;
    }

    public List<SavedLocation> locations() {
        return List.copyOf(locations.values());
    }

    public Nation assignOrFoundNation(UUID citizenId, String founderName, BlockPos citizenPos,
                                      BlockPos foundingCenter, ResourceLocation dimension) {
        Nation containingNation = findNationAt(citizenPos, dimension);
        if (containingNation != null) {
            Nation joined = containingNation.withCitizen(citizenId);
            nations.put(joined.id(), joined);
            setDirty();
            return joined;
        }
        List<String> usedNames = nations.values().stream().map(Nation::name).toList();
        String countryName = COUNTRY_NAMES.stream().filter(name -> !usedNames.contains(name)).findFirst()
            .orElse("País " + (nations.size() + 1));
        if (findNationAt(foundingCenter, dimension) != null) foundingCenter = citizenPos;
        Nation nation = new Nation(UUID.randomUUID(), countryName, new ArrayList<>(List.of(citizenId)),
            foundingCenter.getX(), foundingCenter.getY(), foundingCenter.getZ(), dimension);
        nations.put(nation.id(), nation);
        setDirty();
        return nation;
    }

    public Nation createNation(String name, UUID citizenId, BlockPos center, ResourceLocation dimension) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) return null;
        if (nations.values().stream().anyMatch(nation -> nation.name().equalsIgnoreCase(normalizedName))) return null;
        long minimumDistanceSquared = (long) NATION_RADIUS_BLOCKS * NATION_RADIUS_BLOCKS * 4L;
        boolean overlapsNation = nations.values().stream()
            .filter(nation -> nation.dimension().equals(dimension))
            .anyMatch(nation -> nation.distanceSquared(center) < minimumDistanceSquared);
        if (overlapsNation) return null;
        Nation nation = new Nation(UUID.randomUUID(), normalizedName, new ArrayList<>(List.of(citizenId)),
            center.getX(), center.getY(), center.getZ(), dimension);
        nations.put(nation.id(), nation);
        setDirty();
        return nation;
    }

    public Nation findNationAt(BlockPos pos, ResourceLocation dimension) {
        return nations.values().stream()
            .filter(nation -> nation.dimension().equals(dimension))
            .filter(nation -> nation.contains(pos))
            .min(java.util.Comparator.comparingLong(nation -> nation.distanceSquared(pos)))
            .orElse(null);
    }

    public Nation getNation(UUID id) {
        return nations.get(id);
    }

    public void removeNation(UUID id) {
        if (nations.remove(id) == null) return;
        warehouses.remove(id);
        storagerClaims.remove(id);
        storageRequests.values().removeIf(request -> request.nationId().equals(id));
        setDirty();
    }

    public List<Nation> nations() {
        return List.copyOf(nations.values());
    }

    public Warehouse warehouse(UUID nationId) { return warehouses.get(nationId); }

    public UUID storager(UUID nationId) {
        StoragerClaim claim = storagerClaims.get(nationId);
        return claim == null ? null : claim.citizenId();
    }

    public boolean claimStorager(UUID nationId, UUID citizenId) {
        StoragerClaim current = storagerClaims.get(nationId);
        if (current != null) return current.citizenId().equals(citizenId);
        storagerClaims.put(nationId, new StoragerClaim(nationId, citizenId));
        setDirty();
        return true;
    }

    public void releaseStorager(UUID nationId, UUID citizenId) {
        StoragerClaim current = storagerClaims.get(nationId);
        if (current == null || !current.citizenId().equals(citizenId)) return;
        storagerClaims.remove(nationId);
        setDirty();
    }

    public Warehouse setWarehouse(UUID nationId, BlockPos center, int radius, ResourceLocation dimension) {
        Warehouse warehouse = new Warehouse(nationId, center.getX(), center.getY(), center.getZ(), radius, dimension,
            false, 0, 0, 0);
        warehouses.put(nationId, warehouse);
        setDirty();
        return warehouse;
    }

    public Warehouse setWarehouseInbox(UUID nationId, BlockPos inbox) {
        Warehouse old = warehouses.get(nationId);
        if (old == null) return null;
        Warehouse updated = old.withInbox(inbox);
        warehouses.put(nationId, updated);
        setDirty();
        return updated;
    }

    public StorageRequest requestStorageItem(UUID nationId, UUID requesterId, String itemId, int count) {
        StorageRequest existing = storageRequests.values().stream()
            .filter(request -> request.nationId().equals(nationId) && request.requesterId().equals(requesterId)
                && request.itemId().equals(itemId) && !request.fulfilled())
            .findFirst().orElse(null);
        if (existing != null) return existing;
        StorageRequest request = new StorageRequest(UUID.randomUUID(), nationId, requesterId, itemId, count, false);
        storageRequests.put(request.id(), request);
        setDirty();
        return request;
    }

    public List<StorageRequest> openStorageRequests(UUID nationId) {
        return storageRequests.values().stream()
            .filter(request -> request.nationId().equals(nationId) && !request.fulfilled()).toList();
    }

    public void fulfillStorageRequest(UUID requestId) {
        StorageRequest request = storageRequests.get(requestId);
        if (request == null || request.fulfilled()) return;
        storageRequests.put(requestId, request.fulfill());
        setDirty();
    }

    public Nation updateNationCenter(Nation nation, BlockPos center) {
        Nation updated = new Nation(nation.id(), nation.name(), nation.citizens(), center.getX(), center.getY(),
            center.getZ(), nation.dimension());
        nations.put(updated.id(), updated);
        setDirty();
        return updated;
    }

    public static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record SavedLocation(String name, int x, int y, int z, ResourceLocation dimension) {
        public BlockPos blockPos() { return new BlockPos(x, y, z); }
    }

    public record Warehouse(UUID nationId, int x, int y, int z, int radius, ResourceLocation dimension,
                            boolean hasInbox, int inboxX, int inboxY, int inboxZ) {
        public BlockPos center() { return new BlockPos(x, y, z); }
        public BlockPos inbox() { return hasInbox ? new BlockPos(inboxX, inboxY, inboxZ) : null; }
        public Warehouse withInbox(BlockPos pos) {
            return new Warehouse(nationId, x, y, z, radius, dimension, true, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public record StorageRequest(UUID id, UUID nationId, UUID requesterId, String itemId, int count, boolean fulfilled) {
        public StorageRequest fulfill() { return new StorageRequest(id, nationId, requesterId, itemId, count, true); }
    }

    public record StoragerClaim(UUID nationId, UUID citizenId) {}

    public record Nation(UUID id, String name, List<UUID> citizens, int x, int y, int z, ResourceLocation dimension) {
        public Nation {
            citizens = List.copyOf(citizens);
        }

        public boolean contains(BlockPos pos) {
            return distanceSquared(pos) <= (long) NATION_RADIUS_BLOCKS * NATION_RADIUS_BLOCKS;
        }

        public long distanceSquared(BlockPos pos) {
            long dx = (long) pos.getX() - x;
            long dz = (long) pos.getZ() - z;
            return dx * dx + dz * dz;
        }

        public Nation withCitizen(UUID citizenId) {
            if (citizens.contains(citizenId)) return this;
            List<UUID> updated = new ArrayList<>(citizens);
            updated.add(citizenId);
            return new Nation(id, name, updated, x, y, z, dimension);
        }

        public BlockPos center() { return new BlockPos(x, y, z); }
    }

    public MineSite getMineSite(UUID nationId) {
        return nationId == null ? null : mineSites.get(nationId);
    }

    public MineSite setMineSite(UUID nationId, BlockPos origin, Direction direction, ResourceLocation dimension) {
        if (nationId == null || origin == null) return null;
        MineSite site = new MineSite(nationId, origin.getX(), origin.getY(), origin.getZ(),
            direction.get2DDataValue(), dimension);
        mineSites.put(nationId, site);
        setDirty();
        return site;
    }

    public record MineSite(UUID nationId, int x, int y, int z, int direction2D, ResourceLocation dimension) {
        public BlockPos origin() { return new BlockPos(x, y, z); }
        public Direction direction() { return Direction.from2DDataValue(direction2D); }
    }
}
