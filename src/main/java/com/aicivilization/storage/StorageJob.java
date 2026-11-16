package com.aicivilization.storage;

import com.aicivilization.blueprint.GridBlueprint;
import com.aicivilization.data.CivilizationData;
import com.aicivilization.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.aicivilization.profession.ProfessionJob;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Nation warehouse building, inbound pack handling, sorting, and tool requests. */
public final class StorageJob implements ProfessionJob {
    public static final String ID = "storage";
    public static final String LABEL = "Storager";
    public static final String BEST_AXE_REQUEST_ID = "ai_civilization:best_axe";
    public static final String BEST_PICKAXE_REQUEST_ID = "ai_civilization:best_pickaxe";
    public static final String BEST_SHOVEL_REQUEST_ID = "ai_civilization:best_shovel";
    private static final String RESOURCE_REQUEST_PREFIX = "ai_civilization:resource:";
    private static final int DEFAULT_WAREHOUSE_RADIUS = 12;
    private static final int TICK_INTERVAL = 20;
    private static final List<ToolRecipe> AXE_RECIPES = List.of(
        new ToolRecipe(Items.DIAMOND_AXE, Items.DIAMOND, false),
        new ToolRecipe(Items.IRON_AXE, Items.IRON_INGOT, false),
        new ToolRecipe(Items.STONE_AXE, Items.COBBLESTONE, false),
        new ToolRecipe(Items.WOODEN_AXE, null, true)
    );
    private static final List<ToolRecipe> PICKAXE_RECIPES = List.of(
        new ToolRecipe(Items.NETHERITE_PICKAXE, Items.NETHERITE_INGOT, false),
        new ToolRecipe(Items.DIAMOND_PICKAXE, Items.DIAMOND, false),
        new ToolRecipe(Items.IRON_PICKAXE, Items.IRON_INGOT, false),
        new ToolRecipe(Items.STONE_PICKAXE, Items.COBBLESTONE, false),
        new ToolRecipe(Items.WOODEN_PICKAXE, null, true)
    );
    private static final List<ToolRecipe> SHOVEL_RECIPES = List.of(
        new ToolRecipe(Items.DIAMOND_SHOVEL, Items.DIAMOND, false, 1),
        new ToolRecipe(Items.IRON_SHOVEL, Items.IRON_INGOT, false, 1),
        new ToolRecipe(Items.STONE_SHOVEL, Items.COBBLESTONE, false, 1),
        new ToolRecipe(Items.WOODEN_SHOVEL, null, true, 1)
    );

    private int cooldown;
    private int lastMissingPlanksNotice = -1000;
    private int lastMissingAxeMaterialsNotice = -1000;
    private int lastClearingNoticeTick = -1000;
    private int decorationIndex;
    private boolean decoratingFence;
    private UUID resourceDonorId;
    private static GridBlueprint WAREHOUSE_BLUEPRINT;

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
        if (citizen.getNationId() == null) return false;
        CivilizationData data = CivilizationData.get(level);
        if (!data.claimStorager(citizen.getNationId(), citizen.getUUID())) {
            citizen.announce("Esta nação já tem um Storager. Só pode haver um por cidade.");
            return false;
        }
        if (!ensureWarehouseLocation(citizen, level)) {
            data.releaseStorager(citizen.getNationId(), citizen.getUUID());
            citizen.announce("Não encontrei um local seguro para o armazém fora da área central do estandarte.");
            return false;
        }
        if (!citizen.getMainHandItem().is(ItemTags.AXES) && !citizen.getNpcInventory().hasAnyMatching(stack -> stack.is(ItemTags.AXES))) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        }
        citizen.announce("Vou organizar o armazém da minha nação.");
        return true;
    }

    @Override
    public void stop(CitizenEntity citizen, ServerLevel level, boolean announce) {
        if (citizen.getNationId() != null) {
            CivilizationData.get(level).releaseStorager(citizen.getNationId(), citizen.getUUID());
        }
        citizen.getNavigation().stop();
        if (announce) {
            citizen.announce("Parei de organizar o armazém.");
        }
    }

    @Override
    public boolean needsNavigation(CitizenEntity citizen, ServerLevel level) {
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        return warehouse != null && !isCitizenInsideWarehouse(citizen, warehouse);
    }

    public static boolean isCitizenInsideWarehouse(CitizenEntity citizen, CivilizationData.Warehouse warehouse) {
        if (warehouse == null) return false;
        int halfSize = Math.max(6, warehouse.radius() / 2);
        return Math.abs(citizen.getBlockX() - warehouse.x()) <= halfSize
            && Math.abs(citizen.getBlockZ() - warehouse.z()) <= halfSize;
    }

    @Override
    public void save(ValueOutput output) {
        output.putInt("StorageDecorationIndex", decorationIndex);
        output.putBoolean("StorageDecoratingFence", decoratingFence);
    }

    @Override
    public void load(ValueInput input) {
        decorationIndex = input.getIntOr("StorageDecorationIndex", 0);
        decoratingFence = input.getBooleanOr("StorageDecoratingFence", false);
    }

    public static boolean ensureWarehouseLocation(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null || !nation.dimension().equals(level.dimension().location())) return false;
        CivilizationData.Warehouse current = data.warehouse(nation.id());
        if (current != null && current.dimension().equals(level.dimension().location())
            && outsideStandardExclusion(current.center(), nation)) return true;

        // Village Map Layout:
        // - 10x10 Village Center occupies dx in [-5..+4]
        // - 15x3 Center->Warehouse avenue occupies dx in [+5..+19]
        // - 10x10 Warehouse occupies dx in [+20..+29] -> center at x = nation.x() + 25, z = nation.z()
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] distances = {25, 22, 18};
        BlockPos bestCandidate = null;
        int bestScore = Integer.MAX_VALUE;

        for (int distance : distances) {
            for (int dirIdx = 0; dirIdx < directions.length; dirIdx++) {
                int[] direction = directions[dirIdx];
                int x = nation.x() + direction[0] * distance;
                int z = nation.z() + direction[1] * distance;
                BlockPos probe = new BlockPos(x, nation.y(), z);
                if (!level.hasChunkAt(probe)) continue;
                int optimalY = nation.y();
                BlockPos candidate = new BlockPos(x, optimalY, z);
                if (!outsideStandardExclusion(candidate, nation)) continue;
                if (!nation.contains(candidate.offset(10, 0, 0)) || !nation.contains(candidate.offset(-10, 0, 0))
                    || !nation.contains(candidate.offset(0, 0, 10)) || !nation.contains(candidate.offset(0, 0, -10))) continue;

                // Prioritize East (+X, dirIdx == 0) at distance 25 to match the Village Map (10x10 Center -> 15x3 Road -> 10x10 Warehouse)
                int directionPenalty = dirIdx * 200;
                int score = evaluateWarehouseSiteScore(level, candidate) + Math.abs(distance - 25) * 8 + directionPenalty;
                if (score < bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
                if (bestCandidate != null && distance == 25 && dirIdx == 0) break;
            }
            if (bestCandidate != null && distance == 25) break; // Prioritize 25 blocks away (15-block road between 10x10 plots)!
        }
        if (bestCandidate != null) {
            data.setWarehouse(nation.id(), bestCandidate, 12, level.dimension().location());
            return true;
        }
        return false;
    }

    /** Millénaire-style dominant terrain height calculation: picks the modal (most frequent) natural ground Y in the 10x10 plot. */
    private static int computeOptimalWarehouseSurfaceY(ServerLevel level, int centerX, int centerZ, int fallbackY) {
        java.util.Map<Integer, Integer> heightFrequency = new java.util.HashMap<>();
        int bestGroundY = fallbackY - 1;
        int bestCount = 0;

        for (int dx = -5; dx < 5; dx++) {
            for (int dz = -5; dz < 5; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (!level.hasChunkAt(new BlockPos(x, fallbackY, z))) continue;
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                // Walk down past tree logs or clearable vegetation to find the true terrain ground
                while (topY > level.getMinY() + 5) {
                    BlockPos pos = new BlockPos(x, topY, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.LOGS) || com.aicivilization.profession.BuilderJob.isClearableVegetation(state)) {
                        topY--;
                    } else {
                        break;
                    }
                }
                int count = heightFrequency.merge(topY, 1, Integer::sum);
                if (count > bestCount) {
                    bestCount = count;
                    bestGroundY = topY;
                }
            }
        }
        return bestGroundY + 1;
    }

    private static int evaluateWarehouseSiteScore(ServerLevel level, BlockPos center) {
        int score = 0;
        int targetGroundY = center.getY() - 1;
        for (int dx = -5; dx < 5; dx++) {
            for (int dz = -5; dz < 5; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                BlockPos p = new BlockPos(x, center.getY(), z);
                if (!level.hasChunkAt(p)) {
                    score += 50;
                    continue;
                }
                int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos groundPos = new BlockPos(x, groundY, z);
                BlockState state = level.getBlockState(groundPos);
                if (state.is(BlockTags.LOGS)) {
                    score += 25; // Penalize trees inside the 10x10 plot
                } else if (!level.getFluidState(groundPos).isEmpty()) {
                    score += 20; // Penalize water/lava
                } else {
                    score += Math.abs(groundY - targetGroundY) * 2; // Prefer flatter terrain
                }
            }
        }
        return score;
    }

    private static boolean outsideStandardExclusion(BlockPos pos, CivilizationData.Nation nation) {
        return Math.abs(pos.getX() - nation.x()) >= 10 || Math.abs(pos.getZ() - nation.z()) >= 10;
    }

    public void tick(CitizenEntity citizen, ServerLevel level) {
        if (cooldown-- > 0) return;
        cooldown = TICK_INTERVAL;
        CivilizationData data = CivilizationData.get(level);
        UUID nationId = citizen.getNationId();
        CivilizationData.Warehouse warehouse = data.warehouse(nationId);
        if (warehouse == null) {
            if (!ensureWarehouseLocation(citizen, level)) return;
            warehouse = data.warehouse(nationId);
            citizen.announce("Estou definindo o armazém de " + citizen.getNationName() + " nesta área.");
        }
        if (!warehouse.dimension().equals(level.dimension().location())) return;
        resourceDonorId = null;
        List<BlockPos> chests = findChests(level, warehouse);
        boolean validInbox = warehouse.hasInbox()
            && level.getBlockEntity(warehouse.inbox()) instanceof ChestBlockEntity;
        if (validInbox || hasBlueprintChest(level, warehouse)) data.markWarehousePlotPrepared(nationId);
        if (!data.warehousePlotPrepared(nationId)) {
            boolean firstRequest = !data.warehousePlotRequested(nationId);
            data.requestWarehousePlot(nationId);
            if (firstRequest) citizen.announce("Solicitei ao Builder um terreno plano de 10x10 para começar a warehouse.");
            if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                if (citizen.getNavigation().isDone())
                    CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
                return;
            }
            // Even before the Builder finishes the 10x10 plot, allow Storager to use any existing chests at the Warehouse
            // so resource/tool requests (like crafting planks from logs) are never blocked.
            if (processRequests(citizen, level, data, warehouse, chests, warehouse.inbox())) return;
            if (chests.isEmpty()) takeLogsFromNearbyCitizen(citizen, level, warehouse, 2);
            return;
        }
        if (!isCitizenInsideWarehouse(citizen, warehouse)) {
            if (citizen.getNavigation().isDone())
                CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
            return;
        }

        BlockPos inbox = warehouse.inbox();
        if (inbox == null || !(level.getBlockEntity(inbox) instanceof ChestBlockEntity)) {
            if (!chests.isEmpty()) {
                inbox = chests.get(0);
                warehouse = data.setWarehouseInbox(nationId, inbox);
            } else {
                buildChest(citizen, level, warehouse, chests, true);
                return;
            }
        }

        if (!chests.contains(inbox)) chests = findChests(level, warehouse);
        // Tool requests have priority over sorting incoming resources. Their materials can
        // still be counted in the inbox, so the Storager can craft before moving the items.
        if (processRequests(citizen, level, data, warehouse, chests, inbox)) return;
        // When no open requests are pending, flush excess personal inventory into warehouse chests so Storager never gets full!
        depositExcessStoragerInventory(citizen, level, chests);
        if (chests.size() < 2 && hasPendingSort((ChestBlockEntity) level.getBlockEntity(inbox))) {
            if (!buildChest(citizen, level, warehouse, chests, false)) return;
            chests = findChests(level, warehouse);
        }

        sortInbox((ChestBlockEntity) level.getBlockEntity(inbox), level, citizen, warehouse, chests);
        ChestBlockEntity currentInbox = level.getBlockEntity(inbox) instanceof ChestBlockEntity chest ? chest : null;
        if (chests.size() >= 3 && currentInbox != null && !hasPendingSort(currentInbox)
            && data.openStorageRequests(nationId).isEmpty()) {
            decorateWarehouse(citizen, level, warehouse, chests);
        }
    }

    public String debugStatus(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse == null) return "warehouse=missing, requests=" + data.openStorageRequests(citizen.getNationId()).size();
        if (!data.warehousePlotPrepared(citizen.getNationId())) return "phase=waiting-for-builder-plot-10x10, warehouse=" + warehouse.center().toShortString();
        if (resourceDonorId != null) return "phase=walking-to-lumberjack, donor=" + resourceDonorId;
        List<BlockPos> chests = findChests(level, warehouse);
        String phase;
        if (citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 64.0) phase = "walking-to-warehouse";
        else if (!warehouse.hasInbox() || !(level.getBlockEntity(warehouse.inbox()) instanceof ChestBlockEntity)) phase = "building-inbox";
        else if (!data.openStorageRequests(citizen.getNationId()).isEmpty()) phase = "handling-requests";
        else if (hasPendingSort((ChestBlockEntity) level.getBlockEntity(warehouse.inbox()))) phase = "sorting-inbox";
        else if (chests.size() < 3) phase = "waiting-for-resources-or-chests";
        else phase = "decorating-or-idle";
        return "phase=" + phase + ", warehouse=" + warehouse.center().toShortString()
            + ", distance=" + Math.round(citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5))
            + ", chests=" + chests.size() + ", requests=" + data.openStorageRequests(citizen.getNationId()).size();
    }

    public String workStatus(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse == null) return "Escolhendo o local do armazém";
        if (!data.warehousePlotPrepared(citizen.getNationId())) return "Guardando recursos e aguardando o Builder preparar o terreno 10x10";
        if (resourceDonorId != null) return "Indo buscar madeira com um cidadão da nação";
        if (citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 196.0)
            return "Indo até o armazém";
        List<BlockPos> chests = findChests(level, warehouse);
        if (!warehouse.hasInbox() || !(level.getBlockEntity(warehouse.inbox()) instanceof ChestBlockEntity))
            return "Construindo o baú principal";
        if (!data.openStorageRequests(citizen.getNationId()).isEmpty()) return "Atendendo pedidos e entregando ferramentas";
        ChestBlockEntity inbox = (ChestBlockEntity) level.getBlockEntity(warehouse.inbox());
        if (hasPendingSort(inbox)) return "Organizando os itens nos baús";
        if (chests.size() < 3) return "Ampliando o armazém com mais baús";
        return "Organizando e mantendo o armazém";
    }

    /** Returns true while delivery owns the lumberjack's work cycle. */
    public static boolean deliverLumberjackPack(CitizenEntity citizen, ServerLevel level, Item resource) {
        return deliverResourcePack(citizen, level, resource, 64);
    }

    /** Returns true while delivering or waiting for the input chest. */
    public static boolean deliverResourcePack(CitizenEntity citizen, ServerLevel level, Item resource, int amount) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) {
            if (citizen.tickCount % 200 == 0) citizen.announce("Completei um pack, mas ainda não há um armazém nesta nação.");
            return true;
        }
        BlockPos inbox = warehouse.inbox();
        if (inbox == null || !(level.getBlockEntity(inbox) instanceof ChestBlockEntity)) {
            citizen.getNavigation().moveTo(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
            return true;
        }
        if (citizen.distanceToSqr(inbox.getX() + 0.5, inbox.getY(), inbox.getZ() + 0.5) > 16.0) {
            citizen.getNavigation().moveTo(inbox.getX() + 0.5, inbox.getY(), inbox.getZ() + 0.5, 0.9);
            return true;
        }

        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(inbox);
        ItemStack pack = new ItemStack(resource, amount);
        if (!canFit(chest, pack)) return true;
        ItemStack remainder = insert(chest, pack);
        int delivered = pack.getCount() - remainder.getCount();
        if (delivered > 0) {
            citizen.getNpcInventory().removeItemType(resource, delivered);
            citizen.announce("Entreguei um pack de " + delivered + " " + BuiltInRegistries.ITEM.getKey(resource).getPath() + " ao armazém.");
        }
        if (!remainder.isEmpty()) citizen.getNavigation().stop();
        return true;
    }

    public static boolean hasLumberjackDeliveryResources(CitizenEntity citizen) {
        int totalLogs = 0;
        int secondaryCount = 0;
        int emptySlots = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (stack.is(ItemTags.LOGS)) totalLogs += stack.getCount();
            else if (isLumberjackSecondaryResource(stack)) secondaryCount += stack.getCount();
        }
        // Trigger delivery when the Lumberjack has a solid batch of wood (>= 24 logs),
        // OR when his inventory is nearly full (< 3 empty slots) and he has cargo to deliver!
        // Never stop chopping after 1 single tree just because 1 stick or sapling dropped!
        return totalLogs >= 24 || (emptySlots < 3 && (totalLogs > 0 || secondaryCount > 0));
    }

    public static boolean hasAnyDeliverableLumberjackCargo(CitizenEntity citizen) {
        int saplingsSeen = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(ItemTags.LOGS) || stack.is(Items.STICK) || stack.is(Items.APPLE)) return true;
            if (isSapling(stack)) {
                saplingsSeen += stack.getCount();
                if (saplingsSeen > 8) return true; // Keep up to 8 saplings for replanting
            }
        }
        return false;
    }

    public static String lumberjackDeliveryMessage(CitizenEntity citizen) {
        int totalLogs = 0;
        int sticks = 0;
        int apples = 0;
        int saplings = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) totalLogs += stack.getCount();
            else if (stack.is(Items.STICK)) sticks += stack.getCount();
            else if (stack.is(Items.APPLE)) apples += stack.getCount();
            else if (isSapling(stack)) saplings += stack.getCount();
        }
        List<String> cargo = new ArrayList<>();
        if (totalLogs > 0) cargo.add(totalLogs + "x troncos de madeira");
        if (sticks > 0) cargo.add(sticks + "x gravetos");
        if (apples > 0) cargo.add(apples + (apples == 1 ? "x maçã" : "x maçãs"));
        if (saplings > 8) cargo.add((saplings - 8) + ((saplings - 8) == 1 ? "x muda" : "x mudas"));
        return "Voltando para a Warehouse entregar " + String.join(", ", cargo) + ".";
    }

    /** Returns true while walking to the warehouse or while deliverable resources remain. */
    public static boolean deliverLumberjackResources(CitizenEntity citizen, ServerLevel level) {
        if (!hasAnyDeliverableLumberjackCargo(citizen)) return false;
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return false;

        List<BlockPos> allChests = findChests(level, warehouse);
        BlockPos inbox = warehouse.inbox();
        boolean hasValidInbox = inbox != null && level.getBlockEntity(inbox) instanceof ChestBlockEntity;

        // If no chests exist yet in the Warehouse, walk inside and hand resources directly to the Storager!
        if (!hasValidInbox && allChests.isEmpty()) {
            if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.95);
                return true;
            }
            UUID storagerId = data.storager(citizen.getNationId());
            if (storagerId != null && level.getEntity(storagerId) instanceof CitizenEntity storager
                && storager != citizen && citizen.getNationId().equals(storager.getNationId())) {
                if (citizen.distanceToSqr(storager) > 16.0) {
                    if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(storager, 0.95);
                    return true;
                }
                int totalTransferred = transferLumberjackCargoToContainer(citizen, storager.getNpcInventory());
                if (totalTransferred > 0) {
                    citizen.getNpcInventory().setChanged();
                    storager.getNpcInventory().setChanged();
                    citizen.announce("Entreguei " + totalTransferred + " recursos diretamente ao Storager na Warehouse.");
                }
            }
            return false;
        }

        BlockPos targetChestPos = hasValidInbox ? inbox : allChests.get(0);
        if (citizen.distanceToSqr(targetChestPos.getX() + 0.5, targetChestPos.getY(), targetChestPos.getZ() + 0.5) > 16.0) {
            CitizenEntity.moveTowardsPos(citizen, level, targetChestPos, 0.95);
            return true;
        }

        // Build ordered list of chests: inbox first, then all other warehouse chests as fallback if inbox is full!
        List<ChestBlockEntity> targetChests = new ArrayList<>();
        if (hasValidInbox && level.getBlockEntity(inbox) instanceof ChestBlockEntity inboxChest) {
            targetChests.add(inboxChest);
        }
        for (BlockPos pos : allChests) {
            if (pos.equals(inbox)) continue;
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity c) targetChests.add(c);
        }

        int totalLogsDelivered = 0;
        int sticks = 0;
        int apples = 0;
        int saplings = 0;
        int saplingsKept = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack inventoryStack = citizen.getNpcInventory().getItem(slot);
            if (inventoryStack.isEmpty()) continue;
            int amount;
            if (inventoryStack.is(ItemTags.LOGS) || inventoryStack.is(Items.STICK) || inventoryStack.is(Items.APPLE)) {
                amount = inventoryStack.getCount();
            } else if (isSapling(inventoryStack)) {
                int canKeep = Math.max(0, 8 - saplingsKept);
                int keepNow = Math.min(canKeep, inventoryStack.getCount());
                saplingsKept += keepNow;
                amount = inventoryStack.getCount() - keepNow;
            } else continue;
            if (amount <= 0) continue;

            int remainingToInsert = amount;
            for (ChestBlockEntity targetChest : targetChests) {
                if (remainingToInsert <= 0) break;
                ItemStack cargo = new ItemStack(inventoryStack.getItem(), remainingToInsert);
                ItemStack remainder = insert(targetChest, cargo);
                int delivered = remainingToInsert - remainder.getCount();
                if (delivered > 0) {
                    inventoryStack.shrink(delivered);
                    if (inventoryStack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                    targetChest.setChanged();
                    remainingToInsert -= delivered;
                    if (cargo.is(ItemTags.LOGS)) totalLogsDelivered += delivered;
                    else if (cargo.is(Items.STICK)) sticks += delivered;
                    else if (cargo.is(Items.APPLE)) apples += delivered;
                    else if (isSapling(cargo)) saplings += delivered;
                }
            }
        }

        // Fallback: if all chests were full and cargo remains, hand the rest to the Storager inside the Warehouse!
        if (hasAnyDeliverableLumberjackCargo(citizen)) {
            UUID storagerId = data.storager(citizen.getNationId());
            if (storagerId != null && level.getEntity(storagerId) instanceof CitizenEntity storager
                && storager != citizen && isCitizenInsideWarehouse(storager, warehouse)) {
                int movedToStorager = transferLumberjackCargoToContainer(citizen, storager.getNpcInventory());
                if (movedToStorager > 0) {
                    storager.getNpcInventory().setChanged();
                    totalLogsDelivered += movedToStorager;
                }
            }
        }

        citizen.getNpcInventory().setChanged();
        List<String> deliveredCargo = new ArrayList<>();
        if (totalLogsDelivered > 0) deliveredCargo.add(totalLogsDelivered + "x troncos de madeira");
        if (sticks > 0) deliveredCargo.add(sticks + "x gravetos");
        if (apples > 0) deliveredCargo.add(apples + (apples == 1 ? "x maçã" : "x maçãs"));
        if (saplings > 0) deliveredCargo.add(saplings + (saplings == 1 ? "x muda" : "x mudas"));
        if (!deliveredCargo.isEmpty()) {
            citizen.announce("Entreguei " + String.join(", ", deliveredCargo) + " na Warehouse.");
        }
        // Return false once we reached the chest and deposited all we could, so the Lumberjack never freezes if chests are full!
        return false;
    }

    private static int transferLumberjackCargoToContainer(CitizenEntity citizen, Container targetContainer) {
        int totalTransferred = 0;
        int saplingsKept = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            int amount;
            if (stack.is(ItemTags.LOGS) || stack.is(Items.STICK) || stack.is(Items.APPLE)) {
                amount = stack.getCount();
            } else if (isSapling(stack)) {
                int canKeep = Math.max(0, 8 - saplingsKept);
                int keepNow = Math.min(canKeep, stack.getCount());
                saplingsKept += keepNow;
                amount = stack.getCount() - keepNow;
            } else continue;
            if (amount <= 0) continue;
            ItemStack toMove = new ItemStack(stack.getItem(), amount);
            ItemStack rem = insert(targetContainer, toMove);
            int moved = amount - rem.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                totalTransferred += moved;
            }
        }
        return totalTransferred;
    }

    public static boolean retrieveFirstSapling(CitizenEntity citizen, ServerLevel level) {
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return false;
        for (BlockPos pos : findChests(level, warehouse)) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!isSapling(stack)) continue;
                if (citizen.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > 16.0) {
                    CitizenEntity.moveTowardsPos(citizen, level, pos, 0.9);
                    return true;
                }
                int amount = Math.min(64, stack.getCount());
                ItemStack sapling = stack.split(amount);
                if (stack.isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
                ItemStack remainder = citizen.getNpcInventory().addItem(sapling);
                if (!remainder.isEmpty()) insert(chest, remainder);
                chest.setChanged();
                citizen.announce("Peguei " + amount + " muda(s) no armazém para reflorestar meu território.");
                return true;
            }
        }
        return false;
    }

    private static boolean isLumberjackSecondaryResource(ItemStack stack) {
        return stack.is(Items.STICK) || stack.is(Items.APPLE) || isSapling(stack);
    }

    private static boolean isSapling(ItemStack stack) {
        return !stack.isEmpty() && Block.byItem(stack.getItem()).defaultBlockState().is(BlockTags.SAPLINGS);
    }

    /** Returns true while the Storager is fulfilling a matching tool request. */
    public static boolean retrieveBestAxe(CitizenEntity citizen, ServerLevel level) {
        return retrieveBestTool(citizen, level, AXE_RECIPES);
    }

    public static boolean retrieveBestPickaxe(CitizenEntity citizen, ServerLevel level) {
        return retrieveBestPickaxe(citizen, level, 0);
    }

    /** Retrieves only a pickaxe that meets the requested mining tier. */
    public static boolean retrieveBestPickaxe(CitizenEntity citizen, ServerLevel level, int minimumTier) {
        List<ToolRecipe> eligible = PICKAXE_RECIPES.stream()
            .filter(recipe -> pickaxeTier(recipe.tool()) >= minimumTier).toList();
        if (eligible.isEmpty()) return false;
        return retrieveBestTool(citizen, level, eligible);
    }

    public static String pickaxeRequestId(int minimumTier) {
        return minimumTier <= 0 ? BEST_PICKAXE_REQUEST_ID : BEST_PICKAXE_REQUEST_ID + ".tier_" + minimumTier;
    }

    public static String resourceRequestId(Item item) {
        return RESOURCE_REQUEST_PREFIX + BuiltInRegistries.ITEM.getKey(item);
    }

    public static boolean retrieveBestShovel(CitizenEntity citizen, ServerLevel level) {
        return retrieveBestTool(citizen, level, SHOVEL_RECIPES);
    }

    /** Walks to the Warehouse chests or the nation's Storager and transfers requested materials. */
    public static boolean retrieveRequestedItem(CitizenEntity citizen, ServerLevel level, Item item, int amount) {
        CivilizationData data = CivilizationData.get(level);
        UUID nationId = citizen.getNationId();
        String requestId = resourceRequestId(item);
        boolean isPlanks = new ItemStack(item).is(ItemTags.PLANKS);
        boolean isStairs = new ItemStack(item).is(ItemTags.STAIRS);
        TagKey<Item> matchTag = isPlanks ? ItemTags.PLANKS : (isStairs ? ItemTags.STAIRS : null);

        // 1. Check Warehouse chests first (and convert logs inside chests if planks are requested)
        CivilizationData.Warehouse warehouse = data.warehouse(nationId);
        if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
            List<BlockPos> chests = findChests(level, warehouse);
            for (BlockPos chestPos : chests) {
                if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;
                Item targetItem = findMatchingItemInContainer(chest, item, matchTag);
                Item logToConvert = (targetItem == null && isPlanks) ? firstLogItem(chest) : null;
                if (targetItem == null && logToConvert == null) continue;

                if (citizen.distanceToSqr(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) > 16.0) {
                    CitizenEntity.moveTowardsPos(citizen, level, chestPos, 0.95);
                    return true;
                }

                if (targetItem == null && logToConvert != null) {
                    Item planksFromLog = planksForLog(logToConvert);
                    if (planksFromLog != Items.AIR) {
                        int logsNeeded = Math.max(1, (amount + 3) / 4);
                        int logsAvailable = Math.min(logsNeeded, countItem(chest, logToConvert));
                        while (logsAvailable > 0 && citizen.getNpcInventory().canAddItem(new ItemStack(planksFromLog, 4))) {
                            if (removeItem(chest, logToConvert, 1) == 0) {
                                citizen.getNpcInventory().addItem(new ItemStack(planksFromLog, 4));
                                logsAvailable--;
                            } else {
                                break;
                            }
                        }
                        chest.setChanged();
                        citizen.getNpcInventory().setChanged();
                        fulfillMatchingRequestsForCitizen(data, citizen, requestId);
                        citizen.announce("Peguei troncos no baú da Warehouse e converti em tábuas para a obra.");
                        return true;
                    }
                }

                if (targetItem != null) {
                    int available = Math.min(amount, countItem(chest, targetItem));
                    if (available > 0 && citizen.getNpcInventory().canAddItem(new ItemStack(targetItem, available))) {
                        int notRemoved = removeItem(chest, targetItem, available);
                        int taken = available - notRemoved;
                        if (taken > 0) {
                            citizen.getNpcInventory().addItem(new ItemStack(targetItem, taken));
                            chest.setChanged();
                            citizen.getNpcInventory().setChanged();
                            fulfillMatchingRequestsForCitizen(data, citizen, requestId);
                            citizen.announce("Retirei " + taken + " " + BuiltInRegistries.ITEM.getKey(targetItem).getPath() + " do baú da Warehouse.");
                            return true;
                        }
                    }
                }
            }
        }

        // 2. Check the Storager's inventory (citizen walks into the Warehouse to pick up from Storager)
        UUID storagerId = data.storager(nationId);
        if (storagerId != null && level.getEntity(storagerId) instanceof CitizenEntity storager
            && storager != citizen && nationId.equals(storager.getNationId())) {
            Item targetInStorager = findMatchingItemInContainer(storager.getNpcInventory(), item, matchTag);
            Item logInStorager = (targetInStorager == null && isPlanks) ? firstLogItem(storager.getNpcInventory()) : null;
            if (targetInStorager != null || logInStorager != null) {
                if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                    CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.95);
                    return true;
                }
                if (citizen.distanceToSqr(storager) > 16.0) {
                    if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(storager, 0.95);
                    return true;
                }
                if (targetInStorager == null && logInStorager != null) {
                    Item planksFromLog = planksForLog(logInStorager);
                    if (planksFromLog != Items.AIR && citizen.getNpcInventory().canAddItem(new ItemStack(planksFromLog, 4))) {
                        int logsNeeded = Math.max(1, (amount + 3) / 4);
                        int logsAvailable = Math.min(logsNeeded, countItem(storager.getNpcInventory(), logInStorager));
                        while (logsAvailable > 0 && citizen.getNpcInventory().canAddItem(new ItemStack(planksFromLog, 4))) {
                            if (removeItem(storager.getNpcInventory(), logInStorager, 1) == 0) {
                                citizen.getNpcInventory().addItem(new ItemStack(planksFromLog, 4));
                                logsAvailable--;
                            } else {
                                break;
                            }
                        }
                        storager.getNpcInventory().setChanged();
                        citizen.getNpcInventory().setChanged();
                        fulfillMatchingRequestsForCitizen(data, citizen, requestId);
                        citizen.announce("Vim até a Warehouse, recebi troncos do Storager e converti em tábuas.");
                        return true;
                    }
                }
                if (targetInStorager != null) {
                    int available = Math.min(amount, countItem(storager.getNpcInventory(), targetInStorager));
                    if (available > 0 && citizen.getNpcInventory().canAddItem(new ItemStack(targetInStorager, available))
                        && removeItem(storager.getNpcInventory(), targetInStorager, available) == 0) {
                        citizen.getNpcInventory().addItem(new ItemStack(targetInStorager, available));
                        storager.getNpcInventory().setChanged();
                        citizen.getNpcInventory().setChanged();
                        fulfillMatchingRequestsForCitizen(data, citizen, requestId);
                        citizen.announce("Vim até a Warehouse e recebi " + available + " "
                            + BuiltInRegistries.ITEM.getKey(targetInStorager).getPath() + " do Storager.");
                        return true;
                    }
                }
            }
        }

        // 3. If the citizen has an open request for this resource (excluding dirt, which Builder can dig if out of stock), walk into the Warehouse and wait passively!
        if (item != Items.DIRT) {
            for (CivilizationData.StorageRequest req : data.openStorageRequests(nationId)) {
                if (req.requesterId().equals(citizen.getUUID()) && req.itemId().equals(requestId)) {
                    if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
                        if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                            if (citizen.getNavigation().isDone()) {
                                CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.95);
                            }
                        } else {
                            citizen.getNavigation().stop();
                        }
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    /** Returns a formatted description if the citizen is currently waiting in/for the Warehouse with an open request, or empty string. */
    public static String describeOpenWaitRequest(CitizenEntity citizen, ServerLevel level) {
        if (citizen.getNationId() == null) return "";
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        boolean inside = warehouse != null && isCitizenInsideWarehouse(citizen, warehouse);
        String prefix = inside ? "Aguardando na Warehouse: " : "Indo à Warehouse pedir: ";
        for (CivilizationData.StorageRequest req : data.openStorageRequests(citizen.getNationId())) {
            if (!req.requesterId().equals(citizen.getUUID())) continue;
            String itemLabel;
            if (req.itemId().equals(BEST_AXE_REQUEST_ID)) itemLabel = "Machado";
            else if (req.itemId().equals(BEST_PICKAXE_REQUEST_ID)) itemLabel = "Picareta";
            else if (req.itemId().startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_")) {
                itemLabel = "Picareta tier " + req.itemId().substring((BEST_PICKAXE_REQUEST_ID + ".tier_").length());
            } else if (req.itemId().equals(BEST_SHOVEL_REQUEST_ID)) itemLabel = "Pá";
            else if (req.itemId().equals("ai_civilization:resource:minecraft:oak_planks")) itemLabel = "Tábuas";
            else if (req.itemId().equals("ai_civilization:resource:minecraft:oak_stairs")) itemLabel = "Escadas";
            else if (req.itemId().equals("ai_civilization:resource:minecraft:dirt")) itemLabel = "Terra";
            else if (req.itemId().startsWith(RESOURCE_REQUEST_PREFIX)) {
                itemLabel = req.itemId().substring(RESOURCE_REQUEST_PREFIX.length());
            } else {
                itemLabel = req.itemId();
            }
            return prefix + req.count() + "x " + itemLabel;
        }
        return "";
    }

    private static Item findMatchingItemInContainer(Container container, Item item, TagKey<Item> matchTag) {
        if (countItem(container, item) > 0) return item;
        if (matchTag != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty() && s.is(matchTag)) return s.getItem();
            }
        }
        return null;
    }

    private static void fulfillMatchingRequestsForCitizen(CivilizationData data, CitizenEntity citizen, String requestId) {
        for (CivilizationData.StorageRequest req : data.openStorageRequests(citizen.getNationId())) {
            if (req.requesterId().equals(citizen.getUUID()) && req.itemId().equals(requestId)) {
                data.fulfillStorageRequest(req.id());
            }
        }
    }

    private static int pickaxeTier(Item pickaxe) {
        if (pickaxe == Items.NETHERITE_PICKAXE) return 4;
        if (pickaxe == Items.DIAMOND_PICKAXE) return 3;
        if (pickaxe == Items.IRON_PICKAXE) return 2;
        if (pickaxe == Items.STONE_PICKAXE) return 1;
        if (pickaxe == Items.WOODEN_PICKAXE) return 0;
        return -1;
    }

    private static boolean retrieveBestTool(CitizenEntity citizen, ServerLevel level, List<ToolRecipe> recipes) {
        CivilizationData data = CivilizationData.get(level);
        UUID nationId = citizen.getNationId();
        CivilizationData.Warehouse warehouse = data.warehouse(nationId);

        // 1. Check Warehouse chests for the best available tool
        if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
            List<BlockPos> chests = findChests(level, warehouse);
            for (ToolRecipe recipe : recipes) {
                for (BlockPos chestPos : chests) {
                    if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;
                    int slot = findItem(chest, recipe.tool());
                    if (slot < 0) continue;
                    if (citizen.distanceToSqr(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) > 16.0) {
                        CitizenEntity.moveTowardsPos(citizen, level, chestPos, 0.95);
                        return true;
                    }
                    ItemStack tool = chest.getItem(slot).split(1);
                    if (chest.getItem(slot).isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
                    chest.setChanged();
                    if (citizen.getMainHandItem().isEmpty()) {
                        citizen.setItemSlot(EquipmentSlot.MAINHAND, tool);
                    } else {
                        ItemStack rem = citizen.getNpcInventory().addItem(tool);
                        if (!rem.isEmpty()) insert(chest, rem);
                    }
                    citizen.getNpcInventory().setChanged();
                    fulfillMatchingToolRequests(data, citizen, recipes);
                    citizen.announce("Peguei " + BuiltInRegistries.ITEM.getKey(recipe.tool()).getPath() + " no baú da Warehouse.");
                    return true;
                }
            }
        }

        // 2. Check Storager's inventory (only inside Warehouse)
        UUID storagerId = data.storager(nationId);
        if (storagerId != null && level.getEntity(storagerId) instanceof CitizenEntity storager
            && storager != citizen && nationId.equals(storager.getNationId())) {
            for (ToolRecipe recipe : recipes) {
                int slot = findItem(storager.getNpcInventory(), recipe.tool());
                if (slot < 0) continue;
                if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                    CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.95);
                    return true;
                }
                if (citizen.distanceToSqr(storager) > 16.0) {
                    if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(storager, 0.95);
                    return true;
                }
                ItemStack tool = storager.getNpcInventory().getItem(slot).split(1);
                if (storager.getNpcInventory().getItem(slot).isEmpty()) {
                    storager.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                }
                storager.getNpcInventory().setChanged();
                if (citizen.getMainHandItem().isEmpty()) {
                    citizen.setItemSlot(EquipmentSlot.MAINHAND, tool);
                } else {
                    ItemStack rem = citizen.getNpcInventory().addItem(tool);
                    if (!rem.isEmpty()) storager.getNpcInventory().addItem(rem);
                }
                citizen.getNpcInventory().setChanged();
                fulfillMatchingToolRequests(data, citizen, recipes);
                citizen.announce("Vim até a Warehouse e peguei " + BuiltInRegistries.ITEM.getKey(recipe.tool()).getPath() + " com o Storager.");
                return true;
            }
        }

        // 3. If a tool request is already pending in the queue, walk to the Warehouse and wait passively for the Storager!
        if (hasPendingToolRequest(data, citizen, recipes)) {
            if (warehouse != null && warehouse.dimension().equals(level.dimension().location())) {
                if (!isCitizenInsideWarehouse(citizen, warehouse)) {
                    if (citizen.getNavigation().isDone()) {
                        CitizenEntity.moveTowards(citizen, level, warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.95);
                    }
                } else {
                    citizen.getNavigation().stop();
                }
            }
            return true;
        }
        return false;
    }

    private static void fulfillMatchingToolRequests(CivilizationData data, CitizenEntity citizen, List<ToolRecipe> recipes) {
        for (CivilizationData.StorageRequest request : data.openStorageRequests(citizen.getNationId())) {
            if (!request.requesterId().equals(citizen.getUUID())) continue;
            if (request.itemId().equals(BEST_AXE_REQUEST_ID)
                && recipes.stream().anyMatch(recipe -> recipe.tool() == Items.WOODEN_AXE)) {
                data.fulfillStorageRequest(request.id());
            } else if (request.itemId().equals(BEST_SHOVEL_REQUEST_ID)
                && recipes.stream().anyMatch(recipe -> recipe.tool() == Items.WOODEN_SHOVEL)) {
                data.fulfillStorageRequest(request.id());
            } else if (request.itemId().equals(BEST_PICKAXE_REQUEST_ID)
                || request.itemId().startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_")) {
                if (recipes.stream().anyMatch(recipe -> pickaxeTier(recipe.tool()) >= 0)) {
                    data.fulfillStorageRequest(request.id());
                }
            }
        }
    }

    private boolean processRequests(CitizenEntity citizen, ServerLevel level, CivilizationData data,
                                    CivilizationData.Warehouse warehouse, List<BlockPos> chestPositions, BlockPos inboxPos) {
        ChestBlockEntity inbox = inboxPos != null && level.getBlockEntity(inboxPos) instanceof ChestBlockEntity chest ? chest : null;
        if (!chestPositions.isEmpty() && inbox == null) return false;
        List<CivilizationData.StorageRequest> requests = new ArrayList<>(data.openStorageRequests(citizen.getNationId()));
        requests.sort(Comparator.comparingInt(request -> isToolRequest(request.itemId()) ? 0 : 1));
        for (CivilizationData.StorageRequest request : requests) {
            if (request.itemId().startsWith(RESOURCE_REQUEST_PREFIX)) {
                if (processResourceRequest(citizen, data, warehouse, request,
                    request.itemId().substring(RESOURCE_REQUEST_PREFIX.length()),
                    request.count(), chestPositions, level)) {
                    return true;
                }
                continue;
            }
            List<ToolRecipe> recipes;
            String toolName;
            if (request.itemId().equals(BEST_AXE_REQUEST_ID)) {
                recipes = AXE_RECIPES;
                toolName = "machado";
            } else if (request.itemId().equals(BEST_PICKAXE_REQUEST_ID)
                || request.itemId().startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_")) {
                int minimumTier = request.itemId().equals(BEST_PICKAXE_REQUEST_ID) ? 0
                    : parseRequestedPickaxeTier(request.itemId());
                recipes = PICKAXE_RECIPES.stream().filter(candidate -> pickaxeTier(candidate.tool()) >= minimumTier).toList();
                toolName = "picareta";
            } else if (request.itemId().equals(BEST_SHOVEL_REQUEST_ID)) {
                recipes = SHOVEL_RECIPES;
                toolName = "pá";
            } else continue;
            ToolRecipe stocked = recipes.stream()
                .filter(candidate -> countItem(citizen.getNpcInventory(), chestPositions, level, candidate.tool()) > 0)
                .findFirst().orElse(null);
            if (stocked != null) {
                if (deliverRequestedTool(citizen, level, data, request, stocked, chestPositions)) return true;
                continue;
            }
            ToolRecipe recipe = findCraftableTool(citizen.getNpcInventory(), chestPositions, level, recipes);
            if (recipe == null && convertLogsToPlanks(citizen, chestPositions, level)) {
                recipe = findCraftableTool(citizen.getNpcInventory(), chestPositions, level, recipes);
            }
            if (recipe == null && takeLogsFromNearbyCitizen(citizen, level, warehouse, 2)
                && convertLogsToPlanks(citizen, chestPositions, level)) {
                recipe = findCraftableTool(citizen.getNpcInventory(), chestPositions, level, recipes);
            }
            if (recipe == null) {
                if (citizen.tickCount - this.lastMissingAxeMaterialsNotice >= 200) {
                    citizen.announce("Ainda faltam materiais para fabricar uma " + toolName + "; vou priorizar o melhor tier disponível.");
                    this.lastMissingAxeMaterialsNotice = citizen.tickCount;
                }
                continue;
            }
            if (!ensurePlanksForTool(citizen, level, warehouse, chestPositions, recipe)) continue;
            if (countItem(citizen.getNpcInventory(), chestPositions, level, Items.STICK) < 2
                && !convertPlanksToSticks(citizen, chestPositions, level)) continue;
            recipe = findCraftableTool(citizen.getNpcInventory(), chestPositions, level, recipes);
            if (recipe == null) continue;
            ItemStack toolStack = new ItemStack(recipe.tool());
            if (!citizen.getNpcInventory().canAddItem(toolStack)) continue;
            boolean consumedMaterial = recipe.usesPlanks()
                ? consumeTaggedItems(citizen.getNpcInventory(), chestPositions, level, ItemTags.PLANKS, recipe.materialCount())
                : consumeItem(citizen.getNpcInventory(), chestPositions, level, recipe.material(), recipe.materialCount());
            if (!consumedMaterial || !consumeItem(citizen.getNpcInventory(), chestPositions, level, Items.STICK, 2)) continue;
            ItemStack remainder = citizen.getNpcInventory().addItem(toolStack);
            if (!remainder.isEmpty()) continue;
            citizen.getNpcInventory().setChanged();
            citizen.announce("Fabriquei " + toolName + " e vou levar ao cidadão que pediu.");
            if (deliverRequestedTool(citizen, level, data, request, recipe, chestPositions)) return true;
        }
        return false;
    }

    private boolean deliverRequestedTool(CitizenEntity storager, ServerLevel level, CivilizationData data,
                                         CivilizationData.StorageRequest request, ToolRecipe recipe,
                                         List<BlockPos> chestPositions) {
        CivilizationData.Warehouse warehouse = data.warehouse(storager.getNationId());
        Entity targetEntity = level.getEntity(request.requesterId());
        if (!(targetEntity instanceof CitizenEntity requester) || requester == storager
            || !requester.getNationId().equals(storager.getNationId())) return false;

        // Storager NEVER leaves the Warehouse! He only delivers if the requester is inside the Warehouse.
        if (!isCitizenInsideWarehouse(requester, warehouse)) {
            return false;
        }

        if (storager.distanceToSqr(requester) > 16.0) {
            if (findItem(storager.getNpcInventory(), recipe.tool()) < 0) {
                if (!storager.getNpcInventory().canAddItem(new ItemStack(recipe.tool()))) return false;
                for (BlockPos pos : chestPositions) {
                    if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
                    int slot = findItem(chest, recipe.tool());
                    if (slot < 0) continue;
                    ItemStack tool = chest.getItem(slot).split(1);
                    if (chest.getItem(slot).isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
                    chest.setChanged();
                    ItemStack remainder = storager.getNpcInventory().addItem(tool);
                    if (!remainder.isEmpty()) insert(chest, remainder);
                    storager.getNpcInventory().setChanged();
                    break;
                }
            }
            if (findItem(storager.getNpcInventory(), recipe.tool()) < 0) return false;
            if (storager.getNavigation().isDone()) storager.getNavigation().moveTo(requester, 0.9);
            return true;
        }

        ItemStack delivered = new ItemStack(recipe.tool());
        boolean putInInventory = requester.getNpcInventory().canAddItem(delivered);
        boolean putInHand = requester.getMainHandItem().isEmpty();
        if (!putInInventory && !putInHand) return false;

        ItemStack removed = takeOneTool(storager, level, chestPositions, recipe.tool());
        if (removed.isEmpty()) return false;
        if (putInInventory) requester.getNpcInventory().addItem(removed);
        else requester.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, removed);
        requester.getNpcInventory().setChanged();
        data.fulfillStorageRequest(request.id());
        storager.announce("Entreguei " + BuiltInRegistries.ITEM.getKey(recipe.tool()).getPath()
            + " para " + requester.getPersonalName() + " aqui na Warehouse.");
        requester.announce("Recebi a ferramenta que pedi ao Storager.");
        return true;
    }

    private static ItemStack takeOneTool(CitizenEntity storager, ServerLevel level,
                                         List<BlockPos> chestPositions, Item toolItem) {
        int inventorySlot = findItem(storager.getNpcInventory(), toolItem);
        if (inventorySlot >= 0) {
            ItemStack stack = storager.getNpcInventory().getItem(inventorySlot).split(1);
            if (storager.getNpcInventory().getItem(inventorySlot).isEmpty())
                storager.getNpcInventory().setItem(inventorySlot, ItemStack.EMPTY);
            storager.getNpcInventory().setChanged();
            return stack;
        }
        for (BlockPos pos : chestPositions) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            int slot = findItem(chest, toolItem);
            if (slot < 0) continue;
            ItemStack stack = chest.getItem(slot).split(1);
            if (chest.getItem(slot).isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
            chest.setChanged();
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private static boolean hasPendingToolRequest(CivilizationData data, CitizenEntity requester,
                                                 List<ToolRecipe> recipes) {
        for (CivilizationData.StorageRequest request : data.openStorageRequests(requester.getNationId())) {
            if (!request.requesterId().equals(requester.getUUID())) continue;
            if (request.itemId().equals(BEST_AXE_REQUEST_ID)
                && recipes.stream().anyMatch(recipe -> recipe.tool() == Items.WOODEN_AXE)) return true;
            if (request.itemId().equals(BEST_SHOVEL_REQUEST_ID)
                && recipes.stream().anyMatch(recipe -> recipe.tool() == Items.WOODEN_SHOVEL)) return true;
            if (request.itemId().equals(BEST_PICKAXE_REQUEST_ID)
                || request.itemId().startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_")) {
                int minimumTier;
                if (request.itemId().startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_")) {
                    try {
                        minimumTier = Integer.parseInt(request.itemId()
                            .substring((BEST_PICKAXE_REQUEST_ID + ".tier_").length()));
                    } catch (NumberFormatException ignored) {
                        minimumTier = 0;
                    }
                } else minimumTier = 0;
                int requestedTier = minimumTier;
                if (recipes.stream().anyMatch(recipe -> pickaxeTier(recipe.tool()) >= requestedTier)) return true;
            }
        }
        return false;
    }

    private boolean isToolRequest(String itemId) {
        return itemId.equals(BEST_AXE_REQUEST_ID) || itemId.equals(BEST_PICKAXE_REQUEST_ID)
            || itemId.startsWith(BEST_PICKAXE_REQUEST_ID + ".tier_") || itemId.equals(BEST_SHOVEL_REQUEST_ID);
    }

    private ToolRecipe findCraftableTool(Container inventory, List<BlockPos> chestPositions, ServerLevel level,
                                         List<ToolRecipe> recipes) {
        for (ToolRecipe recipe : recipes) {
            int materialCount = recipe.usesPlanks()
                ? countTaggedItems(inventory, chestPositions, level, ItemTags.PLANKS)
                : countItem(inventory, chestPositions, level, recipe.material());
            if (materialCount >= recipe.materialCount()) return recipe;
        }
        return null;
    }

    private boolean ensurePlanksForTool(CitizenEntity citizen, ServerLevel level,
                                        CivilizationData.Warehouse warehouse, List<BlockPos> chests,
                                        ToolRecipe recipe) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int requiredPlanks = (recipe.usesPlanks() ? recipe.materialCount() : 0)
                + (countItem(citizen.getNpcInventory(), chests, level, Items.STICK) < 2 ? 2 : 0);
            if (countTaggedItems(citizen.getNpcInventory(), chests, level, ItemTags.PLANKS) >= requiredPlanks) return true;
            if (convertLogsToPlanks(citizen, chests, level)) continue;
            if (takeLogsFromNearbyCitizen(citizen, level, warehouse, 2)) continue;
            return false;
        }
        int requiredPlanks = (recipe.usesPlanks() ? recipe.materialCount() : 0)
            + (countItem(citizen.getNpcInventory(), chests, level, Items.STICK) < 2 ? 2 : 0);
        return countTaggedItems(citizen.getNpcInventory(), chests, level, ItemTags.PLANKS) >= requiredPlanks;
    }

    private boolean processResourceRequest(CitizenEntity storager, CivilizationData data,
                                           CivilizationData.Warehouse warehouse,
                                           CivilizationData.StorageRequest request, String itemId, int count,
                                           List<BlockPos> chests, ServerLevel level) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return false;
        Item item = BuiltInRegistries.ITEM.getValue(key);
        if (item == Items.AIR || count <= 0) return false;
        boolean isPlankRequest = new ItemStack(item).is(ItemTags.PLANKS);
        boolean isStairRequest = new ItemStack(item).is(ItemTags.STAIRS);
        if (countItem(storager.getNpcInventory(), chests, level, item) < count) {
            if (isPlankRequest) {
                for (int attempt = 0; attempt < 16 && countTaggedItems(storager.getNpcInventory(), chests, level, ItemTags.PLANKS) < count; attempt++) {
                    if (convertLogsToRequestedPlanks(storager, chests, level, item)
                        || convertLogsToPlanks(storager, chests, level)) continue;
                    int logsNeeded = Math.max(2, (count - countTaggedItems(storager.getNpcInventory(), chests, level, ItemTags.PLANKS) + 3) / 4);
                    if (takeLogsFromNearbyCitizen(storager, level, warehouse, Math.min(8, logsNeeded))) continue;
                    break;
                }
            } else if (isStairRequest) {
                if (countTaggedItems(storager.getNpcInventory(), chests, level, ItemTags.PLANKS) < 6
                    && countTaggedItems(storager.getNpcInventory(), chests, level, ItemTags.LOGS) < 2) {
                    takeLogsFromNearbyCitizen(storager, level, warehouse, 2);
                }
                craftRequestedStairs(storager, chests, level, item, count);
            }
        }
        Item itemToProvide = item;
        int availableCount = countItem(storager.getNpcInventory(), chests, level, itemToProvide);
        if (availableCount <= 0 && isPlankRequest) {
            // Use ANY available plank species in inventory or chests
            Item anyPlank = findAnyAvailableItemByTag(storager.getNpcInventory(), chests, level, ItemTags.PLANKS);
            if (anyPlank != null) {
                itemToProvide = anyPlank;
                availableCount = countItem(storager.getNpcInventory(), chests, level, itemToProvide);
            }
        } else if (availableCount <= 0 && isStairRequest) {
            Item anyStair = findAnyAvailableItemByTag(storager.getNpcInventory(), chests, level, ItemTags.STAIRS);
            if (anyStair != null) {
                itemToProvide = anyStair;
                availableCount = countItem(storager.getNpcInventory(), chests, level, itemToProvide);
            }
        }
        int transferAmount = Math.min(count, availableCount);
        if (transferAmount <= 0) return false;

        // Ensure the Storager is holding the items in his inventory for delivery or pickup
        int inStoragerInv = countItem(storager.getNpcInventory(), itemToProvide);
        if (inStoragerInv < transferAmount) {
            int toPullFromChests = transferAmount - inStoragerInv;
            if (storager.getNpcInventory().canAddItem(new ItemStack(itemToProvide, toPullFromChests))
                && consumeItem(storager.getNpcInventory(), chests, level, itemToProvide, transferAmount)) {
                storager.getNpcInventory().addItem(new ItemStack(itemToProvide, transferAmount));
                storager.getNpcInventory().setChanged();
            }
        }

        // Only deliver directly if the requester is ALREADY inside the Warehouse; otherwise leave in Warehouse chest/inventory for pickup!
        Entity targetEntity = level.getEntity(request.requesterId());
        if (targetEntity instanceof CitizenEntity requester && requester != storager
            && requester.getNationId().equals(storager.getNationId())
            && isCitizenInsideWarehouse(requester, warehouse)) {
            int held = Math.min(count, countItem(storager.getNpcInventory(), itemToProvide));
            if (held > 0) {
                if (storager.distanceToSqr(requester) > 16.0) {
                    if (storager.getNavigation().isDone()) storager.getNavigation().moveTo(requester, 0.95);
                    return true;
                }
                if (requester.getNpcInventory().canAddItem(new ItemStack(itemToProvide, held))
                    && removeItem(storager.getNpcInventory(), itemToProvide, held) == 0) {
                    requester.getNpcInventory().addItem(new ItemStack(itemToProvide, held));
                    storager.getNpcInventory().setChanged();
                    requester.getNpcInventory().setChanged();
                    data.fulfillStorageRequest(request.id());
                    storager.announce("Entreguei " + held + " " + BuiltInRegistries.ITEM.getKey(itemToProvide).getPath()
                        + " para " + requester.getPersonalName() + " aqui na Warehouse.");
                    return true;
                }
            }
        }
        return false;
    }

    private boolean convertLogsToRequestedPlanks(CitizenEntity citizen, List<BlockPos> chests, ServerLevel level, Item planks) {
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (!stack.is(ItemTags.LOGS) || planksForLog(stack.getItem()) != planks) continue;
            if (!citizen.getNpcInventory().canAddItem(new ItemStack(planks, 4))) return false;
            stack.shrink(1);
            if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
            citizen.getNpcInventory().addItem(new ItemStack(planks, 4));
            citizen.getNpcInventory().setChanged();
            return true;
        }
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!stack.is(ItemTags.LOGS) || planksForLog(stack.getItem()) != planks) continue;
                if (!citizen.getNpcInventory().canAddItem(new ItemStack(planks, 4))) return false;
                stack.shrink(1);
                if (stack.isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
                chest.setChanged();
                citizen.getNpcInventory().addItem(new ItemStack(planks, 4));
                citizen.getNpcInventory().setChanged();
                return true;
            }
        }
        return false;
    }

    private void craftRequestedStairs(CitizenEntity citizen, List<BlockPos> chests, ServerLevel level, Item stairs, int targetCount) {
        int crafts = 0;
        while (countTaggedItems(citizen.getNpcInventory(), chests, level, ItemTags.STAIRS) < targetCount && crafts++ < 8) {
            Item planks = findPlanksWithAtLeast(citizen.getNpcInventory(), chests, level, 6);
            if (planks == null && convertLogsToPlanks(citizen, chests, level)) {
                planks = findPlanksWithAtLeast(citizen.getNpcInventory(), chests, level, 6);
            }
            if (planks == null && convertLogsToPlanks(citizen, chests, level)) {
                planks = findPlanksWithAtLeast(citizen.getNpcInventory(), chests, level, 6);
            }
            if (planks == null) return;
            Item targetStair = stairsForPlanks(planks);
            if (targetStair == Items.AIR) targetStair = stairs;
            if (!citizen.getNpcInventory().canAddItem(new ItemStack(targetStair, 4))) return;
            if (!consumeItem(citizen.getNpcInventory(), chests, level, planks, 6)) return;
            citizen.getNpcInventory().addItem(new ItemStack(targetStair, 4));
            citizen.getNpcInventory().setChanged();
        }
    }

    private static Item findAnyAvailableItemByTag(Container inventory, List<BlockPos> chests, ServerLevel level, TagKey<Item> tag) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.is(tag)) return s.getItem();
        }
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (s.is(tag)) return s.getItem();
            }
        }
        return null;
    }

    private static Item findPlanksWithAtLeast(Container inventory, List<BlockPos> chests, ServerLevel level, int minCount) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.is(ItemTags.PLANKS) && countItem(inventory, chests, level, s.getItem()) >= minCount) return s.getItem();
        }
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (s.is(ItemTags.PLANKS) && countItem(inventory, chests, level, s.getItem()) >= minCount) return s.getItem();
            }
        }
        return null;
    }

    private static Item stairsForPlanks(Item planks) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(planks);
        String path = key.getPath();
        if (!path.endsWith("_planks")) return Items.OAK_STAIRS;
        Item stair = BuiltInRegistries.ITEM.getValue(ResourceLocation.fromNamespaceAndPath(
            key.getNamespace(), path.substring(0, path.length() - "_planks".length()) + "_stairs"));
        return stair == Items.AIR ? Items.OAK_STAIRS : stair;
    }

    private int parseRequestedPickaxeTier(String requestId) {
        String prefix = BEST_PICKAXE_REQUEST_ID + ".tier_";
        try {
            return Math.max(0, Math.min(4, Integer.parseInt(requestId.substring(prefix.length()))));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private boolean convertPlanksToSticks(CitizenEntity citizen, List<BlockPos> chestPositions, ServerLevel level) {
        if (countTaggedItems(citizen.getNpcInventory(), chestPositions, level, ItemTags.PLANKS) < 2) return false;
        ItemStack sticks = new ItemStack(Items.STICK, 4);
        boolean hasDestination = canFit(citizen.getNpcInventory(), sticks)
            || chestPositions.stream().map(level::getBlockEntity).filter(ChestBlockEntity.class::isInstance)
                .map(ChestBlockEntity.class::cast).anyMatch(chest -> canFit(chest, sticks));
        if (!hasDestination || !consumeTaggedItems(citizen.getNpcInventory(), chestPositions, level, ItemTags.PLANKS, 2)) return false;
        ItemStack remainder = insert(citizen.getNpcInventory(), sticks);
        for (BlockPos pos : chestPositions) {
            if (remainder.isEmpty()) break;
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) remainder = insert(chest, remainder);
        }
        return remainder.isEmpty();
    }

    private boolean convertLogsToPlanks(CitizenEntity citizen, List<BlockPos> chestPositions, ServerLevel level) {
        Item log = findConvertibleLog(citizen.getNpcInventory(), chestPositions, level);
        if (log == null) return false;
        Item planksItem = planksForLog(log);
        if (planksItem == Items.AIR) return false;
        int available = countItem(citizen.getNpcInventory(), chestPositions, level, log);
        if (available < 1) return false;
        if (!consumeItem(citizen.getNpcInventory(), chestPositions, level, log, 1)) return false;
        ItemStack planks = new ItemStack(planksItem, 4);
        ItemStack remainder = insert(citizen.getNpcInventory(), planks);
        if (!remainder.isEmpty()) {
            for (BlockPos pos : chestPositions) {
                if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
                remainder = insert(chest, remainder);
                if (remainder.isEmpty()) break;
            }
        }
        if (!remainder.isEmpty()) {
            insert(citizen.getNpcInventory(), new ItemStack(log, 1));
            return false;
        }
        return true;
    }

    private void sortInbox(ChestBlockEntity inbox, ServerLevel level, CitizenEntity citizen,
                           CivilizationData.Warehouse warehouse, List<BlockPos> chests) {
        for (int slot = 0; slot < inbox.getContainerSize(); slot++) {
            ItemStack stack = inbox.getItem(slot);
            if (stack.isEmpty()) continue;
            ChestBlockEntity destination = null;
            for (BlockPos pos : chests) {
                if (pos.equals(warehouse.inbox()) || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
                if (canAccept(chest, stack)) { destination = chest; break; }
            }
            if (destination == null) {
                if (buildChest(citizen, level, warehouse, chests, false)) return;
                continue;
            }
            ItemStack remainder = insert(destination, stack.copy());
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                inbox.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                destination.setChanged();
                inbox.setChanged();
            }
        }
    }

    private void decorateWarehouse(CitizenEntity citizen, ServerLevel level,
                                  CivilizationData.Warehouse warehouse, List<BlockPos> chests) {
        BlockPos center = warehouse.center();
        int targetFloorY = warehouse.y() - 1;
        int placed = 0;
        // Phase 1: Ensure all 10x10 floor blocks (x in -5..4, z in -5..4) are wood planks
        while (!decoratingFence && placed < 4) {
            if (decorationIndex >= 100) {
                decoratingFence = true;
                decorationIndex = 0;
                break;
            }
            int x = center.getX() - 5 + (decorationIndex % 10);
            int z = center.getZ() - 5 + (decorationIndex / 10);
            BlockPos floor = new BlockPos(x, targetFloorY, z);
            if (!level.hasChunkAt(floor)) {
                decorationIndex++;
                continue;
            }
            BlockState floorState = level.getBlockState(floor);
            if (floorState.is(BlockTags.PLANKS) || floorState.hasBlockEntity()) {
                decorationIndex++;
                continue;
            }
            Item plank = removeOnePlank(citizen.getNpcInventory(), chests, level);
            if (plank == null && convertLogsToPlanks(citizen, chests, level)) {
                plank = removeOnePlank(citizen.getNpcInventory(), chests, level);
            }
            if (plank == null) return;
            Block plankBlock = Block.byItem(plank);
            if (plankBlock == Blocks.AIR || !level.setBlockAndUpdate(floor, plankBlock.defaultBlockState())) {
                insert(citizen.getNpcInventory(), new ItemStack(plank));
            } else {
                placed++;
            }
            decorationIndex++;
        }
    }

    private boolean isNaturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK);
    }

    private BlockPos perimeterPosition(BlockPos center, int radius, int index) {
        int sideLength = radius * 2;
        if (index < sideLength) return center.offset(-radius + index, 0, -radius);
        index -= sideLength;
        if (index < sideLength) return center.offset(radius, 0, -radius + index);
        index -= sideLength;
        if (index < sideLength) return center.offset(radius - index, 0, radius);
        index -= sideLength;
        return center.offset(-radius, 0, radius - index);
    }

    private Item removeOnePlank(Container inventory, List<BlockPos> chests, ServerLevel level) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(ItemTags.PLANKS)) continue;
            Item item = stack.getItem();
            stack.shrink(1);
            if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            inventory.setChanged();
            return item;
        }
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (!stack.is(ItemTags.PLANKS)) continue;
                Item item = stack.getItem();
                stack.shrink(1);
                if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
                chest.setChanged();
                return item;
            }
        }
        return null;
    }

    private Item removeOneFence(CitizenEntity citizen, List<BlockPos> chests, ServerLevel level) {
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_fence")) continue;
                Item fence = stack.getItem();
                stack.shrink(1);
                if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
                chest.setChanged();
                return fence;
            }
        }
        Item planks = findFenceMaterial(citizen.getNpcInventory(), chests, level);
        if (planks == null) return null;
        Item fence = fenceForPlanks(planks);
        if (fence == Items.AIR) return null;
        if (countItem(citizen.getNpcInventory(), chests, level, Items.STICK) < 2) {
            if (countItem(citizen.getNpcInventory(), chests, level, planks) < 6
                && !convertLogsToPlanks(citizen, chests, level)) return null;
            if (!convertPlanksToSticks(citizen, chests, level)) return null;
        }
        ItemStack fences = new ItemStack(fence, 3);
        boolean hasSpace = canFit(citizen.getNpcInventory(), fences)
            || chests.stream().map(level::getBlockEntity).filter(ChestBlockEntity.class::isInstance)
                .map(ChestBlockEntity.class::cast).anyMatch(chest -> canAccept(chest, fences));
        if (!hasSpace || countItem(citizen.getNpcInventory(), chests, level, planks) < 4
            || !consumeItem(citizen.getNpcInventory(), chests, level, planks, 4)
            || !consumeItem(citizen.getNpcInventory(), chests, level, Items.STICK, 2)) return null;
        ItemStack remainder = insert(citizen.getNpcInventory(), fences);
        for (BlockPos pos : chests) {
            if (remainder.isEmpty()) break;
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) remainder = insert(chest, remainder);
        }
        if (!remainder.isEmpty()) return null;
        ItemStack removed = citizen.getNpcInventory().removeItemType(fence, 1);
        if (!removed.isEmpty()) return removed.getItem();
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            if (removeItem(chest, fence, 1) > 0) return fence;
        }
        return null;
    }

    private Item findFenceMaterial(Container inventory, List<BlockPos> chests, ServerLevel level) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(ItemTags.PLANKS) && countItem(inventory, chests, level, stack.getItem()) >= 4
                && fenceForPlanks(stack.getItem()) != Items.AIR) return stack.getItem();
        }
        for (BlockPos pos : chests) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (stack.is(ItemTags.PLANKS) && countItem(inventory, chests, level, stack.getItem()) >= 4
                    && fenceForPlanks(stack.getItem()) != Items.AIR) return stack.getItem();
            }
        }
        return null;
    }

    private Item fenceForPlanks(Item planks) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(planks);
        String path = key.getPath();
        if (!path.endsWith("_planks")) return Items.AIR;
        Item fence = BuiltInRegistries.ITEM.getValue(ResourceLocation.fromNamespaceAndPath(
            key.getNamespace(), path.substring(0, path.length() - "_planks".length()) + "_fence"));
        return Block.byItem(fence) == Blocks.AIR ? Items.AIR : fence;
    }

    private boolean buildChest(CitizenEntity citizen, ServerLevel level, CivilizationData.Warehouse warehouse,
                               List<BlockPos> existingChests, boolean firstChest) {
        BlockPos place = findChestPlacement(citizen, level, warehouse);
        if (place == null) {
            if (citizen.tickCount % 200 == 0) citizen.announce("Não encontrei espaço livre dentro da área do armazém para outro baú.");
            return false;
        }
        boolean paidByOwnPlanks = consumeInventoryTagged(citizen.getNpcInventory(), ItemTags.PLANKS, 8);
        if (!paidByOwnPlanks && !existingChests.isEmpty()) {
            paidByOwnPlanks = consumeTaggedItems(citizen.getNpcInventory(), existingChests, level, ItemTags.PLANKS, 8);
        }
        boolean paidByLogs = false;
        if (!paidByOwnPlanks) {
            paidByLogs = consumeInventoryTagged(citizen.getNpcInventory(), ItemTags.LOGS, 2);
            if (!paidByLogs && !existingChests.isEmpty()) {
                paidByLogs = consumeTaggedItems(citizen.getNpcInventory(), existingChests, level, ItemTags.LOGS, 2);
            }
        }
        if (!paidByOwnPlanks && !paidByLogs) {
            if (takeLogsFromNearbyCitizen(citizen, level, warehouse, 2)) {
                paidByLogs = consumeInventoryTagged(citizen.getNpcInventory(), ItemTags.LOGS, 2);
            }
        }
        if (!paidByOwnPlanks && !paidByLogs) {
            if (citizen.tickCount - lastMissingPlanksNotice > 600) {
                citizen.announce("Ainda não tenho material para construir o baú; vou buscar troncos com os cidadãos próximos.");
                lastMissingPlanksNotice = citizen.tickCount;
            }
            return false;
        }

        BlockState chestState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        if (!level.setBlockAndUpdate(place, chestState)) return false;
        if (firstChest) {
            CivilizationData.get(level).setWarehouseInbox(citizen.getNationId(), place);
            citizen.announce("Construí o baú de entrada do armazém.");
        } else {
            citizen.announce("Construí um baú de armazenamento para organizar os recursos.");
        }
        return true;
    }

    private boolean takeLogsFromNearbyCitizen(CitizenEntity storager, ServerLevel level,
                                               CivilizationData.Warehouse warehouse, int needed) {
        BlockPos center = warehouse.center();
        // Storager stays strictly inside the Warehouse! He only takes logs from citizens who are inside the Warehouse.
        int halfSize = Math.max(6, warehouse.radius() / 2);
        AABB area = new AABB(center.getX() - halfSize, center.getY() - 6.0, center.getZ() - halfSize,
            center.getX() + halfSize + 1.0, center.getY() + 6.0, center.getZ() + halfSize + 1.0);
        List<CitizenEntity> nearbyCitizens = level.getEntitiesOfClass(CitizenEntity.class, area, other ->
            other != storager && storager.getNationId().equals(other.getNationId()) && isCitizenInsideWarehouse(other, warehouse));
        nearbyCitizens.sort(Comparator.comparingDouble(storager::distanceToSqr));
        CitizenEntity source = null;
        for (CitizenEntity candidate : nearbyCitizens) {
            if (countLogs(candidate.getNpcInventory()) > 0) {
                source = candidate;
                break;
            }
        }
        if (source == null) {
            resourceDonorId = null;
            return false;
        }
        if (storager.distanceToSqr(source) > 16.0) {
            if (storager.getNavigation().isDone()) storager.getNavigation().moveTo(source, 0.9);
            return false;
        }
        int amount = 0;
        for (int slot = 0; slot < source.getNpcInventory().getContainerSize() && amount < needed; slot++) {
            ItemStack stack = source.getNpcInventory().getItem(slot);
            if (!stack.is(ItemTags.LOGS)) continue;
            int take = Math.min(needed - amount, stack.getCount());
            ItemStack split = stack.split(take);
            if (stack.isEmpty()) source.getNpcInventory().setItem(slot, ItemStack.EMPTY);
            ItemStack remainder = storager.getNpcInventory().addItem(split);
            int actualTaken = take - remainder.getCount();
            if (!remainder.isEmpty()) source.getNpcInventory().addItem(remainder);
            amount += actualTaken;
        }
        if (amount <= 0) {
            resourceDonorId = null;
            return false;
        }
        source.getNpcInventory().setChanged();
        storager.getNpcInventory().setChanged();
        resourceDonorId = null;
        return true;
    }

    private BlockPos findChestPlacement(CitizenEntity citizen, ServerLevel level,
                                        CivilizationData.Warehouse warehouse) {
        BlockPos center = warehouse.center();
        GridBlueprint blueprint = warehouseBlueprint(level);
        for (int row = 0; row < blueprint.depth(); row++) {
            for (int column = 0; column < blueprint.width(); column++) {
                if (blueprint.cellAt(column, row) != 'A') continue;
                int x = center.getX() + column - blueprint.width() / 2;
                int z = center.getZ() + row - blueprint.depth() / 2;
                BlockPos pos = new BlockPos(x, warehouse.y(), z);
                if (!level.hasChunkAt(pos) || !citizen.isInsideNation(pos)) continue;
                BlockState atPos = level.getBlockState(pos);
                if (!atPos.isAir() && com.aicivilization.profession.BuilderJob.isClearableVegetation(atPos)) {
                    level.destroyBlock(pos, true, citizen);
                    atPos = level.getBlockState(pos);
                }
                if (!atPos.isAir()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
                if (!level.getEntitiesOfClass(CitizenEntity.class, new AABB(pos)).isEmpty()) continue;
                return pos;
            }
        }
        return null;
    }

    private static GridBlueprint warehouseBlueprint(ServerLevel level) {
        if (WAREHOUSE_BLUEPRINT == null) WAREHOUSE_BLUEPRINT = GridBlueprint.load(level, "warehouse_10x10");
        return WAREHOUSE_BLUEPRINT;
    }

    private static boolean hasBlueprintChest(ServerLevel level, CivilizationData.Warehouse warehouse) {
        BlockPos center = warehouse.center();
        GridBlueprint blueprint = warehouseBlueprint(level);
        for (int row = 0; row < blueprint.depth(); row++) {
            for (int column = 0; column < blueprint.width(); column++) {
                if (blueprint.cellAt(column, row) != 'A') continue;
                BlockPos pos = new BlockPos(center.getX() + column - blueprint.width() / 2,
                    warehouse.y(), center.getZ() + row - blueprint.depth() / 2);
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity) return true;
            }
        }
        return false;
    }

    private void depositExcessStoragerInventory(CitizenEntity storager, ServerLevel level, List<BlockPos> chests) {
        if (chests.isEmpty()) return;
        boolean movedAny = false;
        for (int slot = 0; slot < storager.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = storager.getNpcInventory().getItem(slot);
            if (stack.isEmpty() || stack.is(ItemTags.AXES)) continue;
            for (BlockPos chestPos : chests) {
                if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) continue;
                ItemStack remainder = insert(chest, stack.copy());
                int moved = stack.getCount() - remainder.getCount();
                if (moved > 0) {
                    stack.shrink(moved);
                    if (stack.isEmpty()) storager.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                    chest.setChanged();
                    movedAny = true;
                }
                if (stack.isEmpty()) break;
            }
        }
        if (movedAny) {
            storager.getNpcInventory().setChanged();
        }
    }

    public static boolean depositAllResources(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Warehouse warehouse = data.warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return false;
        List<BlockPos> chests = findChests(level, warehouse);

        boolean depositedAny = false;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.TORCH)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS)) continue;

            // 1. Try inserting into any Warehouse chest (including partial fits)
            for (BlockPos chestPos : chests) {
                if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                    ItemStack remainder = insert(chest, stack.copy());
                    int moved = stack.getCount() - remainder.getCount();
                    if (moved > 0) {
                        stack.shrink(moved);
                        if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                        chest.setChanged();
                        depositedAny = true;
                    }
                    if (stack.isEmpty()) break;
                }
            }

            // 2. Fallback: if chests are not built yet or full, hand resources to the Storager inside the Warehouse!
            if (!stack.isEmpty()) {
                UUID storagerId = data.storager(citizen.getNationId());
                if (storagerId != null && level.getEntity(storagerId) instanceof CitizenEntity storager
                    && storager != citizen && isCitizenInsideWarehouse(storager, warehouse)) {
                    ItemStack remainder = insert(storager.getNpcInventory(), stack.copy());
                    int moved = stack.getCount() - remainder.getCount();
                    if (moved > 0) {
                        stack.shrink(moved);
                        if (stack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
                        storager.getNpcInventory().setChanged();
                        depositedAny = true;
                    }
                }
            }
        }
        if (depositedAny) {
            citizen.getNpcInventory().setChanged();
        }
        return depositedAny;
    }

    public static List<BlockPos> findChests(ServerLevel level, CivilizationData.Warehouse warehouse) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = warehouse.center();
        GridBlueprint blueprint = warehouseBlueprint(level);
        for (int row = 0; row < blueprint.depth(); row++) {
            for (int column = 0; column < blueprint.width(); column++) {
                if (blueprint.cellAt(column, row) != 'A') continue;
                BlockPos pos = new BlockPos(center.getX() + column - blueprint.width() / 2,
                    warehouse.y(), center.getZ() + row - blueprint.depth() / 2);
                addChestIfPresent(level, result, pos);
            }
        }
        if (warehouse.hasInbox()) addChestIfPresent(level, result, warehouse.inbox());

        // Keep access to chests from the older warehouse grid while excluding
        // unrelated player chests in the surrounding area.
        int radius = warehouse.radius();
        int firstDx = -radius + 2;
        int firstDz = -radius + 2;
        for (int dz = firstDz; dz <= radius - 2; dz += 4) {
            for (int dx = firstDx; dx <= radius - 2; dx += 2) {
                long distanceSquared = (long) dx * dx + (long) dz * dz;
                if (distanceSquared < 4 || distanceSquared > (long) radius * radius) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int y = Math.max(level.getMinY(), center.getY() - 6);
                     y <= Math.min(level.getMaxY(), center.getY() + 10); y++) {
                    addChestIfPresent(level, result, new BlockPos(x, y, z));
                }
            }
        }
        result.sort(Comparator.comparingDouble(center::distSqr));
        return result;
    }

    private static void addChestIfPresent(ServerLevel level, List<BlockPos> chests, BlockPos pos) {
        if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity && !chests.contains(pos)) {
            chests.add(pos);
        }
    }

    private boolean hasPendingSort(ChestBlockEntity inbox) {
        for (int i = 0; i < inbox.getContainerSize(); i++) if (!inbox.getItem(i).isEmpty()) return true;
        return false;
    }

    private boolean canAccept(Container container, ItemStack stack) {
        int capacity = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack current = container.getItem(i);
            if (!current.isEmpty() && !current.is(stack.getItem())) return false;
            capacity += current.isEmpty() ? stack.getMaxStackSize() : current.getMaxStackSize() - current.getCount();
            if (capacity >= stack.getCount()) return true;
        }
        return capacity >= stack.getCount();
    }

    public static boolean canFit(Container container, ItemStack incoming) {
        int space = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack current = container.getItem(i);
            if (current.isEmpty()) space += incoming.getMaxStackSize();
            else if (current.is(incoming.getItem())) space += current.getMaxStackSize() - current.getCount();
            if (space >= incoming.getCount()) return true;
        }
        return false;
    }

    public static ItemStack insert(Container container, ItemStack incoming) {
        ItemStack remaining = incoming.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack current = container.getItem(i);
            if (!current.isEmpty() && !current.is(remaining.getItem())) continue;
            int currentCount = current.isEmpty() ? 0 : current.getCount();
            int limit = current.isEmpty() ? remaining.getMaxStackSize() : current.getMaxStackSize();
            int moved = Math.min(remaining.getCount(), limit - currentCount);
            if (moved <= 0) continue;
            if (current.isEmpty()) container.setItem(i, new ItemStack(remaining.getItem(), moved));
            else current.grow(moved);
            remaining.shrink(moved);
        }
        container.setChanged();
        return remaining;
    }

    private static int findItem(Container container, Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) return i;
        return -1;
    }

    private static int countItem(Container container, Item item) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countLogs(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private static Item firstLogItem(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(ItemTags.LOGS)) return stack.getItem();
        }
        return null;
    }

    private static int countItem(Container inventory, List<BlockPos> chestPositions, ServerLevel level, Item item) {
        int count = countItem(inventory, item);
        for (BlockPos pos : chestPositions) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest != inventory) count += countItem(chest, item);
        }
        return count;
    }

    private static boolean consumeInventoryItem(Container inventory, Item item, int count) {
        if (countItem(inventory, item) < count) return false;
        removeItem(inventory, item, count);
        inventory.setChanged();
        return true;
    }

    private static boolean consumeItem(Container inventory, List<BlockPos> chestPositions, ServerLevel level, Item item, int count) {
        if (countItem(inventory, chestPositions, level, item) < count) return false;
        int remaining = removeItem(inventory, item, count);
        for (BlockPos pos : chestPositions) {
            if (remaining <= 0) break;
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) remaining = removeItem(chest, item, remaining);
        }
        inventory.setChanged();
        return remaining == 0;
    }

    private static int removeItem(Container container, Item item, int count) {
        int remaining = count;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        return remaining;
    }

    private static Item planksForLog(Item log) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(log);
        String path = key.getPath();
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        if (path.endsWith("_log")) path = path.substring(0, path.length() - "_log".length());
        else if (path.endsWith("_wood")) path = path.substring(0, path.length() - "_wood".length());
        else if (path.endsWith("_stem")) path = path.substring(0, path.length() - "_stem".length());
        else if (path.endsWith("_hyphae")) path = path.substring(0, path.length() - "_hyphae".length());
        Item planks = BuiltInRegistries.ITEM.getValue(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), path + "_planks"));
        return planks;
    }

    private static int countTaggedItems(Container container, TagKey<Item> tag) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(tag)) count += stack.getCount();
        }
        return count;
    }

    private static int countTaggedItems(Container inventory, List<BlockPos> chestPositions, ServerLevel level, TagKey<Item> tag) {
        int count = countTaggedItems(inventory, tag);
        for (BlockPos pos : chestPositions) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest != inventory) count += countTaggedItems(chest, tag);
        }
        return count;
    }

    private static Item findConvertibleLog(Container inventory, List<BlockPos> chestPositions, ServerLevel level) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(ItemTags.LOGS) && countItem(inventory, chestPositions, level, stack.getItem()) >= 1
                && planksForLog(stack.getItem()) != Items.AIR) return stack.getItem();
        }
        for (BlockPos pos : chestPositions) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (stack.is(ItemTags.LOGS) && countItem(inventory, chestPositions, level, stack.getItem()) >= 1
                    && planksForLog(stack.getItem()) != Items.AIR) return stack.getItem();
            }
        }
        return null;
    }

    private static boolean consumeInventoryTagged(Container inventory, TagKey<Item> tag, int count) {
        if (countTaggedItems(inventory, tag) < count) return false;
        removeTaggedItems(inventory, tag, count);
        return true;
    }

    private static boolean consumeTaggedItems(Container inventory, List<BlockPos> chestPositions, ServerLevel level,
                                               TagKey<Item> tag, int count) {
        if (countTaggedItems(inventory, chestPositions, level, tag) < count) return false;
        int remaining = removeTaggedItems(inventory, tag, count);
        for (BlockPos pos : chestPositions) {
            if (remaining <= 0) break;
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) remaining = removeTaggedItems(chest, tag, remaining);
        }
        return remaining == 0;
    }

    private static int removeTaggedItems(Container container, TagKey<Item> tag, int count) {
        int remaining = count;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(tag)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        return remaining;
    }

    private record ToolRecipe(Item tool, Item material, boolean usesPlanks, int materialCount) {
        private ToolRecipe(Item tool, Item material, boolean usesPlanks) {
            this(tool, material, usesPlanks, 3);
        }
    }
}
