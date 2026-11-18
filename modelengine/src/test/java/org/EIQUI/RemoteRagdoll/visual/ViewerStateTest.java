package org.EIQUI.RemoteRagdoll.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.EIQUI.RemoteRagdoll.visual.ViewerState.Transition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ViewerStateTest {
    @Test
    void enteringLeavingAndReenteringProduceExactlyOneTransition() {
        ViewerState viewers = new ViewerState();
        UUID player = UUID.randomUUID();
        assertEquals(NONE, viewers.update(player, false));
        assertEquals(SPAWN, viewers.update(player, true));
        assertEquals(NONE, viewers.update(player, true));
        assertEquals(DESTROY, viewers.update(player, false));
        assertEquals(NONE, viewers.update(player, false));
        assertEquals(SPAWN, viewers.update(player, true));
    }

    @Test
    void quitExplicitRemovalAndClearDoNotRetainMembership() {
        ViewerState viewers = new ViewerState();
        UUID player = UUID.randomUUID();
        viewers.update(player, true);
        viewers.forget(player); // disconnected: no attempt to send into the closed connection
        assertTrue(viewers.snapshot().isEmpty());
        assertEquals(SPAWN, viewers.update(player, true), "reconnect must get full spawn data");
        assertEquals(DESTROY, viewers.update(player, false), "explicit visibility removal destroys the prior entity");
        assertEquals(SPAWN, viewers.update(player, true));
        viewers.clear();
        assertTrue(viewers.snapshot().isEmpty());
    }

    @Test
    void rangeUsesWorldAndInclusiveHorizontal192BlockBoundary() {
        World first = mock(World.class);
        World second = mock(World.class);
        PacketRenderManager manager = new PacketRenderManager(mock(JavaPlugin.class),
                world -> DisplayTrackingRange.calculate(196, 12, -1));
        Location visual = new Location(first, 10, 20, 30);
        assertTrue(manager.inRange(visual, new Location(first, 202, 20, 30), 12));
        assertFalse(manager.inRange(visual, new Location(first, 202.001, 20, 30), 12));
        assertTrue(manager.inRange(visual, new Location(first, 10, 20, 222), 12));
        assertFalse(manager.inRange(visual, new Location(first, 10, 20, 222.001), 12));
        assertTrue(manager.inRange(visual, new Location(first, 106, 20, 126), 12));
        assertFalse(manager.inRange(visual, new Location(first, 202, 20, 222), 12),
                "tracking uses a horizontal circle, not independent X/Z axis limits");
        assertFalse(manager.inRange(visual, new Location(second, 10, 20, 30), 12));
        assertTrue(manager.inRange(visual, new Location(first, 10, 10_000, 30), 12));
        assertTrue(manager.inRange(visual, new Location(first, 202, -10_000, 30), 12));
    }

    @Test
    void worldsUseTheirOwnLoadedRanges() {
        World first = mock(World.class);
        World second = mock(World.class);
        PacketRenderManager manager = new PacketRenderManager(mock(JavaPlugin.class),
                world -> DisplayTrackingRange.calculate(world == first ? 100 : 196, 12, -1));
        assertFalse(manager.inRange(new Location(first, 0, 0, 0), new Location(first, 101, 0, 0), 12));
        assertTrue(manager.inRange(new Location(second, 0, 0, 0), new Location(second, 101, 0, 0), 12));
    }

    @Test
    void optionalVerticalTrackingUsesInclusiveAbsoluteYDistance() {
        World world = mock(World.class);
        PacketRenderManager manager = new PacketRenderManager(mock(JavaPlugin.class),
                ignored -> DisplayTrackingRange.calculate(196, 12, 64));
        Location visual = new Location(world, 0, 100, 0);
        assertTrue(manager.inRange(visual, new Location(world, 192, 164, 0), 12));
        assertTrue(manager.inRange(visual, new Location(world, 192, 36, 0), 12));
        assertFalse(manager.inRange(visual, new Location(world, 0, 164.001, 0), 12));
        assertFalse(manager.inRange(visual, new Location(world, 0, 35.999, 0), 12));
    }

    @Test
    void zeroTrackingRangeHasNoViewersEvenAtSameLocation() {
        World world = mock(World.class);
        PacketRenderManager manager = new PacketRenderManager(mock(JavaPlugin.class),
                ignored -> DisplayTrackingRange.calculate(0, 12, -1));
        Location location = new Location(world, 0, 0, 0);
        assertFalse(manager.inRange(location, location, 12));
    }
}
