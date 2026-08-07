package io.github.thomas16.minemate;

import io.github.thomas16.minemate.command.CompanionCommands;
import io.github.thomas16.minemate.context.ReplanTriggers;
import io.github.thomas16.minemate.debug.CompanionDebugNetworking;
import io.github.thomas16.minemate.dev.DevAutoTest;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.integration.PlayerEngineQuiet;
import io.github.thomas16.minemate.planner.HostedPlanner;
import io.github.thomas16.minemate.planner.PlannerAutonomy;
import io.github.thomas16.minemate.planner.PlannerConfig;
import io.github.thomas16.minemate.planner.Planners;
import io.github.thomas16.minemate.planner.ScriptedPlanner;
import io.github.thomas16.minemate.task.TaskListeners;
import io.github.thomas16.minemate.task.sink.ChatTaskListener;
import io.github.thomas16.minemate.task.sink.JournalTaskListener;
import io.github.thomas16.minemate.task.sink.LoggingTaskListener;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineMate implements ModInitializer {
	public static final String MOD_ID = "minemate";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static PlannerConfig plannerConfig = PlannerConfig.defaults();

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
		// Before DevAutoTest, so the journal has recorded an outcome by the time the harness
		// evaluates it on the same fan-out.
		TaskListeners.register(new JournalTaskListener());
		ReplanTriggers.registerGlobal(PlannerAutonomy::onReplanTrigger);
		ScriptedPlanner.installDemoScripts();
		installPlanner();
		DevAutoTest.init();
		PlayerEngineQuiet.init();
		LOGGER.info("MineMate initialized");
	}

	/**
	 * Installs the hosted planner when one is configured, and otherwise leaves the scripted
	 * stand-in in place. A missing key is an ordinary state, not an error: the mod stays fully
	 * playable on chat commands, and nothing in the autonomy path fires without a hosted planner.
	 */
	/**
	 * The config loaded at startup; defaults until {@link #installPlanner()} has run. Non-planner
	 * settings such as the conversation transcript bounds are read from here even when the hosted
	 * planner itself stays uninstalled.
	 */
	public static PlannerConfig plannerConfig() {
		return plannerConfig;
	}

	private static void installPlanner() {
		if (Boolean.getBoolean("minemate.autotest")) {
			// The headless suite must never be able to reach the network, whatever the developer
			// happens to have in OPENAI_API_KEY. Its planner scenarios install their own hosted
			// planner over a fixture transport instead. Transcript bounds stay at their defaults.
			LOGGER.info("MineMate planner: scripted (autotest run)");
			return;
		}
		PlannerConfig config = PlannerConfig.loadOrCreate();
		plannerConfig = config;
		if (!config.usable()) {
			LOGGER.info("MineMate planner: scripted ({})",
					config.enabled() ? "no API key configured" : "disabled in config");
			return;
		}
		Planners.install(HostedPlanner.of(config));
		LOGGER.info("MineMate planner: {}", config.describe());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Planners.active().close());
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
