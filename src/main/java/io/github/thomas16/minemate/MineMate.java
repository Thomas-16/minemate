package io.github.thomas16.minemate;

import io.github.thomas16.minemate.command.CompanionCommands;
import io.github.thomas16.minemate.debug.CompanionDebugNetworking;
import io.github.thomas16.minemate.dev.DevAutoTest;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.integration.PlayerEngineQuiet;
import io.github.thomas16.minemate.task.TaskListeners;
import io.github.thomas16.minemate.task.sink.ChatTaskListener;
import io.github.thomas16.minemate.task.sink.LoggingTaskListener;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineMate implements ModInitializer {
	public static final String MOD_ID = "minemate";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		MineMateEntities.register();
		CompanionDebugNetworking.init();
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> CompanionCommands.register(dispatcher, registryAccess));
		TaskListeners.register(new LoggingTaskListener());
		TaskListeners.register(new ChatTaskListener());
		DevAutoTest.init();
		PlayerEngineQuiet.init();
		LOGGER.info("MineMate initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
