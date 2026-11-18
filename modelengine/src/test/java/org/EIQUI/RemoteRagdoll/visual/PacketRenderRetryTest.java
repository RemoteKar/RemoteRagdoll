package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketRenderRetryTest {
    @BeforeEach
    void initializeApi() { DisplayMetadataTest.initializePacketEventsRegistry(); }

    @Test
    void missingChannelPreservesRemovedPartUntilDestroyCanBeSent() throws Exception {
        exerciseFailedBatch(false);
    }

    @Test
    void partialSendFailurePreservesRemovedPartUntilDestroyCanBeSent() throws Exception {
        exerciseFailedBatch(true);
    }

    private void exerciseFailedBatch(boolean throwDuringFlush) throws Exception {
        PlayerManager transport = mock(PlayerManager.class);
        when(PacketEvents.getAPI().getPlayerManager()).thenReturn(transport);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var constructor = PacketRenderManager.class.getDeclaredConstructor(JavaPlugin.class);
        constructor.setAccessible(true);
        PacketRenderManager manager = constructor.newInstance(plugin);
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(mock(World.class), 0, 0, 0));
        Object channel = new Object();
        if (throwDuringFlush) {
            when(transport.getChannel(player)).thenReturn(channel);
            doThrow(new IllegalStateException("simulated channel failure"))
                    .when(transport).sendPacket(eq(player), any(PacketWrapper.class));
        }
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            // The same ownership path is used for a real spawn; transport is mocked here.
            manager.enqueueSpawn(id, 42, () -> new WrapperPlayServerDestroyEntities(42));
            manager.enqueue(id, () -> new WrapperPlayServerDestroyEntities(43));
            invoke(manager, "sendQueued");
            assertEquals(Set.of(42), possible(manager).get(id));
            manager.enqueueDestroy(id, 42); // visual removed before its failed spawn can be retried
            invoke(manager, "sendQueued");
            assertEquals(Set.of(42), possible(manager).get(id), "failed destroy must retain cleanup ownership");
            when(transport.getChannel(player)).thenReturn(channel);
            doNothing().when(transport).sendPacket(eq(player), any(PacketWrapper.class));
            clearInvocations(transport);
            invoke(manager, "recoverViewers");
            invoke(manager, "sendQueued");
            ArgumentCaptor<PacketWrapper<?>> sent = ArgumentCaptor.forClass(PacketWrapper.class);
            verify(transport).sendPacket(eq(player), sent.capture());
            assertInstanceOf(WrapperPlayServerDestroyEntities.class, sent.getValue());
            assertArrayEquals(new int[]{42}, ((WrapperPlayServerDestroyEntities) sent.getValue()).getEntityIds());
            assertFalse(possible(manager).containsKey(id));
        }
    }

    private static void invoke(PacketRenderManager manager, String name) throws Exception {
        var method = PacketRenderManager.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Set<Integer>> possible(PacketRenderManager manager) throws Exception {
        Field field = PacketRenderManager.class.getDeclaredField("possibleEntities");
        field.setAccessible(true);
        return (Map<UUID, Set<Integer>>) field.get(manager);
    }
}
