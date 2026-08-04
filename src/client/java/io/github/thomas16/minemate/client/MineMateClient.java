package io.github.thomas16.minemate.client;

import io.github.thomas16.minemate.client.debug.CompanionDebugScreen;
import io.github.thomas16.minemate.client.render.CompanionRenderer;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshotPayload;
import io.github.thomas16.minemate.entity.MineMateEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class MineMateClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(MineMateEntities.COMPANION, CompanionRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(CompanionDebugSnapshotPayload.TYPE,
				(payload, context) -> {
					if (payload.openScreen()) {
						context.client().setScreen(new CompanionDebugScreen(payload));
					} else if (context.client().screen instanceof CompanionDebugScreen screen
							&& screen.matches(payload.companionId())) {
						screen.receive(payload);
					}
				});
	}
}
