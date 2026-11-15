package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.data.CivilizationData;
import com.aicivilization.profession.LumberjackJob;
import com.aicivilization.profession.BuilderJob;
import com.aicivilization.profession.MinerJob;
import com.aicivilization.profession.ProfessionJob;
import com.aicivilization.storage.StorageJob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Dedicated autonomous citizen entity.
 * Jobs and professions are fully modularized under com.aicivilization.profession.
 */
public class CitizenEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_PROFESSION =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);

    private final SimpleContainer npcInventory = new SimpleContainer(27);

    // Modular jobs
    private final LumberjackJob lumberjackJob = new LumberjackJob();
    private final StorageJob storageJob = new StorageJob();
    private final MinerJob minerJob = new MinerJob();
    private final BuilderJob builderJob = new BuilderJob();
    private ProfessionJob activeJob;

    private Player followTarget;
    private String personalName = "Cidadão";
    private String professionLabel = "Citizen";
    private UUID nationId;
    private String nationName = "";
    private BlockPos travelTarget;
    private String travelLocationName = "";
    private BlockPos lastPositionInsideNation;
    private boolean announcedBorderStop;
    private boolean professionResumeAnnounced;

    private int workStuckTicks;
    private int workStuckNoticeCooldown;
    private double workLastX;
    private double workLastY;
    private double workLastZ;

    public CitizenEntity(EntityType<? extends CitizenEntity> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
        this.getNavigation().setCanFloat(true);
        this.setPathfindingMalus(PathType.WATER, 0.0f);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0f);
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new FollowPlayerGoal());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PROFESSION, "");
    }

    public String getProfessionId() {
        return this.entityData.get(DATA_PROFESSION);
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
        if (activeJob != null && this.level() instanceof ServerLevel serverLevel) {
            stopJob(serverLevel, false);
            announce("Cancelei o trabalho. Agora vou seguir você.");
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
    public String getPersonalName() { return personalName; }
    public SimpleContainer getNpcInventory() { return npcInventory; }
    public ProfessionJob getActiveJob() { return activeJob; }

    public boolean isLumberjackEnabled() {
        return activeJob == lumberjackJob;
    }

    public boolean isStorageEnabled() {
        return activeJob == storageJob;
    }

    public boolean isMinerEnabled() {
        return activeJob == minerJob;
    }

    public MinerJob getMinerJob() {
        return minerJob;
    }

    public boolean isBuilderEnabled() { return activeJob == builderJob; }

    public boolean startLumberjack() {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level)) return false;
        return setJob(lumberjackJob, level);
    }

    public void stopLumberjack() {
        if (this.level() instanceof ServerLevel level && activeJob == lumberjackJob) {
            stopJob(level, true);
        }
    }

    public boolean startStorage() {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level) || nationId == null) return false;
        return setJob(storageJob, level);
    }

    public void stopStorage() {
        if (this.level() instanceof ServerLevel level && activeJob == storageJob) {
            stopJob(level, true);
        }
    }

    public boolean startMiner() {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level) || nationId == null) return false;
        return setJob(minerJob, level);
    }

    public void stopMiner() {
        if (this.level() instanceof ServerLevel level && activeJob == minerJob) {
            stopJob(level, true);
        }
    }

    public boolean startBuilder() {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level) || nationId == null) return false;
        if (!setJob(builderJob, level)) return false;
        announce("Vou preparar o terreno da warehouse quando o Storager solicitar e abrir os caminhos da vila.");
        return true;
    }

    public void stopProfession() {
        if (this.level() instanceof ServerLevel level) {
            stopJob(level, true);
        }
    }

    public boolean setJob(ProfessionJob job, ServerLevel level) {
        if (activeJob != null) {
            activeJob.stop(this, level, false);
        }
        followTarget = null;
        travelTarget = null;
        travelLocationName = "";
        this.getNavigation().stop();
        if (!job.start(this, level)) {
            activeJob = null;
            professionLabel = "Citizen";
            this.entityData.set(DATA_PROFESSION, "");
            refreshDisplayName();
            return false;
        }
        activeJob = job;
        professionLabel = job.getLabel();
        professionResumeAnnounced = true;
        this.entityData.set(DATA_PROFESSION, job.getId());
        refreshDisplayName();
        return true;
    }

    public void stopJob(ServerLevel level, boolean announce) {
        if (activeJob != null) {
            activeJob.stop(this, level, announce);
            activeJob = null;
            professionLabel = "Citizen";
            this.entityData.set(DATA_PROFESSION, "");
            refreshDisplayName();
        }
    }

    public boolean travelTo(BlockPos target, String locationName) {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel level) || nationId == null || !belongsToNation(target)) return false;
        stopJob(level, false);
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

    public String currentWorkStatus(ServerLevel level) {
        if (activeJob != null) return activeJob.workStatus(this, level);
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

    public void teleportToNationCenter(ServerLevel level) {
        if (nationId == null) return;
        CivilizationData.Nation nation = CivilizationData.get(level).getNation(nationId);
        if (nation == null) return;
        int targetX = nation.x();
        int targetZ = nation.z();
        int targetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
        this.teleportTo(targetX + 0.5, targetY, targetZ + 0.5);
        this.getNavigation().stop();
    }

    public String debugSnapshot(ServerLevel level) {
        String jobState = activeJob != null ? activeJob.debugStatus(this, level) : "no active profession";
        return "[AI DEBUG] " + personalName + " profession=" + professionLabel + " pos=" + blockPosition().toShortString()
            + " nav=" + (getNavigation().isDone() ? "done" : "moving") + " " + jobState;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel && nationId != null) {
            if (CivilizationData.get(serverLevel).getNation(nationId) == null) {
                stopProfession();
                discard();
                return;
            }
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

        // Autonomous Navigation Watchdog: if stuck trying to reach a work target for 30s (600 ticks),
        // teleport to the nation center banner as a last resort and safely resume.
        if (hasActiveProfession() && workNavigationExpected(level) && !getNavigation().isDone() && !this.swinging) {
            if (distanceToSqr(workLastX, workLastY, workLastZ) < 0.04) workStuckTicks++;
            else workStuckTicks = 0;
            workLastX = getX();
            workLastY = getY();
            workLastZ = getZ();
            if (workStuckTicks >= 600) {
                workStuckTicks = 0;
                teleportToNationCenter(level);
                getNavigation().stop();
                if (workStuckNoticeCooldown <= 0) {
                    announce("Fiquei bloqueado por muito tempo; fui levado ao centro da cidade como último recurso.");
                    workStuckNoticeCooldown = 600;
                }
            }
        } else {
            workStuckTicks = 0;
            workLastX = getX();
            workLastY = getY();
            workLastZ = getZ();
        }

        if (activeJob != null) {
            announceResumedProfession("Retomei o trabalho como " + activeJob.getLabel() + ".");
            activeJob.tick(this, level);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("PersonalName", personalName);
        tag.putString("ProfessionLabel", professionLabel);
        tag.putString("ActiveJobId", activeJob != null ? activeJob.getId() : "none");
        lumberjackJob.save(tag);
        storageJob.save(tag);
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
        lumberjackJob.load(tag);
        storageJob.load(tag);
        minerJob.load(tag);
        builderJob.load(tag);

        String activeJobId = tag.getStringOr("ActiveJobId", "");
        if (activeJobId.isEmpty() || "none".equals(activeJobId)) {
            if (tag.getBooleanOr("StorageEnabled", false)) activeJobId = StorageJob.ID;
            else if (tag.getBooleanOr("MinerEnabled", false)) activeJobId = MinerJob.ID;
            else if (tag.getBooleanOr("LumberjackEnabled", false)) activeJobId = LumberjackJob.ID;
            else if (tag.getBooleanOr("BuilderEnabled", false) || "Builder".equals(tag.getStringOr("ProfessionLabel", "")))
                activeJobId = BuilderJob.ID;
        }

        if (LumberjackJob.ID.equals(activeJobId)) {
            activeJob = lumberjackJob;
            professionLabel = LumberjackJob.LABEL;
        } else if (StorageJob.ID.equals(activeJobId)) {
            activeJob = storageJob;
            professionLabel = StorageJob.LABEL;
        } else if (MinerJob.ID.equals(activeJobId)) {
            activeJob = minerJob;
            professionLabel = MinerJob.LABEL;
        } else if (BuilderJob.ID.equals(activeJobId)) {
            activeJob = builderJob;
            professionLabel = BuilderJob.LABEL;
        } else {
            activeJob = null;
            professionLabel = tag.getStringOr("ProfessionLabel", "Citizen");
        }
        this.entityData.set(DATA_PROFESSION, activeJob != null ? activeJob.getId() : "");

        nationId = tag.read("NationId", UUIDUtil.CODEC).orElse(null);
        nationName = tag.getStringOr("NationName", "");
        lastPositionInsideNation = tag.read("LastPositionInsideNation", BlockPos.CODEC).orElse(null);
        professionResumeAnnounced = false;

        for (int i = 0; i < npcInventory.getContainerSize(); i++) {
            final int slot = i;
            tag.read("CitizenItem" + slot, ItemStack.CODEC).ifPresent(stack -> npcInventory.setItem(slot, stack));
        }
        refreshDisplayName();
    }

    private void refreshDisplayName() {
        this.setCustomName(Component.literal(getDisplayNameText()).withStyle(ChatFormatting.GOLD).withStyle(ChatFormatting.BOLD));
        this.setCustomNameVisible(true);
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

    public boolean belongsToNation(BlockPos position) {
        if (!(this.level() instanceof ServerLevel serverLevel) || nationId == null) return false;
        CivilizationData.Nation ownNation = CivilizationData.get(serverLevel).getNation(nationId);
        if (ownNation == null || !ownNation.dimension().equals(serverLevel.dimension().location())) return false;
        CivilizationData.Nation territory = CivilizationData.get(serverLevel).findNationAt(position, serverLevel.dimension().location());
        return territory != null && territory.id().equals(nationId);
    }

    public boolean isInsideNation(BlockPos position) { return belongsToNation(position); }

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
        double dx = this.getX() - (returnPosition.getX() + 0.5);
        double dz = this.getZ() - (returnPosition.getZ() + 0.5);
        double horizontalDistSqr = dx * dx + dz * dz;
        if (horizontalDistSqr > 48.0 * 48.0) {
            // Vanilla pathfinding cannot resolve paths longer than 32-48 blocks. Teleport safely back into nation bounds.
            teleportToNationCenter(level);
            announce("Estava muito longe do meu país; fui trazido de volta para dentro da fronteira.");
            return true;
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
        return activeJob != null;
    }

    private boolean workNavigationExpected(ServerLevel level) {
        return activeJob != null && activeJob.needsNavigation(this, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MOVEMENT_SPEED, 0.25);
    }
}
