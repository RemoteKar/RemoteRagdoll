package org.EIQUI.RemoteRagdoll.visual;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** Render target for the unchanged physics-to-display transform calculation. */
public interface PartVisual {
    void setTransform(Vector3f translation, Quaternionf left, Vector3f scale, Quaternionf right);
    void moveTo(Location location);
    Location getLocation();
    Transformation getTransformation();
    boolean isValid();
    void remove();

    default void configureInterpolation() {}
    default boolean isPacket() { return false; }
    default List<Display> getDisplays() { return List.of(); }
}
