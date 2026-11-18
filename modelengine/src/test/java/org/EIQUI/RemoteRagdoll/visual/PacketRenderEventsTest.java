package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketRenderEventsTest {
    private static final int LEAVING = 101;
    private static final int RETAINED = 102;
    private static final int ENTERING = 103;

    private final UUID viewer = UUID.randomUUID();
    private final List<PacketWrapper<?>> packets = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Map<World, DisplayTrackingRange> ranges = new HashMap<>();
    private PacketRenderManager manager;
    private Object previousManager;
    private Field instance;
    private MockedStatic<Bukkit> bukkit;
    private Player player;
    private World world;
    private World otherWorld;
    private Location playerLocation;

    @BeforeEach
    void setUp() throws Exception {
        DisplayMetadataTest.initializePacketEventsRegistry();
        PlayerManager transport = mock(PlayerManager.class);
        when(PacketEvents.getAPI().getPlayerManager()).thenReturn(transport);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        manager = new PacketRenderManager(plugin,
                key -> ranges.getOrDefault(key, DisplayTrackingRange.calculate(196, 12, -1)));
        instance = PacketRenderManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        previousManager = instance.get(null);
        instance.set(null, manager);

        world = mock(World.class);
        otherWorld = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
        playerLocation = location(world, 0);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.isOnline()).thenReturn(true);
        when(player.getClientViewDistance()).thenReturn(12);
        when(player.getLocation()).thenAnswer(ignored -> playerLocation.clone());
        when(player.getWorld()).thenAnswer(ignored -> playerLocation.getWorld());
        when(world.getPlayers()).thenReturn(List.of(player));
        when(transport.getChannel(player)).thenReturn(new Object());
        doAnswer(invocation -> {
            packets.add(invocation.getArgument(1));
            return null;
        }).when(transport).writePacket(eq(player), any(PacketWrapper.class));
        doAnswer(invocation -> {
            packets.add(invocation.getArgument(1));
            return null;
        }).when(transport).sendPacket(eq(player), any(PacketWrapper.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        bukkit.when(() -> Bukkit.getPlayer(viewer)).thenReturn(player);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bukkit != null) bukkit.close();
        if (instance != null) instance.set(null, previousManager);
    }

    @Test
    void shortTeleportsIncluding192BlockBoundaryAndRotationSendNothingAndScheduleNothing() throws Exception {
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        flushInitialPackets();
        for (Location destination : List.of(location(world, 1), location(world, 192),
                new Location(world, 0, 10_000, 0), new Location(world, 0, 0, 192),
                new Location(world, 0, 0, 0, 180, 45))) {
            manager.onTeleport(teleport(destination));
            assertPackets();
            assertTrue(scheduled.isEmpty(), "a short teleport must not defer a reset or refresh");
            assertVisible(retained, true);
        }
    }

    @Test
    void shortTeleportLeavesEnteringAndLeavingVisualsToPeriodicRefresh() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, -191));
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 194));
        flushInitialPackets();
        Location destination = location(world, 4);

        manager.onTeleport(teleport(destination));
        assertPackets();
        assertTrue(scheduled.isEmpty());
        assertVisible(leaving, true);
        assertVisible(entering, false);

        playerLocation = destination;
        periodicRefresh();
        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(retained, true);
        assertVisible(entering, true);
    }

    @Test
    void longTeleportImmediatelyUsesEventDestinationAndSendsOnlyMembershipDifference() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual retained = addVisual(RETAINED, location(world, 150));
        PacketVisual entering = addVisual(ENTERING, location(world, 300));
        addVisual(104, location(otherWorld, 300));
        flushInitialPackets();

        manager.onTeleport(teleport(location(world, 300)));

        assertEquals(0, player.getLocation().getX(), "the player has not moved during the event");
        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertTrue(scheduled.isEmpty(), "long teleports reconcile immediately against event.getTo()");
        assertVisible(leaving, false);
        assertVisible(retained, true);
        assertVisible(entering, true);
    }

    @Test
    void teleportJustBeyond192BlocksReconcilesImmediately() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 384));
        flushInitialPackets();

        manager.onTeleport(teleport(location(world, 192.001)));

        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(entering, true);
    }

    @Test
    void initialSpawnAndPeriodicRefreshUseInclusiveHorizontalBoundaryWithoutVerticalCulling() throws Exception {
        PacketVisual east = addVisual(LEAVING, new Location(world, 192, 10_000, 0));
        PacketVisual south = addVisual(RETAINED, new Location(world, 0, -10_000, 192));
        PacketVisual outside = addVisual(ENTERING, location(world, 192.001));
        invoke("sendQueued");
        assertPackets("spawn:101", "metadata:101", "spawn:102", "metadata:102");
        assertVisible(outside, false);
        packets.clear();

        playerLocation = new Location(world, -0.001, 0, -0.001);
        periodicRefresh();
        assertPackets("destroy:101", "destroy:102");
        assertVisible(east, false);
        assertVisible(south, false);
        packets.clear();

        playerLocation = location(world, 0);
        periodicRefresh();
        assertPackets("spawn:101", "metadata:101", "spawn:102", "metadata:102");
        assertVisible(east, true);
        assertVisible(south, true);
    }

    @Test
    void periodicRefreshStillWaitsTenTicks() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 192));
        flushInitialPackets();
        playerLocation = location(world, -0.001);

        for (int tick = 0; tick < 9; tick++) invoke("tick");
        assertPackets();
        assertVisible(leaving, true);
        invoke("tick");
        assertPackets("destroy:101");
        assertVisible(leaving, false);
    }

    @Test
    void clientViewDistanceChangesTakeEffectOnTheNextPeriodicRefresh() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 80.001));
        PacketVisual boundary = addVisual(RETAINED, location(world, 80));
        flushInitialPackets();

        when(player.getClientViewDistance()).thenReturn(5);
        periodicRefresh();
        assertPackets("destroy:101");
        assertVisible(leaving, false);
        assertVisible(boundary, true);
        packets.clear();

        when(player.getClientViewDistance()).thenReturn(12);
        periodicRefresh();
        assertPackets("spawn:101", "metadata:101");
        assertVisible(leaving, true);
    }

    @Test
    void teleportThresholdUsesClientViewDistanceWithInclusiveBoundary() throws Exception {
        when(player.getClientViewDistance()).thenReturn(5);
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 160));
        flushInitialPackets();

        manager.onTeleport(teleport(location(world, 80)));
        assertPackets();
        assertVisible(leaving, true);
        assertVisible(entering, false);

        manager.onTeleport(teleport(location(world, 80.001)));
        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(entering, true);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void configuredWorldRangeSetsTeleportThreshold() throws Exception {
        ranges.put(world, DisplayTrackingRange.calculate(100, 12, -1));
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 200));
        flushInitialPackets();

        manager.onTeleport(teleport(location(world, 100)));
        assertPackets();
        manager.onTeleport(teleport(location(world, 100.001)));
        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(entering, true);
    }

    @Test
    void enabledVerticalTrackingRangeAlsoSetsTeleportThreshold() throws Exception {
        ranges.put(world, DisplayTrackingRange.calculate(196, 12, 64));
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, new Location(world, 0, 128, 0));
        flushInitialPackets();

        manager.onTeleport(teleport(new Location(world, 0, 64, 0)));
        assertPackets();
        manager.onTeleport(teleport(new Location(world, 0, 64.001, 0)));
        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(entering, true);
    }

    @Test
    void crossWorldTeleportWaitsForWorldChangeToRebuildViewers() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(otherWorld, 0));
        flushInitialPackets();

        manager.onTeleport(teleport(location(otherWorld, 0)));
        assertPackets();
        assertTrue(scheduled.isEmpty(), "PlayerChangedWorldEvent owns cross-world reconciliation");
        assertVisible(leaving, true);
        assertVisible(entering, false);

        completeWorldChange(leaving, entering);
    }

    @Test
    void sameWorldRespawnAtSameLocationKeepsExistingClientEntities() throws Exception {
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        addVisual(104, location(world, 192));
        flushInitialPackets();

        manager.onRespawn(respawn(location(world, 0)));
        assertPackets();
        assertEquals(1, scheduled.size());
        periodicRefresh();
        assertPackets();
        assertVisible(retained, true);
        runScheduled();
        assertPackets();
        assertVisible(retained, true);
    }

    @Test
    void sameWorldRespawnEvenNearbySendsOnlyDestinationMembershipDifferenceNextTick() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, -191));
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 194));
        flushInitialPackets();
        Location destination = location(world, 4);

        manager.onRespawn(respawn(destination));
        assertPackets();
        assertEquals(1, scheduled.size());
        assertVisible(leaving, true);
        assertVisible(entering, false);
        playerLocation = destination;
        runScheduled();

        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(retained, true);
        assertVisible(entering, true);
    }

    @Test
    void crossWorldRespawnWaitsForWorldChangeToRebuildViewers() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(otherWorld, 0));
        flushInitialPackets();

        manager.onRespawn(respawn(location(otherWorld, 0)));
        assertPackets();
        assertTrue(scheduled.isEmpty(), "cross-world respawn must not duplicate the world-change reset");
        assertVisible(leaving, true);

        completeWorldChange(leaving, entering);
    }

    @Test
    void spectatorSwitchKeepsExistingViewersAcrossPeriodicRefreshAndDeferredTask() throws Exception {
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        flushInitialPackets();

        manager.onGameMode(gameMode(GameMode.SPECTATOR));
        assertPackets();
        assertEquals(1, scheduled.size());
        periodicRefresh();
        assertPackets();
        assertVisible(retained, true);
        runScheduled();
        assertPackets();
        assertVisible(retained, true);

        manager.onGameMode(gameMode(GameMode.SURVIVAL));
        runScheduled();
        assertPackets();
    }

    @Test
    void gameModeRefreshCoalescesAndSendsOnlyCurrentPositionMembershipDifference() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, -191));
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        PacketVisual entering = addVisual(ENTERING, location(world, 194));
        flushInitialPackets();

        manager.onGameMode(gameMode(GameMode.SPECTATOR));
        manager.onGameMode(gameMode(GameMode.SURVIVAL));
        assertEquals(1, scheduled.size(), "multiple events before the next tick share one refresh");
        playerLocation = location(world, 4);
        periodicRefresh();
        assertPackets();
        assertVisible(leaving, true);
        assertVisible(entering, false);
        runScheduled();

        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(retained, true);
        assertVisible(entering, true);
    }

    @Test
    void longTeleportDuringDeferredRefreshUsesTheFinalPositionOnTheScheduledTick() throws Exception {
        PacketVisual leaving = addVisual(LEAVING, location(world, 0));
        PacketVisual retained = addVisual(RETAINED, location(world, 150));
        PacketVisual entering = addVisual(ENTERING, location(world, 300));
        flushInitialPackets();

        manager.onRespawn(respawn(location(world, 0)));
        manager.onTeleport(teleport(location(world, 300)));
        assertPackets();
        assertEquals(1, scheduled.size());
        playerLocation = location(world, 300);
        runScheduled();

        assertPackets("destroy:101", "spawn:103", "metadata:103");
        assertVisible(leaving, false);
        assertVisible(retained, true);
        assertVisible(entering, true);
    }

    @Test
    void quitForgetsMembershipAndMakesPendingRefreshHarmless() throws Exception {
        PacketVisual retained = addVisual(RETAINED, location(world, 0));
        flushInitialPackets();
        manager.onGameMode(gameMode(GameMode.SPECTATOR));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);

        manager.onQuit(quit);
        when(player.isOnline()).thenReturn(false);
        runScheduled();

        assertPackets();
        assertVisible(retained, false);
    }

    private void completeWorldChange(PacketVisual leaving, PacketVisual entering) throws Exception {
        playerLocation = location(otherWorld, 0);
        manager.onWorldChange(new PlayerChangedWorldEvent(player, world));
        assertPackets("destroy:101");
        assertEquals(1, scheduled.size());
        assertVisible(leaving, false);
        assertVisible(entering, false);
        packets.clear();
        periodicRefresh();
        assertPackets();
        runScheduled();
        assertPackets("spawn:103", "metadata:103");
        assertVisible(entering, true);
    }

    private PlayerTeleportEvent teleport(Location destination) {
        return new PlayerTeleportEvent(player, playerLocation.clone(), destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private PlayerRespawnEvent respawn(Location destination) {
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRespawnLocation()).thenReturn(destination);
        return event;
    }

    private PlayerGameModeChangeEvent gameMode(GameMode mode) {
        return new PlayerGameModeChangeEvent(player, mode);
    }

    @SuppressWarnings("unchecked")
    private PacketVisual addVisual(int entityId, Location location) throws Exception {
        PacketVisual visual = new PacketVisual(manager, entityId, location, mock(ItemStack.class),
                ItemDisplayTransform.FIXED, DisplayMetadataTest.pose());
        Field visuals = PacketRenderManager.class.getDeclaredField("visuals");
        visuals.setAccessible(true);
        ((Set<PacketVisual>) visuals.get(manager)).add(visual);
        var refreshVisual = PacketRenderManager.class.getDeclaredMethod("refreshVisual", PacketVisual.class);
        refreshVisual.setAccessible(true);
        refreshVisual.invoke(manager, visual);
        return visual;
    }

    private void flushInitialPackets() throws Exception {
        invoke("sendQueued");
        packets.clear();
    }

    private void periodicRefresh() throws Exception {
        for (int tick = 0; tick < PacketRenderManager.VIEWER_INTERVAL; tick++) invoke("tick");
    }

    private void runScheduled() {
        List<Runnable> nextTick = List.copyOf(scheduled);
        scheduled.clear();
        nextTick.forEach(Runnable::run);
    }

    private void invoke(String methodName) throws Exception {
        var method = PacketRenderManager.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(manager);
    }

    private void assertVisible(PacketVisual visual, boolean expected) {
        assertEquals(expected, visual.viewers.snapshot().contains(viewer));
    }

    private void assertPackets(String... expected) {
        List<String> actual = packets.stream().map(packet -> {
            if (packet instanceof WrapperPlayServerDestroyEntities destroy) {
                assertEquals(1, destroy.getEntityIds().length);
                return "destroy:" + destroy.getEntityIds()[0];
            }
            if (packet instanceof WrapperPlayServerSpawnEntity spawn) return "spawn:" + spawn.getEntityId();
            if (packet instanceof WrapperPlayServerEntityMetadata metadata) return "metadata:" + metadata.getEntityId();
            fail("Unexpected packet: " + packet.getClass().getSimpleName());
            return "";
        }).sorted().toList();
        assertEquals(List.of(expected).stream().sorted().toList(), actual);
        // Visual iteration order is unspecified, but each new entity needs its spawn before metadata.
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i) instanceof WrapperPlayServerSpawnEntity spawn) {
                assertTrue(i + 1 < packets.size());
                var metadata = assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(i + 1));
                assertEquals(spawn.getEntityId(), metadata.getEntityId());
            }
        }
    }

    private static Location location(World world, double x) { return new Location(world, x, 0, 0); }
}
