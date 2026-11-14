package com.aicivilization.entity;

import com.aicivilization.AICivilizationMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    private static final ResourceKey<EntityType<?>> CITIZEN_KEY = ResourceKey.create(
        Registries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath(AICivilizationMod.MOD_ID, "citizen")
    );

    public static final EntityType<CitizenEntity> CITIZEN = register(
        CITIZEN_KEY,
        EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(8)
    );

    private ModEntities() {}

    private static <T extends Entity> EntityType<T> register(ResourceKey<EntityType<?>> key, EntityType.Builder<T> builder) {
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }

    public static void initialize() {
        FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createAttributes());
        AICivilizationMod.LOGGER.info("Entidade registrada: {}:citizen", AICivilizationMod.MOD_ID);
    }
}
