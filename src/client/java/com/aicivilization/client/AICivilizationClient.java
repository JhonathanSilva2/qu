package com.aicivilization.client;

import com.aicivilization.entity.CitizenEntity;
import com.aicivilization.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
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

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(ModEntities.CITIZEN, CitizenRenderer::new);
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
        }

        @Override
        public ResourceLocation getTextureLocation(AvatarRenderState state) {
            return CITIZEN_TEXTURE;
        }
    }
}
