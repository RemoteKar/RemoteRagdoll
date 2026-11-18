package org.EIQUI.RemoteRagdoll.visual;

import org.EIQUI.RemoteRagdoll.Ragdoll;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;

/** Optional-backend boundary: ordinary physics and debug displays never need PacketEvents classes. */
public final class Visuals {
    private static boolean ready;
    private static JavaPlugin plugin;
    private static boolean warnedItemFallback;

    private Visuals() {}

    public static void initialize(JavaPlugin owner) {
        plugin = owner;
        try {
            ready = Bukkit.getPluginManager().isPluginEnabled("packetevents")
                    && PacketRenderManager.initialize(owner);
        } catch (RuntimeException | LinkageError ex) {
            owner.getLogger().warning("[Ragdoll] PacketEvents initialization failed: " + ex);
        }
        if (!ready) {
            Ragdoll.PACKET_RENDER = false;
            owner.getLogger().warning("[Ragdoll] Packet renderer unavailable; using real displays.");
        }
    }

    public static PartVisual create(Location at, ItemStack item, ItemDisplay.ItemDisplayTransform mode,
                                    Transformation transform, boolean packet) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Ragdoll visuals require the main thread");
        if (packet && Ragdoll.PACKET_RENDER) {
            if (!ready) {
                Ragdoll.PACKET_RENDER = false;
            } else {
                try {
                    return PacketRenderManager.create(at, item, mode, transform);
                } catch (RuntimeException | LinkageError ex) {
                    if (!warnedItemFallback) {
                        plugin.getLogger().warning("[Ragdoll] Item packet conversion/send failed; this item uses a real display: " + ex);
                        warnedItemFallback = true;
                    }
                }
            }
        }
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setTransformation(transform);
            d.setItemStack(item);
            d.setItemDisplayTransform(mode);
            d.setDisplayWidth(0f);
            d.setDisplayHeight(0f);
            d.setViewRange(DisplayTrackingRange.VIEW_RANGE);
            d.addScoreboardTag(Ragdoll.RAGDOLL_TAG);
        });
        return new EntityVisual(display);
    }

    public static void flush() { if (ready) PacketRenderManager.flush(); }
    public static String diagnostics() { return PacketRenderManager.diagnostics(); }
    public static void clear(World world) { if (ready) PacketRenderManager.clear(world); }
    public static void clearAll() { if (ready) PacketRenderManager.clearAll(); }
    public static void shutdown() {
        if (ready) PacketRenderManager.shutdown();
        ready = false;
        plugin = null;
    }
}
