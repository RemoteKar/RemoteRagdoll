package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.vector.positionpath.LinearPositionPath;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An ItemDisplay that has no Bukkit/NMS entity or server entity tick. */
public final class PacketVisual implements PartVisual {
    private final PacketRenderManager owner;
    private final int entityId;
    private final UUID uuid = UUID.randomUUID();
    private final ItemStack item;
    private final ItemDisplayTransform context;
    final ViewerState viewers = new ViewerState();
    private Location location;
    private Location sentLocation;
    private Transformation pose;
    private Transformation sentPose;
    private boolean valid = true;

    PacketVisual(PacketRenderManager owner, int entityId, Location location, ItemStack item,
                 ItemDisplayTransform context, Transformation pose) {
        this.owner = owner;
        this.entityId = entityId;
        this.location = location.clone();
        this.sentLocation = location.clone();
        this.item = item;
        this.context = context;
        this.pose = DisplayMetadata.copy(pose);
        this.sentPose = DisplayMetadata.copy(pose);
    }

    @Override public void setTransform(Vector3f translation, Quaternionf left, Vector3f scale, Quaternionf right) {
        PacketRenderManager.requireMainThread();
        if (!valid) return;
        if (pose.getTranslation().equals(translation) && pose.getLeftRotation().equals(left)
                && pose.getScale().equals(scale) && pose.getRightRotation().equals(right)) return;
        pose = new Transformation(new Vector3f(translation), new Quaternionf(left), new Vector3f(scale), new Quaternionf(right));
        owner.markDirty(this);
    }

    @Override public void moveTo(Location destination) {
        PacketRenderManager.requireMainThread();
        if (!valid) return;
        if (!location.getWorld().equals(destination.getWorld())) {
            throw new IllegalArgumentException("A ragdoll visual cannot move to another world");
        }
        if (samePosition(location, destination)) return;
        location = destination.clone();
        owner.markDirty(this);
    }

    @Override public Transformation getTransformation() { return DisplayMetadata.copy(pose); }
    @Override public Location getLocation() { return location.clone(); }
    @Override public boolean isValid() { return valid; }
    @Override public boolean isPacket() { return true; }

    @Override public void remove() {
        PacketRenderManager.requireMainThread();
        owner.remove(this);
    }

    Location location() { return location; }

    void updateViewer(UUID player, boolean visible) {
        switch (viewers.update(player, visible && valid)) {
            case SPAWN -> {
                Location spawn = location.clone();
                List<EntityData<?>> metadata = DisplayMetadata.initial(item, context, pose);
                owner.enqueueSpawn(player, entityId, () -> new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), EntityTypes.ITEM_DISPLAY,
                        new Vector3d(spawn.getX(), spawn.getY(), spawn.getZ()), spawn.getPitch(), spawn.getYaw(), spawn.getYaw(), 0, Optional.empty()));
                owner.enqueue(player, () -> new WrapperPlayServerEntityMetadata(entityId, metadata));
            }
            case DESTROY -> owner.enqueueDestroy(player, entityId);
            case NONE -> { }
        }
    }

    void collectChanges() {
        if (!valid) return;
        List<EntityData<?>> metadata = DisplayMetadata.delta(sentPose, pose);
        Location moved = samePosition(sentLocation, location) ? null : location.clone();
        if (metadata.isEmpty() && moved == null) return;
        for (UUID viewer : viewers.snapshot()) {
            if (!metadata.isEmpty()) owner.enqueue(viewer, () -> new WrapperPlayServerEntityMetadata(entityId, metadata));
            if (moved != null) owner.enqueue(viewer, () -> new WrapperPlayServerEntityPositionSync(entityId,
                    new LinearPositionPath(new Vector3d(moved.getX(), moved.getY(), moved.getZ())), moved.getYaw(), moved.getPitch(), false));
        }
        sentPose = DisplayMetadata.copy(pose);
        sentLocation = location.clone();
    }

    static boolean samePosition(Location first, Location second) {
        return first.getWorld().equals(second.getWorld()) && first.getX() == second.getX()
                && first.getY() == second.getY() && first.getZ() == second.getZ()
                && first.getYaw() == second.getYaw() && first.getPitch() == second.getPitch();
    }

    void destroy() {
        if (!valid) return;
        valid = false;
        for (UUID viewer : viewers.snapshot()) owner.enqueueDestroy(viewer, entityId);
        viewers.clear();
    }
}
