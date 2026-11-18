/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.joml.Quaternionf;

/**
 * A puppet that really is an entity: the way everything worked before packets.
 *
 * <p>Kept because not everything wants to be a packet. A block thrown by an explosion is
 * one display that lives two seconds and is seen by whoever happens to be there; going
 * through the packet path for it would mean tracking viewers for something that does not
 * outlive the glance. Corpses are the opposite - a dozen pieces each, dozens at a time -
 * and those go the other way.
 */
public final class EntityPuppet implements Puppet {

    private final Display display;

    public EntityPuppet(Display display) {
        this.display = display;
        // Without these three the display lurches once per tick instead of gliding.
        display.setInterpolationDuration(1);
        display.setInterpolationDelay(0);
        display.setTeleportDuration(1);
    }

    @Override
    public void blind(org.bukkit.entity.Player viewer, boolean hidden) {
        // Whichever plugin carries this class (it was Gazadry by name; looking it up keeps the class portable).
        var plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(EntityPuppet.class);
        if (hidden) viewer.hideEntity(plugin, display);
        else viewer.showEntity(plugin, display);
    }

    @Override
    public void place(Location at, Quaternionf spin) {
        display.teleport(at);
        var t = display.getTransformation();
        t.getLeftRotation().set(spin);
        display.setTransformation(t);
    }

    @Override
    public void dispose() {
        if (display.isValid()) display.remove();
    }

    @Override
    public boolean alive() {
        return display.isValid();
    }
}
