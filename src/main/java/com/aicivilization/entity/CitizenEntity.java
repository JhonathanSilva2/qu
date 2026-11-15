package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.data.CivilizationData;
import com.aicivilization.storage.StorageJob;
import com.aicivilization.profession.MinerJob;
import com.aicivilization.profession.BuilderJob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayerGameMode;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.UUID;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/**
 * Dedicated entity type for future agent behavior. The villager base keeps the
 * first patch visually simple while later patches add custom perception/tasks.
 */
public class CitizenEntity extends PathfinderMob {
    private Player followTarget;
    private boolean lumberjackEnabled;
    private int lumberjackCooldown;
    private BlockPos lumberjackTarget;
    private final List<BlockPos> temporaryTower = new ArrayList<>();
    private final SimpleContainer npcInventory = new SimpleContainer(27);
    private String personalName = "Cidadão";
    private String professionLabel = "Citizen";
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
    private UUID nationId;
    private String nationName = "";
    private BlockPos travelTarget;
    private String travelLocationName = "";
    private BlockPos lastPositionInsideNation;
    private boolean announcedBorderStop;
    private boolean storageEnabled;
    private boolean minerEnabled;
    private boolean builderEnabled;
    private boolean professionResumeAnnounced;
    private int workStuckTicks;
    private int workStuckNoticeCooldown;
    private double workLastX;
    private double workLastY;
    private double workLastZ;
    private final StorageJob storageJob = new StorageJob();
    private final MinerJob minerJob = new MinerJob();
    private final BuilderJob builderJob = new BuilderJob();
    private int toolLookupCooldown;
    private boolean toolPickupPending;
    private ServerPlayer lumberjackBreaker;

    public CitizenEntity(EntityType<? extends CitizenEntity> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
        this.getNavigation().setCanFloat(true);
        this.setPathfindingMalus(PathType.WATER, 0.0f);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0f);
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new FollowPlayerGoal());
    }

    public void follow(Player player) {
        if (nationId == null) {
            announce("Não posso seguir: ainda não pertenço a uma nação.");
            return;
        }
        if (!belongsToNation(player.blockPosition())) {
            announce("Não posso sair do meu país para seguir você.");
            return;
        }
        if (lumberjackEnabled) {
            stopLumberjackWork(false);
            announce("Cancelei o trabalho. Agora vou seguir você.");
        } else if (storageEnabled) {
            stopStorageWork(false);
            announce("Cancelei o trabalho no armazém. Agora vou seguir você.");
        } else if (minerEnabled) {
            stopMinerWork(false);
            announce("Cancelei a escavação. Agora vou seguir você.");
        } else if (builderEnabled) {
            stopBuilderWork(false);
            announce("Cancelei a construção. Agora vou seguir você.");
        } else {
            announce("Estou seguindo você.");
        }
        this.followTarget = player;
    }

    public void stopFollowing() {
        this.followTarget = null;
        this.getNavigation().stop();
        announce("Parei de seguir você.");
    }

    public void setPersonalName(String name) {
        this.personalName = name;
        refreshDisplayName();
    }

    public void setNation(UUID id, String name) {
        if (this.nationId != null && !this.nationId.equals(id)) return;
        this.nationId = id;
        this.nationName = name;
        refreshDisplayName();
    }

    public UUID getNationId() { return nationId; }
    public String getNationName() { return nationName; }

    public boolean travelTo(BlockPos target, String locationName) {
        if (this.level().isClientSide() || nationId == null || !belongsToNation(target)) return false;
        stopLumberjackWork(false);
        stopMinerWork(false);
        stopBuilderWork(false);
        followTarget = null;
        travelTarget = target.immutable();
        travelLocationName = locationName;
        this.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        announce("Estou indo para " + locationName + ".");
        return true;
    }

    public void announce(String message) {
        Component line = Component.literal(getDisplayNameText()).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD)
            .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.players().stream().filter(player -> player.distanceToSqr(this) <= 48.0 * 48.0)
                .forEach(player -> player.sendSystemMessage(line));
        }
    }

    public String getDisplayNameText() {
        return personalName + " - [" + professionLabel + "] - " + getNationalityLabel();
    }

    public String getPersonalName() {
        return personalName;
    }

    private String getNationalityLabel() {
        return switch (nationName) {
            case "Brasil" -> "Brasileiro";
            case "Portugal" -> "Português";
            case "Argentina" -> "Argentino";
            case "Chile" -> "Chileno";
            case "México" -> "Mexicano";
            case "Canadá" -> "Canadense";
            case "Japão" -> "Japonês";
            case "Egito" -> "Egípcio";
            case "Noruega" -> "Norueguês";
            case "Austrália" -> "Australiano";
            case "Marrocos" -> "Marroquino";
            case "Índia" -> "Indiano";
            case "Grécia" -> "Grego";
            case "Quênia" -> "Queniano";
            case "Islândia" -> "Islandês";
            case "Peru" -> "Peruano";
            case "Suécia" -> "Sueco";
            case "Tailândia" -> "Tailandês";
            case "Colômbia" -> "Colombiano";
            case "Nova Zelândia" -> "Neozelandês";
            case "Coreia" -> "Coreano";
            case "Turquia" -> "Turco";
            case "Cuba" -> "Cubano";
            case "Angola" -> "Angolano";
            default -> "Cidadão";
        };
    }

    public SimpleContainer getNpcInventory() {
        return npcInventory;
    }

    private String currentWorkStatus(ServerLevel level) {
        if (builderEnabled) return builderJob.workStatus();
        if (storageEnabled) return storageJob.workStatus(this, level);
        if (minerEnabled) return minerJob.workStatus(this, level);
        if (lumberjackEnabled) {
            if (StorageJob.hasLumberjackDeliveryResources(this)) return "Entregando recursos ao armazém";
            if (lumberjackTarget == null) return getNavigation().isDone() ? "Procurando madeira" : "Patrulhando o território";
            if (!isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) return "Procurando outra árvore";
            return distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
                lumberjackTarget.getZ() + 0.5) > 20.25 ? "Indo até uma árvore" : "Cortando madeira";
        }
        if (followTarget != null) return "Seguindo " + followTarget.getName().getString();
        if (travelTarget != null) return "Indo para " + travelLocationName;
        return "Sem tarefa no momento";
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND && !this.level().isClientSide()) {
            String status = this.level() instanceof ServerLevel serverLevel
                ? currentWorkStatus(serverLevel) : "Status indisponível";
            serverPlayer.sendSystemMessage(Component.literal(getDisplayNameText()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" » Status: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(status).withStyle(ChatFormatting.WHITE)));
            serverPlayer.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> ChestMenu.threeRows(syncId, inventory, npcInventory),
                Component.literal(getDisplayNameText())
            ));
        }
        return InteractionResult.SUCCESS;
    }

    public void teleportToPlayer(ServerPlayer player) {
        BlockPos destination = player.blockPosition().relative(player.getDirection().getOpposite());
        if (!belongsToNation(destination)) {
            announce("Não posso ser teletransportado para fora do meu país ou para outro país.");
            return;
        }
        this.teleportTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
        this.getNavigation().stop();
        announce("Fui teletransportado para você.");
    }

    public boolean startLumberjack() {
        if (this.level().isClientSide()) return false;
        stopStorageWork(false);
        stopMinerWork(false);
        stopBuilderWork(false);
        if (this.getCustomName() != null) {
            String current = this.getCustomName().getString();
            int marker = current.indexOf(" - [");
            if (marker > 0) this.personalName = current.substring(0, marker);
        }
        boolean wasFollowing = followTarget != null;
        if (wasFollowing) {
            followTarget = null;
            this.getNavigation().stop();
        }
        this.lumberjackEnabled = true;
        this.professionLabel = "LumberJack";
        this.professionResumeAnnounced = true;
        refreshDisplayName();
        announce(wasFollowing
            ? "Cancelei o seguimento. Agora vou trabalhar como lenhador."
            : "Vou trabalhar como lenhador e procurar árvores.");
        this.lumberjackTarget = null;
        this.lumberjackCooldown = 0;
        this.announcedSearching = false;
        this.lastAnnouncedWoodMilestone = countLogs() / 16;
        this.towerLimitAnnounced = false;
        this.announcedTreeFound = false;
        this.announcedDirtCollection = false;
        this.patrolIndex = 0;
        this.sweepLogCount = 0;
        this.patrolPoints = List.of();
        this.patrolNationId = nationId;
        this.pendingReplantPositions.clear();
        this.skippedTreeBase = null;
        this.skippedTreeCooldown = 0;
        this.saplingLookupCooldown = 0;
        this.reforestationCooldown = 0;
        this.lumberjackDeliveryPending = false;
        this.toolLookupCooldown = 0;
        this.toolPickupPending = false;
        return true;
    }

    public boolean startStorage() {
        if (this.level().isClientSide() || nationId == null) return false;
        if (!(this.level() instanceof ServerLevel level)) return false;
        CivilizationData data = CivilizationData.get(level);
        if (!data.claimStorager(nationId, this.getUUID())) {
            announce("Esta nação já tem um Storager. Só pode haver um por cidade.");
            return false;
        }
        if (!StorageJob.ensureWarehouseLocation(this, level)) {
            data.releaseStorager(nationId, this.getUUID());
            announce("Não encontrei um local seguro para o armazém fora da área central do estandarte.");
            return false;
        }
        stopLumberjackWork(false);
        stopMinerWork(false);
        stopBuilderWork(false);
        followTarget = null;
        travelTarget = null;
        this.getNavigation().stop();
        storageEnabled = true;
        professionLabel = "Storager";
        professionResumeAnnounced = true;
        refreshDisplayName();
        announce("Vou organizar o armazém da minha nação.");
        return true;
    }

    public void stopStorage() {
        stopStorageWork(true);
    }

    public void stopProfession() {
        stopLumberjackWork(true);
        stopStorageWork(true);
        stopMinerWork(true);
        stopBuilderWork(true);
    }

    public boolean startMiner() {
        if (this.level().isClientSide() || nationId == null || !(this.level() instanceof ServerLevel level)) return false;
        stopLumberjackWork(false);
        stopStorageWork(false);
        stopBuilderWork(false);
        followTarget = null;
        travelTarget = null;
        getNavigation().stop();
        minerEnabled = true;
        professionLabel = "Miner";
        professionResumeAnnounced = true;
        refreshDisplayName();
        minerJob.start(this, level);
        return true;
    }

    public boolean startBuilder() {
        if (this.level().isClientSide() || nationId == null || !(this.level() instanceof ServerLevel level)) return false;
        stopLumberjackWork(false);
        stopStorageWork(false);
        stopMinerWork(false);
        followTarget = null;
        travelTarget = null;
        getNavigation().stop();
        builderEnabled = true;
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
        if (nation != null) builderJob.start(this, nation.center());
        professionLabel = "Builder";
        professionResumeAnnounced = true;
        refreshDisplayName();
        announce("Vou preparar o terreno da warehouse quando o Storager solicitar e abrir os caminhos da vila.");
        return true;
    }

    private void stopBuilderWork(boolean shouldAnnounce) {
        if (!builderEnabled) return;
        builderEnabled = false;
        builderJob.stop();
        getNavigation().stop();
        professionLabel = "Citizen";
        refreshDisplayName();
        if (shouldAnnounce) announce("Parei o trabalho de construção.");
    }

    public void stopMiner() {
        stopMinerWork(true);
    }

    private void stopMinerWork(boolean shouldAnnounce) {
        if (!minerEnabled) return;
        minerEnabled = false;
        getNavigation().stop();
        professionLabel = "Citizen";
        refreshDisplayName();
        if (shouldAnnounce) announce("Parei a escavação.");
    }

    private void stopStorageWork(boolean shouldAnnounce) {
        if (!storageEnabled) return;
        storageEnabled = false;
        if (this.level() instanceof ServerLevel level && nationId != null) {
            CivilizationData.get(level).releaseStorager(nationId, this.getUUID());
        }
        this.getNavigation().stop();
        professionLabel = "Citizen";
        refreshDisplayName();
        if (shouldAnnounce) announce("Parei de organizar o armazém.");
    }

    public void stopLumberjack() {
        stopLumberjackWork(true);
    }

    private void stopLumberjackWork(boolean announce) {
        boolean wasWorking = this.lumberjackEnabled;
        this.lumberjackEnabled = false;
        this.lumberjackTarget = null;
        this.chopProgress = 0.0f;
        this.getNavigation().stop();
        removeTemporaryTower();
        if (wasWorking) {
            if (this.getCustomName() != null) {
                String current = this.getCustomName().getString();
                int marker = current.indexOf(" - [");
                if (marker > 0) this.personalName = current.substring(0, marker);
            }
            this.professionLabel = "Citizen";
            refreshDisplayName();
            if (announce) announce("Parei o trabalho. Minha madeira está no inventário.");
        }
    }

    public boolean isLumberjackEnabled() {
        return lumberjackEnabled;
    }

    public String debugSnapshot(ServerLevel level) {
        String jobState;
        if (storageEnabled) {
            jobState = storageJob.debugStatus(this, level);
        } else if (minerEnabled) {
            jobState = minerJob.debugStatus(this, level);
        } else if (builderEnabled) {
            jobState = builderJob.debugStatus();
        } else if (lumberjackEnabled) {
            String target = lumberjackTarget == null ? "none" : lumberjackTarget.toShortString()
                + "/" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(lumberjackTarget).getBlock());
            BlockPos leaf = findBlockingLeaf(level);
            jobState = "target=" + target + ", chop=" + String.format(java.util.Locale.ROOT, "%.2f", chopProgress)
                + ", stuckTicks=" + stuckTicks + ", blockedTree=" + (skippedTreeBase == null ? "none" : skippedTreeBase.toShortString())
                + ", blockingLeaf=" + (leaf == null ? "none" : leaf.toShortString());
        } else {
            jobState = "no active profession";
        }
        return "[AI DEBUG] " + personalName + " profession=" + professionLabel + " pos=" + blockPosition().toShortString()
            + " nav=" + (getNavigation().isDone() ? "done" : "moving") + " " + jobState;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel && nationId != null) {
            if (!enforceNationBoundary(serverLevel)) return;
        }
        if (travelTarget != null && !this.level().isClientSide()) {
            if (this.blockPosition().closerThan(travelTarget, 2.0)) {
                announce("Cheguei a " + travelLocationName + ".");
                travelTarget = null;
                travelLocationName = "";
                this.getNavigation().stop();
            } else if (this.tickCount % 20 == 0 && this.getNavigation().isDone()) {
                this.getNavigation().moveTo(travelTarget.getX() + 0.5, travelTarget.getY(), travelTarget.getZ() + 0.5, 1.0);
            }
        }
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level)) return;
        AICivilizationMod.tickDebugWatches(this, level);
        if (workStuckNoticeCooldown > 0) workStuckNoticeCooldown--;
        if (hasActiveProfession() && workNavigationExpected(level)) {
            if (distanceToSqr(workLastX, workLastY, workLastZ) < 0.04) workStuckTicks++;
            else workStuckTicks = 0;
            workLastX = getX();
            workLastY = getY();
            workLastZ = getZ();
            if (workStuckTicks >= 200) {
                getNavigation().stop();
                workStuckTicks = 0;
                if (workStuckNoticeCooldown <= 0) {
                    announce("Fiquei bloqueado; vou recalcular o caminho e tentar continuar o trabalho.");
                    workStuckNoticeCooldown = 400;
                }
            }
        } else {
            workStuckTicks = 0;
            workLastX = getX();
            workLastY = getY();
            workLastZ = getZ();
        }
        if (storageEnabled) {
            if (!CivilizationData.get(level).claimStorager(nationId, this.getUUID())) {
                storageEnabled = false;
                professionLabel = "Citizen";
                refreshDisplayName();
                announce("Já existe outro Storager nesta nação; fiquei como cidadão.");
                return;
            }
            announceResumedProfession("Retomei o trabalho no armazém.");
            if (!StorageJob.ensureWarehouseLocation(this, level)) return;
            storageJob.tick(this, level);
            return;
        }
        if (minerEnabled) {
            announceResumedProfession("Retomei a escavação.");
            minerJob.tick(this, level);
            return;
        }
        if (builderEnabled) {
            announceResumedProfession("Retomei o trabalho de construção.");
            if (!builderJob.isInitialized()) {
                CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
                if (nation != null) builderJob.start(this, nation.center());
            }
            builderJob.tick(this, level);
            return;
        }
        if (!lumberjackEnabled) return;
        announceResumedProfession("Retomei o trabalho de lenhador.");
        if (lumberjackCooldown-- > 0) return;
        lumberjackCooldown = 10;
        doLumberjackWork(level);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("PersonalName", personalName);
        tag.putString("ProfessionLabel", professionLabel);
        tag.putBoolean("LumberjackEnabled", lumberjackEnabled);
        tag.putBoolean("StorageEnabled", storageEnabled);
        tag.putBoolean("MinerEnabled", minerEnabled);
        tag.putBoolean("BuilderEnabled", builderEnabled);
        minerJob.save(tag);
        builderJob.save(tag);
        if (nationId != null) tag.store("NationId", UUIDUtil.CODEC, nationId);
        tag.putString("NationName", nationName);
        if (lastPositionInsideNation != null) tag.store("LastPositionInsideNation", BlockPos.CODEC, lastPositionInsideNation);
        for (int i = 0; i < npcInventory.getContainerSize(); i++) {
            ItemStack stack = npcInventory.getItem(i);
            if (!stack.isEmpty()) tag.store("CitizenItem" + i, ItemStack.CODEC, stack);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput tag) {
        super.readAdditionalSaveData(tag);
        personalName = tag.getStringOr("PersonalName", personalName);
        lumberjackEnabled = tag.getBooleanOr("LumberjackEnabled", false);
        storageEnabled = tag.getBooleanOr("StorageEnabled", false);
        if (storageEnabled) lumberjackEnabled = false;
        professionLabel = storageEnabled ? "Storager" : lumberjackEnabled ? "LumberJack"
            : tag.getStringOr("ProfessionLabel", "Citizen");
        minerEnabled = tag.getBooleanOr("MinerEnabled", false);
        builderEnabled = tag.getBooleanOr("BuilderEnabled", false);
        builderJob.load(tag);
        if (minerEnabled) {
            lumberjackEnabled = false;
            storageEnabled = false;
            builderEnabled = false;
            professionLabel = "Miner";
        }
        if (builderEnabled) {
            lumberjackEnabled = false;
            storageEnabled = false;
            minerEnabled = false;
            professionLabel = "Builder";
        }
        minerJob.load(tag);
        nationId = tag.read("NationId", UUIDUtil.CODEC).orElse(null);
        nationName = tag.getStringOr("NationName", "");
        lastPositionInsideNation = tag.read("LastPositionInsideNation", BlockPos.CODEC).orElse(null);
        professionResumeAnnounced = false;
        lumberjackTarget = null;
        chopProgress = 0.0f;
        for (int i = 0; i < npcInventory.getContainerSize(); i++) {
            final int slot = i;
            tag.read("CitizenItem" + slot, ItemStack.CODEC).ifPresent(stack -> npcInventory.setItem(slot, stack));
        }
        refreshDisplayName();
    }

    private void doLumberjackWork(ServerLevel level) {
        if (skippedTreeCooldown > 0 && --skippedTreeCooldown == 0) skippedTreeBase = null;
        if (saplingLookupCooldown > 0) saplingLookupCooldown -= 10;
        if (reforestationCooldown > 0) reforestationCooldown -= 10;
        if (collectNearbyTreeDrops(level)) return;
        replantAvailableSaplings(level);
        if (StorageJob.hasLumberjackDeliveryResources(this)) {
            if (!lumberjackDeliveryPending) {
                announce(StorageJob.lumberjackDeliveryMessage(this));
                lumberjackDeliveryPending = true;
            }
            if (StorageJob.deliverLumberjackResources(this, level)) return;
        }
        lumberjackDeliveryPending = false;
        if (isAxe(this.getMainHandItem())) {
            toolPickupPending = false;
        } else if (bestInventoryAxe() != null) {
            ItemStack axe = npcInventory.removeItemType(bestInventoryAxe(), 1);
            this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, axe);
            toolPickupPending = false;
        } else if (toolPickupPending) {
            if (StorageJob.retrieveBestAxe(this, level)) return;
            toolPickupPending = false;
        } else if (toolLookupCooldown > 0) {
            toolLookupCooldown -= 10;
        } else {
            toolLookupCooldown = 100;
            if (StorageJob.retrieveBestAxe(this, level)) {
                toolPickupPending = !isAxe(this.getMainHandItem());
                return;
            }
            if (nationId != null) {
                CivilizationData.get(level).requestStorageItem(nationId, this.getUUID(),
                    StorageJob.BEST_AXE_REQUEST_ID, 1);
            }
        }
        announcedSearching = true;
        if (lumberjackTarget == null || !isChoppableTreeBlock(level.getBlockState(lumberjackTarget))) {
            lumberjackTarget = findNearestLog(level);
            if (lumberjackTarget == null) {
                if (plantSaplingInTerritory(level)) return;
                if (firstSaplingSlot() < 0 && saplingLookupCooldown <= 0) {
                    saplingLookupCooldown = 100;
                    if (StorageJob.retrieveFirstSapling(this, level)) return;
                }
                patrolForWood(level);
                return;
            }
            if (!announcedTreeFound) {
                announce("Encontrei uma árvore; vou começar a coleta.");
                announcedTreeFound = true;
            }
        }

        BlockPos approach = findTreeApproach(level, lumberjackTarget);
        if (approach == null) {
            recoverPathIfStuck(level);
            return;
        }
        double horizontalDistance = this.distanceToSqr(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5);
        boolean closeEnoughToChop = horizontalDistance <= 20.25;
        if (!closeEnoughToChop || !this.getNavigation().isDone()) {
            if (!closeEnoughToChop && (this.getNavigation().isDone() || this.getNavigation().getTargetPos() == null
                || !this.getNavigation().getTargetPos().equals(approach))) {
                this.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
            }
            recoverPathIfStuck(level);
            return;
        }

        Item targetItem = level.getBlockState(lumberjackTarget).getBlock().asItem();
        if (!npcInventory.canAddItem(new ItemStack(targetItem))) {
            announce("Meu inventário está cheio; vou parar de cortar.");
            stopLumberjack();
            return;
        }
        BlockState targetState = level.getBlockState(lumberjackTarget);
        this.getLookControl().setLookAt(lumberjackTarget.getX() + 0.5, lumberjackTarget.getY() + 0.5,
            lumberjackTarget.getZ() + 0.5, 30.0f, 30.0f);
        this.swing(InteractionHand.MAIN_HAND);
        float hardness = targetState.getDestroySpeed(level, lumberjackTarget);
        float toolSpeed = this.getMainHandItem().isEmpty() ? 1.0f : this.getMainHandItem().getDestroySpeed(targetState);
        chopProgress += hardness <= 0.0f ? 1.0f : Math.max(0.02f, toolSpeed / hardness / 3.0f);
        level.destroyBlockProgress(this.getId(), lumberjackTarget, Math.min(9, (int) (chopProgress * 10.0f)));
        if (chopProgress >= 1.0f) {
            level.destroyBlockProgress(this.getId(), lumberjackTarget, -1);
            BlockPos choppedPosition = lumberjackTarget;
            breakTreeBlockAsPlayer(level, choppedPosition);
            int woodMilestone = countLogs() / 16;
            if (woodMilestone > lastAnnouncedWoodMilestone) {
                lastAnnouncedWoodMilestone = woodMilestone;
                announce("Já tenho " + (woodMilestone * 16) + " troncos no inventário.");
            }
            chopProgress = 0.0f;
            if (!isChoppableTreeBlock(level.getBlockState(choppedPosition))) {
                if (level.getBlockState(choppedPosition.below()).is(BlockTags.DIRT)
                    && belongsToNation(choppedPosition)) pendingReplantPositions.add(choppedPosition.immutable());
                lumberjackTarget = null;
                announcedTreeFound = false;
            }
        }
    }

    private boolean isChoppableTreeBlock(BlockState state) {
        if (state.is(BlockTags.LOGS)) return true;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("treechop:chopped_log");
    }

    private BlockPos findTreeApproach(ServerLevel level, BlockPos trunk) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int verticalOffset = 1; verticalOffset >= -2; verticalOffset--) {
                BlockPos feet = trunk.relative(direction).above(verticalOffset);
                BlockState floor = level.getBlockState(feet.below());
                if (!belongsToNation(feet) || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    || !floor.isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                double distance = this.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = feet.immutable();
                }
            }
        }
        return best;
    }

    /** Uses Minecraft's player block-breaking path so TreeChop can process each hit. */
    private boolean breakTreeBlockAsPlayer(ServerLevel level, BlockPos position) {
        if (!isChoppableTreeBlock(level.getBlockState(position))) return false;
        if (lumberjackBreaker == null) {
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("aiciv:" + getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                getName().getString());
            lumberjackBreaker = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        }
        lumberjackBreaker.setPos(getX(), getY(), getZ());
        lumberjackBreaker.setYRot(getYRot());
        lumberjackBreaker.setXRot(getXRot());
        ItemStack tool = getMainHandItem().copy();
        lumberjackBreaker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool);
        ServerPlayerGameMode gameMode = lumberjackBreaker.gameMode;
        boolean broken = gameMode.destroyBlock(position);
        ItemStack usedTool = lumberjackBreaker.getMainHandItem();
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, usedTool.copy());
        return broken;
    }

    private BlockPos findTreeBase(ServerLevel level, BlockPos log) {
        BlockPos base = log.immutable();
        for (int depth = 0; depth < 64; depth++) {
            BlockPos below = base.below();
            if (!belongsToNation(below) || !level.getBlockState(below).is(BlockTags.LOGS)) break;
            base = below.immutable();
        }
        return base;
    }

    private void patrolForWood(ServerLevel level) {
        refreshPatrolPoints(level);
        if (patrolPoints.isEmpty()) {
            if (!announcedBorderStop) {
                announce("Não consegui criar uma rota de patrulha dentro do meu país.");
                announcedBorderStop = true;
            }
            return;
        }
        BlockPos target = patrolPoints.get(patrolIndex);
        double distance = this.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (distance <= 9.0) {
            patrolIndex++;
            if (patrolIndex >= patrolPoints.size()) {
                patrolIndex = 0;
                if (sweepLogCount == 0) announce("Completei uma ronda pelo território e vou continuar procurando madeira.");
                else announce("Completei uma ronda e coletei " + sweepLogCount + " troncos.");
                sweepLogCount = 0;
            }
            target = patrolPoints.get(patrolIndex);
            distance = this.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        }
        if (distance > 9.0 && this.getNavigation().isDone()) {
            this.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
        }
    }

    private void refreshPatrolPoints(ServerLevel level) {
        if (patrolNationId != null && patrolNationId.equals(nationId) && !patrolPoints.isEmpty()
            && this.tickCount - patrolLastRefreshTick < 200) return;
        patrolNationId = nationId;
        patrolLastRefreshTick = this.tickCount;
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
                if (belongsToNation(waypoint)) route.add(waypoint);
            }
        }
        if (route.isEmpty()) {
            patrolPoints = List.of();
            return;
        }
        if (route.size() == 1) {
            BlockPos center = route.get(0);
            for (BlockPos offset : List.of(new BlockPos(8, 0, 0), new BlockPos(-8, 0, 0),
                new BlockPos(0, 0, 8), new BlockPos(0, 0, -8))) {
                int x = center.getX() + offset.getX();
                int z = center.getZ() + offset.getZ();
                if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) continue;
                BlockPos nearby = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (belongsToNation(nearby)) route.add(nearby);
            }
        }
        patrolPoints = List.copyOf(route);
        patrolIndex = 0;
        for (int i = 1; i < patrolPoints.size(); i++) {
            if (this.blockPosition().distSqr(patrolPoints.get(i)) < this.blockPosition().distSqr(patrolPoints.get(patrolIndex))) {
                patrolIndex = i;
            }
        }
    }

    private boolean collectNearbyTreeDrops(ServerLevel level) {
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(32.0)).stream()
            .filter(item -> isTreeDrop(item.getItem()))
            .sorted(Comparator.comparingDouble(this::distanceToSqr))
            .toList();
        for (ItemEntity itemEntity : drops) {
            ItemStack stack = itemEntity.getItem();
            if (Math.abs(itemEntity.getY() - this.getY()) > 5.0) continue;
            if (this.distanceToSqr(itemEntity) > 16.0) {
                this.getNavigation().moveTo(itemEntity, 0.9);
                return true;
            }
            ItemStack remainder = npcInventory.addItem(stack.copy());
            if (remainder.isEmpty()) itemEntity.discard();
            else {
                itemEntity.setItem(remainder);
                announce("Meu inventário encheu; vou parar para não deixar os recursos para trás.");
                stopLumberjackWork(false);
                return true;
            }
        }
        return false;
    }

    private boolean isTreeDrop(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || isSapling(stack) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    private void replantAvailableSaplings(ServerLevel level) {
        if (pendingReplantPositions.isEmpty()) return;
        for (int i = 0; i < pendingReplantPositions.size(); i++) {
            BlockPos pos = pendingReplantPositions.get(i);
            if (!belongsToNation(pos)) {
                pendingReplantPositions.remove(i--);
                continue;
            }
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(BlockTags.DIRT)
                && !hasLogAbove(level, pos)) {
                ItemStack sapling = removeFirstSapling();
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

    private ItemStack removeFirstSapling() {
        for (int i = 0; i < npcInventory.getContainerSize(); i++) {
            ItemStack stack = npcInventory.getItem(i);
            if (isSapling(stack)) return stack.split(1);
        }
        return null;
    }

    private boolean isSapling(ItemStack stack) {
        return saplingBlock(stack.getItem()) != null;
    }

    private Block saplingBlock(net.minecraft.world.item.Item item) {
        Block block = Block.byItem(item);
        return block.defaultBlockState().is(BlockTags.SAPLINGS) ? block : null;
    }

    private BlockPos findNearestLog(ServerLevel level) {
        BlockPos origin = this.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -4, -8), origin.offset(8, 32, 8))) {
            boolean partOfSkippedTree = skippedTreeBase != null && skippedTreeCooldown > 0
                && Math.abs(pos.getX() - skippedTreeBase.getX()) <= 2
                && Math.abs(pos.getZ() - skippedTreeBase.getZ()) <= 2
                && pos.getY() >= skippedTreeBase.getY() && pos.getY() <= skippedTreeBase.getY() + 32;
            if (level.getBlockState(pos).is(BlockTags.LOGS) && !temporaryTower.contains(pos)
                && !partOfSkippedTree && belongsToNation(pos)) {
                double distance = this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance < bestDistance) { bestDistance = distance; best = pos.immutable(); }
            }
        }
        if (best != null) return findTreeBase(level, best);
        return null;
    }

    private void buildTemporaryTower(ServerLevel level) {
        if (temporaryTower.size() >= 16) {
            if (!towerLimitAnnounced) {
                announce("Não consigo subir mais: atingi o limite seguro da torre.");
                towerLimitAnnounced = true;
            }
            return;
        }
        net.minecraft.world.level.block.Block towerBlock;
        ItemStack towerItem;
        if (countLogs() > 1) {
            towerBlock = Blocks.OAK_LOG;
            towerItem = new ItemStack(Items.OAK_LOG);
        } else if (countItems(Items.DIRT) > 0) {
            towerBlock = Blocks.DIRT;
            towerItem = new ItemStack(Items.DIRT);
        } else {
            if (collectDirt(level)) return;
            announce("Estou sem blocos para subir e não encontrei terra próxima.");
            return;
        }
        Direction direction = horizontalDirectionTo(lumberjackTarget);
        BlockPos placeAt = this.blockPosition().relative(direction);
        if (belongsToNation(placeAt) && level.getBlockState(placeAt).isAir() && level.getBlockState(placeAt.above()).isAir()) {
            level.setBlockAndUpdate(placeAt, towerBlock.defaultBlockState());
            temporaryTower.add(placeAt.immutable());
            npcInventory.removeItemType(towerItem.getItem(), 1);
            this.getJumpControl().jump();
            this.getNavigation().moveTo(placeAt.getX() + 0.5, placeAt.getY() + 1.0, placeAt.getZ() + 0.5, 0.8);
        } else if (temporaryTower.contains(placeAt)) {
            this.getJumpControl().jump();
            this.getNavigation().moveTo(placeAt.getX() + 0.5, placeAt.getY() + 1.0, placeAt.getZ() + 0.5, 0.8);
        } else {
            this.getNavigation().moveTo(lumberjackTarget.getX() + 0.5, this.getY(), lumberjackTarget.getZ() + 0.5, 0.8);
        }
    }

    private void removeTemporaryTower() {
        int removed = 0;
        for (BlockPos pos : temporaryTower) {
            if ((this.level().getBlockState(pos).is(Blocks.OAK_LOG) || this.level().getBlockState(pos).is(Blocks.DIRT))
                && this.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) removed++;
        }
        temporaryTower.clear();
        if (removed > 0) announce("Desmontei a torre temporária (" + removed + " bloco(s)).");
    }

    private void refreshDisplayName() {
        this.setCustomName(Component.literal(getDisplayNameText()).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD));
        this.setCustomNameVisible(true);
    }

    private int countLogs() {
        int count = 0;
        for (int slot = 0; slot < npcInventory.getContainerSize(); slot++) {
            ItemStack stack = npcInventory.getItem(slot);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private Item fullLogStackItem() {
        for (int slot = 0; slot < npcInventory.getContainerSize(); slot++) {
            ItemStack stack = npcInventory.getItem(slot);
            if (stack.is(ItemTags.LOGS) && stack.getCount() >= 64) return stack.getItem();
        }
        return null;
    }

    private int countItems(net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < npcInventory.getContainerSize(); slot++) {
            ItemStack stack = npcInventory.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private boolean collectDirt(ServerLevel level) {
        BlockPos target = null;
        double nearest = Double.MAX_VALUE;
        BlockPos origin = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            if (belongsToNation(pos) && level.getBlockState(pos).is(BlockTags.DIRT) && level.getBlockState(pos.above()).isAir()) {
                double distance = this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distance > 2.0 && distance < nearest) { nearest = distance; target = pos.immutable(); }
            }
        }
        ItemStack dirt = new ItemStack(Items.DIRT);
        if (target != null && belongsToNation(target) && npcInventory.canAddItem(dirt) && level.destroyBlock(target, false, this)) {
            this.swing(InteractionHand.MAIN_HAND);
            npcInventory.addItem(dirt);
            if (!announcedDirtCollection) {
                announce("Estava sem blocos para subir; coletei terra para continuar.");
                announcedDirtCollection = true;
            }
            return true;
        }
        return false;
    }

    private BlockPos findNearbyLeaf(ServerLevel level) {
        BlockPos origin = this.blockPosition();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(2, 2, 2))) {
            if (!belongsToNation(pos) || !level.getBlockState(pos).is(BlockTags.LEAVES)) continue;
            double distance = this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distance < best) { best = distance; nearest = pos.immutable(); }
        }
        return nearest;
    }

    private BlockPos findBlockingLeaf(ServerLevel level) {
        if (lumberjackTarget == null) return findNearbyLeaf(level);
        Direction direction = horizontalDirectionTo(lumberjackTarget);
        Direction across = direction.getClockWise();
        BlockPos origin = blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int forward = 1; forward <= 4; forward++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                for (int height = -1; height <= 4; height++) {
                    BlockPos candidate = origin.relative(direction, forward).relative(across, lateral).above(height);
                    if (!belongsToNation(candidate) || !level.getBlockState(candidate).is(BlockTags.LEAVES)) continue;
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

    private void recoverPathIfStuck(ServerLevel level) {
        double moved = this.distanceToSqr(lastX, lastY, lastZ);
        if (moved < 0.04) stuckTicks++;
        else stuckTicks = 0;
        lastX = this.getX(); lastY = this.getY(); lastZ = this.getZ();
        if (stuckTicks < 10) return;
        stuckTicks = 0;

        Direction direction = horizontalDirectionTo(lumberjackTarget);
        BlockPos front = this.blockPosition().relative(direction);
        BlockPos blockingLeaf = findBlockingLeaf(level);
        if (blockingLeaf != null) {
            this.getLookControl().setLookAt(blockingLeaf.getX() + 0.5, blockingLeaf.getY() + 0.5,
                blockingLeaf.getZ() + 0.5, 30.0f, 30.0f);
            this.swing(InteractionHand.MAIN_HAND);
            if (level.destroyBlock(blockingLeaf, true, this)) {
                announce("As folhas estavam bloqueando meu caminho; limpei a passagem e vou continuar até a árvore.");
                BlockPos approach = findTreeApproach(level, lumberjackTarget);
                if (approach != null) this.getNavigation().moveTo(approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 0.9);
                return;
            }
        }
        for (BlockPos obstacle : List.of(front.above(), front.above(2))) {
            BlockState state = level.getBlockState(obstacle);
            if (belongsToNation(obstacle) && !state.isAir() && !state.hasBlockEntity() && state.getDestroySpeed(level, obstacle) >= 0.0f
                && (state.is(BlockTags.DIRT) || state.is(BlockTags.LOGS))) {
                this.swing(InteractionHand.MAIN_HAND);
                if (level.destroyBlock(obstacle, false, this)) announce("Abri caminho removendo um bloco que me prendia.");
                this.getNavigation().recomputePath();
                return;
            }
        }
        BlockPos step = front;
        if (belongsToNation(step) && level.getBlockState(step).isAir() && level.getBlockState(step.above()).isAir()
            && npcInventory.countItem(Items.DIRT) > 0) {
            level.setBlockAndUpdate(step, Blocks.DIRT.defaultBlockState());
            temporaryTower.add(step.immutable());
            npcInventory.removeItemType(Items.DIRT, 1);
            announce("Fiquei preso. Estou construindo um degrau de terra para sair.");
            this.getJumpControl().jump();
            this.getNavigation().moveTo(step.getX() + 0.5, step.getY() + 1.0, step.getZ() + 0.5, 0.8);
        } else if (countItems(Items.DIRT) == 0 && collectDirt(level)) {
            return;
        } else {
            skippedTreeBase = findTreeBase(level, lumberjackTarget);
            skippedTreeCooldown = 200;
            lumberjackTarget = null;
            announcedTreeFound = false;
            this.getNavigation().stop();
            announce("Esse caminho para a árvore travou; vou procurar outra rota e continuar trabalhando.");
        }
    }

    private int firstSaplingSlot() {
        for (int slot = 0; slot < npcInventory.getContainerSize(); slot++) {
            if (isSapling(npcInventory.getItem(slot))) return slot;
        }
        return -1;
    }

    private boolean plantSaplingInTerritory(ServerLevel level) {
        if (reforestationCooldown > 0) return false;
        int saplingSlot = firstSaplingSlot();
        if (saplingSlot < 0) return false;
        ItemStack sapling = npcInventory.getItem(saplingSlot);
        Block saplingBlock = saplingBlock(sapling.getItem());
        if (saplingBlock == null) return false;
        BlockPos origin = this.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                BlockPos probe = new BlockPos(x, origin.getY(), z);
                if (!level.hasChunkAt(probe)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos plot = new BlockPos(x, y, z);
                if (plot.distSqr(origin) <= 4.0 || !belongsToNation(plot) || isInsideExcludedPlantingArea(plot, level)
                    || !level.getBlockState(plot).isAir() || !level.getBlockState(plot.above()).isAir()
                    || !level.getBlockState(plot.below()).is(BlockTags.DIRT) || hasLogAbove(level, plot)) continue;
                boolean crowded = hasNearbySapling(level, plot);
                if (!crowded && saplingBlock.defaultBlockState().canSurvive(level, plot)) candidates.add(plot.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> this.distanceToSqr(
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        for (BlockPos plot : candidates) {
            if (!level.setBlockAndUpdate(plot, saplingBlock.defaultBlockState())) continue;
            sapling.shrink(1);
            if (sapling.isEmpty()) npcInventory.setItem(saplingSlot, ItemStack.EMPTY);
            npcInventory.setChanged();
            reforestationCooldown = 20;
            announce("Não encontrei árvores por perto; plantei uma muda e vou priorizar o reflorestamento enquanto patrulho o território.");
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

    private boolean isInsideExcludedPlantingArea(BlockPos position, ServerLevel level) {
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
        if (nation == null) return true;
        long dx = (long) position.getX() - nation.x();
        long dz = (long) position.getZ() - nation.z();
        if (dx * dx + dz * dz < 20L * 20L) return true;
        CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(nationId);
        return warehouse != null && warehouse.center().distSqr(position) < (warehouse.radius() + 4.0) * (warehouse.radius() + 4.0);
    }

    private Direction horizontalDirectionTo(BlockPos target) {
        int dx = target.getX() - this.blockPosition().getX();
        int dz = target.getZ() - this.blockPosition().getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Item bestInventoryAxe() {
        for (Item axe : List.of(Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            for (int slot = 0; slot < npcInventory.getContainerSize(); slot++) {
                if (npcInventory.getItem(slot).is(axe)) return axe;
            }
        }
        return null;
    }

    private boolean isAxe(ItemStack stack) {
        return stack.is(Items.DIAMOND_AXE) || stack.is(Items.IRON_AXE)
            || stack.is(Items.STONE_AXE) || stack.is(Items.WOODEN_AXE);
    }

    private final class FollowPlayerGoal extends Goal {
        private int recalculatePath;

        private FollowPlayerGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return isValidTarget() && distanceToSqr(followTarget) > 9.0;
        }

        @Override
        public boolean canContinueToUse() {
            return isValidTarget() && distanceToSqr(followTarget) > 4.0;
        }

        @Override
        public void start() {
            this.recalculatePath = 0;
        }

        @Override
        public void tick() {
            if (!belongsToNation(followTarget.blockPosition())) {
                followTarget = null;
                CitizenEntity.this.getNavigation().stop();
                if (!announcedBorderStop) {
                    announce("Parei na fronteira; não posso entrar em outro país.");
                    announcedBorderStop = true;
                }
                return;
            }
            if (this.recalculatePath-- <= 0) {
                this.recalculatePath = 10;
                CitizenEntity.this.getNavigation().moveTo(followTarget, 1.0);
            }
        }

        @Override
        public void stop() {
            CitizenEntity.this.getNavigation().stop();
        }

        private boolean isValidTarget() {
            return followTarget != null && followTarget.isAlive()
                && followTarget.level() == CitizenEntity.this.level()
                && belongsToNation(followTarget.blockPosition());
        }
    }

    private boolean belongsToNation(BlockPos position) {
        if (!(this.level() instanceof ServerLevel serverLevel) || nationId == null) return false;
        CivilizationData.Nation ownNation = CivilizationData.get(serverLevel).getNation(nationId);
        if (ownNation == null || !ownNation.dimension().equals(serverLevel.dimension().location())) return false;
        CivilizationData.Nation territory = CivilizationData.get(serverLevel).findNationAt(position, serverLevel.dimension().location());
        return territory != null && territory.id().equals(nationId);
    }

    private boolean enforceNationBoundary(ServerLevel level) {
        if (belongsToNation(this.blockPosition())) {
            lastPositionInsideNation = this.blockPosition().immutable();
            announcedBorderStop = false;
            return true;
        }
        followTarget = null;
        travelTarget = null;
        travelLocationName = "";
        BlockPos returnPosition = lastPositionInsideNation;
        if (returnPosition == null || !belongsToNation(returnPosition)) {
            CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
            if (nation == null) return false;
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nation.x(), nation.z());
            returnPosition = new BlockPos(nation.x(), surfaceY, nation.z());
        }
        if (this.getNavigation().isDone()) {
            this.getNavigation().moveTo(returnPosition.getX() + 0.5, returnPosition.getY(),
                returnPosition.getZ() + 0.5, 0.9);
        }
        if (!announcedBorderStop) {
            announce("Saí da fronteira do meu país; estou voltando a pé para continuar meu trabalho.");
            announcedBorderStop = true;
        }
        return false;
    }

    private void announceResumedProfession(String message) {
        if (professionResumeAnnounced) return;
        professionResumeAnnounced = true;
        announce(message);
    }

    private boolean hasActiveProfession() {
        return lumberjackEnabled || minerEnabled || storageEnabled || builderEnabled;
    }

    private boolean workNavigationExpected(ServerLevel level) {
        if (storageEnabled) {
            CivilizationData.Warehouse warehouse = CivilizationData.get(level).warehouse(nationId);
            return warehouse != null && distanceToSqr(warehouse.x() + 0.5, warehouse.y(), warehouse.z() + 0.5) > 196.0;
        }
        if (minerEnabled) return minerJob.needsNavigation(this, level);
        if (builderEnabled) return builderJob.isActive() && !getNavigation().isDone();
        return lumberjackEnabled && lumberjackTarget != null
            && distanceToSqr(lumberjackTarget.getX() + 0.5, getY(), lumberjackTarget.getZ() + 0.5) > 9.0;
    }

    public boolean isInsideNation(BlockPos position) {
        return belongsToNation(position);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MOVEMENT_SPEED, 0.25);
    }
}
