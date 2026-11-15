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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Builds and maintains three-block-wide roads that all start at the town center:
 * center -> warehouse, center -> mine entrance, and center -> the four borders.
 * Every road "step" (the 3 lanes across the road) shares one single height; consecutive steps
 * may only rise or drop by one block. Finished road cells are recorded in a ledger that is
 * inspected a few cells per tick; damaged cells go to a repair queue that is serviced in short
 * batches without taking the Builder away from construction for long.
 */
public final class BuilderJob implements ProfessionJob {
    public static final String ID = "builder";
    public static final String LABEL = "Builder";
    private static final int TICK_INTERVAL = 4;
    private static final int[] LANE_OFFSETS = {-1, 0, 1};
    private static final Direction[] ROAD_DIRECTIONS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    // Route ids, in build priority order
    private static final int ROUTE_WAREHOUSE = 0;
    private static final int ROUTE_MINE = 1;
    private static final int ROUTE_CARDINAL_FIRST = 2;
    private static final int ROUTE_COUNT = 6;
    private static final int UNSET_Y = Integer.MIN_VALUE;
    private static final int HEADROOM = 3;
    private static final int MAX_FILL_DEPTH = 4;
    private static final int MAX_STEPS_CHECKED_PER_TICK = 8;

    // Maintenance tuning
    private static final int INSPECT_PER_TICK = 8;          // ledger cells verified per builder tick
    private static final int REPAIR_EVERY_ACTIONS = 150;    // construction actions between routine repair batches
    private static final int REPAIR_URGENT_QUEUE = 16;      // this many damaged cells interrupt construction
    private static final double REPAIR_NEARBY_SQR = 144.0;  // damage within 12 blocks is fixed right away
    private static final double REPAIR_CLUSTER_SQR = 64.0;  // a batch keeps fixing cells within 8 blocks
    private static final int REPAIR_MAX_ATTEMPTS = 40;

    private static GridBlueprint VILLAGE_CENTER_BLUEPRINT;
    private int cooldown;
    private BlockPos townCenter;
    private int warehousePlotCursor;
    private int plazaCursor;
    private String pendingResourceId = "";
    private boolean shovelRequestSent;
    private boolean pickaxeRequestSent;
    private boolean stairRequestSent;
    private boolean dirtSearchAnnounced;
    private BlockPos dirtSourceTarget;
    private boolean completed;
    private boolean preparingPlot;

    // Road construction progress
    private int routeIndex;
    private int routeStep;
    private int routeY = UNSET_Y;
    private int stepTargetY = UNSET_Y;
    private int routeDoneMask;
    private BlockPos mineRouteOrigin;

    // Road maintenance
    private final List<BlockPos> roadLedger = new ArrayList<>();
    private final Set<BlockPos> roadLedgerSet = new HashSet<>();
    private final LinkedHashSet<BlockPos> repairQueue = new LinkedHashSet<>();
    private int inspectCursor;
    private int actionsSinceRepair;
    private BlockPos repairTarget;
    private int repairAttempts;
    private String currentSubTask = "Iniciando";
    private int reforestationCursor;
    private boolean reforestationDone;

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
    public String workStatus(CitizenEntity citizen, ServerLevel level) {
        return workStatusDetailed(citizen, level);
    }

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
                return horizDistSqr(citizen, dirtSourceTarget.getX() + 0.5, dirtSourceTarget.getZ() + 0.5) > 9.0;
            }
            return horizDistSqr(citizen, warehouse.x() + 0.5, warehouse.z() + 0.5) > 49.0;
        }
        if (townCenter == null) return false;
        if (plazaCursor < 100 && isPrimaryStructureBuilder(citizen, level)) {
            return horizDistSqr(citizen, townCenter.getX() + 0.5, townCenter.getZ() + 0.5) > 36.0;
        }
        if (repairTarget != null) {
            return horizDistSqr(citizen, repairTarget.getX() + 0.5, repairTarget.getZ() + 0.5) > 20.25;
        }
        if (routeIndex >= 0 && routeIndex < ROUTE_COUNT && !isRouteDone(routeIndex)) {
            List<RoadStep> steps = computeRouteSteps(routeIndex, data, citizen, level);
            if (routeStep >= 0 && routeStep < steps.size()) {
                BlockPos center = steps.get(routeStep).center(0);
                return horizDistSqr(citizen, center.getX() + 0.5, center.getZ() + 0.5) > 20.25;
            }
        }
        return false;
    }

    /**
     * When multiple Builders exist in the same nation, Builder #1 (lowest UUID) is the Primary Structure Builder
     * (10x10 Village Center, 10x10 Warehouse plot, 50x50 NW Reforestation Zone smoothing), while Builder #2
     * immediately builds the 15x3 Warehouse road, the "T" Mine road, and the 4 cardinal roads.
     * If only 1 Builder exists, it performs all tasks sequentially.
     */
    private boolean isPrimaryStructureBuilder(CitizenEntity citizen, ServerLevel level) {
        if (citizen.getNationId() == null) return true;
        java.util.UUID myId = citizen.getUUID();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof CitizenEntity other && other != citizen
                && citizen.getNationId().equals(other.getNationId()) && other.isBuilderEnabled()) {
                if (other.getUUID().compareTo(myId) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double horizDistSqr(CitizenEntity citizen, double targetX, double targetZ) {
        double dx = citizen.getX() - targetX;
        double dz = citizen.getZ() - targetZ;
        return dx * dx + dz * dz;
    }

    public void start(CitizenEntity citizen, BlockPos center) {
        townCenter = center.immutable();
        warehousePlotCursor = 0;
        plazaCursor = 0;
        pendingResourceId = "";
        shovelRequestSent = false;
        pickaxeRequestSent = false;
        stairRequestSent = false;
        dirtSearchAnnounced = false;
        dirtSourceTarget = null;
        completed = false;
        preparingPlot = false;
        routeIndex = ROUTE_WAREHOUSE;
        routeStep = 0;
        routeY = UNSET_Y;
        stepTargetY = UNSET_Y;
        routeDoneMask = 0;
        mineRouteOrigin = null;
        roadLedger.clear();
        roadLedgerSet.clear();
        repairQueue.clear();
        inspectCursor = 0;
        actionsSinceRepair = 0;
        repairTarget = null;
        repairAttempts = 0;
        reforestationCursor = 0;
        reforestationDone = false;
        cooldown = 0;
    }

    public void stop() {
        townCenter = null;
        pendingResourceId = "";
        completed = false;
        repairTarget = null;
    }

    public void save(ValueOutput output) {
        if (townCenter != null) output.store("BuilderCenter", BlockPos.CODEC, townCenter);
        output.putInt("BuilderWarehousePlotCursor", warehousePlotCursor);
        output.putInt("BuilderPlazaCursor", plazaCursor);
        output.putString("BuilderPendingResource", pendingResourceId);
        output.putBoolean("BuilderShovelRequestSent", shovelRequestSent);
        output.putBoolean("BuilderPickaxeRequestSent", pickaxeRequestSent);
        output.putBoolean("BuilderStairRequestSent", stairRequestSent);
        output.putBoolean("BuilderDirtSearchAnnounced", dirtSearchAnnounced);
        output.putBoolean("BuilderCompleted", completed);
        output.putBoolean("BuilderPreparingPlot", preparingPlot);
        output.putInt("BuilderRouteIndex", routeIndex);
        output.putInt("BuilderRouteStep", routeStep);
        output.putInt("BuilderRouteY", routeY);
        output.putInt("BuilderStepTargetY", stepTargetY);
        output.putInt("BuilderRouteDoneMask", routeDoneMask);
        if (mineRouteOrigin != null) output.store("BuilderMineRouteOrigin", BlockPos.CODEC, mineRouteOrigin);
        output.store("BuilderRoadLedger", BlockPos.CODEC.listOf(), List.copyOf(roadLedger));
        output.store("BuilderRepairQueue", BlockPos.CODEC.listOf(), List.copyOf(repairQueue));
        output.putInt("BuilderInspectCursor", inspectCursor);
        output.putInt("BuilderActionsSinceRepair", actionsSinceRepair);
        output.putInt("BuilderReforestationCursor", reforestationCursor);
        output.putBoolean("BuilderReforestationDone", reforestationDone);
    }

    public void load(ValueInput input) {
        townCenter = input.read("BuilderCenter", BlockPos.CODEC).orElse(null);
        warehousePlotCursor = Math.max(0, Math.min(100, input.getIntOr("BuilderWarehousePlotCursor", 0)));
        plazaCursor = Math.max(0, Math.min(100, input.getIntOr("BuilderPlazaCursor", 0)));
        pendingResourceId = input.getStringOr("BuilderPendingResource", "");
        shovelRequestSent = input.getBooleanOr("BuilderShovelRequestSent", false);
        pickaxeRequestSent = input.getBooleanOr("BuilderPickaxeRequestSent", false);
        stairRequestSent = input.getBooleanOr("BuilderStairRequestSent", false);
        dirtSearchAnnounced = input.getBooleanOr("BuilderDirtSearchAnnounced", false);
        completed = input.getBooleanOr("BuilderCompleted", false);
        preparingPlot = input.getBooleanOr("BuilderPreparingPlot", false);
        routeIndex = Math.max(0, Math.min(ROUTE_COUNT, input.getIntOr("BuilderRouteIndex", ROUTE_WAREHOUSE)));
        routeStep = Math.max(0, input.getIntOr("BuilderRouteStep", 0));
        routeY = input.getIntOr("BuilderRouteY", UNSET_Y);
        stepTargetY = input.getIntOr("BuilderStepTargetY", UNSET_Y);
        routeDoneMask = input.getIntOr("BuilderRouteDoneMask", 0);
        mineRouteOrigin = input.read("BuilderMineRouteOrigin", BlockPos.CODEC).orElse(null);
        roadLedger.clear();
        roadLedgerSet.clear();
        input.read("BuilderRoadLedger", BlockPos.CODEC.listOf()).ifPresent(saved -> {
            for (BlockPos pos : saved) {
                BlockPos imm = pos.immutable();
                if (roadLedgerSet.add(imm)) roadLedger.add(imm);
            }
        });
        repairQueue.clear();
        input.read("BuilderRepairQueue", BlockPos.CODEC.listOf()).ifPresent(saved -> {
            for (BlockPos pos : saved) repairQueue.add(pos.immutable());
        });
        inspectCursor = Math.max(0, input.getIntOr("BuilderInspectCursor", 0));
        actionsSinceRepair = Math.max(0, input.getIntOr("BuilderActionsSinceRepair", 0));
        reforestationCursor = Math.max(0, Math.min(2500, input.getIntOr("BuilderReforestationCursor", 0)));
        reforestationDone = input.getBooleanOr("BuilderReforestationDone", false);
        repairTarget = null;
        repairAttempts = 0;
    }

    public boolean isActive() {
        return townCenter != null && (!completed || plazaCursor < 100 || !repairQueue.isEmpty() || repairTarget != null);
    }

    public boolean isInitialized() { return townCenter != null; }

    public String workStatus() {
        if (townCenter == null) return "Planejando os caminhos da vila";
        if (preparingPlot) return "Construindo piso 10x10 da warehouse: " + warehousePlotCursor + "% — " + currentSubTask;
        if (plazaCursor < 100) return "Construindo Centro da Vila 10x10: " + plazaCursor + "/100 — " + currentSubTask;
        if (repairTarget != null) {
            return "Reparando caminho em [" + repairTarget.toShortString() + "] (" + (repairQueue.size() + 1) + " pendente(s)) — " + currentSubTask;
        }
        if (completed) {
            if (!reforestationDone) {
                return "Terraplanando área de reflorestamento 50x50 (NO): " + (reforestationCursor * 100 / 2500) + "% — " + currentSubTask;
            }
            return repairQueue.isEmpty()
                ? "Caminhos prontos; monitorando " + roadLedger.size() + " blocos"
                : "Indo reparar " + repairQueue.size() + " bloco(s) do caminho";
        }
        return "Construindo caminho " + routeLabel(routeIndex) + " (passo " + (routeStep + 1) + ") — " + currentSubTask;
    }

    public String workStatusDetailed(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        boolean primaryBuilder = isPrimaryStructureBuilder(citizen, level);
        String waitDesc = StorageJob.describeOpenWaitRequest(citizen, level);
        StringBuilder sb = new StringBuilder();
        sb.append("Papel: ").append(primaryBuilder ? "Builder #1 (Estruturas 10x10 + Reflorestamento + Rotas)" : "Builder #2 (Estradas 3-largura + Manutenção)");
        sb.append("\nFase: ").append(!waitDesc.isEmpty() ? waitDesc : workStatus());

        if (townCenter != null && !preparingPlot && (plazaCursor >= 100 || !primaryBuilder) && !completed && routeIndex >= 0 && routeIndex < ROUTE_COUNT) {
            List<RoadStep> steps = computeRouteSteps(routeIndex, data, citizen, level);
            int total = steps.size();
            int pct = total > 0 ? Math.min(100, (routeStep * 100) / total) : 100;
            sb.append("\nProgresso da rota [").append(routeLabel(routeIndex)).append("]: ")
                .append(routeStep).append("/").append(total).append(" passos (").append(pct).append("%)");
            if (routeStep >= 0 && routeStep < total) {
                RoadStep s = steps.get(routeStep);
                int y = stepTargetY != UNSET_Y ? stepTargetY : (routeY != UNSET_Y ? routeY : townCenter.getY());
                double dist = Math.sqrt(horizDistSqr(citizen, s.x() + 0.5, s.z() + 0.5));
                sb.append(" | Alvo: [").append(s.x()).append(", ").append(y).append(", ").append(s.z())
                    .append(String.format(java.util.Locale.ROOT, "] (%.1fm, 3 faixas niveladas em Y=%d)", dist, y));
            }
        }

        // Route checklist summary
        sb.append("\nRotas (centro da vila): ")
            .append("Warehouse(15x3)=").append(isRouteDone(ROUTE_WAREHOUSE) ? "OK" : (routeIndex == ROUTE_WAREHOUSE ? "ATIVA" : "pendente"))
            .append(" | Mina/Caverna(T)=").append(isRouteDone(ROUTE_MINE) ? "OK" : (routeIndex == ROUTE_MINE ? "ATIVA" : (data.getMineSite(citizen.getNationId()) == null ? "aguardando Miner" : "pendente")))
            .append(" | L/S/O/N=")
            .append(isRouteDone(ROUTE_CARDINAL_FIRST) ? "OK" : "pend").append("/")
            .append(isRouteDone(ROUTE_CARDINAL_FIRST + 1) ? "OK" : "pend").append("/")
            .append(isRouteDone(ROUTE_CARDINAL_FIRST + 2) ? "OK" : "pend").append("/")
            .append(isRouteDone(ROUTE_CARDINAL_FIRST + 3) ? "OK" : "pend")
            .append(" | Reflorestamento(50x50)=").append(reforestationDone ? "OK" : (reforestationCursor * 100 / 2500) + "%");

        int dirtCount = countItem(citizen, Items.DIRT);
        int planksCount = countTaggedItem(citizen, ItemTags.PLANKS);
        int logsCount = countTaggedItem(citizen, ItemTags.LOGS);
        sb.append("\nMateriais: ").append(dirtCount).append(" terra, ")
            .append(planksCount).append(" tábuas, ").append(logsCount).append(" troncos")
            .append(" | Manutenção: ").append(roadLedger.size()).append(" monitorados, ")
            .append(repairQueue.size()).append(" na fila de reparo");

        if (!waitDesc.isEmpty()) {
            sb.append("\n").append(waitDesc);
        }
        return sb.toString();
    }

    public String debugStatus() {
        return "active=" + isActive() + ", plot=" + warehousePlotCursor + "/100, plaza=" + plazaCursor + "/100"
            + ", route=" + routeLabel(routeIndex) + "#" + routeStep + ", y=" + stepTargetY
            + ", reforest=" + reforestationCursor + "/2500"
            + ", task=\"" + currentSubTask + "\""
            + ", ledger=" + roadLedger.size() + ", repairs=" + repairQueue.size()
            + ", pending=" + pendingResourceId;
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
        boolean primaryStructureBuilder = isPrimaryStructureBuilder(citizen, level);

        if (primaryStructureBuilder && warehouse != null && data.warehousePlotRequested(citizen.getNationId())
            && !data.warehousePlotPrepared(citizen.getNationId())) {
            if (!preparingPlot && warehousePlotCursor >= 121) warehousePlotCursor = 0;
            preparingPlot = true;
            if (cooldown-- > 0) return;
            cooldown = TICK_INTERVAL;
            collectNearbyDrops(citizen, level);
            prepareWarehousePlot(citizen, level, warehouse);
            return;
        }
        if (!primaryStructureBuilder || warehouse == null || data.warehousePlotPrepared(citizen.getNationId())) {
            preparingPlot = false;
        }
        if (cooldown-- > 0) return;
        cooldown = TICK_INTERVAL;
        collectNearbyDrops(citizen, level);

        // Architectural gate: while waiting for a requested resource from the Warehouse,
        // do NOT run any construction, maintenance, or reforestation logic that could override navigation.
        if (handlePendingWarehouseResource(citizen, level, data)) {
            return;
        }

        // Background road inspection: checks a few ledger cells per tick (O(1) cost).
        inspectRoadLedger(citizen, level, warehouse);

        if (primaryStructureBuilder && plazaCursor < 100) {
            buildVillageCenter(citizen, level);
            return;
        } else if (!primaryStructureBuilder && plazaCursor < 100) {
            plazaCursor = 100; // Secondary builder skips Plaza 10x10 and starts roads immediately!
        }

        // Primary structure builder smooths the 50x50 NW Reforestation Zone once Plaza & Warehouse are done
        if (primaryStructureBuilder && !reforestationDone
            && (warehouse == null || data.warehousePlotPrepared(citizen.getNationId()))) {
            if (prepareReforestationZoneStep(citizen, level, data)) {
                return;
            }
        }

        // If a warehouse or mine appeared after we skipped its route, re-open that route.
        refreshDynamicRoutes(data, citizen, level);

        // Urgent/nearby maintenance or maintenance window between construction batches.
        if (shouldRunMaintenanceNow(citizen)) {
            if (tickMaintenance(citizen, level, warehouse)) return;
        }

        int currentRoute = selectNextActiveRoute(data, citizen, level);
        if (currentRoute < 0) {
            if (!completed) {
                completed = true;
                citizen.announce("Concluí os caminhos nivelados da vila (centro, warehouse, mina e fronteiras).");
            }
            if (!reforestationDone && prepareReforestationZoneStep(citizen, level, data)) {
                return;
            }
            if (!repairQueue.isEmpty() || repairTarget != null) {
                tickMaintenance(citizen, level, warehouse);
            }
            return;
        }
        completed = false;
        if (currentRoute != routeIndex) {
            routeIndex = currentRoute;
            routeStep = 0;
            routeY = UNSET_Y;
            stepTargetY = UNSET_Y;
        }

        buildRoadRouteStep(citizen, level, data, warehouse);
    }

    /**
     * Builds the active route one 3-block-wide step at a time.
     * All 3 lanes of a step are forced to the exact same Y (stepTargetY), which can differ by at most
     * +-1 from the previous step's Y (routeY). This allows the road to climb hills and descend valleys
     * while keeping the 3-block width completely flat and aligned.
     */
    private void buildRoadRouteStep(CitizenEntity citizen, ServerLevel level, CivilizationData data,
                                    CivilizationData.Warehouse warehouse) {
        List<RoadStep> steps = computeRouteSteps(routeIndex, data, citizen, level);
        if (steps.isEmpty()) {
            markRouteDone(routeIndex);
            return;
        }

        int skippedThisTick = 0;
        while (routeStep < steps.size() && skippedThisTick < MAX_STEPS_CHECKED_PER_TICK) {
            RoadStep step = steps.get(routeStep);
            BlockPos stepCenter = step.center(townCenter.getY());
            if (!citizen.isInsideNation(stepCenter)) {
                advanceRouteStep(stepTargetY != UNSET_Y ? stepTargetY : routeY);
                skippedThisTick++;
                continue;
            }
            if (!level.hasChunkAt(stepCenter)) return;

            boolean allReserved = true;
            for (int i = 0; i < LANE_OFFSETS.length; i++) {
                BlockPos col = step.lanePos(LANE_OFFSETS[i], townCenter.getY());
                if (citizen.isInsideNation(col) && !isInsidePlazaOrWarehouse(col, warehouse)) {
                    allReserved = false;
                    break;
                }
            }
            if (allReserved) {
                int anchorY = sampleStepTerrainY(level, step, warehouse);
                advanceRouteStep(anchorY);
                skippedThisTick++;
                continue;
            }

            if (stepTargetY == UNSET_Y) {
                stepTargetY = computeUnifiedStepY(level, step, warehouse);
            }
            final int targetY = stepTargetY;

            // Walk close enough to the step center so all 3 lanes are within reach.
            // IMPORTANT: Always use 2D horizontal distance (horizDistSqr) and actual terrain height for navigation!
            BlockPos navCenter = step.center(targetY);
            double hDistSqr = horizDistSqr(citizen, navCenter.getX() + 0.5, navCenter.getZ() + 0.5);
            if (hDistSqr > 20.25) {
                int walkY = getTrueRoadSurfaceY(level, navCenter.getX(), navCenter.getZ());
                currentSubTask = String.format(java.util.Locale.ROOT, "Caminhando até o passo %d [%.1fm]", routeStep + 1, Math.sqrt(hDistSqr));
                CitizenEntity.moveTowards(citizen, level, navCenter.getX() + 0.5, walkY, navCenter.getZ() + 0.5, 1.0);
                return;
            }
            citizen.getNavigation().stop();

            // 1. Clear any trees or giant mushrooms overlapping this 3-wide step before shaping terrain.
            for (int i = 0; i < LANE_OFFSETS.length; i++) {
                BlockPos col = step.lanePos(LANE_OFFSETS[i], targetY);
                if (!citizen.isInsideNation(col) || !level.hasChunkAt(col) || isInsidePlazaOrWarehouse(col, warehouse)) continue;
                int rawY = getTrueRoadSurfaceY(level, col.getX(), col.getZ());
                int minY = Math.min(targetY - 1, rawY - 1);
                int maxY = Math.max(targetY, rawY) + HEADROOM + 1;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int checkY = minY; checkY <= maxY; checkY++) {
                            BlockPos checkPos = new BlockPos(col.getX() + dx, checkY, col.getZ() + dz);
                            if (!citizen.isInsideNation(checkPos) || !level.hasChunkAt(checkPos)) continue;
                            BlockState checkState = level.getBlockState(checkPos);
                            if (checkState.is(BlockTags.LOGS) || isMushroomBlock(checkState)) {
                                currentSubTask = "Cortando árvore no traçado [" + checkPos.toShortString() + "]";
                                clearConnectedTreeInPlot(citizen, level, checkPos);
                                collectNearbyDrops(citizen, level);
                                actionsSinceRepair++;
                                return;
                            }
                        }
                    }
                }
            }

            // 2. Millénaire-style 3-phase road step construction across all 3 lanes:
            //    Phase 1: Cut any elevated blocks above targetY across all 3 lanes (harvesting dirt into inventory).
            //    Phase 2: Fill foundations and level all 3 lanes to targetY - 1.
            //    Phase 3: Pave all 3 lanes at targetY - 1 (DIRT_PATH or wood bridge over water).
            for (int laneIdx = 0; laneIdx < LANE_OFFSETS.length; laneIdx++) {
                BlockPos laneSurface = step.lanePos(LANE_OFFSETS[laneIdx], targetY);
                if (!citizen.isInsideNation(laneSurface) || isInsidePlazaOrWarehouse(laneSurface, warehouse)) continue;
                if (!level.hasChunkAt(laneSurface)) return;

                CellOutcome outcome = shapeAndPaveRoadCell(citizen, level, laneSurface, laneIdx == 1);
                if (outcome == CellOutcome.WORK_DONE) {
                    actionsSinceRepair++;
                    return;
                }
                if (outcome == CellOutcome.WAITING_RESOURCE) {
                    return;
                }
                // CellOutcome.READY -> register in maintenance ledger and check next lane in the same tick.
                recordRoadCell(laneSurface.below());
            }

            // All 3 lanes of this step are at the same height and paved!
            currentSubTask = "Passo " + (routeStep + 1) + " nivelado (3 faixas em Y=" + targetY + ")";
            decorateRoadStepFeatures(citizen, level, routeIndex, routeStep, step, targetY, steps.size());
            advanceRouteStep(targetY);
            skippedThisTick++;
        }

        if (routeStep >= steps.size()) {
            finishCurrentRoute(citizen);
        }
    }

    /**
     * Shapes one road column so its walking air is at {@code surface.getY()} and its floor block
     * at {@code surface.below()} is paved (DIRT_PATH on land, PLANKS over water), then registers it.
     */
    private CellOutcome shapeAndPaveRoadCell(CitizenEntity citizen, ServerLevel level, BlockPos surface, boolean centerLane) {
        int x = surface.getX();
        int z = surface.getZ();
        int targetY = surface.getY();
        BlockPos ground = surface.below();

        // Sweep light vegetation in the headroom tunnel right away.
        int currentTopY = getTrueRoadSurfaceY(level, x, z);
        int sweepTop = Math.max(targetY + HEADROOM, currentTopY + 2);
        for (int clearY = targetY; clearY <= sweepTop; clearY++) {
            BlockPos clearPos = new BlockPos(x, clearY, z);
            BlockState clearState = level.getBlockState(clearPos);
            if (!clearState.isAir() && isClearableVegetation(clearState) && level.getFluidState(clearPos).isEmpty()) {
                level.destroyBlock(clearPos, true, citizen);
            }
        }

        // A) Cut down any solid obstacle in the headroom [targetY .. sweepTop] from top to bottom.
        for (int cutY = sweepTop; cutY >= targetY; cutY--) {
            BlockPos cutPos = new BlockPos(x, cutY, z);
            BlockState cutState = level.getBlockState(cutPos);
            if (cutState.isAir() || !level.getFluidState(cutPos).isEmpty()) continue;
            if (isClearableVegetation(cutState)) {
                level.destroyBlock(cutPos, true, citizen);
                continue;
            }
            if (cutState.hasBlockEntity() || cutState.getDestroySpeed(level, cutPos) < 0.0f) {
                break; // Unbreakable/container obstacle: leave it alone.
            }
            boolean stone = isExposedStone(cutState);
            currentSubTask = "Escavando elevação em [" + cutPos.toShortString() + "] para nivelar em Y=" + targetY;
            if (stone) {
                if (!equipPickaxeForRoad(citizen, level)) {
                    // Self-sufficient fallback: don't abandon the road if Warehouse has no pickaxe yet
                    citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
                }
            } else {
                equipShovelForRoad(citizen, level);
            }

            citizen.getLookControl().setLookAt(cutPos.getX() + 0.5, cutPos.getY() + 0.5, cutPos.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            boolean isDirtLike = cutState.is(BlockTags.DIRT) || cutState.is(Blocks.GRASS_BLOCK);
            if (level.destroyBlock(cutPos, !isDirtLike, citizen)) {
                if (isDirtLike) citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
                if (citizen.getMainHandItem().isDamageableItem()) {
                    citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                }
                collectNearbyDrops(citizen, level);
            }
            return CellOutcome.WORK_DONE;
        }

        // B) Water bridge when the column is over water.
        BlockState groundState = level.getBlockState(ground);
        if (isWater(level, ground) || isWater(level, surface)) {
            if (groundState.is(BlockTags.PLANKS) && isClearHeadroom(level, surface)) {
                return CellOutcome.READY;
            }
            currentSubTask = "Construindo ponte sobre água em [" + ground.toShortString() + "]";
            return buildBridgeAt(citizen, level, ground, centerLane);
        }

        // C) Fill any air/replaceable gap below the road floor so the 3 lanes sit on a solid embankment.
        int fillStart = Math.max(level.getMinY() + 5, ground.getY() - MAX_FILL_DEPTH);
        for (int fillY = fillStart; fillY < ground.getY(); fillY++) {
            BlockPos underPos = new BlockPos(x, fillY, z);
            BlockState underState = level.getBlockState(underPos);
            if (isWater(level, underPos)) continue;
            if (underState.isAir() || underState.canBeReplaced() || isClearableVegetation(underState)) {
                currentSubTask = "Aterrando base do caminho em [" + underPos.toShortString() + "]";
                if (!underState.isAir() && isClearableVegetation(underState)) {
                    level.destroyBlock(underPos, true, citizen);
                }
                if (citizen.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(underPos))) {
                    citizen.teleportTo(citizen.getX(), citizen.getY() + 1.0, citizen.getZ());
                }
                if (countItem(citizen, Items.DIRT) > 0) {
                    placeBlockFromInventory(citizen, level, underPos, Blocks.DIRT.defaultBlockState(), Items.DIRT);
                } else {
                    level.setBlockAndUpdate(underPos, Blocks.DIRT.defaultBlockState());
                }
                return CellOutcome.WORK_DONE;
            }
        }

        // D) Ensure the floor block at ground (targetY - 1) is a finished road block.
        groundState = level.getBlockState(ground);
        if (isValidRoadFloor(groundState)) {
            return CellOutcome.READY;
        }

        if (groundState.hasBlockEntity()) {
            return CellOutcome.READY;
        }

        // If ground is air/replaceable (side lane was lower than center lane), fill it with dirt then path it.
        if (groundState.isAir() || groundState.canBeReplaced() || isClearableVegetation(groundState)) {
            currentSubTask = "Nivelando e pavimentando faixa em [" + ground.toShortString() + "]";
            if (!groundState.isAir() && isClearableVegetation(groundState)) {
                level.destroyBlock(ground, true, citizen);
            }
            if (!equipShovelForRoad(citizen, level)) {
                citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SHOVEL));
            }
            if (citizen.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(ground))) {
                citizen.teleportTo(citizen.getX(), citizen.getY() + 1.0, citizen.getZ());
            }
            citizen.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 0.5, ground.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (countItem(citizen, Items.DIRT) > 0) {
                placeBlockFromInventory(citizen, level, ground, Blocks.DIRT.defaultBlockState(), Items.DIRT);
            } else {
                level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
            }
            level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState());
            if (citizen.getMainHandItem().isDamageableItem()) {
                citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            }
            return CellOutcome.WORK_DONE;
        }

        if (isExposedStone(groundState)) {
            currentSubTask = "Trocando pedra por caminho em [" + ground.toShortString() + "]";
            if (!equipPickaxeForRoad(citizen, level)) {
                citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
            }
            citizen.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 0.5, ground.getZ() + 0.5);
            citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level.destroyBlock(ground, true, citizen)) {
                level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState());
                if (citizen.getMainHandItem().isDamageableItem()) {
                    citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
                }
            }
            return CellOutcome.WORK_DONE;
        }

        if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
            level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
        }
        currentSubTask = "Compactando terra (Dirt Path) em [" + ground.toShortString() + "]";
        if (!equipShovelForRoad(citizen, level)) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SHOVEL));
        }
        citizen.getLookControl().setLookAt(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (level.setBlockAndUpdate(ground, Blocks.DIRT_PATH.defaultBlockState())) {
            if (citizen.getMainHandItem().isDamageableItem()) {
                citizen.getMainHandItem().hurtAndBreak(1, citizen, EquipmentSlot.MAINHAND);
            }
        }
        return CellOutcome.WORK_DONE;
    }

    /**
     * Equips a shovel from inventory if available; if none is in inventory, requests an upgrade from
     * the Storager in the background WITHOUT forcing the Builder to walk back to the Warehouse mid-road.
     */
    private boolean equipShovelForRoad(CitizenEntity citizen, ServerLevel level) {
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
        return false;
    }

    private boolean equipPickaxeForRoad(CitizenEntity citizen, ServerLevel level) {
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
        return false;
    }

    private CellOutcome buildBridgeAt(CitizenEntity citizen, ServerLevel level, BlockPos floorPos, boolean supportBelow) {
        convertOwnLogsToPlanks(citizen);
        Item plank = findInventoryItem(citizen, ItemTags.PLANKS);
        if (plank == null) {
            if (!ensureResource(citizen, level, Items.OAK_PLANKS, 1, 16)) return CellOutcome.WAITING_RESOURCE;
            plank = findInventoryItem(citizen, ItemTags.PLANKS);
            if (plank == null) return CellOutcome.WAITING_RESOURCE;
        }
        pendingResourceId = "";
        Block block = Block.byItem(plank);
        if (block == Blocks.AIR) return CellOutcome.WAITING_RESOURCE;
        citizen.getLookControl().setLookAt(floorPos.getX() + 0.5, floorPos.getY() + 0.5, floorPos.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (!level.setBlockAndUpdate(floorPos, block.defaultBlockState())) return CellOutcome.READY;
        consumeItem(citizen, plank);
        if (supportBelow) {
            supportBridgeColumn(citizen, level, floorPos, plank);
        }
        return CellOutcome.WORK_DONE;
    }

    private void supportBridgeColumn(CitizenEntity citizen, ServerLevel level, BlockPos deck, Item plank) {
        BlockPos support = deck.below();
        int depth = 0;
        while (depth < 6 && level.hasChunkAt(support) && level.getFluidState(support).is(FluidTags.WATER)) {
            if (!consumeItem(citizen, plank)) return;
            level.setBlockAndUpdate(support, Block.byItem(plank).defaultBlockState());
            support = support.below();
            depth++;
        }
    }

    /**
     * Computes one single surface Y for all 3 lanes of {@code step}.
     * Uses the median of the 3 lanes' terrain heights, then clamps to [prevY - 1, prevY + 1]
     * so the road rises or falls smoothly by at most 1 block per step while staying flat across its width.
     */
    private int computeUnifiedStepY(ServerLevel level, RoadStep step, CivilizationData.Warehouse warehouse) {
        int rawY = sampleStepTerrainY(level, step, warehouse);
        int prevY = routeY == UNSET_Y ? townCenter.getY() : routeY;
        if (rawY > prevY + 1) return prevY + 1;
        if (rawY < prevY - 1) return prevY - 1;
        return rawY;
    }

    private int sampleStepTerrainY(ServerLevel level, RoadStep step, CivilizationData.Warehouse warehouse) {
        // Only lock onto the CENTER lane (offset 0) if it is already paved, so perpendicular side-lane crossings
        // near the town center don't pull the new road to a different Y.
        int refY = routeY == UNSET_Y ? townCenter.getY() : routeY;
        BlockPos centerCol = step.lanePos(0, refY);
        if (level.hasChunkAt(centerCol)) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos candidateFloor = new BlockPos(centerCol.getX(), refY - 1 + dy, centerCol.getZ());
                if (isValidRoadFloor(level.getBlockState(candidateFloor))) {
                    return candidateFloor.getY() + 1;
                }
            }
        }

        int[] samples = new int[LANE_OFFSETS.length];
        int count = 0;
        for (int offset : LANE_OFFSETS) {
            BlockPos col = step.lanePos(offset, refY);
            if (!level.hasChunkAt(col)) continue;
            if (townCenter != null && col.getX() >= townCenter.getX() - 5 && col.getX() < townCenter.getX() + 5
                && col.getZ() >= townCenter.getZ() - 5 && col.getZ() < townCenter.getZ() + 5) {
                samples[count++] = townCenter.getY();
            } else if (warehouse != null && col.getX() >= warehouse.x() - 5 && col.getX() < warehouse.x() + 5
                && col.getZ() >= warehouse.z() - 5 && col.getZ() < warehouse.z() + 5) {
                samples[count++] = warehouse.y();
            } else {
                samples[count++] = getTrueRoadSurfaceY(level, col.getX(), col.getZ());
            }
        }
        if (count == 0) return refY;
        if (count == 1) return samples[0];
        if (count == 2) return (samples[0] + samples[1]) / 2;
        java.util.Arrays.sort(samples);
        return samples[1]; // median of the 3 lanes
    }

    private void advanceRouteStep(int completedStepY) {
        if (completedStepY != UNSET_Y) {
            routeY = completedStepY;
        }
        routeStep++;
        stepTargetY = UNSET_Y;
    }

    private void decorateRoadStepFeatures(CitizenEntity citizen, ServerLevel level, int routeIdx, int stepIdx,
                                          RoadStep step, int surfaceY, int totalSteps) {
        if (townCenter == null || surfaceY == UNSET_Y) return;
        BlockPos centerPos = step.center(surfaceY);
        int dx = centerPos.getX() - townCenter.getX();
        int dz = centerPos.getZ() - townCenter.getZ();
        int distFromCenter = Math.max(Math.abs(dx), Math.abs(dz));

        // 1. Placas de sinalização nos cruzamentos principais
        if (routeIdx == ROUTE_WAREHOUSE && stepIdx == 0) {
            // Saída Leste do Centro da Vila
            BlockPos signPos = step.lanePos(-2, surfaceY);
            placeDirectionSign(citizen, level, signPos, Direction.WEST,
                "---> LESTE", "Warehouse", "Mina (Sul)", "");
        } else if (routeIdx == ROUTE_MINE && stepIdx == 1) {
            // Bifurcação em "T" para a Mina/Caverna
            BlockPos signPos = step.lanePos(-2, surfaceY);
            placeDirectionSign(citizen, level, signPos, Direction.NORTH,
                "v SUL", "Caverna / Mina", "-> Warehouse", "");
        } else if (routeIdx >= ROUTE_CARDINAL_FIRST && stepIdx == 0) {
            BlockPos signPos = step.lanePos(-2, surfaceY);
            if (routeIdx == ROUTE_CARDINAL_FIRST + 1) {
                placeDirectionSign(citizen, level, signPos, Direction.NORTH,
                    "v SUL", "Currais", "Fronteira", "");
            } else if (routeIdx == ROUTE_CARDINAL_FIRST + 2) {
                placeDirectionSign(citizen, level, signPos, Direction.EAST,
                    "<- OESTE", "Reflorestamento", "50x50 (NO)", "");
            } else if (routeIdx == ROUTE_CARDINAL_FIRST + 3) {
                placeDirectionSign(citizen, level, signPos, Direction.SOUTH,
                    "^ NORTE", "Fazenda / Trigo", "Fronteira", "");
            }
        }

        // 2. Arcos / Portões estilo Millénaire na entrada e saída das construções, no raio de 30 blocos e na Mina
        boolean isArchStep = (routeIdx == ROUTE_WAREHOUSE && (stepIdx == 1 || stepIdx == totalSteps - 1))
            || (routeIdx == ROUTE_MINE && (stepIdx == 1 || (totalSteps > 2 && stepIdx == totalSteps - 2)))
            || (routeIdx >= ROUTE_CARDINAL_FIRST && distFromCenter == 30);
        if (isArchStep) {
            buildMillenaireArch(citizen, level, step, surfaceY);
            return;
        }

        // 3. Postes simples com tocha em PARES (ambos os lados) a cada 16 blocos, longe dos arcos
        boolean nearArch = Math.abs(distFromCenter - 30) < 8 || stepIdx < 6 || (totalSteps > 8 && stepIdx > totalSteps - 6);
        if (!nearArch && stepIdx % 16 == 0) {
            buildStreetLamp(citizen, level, step.lanePos(-2, surfaceY));
            buildStreetLamp(citizen, level, step.lanePos(2, surfaceY));
        }
    }

    private void buildStreetLamp(CitizenEntity citizen, ServerLevel level, BlockPos basePos) {
        if (!level.hasChunkAt(basePos)) return;
        fillFoundationDown(level, basePos.below(), Blocks.COBBLESTONE);
        citizen.getLookControl().setLookAt(basePos.getX() + 0.5, basePos.getY() + 2.0, basePos.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        for (int i = 0; i < 3; i++) {
            BlockPos fencePos = basePos.above(i);
            if ((level.getBlockState(fencePos).isAir() || level.getBlockState(fencePos).canBeReplaced())
                && consumeItem(citizen, Items.OAK_FENCE)) {
                level.setBlockAndUpdate(fencePos, Blocks.OAK_FENCE.defaultBlockState());
            }
        }
        BlockPos torchPos = basePos.above(3);
        if (level.getBlockState(torchPos).isAir() || level.getBlockState(torchPos).canBeReplaced()) {
            if (consumeItem(citizen, Items.TORCH)) {
                level.setBlockAndUpdate(torchPos, Blocks.TORCH.defaultBlockState());
            } else {
                requestResourceOnce(citizen, level, Items.TORCH, 8);
            }
        }
    }

    private void buildMillenaireArch(CitizenEntity citizen, ServerLevel level, RoadStep step, int surfaceY) {
        BlockPos leftBase = step.lanePos(-2, surfaceY);
        BlockPos rightBase = step.lanePos(2, surfaceY);
        if (!level.hasChunkAt(leftBase) || !level.hasChunkAt(rightBase)) return;

        citizen.getLookControl().setLookAt(step.x() + 0.5, surfaceY + 3.0, step.z() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        // Pilares laterais (altura 4) fora da pista de 3 blocos, com fundação até o chão firme.
        // Usa STONE_BRICKS (nunca OAK_LOG) para que o Lenhador jamais confunda a pilastra com uma árvore!
        for (BlockPos pillarBase : new BlockPos[]{leftBase, rightBase}) {
            fillFoundationDown(level, pillarBase.below(), Blocks.COBBLESTONE);
            for (int y = 0; y < 4; y++) {
                if (consumeItem(citizen, Items.STONE_BRICKS)) {
                    level.setBlockAndUpdate(pillarBase.above(y), Blocks.STONE_BRICKS.defaultBlockState());
                } else {
                    requestResourceOnce(citizen, level, Items.STONE_BRICKS, 16);
                }
            }
        }

        // Travessa superior em Y+3 e laje de acabamento em Y+4 (vão livre 3x3)
        for (int lane = -2; lane <= 2; lane++) {
            BlockPos beamPos = step.lanePos(lane, surfaceY + 3);
            Item plank = findInventoryItem(citizen, ItemTags.PLANKS);
            if (plank != null && consumeItem(citizen, plank)) {
                level.setBlockAndUpdate(beamPos, Block.byItem(plank).defaultBlockState());
            } else {
                requestResourceOnce(citizen, level, Items.OAK_PLANKS, 16);
            }
            if (lane >= -1 && lane <= 1 && consumeItem(citizen, Items.OAK_SLAB)) {
                level.setBlockAndUpdate(beamPos.above(), Blocks.OAK_SLAB.defaultBlockState());
            }
        }

        // Tochas de iluminação no topo dos pilares (apenas se houver tocha real no inventário)
        for (BlockPos torchPos : new BlockPos[]{leftBase.above(4), rightBase.above(4)}) {
            if (consumeItem(citizen, Items.TORCH)) {
                level.setBlockAndUpdate(torchPos, Blocks.TORCH.defaultBlockState());
            } else {
                requestResourceOnce(citizen, level, Items.TORCH, 8);
            }
        }
    }

    private void requestResourceOnce(CitizenEntity citizen, ServerLevel level, Item item, int amount) {
        if (citizen.getNationId() == null) return;
        CivilizationData data = CivilizationData.get(level);
        String reqId = StorageJob.resourceRequestId(item);
        if (!hasOpenResourceRequest(data, citizen, reqId)) {
            requestResource(citizen, level, item, amount);
        }
    }

    private static void fillFoundationDown(ServerLevel level, BlockPos start, Block block) {
        BlockPos cursor = start;
        for (int depth = 0; depth < 10 && cursor.getY() > level.getMinY() + 1; depth++) {
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && state.getFluidState().isEmpty() && state.isFaceSturdy(level, cursor, Direction.UP)) {
                break;
            }
            level.setBlockAndUpdate(cursor, block.defaultBlockState());
            cursor = cursor.below();
        }
    }

    private void placeDirectionSign(CitizenEntity citizen, ServerLevel level, BlockPos signPos, Direction facing,
                                    String line1, String line2, String line3, String line4) {
        if (!level.hasChunkAt(signPos)) return;
        BlockPos support = signPos.below();
        if (level.getBlockState(support).isAir() || !level.getFluidState(support).isEmpty()) {
            level.setBlockAndUpdate(support, Blocks.DIRT.defaultBlockState());
        }
        if (!level.getBlockState(signPos).isAir() && !level.getBlockState(signPos).canBeReplaced()) return;

        citizen.getLookControl().setLookAt(signPos.getX() + 0.5, signPos.getY() + 0.5, signPos.getZ() + 0.5);
        citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        int rotation = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            case EAST -> 12;
            default -> 0;
        };
        BlockState signState = Blocks.OAK_SIGN.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, rotation);
        level.setBlockAndUpdate(signPos, signState);
        if (level.getBlockEntity(signPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signBe) {
            net.minecraft.world.level.block.entity.SignText text = new net.minecraft.world.level.block.entity.SignText()
                .setMessage(0, net.minecraft.network.chat.Component.literal(line1))
                .setMessage(1, net.minecraft.network.chat.Component.literal(line2))
                .setMessage(2, net.minecraft.network.chat.Component.literal(line3))
                .setMessage(3, net.minecraft.network.chat.Component.literal(line4));
            signBe.setText(text, true);
            signBe.setChanged();
        }
    }

    private void finishCurrentRoute(CitizenEntity citizen) {
        markRouteDone(routeIndex);
        String label = routeLabel(routeIndex);
        routeStep = 0;
        routeY = UNSET_Y;
        stepTargetY = UNSET_Y;
        citizen.announce("Concluí o caminho nivelado (3 blocos): " + label + ".");
    }

    private boolean isRouteDone(int index) {
        return (routeDoneMask & (1 << index)) != 0;
    }

    private void markRouteDone(int index) {
        routeDoneMask |= (1 << index);
    }

    private void clearRouteDone(int index) {
        routeDoneMask &= ~(1 << index);
    }

    private void refreshDynamicRoutes(CivilizationData data, CitizenEntity citizen, ServerLevel level) {
        CivilizationData.MineSite mine = data.getMineSite(citizen.getNationId());
        if (mine != null && mine.dimension().equals(level.dimension().location())) {
            BlockPos origin = mine.origin();
            if (mineRouteOrigin == null || !mineRouteOrigin.equals(origin)) {
                mineRouteOrigin = origin.immutable();
                clearRouteDone(ROUTE_MINE);
                if (completed) completed = false;
            }
        }
    }

    private int selectNextActiveRoute(CivilizationData data, CitizenEntity citizen, ServerLevel level) {
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse != null && warehouse.dimension().equals(level.dimension().location()) && !isRouteDone(ROUTE_WAREHOUSE)) {
            return ROUTE_WAREHOUSE;
        }
        CivilizationData.MineSite mine = data.getMineSite(citizen.getNationId());
        if (mine != null && mine.dimension().equals(level.dimension().location()) && !isRouteDone(ROUTE_MINE)) {
            if (mineRouteOrigin == null) mineRouteOrigin = mine.origin().immutable();
            return ROUTE_MINE;
        }
        for (int dir = 0; dir < ROAD_DIRECTIONS.length; dir++) {
            int r = ROUTE_CARDINAL_FIRST + dir;
            if (!isRouteDone(r)) return r;
        }
        return -1;
    }

    private String routeLabel(int index) {
        return switch (index) {
            case ROUTE_WAREHOUSE -> "centro -> warehouse";
            case ROUTE_MINE -> "centro -> entrada da mina";
            case ROUTE_CARDINAL_FIRST -> "leste";
            case ROUTE_CARDINAL_FIRST + 1 -> "sul";
            case ROUTE_CARDINAL_FIRST + 2 -> "oeste";
            case ROUTE_CARDINAL_FIRST + 3 -> "norte";
            default -> "concluído";
        };
    }

    private List<RoadStep> computeRouteSteps(int index, CivilizationData data, CitizenEntity citizen, ServerLevel level) {
        if (townCenter == null) return List.of();
        if (index == ROUTE_WAREHOUSE) {
            CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
            if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return List.of();
            return computeLShapedSteps(townCenter, warehouse.center());
        }
        if (index == ROUTE_MINE) {
            CivilizationData.MineSite mine = data.getMineSite(citizen.getNationId());
            if (mine == null || !mine.dimension().equals(level.dimension().location())) return List.of();
            // Connect the midpoint of the 15x3 Center->Warehouse avenue (x = townCenter + 12, z = townCenter)
            // directly to the surface approach in front of the Mine/Cave entrance, forming a "T" junction!
            BlockPos entranceApproach = mine.origin().relative(mine.direction().getOpposite(), 1);
            BlockPos tJunctionOrigin = new BlockPos(townCenter.getX() + 12, townCenter.getY(), townCenter.getZ());
            if (Math.abs(entranceApproach.getX() - tJunctionOrigin.getX()) <= 2) {
                return computeLShapedSteps(tJunctionOrigin, entranceApproach);
            }
            return computeLShapedSteps(townCenter, entranceApproach);
        }
        int dirIdx = index - ROUTE_CARDINAL_FIRST;
        if (dirIdx < 0 || dirIdx >= ROAD_DIRECTIONS.length) return List.of();
        Direction dir = ROAD_DIRECTIONS[dirIdx];
        List<RoadStep> steps = new ArrayList<>(CivilizationData.NATION_RADIUS_BLOCKS);
        for (int dist = 1; dist <= CivilizationData.NATION_RADIUS_BLOCKS; dist++) {
            BlockPos c = townCenter.relative(dir, dist);
            steps.add(new RoadStep(c.getX(), c.getZ(), dir));
        }
        return steps;
    }

    /**
     * Builds an L-shaped sequence of 3-wide RoadSteps starting at {@code from} (townCenter) and ending at {@code to}.
     * Segment 1 marches along the dominant axis; Segment 2 turns into the remaining axis, including a 3x3 corner pad.
     */
    private List<RoadStep> computeLShapedSteps(BlockPos from, BlockPos to) {
        List<RoadStep> steps = new ArrayList<>();
        int cx = from.getX();
        int cz = from.getZ();
        int tx = to.getX();
        int tz = to.getZ();
        int dx = tx - cx;
        int dz = tz - cz;

        if (dx != 0) {
            Direction dirX = dx > 0 ? Direction.EAST : Direction.WEST;
            int stepX = Integer.compare(dx, 0);
            for (int x = cx + stepX; x != tx + stepX; x += stepX) {
                steps.add(new RoadStep(x, cz, dirX));
            }
        }
        if (dz != 0) {
            Direction dirZ = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            int stepZ = Integer.compare(dz, 0);
            int startZ = (dx != 0) ? cz - stepZ : cz + stepZ;
            for (int z = startZ; z != tz + stepZ; z += stepZ) {
                steps.add(new RoadStep(tx, z, dirZ));
            }
        }
        return steps;
    }

    // =========================================================================
    // Efficient Road Maintenance (Ledger + Batched Nearest-First Repair Queue)
    // =========================================================================

    private void recordRoadCell(BlockPos floorPos) {
        BlockPos imm = floorPos.immutable();
        if (roadLedgerSet.add(imm)) {
            roadLedger.add(imm);
        }
        repairQueue.remove(imm);
    }

    private static boolean isValidRoadFloor(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS);
    }

    private boolean isClearHeadroom(ServerLevel level, BlockPos surface) {
        for (int dy = 0; dy < 2; dy++) {
            BlockPos above = surface.above(dy);
            BlockState state = level.getBlockState(above);
            if (!state.isAir() && !state.canBeReplaced() && !isClearableVegetation(state)) {
                return false;
            }
        }
        return true;
    }

    private boolean isRoadCellIntact(ServerLevel level, BlockPos floorPos, CivilizationData.Warehouse warehouse) {
        if (isInsidePlazaOrWarehouse(floorPos, warehouse)) return true;
        BlockState floorState = level.getBlockState(floorPos);
        if (!isValidRoadFloor(floorState)) return false;
        return isClearHeadroom(level, floorPos.above());
    }

    /**
     * Scans at most {@code INSPECT_PER_TICK} cells from the road ledger per tick.
     * Any broken floor block (turned to air/dirt/hole) or blocked headroom is queued for repair.
     */
    private void inspectRoadLedger(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse) {
        if (roadLedger.isEmpty()) return;
        int checks = Math.min(INSPECT_PER_TICK, roadLedger.size());
        for (int i = 0; i < checks; i++) {
            if (inspectCursor >= roadLedger.size()) inspectCursor = 0;
            BlockPos floorPos = roadLedger.get(inspectCursor++);
            if (!citizen.isInsideNation(floorPos) || !level.hasChunkAt(floorPos)) continue;
            if (!isRoadCellIntact(level, floorPos, warehouse)) {
                repairQueue.add(floorPos);
            }
        }
    }

    /**
     * Decides whether the Builder should pause construction to repair road damage right now.
     * - Keeps focus on construction by default (never interrupts an active road for proximity alone).
     * - Repairs immediately only if: (a) a repair batch is already in progress, (b) all roads are finished,
     *   (c) damage is large (>= REPAIR_URGENT_QUEUE), or (d) a construction batch threshold
     *   (REPAIR_EVERY_ACTIONS) was reached.
     */
    private boolean shouldRunMaintenanceNow(CitizenEntity citizen) {
        if (repairTarget != null) return true;
        if (repairQueue.isEmpty()) return false;
        if (completed) return true;
        if (repairQueue.size() >= REPAIR_URGENT_QUEUE) return true;
        return actionsSinceRepair >= REPAIR_EVERY_ACTIONS;
    }

    private boolean tickMaintenance(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse) {
        if (repairTarget == null) {
            repairTarget = pollNearestRepair(citizen, citizen.blockPosition(), Double.MAX_VALUE);
            repairAttempts = 0;
            if (repairTarget == null) {
                actionsSinceRepair = 0;
                return false;
            }
        }

        if (!citizen.isInsideNation(repairTarget) || isInsidePlazaOrWarehouse(repairTarget, warehouse)) {
            advanceRepairCluster(citizen, repairTarget);
            return true;
        }
        if (!level.hasChunkAt(repairTarget)) {
            repairTarget = null;
            return false;
        }
        if (isRoadCellIntact(level, repairTarget, warehouse)) {
            advanceRepairCluster(citizen, repairTarget);
            return true;
        }

        if (++repairAttempts > REPAIR_MAX_ATTEMPTS) {
            // Avoid getting stuck forever on a single unreachable cell.
            advanceRepairCluster(citizen, repairTarget);
            return true;
        }

        double distSqr = horizDistSqr(citizen, repairTarget.getX() + 0.5, repairTarget.getZ() + 0.5);
        if (distSqr > 20.25) {
            int walkY = getTrueRoadSurfaceY(level, repairTarget.getX(), repairTarget.getZ());
            currentSubTask = String.format(java.util.Locale.ROOT, "Indo até reparo [%.1fm]", Math.sqrt(distSqr));
            CitizenEntity.moveTowards(citizen, level, repairTarget.getX() + 0.5, walkY,
                repairTarget.getZ() + 0.5, 1.0);
            return true;
        }
        citizen.getNavigation().stop();

        BlockPos surface = repairTarget.above();
        CellOutcome outcome = shapeAndPaveRoadCell(citizen, level, surface, false);
        if (outcome == CellOutcome.READY || isRoadCellIntact(level, repairTarget, warehouse)) {
            advanceRepairCluster(citizen, repairTarget);
        }
        return true;
    }

    /**
     * After fixing one broken block, continues fixing any other broken blocks within {@code REPAIR_CLUSTER_SQR}
     * so an entire crater/section is restored in a single visit before returning to construction.
     */
    private void advanceRepairCluster(CitizenEntity citizen, BlockPos justFixed) {
        repairQueue.remove(justFixed);
        repairAttempts = 0;
        double maxRadius = completed ? Double.MAX_VALUE : REPAIR_CLUSTER_SQR;
        repairTarget = pollNearestRepair(citizen, justFixed, maxRadius);
        if (repairTarget == null) {
            actionsSinceRepair = 0;
        }
    }

    private BlockPos pollNearestRepair(CitizenEntity citizen, BlockPos anchor, double maxDistSqr) {
        BlockPos best = null;
        double bestDist = maxDistSqr;
        java.util.Iterator<BlockPos> it = repairQueue.iterator();
        while (it.hasNext()) {
            BlockPos candidate = it.next();
            if (!citizen.isInsideNation(candidate)) {
                it.remove();
                continue;
            }
            double d = candidate.distSqr(anchor);
            if (d <= bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        if (best != null) {
            repairQueue.remove(best);
        }
        return best;
    }

    private enum CellOutcome {
        READY,
        WORK_DONE,
        WAITING_RESOURCE
    }

    private record RoadStep(int x, int z, Direction heading) {
        BlockPos center(int y) {
            return new BlockPos(x, y, z);
        }

        BlockPos lanePos(int laneOffset, int y) {
            return switch (heading) {
                case EAST, WEST -> new BlockPos(x, y, z + laneOffset);
                case NORTH, SOUTH -> new BlockPos(x + laneOffset, y, z);
                default -> new BlockPos(x, y, z);
            };
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
        if (townCenter != null && pos.getX() >= townCenter.getX() - 5 && pos.getX() < townCenter.getX() + 5
            && pos.getZ() >= townCenter.getZ() - 5 && pos.getZ() < townCenter.getZ() + 5) {
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
            CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 1.0);
            return;
        }

        // Convert any logs in inventory into planks if needed later, or keep dirt ready
        // PHASE 1: Cut elevated terrain, trees, and surface foliage across the entire 11x11 first!
        for (int idx = 0; idx < 121; idx++) {
            int x = warehouse.x() - 5 + idx % 11;
            int z = warehouse.z() - 5 + idx / 11;
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
                int navY = getTrueRoadSurfaceY(level, standingPosition.getX(), standingPosition.getZ());
                CitizenEntity.moveTowards(citizen, level, standingPosition.getX() + 0.5, navY, standingPosition.getZ() + 0.5, 1.0);
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
            VILLAGE_CENTER_BLUEPRINT = GridBlueprint.load(level, "village_center_11x11");
        return VILLAGE_CENTER_BLUEPRINT;
    }

    /**
     * Smooths and clears the 50x50 Reforestation Zone in the NW quadrant (dx in [-56..-7], dz in [-56..-7],
     * outside the 30-block no-replant inner radius). Never breaks saplings or tree trunks, and leaves DIRT/GRASS on top.
     */
    private boolean prepareReforestationZoneStep(CitizenEntity citizen, ServerLevel level, CivilizationData data) {
        if (reforestationDone || townCenter == null) return false;
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null) return false;

        int scanned = 0;
        while (reforestationCursor < 2500 && scanned < 25) {
            int col = reforestationCursor % 50;
            int row = reforestationCursor / 50;
            int x = townCenter.getX() - 56 + col;
            int z = townCenter.getZ() - 56 + row;
            BlockPos probe = new BlockPos(x, townCenter.getY(), z);

            if (!citizen.isInsideNation(probe) || !LumberjackJob.isInsideReforestationZone(nation, probe)) {
                reforestationCursor++;
                continue;
            }
            if (!level.hasChunkAt(probe)) return false;

            int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos ground = new BlockPos(x, topY, z);
            BlockState groundState = level.getBlockState(ground);

            // Never disturb planted saplings or growing trees in the reforestation zone!
            if (groundState.is(BlockTags.LOGS) || groundState.is(BlockTags.SAPLINGS)
                || level.getBlockState(ground.above()).is(BlockTags.SAPLINGS)) {
                reforestationCursor++;
                scanned++;
                continue;
            }

            // Clear surface scrub/flowers/bushes (excluding saplings)
            BlockPos above = ground.above();
            BlockState aboveState = level.getBlockState(above);
            if (!aboveState.isAir() && !aboveState.is(BlockTags.SAPLINGS) && isClearableVegetation(aboveState)) {
                level.destroyBlock(above, true, citizen);
            }

            // Fill water puddles or steep 2-block pits/steps relative to neighbors
            boolean isWater = !level.getFluidState(ground).isEmpty();
            int neighborAvgY = (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 1, z) - 1
                + level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x - 1, z) - 1
                + level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + 1) - 1
                + level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z - 1) - 1) / 4;

            if (isWater || topY < neighborAvgY - 1) {
                double distSqr = horizDistSqr(citizen, x + 0.5, z + 0.5);
                if (distSqr > 36.0) {
                    currentSubTask = "Indo nivelar área de reflorestamento 50x50 (NO)";
                    CitizenEntity.moveTowards(citizen, level, x + 0.5, topY + 1, z + 0.5, 1.0);
                    return true;
                }
                BlockPos fillPos = isWater ? ground : ground.above();
                currentSubTask = "Aterrando área de reflorestamento em [" + fillPos.toShortString() + "]";
                citizen.getLookControl().setLookAt(fillPos.getX() + 0.5, fillPos.getY() + 0.5, fillPos.getZ() + 0.5);
                citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (countItem(citizen, Items.DIRT) > 0) {
                    placeBlockFromInventory(citizen, level, fillPos, Blocks.DIRT.defaultBlockState(), Items.DIRT);
                } else {
                    level.setBlockAndUpdate(fillPos, Blocks.DIRT.defaultBlockState());
                }
                return true;
            }

            if (topY > neighborAvgY + 1 && (groundState.is(BlockTags.DIRT) || groundState.is(Blocks.GRASS_BLOCK))) {
                double distSqr = horizDistSqr(citizen, x + 0.5, z + 0.5);
                if (distSqr > 36.0) {
                    currentSubTask = "Indo suavizar relevo do reflorestamento 50x50 (NO)";
                    CitizenEntity.moveTowards(citizen, level, x + 0.5, topY + 1, z + 0.5, 1.0);
                    return true;
                }
                currentSubTask = "Suavizando degrau no reflorestamento em [" + ground.toShortString() + "]";
                equipShovelForRoad(citizen, level);
                citizen.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 0.5, ground.getZ() + 0.5);
                citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (level.destroyBlock(ground, false, citizen)) {
                    citizen.getNpcInventory().addItem(new ItemStack(Items.DIRT, 1));
                }
                return true;
            }

            // Ensure top block is plantable dirt/grass if stone is exposed
            if (isExposedStone(groundState)) {
                level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
            }

            reforestationCursor++;
            scanned++;
        }

        if (reforestationCursor >= 2500) {
            reforestationDone = true;
            citizen.announce("Concluí a terraplanagem suave da área de reflorestamento 50x50 (NO) para o Lenhador.");
        }
        return false;
    }

    private static boolean isNaturalPlazaGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK)
            || state.is(BlockTags.BASE_STONE_OVERWORLD);
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
        citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SHOVEL));
        shovelRequestSent = false;
        return true;
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
        citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        pickaxeRequestSent = false;
        return true;
    }

    private boolean handlePendingWarehouseResource(CitizenEntity citizen, ServerLevel level, CivilizationData data) {
        if (pendingResourceId.isEmpty()) return false;
        net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(pendingResourceId);
        if (key == null) {
            pendingResourceId = "";
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        if (item == Items.AIR || item == Items.DIRT) {
            pendingResourceId = "";
            return false;
        }
        boolean isPlanks = new ItemStack(item).is(ItemTags.PLANKS);
        boolean isStairs = new ItemStack(item).is(ItemTags.STAIRS);
        if (isPlanks) convertOwnLogsToPlanks(citizen);
        int have = isPlanks ? countTaggedItem(citizen, ItemTags.PLANKS)
            : (isStairs ? countTaggedItem(citizen, ItemTags.STAIRS) : countItem(citizen, item));
        String reqId = StorageJob.resourceRequestId(item);
        if (have >= 1) {
            pendingResourceId = "";
            cancelDirtRequest(citizen, data, reqId);
            return false;
        }
        if (!hasOpenResourceRequest(data, citizen, reqId)) {
            requestResource(citizen, level, item, isPlanks ? 16 : 4);
        }
        StorageJob.retrieveRequestedItem(citizen, level, item, isPlanks ? 16 : 4);
        int haveNow = isPlanks ? countTaggedItem(citizen, ItemTags.PLANKS)
            : (isStairs ? countTaggedItem(citizen, ItemTags.STAIRS) : countItem(citizen, item));
        if (haveNow >= 1) {
            pendingResourceId = "";
            cancelDirtRequest(citizen, data, reqId);
            return false;
        }
        return true;
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
                CitizenEntity.moveTowardsPos(citizen, level, source.standPosition(), 1.0);
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
            if (dx <= 5 && dz <= 5) return true;
            if ((dx <= 1 && dz <= CivilizationData.NATION_RADIUS_BLOCKS)
                || (dz <= 1 && dx <= CivilizationData.NATION_RADIUS_BLOCKS)) return true;
            if (Math.abs((position.getX() - townCenter.getX()) - 12) <= 1 && position.getZ() >= townCenter.getZ()) return true;
        }
        if (roadLedgerSet.contains(position)) return true;
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse != null && Math.abs(position.getX() - warehouse.x()) <= 5
            && Math.abs(position.getZ() - warehouse.z()) <= 5) {
            return true;
        }
        CivilizationData.MineSite mine = data.getMineSite(citizen.getNationId());
        return mine != null && Math.abs(position.getX() - mine.x()) <= 4
            && Math.abs(position.getZ() - mine.z()) <= 4;
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
