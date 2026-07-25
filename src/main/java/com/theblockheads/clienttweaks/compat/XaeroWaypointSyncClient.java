package com.theblockheads.clienttweaks.compat;

import com.theblockheads.clienttweaks.ClientTweaksConstants;
import com.theblockheads.clienttweaks.network.WaypointClientEditPayload;
import com.theblockheads.clienttweaks.network.WaypointSyncPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.path.XaeroPath;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class XaeroWaypointSyncClient {
    private static final String DEFAULT_SET = "Blockheads";
    private static final int POLL_INTERVAL_TICKS = 100;
    private static final Map<UUID, TrackedWaypoint> TRACKED = new HashMap<>();
    private static int ticks;

    private XaeroWaypointSyncClient() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(WaypointSyncPayload.TYPE, WaypointSyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WaypointClientEditPayload.TYPE, WaypointClientEditPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(WaypointSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> apply(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks >= POLL_INTERVAL_TICKS) {
                ticks = 0;
                importLocalWaypoints();
                pollTrackedWaypoints();
            }
        });
        ClientTweaksConstants.LOGGER.info("Blockheads Xaero waypoint sync registered");
    }

    private static void apply(WaypointSyncPayload payload) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldState().getAutoRootContainerPath() == null) {
            ClientTweaksConstants.LOGGER.warn("Skipped Blockheads waypoint sync for {} because Xaero is not ready", payload.name());
            return;
        }

        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(payload.dimension()));
        MinimapWorld world = worldFor(session, dimension);
        if (world == null) {
            ClientTweaksConstants.LOGGER.warn("Skipped Blockheads waypoint sync for {} because no Xaero world was available", payload.name());
            return;
        }

        LocatedWaypoint located = locate(world, payload);
        if (payload.delete()) {
            if (located != null) {
                located.set().remove(located.waypoint());
                markChangedAndSave(session, world, payload.name());
            }
            TRACKED.remove(payload.id());
            return;
        }

        Waypoint waypoint;
        WaypointSet targetSet = set(world, cleanSetName(payload.setName()));
        if (located == null) {
            waypoint = new Waypoint(
                payload.x(),
                payload.y(),
                payload.z(),
                safeWaypointText(payload.name(), 32, "Waypoint"),
                safeWaypointText(payload.initials(), 3, "WP"),
                color(payload.color()),
                WaypointPurpose.NORMAL,
                false,
                true
            );
            targetSet.add(waypoint);
        } else {
            waypoint = located.waypoint();
            if (!located.set().getName().equals(targetSet.getName())) {
                located.set().remove(waypoint);
                targetSet.add(waypoint);
            }
            applySnapshot(waypoint, Snapshot.fromPayload(payload));
        }

        TRACKED.put(payload.id(), new TrackedWaypoint(payload.id(), payload.dimension(), targetSet.getName(), waypoint, Snapshot.fromPayload(payload)));
        markChangedAndSave(session, world, payload.name());
    }

    private static void importLocalWaypoints() {
        if (!ClientPlayNetworking.canSend(WaypointClientEditPayload.TYPE)) {
            return;
        }

        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldState().getAutoRootContainerPath() == null) {
            return;
        }

        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) {
            world = session.getWorldManager().getAutoWorld();
        }
        if (world == null || world.getDimId() == null) {
            return;
        }

        String dimension = world.getDimId().identifier().toString();
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (isTracked(waypoint) || shouldSkipImport(waypoint)) {
                    continue;
                }

                String setName = cleanSetName(set.getName());
                UUID id = importedId(dimension, setName, waypoint);
                Snapshot snapshot = Snapshot.fromWaypoint(dimension, setName, waypoint);
                TrackedWaypoint tracked = TRACKED.get(id);
                if (tracked != null && snapshot.equals(tracked.snapshot())) {
                    continue;
                }

                TRACKED.put(id, new TrackedWaypoint(id, dimension, setName, waypoint, snapshot));
                ClientPlayNetworking.send(payload(id, snapshot, false));
            }
        }
    }

    private static void pollTrackedWaypoints() {
        if (TRACKED.isEmpty() || !ClientPlayNetworking.canSend(WaypointClientEditPayload.TYPE)) {
            return;
        }

        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldState().getAutoRootContainerPath() == null) {
            return;
        }

        for (TrackedWaypoint tracked : Map.copyOf(TRACKED).values()) {
            MinimapWorld world = worldFor(session, tracked.snapshot().dimensionKey());
            if (world == null) {
                continue;
            }

            LocatedWaypoint located = locate(world, tracked);
            if (located == null) {
                TRACKED.remove(tracked.id());
                ClientPlayNetworking.send(WaypointClientEditPayload.delete(tracked.id()));
                continue;
            }

            Snapshot current = Snapshot.fromWaypoint(tracked.snapshot().dimension(), located.set().getName(), located.waypoint());
            if (!current.equals(tracked.snapshot())) {
                TRACKED.put(tracked.id(), tracked.with(located.set().getName(), located.waypoint(), current));
                ClientPlayNetworking.send(payload(tracked.id(), current, false));
            }
        }
    }

    private static LocatedWaypoint locate(MinimapWorld world, WaypointSyncPayload payload) {
        TrackedWaypoint tracked = TRACKED.get(payload.id());
        if (tracked != null) {
            LocatedWaypoint located = locate(world, tracked);
            if (located != null) {
                return located;
            }
        }

        LocatedWaypoint exact = findBySnapshot(world, Snapshot.fromPayload(payload));
        return exact != null ? exact : findByName(world, payload.name());
    }

    private static LocatedWaypoint locate(MinimapWorld world, TrackedWaypoint tracked) {
        LocatedWaypoint byIdentity = findByIdentity(world, tracked.waypoint());
        return byIdentity != null ? byIdentity : findBySnapshot(world, tracked.snapshot());
    }

    private static LocatedWaypoint findByIdentity(MinimapWorld world, Waypoint needle) {
        if (needle == null) {
            return null;
        }
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (waypoint == needle) {
                    return new LocatedWaypoint(set, waypoint);
                }
            }
        }
        return null;
    }

    private static LocatedWaypoint findBySnapshot(MinimapWorld world, Snapshot snapshot) {
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (snapshot.matches(waypoint)) {
                    return new LocatedWaypoint(set, waypoint);
                }
            }
        }
        return null;
    }

    private static LocatedWaypoint findByName(MinimapWorld world, String name) {
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (sameName(waypoint, name)) {
                    return new LocatedWaypoint(set, waypoint);
                }
            }
        }
        return null;
    }

    private static void applySnapshot(Waypoint waypoint, Snapshot snapshot) {
        waypoint.setX(snapshot.x());
        waypoint.setY(snapshot.y());
        waypoint.setZ(snapshot.z());
        waypoint.setName(safeWaypointText(snapshot.name(), 32, "Waypoint"));
        waypoint.setInitials(safeWaypointText(snapshot.initials(), 3, "WP"));
        waypoint.setWaypointColor(color(snapshot.color()));
        waypoint.setColor(Math.floorMod(snapshot.color(), WaypointColor.values().length));
        waypoint.setDisabled(snapshot.disabled());
        waypoint.setYIncluded(snapshot.yIncluded());
        waypoint.setVisibilityType(Math.max(0, snapshot.visibilityType()));
        waypoint.setPurpose(purpose(snapshot.purpose()));
    }

    private static MinimapWorld worldFor(MinimapSession session, ResourceKey<Level> dimension) {
        MinimapWorld current = session.getWorldManager().getCurrentWorld();
        if (current != null && dimension.equals(current.getDimId())) {
            return current;
        }

        MinimapWorld autoWorld = session.getWorldManager().getAutoWorld();
        String dimensionDirectory = session.getDimensionHelper().getDimensionDirectoryName(dimension);
        XaeroPath containerPath = session.getWorldState().getAutoRootContainerPath().resolve(dimensionDirectory);
        MinimapWorldContainer container = session.getWorldManager().getWorldContainer(containerPath);
        if (autoWorld != null && container == autoWorld.getContainer()) {
            return autoWorld;
        }

        MinimapWorld world = autoWorld == null ? null : container.getFirstWorldConnectedTo(autoWorld);
        if (world == null) {
            world = container.getFirstWorld();
        }
        if (world == null) {
            world = container.addWorld(session.getWorldStateUpdater().getPotentialWorldNode(dimension, false));
        }
        return world;
    }

    private static WaypointSet set(MinimapWorld world, String setName) {
        WaypointSet set = world.getWaypointSet(setName);
        if (set == null) {
            world.addWaypointSet(setName);
            set = world.getWaypointSet(setName);
        }
        if (set == null) {
            set = world.getCurrentWaypointSet();
        }
        return set;
    }

    private static void markChangedAndSave(MinimapSession session, MinimapWorld world, String name) {
        session.getWaypointSession().setSetChangedTime(System.currentTimeMillis());
        try {
            session.getWorldManagerIO().saveWorld(world);
        } catch (IOException e) {
            ClientTweaksConstants.LOGGER.warn("Could not save Xaero waypoint sync for {}", name, e);
        }
    }

    private static WaypointClientEditPayload payload(UUID id, Snapshot snapshot, boolean delete) {
        return new WaypointClientEditPayload(
            delete,
            id,
            snapshot.name(),
            snapshot.initials(),
            snapshot.dimension(),
            snapshot.x(),
            snapshot.y(),
            snapshot.z(),
            snapshot.color(),
            snapshot.setName(),
            snapshot.disabled(),
            snapshot.yIncluded(),
            snapshot.visibilityType(),
            snapshot.purpose()
        );
    }

    private static boolean isTracked(Waypoint waypoint) {
        for (TrackedWaypoint tracked : TRACKED.values()) {
            if (tracked.waypoint() == waypoint) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldSkipImport(Waypoint waypoint) {
        return waypoint.isTemporary() || waypoint.getPurpose().isDestination() || waypoint.isOneoffDestination();
    }

    private static UUID importedId(String dimension, String setName, Waypoint waypoint) {
        String key = dimension + "|" + setName + "|" + waypoint.getCreatedAt() + "|" + waypoint.getX() + "|" + waypoint.getY() + "|" + waypoint.getZ() + "|" + safeWaypointText(waypoint.getName(), 32, "Waypoint");
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static WaypointColor color(int color) {
        return WaypointColor.fromIndex(Math.floorMod(color, WaypointColor.values().length));
    }

    private static WaypointPurpose purpose(String value) {
        try {
            return WaypointPurpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return WaypointPurpose.NORMAL;
        }
    }

    private static boolean sameName(Waypoint waypoint, String name) {
        return waypoint.getName().trim().equalsIgnoreCase(name.trim());
    }

    private static String cleanSetName(String value) {
        String cleaned = value == null ? "" : value.replace(":", "").trim();
        return cleaned.isEmpty() ? DEFAULT_SET : safeWaypointText(cleaned, 32, DEFAULT_SET);
    }

    private static String safeWaypointText(String value, int maxLength, String fallback) {
        String cleaned = value == null ? "" : value.replace(":", "").replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) {
            cleaned = fallback;
        }
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        String truncated = cleaned.substring(0, maxLength).trim();
        int lastSpace = truncated.lastIndexOf(' ');
        return (lastSpace > 0 ? truncated.substring(0, lastSpace) : truncated).toUpperCase(Locale.ROOT).isBlank() ? fallback : truncated;
    }

    private record LocatedWaypoint(WaypointSet set, Waypoint waypoint) {
    }

    private record TrackedWaypoint(UUID id, String dimension, String setName, Waypoint waypoint, Snapshot snapshot) {
        private TrackedWaypoint with(String setName, Waypoint waypoint, Snapshot snapshot) {
            return new TrackedWaypoint(id, dimension, setName, waypoint, snapshot);
        }
    }

    private record Snapshot(String dimension, String setName, int x, int y, int z, String name, String initials, int color, boolean disabled, boolean yIncluded, int visibilityType, String purpose) {
        private static Snapshot fromPayload(WaypointSyncPayload payload) {
            return new Snapshot(
                payload.dimension(),
                cleanSetName(payload.setName()),
                payload.x(),
                payload.y(),
                payload.z(),
                safeWaypointText(payload.name(), 32, "Waypoint"),
                safeWaypointText(payload.initials(), 3, "WP"),
                Math.floorMod(payload.color(), WaypointColor.values().length),
                payload.disabled(),
                payload.yIncluded(),
                Math.max(0, payload.visibilityType()),
                cleanPurpose(payload.purpose())
            );
        }

        private static Snapshot fromWaypoint(String dimension, String setName, Waypoint waypoint) {
            return new Snapshot(
                dimension,
                cleanSetName(setName),
                waypoint.getX(),
                waypoint.getY(),
                waypoint.getZ(),
                safeWaypointText(waypoint.getName(), 32, "Waypoint"),
                safeWaypointText(waypoint.getInitials(), 3, "WP"),
                Math.floorMod(waypoint.getColor(), WaypointColor.values().length),
                waypoint.isDisabled(),
                waypoint.isYIncluded(),
                waypoint.getVisibilityType(),
                cleanPurpose(waypoint.getPurpose().name())
            );
        }

        private ResourceKey<Level> dimensionKey() {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        }

        private boolean matches(Waypoint waypoint) {
            return x == waypoint.getX()
                && y == waypoint.getY()
                && z == waypoint.getZ()
                && color == Math.floorMod(waypoint.getColor(), WaypointColor.values().length)
                && name.equals(safeWaypointText(waypoint.getName(), 32, "Waypoint"))
                && initials.equals(safeWaypointText(waypoint.getInitials(), 3, "WP"))
                && disabled == waypoint.isDisabled()
                && yIncluded == waypoint.isYIncluded()
                && visibilityType == waypoint.getVisibilityType()
                && purpose.equals(cleanPurpose(waypoint.getPurpose().name()));
        }
    }

    private static String cleanPurpose(String value) {
        if (value == null || value.isBlank()) {
            return WaypointPurpose.NORMAL.name();
        }
        try {
            return WaypointPurpose.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (RuntimeException ignored) {
            return WaypointPurpose.NORMAL.name();
        }
    }
}
