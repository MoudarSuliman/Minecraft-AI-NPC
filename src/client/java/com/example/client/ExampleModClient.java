package com.example.client;

import com.example.ai.trade.PriceConfig;
import com.example.ai.trade.TradePriceStore;
import com.example.client.screen.TradePricesScreen;
import com.example.network.OpenTradePricesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;

public class ExampleModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(OpenTradePricesPayload.TYPE, (payload, context) -> {
			Map<String, PriceConfig> prices = TradePriceStore.fromJson(payload.pricesJson());
			context.client().execute(() ->
					context.client().setScreen(new TradePricesScreen(prices)));
		});
	}
}
