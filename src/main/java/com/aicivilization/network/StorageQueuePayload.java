package com.aicivilization.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StorageQueuePayload(boolean response, String content) implements CustomPacketPayload {
    public static final Type<StorageQueuePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("ai_civilization", "storage_queue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StorageQueuePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeBoolean(payload.response());
            buffer.writeUtf(payload.content(), 32767);
        },
        buffer -> new StorageQueuePayload(buffer.readBoolean(), buffer.readUtf(32767))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
