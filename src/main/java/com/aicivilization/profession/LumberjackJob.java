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
    private final Set<UUID> ignoredDrops = new HashSet<>();
    private UUID currentDropTargetId;
    private int dropChaseTicks;
    private WorkPhase phase = WorkPhase.SEARCHING;
    private int activeQuadrant = 0; // 0=NL, 1=NO, 2=SL, 3=SO

    private static String quadrantName(int q) {
        return switch (q) {
            case 0 -> "NL";
            case 1 -> "NO";
            case 2 -> "SL";
            default -> "SO";
        };
    }

    private static boolean isInQuadrant(int dx, int dz, int q) {
        return switch (q) {
            case 0 -> dx >= 0 && dz <= 0; // NL
            case 1 -> dx <= 0 && dz <= 0; // NO
            case 2 -> dx >= 0 && dz >= 0; // SL
            default -> dx <= 0 && dz >= 0; // SO
        };
    }

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
        this.ignoredDrops.clear();
        this.currentDropTargetId = null;
        this.dropChaseTicks = 0;
        this.treeSearchCooldown = 0;
        this.saplingLookupCooldown = 0;
        this.reforestationCooldown = 0;
        this.lumberjackDeliveryPending = false;
        this.toolLookupCooldown = 0;
        this.phase = WorkPhase.SEARCHING;

        citizen.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, 0.0f);
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
        if (phase == WorkPhase.WAITING_FOR_TOOL) {
            String waitDesc = StorageJob.describeOpenWaitRequest(citizen, level);
            return !waitDesc.isEmpty() ? waitDesc : "Solicitando ou buscando um machado no Storager";
        }
        if (phase == WorkPhase.WAITING_FOR_SAPLING) return "Buscando mudas no armazém para reflorestamento";
        if (phase == WorkPhase.COLLECTING) return "Recolhendo os recursos derrubados pela árvore";
        if (phase == WorkPhase.RECOVERING) return "Desobstruindo o caminho para uma árvore";
        if (phase == WorkPhase.REFORESTING) return "Plantando mudas na área de reflorestamento (50x50 NO)";
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
        if (phase == WorkPhase.DELIVERING || phase == WorkPhase.WAITING_FOR_TOOL || phase == WorkPhase.WAITING_FOR_SAPLING) {
            CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
            if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
                return !StorageJob.isCitizenInsideWarehouse(citizen, warehouse);
            }
        }
        if (phase == WorkPhase.PATROLLING && !patrolPoints.isEmpty() && patrolIndex < patrolPoints.size()) {
            BlockPos target = patrolPoints.get(patrolIndex);
            return citizen.distanceToSqr(target.getX() + 0.5, citizen.getY(), target.getZ() + 0.5) > 16.0;
        }
        if (phase == WorkPhase.COLLECTING) {
            return true;
        }
        BlockPos destination = plannedApproach != null ? plannedApproach : lumberjackTarget;
        return destination != null && citizen.distanceToSqr(destination.getX() + 0.5, citizen.getY(),
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

        if (lumberjackDeliveryPending || StorageJob.hasLumberjackDeliveryResources(citizen)) {
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
        double horizontalDistance = citizen.distanceToSqr(lumberjackTarget.getX() + 0.5, citizen.getY(),
            lumberjackTarget.getZ() + 0.5);
        boolean closeEnoughToChop = horizontalDistance <= 9.0 && Math.abs(citizen.getY() - lumberjackTarget.getY()) <= 3.5;
        if (!closeEnoughToChop) {
            CitizenEntity.moveTowardsPos(citizen, level, approach, 0.9);
            recoverPathIfStuck(citizen, level);
            return;
        }
        citizen.getNavigation().stop();

        phase = WorkPhase.CHOPPING;

        Item targetItem = level.getBlockState(lumberjackTarget).getBlock().asItem();
        if (!citizen.getNpcInventory().canAddItem(new ItemStack(targetItem))) {
            // Inventory filled up: trigger delivery to Warehouse instead of stopping the profession!
            clearCurrentTarget(citizen, level);
            lumberjackDeliveryPending = true;
            phase = WorkPhase.DELIVERING;
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

        // Plan replanting on base ONLY if outside the 30-block no-replant inner radius and road corridors!
        if (base != null && level.getBlockState(base.below()).is(BlockTags.DIRT)
            && citizen.belongsToNation(base) && !isInsideExcludedPlantingArea(citizen, base, level)) {
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
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > 5) continue;
                for (int verticalOffset = 1; verticalOffset >= -2; verticalOffset--) {
                    BlockPos feet = trunk.offset(dx, verticalOffset, dz);
                    if (!isSafeApproachPosition(citizen, level, feet)) continue;
                    double distance = citizen.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    candidates.add(new ApproachCandidate(feet.immutable(), distance));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(ApproachCandidate::distance));

        PathNavigation navigation = citizen.getNavigation();
        int checked = 0;
        for (ApproachCandidate candidate : candidates) {
            Path path = navigation.createPath(candidate.position(), 1);
            if (path != null && (path.canReach() || candidate.distance() <= 16.0)) {
                plannedApproach = candidate.position();
                failedApproachFor = null;
                approachRetryCooldown = 0;
                return plannedApproach;
            }
            if (++checked >= MAX_APPROACH_PATH_CHECKS) break;
        }
        // Fallback: if we have a safe ground candidate next to the tree, try walking to the closest one before abandoning!
        if (!candidates.isEmpty()) {
            plannedApproach = candidates.get(0).position();
            return plannedApproach;
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
            && floor.isFaceSturdy(level, floorPos, Direction.UP)
            && !floor.is(BlockTags.LEAVES) && !floor.is(BlockTags.LOGS);
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
        double distance = citizen.distanceToSqr(target.getX() + 0.5, citizen.getY(), target.getZ() + 0.5);
        if (distance <= 25.0) {
            patrolIndex++;
            if (patrolIndex >= patrolPoints.size()) {
                patrolIndex = 0;
                String finishedQ = quadrantName(activeQuadrant);
                activeQuadrant = (activeQuadrant + 1) % 4;
                patrolPoints = List.of(); // força gerar a rota do próximo quadrante
                if (sweepLogCount == 0) citizen.announce("Explorei todo o quadrante " + finishedQ + " sem encontrar árvores; avançando para " + quadrantName(activeQuadrant) + ".");
                else citizen.announce("Concluí o quadrante " + finishedQ + " (" + sweepLogCount + " troncos); avançando para " + quadrantName(activeQuadrant) + ".");
                sweepLogCount = 0;
                refreshPatrolPoints(citizen, level);
                if (patrolPoints.isEmpty()) return;
            }
            target = patrolPoints.get(patrolIndex);
            distance = citizen.distanceToSqr(target.getX() + 0.5, citizen.getY(), target.getZ() + 0.5);
        }
        if (distance > 25.0) {
            CitizenEntity.moveTowardsPos(citizen, level, target, 0.9);
        }
    }

    private void refreshPatrolPoints(CitizenEntity citizen, ServerLevel level) {
        UUID nationId = citizen.getNationId();
        if (patrolNationId != null && patrolNationId.equals(nationId) && !patrolPoints.isEmpty()
            && citizen.tickCount - patrolLastRefreshTick < 2000) return;
        patrolNationId = nationId;
        patrolLastRefreshTick = citizen.tickCount;
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
        if (nation == null) {
            patrolPoints = List.of();
            patrolIndex = 0;
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
                if (!isInQuadrant(xOffset, zOffset, activeQuadrant)) continue;
                long distanceSquared = (long) xOffset * xOffset + (long) zOffset * zOffset;
                if (distanceSquared > (long) radius * radius) continue;
                int x = nation.x() + xOffset;
                int z = nation.z() + zOffset;
                if (!level.hasChunkAt(new BlockPos(x, nation.y(), z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                while (y > level.getMinY() + 5) {
                    BlockPos below = new BlockPos(x, y - 1, z);
                    BlockState state = level.getBlockState(below);
                    if (state.is(BlockTags.LOGS) || BuilderJob.isClearableVegetation(state)) y--;
                    else break;
                }
                if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
                BlockPos waypoint = new BlockPos(x, y, z);
                if (citizen.belongsToNation(waypoint)) route.add(waypoint);
            }
        }
        if (route.isEmpty()) {
            patrolPoints = List.of();
            patrolIndex = 0;
            return;
        }
        boolean hadRoute = !patrolPoints.isEmpty();
        patrolPoints = List.copyOf(route);
        if (!hadRoute || patrolIndex >= patrolPoints.size()) {
            patrolIndex = 0;
            for (int i = 1; i < patrolPoints.size(); i++) {
                if (citizen.blockPosition().distSqr(patrolPoints.get(i)) < citizen.blockPosition().distSqr(patrolPoints.get(patrolIndex))) {
                    patrolIndex = i;
                }
            }
        }
    }

    private boolean collectNearbyTreeDrops(CitizenEntity citizen, ServerLevel level) {
        if (citizen.tickCount % 600 == 0) ignoredDrops.clear();
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, citizen.getBoundingBox().inflate(16.0, 5.0, 16.0)).stream()
            .filter(item -> item.isAlive() && !ignoredDrops.contains(item.getUUID()) && isTreeDrop(item.getItem())
                && citizen.belongsToNation(item.blockPosition()))
            .sorted(Comparator.comparingDouble(citizen::distanceToSqr))
            .toList();
        for (ItemEntity itemEntity : drops) {
            ItemStack stack = itemEntity.getItem();
            if (Math.abs(itemEntity.getY() - citizen.getY()) > 4.0) continue;
            if (!citizen.getNpcInventory().canAddItem(stack)) continue;
            if (citizen.distanceToSqr(itemEntity) > 9.0) {
                if (!itemEntity.getUUID().equals(currentDropTargetId)) {
                    currentDropTargetId = itemEntity.getUUID();
                    dropChaseTicks = 0;
                } else {
                    dropChaseTicks++;
                    if (dropChaseTicks >= 8) {
                        // Item is stuck on top of a canopy or unreachable hole: ignore it and move on!
                        ignoredDrops.add(itemEntity.getUUID());
                        currentDropTargetId = null;
                        dropChaseTicks = 0;
                        continue;
                    }
                }
                if (citizen.getNavigation().isDone() || citizen.getNavigation().getTargetPos() == null
                    || citizen.getNavigation().getTargetPos().distToCenterSqr(itemEntity.position()) > 9.0) {
                    citizen.getNavigation().moveTo(itemEntity, 0.95);
                }
                return true;
            }
            currentDropTargetId = null;
            dropChaseTicks = 0;
            ItemStack remainder = citizen.getNpcInventory().addItem(stack.copy());
            if (remainder.isEmpty()) itemEntity.discard();
            else {
                itemEntity.setItem(remainder);
                lumberjackDeliveryPending = true;
                return false;
            }
        }
        currentDropTargetId = null;
        dropChaseTicks = 0;
        return false;
    }

    private boolean isTreeDrop(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || isSapling(stack) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    private void replantAvailableSaplings(CitizenEntity citizen, ServerLevel level) {
        if (pendingReplantPositions.isEmpty()) return;
        CivilizationData.Nation nation = citizen.getNationId() == null
            ? null : CivilizationData.get(level).getNation(citizen.getNationId());
        for (int i = 0; i < pendingReplantPositions.size(); i++) {
            BlockPos pos = pendingReplantPositions.get(i);
            if (!citizen.belongsToNation(pos) || nation == null
                || !isInsideReforestationZone(nation, pos)
                || isInsideExcludedPlantingArea(citizen, pos, level)) {
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
        // Only consider trunks reachable from the ground: scan low (Y -4..+6), resolve the trunk base,
        // and require natural ground under the base. Canopy branches / floating logs are ignored so the
        // Lumberjack never tries to climb into the sky to reach a log high in the leaves.
        Set<BlockPos> checkedBases = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -4, -16), origin.offset(16, 6, 16))) {
            if (!isChoppableTreeBlock(level.getBlockState(pos)) || temporaryTower.contains(pos)
                || isInAbandonedTree(pos) || !citizen.belongsToNation(pos)) continue;
            BlockPos base = findTreeBase(citizen, level, pos.immutable());
            if (!checkedBases.add(base)) continue;
            BlockState ground = level.getBlockState(base.below());
            if (!(ground.is(BlockTags.DIRT) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL)
                || ground.is(Blocks.MYCELIUM) || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.MUD))) continue;
            if (Math.abs(base.getY() - origin.getY()) > 4) continue;
            double distance = citizen.distanceToSqr(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            if (distance < bestDistance) { bestDistance = distance; best = base; }
        }
        return best;
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
        if (stuckTicks < 4) return;

        Direction direction = horizontalDirectionTo(citizen, lumberjackTarget);
        BlockPos front = citizen.blockPosition().relative(direction);
        BlockPos blockingLeaf = findBlockingLeaf(citizen, level);
        if (blockingLeaf != null) {
            citizen.getLookControl().setLookAt(blockingLeaf.getX() + 0.5, blockingLeaf.getY() + 0.5,
                blockingLeaf.getZ() + 0.5, 30.0f, 30.0f);
            citizen.swing(InteractionHand.MAIN_HAND);
            // Clear a 3x3x3 pocket of leaves around the blocking leaf so he doesn't break 1 leaf at a time!
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos lp = blockingLeaf.offset(dx, dy, dz);
                        if (citizen.belongsToNation(lp) && level.getBlockState(lp).is(BlockTags.LEAVES)) {
                            level.destroyBlock(lp, true, citizen);
                        }
                    }
                }
            }
            citizen.getJumpControl().jump();
            plannedApproach = null;
            plannedApproachFor = null;
            failedApproachFor = null;
            approachRetryCooldown = 0;
            BlockPos approach = findTreeApproach(citizen, level, lumberjackTarget);
            if (approach != null) CitizenEntity.moveTowardsPos(citizen, level, approach, 0.9);
            return;
        }
        if (stuckTicks < 8) {
            citizen.getJumpControl().jump();
            citizen.getNavigation().recomputePath();
            return;
        }
        stuckTicks = 0;
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
        // No more dirt staircases: if the path is still blocked, abandon this tree and pick another one on the ground.
        {
            removeTemporaryTower(citizen, level);
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

        CivilizationData.Nation nation = CivilizationData.get(level).getNation(citizen.getNationId());
        if (nation != null && !isInsideReforestationZone(nation, citizen.blockPosition())) {
            // Walk directly into the 50x50 NW Reforestation Zone (center at x - 32, z - 32, outside 30-block radius)
            int targetX = nation.x() - 32;
            int targetZ = nation.z() - 32;
            BlockPos zoneTarget = new BlockPos(targetX, nation.y(), targetZ);
            if (citizen.distanceToSqr(targetX + 0.5, citizen.getY(), targetZ + 0.5) > 64.0) {
                CitizenEntity.moveTowardsPos(citizen, level, zoneTarget, 0.95);
                return true;
            }
        }

        BlockPos origin = citizen.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
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
        candidates.sort(Comparator.comparingDouble(pos -> {
            double dist = citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            // Strongly prefer planting inside the 50x50 NW Reforestation Zone
            boolean inZone = nation != null && isInsideReforestationZone(nation, pos);
            return inZone ? dist : dist + 1000.0;
        }));
        for (BlockPos plot : candidates) {
            if (!level.setBlockAndUpdate(plot, saplingBlock.defaultBlockState())) continue;
            sapling.shrink(1);
            if (sapling.isEmpty()) citizen.getNpcInventory().setItem(saplingSlot, ItemStack.EMPTY);
            citizen.getNpcInventory().setChanged();
            reforestationCooldown = 20;
            citizen.announce("Plantei uma muda na área de reflorestamento (50x50 NO).");
            return true;
        }
        return false;
    }

    private boolean hasNearbySapling(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -1, -4), center.offset(4, 1, 4))) {
            if (level.getBlockState(pos).is(BlockTags.SAPLINGS)) return true;
        }
        return false;
    }

    /**
     * Checks if a position is inside the 50x50 Reforestation Zone in the Northwest quadrant
     * (dx in [-56 .. -7], dz in [-56 .. -7], and outside the 30-block no-replant inner radius).
     */
    public static boolean isInsideReforestationZone(CivilizationData.Nation nation, BlockPos position) {
        if (nation == null) return false;
        int dx = position.getX() - nation.x();
        int dz = position.getZ() - nation.z();
        if (dx < -56 || dx > -7 || dz < -56 || dz > -7) return false;
        return (long) dx * dx + (long) dz * dz >= 30L * 30L;
    }

    public static boolean isInsideReforestationArea(CivilizationData.Nation nation, BlockPos position) {
        int relX = position.getX() - nation.x();
        int relZ = position.getZ() - nation.z();
        // 50x50 reforestation zone in the Northwest quadrant, outside the 30-block inner radius
        return relX >= -80 && relX <= -31 && relZ >= -80 && relZ <= -31;
    }

    private boolean isInsideExcludedPlantingArea(CitizenEntity citizen, BlockPos position, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null) return true;
        if (!isInsideReforestationArea(nation, position)) return true;
        long dx = (long) position.getX() - nation.x();
        long dz = (long) position.getZ() - nation.z();
        // 30-block inner radius around Village Center: never replant saplings here!
        if (dx * dx + dz * dz < 30L * 30L) return true;
        // Keep 3-block road corridors and 2-block margins clear of saplings (N/S/E/W + Mine "T" branch)
        if (Math.abs(dx) <= 3 || Math.abs(dz) <= 3) return true;
        if (Math.abs(dx - 12) <= 3 && dz >= 0) return true;
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse != null && warehouse.center().distSqr(position) < (warehouse.radius() + 4.0) * (warehouse.radius() + 4.0)) {
            return true;
        }
        CivilizationData.MineSite mine = data.getMineSite(citizen.getNationId());
        return mine != null && mine.origin().distSqr(position) < 64.0;
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
