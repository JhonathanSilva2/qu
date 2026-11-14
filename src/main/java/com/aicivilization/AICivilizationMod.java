package com.aicivilization;

import com.aicivilization.config.ModConfig;
import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.entity.ModEntities;
import com.aicivilization.storage.StorageJob;
import com.aicivilization.data.CivilizationData;
import com.aicivilization.util.ChatMessages;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import com.mojang.brigadier.arguments.StringArgumentType;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;

public class AICivilizationMod implements ModInitializer {
    public static final String MOD_ID = "ai_civilization";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> HELP_LINES = List.of(
        "/aiciv help — mostra esta ajuda",
        "/aiciv npc — invoca um cidadão",
        "/aiciv list — lista as categorias disponíveis",
        "/aiciv list npc — lista NPCs carregados na dimensão atual; o número é o slot",
        "/aiciv list job — lista profissões disponíveis",
        "/aiciv list nation — lista nações e seus fundadores",
        "/aiciv create civilization <name> — cria a civilização com LumberJack, Storager e Miner",
        "/aiciv nation size — mostra o raio dos territórios em blocos",
        "/aiciv list location — lista locais salvos no mundo",
        "/aiciv list warehouse — mostra os armazéns das nações",
        "/aiciv warehouse set <radius> — define a área do armazém da sua nação",
        "/aiciv warehouse status — mostra o baú de entrada e os pedidos",
        "/aiciv storage queue — lista pedidos abertos de recursos e ferramentas",
        "/aiciv debug status <name|slot> — mostra posição, alvo e motivo provável de bloqueio",
        "/aiciv debug watch <name|slot> — acompanha o estado do cidadão a cada 2 segundos por 60 segundos",
        "/aiciv debug stop — encerra o rastreamento",
        "/aiciv location set <name> — salva sua posição como local",
        "/aiciv location remove <name> — remove um local salvo",
        "/aiciv npc <name|slot> go <location> — envia o cidadão a um local",
        "/aiciv nation — mostra a nação do cidadão mais próximo",
        "/aiciv npc <name|slot> <job> — atribui um job a um NPC",
        "/aiciv npc <name|slot> storage — inicia a profissão Storager",
        "/aiciv npc <name|slot> miner — inicia a profissão Miner",
        "/aiciv npc <name|slot> stop — interrompe o job e remove a torre temporária",
        "/aiciv npc follow — makes the nearest NPC follow you",
        "/aiciv npc unfollow — stops the nearest NPC following you",
        "/aiciv npc select <name|slot> — selects one NPC",
        "/aiciv npc tp [name|slot|selected] — teleports NPCs to you"
    );
    private static final Map<UUID, UUID> SELECTED_NPCS = new ConcurrentHashMap<>();
    private static final Map<UUID, DebugWatch> DEBUG_WATCHES = new ConcurrentHashMap<>();
    private static final List<String> BRAZILIAN_NAMES = List.of(
        "Gabriel", "Lucas", "Mateus", "João", "Pedro", "Rafael", "Arthur", "Davi",
        "Miguel", "Heitor", "Enzo", "Bernardo", "Thiago", "Gustavo", "Felipe", "Caio"
    );

    @Override
    public void onInitialize() {
        ModConfig.load();
        ModEntities.initialize();
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof CitizenEntity citizen && entity.level() instanceof ServerLevel level) {
                Component message = Component.literal(citizen.getDisplayNameText() + " morreu.")
                    .withStyle(ChatFormatting.RED);
                level.getServer().getPlayerList().broadcastSystemMessage(message, false);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("aiciv")
                .then(Commands.literal("help")
                    .executes(context -> showHelp(context.getSource())))
                .then(Commands.literal("list")
                    .executes(context -> showListCategories(context.getSource()))
                    .then(Commands.literal("npc")
                        .executes(context -> listNpcs(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("job")
                        .executes(context -> listJobs(context.getSource())))
                    .then(Commands.literal("jobs")
                        .executes(context -> listJobs(context.getSource())))
                    .then(Commands.literal("nation")
                        .executes(context -> listNations(context.getSource().getPlayerOrException()))
                        .then(Commands.literal("size")
                            .executes(context -> showNationSize(context.getSource()))) )
                    .then(Commands.literal("location")
                        .executes(context -> listLocations(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("warehouse")
                        .executes(context -> listWarehouses(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("npcs")
                        .executes(context -> listNpcs(context.getSource().getPlayerOrException()))) )
                .then(Commands.literal("npc")
                    .executes(context -> spawnNpc(context.getSource().getPlayerOrException()))
                    .then(Commands.argument("npc", StringArgumentType.word())
                        .then(Commands.argument("job", StringArgumentType.word())
                            .executes(context -> assignJob(context.getSource().getPlayerOrException(),
                                getString(context, "npc"), getString(context, "job")))))
                    .then(Commands.literal("follow")
                        .executes(context -> followNearestNpc(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("unfollow")
                        .executes(context -> stopNearestNpc(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("select")
                        .then(Commands.argument("npc", StringArgumentType.word())
                            .executes(context -> selectNpc(context.getSource().getPlayerOrException(), getString(context, "npc")))))
                    .then(Commands.literal("list")
                        .executes(context -> listNpcs(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("seguir")
                        .executes(context -> followNearestNpc(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("parar")
                        .executes(context -> stopNearestNpc(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("listar")
                        .executes(context -> listNpcs(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("selecionar")
                        .then(Commands.argument("nome", StringArgumentType.greedyString())
                            .executes(context -> selectNpc(context.getSource().getPlayerOrException(), getString(context, "nome")))))
                    .then(Commands.literal("tp")
                        .executes(context -> teleportAllNpcs(context.getSource().getPlayerOrException()))
                        .then(Commands.argument("nome", StringArgumentType.greedyString())
                            .executes(context -> teleportNamedNpc(context.getSource().getPlayerOrException(), getString(context, "nome")))))
                    .then(Commands.literal("lenhador")
                        .then(Commands.literal("iniciar")
                            .executes(context -> startLumberjack(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("parar")
                            .executes(context -> stopLumberjack(context.getSource().getPlayerOrException()))))
                    .then(Commands.literal("go")
                        .then(Commands.argument("location", StringArgumentType.word())
                            .executes(context -> sendNpcToLocation(context.getSource().getPlayerOrException(),
                                getString(context, "npc"), getString(context, "location"))))))
                .then(Commands.literal("create")
                    .then(Commands.literal("civilization")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .executes(context -> createCivilization(context.getSource().getPlayerOrException(),
                                getString(context, "name"))))))
                .then(Commands.literal("location")
                    .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(context -> setLocation(context.getSource().getPlayerOrException(), getString(context, "name")))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(context -> removeLocation(context.getSource().getPlayerOrException(), getString(context, "name")))))
                    .then(Commands.literal("list")
                        .executes(context -> listLocations(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("warehouse")
                    .then(Commands.literal("set")
                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 32))
                            .executes(context -> setWarehouse(context.getSource().getPlayerOrException(), getInteger(context, "radius")))))
                    .then(Commands.literal("status")
                        .executes(context -> warehouseStatus(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("storage")
                    .then(Commands.literal("queue")
                        .executes(context -> showStorageQueue(context.getSource().getPlayerOrException()))) )
                .then(Commands.literal("debug")
                    .then(Commands.literal("status")
                        .then(Commands.argument("npc", StringArgumentType.word())
                            .executes(context -> debugNpc(context.getSource().getPlayerOrException(), getString(context, "npc")))))
                    .then(Commands.literal("watch")
                        .then(Commands.argument("npc", StringArgumentType.word())
                            .executes(context -> watchNpc(context.getSource().getPlayerOrException(), getString(context, "npc")))))
                    .then(Commands.literal("stop")
                        .executes(context -> stopDebugWatch(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("nation")
                    .executes(context -> listNations(context.getSource().getPlayerOrException()))
                    .then(Commands.literal("size")
                        .executes(context -> showNationSize(context.getSource()))))));
        LOGGER.info("AI Civilization inicializado para Minecraft 1.21.10");
    }

    private int spawnNpc(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos foundingCenter = player.blockPosition().relative(player.getDirection());
        BlockPos spawnPos = foundingCenter.relative(player.getDirection());
        CitizenEntity npc = ModEntities.CITIZEN.create(level, null, spawnPos, EntitySpawnReason.COMMAND, true, false);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não foi possível criar o NPC."));
            return 0;
        }

        String citizenName = generateBrazilianName(player);
        npc.setPersonalName(citizenName);
        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.assignOrFoundNation(
            npc.getUUID(), citizenName, spawnPos, foundingCenter, level.dimension().location());
        npc.setNation(nation.id(), nation.name());
        if (!level.addFreshEntity(npc)) {
            player.sendSystemMessage(ChatMessages.error("Não foi possível adicionar o NPC ao mundo."));
            return 0;
        }

        if (nation.citizens().size() == 1) {
            BlockPos standardPos = new BlockPos(nation.x(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nation.x(), nation.z()), nation.z());
            if (level.getBlockState(standardPos).isAir()
                && level.getBlockState(standardPos.below()).isFaceSturdy(level, standardPos.below(), net.minecraft.core.Direction.UP)) {
                level.setBlockAndUpdate(standardPos, standardFor(nation.name()).defaultBlockState());
                BlockEntity blockEntity = level.getBlockEntity(standardPos);
                if (blockEntity instanceof BannerBlockEntity banner) {
                    banner.setChanged();
                }
                data.updateNationCenter(nation, standardPos);
                nation = data.getNation(nation.id());
            }
        }

        npc.announce("Olá! Acabei de chegar.");
        if (nation.citizens().size() == 1) {
            npc.announce("Fundei " + nation.name() + ", meu país. O estandarte marca o centro do nosso território, que tem "
                + CivilizationData.NATION_RADIUS_BLOCKS + " blocos de raio.");
        } else {
            npc.announce("Nasci em " + nation.name() + " e agora faço parte deste país.");
        }
        LOGGER.info("NPC {} invocado por {} em {}", citizenName, player.getGameProfile().name(), spawnPos);
        return 1;
    }

    private net.minecraft.world.level.block.Block standardFor(String country) {
        return switch (country) {
            case "Brasil", "México", "Colômbia" -> Blocks.GREEN_BANNER;
            case "Portugal", "Chile", "Japão", "Egito", "Noruega", "Marrocos", "Peru", "Tailândia", "Cuba", "Angola" -> Blocks.RED_BANNER;
            case "Argentina", "Grécia" -> Blocks.LIGHT_BLUE_BANNER;
            case "Canadá", "Austrália", "Suécia", "Nova Zelândia", "Islândia" -> Blocks.BLUE_BANNER;
            case "Índia" -> Blocks.ORANGE_BANNER;
            case "Quênia" -> Blocks.BLACK_BANNER;
            default -> Blocks.WHITE_BANNER;
        };
    }

    private int createCivilization(ServerPlayer player, String name) {
        if (name.isBlank() || name.length() > 32) {
            player.sendSystemMessage(ChatMessages.error("O nome da civilização deve ter de 1 a 32 caracteres."));
            return 0;
        }
        ServerLevel level = player.level();
        BlockPos center = findSafeCivilizationCenter(level, player.blockPosition());
        if (center == null) {
            player.sendSystemMessage(ChatMessages.error("Não encontrei uma área segura para fundar a civilização."));
            return 0;
        }

        List<BlockPos> spawnPositions = findCivilizationSpawnPositions(level, center);
        if (spawnPositions.size() < 3) {
            player.sendSystemMessage(ChatMessages.error("Não encontrei três pontos seguros para os cidadãos."));
            return 0;
        }

        List<CitizenEntity> citizens = new ArrayList<>();
        for (BlockPos spawnPos : spawnPositions) {
            CitizenEntity citizen = ModEntities.CITIZEN.create(level, null, spawnPos,
                EntitySpawnReason.COMMAND, true, false);
            if (citizen == null) {
                citizens.forEach(Entity::discard);
                player.sendSystemMessage(ChatMessages.error("Não foi possível criar os cidadãos."));
                return 0;
            }
            citizen.setPersonalName(generateBrazilianName(player));
            citizens.add(citizen);
        }

        for (CitizenEntity citizen : citizens) {
            if (!level.addFreshEntity(citizen)) {
                citizens.forEach(Entity::discard);
                player.sendSystemMessage(ChatMessages.error("Não foi possível adicionar os cidadãos ao mundo."));
                return 0;
            }
        }

        CivilizationData data = CivilizationData.get(level);
        CivilizationData.Nation nation = data.createNation(name, citizens.getFirst().getUUID(), center,
            level.dimension().location());
        if (nation == null) {
            citizens.forEach(Entity::discard);
            player.sendSystemMessage(ChatMessages.error("Esse nome já existe ou o território se sobrepõe a outra nação."));
            return 0;
        }
        for (int index = 0; index < citizens.size(); index++) {
            CitizenEntity citizen = citizens.get(index);
            if (index > 0) nation = data.assignOrFoundNation(citizen.getUUID(), citizen.getPersonalName(),
                spawnPositions.get(index), center, level.dimension().location());
            citizen.setNation(nation.id(), nation.name());
        }
        level.setBlockAndUpdate(center, standardFor(nation.name()).defaultBlockState());
        BlockEntity bannerEntity = level.getBlockEntity(center);
        if (bannerEntity instanceof BannerBlockEntity banner) banner.setChanged();

        boolean jobsStarted = citizens.get(0).startLumberjack()
            && citizens.get(1).startStorage()
            && citizens.get(2).startMiner();
        if (!jobsStarted) {
            citizens.forEach(Entity::discard);
            level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
            data.removeNation(nation.id());
            player.sendSystemMessage(ChatMessages.error("A civilização não pôde iniciar os três trabalhos; tente em outra área."));
            return 0;
        }
        citizens.getFirst().announce("Fundei " + nation.name() + "! Nossa equipe já está trabalhando.");
        player.sendSystemMessage(ChatMessages.success("Civilization " + nation.name() + " foi criada."));
        player.sendSystemMessage(ChatMessages.info(citizens.get(0).getPersonalName() + " foi criado como LumberJack."));
        player.sendSystemMessage(ChatMessages.info(citizens.get(1).getPersonalName() + " foi criado como Storager."));
        player.sendSystemMessage(ChatMessages.info(citizens.get(2).getPersonalName() + " foi criado como Miner."));
        return 1;
    }

    private BlockPos findSafeCivilizationCenter(ServerLevel level, BlockPos around) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = around.getX() + dx;
                    int z = around.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below())
                        .isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) return pos;
                }
            }
        }
        return null;
    }

    private List<BlockPos> findCivilizationSpawnPositions(ServerLevel level, BlockPos center) {
        List<BlockPos> positions = new ArrayList<>();
        for (int radius = 2; radius <= 5 && positions.size() < 3; radius++) {
            for (int dx = -radius; dx <= radius && positions.size() < 3; dx++) {
                for (int dz = -radius; dz <= radius && positions.size() < 3; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                        || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
                        || !level.getEntitiesOfClass(CitizenEntity.class, new net.minecraft.world.phys.AABB(pos)).isEmpty()) continue;
                    if (positions.stream().noneMatch(existing -> existing.distSqr(pos) < 4.0)) positions.add(pos);
                }
            }
        }
        return positions;
    }

    private int showHelp(CommandSourceStack source) {
        source.sendSuccess(() -> ChatMessages.title("AI Civilization — comandos disponíveis"), false);
        HELP_LINES.forEach(line -> source.sendSuccess(() -> ChatMessages.info(line), false));
        return HELP_LINES.size();
    }

    private int showListCategories(CommandSourceStack source) {
        source.sendSuccess(() -> ChatMessages.info("Available lists: npc, job, nation, location, warehouse"), false);
        source.sendSuccess(() -> ChatMessages.info("Use /aiciv list <npc|job|nation|location|warehouse>."), false);
        return 5;
    }

    private int listJobs(CommandSourceStack source) {
        source.sendSuccess(() -> ChatMessages.info("Available jobs:"), false);
        source.sendSuccess(() -> ChatMessages.info("- lumberjack"), false);
        source.sendSuccess(() -> ChatMessages.info("- storage"), false);
        source.sendSuccess(() -> ChatMessages.info("- miner"), false);
        return 3;
    }

    private int listWarehouses(ServerPlayer player) {
        CivilizationData data = CivilizationData.get((ServerLevel) player.level());
        int listed = 0;
        for (CivilizationData.Nation nation : data.nations()) {
            CivilizationData.Warehouse warehouse = data.warehouse(nation.id());
            if (warehouse == null) continue;
            player.sendSystemMessage(ChatMessages.info(nation.name() + " warehouse: center " + warehouse.x() + ", "
                + warehouse.y() + ", " + warehouse.z() + "; radius " + warehouse.radius()
                + "; inbox " + (warehouse.hasInbox() ? warehouse.inboxX() + ", " + warehouse.inboxY() + ", " + warehouse.inboxZ() : "not built")));
            listed++;
        }
        if (listed == 0) player.sendSystemMessage(ChatMessages.info("No nation warehouses have been configured yet."));
        return listed;
    }

    private int setWarehouse(ServerPlayer player, int radius) {
        CivilizationData data = CivilizationData.get((ServerLevel) player.level());
        CivilizationData.Nation nation = data.findNationAt(player.blockPosition(), player.level().dimension().location());
        if (nation == null) {
            player.sendSystemMessage(ChatMessages.error("Stand inside your nation's territory to set its warehouse."));
            return 0;
        }
        data.setWarehouse(nation.id(), player.blockPosition(), radius, player.level().dimension().location());
        player.sendSystemMessage(ChatMessages.success("Warehouse area set for " + nation.name() + " with radius " + radius + " blocks."));
        return 1;
    }

    private int warehouseStatus(ServerPlayer player) {
        CivilizationData data = CivilizationData.get((ServerLevel) player.level());
        CivilizationData.Nation nation = data.findNationAt(player.blockPosition(), player.level().dimension().location());
        if (nation == null) {
            player.sendSystemMessage(ChatMessages.error("You are outside a nation; no warehouse is available here."));
            return 0;
        }
        CivilizationData.Warehouse warehouse = data.warehouse(nation.id());
        if (warehouse == null) {
            player.sendSystemMessage(ChatMessages.info(nation.name() + " has no warehouse yet. Use /aiciv warehouse set <radius>."));
            return 0;
        }
        int openRequests = data.openStorageRequests(nation.id()).size();
        player.sendSystemMessage(ChatMessages.info(nation.name() + " warehouse radius: " + warehouse.radius()
            + "; inbox: " + (warehouse.hasInbox() ? warehouse.inboxX() + ", " + warehouse.inboxY() + ", " + warehouse.inboxZ() : "awaiting Storager")
            + "; open requests: " + openRequests));
        return openRequests;
    }

    private int showStorageQueue(ServerPlayer player) {
        CivilizationData data = CivilizationData.get((ServerLevel) player.level());
        CivilizationData.Nation nation = data.findNationAt(player.blockPosition(), player.level().dimension().location());
        if (nation == null) {
            player.sendSystemMessage(ChatMessages.error("You are outside a nation; there is no local storage queue."));
            return 0;
        }
        List<CivilizationData.StorageRequest> requests = data.openStorageRequests(nation.id());
        if (requests.isEmpty()) {
            player.sendSystemMessage(ChatMessages.info(nation.name() + " storage queue is empty."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.title(nation.name() + " — storage queue"));
        for (CivilizationData.StorageRequest request : requests) {
            String requester = getCitizens(player).stream().filter(citizen -> citizen.getUUID().equals(request.requesterId()))
                .map(CitizenEntity::getDisplayNameText).findFirst().orElse("citizen not loaded");
            String requestedItem = request.itemId().equals(StorageJob.BEST_AXE_REQUEST_ID)
                ? "the best available axe" : request.itemId().equals(StorageJob.BEST_PICKAXE_REQUEST_ID)
                    ? "the best available pickaxe" : request.itemId();
            player.sendSystemMessage(ChatMessages.info(requester + " requests " + request.count() + " x " + requestedItem));
        }
        return requests.size();
    }

    private int debugNpc(ServerPlayer player, String identifier) {
        CitizenEntity npc = findNpcByIdentifier(player, identifier);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Cidadão não encontrado ou não carregado nesta dimensão. Use /aiciv list npc."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.title("Debug — " + npc.getPersonalName()));
        player.sendSystemMessage(ChatMessages.info(npc.debugSnapshot((ServerLevel) player.level())));
        return 1;
    }

    private int watchNpc(ServerPlayer player, String identifier) {
        CitizenEntity npc = findNpcByIdentifier(player, identifier);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Cidadão não encontrado ou não carregado nesta dimensão. Use /aiciv list npc."));
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();
        DEBUG_WATCHES.put(player.getUUID(), new DebugWatch(npc.getUUID(), now + 40, now + 1200));
        player.sendSystemMessage(ChatMessages.success("Rastreamento de " + npc.getPersonalName()
            + " iniciado: estado a cada 2 segundos por 60 segundos. Use /aiciv debug stop para encerrar."));
        return 1;
    }

    private int stopDebugWatch(ServerPlayer player) {
        DebugWatch removed = DEBUG_WATCHES.remove(player.getUUID());
        if (removed == null) {
            player.sendSystemMessage(ChatMessages.info("Você não tem um rastreamento ativo."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.success("Rastreamento encerrado."));
        return 1;
    }

    public static void tickDebugWatches(CitizenEntity citizen, ServerLevel level) {
        long now = level.getGameTime();
        for (Map.Entry<UUID, DebugWatch> entry : DEBUG_WATCHES.entrySet()) {
            DebugWatch watch = entry.getValue();
            if (!watch.citizenId().equals(citizen.getUUID())) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                DEBUG_WATCHES.remove(entry.getKey());
                continue;
            }
            if (now >= watch.expiresAt()) {
                DEBUG_WATCHES.remove(entry.getKey());
                player.sendSystemMessage(ChatMessages.info("Rastreamento de " + citizen.getPersonalName() + " expirou."));
            } else if (now >= watch.nextAt()) {
                player.sendSystemMessage(ChatMessages.info(citizen.debugSnapshot(level)));
                DEBUG_WATCHES.replace(entry.getKey(), watch, new DebugWatch(watch.citizenId(), now + 40, watch.expiresAt()));
            }
        }
    }

    private record DebugWatch(UUID citizenId, long nextAt, long expiresAt) {}

    private int listNations(ServerPlayer player) {
        CivilizationData data = CivilizationData.get((ServerLevel) player.level());
        List<CitizenEntity> citizens = getCitizens(player);
        List<CivilizationData.Nation> nations = data.nations();
        if (nations.isEmpty()) {
            player.sendSystemMessage(ChatMessages.info("No nations have been founded in this world."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.info("Nations (territory radius: " + CivilizationData.NATION_RADIUS_BLOCKS + " blocks):"));
        nations.forEach(nation -> {
            String founder = nation.citizens().isEmpty() ? "unknown" : findCitizenName(citizens, nation.citizens().get(0));
            player.sendSystemMessage(ChatMessages.info(nation.name() + " — standard/center: " + nation.x() + ", " + nation.y() + ", " + nation.z()
                + " — dimension: " + nation.dimension() + " — founder: " + founder
                + " — citizens: " + nation.citizens().size()));
        });
        return nations.size();
    }

    private int showNationSize(CommandSourceStack source) {
        source.sendSuccess(() -> ChatMessages.info("Each nation has a circular territory with a radius of "
            + CivilizationData.NATION_RADIUS_BLOCKS + " blocks, centered on its founding point."), false);
        return CivilizationData.NATION_RADIUS_BLOCKS;
    }

    private String findCitizenName(List<CitizenEntity> citizens, UUID id) {
        return citizens.stream().filter(citizen -> citizen.getUUID().equals(id)).map(CitizenEntity::getPersonalName)
            .findFirst().orElse("citizen not loaded");
    }

    private int setLocation(ServerPlayer player, String name) {
        CivilizationData.SavedLocation location = CivilizationData.get((ServerLevel) player.level())
            .setLocation(name, player.blockPosition(), player.level().dimension());
        player.sendSystemMessage(ChatMessages.success("Saved location " + location.name() + " at "
            + location.x() + ", " + location.y() + ", " + location.z() + "."));
        return 1;
    }

    private int removeLocation(ServerPlayer player, String name) {
        boolean removed = CivilizationData.get((ServerLevel) player.level()).removeLocation(name);
        player.sendSystemMessage(removed ? ChatMessages.success("Location removed: " + name)
            : ChatMessages.error("Location not found: " + name));
        return removed ? 1 : 0;
    }

    private int listLocations(ServerPlayer player) {
        List<CivilizationData.SavedLocation> locations = CivilizationData.get((ServerLevel) player.level()).locations();
        if (locations.isEmpty()) {
            player.sendSystemMessage(ChatMessages.info("No named locations saved in this world."));
            return 0;
        }
        for (CivilizationData.SavedLocation location : locations) {
            player.sendSystemMessage(ChatMessages.info(location.name() + " — " + location.x() + ", " + location.y()
                + ", " + location.z() + " (" + location.dimension() + ")"));
        }
        return locations.size();
    }

    private int sendNpcToLocation(ServerPlayer player, String identifier, String locationName) {
        CitizenEntity npc = findNpcByIdentifier(player, identifier);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Citizen not found. Use /aiciv list npc."));
            return 0;
        }
        CivilizationData.SavedLocation location = CivilizationData.get((ServerLevel) player.level()).getLocation(locationName);
        if (location == null) {
            player.sendSystemMessage(ChatMessages.error("Location not found. Use /aiciv list location."));
            return 0;
        }
        if (!location.dimension().equals(player.level().dimension().location())) {
            player.sendSystemMessage(ChatMessages.error("That location is in another dimension; citizens cannot travel between dimensions yet."));
            return 0;
        }
        if (!npc.travelTo(location.blockPos(), location.name())) {
            player.sendSystemMessage(ChatMessages.error("The destination is outside this citizen's nation, or the citizen has no valid nation."));
            return 0;
        }
        return 1;
    }

    private int assignJob(ServerPlayer player, String identifier, String job) {
        CitizenEntity npc = findNpcByIdentifier(player, identifier);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("NPC not found. Use /aiciv list npc to see names and slots."));
            return 0;
        }
        if (job.equalsIgnoreCase("stop")) {
            npc.stopProfession();
            return 1;
        }
        if (job.equalsIgnoreCase("lumberjack")) {
            npc.startLumberjack();
            return 1;
        }
        if (job.equalsIgnoreCase("storage") || job.equalsIgnoreCase("storager")) {
            if (npc.startStorage()) return 1;
            player.sendSystemMessage(ChatMessages.error("Could not assign Storager: the citizen needs a nation, a safe site, and an unassigned Storager slot."));
            return 0;
        }
        if (job.equalsIgnoreCase("miner") || job.equalsIgnoreCase("mining")) {
            if (npc.startMiner()) return 1;
            player.sendSystemMessage(ChatMessages.error("That citizen needs a nation before becoming a Miner."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.error("Unknown job. Available jobs: lumberjack, storage, miner. Use /aiciv list job."));
        return 0;
    }

    private int followNearestNpc(ServerPlayer player) {
        CitizenEntity npc = findNearestNpc(player);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não há cidadãos próximos. Use /aiciv npc para invocar um."));
            return 0;
        }

        npc.follow(player);
        player.sendSystemMessage(ChatMessages.success("O cidadão está seguindo você."));
        return 1;
    }

    private int stopNearestNpc(ServerPlayer player) {
        CitizenEntity npc = findNearestNpc(player);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não há cidadãos próximos."));
            return 0;
        }

        npc.stopFollowing();
        player.sendSystemMessage(ChatMessages.success("O cidadão parou de seguir você."));
        return 1;
    }

    private int startLumberjack(ServerPlayer player) {
        CitizenEntity npc = findNearestNpc(player);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não há cidadãos próximos. Use /aiciv npc para invocar um."));
            return 0;
        }
        npc.startLumberjack();
        player.sendSystemMessage(ChatMessages.success(npc.getDisplayNameText() + " começou a procurar árvores próximas."));
        return 1;
    }

    private int stopLumberjack(ServerPlayer player) {
        CitizenEntity npc = findNearestNpc(player);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não há cidadãos próximos."));
            return 0;
        }
        npc.stopLumberjack();
        player.sendSystemMessage(ChatMessages.success(npc.getDisplayNameText() + " parou; a torre temporária foi removida."));
        return 1;
    }

    private CitizenEntity findNearestNpc(Player player) {
        return player.level().getEntitiesOfClass(CitizenEntity.class, player.getBoundingBox().inflate(32.0))
            .stream()
            .min(Comparator.comparingDouble(player::distanceToSqr))
            .orElse(null);
    }

    private int listNpcs(ServerPlayer player) {
        List<CitizenEntity> citizens = getCitizens(player);
        if (citizens.isEmpty()) {
            player.sendSystemMessage(ChatMessages.error("Nenhum cidadão carregado neste mundo."));
            return 0;
        }
        player.sendSystemMessage(ChatMessages.info("Cidadãos carregados:"));
        for (int i = 0; i < citizens.size(); i++) {
            CitizenEntity npc = citizens.get(i);
            int slot = i + 1;
            player.sendSystemMessage(ChatMessages.info(slot + " - " + npc.getName().getString()
                + " [" + npc.blockPosition().getX() + ", " + npc.blockPosition().getY() + ", " + npc.blockPosition().getZ() + "]"));
        }
        return citizens.size();
    }

    private int selectNpc(ServerPlayer player, String name) {
        CitizenEntity npc = findNpcByIdentifier(player, name);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não encontrei esse cidadão carregado. Use /aiciv npc listar."));
            return 0;
        }
        SELECTED_NPCS.put(player.getUUID(), npc.getUUID());
        player.sendSystemMessage(ChatMessages.success("Selecionado: " + npc.getDisplayNameText()));
        return 1;
    }

    private int teleportAllNpcs(ServerPlayer player) {
        List<CitizenEntity> citizens = getCitizens(player);
        citizens.forEach(npc -> npc.teleportToPlayer(player));
        player.sendSystemMessage(ChatMessages.success(citizens.size() + " cidadão(ãos) teletransportado(s) para você."));
        return citizens.size();
    }

    private int teleportNamedNpc(ServerPlayer player, String name) {
        if (name.equalsIgnoreCase("selected") || name.equalsIgnoreCase("selecionado")) {
            UUID selectedId = SELECTED_NPCS.get(player.getUUID());
            CitizenEntity selected = selectedId == null ? null : getCitizens(player).stream()
                .filter(npc -> npc.getUUID().equals(selectedId)).findFirst().orElse(null);
            if (selected == null) {
                player.sendSystemMessage(ChatMessages.error("Nenhum cidadão selecionado e carregado. Use /aiciv npc selecionar <nome>."));
                return 0;
            }
            selected.teleportToPlayer(player);
            player.sendSystemMessage(ChatMessages.success(selected.getDisplayNameText() + " teletransportado para você."));
            return 1;
        }
        CitizenEntity npc = findNpcByIdentifier(player, name);
        if (npc == null) {
            player.sendSystemMessage(ChatMessages.error("Não encontrei esse cidadão carregado. Use /aiciv npc listar."));
            return 0;
        }
        npc.teleportToPlayer(player);
        player.sendSystemMessage(ChatMessages.success(npc.getDisplayNameText() + " teletransportado para você."));
        return 1;
    }

    private List<CitizenEntity> getCitizens(ServerPlayer player) {
        List<CitizenEntity> citizens = new ArrayList<>();
        for (Entity entity : ((ServerLevel) player.level()).getAllEntities()) {
            if (entity instanceof CitizenEntity citizen) {
                citizens.add(citizen);
            }
        }
        citizens.sort(Comparator.comparing(npc -> npc.getName().getString()));
        return citizens;
    }

    private CitizenEntity findNpcByName(ServerPlayer player, String name) {
        return getCitizens(player).stream()
            .filter(npc -> npc.getName().getString().equalsIgnoreCase(name))
            .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private CitizenEntity findNpcByIdentifier(ServerPlayer player, String identifier) {
        List<CitizenEntity> citizens = getCitizens(player);
        try {
            int slot = Integer.parseInt(identifier);
            if (slot > 0 && slot <= citizens.size()) return citizens.get(slot - 1);
        } catch (NumberFormatException ignored) {
            // The identifier is a generated NPC name, not a numeric slot.
        }
        return citizens.stream().filter(npc -> npc.getPersonalName().equalsIgnoreCase(identifier)
                || npc.getDisplayNameText().equalsIgnoreCase(identifier))
            .findFirst().orElse(null);
    }

    private String generateBrazilianName(ServerPlayer player) {
        List<String> available = new ArrayList<>(BRAZILIAN_NAMES);
        getCitizens(player).stream().map(CitizenEntity::getPersonalName).forEach(available::remove);
        if (available.isEmpty()) {
            return "Gabriel" + (getCitizens(player).size() + 1);
        }
        return available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }
}
