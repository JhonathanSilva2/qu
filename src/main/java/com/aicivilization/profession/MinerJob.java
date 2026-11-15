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
public final class MinerJob implements ProfessionJob {
    public static final String ID = "miner";
    public static final String LABEL = "Miner";

    private static final int WIDTH = 7;
    private static final int HEIGHT = 6;
    private static final int MAX_DEPTH = 72;
    private static final List<Item> PICKAXES = List.of(
        Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
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
    private boolean entranceMarked;
    private final List<BlockPos> pendingWaterSources = new ArrayList<>();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getLabel() {
        return LABEL;
    }

    @Override
    public boolean start(CitizenEntity citizen, ServerLevel level) {
        chooseMineSite(citizen, level);
        step = 0;
        blockCursor = 0;
        cooldown = 0;
        toolLookupCooldown = 0;
        requestedPickaxeTier = 0;
        toolPickupPending = false;
        oreDeliveryPending = false;
        pendingWaterSources.clear();
        entranceMarked = false;

        if (!isPickaxe(citizen.getMainHandItem()) && bestInventoryPickaxe(citizen) == null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        }

        if (!citizen.getNpcInventory().hasAnyMatching(stack -> stack.is(Items.TORCH))) {
            citizen.getNpcInventory().addItem(new ItemStack(Items.TORCH, 16));
        }

        markMineEntrance(level, citizen);
        entranceMarked = true;

        citizen.announce(random(START_PHRASES));
        return true;
    }

    @Override
    public void stop(CitizenEntity citizen, ServerLevel level, boolean announce) {
        citizen.getNavigation().stop();
        if (announce) {
            citizen.announce("Parei a escavação.");
        }
    }

    public void save(ValueOutput output) {
        if (mineOrigin != null) output.store("MineOrigin", BlockPos.CODEC, mineOrigin);
        output.putInt("MineDirection", direction.get2DDataValue());
        output.putInt("MineStep", step);
        output.putInt("MineBlockCursor", blockCursor);
        output.putInt("MineRequestedPickaxeTier", requestedPickaxeTier);
        output.putBoolean("MineOreDeliveryPending", oreDeliveryPending);
        output.putBoolean("MineEntranceMarked", entranceMarked);
        output.store("PendingMineWater", BlockPos.CODEC.listOf(), List.copyOf(pendingWaterSources));
    }

    public void load(ValueInput input) {
        mineOrigin = input.read("MineOrigin", BlockPos.CODEC).orElse(null);
        direction = Direction.from2DDataValue(input.getIntOr("MineDirection", Direction.SOUTH.get2DDataValue()));
        step = Math.max(0, input.getIntOr("MineStep", 0));
        blockCursor = Math.max(0, input.getIntOr("MineBlockCursor", 0));
        requestedPickaxeTier = Math.max(0, input.getIntOr("MineRequestedPickaxeTier", 0));
        oreDeliveryPending = input.getBooleanOr("MineOreDeliveryPending", false);
        entranceMarked = input.getBooleanOr("MineEntranceMarked", false);
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
            return !citizen.getNavigation().isDone();
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
    private boolean ensurePickaxe(CitizenEntity citizen, ServerLevel level) {
        ItemStack held = citizen.getMainHandItem();
        if (isPickaxe(held)) {
            toolPickupPending = false;
            toolLookupCooldown = 0;
            requestedPickaxeTier = 0;
            return true;
        }

        Item inventoryPickaxe = bestInventoryPickaxe(citizen, 0);
        if (inventoryPickaxe != null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, citizen.getNpcInventory().removeItemType(inventoryPickaxe, 1));
            toolPickupPending = false;
            toolLookupCooldown = 0;
            requestedPickaxeTier = 0;
            return true;
        }

        // Miner has no pickaxe. Deposit mined materials so the Storager can immediately craft tools if needed.
        StorageJob.depositAllResources(citizen, level);

        // If we are currently navigating towards a chest with a pickaxe:
        if (toolPickupPending) {
            if (StorageJob.retrieveBestPickaxe(citizen, level, 0)) {
                if (isPickaxe(citizen.getMainHandItem())) {
                    toolPickupPending = false;
                    toolLookupCooldown = 0;
                    requestedPickaxeTier = 0;
                    return true;
                }
                return false; // Still walking to the chest; do not cancel or restart navigation
            }
            toolPickupPending = false;
        }

        if (toolLookupCooldown > 0) {
            toolLookupCooldown = Math.max(0, toolLookupCooldown - 8);
            return false;
        }

        toolLookupCooldown = 100;
        if (StorageJob.retrieveBestPickaxe(citizen, level, 0)) {
            if (isPickaxe(citizen.getMainHandItem())) {
                toolPickupPending = false;
                toolLookupCooldown = 0;
                requestedPickaxeTier = 0;
                return true;
            }
            toolPickupPending = true;
            return false; // Walking towards the chest
        }

        // Truly no pickaxe in any warehouse chest
        CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
            StorageJob.pickaxeRequestId(0), 1);
        announceThrottled(citizen, List.of("Não achei picareta nos baús. Pedi uma ao Storager e vou aguardar na base."));

        BlockPos base = basePosition(citizen, level);
        double baseX = base.getX() + 0.5;
        double baseY = base.getY();
        double baseZ = base.getZ() + 0.5;
        if (citizen.distanceToSqr(baseX, baseY, baseZ) > 16.0) {
            if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(baseX, baseY, baseZ, 0.9);
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
            StorageJob.depositAllResources(citizen, level);
        }

        Item fullStack = fullResourceStack(citizen);
        if (fullStack != null && StorageJob.deliverResourcePack(citizen, level, fullStack, 64)) {
            announceThrottled(citizen, FULL_PHRASES);
            return;
        }

        if (mineOrigin == null) chooseMineSite(citizen, level);
        if (!ensurePickaxe(citizen, level)) return;

        if (mineOrigin == null) chooseMineSite(citizen, level);
        if (mineOrigin == null) return;

        if (!entranceMarked) {
            markMineEntrance(level, citizen);
            entranceMarked = true;
        }

        if (step == 0 && insideNation(citizen, level, mineOrigin)
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
            } else {
                placeTorchIfNeeded(citizen, level);
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
            int neededTier = requiredPickaxeTier(state);
            CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
                StorageJob.pickaxeRequestId(neededTier), 1);
            announceThrottled(citizen, List.of(
                "Encontrei " + state.getBlock().getName().getString() + " que exige picareta melhor; deixei marcado e continuei escavando a mina."
            ));
            blockCursor++;
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

    private void placeTorchIfNeeded(CitizenEntity citizen, ServerLevel level) {
        if (mineOrigin == null || step <= 0 || step % 4 != 0) return;
        BlockPos row = mineOrigin.relative(direction, step);
        Direction across = direction.getClockWise();
        int lateral = (step % 8 == 0) ? 2 : -2;
        BlockPos floor = row.relative(across, lateral).below(step);
        BlockPos torchPos = floor.above(1);

        if (!level.getBlockState(torchPos).isAir() && !level.getBlockState(torchPos).canBeReplaced()) return;
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            level.setBlockAndUpdate(floor, Blocks.COBBLESTONE.defaultBlockState());
        }

        boolean hasTorch = false;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(Items.TORCH) && stack.getCount() > 0) {
                stack.shrink(1);
                if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                hasTorch = true;
                break;
            }
        }

        if (!hasTorch) {
            boolean hasCoal = citizen.getNpcInventory().hasAnyMatching(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
            boolean hasStick = citizen.getNpcInventory().hasAnyMatching(s -> s.is(Items.STICK));
            if (hasCoal && hasStick) {
                citizen.getNpcInventory().removeItemType(Items.STICK, 1);
                Item coalItem = citizen.getNpcInventory().hasAnyMatching(s -> s.is(Items.COAL)) ? Items.COAL : Items.CHARCOAL;
                citizen.getNpcInventory().removeItemType(coalItem, 1);
                citizen.getNpcInventory().addItem(new ItemStack(Items.TORCH, 3));
                hasTorch = true;
            }
        }

        if (hasTorch) {
            level.setBlockAndUpdate(torchPos, Blocks.TORCH.defaultBlockState());
            announceThrottled(citizen, List.of("Coloquei uma tocha para iluminar a mina e evitar monstros."));
        }
    }

    private void markMineEntrance(ServerLevel level, CitizenEntity citizen) {
        if (mineOrigin == null) return;
        Direction across = direction.getClockWise();
        boolean placedAny = false;
        for (int side : List.of(-4, 4)) {
            BlockPos lateral = mineOrigin.relative(across, side);
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lateral.getX(), lateral.getZ());
            BlockPos ground = new BlockPos(lateral.getX(), surfaceY - 1, lateral.getZ());
            BlockPos bannerPos = new BlockPos(lateral.getX(), surfaceY, lateral.getZ());
            if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
                level.setBlockAndUpdate(ground, Blocks.COBBLESTONE.defaultBlockState());
            }
            if (level.getBlockState(bannerPos).isAir() || level.getBlockState(bannerPos).canBeReplaced()) {
                if (level.setBlockAndUpdate(bannerPos, Blocks.GRAY_BANNER.defaultBlockState())) {
                    placedAny = true;
                }
            }
        }
        if (placedAny) {
            citizen.announce("Marquei a entrada da mina com um estandarte.");
        }
    }

    private void chooseMineSite(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null) {
            mineOrigin = citizen.blockPosition().immutable();
            entranceMarked = false;
            return;
        }

        // Se a civilização já tem uma mina registrada, o minerador se junta a ela!
        CivilizationData.MineSite existing = data.getMineSite(nation.id());
        if (existing != null && existing.dimension().equals(level.dimension().location())) {
            mineOrigin = existing.origin();
            direction = existing.direction();
            entranceMarked = true;
            // Sincroniza o degrau com outro minerador da mesma mina
            List<CitizenEntity> citizens = level.getEntitiesOfClass(CitizenEntity.class, citizen.getBoundingBox().inflate(128.0));
            for (CitizenEntity other : citizens) {
                if (other != citizen && nation.id().equals(other.getNationId()) && other.isMinerEnabled()) {
                    MinerJob otherJob = other.getMinerJob();
                    if (otherJob != null && otherJob.getMineOrigin() != null && otherJob.getMineOrigin().equals(mineOrigin)) {
                        this.step = Math.max(this.step, otherJob.getStep());
                    }
                }
            }
            citizen.announce("Juntei-me à mina existente da nossa civilização.");
            return;
        }

        BlockPos base = basePosition(citizen, level);
        int baseY = base.getY();

        int[][] offsets = {
            {24, 0}, {-24, 0}, {0, 24}, {0, -24},
            {24, 24}, {24, -24}, {-24, 24}, {-24, -24},
            {32, 0}, {-32, 0}, {0, 32}, {0, -32},
            {20, 0}, {-20, 0}, {0, 20}, {0, -20},
            {28, 0}, {-28, 0}, {0, 28}, {0, -28}
        };
        CivilizationData.Warehouse warehouse = data.warehouse(nation.id());
        BlockPos bestCandidate = null;
        int bestDiff = Integer.MAX_VALUE;
        Direction bestDirection = Direction.SOUTH;

        for (int[] offset : offsets) {
            int x = nation.x() + offset[0];
            int z = nation.z() + offset[1];
            BlockPos probe = new BlockPos(x, baseY, z);
            if (!level.hasChunkAt(probe)) continue;
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, baseY, z);
            if (!nation.contains(candidate) || (Math.abs(offset[0]) < 10 && Math.abs(offset[1]) < 10)) continue;
            if (warehouse != null && warehouse.center().distSqr(candidate) < (warehouse.radius() + 12.0) * (warehouse.radius() + 12.0)) continue;

            int diff = Math.abs(surfaceY - baseY);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestCandidate = candidate;
                bestDirection = Math.abs(offset[0]) >= Math.abs(offset[1])
                    ? (offset[0] >= 0 ? Direction.EAST : Direction.WEST)
                    : (offset[1] >= 0 ? Direction.SOUTH : Direction.NORTH);
                if (diff == 0) break;
            }
        }

        if (bestCandidate != null) {
            mineOrigin = bestCandidate;
            direction = bestDirection;
            entranceMarked = false;
            BlockPos below = mineOrigin.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                level.setBlockAndUpdate(below, Blocks.COBBLESTONE.defaultBlockState());
            }
            data.setMineSite(nation.id(), mineOrigin, direction, level.dimension().location());
            return;
        }

        mineOrigin = new BlockPos(citizen.blockPosition().getX(), baseY, citizen.blockPosition().getZ());
        direction = Direction.SOUTH;
        entranceMarked = false;
        data.setMineSite(nation.id(), mineOrigin, direction, level.dimension().location());
    }

    private List<BlockPos> currentSlice() {
        List<BlockPos> targets = new ArrayList<>(WIDTH * HEIGHT);
        // Clear the HEIGHT blocks above the next, lower floor while leaving that floor intact.
        // The next approach point is one block forward and one block down.
        BlockPos row = mineOrigin.relative(direction, step + 1);
        Direction across = direction.getClockWise();
        for (int offset = 0; offset < WIDTH; offset++) {
            int lateral = offset - WIDTH / 2;
            BlockPos base = row.relative(across, lateral).below(step + 1);
            for (int height = HEIGHT - 1; height >= 0; height--) targets.add(base.above(height));
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
            for (int height = 0; height <= HEIGHT; height++) {
                BlockPos pos = column.above(height);
                if (level.getFluidState(pos).is(FluidTags.LAVA)) return false;
            }
        }
        return true;
    }

    /** Opens the staircase step: forward, headroom up to HEIGHT, then floor. */
    private boolean prepareStairStep(CitizenEntity citizen, ServerLevel level) {
        BlockPos current = citizen.blockPosition();
        BlockPos forward = current.relative(direction);
        List<BlockPos> candidates = new ArrayList<>();
        for (int h = HEIGHT - 1; h >= -1; h--) {
            candidates.add(forward.above(h));
        }
        for (BlockPos candidate : candidates) {
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
            || path.equals("quartz") || path.equals("ancient_debris") || path.equals("netherite_scrap")
            || path.equals("raw_copper") || path.equals("copper_ingot") || path.equals("iron_ingot") || path.equals("gold_ingot");
    }

    private int pickaxeTier(ItemStack stack) {
        return pickaxeTier(stack.getItem());
    }

    private int pickaxeTier(Item item) {
        if (item == Items.NETHERITE_PICKAXE) return 4;
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

    public BlockPos getMineOrigin() { return mineOrigin; }
    public Direction getDirection() { return direction; }
    public int getStep() { return step; }
    public int getWidth() { return WIDTH; }
    public int getHeight() { return HEIGHT; }
    public BlockPos getCurrentApproach() {
        return mineOrigin == null ? null : mineOrigin.relative(direction, step).below(step);
    }
}
