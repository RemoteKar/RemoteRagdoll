package kr.gcb.dungeon.model;

import java.util.function.Supplier;
import kr.gcb.dungeon.physics.PhysicsWorld;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Whether a body goes to the bottom or floats (the user): a dead thing in water floats, unless it
 * is wearing a whole suit of iron or heavier, or was carrying a pack full enough to drag it down.
 */
public final class Sinking {

    /** How many of a player's thirty-six pack slots have to be full before the pack sinks them. */
    public static final int FULL_PACK = 27;

    private Sinking() {}

    /** Iron, gold, diamond or netherite - chain and leather float. */
    static boolean metal(ItemStack piece) {
        if (piece == null || piece.getType().isAir()) return false;
        String name = piece.getType().name();
        return name.startsWith("IRON_") || name.startsWith("GOLDEN_")
                || name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_");
    }

    /** A whole suit of metal: all four pieces. */
    public static boolean plated(EntityEquipment worn) {
        if (worn == null) return false;
        return metal(worn.getHelmet()) && metal(worn.getChestplate())
                && metal(worn.getLeggings()) && metal(worn.getBoots());
    }

    /** The same from loose pieces: helmet, chest, legs, boots, in that order (a companion's gear). */
    public static boolean plated(ItemStack... pieces) {
        if (pieces == null || pieces.length < 4) return false;
        for (int at = 0; at < 4; at++) if (!metal(pieces[at])) return false;
        return true;
    }

    /** A pack with {@link #FULL_PACK} or more of its slots taken. */
    public static boolean laden(HumanEntity who) {
        int full = 0;
        ItemStack[] pack = who.getInventory().getStorageContents();
        for (ItemStack item : pack) if (item != null && !item.getType().isAir()) full++;
        return full >= FULL_PACK;
    }

    public static boolean heavy(LivingEntity who) {
        if (who == null) return false;
        if (plated(who.getEquipment())) return true;
        return who instanceof HumanEntity human && laden(human);
    }

    /** Lays a body out with every limb made meanwhile marked as sinking or not. */
    public static <T> T laying(PhysicsWorld physics, boolean heavy, Supplier<T> make) {
        if (physics == null) return make.get();
        physics.heavy(heavy);
        try {
            return make.get();
        } finally {
            physics.heavy(false);
        }
    }

    /** Whether this block is somewhere a body is in the water. */
    public static boolean wet(org.bukkit.block.Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.KELP
                || type == Material.KELP_PLANT || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS) {
            return true;
        }
        return block.getBlockData() instanceof org.bukkit.block.data.Waterlogged soaked && soaked.isWaterlogged();
    }
}
