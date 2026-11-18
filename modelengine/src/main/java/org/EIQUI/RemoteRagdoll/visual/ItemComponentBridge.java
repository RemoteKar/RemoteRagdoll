package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/** Verifies the actual 26.3 network component codecs before exposing a fake part. */
public final class ItemComponentBridge {
    private ItemComponentBridge() {}

    public static ItemStack convert(org.bukkit.inventory.ItemStack original) {
        // These conversions use NMS's item stream codec and PacketEvents' item reader/writer,
        // rather than reconstructing the item from a lossy Bukkit metadata subset.
        ItemStack packetItem = SpigotConversionUtil.fromBukkitItemStack(original);
        org.bukkit.inventory.ItemStack restored = SpigotConversionUtil.toBukkitItemStack(packetItem);
        if (!net.minecraft.world.item.ItemStack.matches(
                CraftItemStack.asNMSCopy(original), CraftItemStack.asNMSCopy(restored))) {
            throw new IllegalArgumentException("PacketEvents item component round trip changed " + original.getType());
        }
        return packetItem;
    }
}
