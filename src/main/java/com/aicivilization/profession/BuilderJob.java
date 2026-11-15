package com.aicivilization.profession;

import com.aicivilization.blueprint.GridBlueprint;
import com.aicivilization.data.CivilizationData;
import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.storage.StorageJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;


/** Builds and repairs three-block-wide dirt roads from the town center to its four borders. */
public final class BuilderJob implements ProfessionJob {
    public static final String ID = "builder";
    public static final String LABEL = "Builder";
    private static final int TICK_INTERVAL = 4;
    private static final int ROAD_WIDTH = 3;
    private static final int[] LANE_OFFSETS = {-1, 0, 1};
    private static final Direction[] ROAD_DIRECTIONS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
    private static GridBlueprint VILLAGE_CENTER_BLUEPRINT;
    private int cooldown;
    private BlockPos townCenter;
    private int directionIndex;
    private int distance = 1;
    private int laneIndex;
    private int warehousePlotCursor;
    private int plazaCursor;
    private int[] previousSurfaceY = new int[ROAD_WIDTH];
    private String pendingResourceId = "";
    private boolean shovelRequestSent;
    private boolean pickaxeRequestSent;
    private boolean stairRequestSent;
    private boolean dirtSearchAnnounced;
    private BlockPos dirtSourceTarget;
    private boolean completed;
    private boolean preparingPlot;
    private boolean warehousePathDone;
    private int warehousePathStep;
    private int warehousePathLane;

    private boolean gatheringDirtBatch;
    private int dirtBatchTargetCount;

    @Override
    public String getId() { return ID; }

    @Override
    public String getLabel() { return LABEL; }

    @Override
    public boolean start(CitizenEntity citizen, ServerLevel level) {
        if (citizen.getNationId() == null) return false;
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(citizen.getNationId());
        if (nation == null) return false;
        start(citizen, nation.center());
        if (!citizen.getMainHandItem().is(ItemTags.SHOVELS) && findInventorySlot(citizen, ItemTags.SHOVELS) < 0) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SHOVEL));
        }
        if (!citizen.getMainHandItem().is(ItemTags.PICKAXES) && findInventorySlot(citizen, ItemTags.PICKAXES) < 0) {
            citizen.getNpcInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        }
        return true;
    }

    @Override
    public void stop(CitizenEntity citizen, ServerLevel level, boolean announce) {
        stop();
        citizen.getNavigation().stop();
        if (announce) citizen.announce("Parei o trabalho de construção.");
    }

    @Override
    public String workStatus(CitizenEntity citizen, ServerLevel level) { return workStatus(); }

    @Override
    public String debugStatus(CitizenEntity citizen, ServerLevel level) { return debugStatus(); }

    @Override
    public boolean needsNavigation(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (!pendingResourceId.isEmpty() || shovelRequestSent || pickaxeRequestSent || stairRequestSent) {
            if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
                return !StorageJob.isCitizenInsideWarehouse(citizen, warehouse);
            }
        }
        if (warehouse != null && data.warehousePlotRequested(citizen.getNationId())
            && !data.warehousePlotPrepared(citizen.getNationId())) {
            if (dirtSourceTarget != null) {
                return citizen.distanceToSqr(dirtSourceTarget.getX() + 0.5, dirtSourceTarget.getY(), dirtSourceTarget.getZ() + 0.5) > 9.0;
            }
            return citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 49.0;
        }
        if (townCenter == null) return false;
        if (plazaCursor < villageCenterBlueprint(level).width() * villageCenterBlueprint(level).depth()) {
            return citizen.distanceToSqr(townCenter.getX() + 0.5, townCenter.getY(), townCenter.getZ() + 0.5) > 36.0;
        }
        if (warehouse != null && !warehousePathDone) {
            java.util.List<BlockPos> cells = computeWarehouseConnectionCells(warehouse);
            if (warehousePathStep < cells.size()) {
                BlockPos next = cells.get(warehousePathStep);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, next.getX(), next.getZ());
                return citizen.distanceToSqr(next.getX() + 0.5, y, next.getZ() + 0.5) > 16.0;
            }
        }
        if (completed || directionIndex >= ROAD_DIRECTIONS.length) return false;
        BlockPos target = roadPosition(ROAD_DIRECTIONS[directionIndex], distance, LANE_OFFSETS[laneIndex]);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
        return citizen.distanceToSqr(target.getX() + 0.5, y, target.getZ() + 0.5) > 16.0;
    }

    public void start(CitizenEntity citizen, BlockPos center) {
        townCenter = center.immutable();
        directionIndex = 0;
        distance = 1;
        laneIndex = 0;
        warehousePlotCursor = 0;
        plazaCursor = 0;
        previousSurfaceY = new int[ROAD_WIDTH];
        pendingResourceId = "";
        shovelRequestSent = false;
        pickaxeRequestSent = false;
        stairRequestSent = false;
        dirtSearchAnnounced = false;
        dirtSourceTarget = null;
        completed = false;
        preparingPlot = false;
        warehousePathDone = false;
        warehousePathStep = 0;
        warehousePathLane = 0;
        cooldown = 0;
    }

    public void stop() {
        townCenter = null;
        pendingResourceId = "";
        completed = false;
    }

    public void save(ValueOutput output) {
        if (townCenter != null) output.store("BuilderCenter", BlockPos.CODEC, townCenter);
        output.putInt("BuilderDirection", directionIndex);
        output.putInt("BuilderDistance", distance);
        output.putInt("BuilderLane", laneIndex);
        output.putInt("BuilderWarehousePlotCursor", warehousePlotCursor);
        output.putInt("BuilderPlazaCursor", plazaCursor);
        output.putIntArray("BuilderPreviousSurfaceY", previousSurfaceY);
        output.putString("BuilderPendingResource", pendingResourceId);
        output.putBoolean("BuilderShovelRequestSent", shovelRequestSent);
        output.putBoolean("BuilderPickaxeRequestSent", pickaxeRequestSent);
        output.putBoolean("BuilderStairRequestSent", stairRequestSent);
        output.putBoolean("BuilderDirtSearchAnnounced", dirtSearchAnnounced);
        output.putBoolean("BuilderCompleted", completed);
        output.putBoolean("BuilderPreparingPlot", preparingPlot);
        output.putBoolean("BuilderWarehousePathDone", warehousePathDone);
    }

    public void load(ValueInput input) {
        townCenter = input.read("BuilderCenter", BlockPos.CODEC).orElse(null);
        directionIndex = Math.max(0, Math.min(ROAD_DIRECTIONS.length - 1, input.getIntOr("BuilderDirection", 0)));
        distance = Math.max(1, input.getIntOr("BuilderDistance", 1));
        laneIndex = Math.max(0, Math.min(ROAD_WIDTH - 1, input.getIntOr("BuilderLane", 0)));
        warehousePlotCursor = Math.max(0, Math.min(100, input.getIntOr("BuilderWarehousePlotCursor", 0)));
        plazaCursor = Math.max(0, Math.min(35, input.getIntOr("BuilderPlazaCursor", 0)));
        previousSurfaceY = input.getIntArray("BuilderPreviousSurfaceY").filter(values -> values.length == ROAD_WIDTH)
            .orElse(new int[ROAD_WIDTH]);
        pendingResourceId = input.getStringOr("BuilderPendingResource", "");
        shovelRequestSent = input.getBooleanOr("BuilderShovelRequestSent", false);
        pickaxeRequestSent = input.getBooleanOr("BuilderPickaxeRequestSent", false);
        stairRequestSent = input.getBooleanOr("BuilderStairRequestSent", false);
        dirtSearchAnnounced = input.getBooleanOr("BuilderDirtSearchAnnounced", false);
        completed = input.getBooleanOr("BuilderCompleted", false);
        preparingPlot = input.getBooleanOr("BuilderPreparingPlot", false);
        warehousePathDone = input.getBooleanOr("BuilderWarehousePathDone", false);
    }

    public boolean isActive() {
        return townCenter != null && (!completed || plazaCursor < 35);
    }

    public boolean isInitialized() { return townCenter != null; }

    public String workStatus() {
        if (townCenter == null) return "Planejando os caminhos da vila";
        if (preparingPlot) return "Construindo piso 10x10 da warehouse: " + warehousePlotCursor + "%";
        if (plazaCursor < 35) return "Construindo a praça de madeira do centro: " + plazaCursor + "/35";
        if (!warehousePathDone) return "Conectando o caminho do centro da vila até a warehouse";
        if (completed) return "Os quatro caminhos até as fronteiras estão prontos";
        return "Construindo caminho " + directionName() + ", trecho " + distance + "/"
            + CivilizationData.NATION_RADIUS_BLOCKS + ", faixa " + (laneIndex + 1) + "/" + ROAD_WIDTH;
    }

    public String debugStatus() {
        return "active=" + isActive() + ", plot=" + warehousePlotCursor + "/100, plaza=" + plazaCursor + "/35, direction=" + directionName() + ", distance=" + distance
            + ", lane=" + laneIndex + ", pending=" + pendingResourceId;
    }

    public void tick(CitizenEntity citizen, ServerLevel level) {
        if (townCenter == null) {
            CivilizationData.Nation nation = CivilizationData.get(level).getNation(citizen.getNationId());
            if (nation == null) return;
            start(citizen, nation.center());
            return;
        }
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse != null && data.warehousePlotRequested(citizen.getNationId())
            && !data.warehousePlotPrepared(citizen.getNationId())) {
            if (!preparingPlot && warehousePlotCursor >= 100) warehousePlotCursor = 0;
            preparingPlot = true;
            if (cooldown-- > 0) return;
            cooldown = TICK_INTERVAL;
            collectNearbyDrops(citizen, level);
            prepareWarehousePlot(citizen, level, warehouse);
            return;
        }
        if (warehouse == null || data.warehousePlotPrepared(citizen.getNationId())) preparingPlot = false;
        if (cooldown-- > 0) return;
        cooldown = TICK_INTERVAL;
        collectNearbyDrops(citizen, level);
        if (plazaCursor < 35) {
            buildVillageCenter(citizen, level);
            return;
        }
        if (warehouse != null && !warehousePathDone) {
            buildWarehouseConnectionRoad(citizen, level, warehouse);
            return;
        }
        if (completed) return;
        if (directionIndex >= ROAD_DIRECTIONS.length) {
            completed = true;
            citizen.announce("Concluí e revisei os caminhos nas quatro direções da vila.");
            return;
        }

        Direction direction = ROAD_DIRECTIONS[directionIndex];
        BlockPos target = roadPosition(direction, distance, LANE_OFFSETS[laneIndex]);
        if (!citizen.isInsideNation(target)) {
            advanceLane();
            return;
        }
        if (!level.hasChunkAt(target)) return;

        // Skip cells that are inside the 7x5 village center plaza or the 10x10 warehouse plot
        if (isInsidePlazaOrWarehouse(target, warehouse)) {
            previousSurfaceY[laneIndex] = getTrueRoadSurfaceY(level, target.getX(), target.getZ());
            advanceLane();
            return;
        }

        int surfaceY = getTrueRoadSurfaceY(level, target.getX(), target.getZ());
        int prevY = previousSurfaceY[laneIndex] == 0 ? townCenter.getY() : previousSurfaceY[laneIndex];

        // Millénaire-style slope smoothing: never allow the road to jump or drop by more than 1 block per step!
        int desiredSurfaceY = surfaceY;
        if (surfaceY - prevY > 1) {
            desiredSurfaceY = prevY + 1;
        } else if (prevY - surfaceY > 1) {
            desiredSurfaceY = prevY - 1;
        }

        BlockPos surface = new BlockPos(target.getX(), surfaceY, target.getZ());
        double horizDistSqr = citizen.distanceToSqr(target.getX() + 0.5, citizen.getY(), target.getZ() + 0.5);
        if (horizDistSqr > 25.0) {
            if (citizen.getNavigation().isDone())
                citizen.getNavigation().moveTo(surface.getX() + 0.5, prevY, surface.getZ() + 0.5, 0.95);
            return;
        }

        // Check a 1-block radius around the road cell (from desiredSurfaceY - 1 up to surfaceY + 4) for any tree logs or mushrooms!
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int checkY = Math.min(desiredSurfaceY - 1, surfaceY - 1); checkY <= Math.max(surfaceY, desiredSurfaceY) + 4; checkY++) {
                    BlockPos checkPos = new BlockPos(target.getX() + dx, checkY, target.getZ() + dz);
                    if (!citizen.isInsideNation(checkPos) || !level.hasChunkAt(checkPos)) continue;
                    BlockState checkState = level.getBlockState(checkPos);
                    if (checkState.is(BlockTags.LOGS) || isMushroomBlock(checkState)) {
                        clearConnectedTreeInPlot(citizen, level, checkPos);
                        collectNearbyDrops(citizen, level);
                        return;
                    }
                }
            }
        }

        BlockPos ground = surface.below();
        BlockState groundState = level.getBlockState(ground);

        // Sweep any foliage/flowers/leaves/leaf litter at or above the road so the Builder has a 4-block-high tunnel of air!
        for (int clearY = desiredSurfaceY; clearY <= Math.max(surfaceY, desiredSurfaceY) + 4; clearY++) {
            BlockPos clearPos = new BlockPos(target.getX(), clearY, target.getZ());
            BlockState clearState = level.getBlockState(clearPos);
            if (!clearState.isAir() && isClearableVegetation(clearState) && level.getFluidState(clearPos).isEmpty()) {
                level.destroyBlock(clearPos, true, citizen);
            }
        }

        // If terrain is too high (cliff > 1 block step up), cut it down and collect dirt!
        if (surfaceY > desiredSurfaceY && level.getFluidState(ground).isEmpty()) {
            if (!groundState.hasBlockEntity() && groundState.getDestroySpeed(level, ground) >= 0.0f) {
                boolean isDirtLike = groundState.is(BlockTags.DIRT) || groundState.is(Blocks.GRASS_BLOCK);
                if (level.destroyBlock(ground, !isDirtLike, citizen)) {
                    if (isDirtLike) citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
                }
                return;
            }
        }

        // If terrain is too low (drop > 1 block step down), fill it up so the road slopes smoothly!
        if (surfaceY < desiredSurfaceY && !isWater(level, ground)) {
            BlockPos fillPos = new BlockPos(target.getX(), surfaceY, target.getZ());
            if (countItem(citizen, Items.DIRT) > 0) {
                placeBlockFromInventory(citizen, level, fillPos, Blocks.DIRT.defaultBlockState(), Items.DIRT);
            } else {
                level.setBlockAndUpdate(fillPos, Blocks.DIRT.defaultBlockState());
            }
            return;
        }

        surface = new BlockPos(target.getX(), desiredSurfaceY, target.getZ());
        ground = surface.below();
        groundState = level.getBlockState(ground);

        if (groundState.is(Blocks.DIRT_PATH) || groundState.is(BlockTags.PLANKS) || groundState.is(BlockTags.STAIRS)) {
            previousSurfaceY[laneIndex] = desiredSurfaceY;
            advanceLane();
            return;
        }

        if (isWater(level, ground)) {
            buildBridgeCell(citizen, level, surface, direction);
            return;
        }

        if (isExposedStone(groundState)) {
            if (!equipPickaxe(citizen, level)) return;
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level.destroyBlock(ground, true, citizen)) {
                level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState());
                if (citizen.getMainHandItem().isDamageableItem()) {
                    citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                }
                previousSurfaceY[laneIndex] = desiredSurfaceY;
                advanceLane();
            }
            return;
        }

        if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
            level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
        }
        if (!equipShovel(citizen, level)) return;

        if (!level.getBlockState(surface).isAir() && !level.getBlockState(surface).canBeReplaced()) {
            BlockState obstacle = level.getBlockState(surface);
            if (isClearableVegetation(obstacle)) {
                level.destroyBlock(surface, true, citizen);
            } else {
                previousSurfaceY[laneIndex] = desiredSurfaceY;
                advanceLane();
                return;
            }
        }
        citizen.getLookControl().setLookAt(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState())) {
            if (!level.getBlockState(surface).isAir() && isClearableVegetation(level.getBlockState(surface))) {
                level.destroyBlock(surface, true, citizen);
            }
            if (citizen.getMainHandItem().isDamageableItem()) {
                citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            }
            previousSurfaceY[laneIndex] = desiredSurfaceY;
            advanceLane();
        }
    }

    /**
     * Walks down past flowers, grass, leaf litter, and stairs to find the true terrain surface Y (air block above solid ground).
     */
    private int getTrueRoadSurfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (y > level.getMinY() + 5) {
            BlockPos below = new BlockPos(x, y - 1, z);
            BlockState state = level.getBlockState(below);
            if (isClearableVegetation(state) && level.getFluidState(below).isEmpty()) {
                y--;
            } else {
                break;
            }
        }
        return y;
    }

    private boolean isInsidePlazaOrWarehouse(BlockPos pos, CivilizationData.Warehouse warehouse) {
        if (townCenter != null && Math.abs(pos.getX() - townCenter.getX()) <= 3 && Math.abs(pos.getZ() - townCenter.getZ()) <= 2) {
            return true;
        }
        return warehouse != null && pos.getX() >= warehouse.x() - 5 && pos.getX() < warehouse.x() + 5
            && pos.getZ() >= warehouse.z() - 5 && pos.getZ() < warehouse.z() + 5;
    }

    /**
     * Millénaire-style 2-phase plot preparation:
     * Phase 1 (Pass 0..99): Clear all trees, vegetation, flowers, leaf litter, and cut elevated blocks above targetGroundY.
     *                       All excavated dirt goes directly into the Builder's inventory (Cut & Fill).
     * Phase 2 (Pass 100..199): Fill all depressions and water/lava up to targetGroundY.
     */
    private void prepareWarehousePlot(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse) {
        int targetGroundY = warehouse.y() - 1;

        // Walk to the construction site perimeter/center once (reach covers the 10x10 area)
        if (dirtSourceTarget == null && citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 49.0) {
            if (citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 1.0);
            }
            return;
        }

        // Convert any logs in inventory into planks if needed later, or keep dirt ready
        // PHASE 1: Cut elevated terrain, trees, and surface foliage across the entire 10x10 first!
        for (int idx = 0; idx < 100; idx++) {
            int x = warehouse.x() - 5 + idx % 10;
            int z = warehouse.z() - 5 + idx / 10;
            BlockPos probe = new BlockPos(x, warehouse.y(), z);
            if (!citizen.isInsideNation(probe) || !level.hasChunkAt(probe)) continue;

            // Instantly sweep any surface vegetation (flowers, wildflowers, leaf litter, grass, bushes) above target level
            for (int clearY = targetGroundY + 1; clearY <= targetGroundY + 5; clearY++) {
                BlockPos abovePos = new BlockPos(x, clearY, z);
                BlockState aboveState = level.getBlockState(abovePos);
                if (isClearableVegetation(aboveState) && level.getFluidState(abovePos).isEmpty()) {
                    level.destroyBlock(abovePos, true, citizen);
                }
            }

            int currentGroundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos currentGround = new BlockPos(x, currentGroundY, z);
            BlockState topState = level.getBlockState(currentGround);

            // If there is a tree or giant mushroom anywhere in the 10x10 plot, fell it first!
            if (topState.is(BlockTags.LOGS) || isMushroomBlock(topState)) {
                warehousePlotCursor = idx / 2;
                if (citizen.distanceToSqr(x + 0.5, warehouse.y(), z + 0.5) > 36.0) {
                    if (citizen.getNavigation().isDone()) {
                        citizen.getNavigation().moveTo(x + 0.5, warehouse.y(), z + 0.5, 1.0);
                    }
                    return;
                }
                clearConnectedTreeInPlot(citizen, level, currentGround);
                collectNearbyDrops(citizen, level);
                return;
            }

            // Cut down any elevated terrain (currentGroundY > targetGroundY) and capture the dirt directly!
            if (currentGroundY > targetGroundY && level.getFluidState(currentGround).isEmpty()) {
                if (topState.hasBlockEntity() || topState.getDestroySpeed(level, currentGround) < 0.0f) continue;
                if (isClearableVegetation(topState)) {
                    level.destroyBlock(currentGround, true, citizen);
                    continue;
                }
                warehousePlotCursor = idx / 2;
                boolean stone = isExposedStone(topState);
                if (stone && !equipPickaxe(citizen, level)) return;
                if (!stone) equipShovel(citizen, level);

                if (citizen.distanceToSqr(x + 0.5, currentGroundY, z + 0.5) > 36.0) {
                    if (citizen.getNavigation().isDone()) {
                        citizen.getNavigation().moveTo(x + 0.5, currentGroundY, z + 0.5, 1.0);
                    }
                    return;
                }
                citizen.getLookControl().setLookAt(currentGround.getX() + 0.5, currentGround.getY() + 0.5, currentGround.getZ() + 0.5);
                citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                boolean isDirtLike = topState.is(BlockTags.DIRT) || topState.is(Blocks.GRASS_BLOCK);
                if (level.destroyBlock(currentGround, !isDirtLike, citizen)) {
                    if (isDirtLike) {
                        citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
                    }
                    if (citizen.getMainHandItem().isDamageableItem()) {
                        citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                    }
                    collectNearbyDrops(citizen, level);
                }
                return;
            }
        }

        // PHASE 2: All high blocks and trees are cleared! Now fill any holes or water up to targetGroundY.
        for (int idx = 0; idx < 100; idx++) {
            int x = warehouse.x() - 5 + idx % 10;
            int z = warehouse.z() - 5 + idx / 10;
            BlockPos probe = new BlockPos(x, warehouse.y(), z);
            if (!citizen.isInsideNation(probe) || !level.hasChunkAt(probe)) continue;

            int currentGroundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos currentGround = new BlockPos(x, currentGroundY, z);
            boolean isFluid = !level.getFluidState(currentGround).isEmpty();

            if (isFluid || currentGroundY < targetGroundY) {
                warehousePlotCursor = 50 + (idx / 2);
                BlockPos fillPos = isFluid ? currentGround : new BlockPos(x, currentGroundY + 1, z);
                int requiredDirt = estimateRemainingPlotDirtNeeded(level, warehouse);
                if (!ensureDirt(citizen, level, fillPos, Math.max(1, requiredDirt))) {
                    return;
                }
                if (citizen.distanceToSqr(x + 0.5, warehouse.y(), z + 0.5) > 36.0) {
                    if (citizen.getNavigation().isDone()) {
                        citizen.getNavigation().moveTo(x + 0.5, warehouse.y(), z + 0.5, 1.0);
                    }
                    return;
                }
                if (citizen.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(fillPos))) {
                    citizen.teleportTo(citizen.getX(), citizen.getY() + 1.0, citizen.getZ());
                }
                citizen.getLookControl().setLookAt(fillPos.getX() + 0.5, fillPos.getY() + 0.5, fillPos.getZ() + 0.5);
                citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                placeBlockFromInventory(citizen, level, fillPos, Blocks.DIRT.defaultBlockState(), Items.DIRT);
                return;
            }

            if (!level.getBlockState(currentGround).isFaceSturdy(level, currentGround, Direction.UP)) {
                level.setBlockAndUpdate(currentGround, Blocks.DIRT.defaultBlockState());
            }
        }

        // PHASE 3: Pave the 10x10 warehouse floor with wood planks (any wood species available)!
        convertOwnLogsToPlanks(citizen);
        for (int idx = 0; idx < 100; idx++) {
            int x = warehouse.x() - 5 + idx % 10;
            int z = warehouse.z() - 5 + idx / 10;
            BlockPos floorPos = new BlockPos(x, targetGroundY, z);
            if (!citizen.isInsideNation(floorPos) || !level.hasChunkAt(floorPos)) continue;

            BlockState floorState = level.getBlockState(floorPos);
            if (floorState.is(BlockTags.PLANKS) || floorState.hasBlockEntity()) continue;

            warehousePlotCursor = 66 + (idx / 3);
            Item plankItem = findInventoryItem(citizen, ItemTags.PLANKS);
            if (plankItem == null) {
                int remainingPlanks = estimateRemainingPlotPlanksNeeded(level, warehouse);
                if (!ensureResource(citizen, level, Items.OAK_PLANKS, 1, Math.max(8, remainingPlanks))) {
                    return;
                }
                plankItem = findInventoryItem(citizen, ItemTags.PLANKS);
                if (plankItem == null) return;
            }

            if (citizen.distanceToSqr(x + 0.5, warehouse.y(), z + 0.5) > 36.0) {
                if (citizen.getNavigation().isDone()) {
                    citizen.getNavigation().moveTo(x + 0.5, warehouse.y(), z + 0.5, 1.0);
                }
                return;
            }

            Block plankBlock = Block.byItem(plankItem);
            if (plankBlock == Blocks.AIR) return;
            citizen.getLookControl().setLookAt(floorPos.getX() + 0.5, floorPos.getY() + 0.5, floorPos.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            placeOrReplaceBlockFromInventory(citizen, level, floorPos, plankBlock.defaultBlockState(), plankItem);
            return;
        }

        // All 100 cells are flat, clear, and paved with wood planks!
        warehousePlotCursor = 100;
        CivilizationData.get(level).markWarehousePlotPrepared(citizen.getNationId());
        preparingPlot = false;
        gatheringDirtBatch = false;
        dirtSourceTarget = null;
        citizen.announce("O piso de madeira 10x10 da warehouse está pronto; o Storager já pode posicionar os baús.");
    }

    private int estimateRemainingPlotPlanksNeeded(ServerLevel level, CivilizationData.Warehouse warehouse) {
        int needed = 0;
        int targetGroundY = warehouse.y() - 1;
        for (int idx = 0; idx < 100; idx++) {
            int x = warehouse.x() - 5 + idx % 10;
            int z = warehouse.z() - 5 + idx / 10;
            BlockPos floorPos = new BlockPos(x, targetGroundY, z);
            if (!level.hasChunkAt(floorPos)) continue;
            BlockState state = level.getBlockState(floorPos);
            if (!state.is(BlockTags.PLANKS) && !state.hasBlockEntity()) needed++;
        }
        return Math.min(64, Math.max(1, needed));
    }

    /**
     * Builds a direct 3-block-wide road connecting the Village Center plaza directly to the Warehouse border.
     * Handles both cardinal and L-shaped alignments seamlessly.
     */
    private void buildWarehouseConnectionRoad(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse) {
        java.util.List<BlockPos> pathCells = computeWarehouseConnectionCells(warehouse);
        while (warehousePathStep < pathCells.size()) {
            BlockPos cellXZ = pathCells.get(warehousePathStep);
            if (!citizen.isInsideNation(cellXZ) || !level.hasChunkAt(cellXZ) || isInsidePlazaOrWarehouse(cellXZ, warehouse)) {
                warehousePathStep++;
                continue;
            }
            int surfaceY = getTrueRoadSurfaceY(level, cellXZ.getX(), cellXZ.getZ());
            BlockPos surface = new BlockPos(cellXZ.getX(), surfaceY, cellXZ.getZ());
            BlockPos ground = surface.below();
            BlockState groundState = level.getBlockState(ground);

            // Skip if already part of the plaza, warehouse wood floor, or an existing dirt path / bridge / stair
            if (groundState.is(Blocks.DIRT_PATH) || groundState.is(BlockTags.PLANKS) || groundState.is(BlockTags.STAIRS)) {
                warehousePathStep++;
                continue;
            }

            double horizDistSqr = citizen.distanceToSqr(cellXZ.getX() + 0.5, citizen.getY(), cellXZ.getZ() + 0.5);
            if (horizDistSqr > 25.0) {
                if (citizen.getNavigation().isDone()) {
                    citizen.getNavigation().moveTo(surface.getX() + 0.5, citizen.getY(), surface.getZ() + 0.5, 0.95);
                }
                return;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int checkY = surfaceY - 1; checkY <= surfaceY + 4; checkY++) {
                        BlockPos checkPos = new BlockPos(cellXZ.getX() + dx, checkY, cellXZ.getZ() + dz);
                        if (!citizen.isInsideNation(checkPos) || !level.hasChunkAt(checkPos)) continue;
                        BlockState checkState = level.getBlockState(checkPos);
                        if (checkState.is(BlockTags.LOGS) || isMushroomBlock(checkState)) {
                            clearConnectedTreeInPlot(citizen, level, checkPos);
                            collectNearbyDrops(citizen, level);
                            return;
                        }
                    }
                }
            }

            for (int clearY = surfaceY; clearY <= surfaceY + 4; clearY++) {
                BlockPos clearPos = new BlockPos(cellXZ.getX(), clearY, cellXZ.getZ());
                BlockState clearState = level.getBlockState(clearPos);
                if (!clearState.isAir() && isClearableVegetation(clearState) && level.getFluidState(clearPos).isEmpty()) {
                    level.destroyBlock(clearPos, true, citizen);
                }
            }

            surfaceY = getTrueRoadSurfaceY(level, cellXZ.getX(), cellXZ.getZ());
            surface = new BlockPos(cellXZ.getX(), surfaceY, cellXZ.getZ());
            ground = surface.below();
            groundState = level.getBlockState(ground);

            if (isWater(level, ground)) {
                convertOwnLogsToPlanks(citizen);
                Item plank = findInventoryItem(citizen, ItemTags.PLANKS);
                if (plank == null) {
                    if (!ensureResource(citizen, level, Items.OAK_PLANKS, 1, 16)) return;
                    plank = findInventoryItem(citizen, ItemTags.PLANKS);
                    if (plank == null) return;
                }
                Block plankBlock = Block.byItem(plank);
                if (plankBlock != Blocks.AIR && placeOrReplaceBlockFromInventory(citizen, level, ground, plankBlock.defaultBlockState(), plank)) {
                    warehousePathStep++;
                }
                return;
            }

            if (!equipShovel(citizen, level)) return;
            citizen.getLookControl().setLookAt(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState())) {
                if (!level.getBlockState(surface).isAir() && isClearableVegetation(level.getBlockState(surface))) {
                    level.destroyBlock(surface, true, citizen);
                }
                if (citizen.getMainHandItem().isDamageableItem()) {
                    citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                }
            }
            warehousePathStep++;
            return;
        }

        warehousePathDone = true;
        citizen.announce("Conectei o caminho principal do centro da vila até a entrada da Warehouse!");
    }

    private java.util.List<BlockPos> computeWarehouseConnectionCells(CivilizationData.Warehouse warehouse) {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        int cx = townCenter.getX();
        int cz = townCenter.getZ();
        int wx = warehouse.x();
        int wz = warehouse.z();

        int dx = wx - cx;
        int dz = wz - cz;
        int stepX = Integer.compare(dx, 0);
        int stepZ = Integer.compare(dz, 0);

        // Segment 1: along X from townCenter.x to warehouse.x (3 blocks wide on Z around cz)
        if (stepX != 0) {
            for (int x = cx; x != wx + stepX; x += stepX) {
                for (int lane = -1; lane <= 1; lane++) {
                    cells.add(new BlockPos(x, townCenter.getY(), cz + lane));
                }
            }
        }
        // Segment 2: along Z from townCenter.z to warehouse.z (3 blocks wide on X around wx)
        if (stepZ != 0) {
            for (int z = cz; z != wz + stepZ; z += stepZ) {
                for (int lane = -1; lane <= 1; lane++) {
                    cells.add(new BlockPos(wx + lane, townCenter.getY(), z));
                }
            }
        }
        return cells;
    }

    private int estimateRemainingPlotDirtNeeded(ServerLevel level, CivilizationData.Warehouse warehouse) {
        int needed = 0;
        int targetGroundY = warehouse.y() - 1;
        for (int idx = warehousePlotCursor; idx < 100; idx++) {
            int x = warehouse.x() - 5 + idx % 10;
            int z = warehouse.z() - 5 + idx / 10;
            BlockPos probe = new BlockPos(x, warehouse.y(), z);
            if (!level.hasChunkAt(probe)) continue;
            int currentGroundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos currentGround = new BlockPos(x, currentGroundY, z);
            if (level.getFluidState(currentGround).is(FluidTags.WATER)) needed++;
            if (currentGroundY < targetGroundY) {
                needed += (targetGroundY - currentGroundY);
            }
        }
        return Math.min(64, Math.max(1, needed));
    }

    private void buildVillageCenter(CitizenEntity citizen, ServerLevel level) {
        GridBlueprint blueprint = villageCenterBlueprint(level);
        int totalCells = blueprint.width() * blueprint.depth();
        if (plazaCursor >= totalCells) {
            citizen.announce("Concluí a praça de madeira ao redor do estandarte da vila.");
            return;
        }

        // Convert any logs the Builder collected while clearing trees into planks automatically!
        convertOwnLogsToPlanks(citizen);

        while (plazaCursor < totalCells) {
            char cell = blueprint.cellAt(plazaCursor % blueprint.width(), plazaCursor / blueprint.width());
            if (cell != 'P') {
                advancePlazaCursor(citizen, totalCells);
                continue;
            }

            BlockPos floor = plazaPosition(blueprint, plazaCursor).below();
            BlockPos standingPosition = floor.above();
            if (!citizen.isInsideNation(floor)) {
                advancePlazaCursor(citizen, totalCells);
                continue;
            }
            if (!level.hasChunkAt(floor)) return;
            if (citizen.distanceToSqr(standingPosition.getX() + 0.5, standingPosition.getY(),
                standingPosition.getZ() + 0.5) > 36.0) {
                if (citizen.getNavigation().isDone()) {
                    int navY = getTrueRoadSurfaceY(level, standingPosition.getX(), standingPosition.getZ());
                    citizen.getNavigation().moveTo(standingPosition.getX() + 0.5, navY,
                        standingPosition.getZ() + 0.5, 1.0);
                }
                return;
            }

            // Fill any depression below floor so the 7x5 plaza is solid and doesn't float over holes/slopes
            for (int fillY = floor.getY() - 2; fillY < floor.getY(); fillY++) {
                BlockPos underPos = new BlockPos(floor.getX(), fillY, floor.getZ());
                BlockState underState = level.getBlockState(underPos);
                if (underState.isAir() || underState.canBeReplaced() || isClearableVegetation(underState)) {
                    level.setBlockAndUpdate(underPos, Blocks.DIRT.defaultBlockState());
                }
            }

            // Clear any trees or vegetation above the plaza cell so the 7x5 plaza is completely open and level!
            for (int clearY = standingPosition.getY(); clearY <= standingPosition.getY() + 4; clearY++) {
                BlockPos abovePos = new BlockPos(floor.getX(), clearY, floor.getZ());
                BlockState aboveState = level.getBlockState(abovePos);
                if (aboveState.is(BlockTags.LOGS) || isMushroomBlock(aboveState)) {
                    clearConnectedTreeInPlot(citizen, level, abovePos);
                    return;
                }
                if (!aboveState.isAir() && (isClearableVegetation(aboveState) || isNaturalPlazaGround(aboveState))
                    && !aboveState.hasBlockEntity()) {
                    boolean isDirtLike = aboveState.is(BlockTags.DIRT) || aboveState.is(Blocks.GRASS_BLOCK);
                    if (level.destroyBlock(abovePos, !isDirtLike, citizen) && isDirtLike) {
                        citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
                    }
                }
            }

            BlockState ground = level.getBlockState(floor);
            if (ground.is(BlockTags.LOGS) || isMushroomBlock(ground)) {
                clearConnectedTreeInPlot(citizen, level, floor);
                return;
            }
            if (ground.is(BlockTags.PLANKS)) {
                advancePlazaCursor(citizen, totalCells);
                continue;
            }
            BlockState aboveFloor = level.getBlockState(standingPosition);
            if (ground.hasBlockEntity() || (!aboveFloor.isAir() && !aboveFloor.canBeReplaced())) {
                advancePlazaCursor(citizen, totalCells);
                continue;
            }
            Item plankItem = findInventoryItem(citizen, ItemTags.PLANKS);
            if (plankItem == null) {
                if (!ensureResource(citizen, level, Items.OAK_PLANKS, 1, 34)) return;
                plankItem = findInventoryItem(citizen, ItemTags.PLANKS);
                if (plankItem == null) return;
            }
            Block plankBlock = Block.byItem(plankItem);
            if (plankBlock == Blocks.AIR) return;
            citizen.getLookControl().setLookAt(floor.getX() + 0.5, floor.getY() + 0.5, floor.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!placeOrReplaceBlockFromInventory(citizen, level, floor, plankBlock.defaultBlockState(), plankItem)) return;
            advancePlazaCursor(citizen, totalCells);
            return;
        }
    }

    private void convertOwnLogsToPlanks(CitizenEntity citizen) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (!stack.is(ItemTags.LOGS)) continue;
            net.minecraft.resources.ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = key.getPath();
            if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
            if (path.endsWith("_log")) path = path.substring(0, path.length() - "_log".length());
            else if (path.endsWith("_wood")) path = path.substring(0, path.length() - "_wood".length());
            else if (path.endsWith("_stem")) path = path.substring(0, path.length() - "_stem".length());
            else if (path.endsWith("_hyphae")) path = path.substring(0, path.length() - "_hyphae".length());
            Item planks = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(key.getNamespace(), path + "_planks"));
            if (planks == Items.AIR) planks = Items.OAK_PLANKS;
            while (!stack.isEmpty() && citizen.getNpcInventory().canAddItem(new ItemStack(planks, 4))) {
                stack.shrink(1);
                citizen.getNpcInventory().addItem(new ItemStack(planks, 4));
            }
            if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
        }
        citizen.getNpcInventory().setChanged();
    }

    private void advancePlazaCursor(CitizenEntity citizen, int totalCells) {
        plazaCursor++;
        if (plazaCursor >= totalCells) citizen.announce("Concluí a praça de madeira ao redor do estandarte da vila.");
    }

    private BlockPos plazaPosition(GridBlueprint blueprint, int cursor) {
        int column = cursor % blueprint.width();
        int row = cursor / blueprint.width();
        return new BlockPos(townCenter.getX() + column - blueprint.width() / 2, townCenter.getY(),
            townCenter.getZ() + row - blueprint.depth() / 2);
    }

    private static GridBlueprint villageCenterBlueprint(ServerLevel level) {
        if (VILLAGE_CENTER_BLUEPRINT == null)
            VILLAGE_CENTER_BLUEPRINT = GridBlueprint.load(level, "village_center_7x5");
        return VILLAGE_CENTER_BLUEPRINT;
    }

    private static boolean isNaturalPlazaGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK)
            || state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private void buildBridgeCell(CitizenEntity citizen, ServerLevel level, BlockPos deck, Direction direction) {
        convertOwnLogsToPlanks(citizen);
        Item plank = findInventoryItem(citizen, ItemTags.PLANKS);
        if (plank == null) {
            if (!ensureResource(citizen, level, Items.OAK_PLANKS, 1, 16)) return;
            plank = findInventoryItem(citizen, ItemTags.PLANKS);
            if (plank == null) return;
        }
        pendingResourceId = "";
        Block block = Block.byItem(plank);
        if (block == Blocks.AIR) return;
        if (!level.getBlockState(deck).isAir() && !level.getBlockState(deck).canBeReplaced()) return;
        if (!level.setBlockAndUpdate(deck, block.defaultBlockState())) return;
        consumeItem(citizen, plank);
        supportBridge(citizen, level, deck, plank);
        previousSurfaceY[laneIndex] = deck.getY();
        advanceLane();
    }

    private void supportBridge(CitizenEntity citizen, ServerLevel level, BlockPos deck, Item plank) {
        if (laneIndex != 1) return;
        BlockPos support = deck.below();
        int depth = 0;
        while (depth < 6 && level.hasChunkAt(support) && level.getFluidState(support).is(FluidTags.WATER)) {
            if (!consumeItem(citizen, plank)) return;
            level.setBlockAndUpdate(support, Block.byItem(plank).defaultBlockState());
            support = support.below();
            depth++;
        }
    }

    private boolean placeStairIfNeeded(CitizenEntity citizen, ServerLevel level, BlockPos target, Direction direction) {
        Item stair = findStair(citizen);
        if (stair == null) {
            if (!ensureResource(citizen, level, Items.OAK_STAIRS, 1, 4)) return false;
            stair = findStair(citizen);
            if (stair == null) return false;
        }
        Block block = Block.byItem(stair);
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(StairBlock.FACING)) state = state.setValue(StairBlock.FACING, direction);
        if (level.getBlockState(target).isAir() || level.getBlockState(target).canBeReplaced()) {
            if (!consumeItem(citizen, stair)) return false;
            level.setBlockAndUpdate(target, state);
            stairRequestSent = false;
            return true;
        }
        return false;
    }

    private boolean equipShovel(CitizenEntity citizen, ServerLevel level) {
        if (citizen.getMainHandItem().is(ItemTags.SHOVELS)) return true;
        int slot = findInventorySlot(citizen, ItemTags.SHOVELS);
        if (slot >= 0) {
            ItemStack oldHand = citizen.getMainHandItem().copy();
            if (!oldHand.isEmpty() && !citizen.getNpcInventory().canAddItem(oldHand)) return false;
            ItemStack shovel = citizen.getNpcInventory().removeItem(slot, 1);
            if (!oldHand.isEmpty()) citizen.getNpcInventory().addItem(oldHand);
            citizen.setItemSlot(EquipmentSlot.MAINHAND, shovel);
            shovelRequestSent = false;
            return true;
        }
        if (StorageJob.retrieveBestShovel(citizen, level)) return false;
        if (!shovelRequestSent) {
            CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
                StorageJob.BEST_SHOVEL_REQUEST_ID, 1);
            citizen.announce("Estou sem pá; solicitei uma ao Storager para abrir os caminhos.");
            shovelRequestSent = true;
        }
        return false;
    }

    private boolean equipPickaxe(CitizenEntity citizen, ServerLevel level) {
        if (citizen.getMainHandItem().is(ItemTags.PICKAXES)) return true;
        int slot = findInventorySlot(citizen, ItemTags.PICKAXES);
        if (slot >= 0) {
            ItemStack oldHand = citizen.getMainHandItem().copy();
            if (!oldHand.isEmpty() && !citizen.getNpcInventory().canAddItem(oldHand)) return false;
            ItemStack pickaxe = citizen.getNpcInventory().removeItem(slot, 1);
            if (!oldHand.isEmpty()) citizen.getNpcInventory().addItem(oldHand);
            citizen.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
            pickaxeRequestSent = false;
            return true;
        }
        if (StorageJob.retrieveBestPickaxe(citizen, level)) return false;
        if (!pickaxeRequestSent) {
            CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
                StorageJob.BEST_PICKAXE_REQUEST_ID, 1);
            citizen.announce("Encontrei pedra exposta; pedi uma picareta ao Storager antes de removê-la.");
            pickaxeRequestSent = true;
        }
        return false;
    }

    private boolean ensureResource(CitizenEntity citizen, ServerLevel level, Item item, int amount) {
        return ensureResource(citizen, level, item, amount, amount);
    }

    private boolean ensureDirt(CitizenEntity citizen, ServerLevel level, BlockPos workPosition) {
        return ensureDirt(citizen, level, workPosition, 16);
    }

    private boolean ensureDirt(CitizenEntity citizen, ServerLevel level, BlockPos workPosition, int desiredBatch) {
        CivilizationData data = CivilizationData.get(level);
        String dirtRequestId = StorageJob.resourceRequestId(Items.DIRT);
        int currentDirt = countItem(citizen, Items.DIRT);

        // If we are not in the middle of gathering a batch and already have at least 1 dirt, or we reached our batch goal:
        if (currentDirt > 0 && (!gatheringDirtBatch || currentDirt >= dirtBatchTargetCount)) {
            gatheringDirtBatch = false;
            cancelDirtRequest(citizen, data, dirtRequestId);
            pendingResourceId = "";
            dirtSearchAnnounced = false;
            dirtSourceTarget = null;
            return true;
        }

        int batchToGather = Math.min(64, Math.max(8, desiredBatch));
        if (!gatheringDirtBatch) {
            gatheringDirtBatch = true;
            dirtBatchTargetCount = batchToGather;
        }

        if (!hasOpenResourceRequest(data, citizen, dirtRequestId)) {
            pendingResourceId = BuiltInRegistries.ITEM.getKey(Items.DIRT).toString();
            requestResource(citizen, level, Items.DIRT, dirtBatchTargetCount);
        }
        if (StorageJob.retrieveRequestedItem(citizen, level, Items.DIRT, dirtBatchTargetCount)) {
            if (countItem(citizen, Items.DIRT) > 0) {
                gatheringDirtBatch = false;
                cancelDirtRequest(citizen, data, dirtRequestId);
                pendingResourceId = "";
                dirtSearchAnnounced = false;
                dirtSourceTarget = null;
                return true;
            }
            return false;
        }

        // First try to dig with shovel; if we don't have one yet, request it, or dig dirt by hand if Storager can't craft yet
        boolean hasShovel = equipShovel(citizen, level);

        DirtSource source = findDirtSource(citizen, level, workPosition);
        if (source == null) {
            dirtSourceTarget = null;
            // If we already collected at least some dirt and can't find more nearby, use what we have!
            if (currentDirt > 0) {
                gatheringDirtBatch = false;
                return true;
            }
            return false;
        }
        if (!dirtSearchAnnounced) {
            citizen.announce("O Storager não tem terra no estoque; vou coletar " + dirtBatchTargetCount
                + " blocos de terra nos arredores para nivelar o terreno.");
            dirtSearchAnnounced = true;
        }
        double distSqr = citizen.distanceToSqr(source.standPosition().getX() + 0.5, source.standPosition().getY(),
            source.standPosition().getZ() + 0.5);
        if (distSqr > 12.25) {
            if (dirtSourceTarget == null || !dirtSourceTarget.equals(source.position()) || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(source.standPosition().getX() + 0.5, source.standPosition().getY(),
                    source.standPosition().getZ() + 0.5, 1.0);
                dirtSourceTarget = source.position().immutable();
            }
            return false;
        }

        citizen.getLookControl().setLookAt(source.position().getX() + 0.5, source.position().getY() + 0.5,
            source.position().getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        BlockPos aboveSource = source.position().above();
        if (!level.getBlockState(aboveSource).isAir() && isClearableVegetation(level.getBlockState(aboveSource))) {
            level.destroyBlock(aboveSource, true, citizen);
        }
        if (level.destroyBlock(source.position(), false, citizen)) {
            citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
            if (hasShovel) {
                citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            }
            collectNearbyDrops(citizen, level);
            dirtSourceTarget = null;
        }
        if (countItem(citizen, Items.DIRT) >= dirtBatchTargetCount) {
            gatheringDirtBatch = false;
            cancelDirtRequest(citizen, data, dirtRequestId);
            pendingResourceId = "";
            dirtSearchAnnounced = false;
            return true;
        }
        return false;
    }

    private DirtSource findDirtSource(CitizenEntity citizen, ServerLevel level, BlockPos workPosition) {
        BlockPos origin = citizen.blockPosition();
        DirtSource closest = null;
        double closestScore = Double.MAX_VALUE;
        int searchRadius = 32;
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        int preferredMinY = warehouse != null ? warehouse.y() : origin.getY();

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                if (dx * dx + dz * dz > searchRadius * searchRadius) continue;
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos columnProbe = new BlockPos(x, origin.getY(), z);
                if (!level.hasChunkAt(columnProbe)) continue;

                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos source = new BlockPos(x, y, z);
                if (source.equals(workPosition) || source.distSqr(workPosition) <= 2.0
                    || !citizen.isInsideNation(source)
                    || isReservedConstructionPosition(source, citizen, level)) continue;

                BlockState state = level.getBlockState(source);
                if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL))) continue;
                BlockState above = level.getBlockState(source.above());
                if (!above.isAir() && !above.canBeReplaced() && !isClearableVegetation(above)) continue;

                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos stand = source.relative(direction).above();
                    if (!level.hasChunkAt(stand) || !citizen.isInsideNation(stand)
                        || (!level.getBlockState(stand).canBeReplaced() && !isClearableVegetation(level.getBlockState(stand)))
                        || (!level.getBlockState(stand.above()).canBeReplaced() && !isClearableVegetation(level.getBlockState(stand.above())))
                        || !level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) continue;
                    double distance = stand.distSqr(origin);
                    // Prefer digging higher ground (hills) rather than making deep holes below warehouse level
                    double elevationBonus = y >= preferredMinY ? 0.0 : 64.0;
                    double score = distance + elevationBonus;
                    if (score < closestScore) {
                        closestScore = score;
                        closest = new DirtSource(source, stand);
                    }
                }
            }
        }
        return closest;
    }

    private boolean isReservedConstructionPosition(BlockPos position, CitizenEntity citizen, ServerLevel level) {
        if (townCenter != null) {
            int dx = Math.abs(position.getX() - townCenter.getX());
            int dz = Math.abs(position.getZ() - townCenter.getZ());
            if (dx <= 3 && dz <= 2) return true;
            if ((dx <= 1 && dz <= CivilizationData.NATION_RADIUS_BLOCKS)
                || (dz <= 1 && dx <= CivilizationData.NATION_RADIUS_BLOCKS)) return true;
        }
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        return warehouse != null && Math.abs(position.getX() - warehouse.x()) <= 5
            && Math.abs(position.getZ() - warehouse.z()) <= 5;
    }

    private void cancelDirtRequest(CitizenEntity citizen, CivilizationData data, String dirtRequestId) {
        for (CivilizationData.StorageRequest request : data.openStorageRequests(citizen.getNationId())) {
            if (request.requesterId().equals(citizen.getUUID()) && request.itemId().equals(dirtRequestId))
                data.fulfillStorageRequest(request.id());
        }
    }

    private record DirtSource(BlockPos position, BlockPos standPosition) { }

    private boolean ensureResource(CitizenEntity citizen, ServerLevel level, Item item, int amount, int requestAmount) {
        convertOwnLogsToPlanks(citizen);
        boolean isPlanks = new ItemStack(item).is(ItemTags.PLANKS);
        boolean isStairs = new ItemStack(item).is(ItemTags.STAIRS);
        int have = isPlanks ? countTaggedItem(citizen, ItemTags.PLANKS)
            : (isStairs ? countTaggedItem(citizen, ItemTags.STAIRS) : countItem(citizen, item));
        CivilizationData data = CivilizationData.get(level);
        String reqId = StorageJob.resourceRequestId(item);
        if (have >= amount) {
            pendingResourceId = "";
            cancelDirtRequest(citizen, data, reqId);
            return true;
        }
        if (!hasOpenResourceRequest(data, citizen, reqId)) {
            pendingResourceId = BuiltInRegistries.ITEM.getKey(item).toString();
            requestResource(citizen, level, item, requestAmount);
        }
        StorageJob.retrieveRequestedItem(citizen, level, item, requestAmount);
        int haveNow = isPlanks ? countTaggedItem(citizen, ItemTags.PLANKS)
            : (isStairs ? countTaggedItem(citizen, ItemTags.STAIRS) : countItem(citizen, item));
        if (haveNow >= amount) {
            pendingResourceId = "";
            cancelDirtRequest(citizen, data, reqId);
            return true;
        }
        return false;
    }

    private boolean hasOpenResourceRequest(CivilizationData data, CitizenEntity citizen, String requestId) {
        for (CivilizationData.StorageRequest request : data.openStorageRequests(citizen.getNationId())) {
            if (request.requesterId().equals(citizen.getUUID()) && request.itemId().equals(requestId)) {
                return true;
            }
        }
        return false;
    }

    private static int countTaggedItem(CitizenEntity citizen, net.minecraft.tags.TagKey<Item> tag) {
        int count = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(tag)) count += stack.getCount();
        }
        return count;
    }

    private void requestResource(CitizenEntity citizen, ServerLevel level, Item item, int amount) {
        CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
            StorageJob.resourceRequestId(item), amount);
        citizen.announce("Pedi " + amount + " " + BuiltInRegistries.ITEM.getKey(item) + " ao Storager para continuar a obra.");
    }

    private BlockPos roadPosition(Direction direction, int forward, int side) {
        return switch (direction) {
            case EAST -> townCenter.offset(forward, 0, side);
            case SOUTH -> townCenter.offset(side, 0, forward);
            case WEST -> townCenter.offset(-forward, 0, side);
            case NORTH -> townCenter.offset(side, 0, -forward);
            default -> townCenter;
        };
    }

    private void advanceLane() {
        laneIndex++;
        if (laneIndex >= ROAD_WIDTH) {
            laneIndex = 0;
            distance++;
            if (distance > CivilizationData.NATION_RADIUS_BLOCKS) {
                directionIndex++;
                distance = 1;
                previousSurfaceY = new int[ROAD_WIDTH];
            }
        }
    }

    private String directionName() {
        if (directionIndex >= ROAD_DIRECTIONS.length) return "concluído";
        return switch (ROAD_DIRECTIONS[directionIndex]) {
            case EAST -> "leste";
            case SOUTH -> "sul";
            case WEST -> "oeste";
            default -> "norte";
        };
    }

    private boolean isWater(ServerLevel level, BlockPos ground) {
        return level.getFluidState(ground).is(FluidTags.WATER);
    }

    private static boolean isMushroomBlock(BlockState state) {
        return state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM);
    }

    public static boolean isClearableVegetation(BlockState state) {
        if (state.isAir()) return false;
        return state.canBeReplaced()
            || state.is(BlockTags.FLOWERS)
            || state.is(BlockTags.SAPLINGS)
            || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.CROPS)
            || state.is(BlockTags.REPLACEABLE)
            || state.is(BlockTags.REPLACEABLE_BY_TREES)
            || state.is(Blocks.LEAF_LITTER)
            || state.is(Blocks.WILDFLOWERS)
            || state.is(Blocks.BUSH)
            || state.is(Blocks.FIREFLY_BUSH)
            || state.is(Blocks.SHORT_DRY_GRASS)
            || state.is(Blocks.TALL_DRY_GRASS)
            || state.is(Blocks.CACTUS_FLOWER)
            || state.is(Blocks.BROWN_MUSHROOM)
            || state.is(Blocks.RED_MUSHROOM)
            || state.is(Blocks.CRIMSON_FUNGUS)
            || state.is(Blocks.WARPED_FUNGUS)
            || state.is(Blocks.DEAD_BUSH)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.BAMBOO)
            || state.is(Blocks.BAMBOO_SAPLING)
            || state.is(Blocks.SUGAR_CANE)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.PUMPKIN)
            || state.is(Blocks.MELON)
            || state.is(Blocks.MOSS_CARPET)
            || state.is(Blocks.PINK_PETALS)
            || state.is(Blocks.SNOW);
    }

    private void clearConnectedTreeInPlot(CitizenEntity citizen, ServerLevel level, BlockPos start) {
        java.util.Set<BlockPos> logs = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start.immutable());
        logs.add(start.immutable());

        while (!queue.isEmpty() && logs.size() < 128) {
            BlockPos current = queue.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (logs.contains(neighbor) || !citizen.isInsideNation(neighbor)) continue;
                        BlockState neighborState = level.getBlockState(neighbor);
                        if (neighborState.is(BlockTags.LOGS) || isMushroomBlock(neighborState)) {
                            logs.add(neighbor.immutable());
                            queue.addLast(neighbor.immutable());
                        }
                    }
                }
            }
        }

        java.util.Set<BlockPos> leaves = new java.util.HashSet<>();
        for (BlockPos logPos : logs) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos leafPos = logPos.offset(dx, dy, dz);
                        if (citizen.isInsideNation(leafPos) && level.getBlockState(leafPos).is(BlockTags.LEAVES)) {
                            leaves.add(leafPos.immutable());
                        }
                    }
                }
            }
            BlockState logState = level.getBlockState(logPos);
            Item logItem = logState.getBlock().asItem();
            if (level.destroyBlock(logPos, false, citizen) && logItem != Items.AIR) {
                ItemStack rem = citizen.getNpcInventory().addItem(new ItemStack(logItem, 1));
                if (!rem.isEmpty()) Block.popResource(level, logPos, rem);
            }
        }
        for (BlockPos leafPos : leaves) {
            level.destroyBlock(leafPos, true, citizen);
        }
        convertOwnLogsToPlanks(citizen);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        citizen.announce("Removi uma árvore (" + logs.size() + " troncos) que estava na área da obra.");
    }

    private static boolean isExposedStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SANDSTONE) || state.is(BlockTags.TERRACOTTA);
    }

    private static int findInventorySlot(CitizenEntity citizen, net.minecraft.tags.TagKey<Item> tag) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++)
            if (citizen.getNpcInventory().getItem(slot).is(tag)) return slot;
        return -1;
    }

    private static Item findInventoryItem(CitizenEntity citizen, net.minecraft.tags.TagKey<Item> tag) {
        int slot = findInventorySlot(citizen, tag);
        return slot < 0 ? null : citizen.getNpcInventory().getItem(slot).getItem();
    }

    private static Item findStair(CitizenEntity citizen) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.STAIRS)) return stack.getItem();
        }
        return null;
    }

    private static int countItem(CitizenEntity citizen, Item item) {
        int count = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean placeBlockFromInventory(CitizenEntity citizen, ServerLevel level, BlockPos pos,
                                                   BlockState state, Item item) {
        int slot = -1;
        for (int index = 0; index < citizen.getNpcInventory().getContainerSize(); index++) {
            if (citizen.getNpcInventory().getItem(index).is(item)) { slot = index; break; }
        }
        if (slot < 0) return false;
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && !existing.canBeReplaced() && !isClearableVegetation(existing)) return false;
        if (!existing.isAir() && isClearableVegetation(existing)) {
            level.destroyBlock(pos, true, citizen);
        }
        if (!level.setBlockAndUpdate(pos, state)) return false;
        ItemStack stack = citizen.getNpcInventory().getItem(slot);
        stack.shrink(1);
        if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
        citizen.getNpcInventory().setChanged();
        return true;
    }

    /** Replaces an existing solid ground block (like dirt/grass in the village plaza) with a block from inventory. */
    private static boolean placeOrReplaceBlockFromInventory(CitizenEntity citizen, ServerLevel level, BlockPos pos,
                                                            BlockState state, Item item) {
        int slot = -1;
        for (int index = 0; index < citizen.getNpcInventory().getContainerSize(); index++) {
            if (citizen.getNpcInventory().getItem(index).is(item)) { slot = index; break; }
        }
        if (slot < 0) return false;
        if (!level.setBlockAndUpdate(pos, state)) return false;
        ItemStack stack = citizen.getNpcInventory().getItem(slot);
        stack.shrink(1);
        if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
        citizen.getNpcInventory().setChanged();
        return true;
    }

    private static boolean consumeItem(CitizenEntity citizen, Item item) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (!stack.is(item)) continue;
            stack.shrink(1);
            if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
            citizen.getNpcInventory().setChanged();
            return true;
        }
        return false;
    }

    private void collectNearbyDrops(CitizenEntity citizen, ServerLevel level) {
        var area = citizen.getBoundingBox().inflate(3.0, 2.0, 3.0);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area,
            entity -> citizen.isInsideNation(entity.blockPosition()))) {
            ItemStack remainder = citizen.getNpcInventory().addItem(drop.getItem().copy());
            if (remainder.isEmpty()) drop.discard();
            else drop.setItem(remainder);
        }
    }
}
