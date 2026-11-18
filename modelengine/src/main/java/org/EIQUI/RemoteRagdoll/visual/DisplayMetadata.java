package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.util.Quaternion4f;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** 26.3 Display/ItemDisplay DATA_* declaration order; evidence is in PORTING.md. */
final class DisplayMetadata {
    static final int INTERPOLATION_DELAY = 8;
    static final int INTERPOLATION_DURATION = 9;
    static final int TELEPORT_DURATION = 10;
    static final int TRANSLATION = 11;
    static final int SCALE = 12;
    static final int LEFT_ROTATION = 13;
    static final int RIGHT_ROTATION = 14;
    static final int VIEW_RANGE = 17;
    static final int WIDTH = 20;
    static final int HEIGHT = 21;
    static final int ITEM = 23;
    static final int ITEM_TRANSFORM = 24;

    private DisplayMetadata() {}

    static List<EntityData<?>> initial(ItemStack item, ItemDisplayTransform context, Transformation pose) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, true)); // Entity.DATA_NO_GRAVITY
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(INTERPOLATION_DURATION, EntityDataTypes.INT, 1));
        data.add(new EntityData<>(TELEPORT_DURATION, EntityDataTypes.INT, 1));
        data.add(vector(TRANSLATION, pose.getTranslation()));
        data.add(vector(SCALE, pose.getScale()));
        data.add(rotation(LEFT_ROTATION, pose.getLeftRotation()));
        data.add(rotation(RIGHT_ROTATION, pose.getRightRotation()));
        data.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, DisplayTrackingRange.VIEW_RANGE));
        data.add(new EntityData<>(WIDTH, EntityDataTypes.FLOAT, 0f));
        data.add(new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, 0f));
        data.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item));
        data.add(new EntityData<>(ITEM_TRANSFORM, EntityDataTypes.BYTE, contextId(context)));
        return data;
    }

    static List<EntityData<?>> delta(Transformation previous, Transformation current) {
        List<EntityData<?>> data = new ArrayList<>(4);
        if (!previous.getTranslation().equals(current.getTranslation())) data.add(vector(TRANSLATION, current.getTranslation()));
        if (!previous.getScale().equals(current.getScale())) data.add(vector(SCALE, current.getScale()));
        if (!previous.getLeftRotation().equals(current.getLeftRotation())) data.add(rotation(LEFT_ROTATION, current.getLeftRotation()));
        if (!previous.getRightRotation().equals(current.getRightRotation())) data.add(rotation(RIGHT_ROTATION, current.getRightRotation()));
        return data;
    }

    static Transformation copy(Transformation pose) {
        return new Transformation(new Vector3f(pose.getTranslation()), new Quaternionf(pose.getLeftRotation()),
                new Vector3f(pose.getScale()), new Quaternionf(pose.getRightRotation()));
    }

    private static EntityData<?> vector(int index, Vector3f value) {
        return new EntityData<>(index, EntityDataTypes.VECTOR3F,
                new com.github.retrooper.packetevents.util.Vector3f(value.x, value.y, value.z));
    }

    private static EntityData<?> rotation(int index, Quaternionf value) {
        return new EntityData<>(index, EntityDataTypes.QUATERNION, new Quaternion4f(value.x, value.y, value.z, value.w));
    }

    private static byte contextId(ItemDisplayTransform context) {
        // Explicit protocol mapping: never rely on a Bukkit enum ordinal.
        return switch (context) {
            case NONE -> 0;
            case THIRDPERSON_LEFTHAND -> 1;
            case THIRDPERSON_RIGHTHAND -> 2;
            case FIRSTPERSON_LEFTHAND -> 3;
            case FIRSTPERSON_RIGHTHAND -> 4;
            case HEAD -> 5;
            case GUI -> 6;
            case GROUND -> 7;
            case FIXED -> 8;
        };
    }
}
