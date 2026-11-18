package org.EIQUI.RemoteRagdoll.visual;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** The original Bukkit Display calls, including their ordering and interpolation settings. */
public final class EntityVisual implements PartVisual {
    private final Display display;

    public EntityVisual(Display display) { this.display = display; }

    @Override
    public void setTransform(Vector3f translation, Quaternionf left, Vector3f scale, Quaternionf right) {
        display.setTransformation(new Transformation(translation, left, scale, right));
    }

    @Override public void moveTo(Location location) { display.teleport(location); }
    @Override public Transformation getTransformation() { return display.getTransformation(); }
    @Override public Location getLocation() { return display.getLocation(); }
    @Override public boolean isValid() { return display.isValid(); }
    @Override public void remove() { display.remove(); }
    @Override public List<Display> getDisplays() { return List.of(display); }

    @Override
    public void configureInterpolation() {
        display.setTeleportDuration(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
    }
}
