package com.example.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OpenTradePricesPayload(String pricesJson) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTradePricesPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse("llm_npc:open_trade_prices"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTradePricesPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUtf(p.pricesJson()),
                    buf -> new OpenTradePricesPayload(buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
