package com.aicivilization.client;

import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.entity.ModEntities;
import com.aicivilization.network.StorageQueuePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.ResourceLocation;

public class AICivilizationClient implements ClientModInitializer {
    private static final ResourceLocation CITIZEN_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/citizen.png");
    private static final ResourceLocation MINER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/miner.png");
    private static final ResourceLocation LUMBERJACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/lumberjack.png");
    private static final ResourceLocation STORAGER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/storager.png");
    private static final ResourceLocation BUILDER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/builder.png");
    private static final ResourceLocation FARMER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/farmer.png");
    private static final ResourceLocation FISHERMAN_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/fisherman.png");
    private static final ResourceLocation EXPLORER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/explorer.png");
    private static final ResourceLocation GUARD_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/guard.png");
    private static final ResourceLocation HUNTER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "textures/entity/hunter.png");

    private static KeyMapping openStorageQueueKey;

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(ModEntities.CITIZEN, CitizenRenderer::new);
        openStorageQueueKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.ai_civilization.storage_queue", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
        ClientPlayNetworking.registerGlobalReceiver(StorageQueuePayload.TYPE, (payload, context) -> {
            if (!payload.response()) return;
            if (context.client().screen instanceof StorageQueueScreen screen) screen.setQueueText(payload.content());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openStorageQueueKey.consumeClick()) {
                if (client.player == null || client.getConnection() == null) continue;
                client.setScreen(new StorageQueueScreen(() -> ClientPlayNetworking.send(
                    new StorageQueuePayload(false, ""))));
                ClientPlayNetworking.send(new StorageQueuePayload(false, ""));
            }
        });
    }

    private static final java.util.Map<Integer, ResourceLocation> ENTITY_TEXTURES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static ResourceLocation getTextureForProfession(String professionId) {
        if (professionId == null) return CITIZEN_TEXTURE;
        return switch (professionId.toLowerCase()) {
            case "miner" -> MINER_TEXTURE;
            case "lumberjack" -> LUMBERJACK_TEXTURE;
            case "storage", "storager" -> STORAGER_TEXTURE;
            case "builder" -> BUILDER_TEXTURE;
            case "farmer" -> FARMER_TEXTURE;
            case "fisherman" -> FISHERMAN_TEXTURE;
            case "explorer" -> EXPLORER_TEXTURE;
            case "guard" -> GUARD_TEXTURE;
            case "hunter" -> HUNTER_TEXTURE;
            default -> CITIZEN_TEXTURE;
        };
    }

    private static final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, AvatarRenderState, PlayerModel> {
        private CitizenRenderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        }

        @Override
        public AvatarRenderState createRenderState() {
            return new AvatarRenderState();
        }

        @Override
        public void extractRenderState(CitizenEntity citizen, AvatarRenderState state, float partialTick) {
            super.extractRenderState(citizen, state, partialTick);
            state.showHat = true;
            state.showJacket = true;
            state.showLeftPants = true;
            state.showRightPants = true;
            state.showLeftSleeve = true;
            state.showRightSleeve = true;
            ENTITY_TEXTURES.put(citizen.getId(), getTextureForProfession(citizen.getProfessionId()));
        }

        @Override
        public ResourceLocation getTextureLocation(AvatarRenderState state) {
            ResourceLocation tex = ENTITY_TEXTURES.get(state.id);
            return tex != null ? tex : CITIZEN_TEXTURE;
        }
    }
}
