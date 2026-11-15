package com.aicivilization.profession;

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
public final class BuilderJob {
    private static final int TICK_INTERVAL = 10;
    private static final int ROAD_WIDTH = 3;
    private static final int[] LANE_OFFSETS = {-1, 0, 1};
    private static final Direction[] ROAD_DIRECTIONS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
    private int cooldown;
    private BlockPos townCenter;
    private int directionIndex;
    private int distance = 1;
    private int laneIndex;
    private int warehousePlotCursor;
    private int[] previousSurfaceY = new int[ROAD_WIDTH];
    private String pendingResourceId = "";
    private boolean shovelRequestSent;
    private boolean pickaxeRequestSent;
    private boolean stairRequestSent;
    private boolean completed;
    private boolean preparingPlot;

    public void start(CitizenEntity citizen, BlockPos center) {
        townCenter = center.immutable();
        directionIndex = 0;
        distance = 1;
        laneIndex = 0;
        warehousePlotCursor = 0;
        previousSurfaceY = new int[ROAD_WIDTH];
        pendingResourceId = "";
        shovelRequestSent = false;
        pickaxeRequestSent = false;
        stairRequestSent = false;
        completed = false;
        preparingPlot = false;
        cooldown = 0;
        citizen.announce("Vou abrir e reparar caminhos de terra nas quatro direções da vila até as fronteiras.");
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
        output.putIntArray("BuilderPreviousSurfaceY", previousSurfaceY);
        output.putString("BuilderPendingResource", pendingResourceId);
        output.putBoolean("BuilderShovelRequestSent", shovelRequestSent);
        output.putBoolean("BuilderPickaxeRequestSent", pickaxeRequestSent);
        output.putBoolean("BuilderStairRequestSent", stairRequestSent);
        output.putBoolean("BuilderCompleted", completed);
        output.putBoolean("BuilderPreparingPlot", preparingPlot);
    }

    public void load(ValueInput input) {
        townCenter = input.read("BuilderCenter", BlockPos.CODEC).orElse(null);
        directionIndex = Math.max(0, Math.min(ROAD_DIRECTIONS.length - 1, input.getIntOr("BuilderDirection", 0)));
        distance = Math.max(1, input.getIntOr("BuilderDistance", 1));
        laneIndex = Math.max(0, Math.min(ROAD_WIDTH - 1, input.getIntOr("BuilderLane", 0)));
        warehousePlotCursor = Math.max(0, Math.min(100, input.getIntOr("BuilderWarehousePlotCursor", 0)));
        previousSurfaceY = input.getIntArray("BuilderPreviousSurfaceY").filter(values -> values.length == ROAD_WIDTH)
            .orElse(new int[ROAD_WIDTH]);
        pendingResourceId = input.getStringOr("BuilderPendingResource", "");
        shovelRequestSent = input.getBooleanOr("BuilderShovelRequestSent", false);
        pickaxeRequestSent = input.getBooleanOr("BuilderPickaxeRequestSent", false);
        stairRequestSent = input.getBooleanOr("BuilderStairRequestSent", false);
        completed = input.getBooleanOr("BuilderCompleted", false);
        preparingPlot = input.getBooleanOr("BuilderPreparingPlot", false);
    }

    public boolean isActive() {
        return townCenter != null && !completed;
    }

    public boolean isInitialized() { return townCenter != null; }

    public String workStatus() {
        if (townCenter == null) return "Planejando os caminhos da vila";
        if (preparingPlot) return "Preparando terreno 10x10 para os baús: " + warehousePlotCursor + "%";
        if (completed) return "Os quatro caminhos até as fronteiras estão prontos";
        return "Construindo caminho " + directionName() + ", trecho " + distance + "/"
            + CivilizationData.NATION_RADIUS_BLOCKS + ", faixa " + (laneIndex + 1) + "/" + ROAD_WIDTH;
    }

    public String debugStatus() {
        return "active=" + isActive() + ", plot=" + warehousePlotCursor + "/100, direction=" + directionName() + ", distance=" + distance
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
            preparingPlot = true;
            if (cooldown-- > 0) return;
            cooldown = TICK_INTERVAL;
            prepareWarehousePlot(citizen, level, warehouse);
            return;
        }
        if (completed || cooldown-- > 0) return;
        cooldown = TICK_INTERVAL;
        collectNearbyDrops(citizen, level);
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
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
        BlockPos surface = new BlockPos(target.getX(), surfaceY, target.getZ());
        if (citizen.distanceToSqr(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5) > 16.0) {
            if (citizen.getNavigation().isDone())
                citizen.getNavigation().moveTo(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5, 0.9);
            return;
        }

        BlockPos ground = surface.below();
        if (!level.hasChunkAt(surface) || !citizen.isInsideNation(surface)) {
            advanceLane();
            return;
        }
        BlockState groundState = level.getBlockState(ground);
        if (groundState.is(Blocks.DIRT_PATH) || groundState.is(BlockTags.PLANKS) || groundState.is(BlockTags.STAIRS)) {
            previousSurfaceY[laneIndex] = surfaceY;
            advanceLane();
            return;
        }

        if (isWater(level, ground)) {
            buildBridgeCell(citizen, level, surface, direction);
            return;
        }

        if (isExposedStone(groundState)) {
            if (!equipPickaxe(citizen, level)) return;
            if (!ensureResource(citizen, level, Items.DIRT, 1)) return;
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!level.destroyBlock(ground, true, citizen)) return;
            BlockPos dirtPos = ground;
            if (!placeBlockFromInventory(citizen, level, dirtPos, Blocks.DIRT.defaultBlockState(), Items.DIRT)) return;
            citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            return;
        }

        if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
            if (!ensureResource(citizen, level, Items.DIRT, 1)) return;
            if (!placeBlockFromInventory(citizen, level, ground, Blocks.DIRT.defaultBlockState(), Items.DIRT)) return;
            return;
        }
        if (!equipShovel(citizen, level)) return;

        int elevation = previousSurfaceY[laneIndex] == 0 ? 0 : surfaceY - previousSurfaceY[laneIndex];
        if (elevation != 0) {
            if (!placeStairIfNeeded(citizen, level, surface, direction)) return;
            previousSurfaceY[laneIndex] = surfaceY;
            advanceLane();
            return;
        }
        if (!level.getBlockState(surface).isAir() && !level.getBlockState(surface).canBeReplaced()) {
            citizen.announce("Encontrei um obstáculo no traçado; vou aguardar para não apagar construções.");
            return;
        }
        citizen.getLookControl().setLookAt(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (level.setBlockAndUpdate(surface, Blocks.DIRT_PATH.defaultBlockState())) {
            citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            previousSurfaceY[laneIndex] = surfaceY;
            advanceLane();
        }
    }

    private void prepareWarehousePlot(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse) {
        if (warehousePlotCursor >= 100) {
            CivilizationData.get(level).markWarehousePlotPrepared(citizen.getNationId());
            preparingPlot = false;
            citizen.announce("O terreno plano de 10x10 da warehouse está pronto; o Storager já pode construir os baús.");
            return;
        }
        int x = warehouse.x() - 5 + warehousePlotCursor % 10;
        int z = warehouse.z() - 5 + warehousePlotCursor / 10;
        BlockPos probe = new BlockPos(x, warehouse.y(), z);
        if (!citizen.isInsideNation(probe) || !level.hasChunkAt(probe)) return;
        if (citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 16.0) {
            if (citizen.getNavigation().isDone())
                citizen.getNavigation().moveTo(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
            return;
        }
        int targetGroundY = warehouse.y() - 1;
        int currentGroundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos currentGround = new BlockPos(x, currentGroundY, z);
        if (level.getFluidState(currentGround).is(FluidTags.WATER)) {
            if (!ensureResource(citizen, level, Items.DIRT, 1, 64)) return;
            placeBlockFromInventory(citizen, level, currentGround, Blocks.DIRT.defaultBlockState(), Items.DIRT);
            return;
        }
        if (currentGroundY > targetGroundY) {
            BlockState state = level.getBlockState(currentGround);
            if (state.hasBlockEntity() || state.getDestroySpeed(level, currentGround) < 0.0f) return;
            boolean stone = isExposedStone(state);
            if (stone && !equipPickaxe(citizen, level)) return;
            if (!stone && !equipShovel(citizen, level)) return;
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level.destroyBlock(currentGround, true, citizen) && !stone)
                citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            return;
        }
        if (currentGroundY < targetGroundY) {
            if (!ensureResource(citizen, level, Items.DIRT, 1, 64)) return;
            BlockPos fill = new BlockPos(x, currentGroundY + 1, z);
            if (placeBlockFromInventory(citizen, level, fill, Blocks.DIRT.defaultBlockState(), Items.DIRT)) return;
            return;
        }
        warehousePlotCursor++;
        if (warehousePlotCursor % 10 == 0) citizen.announce("Preparei " + warehousePlotCursor + "% do terreno 10x10 da warehouse.");
        if (warehousePlotCursor >= 100) {
            CivilizationData.get(level).markWarehousePlotPrepared(citizen.getNationId());
            preparingPlot = false;
            citizen.announce("O terreno plano de 10x10 da warehouse está pronto; o Storager já pode construir os baús.");
        }
    }

    private void buildBridgeCell(CitizenEntity citizen, ServerLevel level, BlockPos deck, Direction direction) {
        Item plank = findInventoryItem(citizen, ItemTags.PLANKS);
        if (plank == null) {
            String oakPlanks = BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS).toString();
            if (!pendingResourceId.equals(oakPlanks)) {
                pendingResourceId = oakPlanks;
                requestResource(citizen, level, Items.OAK_PLANKS, 16);
            }
            StorageJob.retrieveRequestedItem(citizen, level, Items.OAK_PLANKS, 16);
            return;
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
            if (StorageJob.retrieveRequestedItem(citizen, level, Items.OAK_STAIRS, 4)) return false;
            if (!stairRequestSent) {
                Item requestItem = Items.OAK_STAIRS;
                requestResource(citizen, level, requestItem, 4);
                stairRequestSent = true;
            }
            return false;
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

    private boolean ensureResource(CitizenEntity citizen, ServerLevel level, Item item, int amount, int requestAmount) {
        if (countItem(citizen, item) >= amount) {
            pendingResourceId = "";
            return true;
        }
        if (!pendingResourceId.equals(BuiltInRegistries.ITEM.getKey(item).toString())) {
            pendingResourceId = BuiltInRegistries.ITEM.getKey(item).toString();
            requestResource(citizen, level, item, requestAmount);
        }
        StorageJob.retrieveRequestedItem(citizen, level, item, requestAmount);
        return false;
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

    private static boolean isExposedStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.COBBLED_DEEPSLATE);
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
        if (slot < 0 || (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced())) return false;
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
