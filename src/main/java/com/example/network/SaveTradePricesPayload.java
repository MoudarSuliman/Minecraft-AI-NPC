package com.example.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SaveTradePricesPayload(String pricesJson) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SaveTradePricesPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("llm_npc:save_trade_prices"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveTradePricesPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUtf(p.pricesJson()),
                    buf -> new SaveTradePricesPayload(buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
