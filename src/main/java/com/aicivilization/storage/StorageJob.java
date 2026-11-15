package com.aicivilization.storage;

import com.aicivilization.data.CivilizationData;
import com.aicivilization.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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

/** Nation warehouse building, inbound pack handling, sorting, and tool requests. */
public final class StorageJob {
    public static final String BEST_AXE_REQUEST_ID = "ai_civilization:best_axe";
    public static final String BEST_PICKAXE_REQUEST_ID = "ai_civilization:best_pickaxe";
    private static final int DEFAULT_WAREHOUSE_RADIUS = 12;
    private static final int TICK_INTERVAL = 20;
    private static final List<ToolRecipe> AXE_RECIPES = List.of(
        new ToolRecipe(Items.DIAMOND_AXE, Items.DIAMOND, false),
        new ToolRecipe(Items.IRON_AXE, Items.IRON_INGOT, false),
        new ToolRecipe(Items.STONE_AXE, Items.COBBLESTONE, false),
        new ToolRecipe(Items.WOODEN_AXE, null, true)
    );
    private static final List<ToolRecipe> PICKAXE_RECIPES = List.of(
        new ToolRecipe(Items.DIAMOND_PICKAXE, Items.DIAMOND, false),
        new ToolRecipe(Items.IRON_PICKAXE, Items.IRON_INGOT, false),
        new ToolRecipe(Items.STONE_PICKAXE, Items.COBBLESTONE, false),
        new ToolRecipe(Items.WOODEN_PICKAXE, null, true)
    );

    private int cooldown;
    private int lastMissingPlanksNotice = -1000;
    private int lastMissingAxeMaterialsNotice = -1000;
    private int decorationIndex;
    private boolean decoratingFence;
    private UUID resourceDonorId;

    public static boolean ensureWarehouseLocation(CitizenEntity citizen, ServerLevel level) {
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.getNation(citizen.getNationId());
        if (nation == null || !nation.dimension().equals(level.dimension().location())) return false;
        CivilizationData.Warehouse current = data.warehouse(nation.id());
        if (current != null && current.dimension().equals(level.dimension().location())
            && outsideStandardExclusion(current.center(), nation)) return true;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int[] distances = {24, 32, 40, 48};
        for (int distance : distances) {
            for (int[] direction : directions) {
                int x = nation.x() + direction[0] * distance;
                int z = nation.z() + direction[1] * distance;
                BlockPos probe = new BlockPos(x, nation.y(), z);
                if (!level.hasChunkAt(probe)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!outsideStandardExclusion(candidate, nation) || !level.getBlockState(candidate).isAir()
                    || !level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)) continue;
                if (!nation.contains(candidate.offset(12, 0, 0)) || !nation.contains(candidate.offset(-12, 0, 0))
                    || !nation.contains(candidate.offset(0, 0, 12)) || !nation.contains(candidate.offset(0, 0, -12))) continue;
                data.setWarehouse(nation.id(), candidate, 12, level.dimension().location());
                return true;
            }
        }
        return false;
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
        boolean atDonor = false;
        if (resourceDonorId != null) {
            Entity donorEntity = level.getEntity(resourceDonorId);
            if (donorEntity instanceof CitizenEntity donor && donor.getNationId().equals(nationId)) {
                if (citizen.distanceToSqr(donor) > 16.0) {
                    if (citizen.getNavigation().isDone()) citizen.getNavigation().moveTo(donor, 0.9);
                    return;
                }
                atDonor = true;
            } else {
                resourceDonorId = null;
            }
        }
        if (!atDonor && citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 196.0) {
            if (citizen.getNavigation().isDone())
                citizen.getNavigation().moveTo(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
            return;
        }

        List<BlockPos> chests = findChests(level, warehouse);
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
        processRequests(citizen, level, data, warehouse, chests, inbox);
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
        if (resourceDonorId != null) return "Indo buscar madeira com um cidadão da nação";
        if (citizen.distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 196.0)
            return "Indo até o armazém";
        List<BlockPos> chests = findChests(level, warehouse);
        if (!warehouse.hasInbox() || !(level.getBlockEntity(warehouse.inbox()) instanceof ChestBlockEntity))
            return "Construindo o baú principal";
        if (!data.openStorageRequests(citizen.getNationId()).isEmpty()) return "Fabricando ferramentas solicitadas";
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
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(ItemTags.LOGS) && stack.getCount() >= 64) return true;
            if (isLumberjackSecondaryResource(stack)) return true;
        }
        return false;
    }

    public static String lumberjackDeliveryMessage(CitizenEntity citizen) {
        int logPacks = 0;
        int sticks = 0;
        int apples = 0;
        int saplings = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack stack = citizen.getNpcInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) logPacks += stack.getCount() / 64;
            else if (stack.is(Items.STICK)) sticks += stack.getCount();
            else if (stack.is(Items.APPLE)) apples += stack.getCount();
            else if (isSapling(stack)) saplings += stack.getCount();
        }
        List<String> cargo = new ArrayList<>();
        if (logPacks > 0) cargo.add(logPacks + (logPacks == 1 ? " pack de madeira" : " packs de madeira"));
        if (sticks > 0) cargo.add(sticks + "x gravetos");
        if (apples > 0) cargo.add(apples + (apples == 1 ? "x maçã" : "x maçãs"));
        if (saplings > 0) cargo.add(saplings + (saplings == 1 ? "x muda" : "x mudas"));
        return "Entregando " + String.join(", ", cargo) + " ao Storager.";
    }

    /** Returns true while walking to the warehouse or while deliverable resources remain. */
    public static boolean deliverLumberjackResources(CitizenEntity citizen, ServerLevel level) {
        if (!hasLumberjackDeliveryResources(citizen)) return false;
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return true;
        BlockPos inbox = warehouse.inbox();
        if (inbox == null || !(level.getBlockEntity(inbox) instanceof ChestBlockEntity chest)) {
            citizen.getNavigation().moveTo(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5, 0.9);
            return true;
        }
        if (citizen.distanceToSqr(inbox.getX() + 0.5, inbox.getY(), inbox.getZ() + 0.5) > 16.0) {
            citizen.getNavigation().moveTo(inbox.getX() + 0.5, inbox.getY(), inbox.getZ() + 0.5, 0.9);
            return true;
        }

        int logPacks = 0;
        int sticks = 0;
        int apples = 0;
        int saplings = 0;
        for (int slot = 0; slot < citizen.getNpcInventory().getContainerSize(); slot++) {
            ItemStack inventoryStack = citizen.getNpcInventory().getItem(slot);
            int amount;
            if (inventoryStack.is(ItemTags.LOGS)) {
                amount = inventoryStack.getCount() >= 64 ? 64 : 0;
            } else if (isLumberjackSecondaryResource(inventoryStack)) {
                amount = inventoryStack.getCount();
            } else continue;
            if (amount <= 0) continue;

            ItemStack cargo = new ItemStack(inventoryStack.getItem(), amount);
            ItemStack remainder = insert(chest, cargo);
            int delivered = amount - remainder.getCount();
            if (delivered <= 0) continue;
            inventoryStack.shrink(delivered);
            if (inventoryStack.isEmpty()) citizen.getNpcInventory().setItem(slot, ItemStack.EMPTY);
            if (inventoryStack.is(ItemTags.LOGS)) logPacks += delivered / 64;
            else if (inventoryStack.is(Items.STICK)) sticks += delivered;
            else if (inventoryStack.is(Items.APPLE)) apples += delivered;
            else if (isSapling(cargo)) saplings += delivered;
        }
        citizen.getNpcInventory().setChanged();
        chest.setChanged();
        List<String> deliveredCargo = new ArrayList<>();
        if (logPacks > 0) deliveredCargo.add(logPacks + (logPacks == 1 ? " pack de madeira" : " packs de madeira"));
        if (sticks > 0) deliveredCargo.add(sticks + "x gravetos");
        if (apples > 0) deliveredCargo.add(apples + (apples == 1 ? "x maçã" : "x maçãs"));
        if (saplings > 0) deliveredCargo.add(saplings + (saplings == 1 ? "x muda" : "x mudas"));
        if (!deliveredCargo.isEmpty()) {
            citizen.announce("Entreguei " + String.join(", ", deliveredCargo) + " ao Storager.");
        }
        return hasLumberjackDeliveryResources(citizen);
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
                    citizen.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.9);
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

    /** Returns true while walking to or taking a requested tool from the warehouse. */
    public static boolean retrieveBestAxe(CitizenEntity citizen, ServerLevel level) {
        return retrieveBestTool(citizen, level, AXE_RECIPES, "machado");
    }

    public static boolean retrieveBestPickaxe(CitizenEntity citizen, ServerLevel level) {
        return retrieveBestPickaxe(citizen, level, 0);
    }

    /** Retrieves only a pickaxe that meets the requested mining tier. */
    public static boolean retrieveBestPickaxe(CitizenEntity citizen, ServerLevel level, int minimumTier) {
        List<ToolRecipe> eligible = PICKAXE_RECIPES.stream()
            .filter(recipe -> pickaxeTier(recipe.tool()) >= minimumTier).toList();
        if (eligible.isEmpty()) return false;
        return retrieveBestTool(citizen, level, eligible, "picareta");
    }

    public static String pickaxeRequestId(int minimumTier) {
        return minimumTier <= 0 ? BEST_PICKAXE_REQUEST_ID : BEST_PICKAXE_REQUEST_ID + ".tier_" + minimumTier;
    }

    private static int pickaxeTier(Item pickaxe) {
        if (pickaxe == Items.DIAMOND_PICKAXE) return 3;
        if (pickaxe == Items.IRON_PICKAXE) return 2;
        if (pickaxe == Items.STONE_PICKAXE) return 1;
        if (pickaxe == Items.WOODEN_PICKAXE) return 0;
        return -1;
    }

    private static boolean retrieveBestTool(CitizenEntity citizen, ServerLevel level,
                                            List<ToolRecipe> recipes, String toolName) {
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(citizen.getNationId());
        if (warehouse == null || !warehouse.dimension().equals(level.dimension().location())) return false;
        List<BlockPos> chests = findChests(level, warehouse);
        for (ToolRecipe recipe : recipes) {
            for (BlockPos pos : chests) {
                if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
                int slot = findItem(chest, recipe.tool());
                if (slot < 0) continue;
                if (citizen.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > 16.0) {
                    citizen.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.9);
                    return true;
                }
                ItemStack toolStack = chest.getItem(slot).split(1);
                if (chest.getItem(slot).isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
                chest.setChanged();
                citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, toolStack);
                citizen.announce("Peguei a melhor " + toolName + " disponível no armazém e vou voltar ao trabalho.");
                return true;
            }
        }
        return false;
    }

    private void processRequests(CitizenEntity citizen, ServerLevel level, CivilizationData data,
                                 CivilizationData.Warehouse warehouse, List<BlockPos> chestPositions, BlockPos inboxPos) {
        if (chestPositions.isEmpty()) return;
        ChestBlockEntity inbox = level.getBlockEntity(inboxPos) instanceof ChestBlockEntity chest ? chest : null;
        if (inbox == null) return;
        for (CivilizationData.StorageRequest request : data.openStorageRequests(citizen.getNationId())) {
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
            } else continue;
            ToolRecipe stocked = recipes.stream()
                .filter(candidate -> countItem(citizen.getNpcInventory(), chestPositions, level, candidate.tool()) > 0)
                .findFirst().orElse(null);
            if (stocked != null) {
                data.fulfillStorageRequest(request.id());
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
            if (countItem(citizen.getNpcInventory(), chestPositions, level, Items.STICK) < 2) {
                int requiredPlanks = recipe.usesPlanks() ? 5 : 2;
                if (countTaggedItems(citizen.getNpcInventory(), chestPositions, level, ItemTags.PLANKS) < requiredPlanks
                    && !convertLogsToPlanks(citizen, chestPositions, level)) continue;
                if (!convertPlanksToSticks(citizen, chestPositions, level)) continue;
                recipe = findCraftableTool(citizen.getNpcInventory(), chestPositions, level, recipes);
                if (recipe == null) continue;
            }
            ItemStack toolStack = new ItemStack(recipe.tool());
            ChestBlockEntity destination = null;
            for (BlockPos pos : chestPositions) {
                if (pos.equals(inboxPos)) continue;
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest && canAccept(chest, toolStack)) {
                    destination = chest;
                    break;
                }
            }
            if (destination == null) {
                buildChest(citizen, level, warehouse, chestPositions, false);
                return;
            }
            boolean consumedMaterial = recipe.usesPlanks()
                ? consumeTaggedItems(citizen.getNpcInventory(), chestPositions, level, ItemTags.PLANKS, 3)
                : consumeItem(citizen.getNpcInventory(), chestPositions, level, recipe.material(), 3);
            if (!consumedMaterial || !consumeItem(citizen.getNpcInventory(), chestPositions, level, Items.STICK, 2)) continue;
            ItemStack remainder = insert(destination, toolStack);
            if (!remainder.isEmpty()) continue;
            data.fulfillStorageRequest(request.id());
            citizen.announce("Fabriquei a melhor " + toolName + " que os recursos do armazém permitiram.");
        }
    }

    private ToolRecipe findCraftableTool(Container inventory, List<BlockPos> chestPositions, ServerLevel level,
                                         List<ToolRecipe> recipes) {
        for (ToolRecipe recipe : recipes) {
            int materialCount = recipe.usesPlanks()
                ? countTaggedItems(inventory, chestPositions, level, ItemTags.PLANKS)
                : countItem(inventory, chestPositions, level, recipe.material());
            if (materialCount >= 3) return recipe;
        }
        return null;
    }

    private int parseRequestedPickaxeTier(String requestId) {
        String prefix = BEST_PICKAXE_REQUEST_ID + ".tier_";
        try {
            return Math.max(0, Math.min(3, Integer.parseInt(requestId.substring(prefix.length()))));
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
        if (available < 2) return false;
        if (!consumeItem(citizen.getNpcInventory(), chestPositions, level, log, 2)) return false;
        ItemStack planks = new ItemStack(planksItem, 8);
        ItemStack remainder = insert(citizen.getNpcInventory(), planks);
        if (!remainder.isEmpty()) {
            for (BlockPos pos : chestPositions) {
                if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
                remainder = insert(chest, remainder);
                if (remainder.isEmpty()) break;
            }
        }
        if (!remainder.isEmpty()) {
            insert(citizen.getNpcInventory(), new ItemStack(log, 2));
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
        int radius = Math.max(1, Math.min(6, warehouse.radius() / 2));
        int width = radius * 2 + 1;
        int floorCells = width * width;
        for (int placed = 0; placed < 4; placed++) {
            if (!decoratingFence) {
                if (decorationIndex >= floorCells) {
                    decoratingFence = true;
                    decorationIndex = 0;
                    continue;
                }
                int xOffset = decorationIndex % width - radius;
                int zOffset = decorationIndex / width - radius;
                BlockPos center = warehouse.center();
                int x = center.getX() + xOffset;
                int z = center.getZ() + zOffset;
                if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) {
                    decorationIndex++;
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos floor = new BlockPos(x, y, z);
                if (level.getBlockState(floor).is(BlockTags.PLANKS) || !isNaturalGround(level.getBlockState(floor))
                    || level.getBlockState(floor).hasBlockEntity()) {
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
                }
                decorationIndex++;
            } else {
                int perimeterLength = radius * 8;
                if (decorationIndex >= perimeterLength) {
                    decorationIndex = perimeterLength;
                    return;
                }
                BlockPos fencePos = perimeterPosition(warehouse.center(), radius, decorationIndex);
                if (!level.hasChunkAt(fencePos)) {
                    decorationIndex++;
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fencePos.getX(), fencePos.getZ());
                fencePos = new BlockPos(fencePos.getX(), y, fencePos.getZ());
                if (!level.getBlockState(fencePos).isAir()
                    || !level.getBlockState(fencePos.below()).isFaceSturdy(level, fencePos.below(), Direction.UP)) {
                    decorationIndex++;
                    continue;
                }
                Item fence = removeOneFence(citizen, chests, level);
                if (fence == null) return;
                Block fenceBlock = Block.byItem(fence);
                if (fenceBlock == Blocks.AIR || !level.setBlockAndUpdate(fencePos, fenceBlock.defaultBlockState())) {
                    insert(citizen.getNpcInventory(), new ItemStack(fence));
                }
                decorationIndex++;
            }
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
        BlockPos place = firstChest ? warehouse.center() : findChestPlacement(citizen, level, warehouse, false);
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
        // Bootstrap logs can be held by a Lumberjack anywhere inside the same nation.
        // Restricting this search to the warehouse radius left the Storager idle while
        // the Lumberjack was doing its job elsewhere in the territory.
        double radius = CivilizationData.NATION_RADIUS_BLOCKS;
        AABB area = new AABB(center.getX() - radius, center.getY() - radius, center.getZ() - radius,
            center.getX() + radius + 1.0, center.getY() + radius + 1.0, center.getZ() + radius + 1.0);
        List<CitizenEntity> nearbyCitizens = level.getEntitiesOfClass(CitizenEntity.class, area, other ->
            other != storager && storager.getNationId().equals(other.getNationId()));
        nearbyCitizens.sort(Comparator.comparingDouble(storager::distanceToSqr));
        Entity donorEntity = resourceDonorId == null ? null : level.getEntity(resourceDonorId);
        CitizenEntity source = donorEntity instanceof CitizenEntity target ? target : null;
        if (source == null || !nearbyCitizens.contains(source)) {
            resourceDonorId = null;
            source = null;
            for (CitizenEntity candidate : nearbyCitizens) {
                if (countLogs(candidate.getNpcInventory()) > 0) {
                    source = candidate;
                    resourceDonorId = candidate.getUUID();
                    break;
                }
            }
        }
        if (source == null || countLogs(source.getNpcInventory()) <= 0) {
            resourceDonorId = null;
            return false;
        }
        if (storager.distanceToSqr(source) > 16.0) {
            if (storager.getNavigation().isDone()) storager.getNavigation().moveTo(source, 0.9);
            return false;
        }
        Item log = firstLogItem(source.getNpcInventory());
        if (log == null) {
            resourceDonorId = null;
            return false;
        }
        int amount = Math.min(needed, countItem(source.getNpcInventory(), log));
        if (amount <= 0 || !storager.getNpcInventory().canAddItem(new ItemStack(log, amount))) return false;
        int remaining = removeItem(source.getNpcInventory(), log, amount);
        if (remaining != 0) return false;
        storager.getNpcInventory().addItem(new ItemStack(log, amount));
        source.getNpcInventory().setChanged();
        storager.getNpcInventory().setChanged();
        resourceDonorId = null;
        return true;
    }

    private BlockPos findChestPlacement(CitizenEntity citizen, ServerLevel level,
                                        CivilizationData.Warehouse warehouse, boolean firstChest) {
        BlockPos center = warehouse.center();
        int radius = warehouse.radius();
        List<BlockPos> candidates = new ArrayList<>();
        // Fixed chest rows with four blocks between row centers leave a two-block aisle.
        // One empty block between chests keeps them from merging into double chests.
        for (int dz = -radius + 2; dz <= radius - 2; dz += 4) {
            for (int dx = -radius + 2; dx <= radius - 2; dx += 2) {
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                BlockPos probe = new BlockPos(x, center.getY(), z);
                if (!level.hasChunkAt(probe)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (dx * dx + dz * dz < 4) continue;
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
                if (!level.getEntitiesOfClass(CitizenEntity.class, new net.minecraft.world.phys.AABB(pos)).isEmpty()) continue;
                if (!firstChest && (level.getBlockState(pos.east()).is(Blocks.CHEST) || level.getBlockState(pos.west()).is(Blocks.CHEST)
                    || level.getBlockState(pos.north()).is(Blocks.CHEST) || level.getBlockState(pos.south()).is(Blocks.CHEST))) continue;
                candidates.add(pos);
            }
        }
        candidates.sort(Comparator.comparingInt((BlockPos pos) -> pos.getZ())
            .thenComparingInt(BlockPos::getX));
        return candidates.stream().filter(pos -> CivilizationData.get(level).findNationAt(pos, level.dimension().location())
            != null && CivilizationData.get(level).findNationAt(pos, level.dimension().location()).id().equals(warehouse.nationId()))
            .findFirst().orElse(null);
    }

    private static List<BlockPos> findChests(ServerLevel level, CivilizationData.Warehouse warehouse) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos center = warehouse.center();
        int radius = warehouse.radius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int y = Math.max(level.getMinY(), center.getY() - 6); y <= Math.min(level.getMaxY(), center.getY() + 10); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity) result.add(pos);
                }
            }
        }
        result.sort(Comparator.comparingDouble(center::distSqr));
        return result;
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

    private static boolean canFit(Container container, ItemStack incoming) {
        int space = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack current = container.getItem(i);
            if (current.isEmpty()) space += incoming.getMaxStackSize();
            else if (current.is(incoming.getItem())) space += current.getMaxStackSize() - current.getCount();
            if (space >= incoming.getCount()) return true;
        }
        return false;
    }

    private static ItemStack insert(Container container, ItemStack incoming) {
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
            if (stack.is(ItemTags.LOGS) && countItem(inventory, chestPositions, level, stack.getItem()) >= 2
                && planksForLog(stack.getItem()) != Items.AIR) return stack.getItem();
        }
        for (BlockPos pos : chestPositions) {
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (stack.is(ItemTags.LOGS) && countItem(inventory, chestPositions, level, stack.getItem()) >= 2
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

    private record ToolRecipe(Item tool, Item material, boolean usesPlanks) {}
}
