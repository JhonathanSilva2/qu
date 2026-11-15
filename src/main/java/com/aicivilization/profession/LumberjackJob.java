package com.aicivilization.profession;

import com.aicivilization.data.CivilizationData;
import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.storage.StorageJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Modular Lumberjack profession with autonomous patrol, tool requesting,
 * whole-tree felling, leaf clearing with vanilla drops, and reforestation.
 */
public final class LumberjackJob implements ProfessionJob {
    public static final String ID = "lumberjack";
    public static final String LABEL = "LumberJack";
    private static final int MAX_TREE_LOGS = 128;

    private int lumberjackCooldown;
    private BlockPos lumberjackTarget;
    private final List<BlockPos> temporaryTower = new ArrayList<>();
    private boolean announcedSearching;
    private int stuckTicks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float chopProgress;
    private int lastAnnouncedWoodMilestone;
    private boolean towerLimitAnnounced;
    private boolean announcedTreeFound;
    private boolean announcedDirtCollection;
    private List<BlockPos> patrolPoints = List.of();
    private UUID patrolNationId;
    private int patrolIndex;
    private int patrolLastRefreshTick;
    private int sweepLogCount;
    private int skippedTreeCooldown;
    private int saplingLookupCooldown;
    private int reforestationCooldown;
    private BlockPos skippedTreeBase;
    private boolean lumberjackDeliveryPending;
    private final List<BlockPos> pendingReplantPositions = new ArrayList<>();
    private int toolLookupCooldown;
    private boolean toolPickupPending;

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
        this.lumberjackTarget = null;
        this.lumberjackCooldown = 0;
        this.announcedSearching = false;
        this.lastAnnouncedWoodMilestone = countLogs(citizen) / 16;
        this.towerLimitAnnounced = false;
        this.announcedTreeFound = false;
        this.announcedDirtCollection = false;
        this.patrolIndex = 0;
        this.sweepLogCount = 0;
        this.patrolPoints = List.of();
        this.patrolNationId = citizen.getNationId();
        this.pendingReplantPositions.clear();
        this.skippedTreeBase = null;
        this.skippedTreeCooldown = 0;
        this.saplingLookupCooldown = 0;
        this.reforestationCooldown = 0;
        this.lumberjackDeliveryPending = false;
        this.toolLookupCooldown = 0;
        this.toolPickupPending = false;
        citizen.announce("Vou trabalhar como lenhador e procurar árvores.");
        return true;
    }

    @Override
    public void stop(CitizenEntity citizen, ServerLevel level, boolean announce) {
        this.lumberjackTarget = null;
        this.chopProgress = 0.0f;
        citizen.getNavigation().stop();
        removeTemporaryTower(citizen, level);
        if (announce) {
            citizen.announce("Parei o trabalho. Minha madeira está no inventário.");
        }
    }

    @Override
    public void save(ValueOutput output) {
        if (lumberjackTarget != null) output.store("LumberjackTarget", BlockPos.CODEC, lumberjackTarget);
        output.putFloat("ChopProgress", chopProgress);
        output.store("PendingReplant", BlockPos.CODEC.listOf(), List.copyOf(pendingReplantPositions));
    }

    @Override
    public void load(ValueInput input) {
        lumberjackTarget = input.read("LumberjackTarget", BlockPos.CODEC).orElse(null);
        chopProgress = input.getFloatOr("ChopProgress", 0.0f);
        pendingReplantPositions.clear();
        input.read("PendingReplant", BlockPos.CODEC.listOf()).ifPresent(pendingReplantPositions::addAll);
    }

    @Override
    public String workStatus(CitizenEntity citizen, ServerLevel level) {
        if (StorageJob.hasLumberjackDeliveryResources(citizen)) return "Entregando recursos ao armazém";
        if (lumberjackTarget == null) return citizen.getNavigation().isDone() ? "Procurando madeira" : "Patrulhando o território";
        if (!isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) return "Procurando outra árvore";
        return citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5) > 20.25 ? "Indo até uma árvore" : "Cortando madeira";
    }

    @Override
    public String debugStatus(CitizenEntity citizen, ServerLevel level) {
        String target = lumberjackTarget == null ? "none" : lumberjackTarget.toShortString()
            + "/" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(lumberjackTarget).getBlock());
        BlockPos leaf = findBlockingLeaf(citizen, level);
        return "target=" + target + ", chop=" + String.format(java.util.Locale.ROOT, "%.2f", chopProgress)
            + ", stuckTicks=" + stuckTicks + ", blockedTree=" + (skippedTreeBase == null ? "none" : skippedTreeBase.toShortString())
            + ", blockingLeaf=" + (leaf == null ? "none" : leaf.toShortString())
            + ", pendingReplant=" + pendingReplantPositions.size();
    }

    @Override
    public boolean needsNavigation(CitizenEntity citizen, ServerLevel level) {
        return lumberjackTarget != null
            && citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, citizen.getY(), lumberjackTarget.getZ() + 0.5) > 9.0;
    }

    @Override
    public void tick(CitizenEntity citizen, ServerLevel level) {
        if (lumberjackCooldown-- > 0) return;
        lumberjackCooldown = 10;
        doLumberjackWork(citizen, level);
    }

    private void doLumberjackWork(CitizenEntity citizen, ServerLevel level) {
        if (skippedTreeCooldown > 0 && --skippedTreeCooldown == 0) skippedTreeBase = null;
        if (saplingLookupCooldown > 0) saplingLookupCooldown -= 10;
        if (reforestationCooldown > 0) reforestationCooldown -= 10;
        if (collectNearbyTreeDrops(citizen, level)) return;
        replantAvailableSaplings(citizen, level);

        if (StorageJob.hasLumberjackDeliveryResources(citizen)) {
            if (!lumberjackDeliveryPending) {
                citizen.announce(StorageJob.lumberjackDeliveryMessage(citizen));
                lumberjackDeliveryPending = true;
            }
            if (StorageJob.deliverLumberjackResources(citizen, level)) return;
        }
        lumberjackDeliveryPending = false;

        if (isAxe(citizen.getMainHandItem())) {
            toolPickupPending = false;
        } else if (bestInventoryAxe(citizen) != null) {
            ItemStack axe = citizen.getNpcInventory().removeItemType(bestInventoryAxe(citizen), 1);
            citizen.setItemSlot(EquipmentSlot.MAINHAND, axe);
            toolPickupPending = false;
        } else if (toolPickupPending) {
            if (StorageJob.retrieveBestAxe(citizen, level)) return;
            toolPickupPending = false;
        } else if (toolLookupCooldown > 0) {
            toolLookupCooldown -= 10;
        } else {
            toolLookupCooldown = 100;
            if (StorageJob.retrieveBestAxe(citizen, level)) {
                toolPickupPending = !isAxe(citizen.getMainHandItem());
                return;
            }
            if (citizen.getNationId() != null) {
                CivilizationData.get(level).requestStorageItem(citizen.getNationId(), citizen.getUUID(),
                    StorageJob.BEST_AXE_REQUEST_ID, 1);
            }
        }

        announcedSearching = true;
        if (lumberjackTarget == null || !isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) {
            lumberjackTarget = findNearestLog(citizen, level);
            if (lumberjackTarget == null) {
                if (plantSaplingInTerritory(citizen, level)) return;
                if (firstSaplingSlot(citizen) < 0 && saplingLookupCooldown <= 0) {
                    saplingLookupCooldown = 100;
                    if (StorageJob.retrieveFirstSapling(citizen, level)) return;
                }
                patrolForWood(citizen, level);
                return;
            }
            if (!announcedTreeFound) {
                citizen.announce("Encontrei uma árvore; vou começar a coleta.");
                announcedTreeFound = true;
            }
        }

        BlockPos approach = findTreeApproach(citizen, level, lumberjackTarget);
        if (approach == null) {
            recoverPathIfStuck(citizen, level);
            return;
        }
        double horizontalDistance = citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5);
        boolean closeEnoughToChop = horizontalDistance <= 20.25;
        if (!closeEnoughToChop || !citizen.getNavigation().isDone()) {
            if (!closeEnoughToChop && (citizen.getNavigation().isDone() || citizen.getNavigation().getTargetPos() == null
                || !citizen.getNavigation().getTargetPos().equals(approach))) {
                citizen.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
            }
            recoverPathIfStuck(citizen, level);
            return;
        }

        Item targetItem = level.getBlockState(lumberjackTarget).getBlock().asItem();
        if (!citizen.getNpcInventory().canAddItem(new ItemStack(targetItem))) {
            citizen.announce("Meu inventário está cheio; vou parar de cortar.");
            citizen.stopProfession();
            return;
        }

        BlockState targetState = level.getBlockState(lumberjackTarget);
        citizen.getLookControl().setLookAt(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5, 30.0f, 30.0f);
        citizen.swing(InteractionHand.MAIN_HAND);
        float hardness = targetState.getDestroySpeed(level, lumberjackTarget);
        float toolSpeed = citizen.getMainHandItem().isEmpty() ? 1.0f : citizen.getMainHandItem().getDestroySpeed(targetState);
        chopProgress += hardness <= 0.0f ? 1.0f : Math.max(0.02f, toolSpeed / hardness / 3.0f);
        level.destroyBlockProgress(citizen.getId(), lumberjackTarget, Math.min(9, (int) (chopProgress * 10.0f)));

        if (chopProgress >= 1.0f) {
            level.destroyBlockProgress(citizen.getId(), lumberjackTarget, -1);
            BlockPos choppedPosition = lumberjackTarget;

            // Fell the whole connected tree to prevent floating tops and clear leaves with vanilla loot
            fellConnectedTree(citizen, level, choppedPosition);

            int woodMilestone = countLogs(citizen) / 16;
            if (woodMilestone > lastAnnouncedWoodMilestone) {
                lastAnnouncedWoodMilestone = woodMilestone;
                citizen.announce("Já tenho " + (woodMilestone * 16) + " troncos no inventário.");
            }
            chopProgress = 0.0f;
            lumberjackTarget = null;
            announcedTreeFound = false;
        }
    }

    /**
     * Finds and breaks all connected logs in the tree from top to bottom, clears nearby leaves,
     * and schedules a sapling replant at the base.
     */
    private void fellConnectedTree(CitizenEntity citizen, ServerLevel level, BlockPos origin) {
        BlockPos base = findTreeBase(citizen, level, origin);
        Set<BlockPos> connectedLogs = findConnectedLogs(citizen, level, origin);

        if (connectedLogs.isEmpty()) {
            connectedLogs.add(origin.immutable());
        }

        // Sort from top to bottom so logs fall naturally
        List<BlockPos> sortedLogs = new ArrayList<>(connectedLogs);
        sortedLogs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        Set<BlockPos> leavesToBreak = new HashSet<>();

        for (BlockPos logPos : sortedLogs) {
            if (!citizen.belongsToNation(logPos)) continue;
            // Gather surrounding leaves
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos leafPos = logPos.offset(dx, dy, dz);
                        if (citizen.belongsToNation(leafPos) && level.getBlockState(leafPos).is(BlockTags.LEAVES)) {
                            leavesToBreak.add(leafPos.immutable());
                        }
                    }
                }
            }

            // Break the log
            if (level.destroyBlock(logPos, true, citizen)) {
                ItemStack axe = citizen.getMainHandItem();
                if (!axe.isEmpty()) {
                    axe.hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                }
            }
        }

        // Break connected leaves with standard vanilla drops
        for (BlockPos leafPos : leavesToBreak) {
            level.destroyBlock(leafPos, true, citizen);
        }

        // Plan replanting on base
        if (base != null && level.getBlockState(base.below()).is(BlockTags.DIRT) && citizen.belongsToNation(base)) {
            pendingReplantPositions.add(base.immutable());
        }

        citizen.announce("Derrubei uma árvore completa (" + sortedLogs.size() + " troncos) e limpei a folhagem.");
    }

    private Set<BlockPos> findConnectedLogs(CitizenEntity citizen, ServerLevel level, BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        visited.add(start.immutable());

        while (!queue.isEmpty() && visited.size() < MAX_TREE_LOGS) {
            BlockPos current = queue.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && citizen.belongsToNation(neighbor)
                            && isChoppableTreeBlock(level.getBlockState(neighbor))) {
                            visited.add(neighbor.immutable());
                            queue.addLast(neighbor.immutable());
                        }
                    }
                }
            }
        }
        return visited;
    }

    private boolean isChoppableTreeBlock(BlockState state) {
        if (state.is(BlockTags.LOGS)) return true;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("treechop:chopped_log");
    }

    private BlockPos findTreeApproach(CitizenEntity citizen, ServerLevel level, BlockPos trunk) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int verticalOffset = 1; verticalOffset >= -2; verticalOffset--) {
                BlockPos feet = trunk.relative(direction).above(verticalOffset);
                BlockState floor = level.getBlockState(feet.below());
                if (!citizen.belongsToNation(feet) || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    || !floor.isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                double distance = citizen.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = feet.immutable();
                }
            }
        }
        return best;
    }

    private BlockPos findTreeBase(CitizenEntity citizen, ServerLevel level, BlockPos log) {
        BlockPos base = log.immutable();
        for (int depth = 0; depth < 64; depth++) {
            BlockPos below = base.below();
            if (!citizen.belongsToNation(below) || !level.getBlockState(below).is(BlockTags.LOGS)) break;
            base = below.immutable();
        }
        return base;
    }

    private void patrolForWood(CitizenEntity citizen, ServerLevel level) {
        refreshPatrolPoints(citizen, level);
        if (patrolPoints.isEmpty()) {
            return;
        }
        BlockPos target = patrolPoints.get(patrolIndex);
        double distance = citizen.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (distance <= 9.0) {
            patrolIndex++;
            if (patrolIndex >= patrolPoints.size()) {
                patrolIndex = 0;
                if (sweepLogCount == 0) citizen.announce("Completei uma ronda pelo território e vou continuar procurando madeira.");
                else citizen.announce("Completei uma ronda e coletei " + sweepLogCount + " troncos.");
                sweepLogCount = 0;
            }
            target = patrolPoints.get(patrolIndex);
            distance = citizen.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        }
        if (distance > 9.0 && citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
        }
    }

    private void refreshPatrolPoints(CitizenEntity citizen, ServerLevel level) {
        UUID nationId = citizen.getNationId();
        if (patrolNationId != null && patrolNationId.equals(nationId) && !patrolPoints.isEmpty()
            && citizen.tickCount - patrolLastRefreshTick < 200) return;
        patrolNationId = nationId;
        patrolLastRefreshTick = citizen.tickCount;
        patrolIndex = 0;
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
        if (nation == null) {
            patrolPoints = List.of();
            return;
        }
        List<BlockPos> route = new ArrayList<>();
        int radius = CivilizationData.NATION_RADIUS_BLOCKS;
        int spacing = 16;
        int row = 0;
        for (int zOffset = -radius; zOffset <= radius; zOffset += spacing, row++) {
            int startX = row % 2 == 0 ? -radius : radius;
            int endX = row % 2 == 0 ? radius : -radius;
            int step = row % 2 == 0 ? spacing : -spacing;
            for (int xOffset = startX; row % 2 == 0 ? xOffset <= endX : xOffset >= endX; xOffset += step) {
                long distanceSquared = (long) xOffset * xOffset + (long) zOffset * zOffset;
                if (distanceSquared > (long) radius * radius) continue;
                int x = nation.x() + xOffset;
                int z = nation.z() + zOffset;
                if (!level.hasChunkAt(new BlockPos(x, nation.y(), z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos waypoint = new BlockPos(x, y, z);
                if (citizen.belongsToNation(waypoint)) route.add(waypoint);
            }
        }
        if (route.isEmpty()) {
            patrolPoints = List.of();
            return;
        }
        patrolPoints = List.copyOf(route);
        patrolIndex = 0;
        for (int i = 1; i < patrolPoints.size(); i++) {
            if (citizen.blockPosition().distSqr(patrolPoints.get(i)) < citizen.blockPosition().distSqr(patrolPoints.get(patrolIndex))) {
                patrolIndex = i;
            }
        }
    }

    private boolean collectNearbyTreeDrops(CitizenEntity citizen, ServerLevel level) {
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, citizen.getBoundingBox().inflate(32.0)).stream()
            .filter(item -> isTreeDrop(item.getItem()))
            .sorted(Comparator.comparingDouble(citizen::distanceToSqr))
            .toList();
        for (ItemEntity itemEntity : drops) {
            ItemStack stack = itemEntity.getItem();
            if (Math.abs(itemEntity.getY() - citizen.getY()) > 5.0) continue;
            if (citizen.distanceToSqr(itemEntity) > 16.0) {
                citizen.getNavigation().moveTo(itemEntity, 0.9);
                return true;
            }
            ItemStack remainder = citizen.getNpcInventory().addItem(stack.copy());
            if (remainder.isEmpty()) itemEntity.discard();
            else {
                itemEntity.setItem(remainder);
                citizen.announce("Meu inventário encheu; vou parar para não deixar os recursos para trás.");
                citizen.stopProfession();
                return true;
            }
        }
        return false;
    }

    private boolean isTreeDrop(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || isSapling(stack) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    private void replantAvailableSaplings(CitizenEntity citizen, ServerLevel level) {
        if (pendingReplantPositions.isEmpty()) return;
        for (int i = 0; i < pendingReplantPositions.size(); i++) {
            BlockPos pos = pendingReplantPositions.get(i);
            if (!citizen.belongsToNation(pos)) {
                pendingReplantPositions.remove(i--);
                continue;
            }
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(BlockTags.DIRT)
                && !hasLogAbove(level, pos)) {
                ItemStack sapling = removeFirstSapling(citizen);
                if (sapling != null) {
                    Block saplingBlock = saplingBlock(sapling.getItem());
                    if (saplingBlock != null && level.setBlockAndUpdate(pos, saplingBlock.defaultBlockState())) {
                        pendingReplantPositions.remove(i--);
                    }
                }
            }
        }
    }

    private boolean hasLogAbove(ServerLevel level, BlockPos base) {
        for (int y = 1; y <= 32; y++) {
            if (level.getBlockState(base.above(y)).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    private ItemStack removeFirstSapling(CitizenEntity citizen) {
        for (int i = 0; i < citizen.getNpcInventory().getContainerSize(); i++) {
            ItemStack stack = citizen.getNpcInventory().getItem(i);
            if (isSapling(stack)) return stack.split(1);
        }
        return null;
    }

    private boolean isSapling(ItemStack stack) {
        return saplingBlock(stack.getItem()) != null;
    }

    private Block saplingBlock(Item item) {
        Block block = Block.byItem(item);
        return block.defaultBlockState().is(BlockTags.SAPLINGS) ? block : null;
    }

    private BlockPos findNearestLog(CitizenEntity citizen, ServerLevel level) {
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -4, -12), origin.offset(12, 32, 12))) {
            boolean partOfSkippedTree = skippedTreeBase != null && skippedTreeCooldown > 0
                && Math.abs(pos.getX() - skippedTreeBase.getX()) <= 2
                && Math.abs(pos.getZ() - skippedTreeBase.getZ()) <= 2
                && pos.getY() >= skippedTreeBase.getY() && pos.getY() <= skippedTreeBase.getY() + 32;
            if (level.getBlockState(pos).is(BlockTags.LOGS) && !temporaryTower.contains(pos)
                && !partOfSkippedTree && citizen.belongsToNation(pos)) {
                double distance = citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
            }
        }
        if (best != null) return findTreeBase(citizen, level, best);
        return null;
    }

    private void removeTemporaryTower(CitizenEntity citizen, ServerLevel level) {
        int removed = 0;
        for (BlockPos pos : temporaryTower) {
            if ((level.getBlockState(pos).is(Blocks.OAK_LOG) || level.getBlockState(pos).is(Blocks.DIRT))
                && level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) removed++;
        }
        temporaryTower.clear();
        if (removed > 0) citizen.announce("Desmontei a torre temporária (" + removed + " bloco(s)).");
    }

    private int countLogs(CitizenEntity citizen) {
        int count = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private int countItems(CitizenEntity citizen, Item item) {
        int count = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private boolean collectDirt(CitizenEntity citizen, ServerLevel level) {
        BlockPos target = null;
        double nearest = Double.MAX_VALUE;
        BlockPos origin = citizen.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            if (citizen.belongsToNation(pos) && level.getBlockState(pos).is(BlockTags.DIRT) && level.getBlockState(pos.above()).isAir()) {
                double distance = citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance > 2.0 && distance < nearest) { nearest = distance; target = pos.immutable(); }
            }
        }
        ItemStack dirt = new ItemStack(Items.DIRT);
        if (target != null && citizen.belongsToNation(target) && citizen.getNpcInventory().canAddItem(dirt)
            && level.destroyBlock(target, false, citizen)) {
            citizen.swing(InteractionHand.MAIN_HAND);
            citizen.getNpcInventory().addItem(dirt);
            if (!announcedDirtCollection) {
                citizen.announce("Estava sem blocos para subir; coletei terra para continuar.");
                announcedDirtCollection = true;
            }
            return true;
        }
        return false;
    }

    private BlockPos findNearbyLeaf(CitizenEntity citizen, ServerLevel level) {
        BlockPos origin = citizen.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(2, 2, 2))) {
            if (!citizen.belongsToNation(pos) || !level.getBlockState(pos).is(BlockTags.LEAVES)) continue;
            double distance = citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distance < best) { best = distance; nearest = pos.immutable(); }
        }
        return nearest;
    }

    private BlockPos findBlockingLeaf(CitizenEntity citizen, ServerLevel level) {
        if (lumberjackTarget == null) return findNearbyLeaf(citizen, level);
        Direction direction = horizontalDirectionTo(citizen, lumberjackTarget);
        Direction across = direction.getClockWise();
        BlockPos origin = citizen.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int forward = 1; forward <= 4; forward++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                for (int height = -1; height <= 4; height++) {
                    BlockPos candidate = origin.relative(direction, forward).relative(across, lateral).above(height);
                    if (!citizen.belongsToNation(candidate) || !level.getBlockState(candidate).is(BlockTags.LEAVES)) continue;
                    double distance = candidate.distSqr(origin);
                    if (distance < nearestDistance) {
                        nearest = candidate.immutable();
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private void recoverPathIfStuck(CitizenEntity citizen, ServerLevel level) {
        double moved = citizen.distanceToSqr(lastX, lastY, lastZ);
        if (moved < 0.04) stuckTicks++;
        else stuckTicks = 0;
        lastX = citizen.getX(); lastY = citizen.getY(); lastZ = citizen.getZ();
        if (stuckTicks < 10) return;
        stuckTicks = 0;

        Direction direction = horizontalDirectionTo(citizen, lumberjackTarget);
        BlockPos front = citizen.blockPosition().relative(direction);
        BlockPos blockingLeaf = findBlockingLeaf(citizen, level);
        if (blockingLeaf != null) {
            citizen.getLookControl().setLookAt(blockingLeaf.getX() + 0.5, blockingLeaf.getY() + 0.5,
                blockingLeaf.getZ() + 0.5, 30.0f, 30.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            if (level.destroyBlock(blockingLeaf, true, citizen)) {
                citizen.announce("As folhas estavam bloqueando meu caminho; limpei a passagem e vou continuar até a árvore.");
                BlockPos approach = findTreeApproach(citizen, level, lumberjackTarget);
                if (approach != null) citizen.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
                return;
            }
        }
        for (BlockPos obstacle : List.of(front.above(), front.above(2))) {
            BlockState state = level.getBlockState(obstacle);
            if (citizen.belongsToNation(obstacle) && !state.isAir() && !state.hasBlockEntity() && state.getDestroySpeed(level, obstacle) >= 0.0f
                && (state.is(BlockTags.DIRT) || state.is(BlockTags.LOGS))) {
                citizen.swing(InteractionHand.MAIN_HAND);
                if (level.destroyBlock(obstacle, false, citizen)) citizen.announce("Abri caminho removendo um bloco que me prendia.");
                citizen.getNavigation().recomputePath();
                return;
            }
        }
        BlockPos step = front;
        if (citizen.belongsToNation(step) && level.getBlockState(step).isAir() && level.getBlockState(step.above()).isAir()
            && citizen.getNpcInventory().countItem(Items.DIRT) > 0) {
            level.setBlockAndUpdate(step, Blocks.DIRT.defaultBlockState());
            temporaryTower.add(step.immutable());
            citizen.getNpcInventory().removeItemType(Items.DIRT, 1);
            citizen.announce("Fiquei preso. Estou construindo um degrau de terra para sair.");
            citizen.getJumpControl().jump();
            citizen.getNavigation().moveTo(step.getX() + 0.5, step.getY() + 1.0, step.getZ() + 0.5, 0.8);
        } else if (countItems(citizen, Items.DIRT) == 0 && collectDirt(citizen, level)) {
            return;
        } else {
            skippedTreeBase = findTreeBase(citizen, level, lumberjackTarget);
            skippedTreeCooldown = 200;
            lumberjackTarget = null;
            announcedTreeFound = false;
            citizen.getNavigation().stop();
            citizen.announce("Esse caminho para a árvore travou; vou procurar outra rota e continuar trabalhando.");
        }
    }

    private int firstSaplingSlot(CitizenEntity citizen) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            if (isSapling(citizen.getNpcInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    private boolean plantSaplingInTerritory(CitizenEntity citizen, ServerLevel level) {
        if (reforestationCooldown > 0) return false;
        int saplingSlot = firstSaplingSlot(citizen);
        if (saplingSlot < 0) return false;
        ItemStack sapling = citizen.getNpcInventory().getItem(saplingSlot);
        Block saplingBlock = saplingBlock(sapling.getItem());
        if (saplingBlock == null) return false;
        BlockPos origin = citizen.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos probe = new BlockPos(x, origin.getY(), z);
                if (!level.hasChunkAt(probe)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos plot = new BlockPos(x, y, z);
                if (plot.distSqr(origin) <= 4.0 || !citizen.belongsToNation(plot) || isInsideExcludedPlantingArea(citizen, plot, level)
                    || !level.getBlockState(plot).isAir() || !level.getBlockState(plot.above()).isAir()
                    || !level.getBlockState(plot.below()).is(BlockTags.DIRT) || hasLogAbove(level, plot)) continue;
                boolean crowded = hasNearbySapling(level, plot);
                if (!crowded && saplingBlock.defaultBlockState().canSurvive(level, plot)) candidates.add(plot.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> citizen.distanceToSqr(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        for (BlockPos plot : candidates) {
            if (!level.setBlockAndUpdate(plot, saplingBlock.defaultBlockState())) continue;
            sapling.shrink(1);
            if (sapling.isEmpty()) citizen.getNpcInventory().setItem(saplingSlot, ItemStack.EMPTY);
            citizen.getNpcInventory().setChanged();
            reforestationCooldown = 20;
            citizen.announce("Não encontrei árvores por perto; plantei uma muda e vou priorizar o reflorestamento enquanto patrulho o território.");
            return true;
        }
        return false;
    }

    private boolean hasNearbySapling(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -1, -5), center.offset(5, 1, 5))) {
            if (level.getBlockState(pos).is(BlockTags.SAPLINGS)) return true;
        }
        return false;
    }

    private boolean isInsideExcludedPlantingArea(CitizenEntity citizen, BlockPos position, ServerLevel level) {
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(citizen.getNationId());
        if (nation == null) return true;
        long dx = (long) position.getX() - nation.x();
        long dz = (long) position.getZ() - nation.z();
        if (dx * dx + dz * dz < 20L * 20L) return true;
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        return warehouse != null && warehouse.center().distSqr(position) < (warehouse.radius() + 4.0) * (warehouse.radius() + 4.0);
    }

    private Direction horizontalDirectionTo(CitizenEntity citizen, BlockPos target) {
        int dx = target.getX() - citizen.blockPosition().getX();
        int dz = target.getZ() - citizen.blockPosition().getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Item bestInventoryAxe(CitizenEntity citizen) {
        for (Item axe : List.of(Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
                if (citizen.getNpcInventory().getItem(slot).is(axe)) return axe;
            }
        }
        return null;
    }

    private boolean isAxe(ItemStack stack) {
        return stack.is(Items.DIAMOND_AXE) || stack.is(Items.IRON_AXE)
            || stack.is(Items.STONE_AXE) || stack.is(Items.WOODEN_AXE);
    }
}
