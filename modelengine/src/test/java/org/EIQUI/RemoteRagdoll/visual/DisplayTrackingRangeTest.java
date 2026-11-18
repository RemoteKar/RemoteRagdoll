package org.EIQUI.RemoteRagdoll.visual;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayTrackingRangeTest {
    @Test
    void configuredRangeIsLimitedByWorldChunksAndClientDisplayRange() {
        assertEquals(100, DisplayTrackingRange.calculate(100, 12, -1).horizontal());
        assertEquals(96, DisplayTrackingRange.calculate(196, 6, -1).horizontal());
        assertEquals(192, DisplayTrackingRange.calculate(196, 12, -1).horizontal());
        assertEquals(192, DisplayTrackingRange.calculate(1024, 32, -1).horizontal());
        assertEquals(32, DisplayTrackingRange.calculate(196, 1, -1).horizontal());
        assertEquals(32, DisplayTrackingRange.calculate(196, -1, -1).horizontal());
        assertEquals(0, DisplayTrackingRange.calculate(0, 12, -1).horizontal());
        assertEquals(0, DisplayTrackingRange.calculate(-1, 12, -1).horizontal());
    }

    @Test
    void fallbackUsesSpigotDisplayDefault128WithTheSameCaps() {
        assertEquals(128, DisplayTrackingRange.fallback(12).horizontal());
        assertEquals(96, DisplayTrackingRange.fallback(6).horizontal());
        assertEquals(32, DisplayTrackingRange.fallback(0).horizontal());
        assertEquals(Double.POSITIVE_INFINITY, DisplayTrackingRange.fallback(12).vertical());
    }

    @Test
    void playerRequestedChunksFurtherLimitRangeAndClampToTwoChunks() {
        DisplayTrackingRange range = DisplayTrackingRange.calculate(196, 12, -1);
        assertEquals(80, range.forClient(5));
        assertEquals(192, range.forClient(12));
        assertEquals(192, range.forClient(32));
        assertEquals(32, range.forClient(1));
        assertEquals(32, range.forClient(-1));
        assertEquals(50, DisplayTrackingRange.calculate(50, 12, -1).forClient(32));
    }

    @Test
    void verticalMinusOneIsUnlimitedAndOtherValuesFollowSquaredDistance() {
        assertEquals(Double.POSITIVE_INFINITY, DisplayTrackingRange.calculate(196, 12, -1).vertical());
        assertEquals(64, DisplayTrackingRange.calculate(196, 12, 64).vertical());
        assertEquals(64, DisplayTrackingRange.calculate(196, 12, -64).vertical());
        assertEquals(0, DisplayTrackingRange.calculate(196, 12, 0).vertical());
    }

    @Test
    void scaledZeroRetainsItsEnabledTrackerButConfiguredZeroDisablesTracking() {
        DisplayTrackingRange disabled = DisplayTrackingRange.calculate(0, 12, -1);
        assertFalse(disabled.contains(0, 0, 0, 12));
        DisplayTrackingRange scaledZero = DisplayTrackingRange.calculate(0, 12, -1, true);
        assertTrue(scaledZero.contains(0, 10_000, 0, 12));
        assertFalse(scaledZero.contains(0.001, 0, 0, 12));
    }

    @Test
    void loadedRangesAreCachedIndependentlyUntilWorldLoadOrUnload() {
        World first = mock(World.class);
        World second = mock(World.class);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger configured = new AtomicInteger(100);
        PacketRenderManager manager = new PacketRenderManager(plugin(), world -> {
            (world == first ? firstCalls : secondCalls).incrementAndGet();
            return DisplayTrackingRange.calculate(world == first ? configured.get() : 160, 12, -1);
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertEquals(100, manager.trackingRange(first));
            assertEquals(160, manager.trackingRange(second));
            configured.set(196);
            assertEquals(100, manager.trackingRange(first), "settings are not polled every tick");
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());

            manager.onWorldLoad(new WorldLoadEvent(first));
            assertEquals(2, firstCalls.get(), "world load immediately populates its new range");
            assertEquals(192, manager.trackingRange(first));
            assertEquals(2, firstCalls.get());
            configured.set(80);
            manager.onWorldUnload(new WorldUnloadEvent(first));
            assertEquals(2, firstCalls.get(), "unload evicts without reloading the world");
            assertEquals(80, manager.trackingRange(first));
            assertEquals(3, firstCalls.get());
            assertEquals(160, manager.trackingRange(second));
            assertEquals(1, secondCalls.get(), "other worlds retain their cache");
        }
    }

    @Test
    void lookupFailureCachesFallbackUntilWorldLoadRetries() {
        World world = mock(World.class);
        when(world.getViewDistance()).thenReturn(6);
        AtomicInteger calls = new AtomicInteger();
        PacketRenderManager manager = new PacketRenderManager(plugin(), ignored -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("range unavailable");
            return DisplayTrackingRange.calculate(196, 12, -1);
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertEquals(96, manager.trackingRange(world));
            assertEquals(96, manager.trackingRange(world));
            assertEquals(1, calls.get());
            manager.onWorldLoad(new WorldLoadEvent(world));
            assertEquals(192, manager.trackingRange(world));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void linkageFailureAlsoUsesTheSpigotFallback() {
        World world = mock(World.class);
        when(world.getViewDistance()).thenReturn(12);
        PacketRenderManager manager = new PacketRenderManager(plugin(), ignored -> {
            throw new NoSuchFieldError("displayTrackingRange");
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertEquals(128, manager.trackingRange(world));
        }
    }

    private static JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        return plugin;
    }
}
