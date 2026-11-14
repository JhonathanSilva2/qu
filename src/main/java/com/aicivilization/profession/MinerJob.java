package com.aicivilization.profession;

import com.aicivilization.data.CivilizationData;
import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.storage.StorageJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Autonomous seven-wide descending mine with water sealing and lava avoidance. */
public final class MinerJob {
    private static final int WIDTH = 7;
    private static final int MAX_DEPTH = 72;
    private static final List<Item> PICKAXES = List.of(
        Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
    );
    private static final List<String> START_PHRASES = List.of(
        "Bora trabalhar!", "Picareta afiada, vamos nessa.", "Iniciando escavação."
    );
    private static final List<String> FULL_PHRASES = List.of(
        "Mochila cheia! Voltando para guardar.", "Estoque lotado.", "Vou descarregar e já volto."
    );
    private static final List<String> BROKEN_PHRASES = List.of(
        "Quebrou a picareta...", "Preciso de ferramenta nova.", "Ferrou, sem picareta."
    );
    private static final List<String> RICH_PHRASES = List.of(
        "Opa! Minério!", "Achado bom!", "Dinheiro no bolso."
    );

    private BlockPos mineOrigin;
    private Direction direction = Direction.SOUTH;
    private int step;
    private int blockCursor;
    private int cooldown;
    private int toolLookupCooldown;
    private int requestedPickaxeTier;
    private int announcementCooldown;
    private boolean toolPickupPending;
    private boolean oreDeliveryPending;
    private final List<BlockPos> pendingWaterSources = new ArrayList<>();

    public void start(CitizenEntity citizen, ServerLevel level) {
        chooseMineSite(citizen, level);
        step = 0;
        blockCursor = 0;
        cooldown = 0;
        toolLookupCooldown = 0;
        requestedPickaxeTier = 0;
        toolPickupPending = false;
        oreDeliveryPending = false;
        pendingWaterSources.clear();
        citizen.announce(random(START_PHRASES));
    }

    public void save(ValueOutput output) {
        if (mineOrigin != null) output.store("MineOrigin", BlockPos.CODEC, mineOrigin);
        output.putInt("MineDirection", direction.get2DDataValue());
        output.putInt("MineStep", step);
        output.putInt("MineBlockCursor", blockCursor);
        output.putInt("MineRequestedPickaxeTier", requestedPickaxeTier);
        output.putBoolean("MineOreDeliveryPending", oreDeliveryPending);
        output.store("PendingMineWater", BlockPos.CODEC.listOf(), List.copyOf(pendingWaterSources));
    }

    public void load(ValueInput input) {
        mineOrigin = input.read("MineOrigin", BlockPos.CODEC).orElse(null);
        direction = Direction.from2DDataValue(input.getIntOr("MineDirection", Direction.SOUTH.get2DDataValue()));
        step = Math.max(0, input.getIntOr("MineStep", 0));
        blockCursor = Math.max(0, input.getIntOr("MineBlockCursor", 0));
        requestedPickaxeTier = Math.max(0, input.getIntOr("MineRequestedPickaxeTier", 0));
        oreDeliveryPending = input.getBooleanOr("MineOreDeliveryPending", false);
        pendingWaterSources.clear();
        input.read("PendingMineWater", BlockPos.CODEC.listOf()).ifPresent(pendingWaterSources::addAll);
    }

    public String debugStatus(CitizenEntity citizen, ServerLevel level) {
        BlockPos target = null;
        String targetState = "none";
        if (mineOrigin != null) {
            List<BlockPos> slice = currentSlice();
            if (blockCursor >= 0 && blockCursor < slice.size()) {
                target = slice.get(blockCursor);
                targetState = BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString();
            }
        }
        String tool = BuiltInRegistries.ITEM.getKey(citizen.getMainHandItem().getItem()).toString();
        return "mineOrigin=" + (mineOrigin == null ? "none" : mineOrigin.toShortString())
            + ", heading=" + direction + ", step=" + step + ", cursor=" + blockCursor
            + ", target=" + (target == null ? "none" : target.toShortString() + "/" + targetState)
            + ", tool=" + tool + ", toolWait=" + toolLookupCooldown
            + ", oreReturn=" + oreDeliveryPending + ", pendingWater=" + pendingWaterSources.size();
    }

    public String workStatus(CitizenEntity citizen, ServerLevel level) {
        if (oreDeliveryPending || fullResourceStack(citizen) != null) return "Entregando recursos ao armazém";
        if (toolPickupPending || !isPickaxe(citizen.getMainHandItem())) return "Buscando uma picareta adequada";
        if (mineOrigin == null) return "Escolhendo um local seguro para a mina";
        BlockPos approach = mineOrigin.relative(direction, step).below(step);
        if (citizen.distanceToSqr(approach.getX() + 0.5, approach.getY() + 0.5, approach.getZ() + 0.5) > 16.0)
            return "Indo até a frente da mina";
        if (!pendingWaterSources.isEmpty()) return "Tampando água na mina";
        return "Escavando o túnel, etapa " + (step + 1);
    }

    public boolean needsNavigation(CitizenEntity citizen, ServerLevel level) {
        if (toolPickupPending || (!isPickaxe(citizen.getMainHandItem()) && bestInventoryPickaxe(citizen) == null)) {
            BlockPos base = basePosition(citizen, level);
            return !citizen.getNavigation().isDone()
                || citizen.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5) > 16.0;
        }
        if (mineOrigin == null) return true;
        BlockPos approach = mineOrigin.relative(direction, step).below(step);
        return citizen.distanceToSqr(approach.getX() + 0.5, approach.getY() + 0.5, approach.getZ() + 0.5) > 16.0;
    }

    private BlockPos basePosition(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) return warehouse.center();
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        return nation == null ? citizen.blockPosition() : nation.center().above();
    }

    /** Gets a suitable pickaxe before allowing the Miner to leave the warehouse base. */
    private boolean ensurePickaxe(CitizenEntity citizen, ServerLevel level, int minimumTier) {
        minimumTier = Math.max(minimumTier, requestedPickaxeTier);
        ItemStack held = citizen.getMainHandItem();
        if (isPickaxe(held) && pickaxeTier(held) >= minimumTier) {
            toolPickupPending = false;
            toolLookupCooldown = 0;
            requestedPickaxeTier = 0;
            return true;
        }

        Item inventoryPickaxe = bestInventoryPickaxe(citizen, minimumTier);
        if (inventoryPickaxe != null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, citizen.getNpcInventory().removeItemType(inventoryPickaxe, 1));
            toolPickupPending = false;
            toolLookupCooldown = 0;
            requestedPickaxeTier = 0;
            return true;
        }

        if (!toolPickupPending) {
            toolPickupPending = true;
            citizen.getNavigation().stop();
            announceThrottled(citizen, List.of("Vou voltar à base para procurar uma picareta nos baús."));
        }
        BlockPos base = basePosition(citizen, level);
        double baseX = base.getX() + 0.5;
        double baseY = base.getY();
        double baseZ = base.getZ() + 0.5;
        if (citizen.distanceToSqr(baseX, baseY, baseZ) > 16.0) {
            if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(baseX, baseY, baseZ, 0.9);
            return false;
        }

        // Do not restart a path to a chest every work tick. Check again when the current
        // path finishes, or immediately while standing at the base.
        if (citizen.getNavigation().isDone()
            && StorageJob.retrieveBestPickaxe(citizen, level, minimumTier)) return false;

        if (toolLookupCooldown <= 0) {
            CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
                StorageJob.BEST_PICKAXE_REQUEST_ID, 1);
            announceThrottled(citizen, List.of(minimumTier > 0
                ? "Não achei a picareta necessária nos baús. Pedi uma melhor ao Storager e vou aguardar na base."
                : "Não achei picareta nos baús. Pedi uma ao Storager e vou aguardar na base."));
            toolLookupCooldown = 100;
            citizen.getNavigation().stop();
        } else {
            toolLookupCooldown = Math.max(0, toolLookupCooldown - 8);
        }
        return false;
    }

    public void tick(CitizenEntity citizen, ServerLevel level) {
        if (cooldown-- > 0) return;
        cooldown = 8;
        collectDrops(citizen, level);
        sealPendingWater(citizen, level);

        if (oreDeliveryPending) {
            if (deliverOreResources(citizen, level)) return;
            oreDeliveryPending = false;
        }

        Item fullStack = fullResourceStack(citizen);
        if (fullStack != null && StorageJob.deliverResourcePack(citizen, level, fullStack, 64)) {
            announceThrottled(citizen, FULL_PHRASES);
            return;
        }

        if (mineOrigin == null) chooseMineSite(citizen, level);
        if (!ensurePickaxe(citizen, level, 0)) return;

        if (mineOrigin == null) chooseMineSite(citizen, level);
        if (mineOrigin == null) return;

        if (insideNation(citizen, level, mineOrigin)
            && citizen.distanceToSqr(mineOrigin.getX() + 0.5, mineOrigin.getY(), mineOrigin.getZ() + 0.5) > 16.0) {
            if (citizen.getNavigation().isDone())
                citizen.getNavigation().moveTo(mineOrigin.getX() + 0.5, mineOrigin.getY(), mineOrigin.getZ() + 0.5, 0.9);
            return;
        }

        List<BlockPos> slice = currentSlice();
        if (blockCursor >= slice.size()) {
            step++;
            blockCursor = 0;
            if (step >= MAX_DEPTH || mineOrigin.getY() - step < level.getMinY() + 8) {
                chooseMineSite(citizen, level);
                step = 0;
            }
            slice = currentSlice();
        }
        BlockPos approach = mineOrigin.relative(direction, step).below(step);
        if (!insideNation(citizen, level, approach)) {
            if (!reroute(citizen, level)) announceThrottled(citizen, List.of("Cheguei ao limite seguro da nação; vou procurar outra direção."));
            return;
        }
        if (citizen.distanceToSqr(approach.getX() + 0.5, approach.getY() + 0.5, approach.getZ() + 0.5) > 16.0) {
            citizen.getNavigation().moveTo(approach.getX() + 0.5, approach.getY() + 0.5, approach.getZ() + 0.5, 0.9);
            return;
        }
        // Prepare the next lower step only after reaching the current stair cell. Doing
        // this while still at the surface can break the support block under the miner.
        if (step > 0 && prepareStairStep(citizen, level)) return;

        BlockPos target = slice.get(blockCursor);
        if (!insideNation(citizen, level, target)) {
            blockCursor++;
            return;
        }
        var fluid = level.getFluidState(target);
        if (fluid.is(FluidTags.LAVA)) {
            if (!reroute(citizen, level)) announceThrottled(citizen, List.of("Encontrei lava; parei a escavação para manter a mina segura."));
            return;
        }
        if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
            if (!sealWaterSource(citizen, level, target)) pendingWaterSources.add(target.immutable());
            blockCursor++;
            return;
        }
        BlockState state = level.getBlockState(target);
        if (state.isAir() || state.hasBlockEntity() || state.getDestroySpeed(level, target) < 0.0f) {
            blockCursor++;
            return;
        }
        if (isOre(state) && pickaxeTier(citizen.getMainHandItem()) < requiredPickaxeTier(state)) {
            requestedPickaxeTier = requiredPickaxeTier(state);
            toolPickupPending = true;
            ensurePickaxe(citizen, level, requiredPickaxeTier(state));
            return;
        }
        if (isOre(state)) {
            citizen.announce(random(RICH_PHRASES));
            mineOreVein(citizen, level, target, state);
            oreDeliveryPending = true;
            blockCursor++;
            return;
        }
        citizen.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30.0f, 30.0f);
        citizen.swing(InteractionHand.MAIN_HAND);
        if (level.destroyBlock(target, true, citizen)) {
            ItemStack pickaxe = citizen.getMainHandItem();
            pickaxe.hurtAndBreak(1, citizen, InteractionHand.MAIN_HAND);
            blockCursor++;
            if (pickaxe.isEmpty()) {
                toolPickupPending = false;
                toolLookupCooldown = 0;
                citizen.announce(random(BROKEN_PHRASES));
            }
        } else {
            blockCursor++;
        }
    }

    private void chooseMineSite(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null) {
            mineOrigin = citizen.blockPosition().immutable();
            return;
        }
        int[][] offsets = {{24, 0}, {-24, 0}, {0, 24}, {0, -24}, {24, 24}, {24, -24}, {-24, 24}, {-24, -24},
            {32, 0}, {-32, 0}, {0, 32}, {0, -32}};
        CivilizationData.Warehouse warehouse = data.warehouse(nation.id());
        for (int[] offset : offsets) {
            int x = nation.x() + offset[0];
            int z = nation.z() + offset[1];
            BlockPos probe = new BlockPos(x, nation.y(), z);
            if (!level.hasChunkAt(probe)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!nation.contains(candidate) || Math.abs(offset[0]) < 10 && Math.abs(offset[1]) < 10) continue;
            if (warehouse != null && warehouse.center().distSqr(candidate) < (warehouse.radius() + 12.0) * (warehouse.radius() + 12.0)) continue;
            if (!level.getBlockState(candidate).isAir() || !level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)) continue;
            mineOrigin = candidate;
            direction = Math.abs(offset[0]) >= Math.abs(offset[1])
                ? (offset[0] >= 0 ? Direction.EAST : Direction.WEST)
                : (offset[1] >= 0 ? Direction.SOUTH : Direction.NORTH);
            return;
        }
        mineOrigin = citizen.blockPosition().immutable();
        direction = Direction.SOUTH;
    }

    private List<BlockPos> currentSlice() {
        List<BlockPos> targets = new ArrayList<>(WIDTH * 2);
        // Clear the two blocks above the next, lower floor while leaving that floor intact.
        // The next approach point is one block forward and one block down.
        BlockPos row = mineOrigin.relative(direction, step + 1);
        Direction across = direction.getClockWise();
        for (int offset = 0; offset < WIDTH; offset++) {
            int lateral = offset - WIDTH / 2;
            BlockPos base = row.relative(across, lateral).below(step + 1);
            for (int height = 1; height >= 0; height--) targets.add(base.above(height));
        }
        return targets;
    }

    private boolean reroute(CitizenEntity citizen, ServerLevel level) {
        BlockPos pivot = citizen.blockPosition().immutable();
        for (Direction candidate : List.of(direction.getClockWise(), direction.getCounterClockWise(), direction.getOpposite())) {
            if (!insideNation(citizen, level, pivot.relative(candidate, 2)) || !safeSlice(pivot, candidate, level)) continue;
            mineOrigin = pivot;
            direction = candidate;
            step = 0;
            blockCursor = 0;
            return true;
        }
        return false;
    }

    private boolean safeSlice(BlockPos origin, Direction heading, ServerLevel level) {
        Direction across = heading.getClockWise();
        for (int width = -3; width <= 3; width++) {
            BlockPos column = origin.relative(across, width).relative(heading).below();
            for (int height = 0; height <= 2; height++) {
                BlockPos pos = column.above(height);
                if (level.getFluidState(pos).is(FluidTags.LAVA)) return false;
            }
        }
        return true;
    }

    /** Opens the three-block staircase step used by AI Buddies: forward, headroom, then floor. */
    private boolean prepareStairStep(CitizenEntity citizen, ServerLevel level) {
        BlockPos current = citizen.blockPosition();
        BlockPos forward = current.relative(direction);
        for (BlockPos candidate : List.of(forward, forward.above(), forward.below())) {
            if (!insideNation(citizen, level, candidate)) return false;
            var fluid = level.getFluidState(candidate);
            if (fluid.is(FluidTags.LAVA)) return false;
            BlockState state = level.getBlockState(candidate);
            if (state.isAir() || state.hasBlockEntity() || state.getDestroySpeed(level, candidate) < 0.0f) continue;
            citizen.getLookControl().setLookAt(candidate.getX() + 0.5, candidate.getY() + 0.5,
                candidate.getZ() + 0.5, 30.0f, 30.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            if (level.destroyBlock(candidate, true, citizen)) {
                ItemStack pickaxe = citizen.getMainHandItem();
                pickaxe.hurtAndBreak(1, citizen, InteractionHand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    private boolean sealWaterSource(CitizenEntity citizen, ServerLevel level, BlockPos source) {
        for (Item coverItem : List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.STONE)) {
            ItemStack cover = citizen.getNpcInventory().removeItemType(coverItem, 1);
            if (cover.isEmpty()) continue;
            Block coverBlock = Block.byItem(coverItem);
            if (level.setBlockAndUpdate(source, coverBlock.defaultBlockState())) return true;
            citizen.getNpcInventory().addItem(cover);
            return false;
        }
        return false;
    }

    private void sealPendingWater(CitizenEntity citizen, ServerLevel level) {
        for (int i = 0; i < pendingWaterSources.size(); i++) {
            BlockPos source = pendingWaterSources.get(i);
            if (!level.hasChunkAt(source) || !level.getFluidState(source).is(FluidTags.WATER)
                || !level.getFluidState(source).isSource()) {
                pendingWaterSources.remove(i--);
                continue;
            }
            if (citizen.distanceToSqr(source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5) <= 25.0
                && sealWaterSource(citizen, level, source)) pendingWaterSources.remove(i--);
        }
    }

    private void collectDrops(CitizenEntity citizen, ServerLevel level) {
        AABB area = citizen.getBoundingBox().inflate(8.0, 4.0, 8.0);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area).stream()
            .filter(item -> !isPickaxe(item.getItem()) && item.getItem().getItem() != Items.AIR)
            .sorted(Comparator.comparingDouble(citizen::distanceToSqr)).toList();
        for (ItemEntity drop : drops) {
            ItemStack remainder = citizen.getNpcInventory().addItem(drop.getItem().copy());
            if (remainder.isEmpty()) drop.discard();
            else drop.setItem(remainder);
        }
    }

    private Item fullResourceStack(CitizenEntity citizen) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.getCount() >= 64 && !isPickaxe(stack)) return stack.getItem();
        }
        return null;
    }

    private Item bestInventoryPickaxe(CitizenEntity citizen) {
        return bestInventoryPickaxe(citizen, 0);
    }

    private Item bestInventoryPickaxe(CitizenEntity citizen, int minimumTier) {
        for (Item pickaxe : PICKAXES) {
            if (pickaxeTier(pickaxe) < minimumTier) continue;
            for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
                if (citizen.getNpcInventory().getItem(slot).is(pickaxe)) return pickaxe;
            }
        }
        return null;
    }

    private void mineOreVein(CitizenEntity citizen, ServerLevel level, BlockPos first, BlockState firstState) {
        String family = oreFamily(firstState);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(first.immutable());
        visited.add(first.immutable());
        int broken = 0;
        while (!queue.isEmpty() && broken < 64) {
            BlockPos ore = queue.removeFirst();
            BlockState state = level.getBlockState(ore);
            if (!isOre(state) || !oreFamily(state).equals(family)
                || !insideNation(citizen, level, ore)
                || level.getFluidState(ore).is(FluidTags.LAVA)
                || pickaxeTier(citizen.getMainHandItem()) < requiredPickaxeTier(state)) continue;
            citizen.getLookControl().setLookAt(ore.getX() + 0.5, ore.getY() + 0.5, ore.getZ() + 0.5, 30.0f, 30.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            if (!level.destroyBlock(ore, true, citizen)) continue;
            citizen.getMainHandItem().hurtAndBreak(1, citizen, InteractionHand.MAIN_HAND);
            broken++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = ore.offset(dx, dy, dz);
                        if (visited.add(neighbor.immutable())) queue.addLast(neighbor.immutable());
                    }
                }
            }
        }
    }

    private boolean deliverOreResources(CitizenEntity citizen, ServerLevel level) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (!stack.isEmpty() && isOreDrop(stack.getItem())) {
                return StorageJob.deliverResourcePack(citizen, level, stack.getItem(), stack.getCount());
            }
        }
        return false;
    }

    private boolean isOreDrop(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return path.startsWith("raw_") || path.equals("coal") || path.equals("diamond")
            || path.equals("emerald") || path.equals("redstone") || path.equals("lapis_lazuli")
            || path.equals("quartz") || path.equals("ancient_debris") || path.equals("netherite_scrap");
    }

    private int pickaxeTier(ItemStack stack) {
        return pickaxeTier(stack.getItem());
    }

    private int pickaxeTier(Item item) {
        if (item == Items.DIAMOND_PICKAXE) return 3;
        if (item == Items.IRON_PICKAXE) return 2;
        if (item == Items.STONE_PICKAXE) return 1;
        if (item == Items.WOODEN_PICKAXE) return 0;
        return -1;
    }

    private int requiredPickaxeTier(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return 3;
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return 2;
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return 1;
        return 0;
    }

    private String oreFamily(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.startsWith("deepslate_")) path = path.substring("deepslate_".length());
        if (path.startsWith("nether_") && path.endsWith("_ore")) return path;
        return path.endsWith("_ore") ? path.substring(0, path.length() - "_ore".length()) : path;
    }

    private boolean isPickaxe(ItemStack stack) {
        return PICKAXES.stream().anyMatch(stack::is);
    }

    private boolean insideNation(CitizenEntity citizen, ServerLevel level, BlockPos pos) {
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(citizen.getNationId());
        return nation != null && nation.dimension().equals(level.dimension().location()) && nation.contains(pos);
    }

    private boolean isOre(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private void announceThrottled(CitizenEntity citizen, List<String> phrases) {
        if (announcementCooldown-- <= 0) {
            citizen.announce(random(phrases));
            announcementCooldown = 200;
        }
    }

    private String random(List<String> phrases) {
        return phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
    }
}
