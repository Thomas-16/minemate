package io.github.thomas16.minemate.client;

import io.github.thomas16.minemate.client.render.CompanionRenderer;
import io.github.thomas16.minemate.entity.MineMateEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class MineMateClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(MineMateEntities.COMPANION, CompanionRenderer::new);
	}
}