package io.github.thomas16.minemate.dev;

import com.player2.playerengine.tasks.movement.GetToXZTask;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.entity.MineMateEntities;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Headless smoke test for the PlayerEngine integration, enabled with
 * -Dminemate.autotest=true on a dedicated server (gradlew runServer
 * -Dminemate.autotest=true). Spawns a companion at world spawn, tells it to
 * walk 24 blocks, and logs AUTOTEST PASS/FAIL so CI or a log watcher can
 * assert on it. No-op unless the property is set.
 */
public final class DevAutoTest {

    private static final int WALK_DISTANCE = 24;
    private static final double ARRIVAL_RADIUS = 2.5;
    private static final int TIMEOUT_TICKS = 2400;

    private static CompanionEntity companion;
    private static BlockPos target;
    private static int ticks;
    private static boolean done;

    private DevAutoTest() {
    }

    public static void init() {
        if (!Boolean.getBoolean("minemate.autotest")) {
            return;
        }
        MineMate.LOGGER.info("[autotest] enabled — will spawn companion on server start");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel level = server.overworld();
            BlockPos spawn = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
            companion = new CompanionEntity(MineMateEntities.COMPANION, level);
            companion.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
            level.addFreshEntity(companion);

            target = spawn.offset(WALK_DISTANCE, 0, 0);
            companion.getController().runUserTask(new GetToXZTask(target.getX(), target.getZ()));
            MineMate.LOGGER.info("[autotest] companion spawned at {} — walking to x={} z={}",
                    spawn, target.getX(), target.getZ());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (companion == null || done) {
                return;
            }
            ticks++;
            double dx = companion.getX() - (target.getX() + 0.5);
            double dz = companion.getZ() - (target.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (ticks % 20 == 0) {
                MineMate.LOGGER.info("[autotest] t={}t pos=({}, {}, {}) dist={}",
                        ticks, fmt(companion.getX()), fmt(companion.getY()), fmt(companion.getZ()), fmt(dist));
            }
            if (dist < ARRIVAL_RADIUS) {
                done = true;
                MineMate.LOGGER.info("[autotest] AUTOTEST PASS — arrived in {} ticks (dist={})", ticks, fmt(dist));
                server.halt(false);
            } else if (ticks > TIMEOUT_TICKS) {
                done = true;
                MineMate.LOGGER.error("[autotest] AUTOTEST FAIL — timed out at dist={}", fmt(dist));
                server.halt(false);
            }
        });
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }
}
