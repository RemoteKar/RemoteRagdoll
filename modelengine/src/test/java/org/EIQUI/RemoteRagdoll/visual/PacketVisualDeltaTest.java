package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PacketVisualDeltaTest {
    @BeforeAll
    static void initializeApi() { DisplayMetadataTest.initializePacketEventsRegistry(); }

    @Test
    void multiplePoseAndPositionChangesCoalesceToLatestMetadataAndPosition() {
        PacketRenderManager owner = mock(PacketRenderManager.class);
        Location origin = new Location(mock(World.class), 10, 20, 30);
        Transformation pose = DisplayMetadataTest.pose();
        UUID viewer = UUID.randomUUID();
        PacketVisual visual = fixture(owner, origin, pose, viewer);
        List<PacketWrapper<?>> packets = capture(owner, viewer);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            visual.setTransform(new Vector3f(2, -1024, 3), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
            visual.moveTo(origin.clone().add(1, 0, 0));
            Vector3f latestTranslation = new Vector3f(4, -1024, 3);
            Location latestPosition = origin.clone().add(2, 0, 0);
            visual.setTransform(latestTranslation, pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
            visual.moveTo(latestPosition);
            // Callers may reuse their mutable JOML/Bukkit inputs after the setters return.
            latestTranslation.x = 99;
            latestPosition.setX(99);
            visual.collectChanges();
            assertEquals(2, packets.size());
            var metadata = assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.getFirst());
            assertEquals(List.of(11), metadata.getEntityMetadata().stream().map(EntityData::getIndex).toList());
            var translation = (com.github.retrooper.packetevents.util.Vector3f) metadata.getEntityMetadata().getFirst().getValue();
            assertEquals(4f, translation.getX());
            var position = assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.getLast());
            assertEquals(12, position.getPosition().getEndPosition().getX());
            packets.clear();
            visual.collectChanges();
            assertTrue(packets.isEmpty(), "a second flush must not repeat the prior tick's changes");
        }
    }

    @Test
    void positionOnlyChangeDoesNotSendMetadata() {
        PacketRenderManager owner = mock(PacketRenderManager.class);
        Location origin = new Location(mock(World.class), 10, 20, 30);
        UUID viewer = UUID.randomUUID();
        PacketVisual visual = fixture(owner, origin, DisplayMetadataTest.pose(), viewer);
        List<PacketWrapper<?>> packets = capture(owner, viewer);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Location destination = origin.clone();
            destination.setYaw(45);
            visual.moveTo(destination);
            visual.collectChanges();
            assertEquals(1, packets.size());
            var position = assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.getFirst());
            assertEquals(45f, position.getYRot());
            assertEquals(10, position.getPosition().getEndPosition().getX());
        }
    }

    @Test
    void identicalAndRevertedValuesDoNotEnqueuePackets() {
        PacketRenderManager owner = mock(PacketRenderManager.class);
        Location origin = new Location(mock(World.class), 10, 20, 30);
        Transformation pose = DisplayMetadataTest.pose();
        UUID viewer = UUID.randomUUID();
        PacketVisual visual = fixture(owner, origin, pose, viewer);
        List<PacketWrapper<?>> packets = capture(owner, viewer);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            visual.setTransform(pose.getTranslation(), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
            visual.moveTo(origin.clone());
            visual.collectChanges();
            verify(owner, never()).markDirty(any());
            assertTrue(packets.isEmpty());
            visual.setTransform(new Vector3f(99, -1024, 3), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
            visual.moveTo(origin.clone().add(8, 0, 0));
            visual.setTransform(pose.getTranslation(), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation());
            visual.moveTo(origin);
            visual.collectChanges();
            assertTrue(packets.isEmpty(), "changes reverted before flush have no observable delta");
            verify(owner, never()).enqueue(any(), any());
        }
    }

    private static PacketVisual fixture(PacketRenderManager owner, Location origin, Transformation pose, UUID viewer) {
        // No spawn/serialization is exercised here; the item belongs to the independently tested initial metadata.
        PacketVisual visual = new PacketVisual(owner, 123, origin, null, ItemDisplayTransform.FIXED, pose);
        visual.viewers.update(viewer, true);
        return visual;
    }

    private static List<PacketWrapper<?>> capture(PacketRenderManager owner, UUID viewer) {
        List<PacketWrapper<?>> packets = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<PacketWrapper<?>> packet = invocation.getArgument(1);
            packets.add(packet.get());
            return null;
        }).when(owner).enqueue(eq(viewer), any());
        return packets;
    }
}
