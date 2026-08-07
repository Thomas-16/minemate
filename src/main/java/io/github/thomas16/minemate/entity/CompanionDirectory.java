package io.github.thomas16.minemate.entity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-level authority on who owns which companion and where it last was. Identity and
 * last-known location persist as {@link SavedData} in the overworld's storage; a runtime cache
 * tracks the loaded entity, so name-addressed chat, stop, status, and spawn exclusivity work at
 * any distance and across dimensions -- never through a proximity query.
 *
 * <p>An unloaded companion is deliberately not force-loaded: callers get its last-known entry and
 * give the owner a deterministic line instead of spending a model call.
 */
public final class CompanionDirectory extends SavedData {

    /** One owner's companion: identity plus where it was last seen. */
    public record Entry(UUID companionId, UUID ownerId, String companionName,
            ResourceKey<Level> dimension, BlockPos lastKnownPos) {
    }

    private static final String STORAGE_ID = "minemate_companions";
    private static final SavedData.Factory<CompanionDirectory> FACTORY =
            new SavedData.Factory<>(CompanionDirectory::new, CompanionDirectory::load, null);
    /** Loaded entities by companion UUID; server-global, because dimension change reloads. */
    private static final Map<UUID, CompanionEntity> LOADED = new ConcurrentHashMap<>();

    private final Map<UUID, Entry> byOwner = new HashMap<>();

    public static CompanionDirectory of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_ID);
    }

    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof CompanionEntity companion) {
                LOADED.put(companion.getUUID(), companion);
                of(level.getServer()).upsert(companion);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof CompanionEntity companion) {
                LOADED.remove(companion.getUUID());
                if (companion.getRemovalReason() != null
                        && companion.getRemovalReason().shouldDestroy()) {
                    // Dismissed or dead: the companion no longer exists anywhere.
                    of(level.getServer()).removeEntity(companion);
                } else {
                    // Chunk unload or dimension change: remember where it was last seen.
                    of(level.getServer()).upsert(companion);
                }
            }
        });
        // Dedicated-server hygiene, and protection for the singleplayer world-reopen case: a
        // stale entity reference must not outlive the server that owned it.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
    }

    /** The owner's companion entity, when one is loaded anywhere on the server. */
    public Optional<CompanionEntity> loadedCompanionOf(UUID ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        // A live scan, not a lookup by stored owner: ownership can be reassigned on a loaded
        // entity, and with at most a handful of companions per server the scan is free.
        for (CompanionEntity companion : LOADED.values()) {
            if (!companion.isRemoved()
                    && companion.getOwnerUuid().map(ownerId::equals).orElse(false)) {
                return Optional.of(companion);
            }
        }
        return Optional.empty();
    }

    /** The owner's persisted companion record, loaded or not. */
    public Optional<Entry> entryOf(UUID ownerId) {
        return Optional.ofNullable(byOwner.get(ownerId));
    }

    /** Whether this owner has a companion at all -- loaded, unloaded, or in another dimension. */
    public boolean hasCompanion(UUID ownerId) {
        return loadedCompanionOf(ownerId).isPresent() || byOwner.containsKey(ownerId);
    }

    /** Records or refreshes the companion's identity and last-known location. */
    public void upsert(CompanionEntity companion) {
        UUID ownerId = companion.getOwnerUuid().orElse(null);
        if (ownerId == null || !(companion.level() instanceof ServerLevel level)) {
            return;
        }
        // An ownership reassignment must not leave the previous owner's mapping behind.
        byOwner.values().removeIf(entry -> entry.companionId().equals(companion.getUUID()));
        byOwner.put(ownerId, new Entry(companion.getUUID(), ownerId,
                companion.getCompanionName().orElse("Companion"),
                level.dimension(), companion.blockPosition()));
        setDirty();
    }

    /** Deletes the record when the companion is destroyed (dismissed or dead). */
    public void removeEntity(CompanionEntity companion) {
        if (byOwner.values().removeIf(entry -> entry.companionId().equals(companion.getUUID()))) {
            setDirty();
        }
        LOADED.remove(companion.getUUID());
    }

    /** Test seam: records an entry directly, as if the companion had unloaded elsewhere. */
    public void putEntry(Entry entry) {
        byOwner.put(entry.ownerId(), entry);
        setDirty();
    }

    /** Test seam: forgets a loaded entity without touching its entry, as a chunk unload would. */
    public static void uncache(UUID companionId) {
        LOADED.remove(companionId);
    }

    /** Test seam: drops every persisted entry, so scenarios cannot leak state into each other. */
    public void clearEntries() {
        if (!byOwner.isEmpty()) {
            byOwner.clear();
            setDirty();
        }
    }

    /** Test seam: registers a loaded entity directly, as the load event would. */
    public static void cache(CompanionEntity companion) {
        LOADED.put(companion.getUUID(), companion);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : byOwner.values()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("companion", entry.companionId());
            item.putUUID("owner", entry.ownerId());
            item.putString("name", entry.companionName());
            item.putString("dimension", entry.dimension().location().toString());
            item.putLong("pos", entry.lastKnownPos().asLong());
            list.add(item);
        }
        tag.put("companions", list);
        return tag;
    }

    private static CompanionDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        CompanionDirectory directory = new CompanionDirectory();
        ListTag list = tag.getList("companions", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            if (!item.hasUUID("companion") || !item.hasUUID("owner")) {
                continue;
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(item.getString("dimension"));
            if (dimensionId == null) {
                continue;
            }
            Entry entry = new Entry(item.getUUID("companion"), item.getUUID("owner"),
                    item.getString("name"),
                    ResourceKey.create(Registries.DIMENSION, dimensionId),
                    BlockPos.of(item.getLong("pos")));
            directory.byOwner.put(entry.ownerId(), entry);
        }
        return directory;
    }
}
