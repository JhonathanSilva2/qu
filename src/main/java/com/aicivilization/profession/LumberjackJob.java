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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private static final int TREE_SEARCH_COOLDOWN_TICKS = 30;
    private static final int ABANDONED_TREE_TICKS = 1200;
    private static final int MAX_APPROACH_PATH_CHECKS = 6;

    private int lumberjackCooldown;
    private BlockPos lumberjackTarget;
    private BlockPos plannedApproach;
    private BlockPos plannedApproachFor;
    private BlockPos failedApproachFor;
    private int approachRetryCooldown;
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
    private int treeSearchCooldown;
    private final Map<BlockPos, Long> abandonedTreeBases = new HashMap<>();
    private int saplingLookupCooldown;
    private int reforestationCooldown;
    private boolean lumberjackDeliveryPending;
    private final List<BlockPos> pendingReplantPositions = new ArrayList<>();
    private int toolLookupCooldown;
    private boolean toolPickupPending;
    private WorkPhase phase = WorkPhase.SEARCHING;

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
        this.plannedApproach = null;
        this.plannedApproachFor = null;
        this.failedApproachFor = null;
        this.approachRetryCooldown = 0;
        this.chopProgress = 0.0f;
        this.stuckTicks = 0;
        this.lastX = citizen.getX();
        this.lastY = citizen.getY();
        this.lastZ = citizen.getZ();
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
        this.abandonedTreeBases.clear();
        this.treeSearchCooldown = 0;
        this.saplingLookupCooldown = 0;
        this.reforestationCooldown = 0;
        this.lumberjackDeliveryPending = false;
        this.toolLookupCooldown = 0;
        this.phase = WorkPhase.SEARCHING;

        if (!isAxe(citizen.getMainHandItem()) && bestInventoryAxe(citizen) == null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        }

        citizen.announce("Vou trabalhar como lenhador e procurar árvores.");
        return true;
    }

    @Override
    public void stop(CitizenEntity citizen, ServerLevel level, boolean announce) {
        clearDestroyProgress(citizen, level);
        this.lumberjackTarget = null;
        this.plannedApproach = null;
        this.plannedApproachFor = null;
        this.failedApproachFor = null;
        this.chopProgress = 0.0f;
        this.phase = WorkPhase.IDLE;
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
        output.putString("LumberjackPhase", phase.name());
        output.store("PendingReplant", BlockPos.CODEC.listOf(), List.copyOf(pendingReplantPositions));
    }

    @Override
    public void load(ValueInput input) {
        lumberjackTarget = input.read("LumberjackTarget", BlockPos.CODEC).orElse(null);
        chopProgress = input.getFloatOr("ChopProgress", 0.0f);
        phase = WorkPhase.fromId(input.getStringOr("LumberjackPhase", WorkPhase.SEARCHING.name()));
        pendingReplantPositions.clear();
        input.read("PendingReplant", BlockPos.CODEC.listOf()).ifPresent(pendingReplantPositions::addAll);
    }

    @Override
    public String workStatus(CitizenEntity citizen, ServerLevel level) {
        if (StorageJob.hasLumberjackDeliveryResources(citizen)) return "Entregando recursos ao armazém";
        if (phase == WorkPhase.WAITING_FOR_TOOL) return "Solicitando ou buscando um machado no Storager";
        if (phase == WorkPhase.WAITING_FOR_SAPLING) return "Solicitando mudas ao Storager";
        if (phase == WorkPhase.COLLECTING) return "Recolhendo os recursos derrubados pela árvore";
        if (phase == WorkPhase.RECOVERING) return "Desobstruindo o caminho para uma árvore";
        if (phase == WorkPhase.REFORESTING) return "Replantando árvores no território";
        if (phase == WorkPhase.PATROLLING) return "Percorrendo o território à procura de madeira";
        if (lumberjackTarget == null) return citizen.getNavigation().isDone() ? "Procurando madeira" : "Patrulhando o território";
        if (!isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) return "Procurando outra árvore";
        return citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5) > 9.0 ? "Indo até uma árvore" : "Cortando madeira";
    }

    @Override
    public String debugStatus(CitizenEntity citizen, ServerLevel level) {
        String target = lumberjackTarget == null ? "none" : lumberjackTarget.toShortString()
            + "/" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(lumberjackTarget).getBlock());
        BlockPos leaf = findBlockingLeaf(citizen, level);
        return "phase=" + phase.name().toLowerCase(java.util.Locale.ROOT) + ", target=" + target
            + ", approach=" + (plannedApproach == null ? "none" : plannedApproach.toShortString())
            + ", chop=" + String.format(java.util.Locale.ROOT, "%.2f", chopProgress)
            + ", stuckTicks=" + stuckTicks + ", abandonedTrees=" + abandonedTreeBases.size()
            + ", blockingLeaf=" + (leaf == null ? "none" : leaf.toShortString())
            + ", pendingReplant=" + pendingReplantPositions.size();
    }

    @Override
    public boolean needsNavigation(CitizenEntity citizen, ServerLevel level) {
        BlockPos destination = plannedApproach != null ? plannedApproach : lumberjackTarget;
        return destination != null && citizen.distanceToSqr(destination.getX() + 0.5, destination.getY(),
            destination.getZ() + 0.5) > 9.0;
    }

    @Override
    public void tick(CitizenEntity citizen, ServerLevel level) {
        if (lumberjackCooldown-- > 0) return;
        lumberjackCooldown = 10;
        doLumberjackWork(citizen, level);
    }

    private void doLumberjackWork(CitizenEntity citizen, ServerLevel level) {
        treeSearchCooldown = Math.max(0, treeSearchCooldown - 10);
        approachRetryCooldown = Math.max(0, approachRetryCooldown - 10);
        expireAbandonedTrees(level.getGameTime());
        if (saplingLookupCooldown > 0) saplingLookupCooldown -= 10;
        if (reforestationCooldown > 0) reforestationCooldown -= 10;
        if (collectNearbyTreeDrops(citizen, level)) {
            phase = WorkPhase.COLLECTING;
            return;
        }
        replantAvailableSaplings(citizen, level);

        if (StorageJob.hasLumberjackDeliveryResources(citizen)) {
            phase = WorkPhase.DELIVERING;
            if (!lumberjackDeliveryPending) {
                citizen.announce(StorageJob.lumberjackDeliveryMessage(citizen));
                lumberjackDeliveryPending = true;
            }
            if (StorageJob.deliverLumberjackResources(citizen, level)) return;
        }
        lumberjackDeliveryPending = false;

        if (isAxe(citizen.getMainHandItem())) {
            phase = WorkPhase.SEARCHING;
            toolPickupPending = false;
        } else if (bestInventoryAxe(citizen) != null) {
            phase = WorkPhase.EQUIPPING;
            ItemStack axe = citizen.getNpcInventory().removeItemType(bestInventoryAxe(citizen), 1);
            citizen.setItemSlot(EquipmentSlot.MAINHAND, axe);
            toolPickupPending = false;
        } else if (toolPickupPending) {
            phase = WorkPhase.WAITING_FOR_TOOL;
            if (StorageJob.retrieveBestAxe(citizen, level)) return;
            toolPickupPending = false;
        } else if (toolLookupCooldown > 0) {
            toolLookupCooldown -= 10;
            if (StorageJob.retrieveBestAxe(citizen, level)) {
                toolPickupPending = !isAxe(citizen.getMainHandItem());
                phase = WorkPhase.WAITING_FOR_TOOL;
                return;
            }
            phase = WorkPhase.SEARCHING;
        } else {
            phase = WorkPhase.WAITING_FOR_TOOL;
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
        if (lumberjackTarget != null && !isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) {
            clearCurrentTarget(citizen, level);
        }
        if (lumberjackTarget == null && treeSearchCooldown == 0) {
            phase = WorkPhase.SEARCHING;
            lumberjackTarget = findNearestLog(citizen, level);
            treeSearchCooldown = TREE_SEARCH_COOLDOWN_TICKS;
            if (lumberjackTarget == null) {
                if (plantSaplingInTerritory(citizen, level)) {
                    phase = WorkPhase.REFORESTING;
                    return;
                }
                if (firstSaplingSlot(citizen) < 0 && saplingLookupCooldown <= 0) {
                    saplingLookupCooldown = 100;
                    if (StorageJob.retrieveFirstSapling(citizen, level)) {
                        phase = WorkPhase.WAITING_FOR_SAPLING;
                        return;
                    }
                }
                phase = WorkPhase.PATROLLING;
                patrolForWood(citizen, level);
                return;
            }
            plannedApproachFor = null;
            plannedApproach = null;
            failedApproachFor = null;
            approachRetryCooldown = 0;
            if (!announcedTreeFound) {
                citizen.announce("Encontrei uma árvore; vou começar a coleta.");
                announcedTreeFound = true;
            }
        }

        if (lumberjackTarget == null) {
            phase = WorkPhase.PATROLLING;
            patrolForWood(citizen, level);
            return;
        }

        phase = WorkPhase.NAVIGATING;
        BlockPos approach = findTreeApproach(citizen, level, lumberjackTarget);
        if (approach == null) {
            phase = WorkPhase.RECOVERING;
            recoverPathIfStuck(citizen, level);
            return;
        }
        double horizontalDistance = citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5);
        boolean closeEnoughToChop = horizontalDistance <= 9.0;
        if (!closeEnoughToChop || !citizen.getNavigation().isDone()) {
            if (!closeEnoughToChop && (citizen.getNavigation().isDone() || citizen.getNavigation().getTargetPos() == null
                || !citizen.getNavigation().getTargetPos().equals(approach))) {
                citizen.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
            }
            recoverPathIfStuck(citizen, level);
            return;
        }

        phase = WorkPhase.CHOPPING;

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
            plannedApproach = null;
            plannedApproachFor = null;
            failedApproachFor = null;
            approachRetryCooldown = 0;
            treeSearchCooldown = 0;
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
        if (trunk.equals(plannedApproachFor) && plannedApproach != null
            && isSafeApproachPosition(citizen, level, plannedApproach)) return plannedApproach;
        if (trunk.equals(failedApproachFor) && approachRetryCooldown > 0) return null;

        plannedApproachFor = trunk.immutable();
        plannedApproach = null;
        List<ApproachCandidate> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int verticalOffset = 1; verticalOffset >= -2; verticalOffset--) {
                BlockPos feet = trunk.relative(direction).above(verticalOffset);
                if (!isSafeApproachPosition(citizen, level, feet)) continue;
                double distance = citizen.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                candidates.add(new ApproachCandidate(feet.immutable(), distance));
            }
        }
        candidates.sort(Comparator.comparingDouble(ApproachCandidate::distance));

        PathNavigation navigation = citizen.getNavigation();
        int checked = 0;
        for (ApproachCandidate candidate : candidates) {
            Path path = navigation.createPath(candidate.position(), 0);
            if (path != null && path.canReach()) {
                plannedApproach = candidate.position();
                failedApproachFor = null;
                approachRetryCooldown = 0;
                return plannedApproach;
            }
            if (++checked >= MAX_APPROACH_PATH_CHECKS) break;
        }
        failedApproachFor = trunk.immutable();
        approachRetryCooldown = TREE_SEARCH_COOLDOWN_TICKS;
        return null;
    }

    private boolean isSafeApproachPosition(CitizenEntity citizen, ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        return citizen.belongsToNation(feet) && citizen.belongsToNation(head)
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
            && floor.isFaceSturdy(level, floorPos, Direction.UP);
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
            if (isChoppableTreeBlock(level.getBlockState(pos)) && !temporaryTower.contains(pos)
                && !isInAbandonedTree(pos) && citizen.belongsToNation(pos)) {
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
        if (lumberjackTarget == null) return;
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
                citizen.announce("As folhas bloqueavam o caminho; limpei a passagem.");
                plannedApproach = null;
                plannedApproachFor = null;
                failedApproachFor = null;
                approachRetryCooldown = 0;
                BlockPos approach = findTreeApproach(citizen, level, lumberjackTarget);
                if (approach != null) citizen.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
                return;
            }
        }
        BlockPos obstacleTowardTarget = findObstacleTowardTarget(citizen, level, direction);
        if (obstacleTowardTarget != null) {
            BlockState state = level.getBlockState(obstacleTowardTarget);
            citizen.getLookControl().setLookAt(obstacleTowardTarget.getX() + 0.5,
                obstacleTowardTarget.getY() + 0.5, obstacleTowardTarget.getZ() + 0.5, 30.0f, 30.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            if (level.destroyBlock(obstacleTowardTarget, false, citizen))
                citizen.announce("Abri um caminho para chegar à árvore.");
            plannedApproach = null;
            plannedApproachFor = null;
            failedApproachFor = null;
            approachRetryCooldown = 0;
            citizen.getNavigation().recomputePath();
            return;
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
            plannedApproach = null;
            plannedApproachFor = null;
            failedApproachFor = null;
            approachRetryCooldown = 0;
            citizen.getNavigation().moveTo(step.getX() + 0.5, step.getY() + 1.0, step.getZ() + 0.5, 0.8);
        } else if (countItems(citizen, Items.DIRT) == 0 && collectDirt(citizen, level)) {
            return;
        } else {
            BlockPos blockedBase = findTreeBase(citizen, level, lumberjackTarget);
            if (blockedBase != null) abandonedTreeBases.put(blockedBase.immutable(), level.getGameTime() + ABANDONED_TREE_TICKS);
            clearCurrentTarget(citizen, level);
            treeSearchCooldown = 0;
            announcedTreeFound = false;
            citizen.getNavigation().stop();
            citizen.announce("Não encontrei uma rota segura para essa árvore; vou tentar outra.");
        }
    }

    private BlockPos findObstacleTowardTarget(CitizenEntity citizen, ServerLevel level, Direction direction) {
        BlockPos origin = citizen.blockPosition();
        for (int forward = 1; forward <= 2; forward++) {
            for (int height = 1; height <= 2; height++) {
                BlockPos candidate = origin.relative(direction, forward).above(height);
                BlockState state = level.getBlockState(candidate);
                if (citizen.belongsToNation(candidate) && !state.isAir() && !state.hasBlockEntity()
                    && state.getDestroySpeed(level, candidate) >= 0.0f
                    && (state.is(BlockTags.DIRT) || state.is(BlockTags.LOGS))) return candidate;
            }
        }
        return null;
    }

    private void clearDestroyProgress(CitizenEntity citizen, ServerLevel level) {
        if (lumberjackTarget != null) level.destroyBlockProgress(citizen.getId(), lumberjackTarget, -1);
    }

    private void clearCurrentTarget(CitizenEntity citizen, ServerLevel level) {
        clearDestroyProgress(citizen, level);
        lumberjackTarget = null;
        plannedApproach = null;
        plannedApproachFor = null;
        failedApproachFor = null;
        approachRetryCooldown = 0;
        chopProgress = 0.0f;
    }

    private void expireAbandonedTrees(long gameTime) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = abandonedTreeBases.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= gameTime) iterator.remove();
        }
    }

    private boolean isInAbandonedTree(BlockPos position) {
        for (BlockPos base : abandonedTreeBases.keySet()) {
            if (Math.abs(position.getX() - base.getX()) <= 2
                && Math.abs(position.getZ() - base.getZ()) <= 2
                && position.getY() >= base.getY() && position.getY() <= base.getY() + MAX_TREE_LOGS) return true;
        }
        return false;
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

    private record ApproachCandidate(BlockPos position, double distance) {}

    private enum WorkPhase {
        IDLE,
        SEARCHING,
        NAVIGATING,
        CHOPPING,
        COLLECTING,
        DELIVERING,
        EQUIPPING,
        WAITING_FOR_TOOL,
        WAITING_FOR_SAPLING,
        REFORESTING,
        PATROLLING,
        RECOVERING;

        private static WorkPhase fromId(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException exception) {
                return SEARCHING;
            }
        }
    }
}
