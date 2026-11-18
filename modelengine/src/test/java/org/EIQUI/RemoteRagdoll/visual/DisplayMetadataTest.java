package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.Quaternion4f;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayMetadataTest {
    @BeforeAll
    static void initializePacketEventsRegistry() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager server = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(server);
        when(server.getVersion()).thenReturn(ServerVersion.V_26_3);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(api.getNettyManager()).thenReturn(new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl());
        when(api.getLogManager()).thenReturn(mock(com.github.retrooper.packetevents.util.LogManager.class));
        PacketEvents.setAPI(api);
    }

    static Transformation pose() {
        return new Transformation(new Vector3f(1, -1024, 3), new Quaternionf(0.1f, 0.2f, 0.3f, 0.4f),
                new Vector3f(2, 3, 4), new Quaternionf(0.5f, 0.6f, 0.7f, 0.8f));
    }

    @Test
    void initialMetadataUsesVerified26_3IndicesTypesAndValues() {
        ItemStack item = mock(ItemStack.class);
        List<EntityData<?>> data = DisplayMetadata.initial(item, ItemDisplayTransform.FIXED, pose());
        Map<Integer, EntityData<?>> indexed = data.stream().collect(Collectors.toMap(EntityData::getIndex, Function.identity()));
        assertEquals(List.of(5, 8, 9, 10, 11, 12, 13, 14, 17, 20, 21, 23, 24), data.stream().map(EntityData::getIndex).toList());
        assertSame(EntityDataTypes.BOOLEAN, indexed.get(5).getType());
        assertEquals(true, indexed.get(5).getValue());
        for (int id : List.of(8, 9, 10)) assertSame(EntityDataTypes.INT, indexed.get(id).getType());
        assertEquals(0, indexed.get(8).getValue());
        assertEquals(1, indexed.get(9).getValue());
        assertEquals(1, indexed.get(10).getValue());
        for (int id : List.of(11, 12)) assertSame(EntityDataTypes.VECTOR3F, indexed.get(id).getType());
        var translation = (com.github.retrooper.packetevents.util.Vector3f) indexed.get(11).getValue();
        assertEquals(1, translation.getX());
        assertEquals(-1024, translation.getY());
        assertEquals(3, translation.getZ());
        var scale = (com.github.retrooper.packetevents.util.Vector3f) indexed.get(12).getValue();
        assertEquals(2, scale.getX());
        assertEquals(3, scale.getY());
        assertEquals(4, scale.getZ());
        for (int id : List.of(13, 14)) assertSame(EntityDataTypes.QUATERNION, indexed.get(id).getType());
        Quaternion4f left = (Quaternion4f) indexed.get(13).getValue();
        Quaternion4f right = (Quaternion4f) indexed.get(14).getValue();
        assertEquals(0.1f, left.getX());
        assertEquals(0.4f, left.getW());
        assertEquals(0.5f, right.getX());
        assertEquals(0.8f, right.getW());
        for (int id : List.of(17, 20, 21)) assertSame(EntityDataTypes.FLOAT, indexed.get(id).getType());
        assertEquals(3f, indexed.get(17).getValue());
        assertEquals(0f, indexed.get(20).getValue());
        assertEquals(0f, indexed.get(21).getValue());
        assertSame(EntityDataTypes.ITEMSTACK, indexed.get(23).getType());
        assertSame(item, indexed.get(23).getValue());
        assertSame(EntityDataTypes.BYTE, indexed.get(24).getType());
        assertEquals((byte) 8, indexed.get(24).getValue());
    }

    @Test
    void onlyTheChangedTransformFieldIsSentAndUnchangedPoseSendsNothing() {
        Transformation original = pose();
        Transformation next = DisplayMetadata.copy(original);
        assertTrue(DisplayMetadata.delta(original, next).isEmpty());
        next.getTranslation().x += 1;
        assertEquals(List.of(11), DisplayMetadata.delta(original, next).stream().map(EntityData::getIndex).toList());
        next.getTranslation().x -= 1;
        assertTrue(DisplayMetadata.delta(original, next).isEmpty(), "a change reverted before flush is not a packet");
        next.getLeftRotation().y += 0.01f;
        assertEquals(List.of(13), DisplayMetadata.delta(original, next).stream().map(EntityData::getIndex).toList());
        next.getScale().z += 1;
        next.getRightRotation().x += 0.01f;
        assertEquals(List.of(12, 13, 14), DisplayMetadata.delta(original, next).stream().map(EntityData::getIndex).toList());
        assertEquals(0.2f, original.getLeftRotation().y, "pose snapshots must not alias mutable JOML values");
    }

    @Test
    void allBukkitItemContextsHaveExplicitProtocolIds() {
        ItemDisplayTransform[] expected = { ItemDisplayTransform.NONE, ItemDisplayTransform.THIRDPERSON_LEFTHAND,
                ItemDisplayTransform.THIRDPERSON_RIGHTHAND, ItemDisplayTransform.FIRSTPERSON_LEFTHAND,
                ItemDisplayTransform.FIRSTPERSON_RIGHTHAND, ItemDisplayTransform.HEAD, ItemDisplayTransform.GUI,
                ItemDisplayTransform.GROUND, ItemDisplayTransform.FIXED };
        ItemStack item = mock(ItemStack.class);
        for (byte id = 0; id < expected.length; id++) {
            var data = DisplayMetadata.initial(item, expected[id], pose());
            assertEquals(id, data.getLast().getValue());
        }
    }
}
