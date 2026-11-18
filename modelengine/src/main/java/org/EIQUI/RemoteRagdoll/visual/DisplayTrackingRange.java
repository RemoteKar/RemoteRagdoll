package org.EIQUI.RemoteRagdoll.visual;

import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/** Loaded 26.3 display tracking limits; bytecode evidence is recorded in PORTING.md. */
record DisplayTrackingRange(double horizontal, double vertical, boolean enabled) {
    static final int DEFAULT_DISPLAY_RANGE = 128;
    static final float VIEW_RANGE = 3f;
    static final double CLIENT_RANGE = VIEW_RANGE * 64;

    static DisplayTrackingRange load(World world) {
        var level = ((CraftWorld) world).getHandle();
        int configuredRange = level.spigotConfig.displayTrackingRange;
        int displayRange = level.getServer().getScaledTrackingDistance(configuredRange);
        var trackingY = level.paperConfig().entities.trackingRangeY;
        return calculate(displayRange, world.getViewDistance(), trackingY.enabled ? trackingY.display.or(-1) : -1,
                configuredRange != 0);
    }

    static DisplayTrackingRange calculate(int scaledDisplayRange, int viewDistanceChunks, int verticalRange) {
        return calculate(scaledDisplayRange, viewDistanceChunks, verticalRange, scaledDisplayRange != 0);
    }

    static DisplayTrackingRange calculate(int scaledDisplayRange, int viewDistanceChunks, int verticalRange,
                                          boolean enabled) {
        double horizontal = Math.min(Math.max(0, scaledDisplayRange),
                Math.min(Math.max(2, viewDistanceChunks) * 16.0, CLIENT_RANGE));
        // Paper treats -1 as unlimited; other values are squared in TrackedEntity.updatePlayer.
        double vertical = verticalRange == -1 ? Double.POSITIVE_INFINITY : Math.abs((double) verticalRange);
        return new DisplayTrackingRange(horizontal, vertical, enabled);
    }

    static DisplayTrackingRange fallback(int viewDistanceChunks) {
        return calculate(DEFAULT_DISPLAY_RANGE, viewDistanceChunks, -1);
    }

    double forClient(int requestedViewDistance) {
        return Math.min(horizontal, Math.max(2, requestedViewDistance) * 16.0);
    }

    boolean contains(double dx, double dy, double dz, int requestedViewDistance) {
        double range = forClient(requestedViewDistance);
        // ChunkMap disables raw range=0 before scaling; a positive range may later round down to zero.
        return enabled && dx * dx + dz * dz <= range * range && Math.abs(dy) <= vertical;
    }
}
