import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spigotmc.TrackingRange;

/** Test-only plugin; never included in the published RemoteRagdoll jar. */
public final class PacketComponentProbe extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                verifyMetadata();
                for (boolean slim : new boolean[] {false, true}) {
                    for (boolean signed : new boolean[] {false, true}) {
                        verifyHead(slim, signed);
                    }
                }
                getLogger().info("PACKET_COMPONENT_PROBE_OK: 4 player_head roundtrips; profile/name/UUID/textures/signature, item_model, CMD floats/flags/strings/colors; NMS metadata IDs/types");
                verifyTrackingRanges();
                verifyRenderer();
                getLogger().info("PACKET_RENDER_PROBE_OK: ready, packet-only creation, pose/move, frozen rotation, removal, detach retention, world/global clear, real renderer toggle");
            } catch (Throwable failure) {
                getLogger().log(Level.SEVERE, "PACKET_COMPONENT_PROBE_FAILED", failure);
            }
        });
    }

    private void verifyHead(boolean slim, boolean signed) throws Exception {
        ItemStack original = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) original.getItemMeta();
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), slim ? "SlimProbe" : "NormalProbe");
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\""
                + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        String texture = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        profile.setProperty(new ProfileProperty("textures", texture, signed ? "probe-signature-bytes" : null));
        meta.setPlayerProfile(profile);
        meta.setItemModel(new NamespacedKey("gcb", "player_limb_probe"));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setFloats(List.of(slim ? 7f : 2f, 0.25f));
        cmd.setFlags(List.of(true, false));
        cmd.setStrings(List.of("limb_probe", slim ? "slim" : "normal"));
        cmd.setColors(List.of(Color.fromRGB(0x123456), Color.fromRGB(0xABCDEF)));
        meta.setCustomModelDataComponent(cmd);
        original.setItemMeta(meta);
        var converted = SpigotConversionUtil.fromBukkitItemStack(original);
        ItemStack restored = SpigotConversionUtil.toBukkitItemStack(converted);
        if (!net.minecraft.world.item.ItemStack.matches(CraftItemStack.asNMSCopy(original), CraftItemStack.asNMSCopy(restored))) {
            throw new AssertionError("Component loss, slim=" + slim + ", signed=" + signed
                    + " before=" + original.getItemMeta().getAsComponentString()
                    + " after=" + restored.getItemMeta().getAsComponentString());
        }
        SkullMeta restoredMeta = (SkullMeta) restored.getItemMeta();
        if (!profile.getProperties().equals(restoredMeta.getPlayerProfile().getProperties())
                || !meta.getItemModel().equals(restoredMeta.getItemModel())
                || !cmd.getFloats().equals(restoredMeta.getCustomModelDataComponent().getFloats())
                || !cmd.getFlags().equals(restoredMeta.getCustomModelDataComponent().getFlags())
                || !cmd.getStrings().equals(restoredMeta.getCustomModelDataComponent().getStrings())
                || !cmd.getColors().equals(restoredMeta.getCustomModelDataComponent().getColors())) {
            throw new AssertionError("Explicit profile/model/CMD field mismatch");
        }
        getLogger().info("PLAYER_HEAD_ROUNDTRIP_OK slim=" + slim + " signed=" + signed);
        // Exercise the exact production bridge as well as the independently checked raw conversion.
        Class<?> bridge = remoteClass("visual.ItemComponentBridge");
        bridge.getMethod("convert", ItemStack.class).invoke(null, original);
    }

    private Class<?> remoteClass(String suffix) throws ClassNotFoundException {
        return Class.forName("org.EIQUI.RemoteRagdoll." + suffix, true,
                Bukkit.getPluginManager().getPlugin("RemoteRagdoll").getClass().getClassLoader());
    }

    private void verifyTrackingRanges() throws Exception {
        Class<?> manager = remoteClass("visual.PacketRenderManager");
        Field instance = manager.getDeclaredField("instance");
        instance.setAccessible(true);
        Object renderer = instance.get(null);
        if (renderer == null) throw new AssertionError("Packet manager is not initialized");
        var rangeMethod = manager.getMethod("trackingRange", World.class);
        YamlConfiguration spigot = YamlConfiguration.loadConfiguration(new File("spigot.yml"));
        for (World world : Bukkit.getWorlds()) {
            var level = ((CraftWorld) world).getHandle();
            int yamlRange = spigot.getInt("world-settings." + world.getName()
                    + ".entity-tracking-range.display",
                    spigot.getInt("world-settings.default.entity-tracking-range.display", 128));
            int loadedRange = level.spigotConfig.displayTrackingRange;
            // Allocate an unregistered real Display to independently exercise Spigot's type rule.
            Display.ItemDisplay display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            int entityRange = TrackingRange.getEntityTrackingRange(display,
                    display.getType().clientTrackingRange() * 16);
            if (yamlRange != loadedRange || loadedRange != entityRange) {
                throw new AssertionError("Display range mismatch world=" + world.getName()
                        + " spigot.yml=" + yamlRange + " loaded=" + loadedRange + " entity=" + entityRange);
            }
            int scaledRange = level.getServer().getScaledTrackingDistance(entityRange);
            int viewCap = world.getViewDistance() * 16;
            double expected = Math.min(Math.min(scaledRange, viewCap), 3f * 64);
            double actual = ((Number) rangeMethod.invoke(renderer, world)).doubleValue();
            if (Double.compare(expected, actual) != 0) {
                throw new AssertionError("Packet range mismatch world=" + world.getName()
                        + " expected=" + expected + " actual=" + actual);
            }
            getLogger().info("PACKET_TRACKING_RANGE_WORLD_OK world=" + world.getName()
                    + " spigotYmlDisplay=" + yamlRange + " loadedDisplay=" + loadedRange
                    + " entityTrackingRange=" + entityRange + " scaledTrackingRange=" + scaledRange
                    + " viewDistanceChunks=" + world.getViewDistance() + " viewCap=" + viewCap
                    + " clientViewRangeCap=192 computedRange=" + actual);
        }
        getLogger().info("PACKET_TRACKING_RANGE_PROBE_OK: worlds=" + Bukkit.getWorlds().size());
    }

    private void verifyRenderer() throws Exception {
        Class<?> manager = remoteClass("visual.PacketRenderManager");
        Class<?> visuals = remoteClass("visual.Visuals");
        Class<?> visual = remoteClass("visual.PartVisual");
        Class<?> bodies = remoteClass("PhysicsBody");
        Class<?> worlds = remoteClass("PhysicsWorld");
        Class<?> ragdoll = remoteClass("Ragdoll");
        if (!(boolean) manager.getMethod("isReady").invoke(null)
                || !ragdoll.getField("PACKET_RENDER").getBoolean(null)) {
            throw new AssertionError("Packet backend was not enabled");
        }
        World world = Bukkit.getWorlds().getFirst();
        Location at = world.getSpawnLocation().add(0, 5, 0);
        // A server without players need not have its spawn chunk loaded/ticking in 26.3.
        // Pin this test chunk so getEntitiesByClass observes real entity registration.
        at.getChunk().addPluginChunkTicket(this);
        Transformation pose = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf());
        var create = visuals.getMethod("create", Location.class, ItemStack.class,
                ItemDisplay.ItemDisplayTransform.class, Transformation.class, boolean.class);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        long before = world.getEntitiesByClass(ItemDisplay.class).size();
        Object first = create.invoke(null, at, head, ItemDisplay.ItemDisplayTransform.HEAD, pose, true);
        if (!(boolean) visual.getMethod("isPacket").invoke(first)
                || !(boolean) visual.getMethod("isValid").invoke(first)
                || !((List<?>) visual.getMethod("getDisplays").invoke(first)).isEmpty()
                || world.getEntitiesByClass(ItemDisplay.class).size() != before) {
            throw new AssertionError("Packet visual allocated a Bukkit display or was invalid");
        }
        visual.getMethod("setTransform", Vector3f.class, Quaternionf.class, Vector3f.class, Quaternionf.class)
                .invoke(first, new Vector3f(0, 1, 0), new Quaternionf(), new Vector3f(1), new Quaternionf());
        visual.getMethod("moveTo", Location.class).invoke(first, at.clone().add(1, 0, 0));
        visuals.getMethod("flush").invoke(null);
        visual.getMethod("remove").invoke(first);
        visual.getMethod("remove").invoke(first);
        if ((boolean) visual.getMethod("isValid").invoke(first)) throw new AssertionError("remove retained visual");

        Object detached = create.invoke(null, at, head, ItemDisplay.ItemDisplayTransform.HEAD, pose, true);
        var createBody = bodies.getDeclaredMethod("createVisuals", Location.class, List.class, Vector.class,
                float.class, int.class, Vector.class, int.class, double.class, int.class, int.class);
        createBody.setAccessible(true);
        Object body = createBody.invoke(null, at, List.of(detached), new Vector(0.1, 0.1, 0.1),
                1f, 20, new Vector(), worlds.getField("LAYER_DYNAMIC").getInt(null), 0d, -1, -1);
        if (body == null) throw new AssertionError("Physics body creation failed");
        bodies.getMethod("freeze").invoke(body);
        Quaternionf edited = new Quaternionf().rotateY(0.25f);
        bodies.getMethod("setRotation", Quaternionf.class).invoke(body, edited);
        Transformation frozenPose = (Transformation) visual.getMethod("getTransformation").invoke(detached);
        if (!edited.equals(frozenPose.getLeftRotation(), 0.00001f)) {
            throw new AssertionError("Explicit rotation was lost while the body was frozen");
        }
        bodies.getMethod("detach").invoke(body);
        if (!(boolean) visual.getMethod("isValid").invoke(detached)) throw new AssertionError("detach removed frozen pose");
        if (!((List<?>) bodies.getMethod("getDisplays").invoke(body)).isEmpty()) throw new AssertionError("packet getDisplays nonempty");
        worlds.getMethod("clearAll").invoke(null);
        if ((boolean) visual.getMethod("isValid").invoke(detached)) throw new AssertionError("clearAll retained detached pose");
        bodies.getMethod("destroy").invoke(body); // Exercise released guard after detach + clear.

        Object worldCleared = create.invoke(null, at, head, ItemDisplay.ItemDisplayTransform.HEAD, pose, true);
        worlds.getMethod("clearWorld", World.class).invoke(null, world);
        if ((boolean) visual.getMethod("isValid").invoke(worldCleared)) throw new AssertionError("clearWorld retained visual");

        ragdoll.getField("PACKET_RENDER").setBoolean(null, false);
        try {
            Object real = create.invoke(null, at, head, ItemDisplay.ItemDisplayTransform.HEAD, pose, true);
            try {
                boolean packet = (boolean) visual.getMethod("isPacket").invoke(real);
                var displays = (List<?>) visual.getMethod("getDisplays").invoke(real);
                int after = world.getEntitiesByClass(ItemDisplay.class).size();
                boolean valid = (boolean) visual.getMethod("isValid").invoke(real);
                getLogger().info("REAL_RENDER_CHECK packet=" + packet + " before=" + before
                        + " after=" + after + " displays=" + displays.size() + " valid=" + valid
                        + " chunkLoaded=" + at.isChunkLoaded() + " world=" + world.getUID());
                if (packet || displays.size() != 1 || !(displays.getFirst() instanceof ItemDisplay)
                        || !valid || after != before + 1) {
                    throw new AssertionError("Real renderer registration failed; see REAL_RENDER_CHECK");
                }
            } finally {
                visual.getMethod("remove").invoke(real);
            }
        } finally {
            ragdoll.getField("PACKET_RENDER").setBoolean(null, true);
            worlds.getMethod("clearAll").invoke(null);
            at.getChunk().removePluginChunkTicket(this);
        }
    }

    private static void verifyMetadata() throws Exception {
        check(Display.class, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID", 8, EntityDataSerializers.INT);
        check(Display.class, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID", 9, EntityDataSerializers.INT);
        check(Display.class, "DATA_POS_ROT_INTERPOLATION_DURATION_ID", 10, EntityDataSerializers.INT);
        check(Display.class, "DATA_TRANSLATION_ID", 11, EntityDataSerializers.VECTOR3);
        check(Display.class, "DATA_SCALE_ID", 12, EntityDataSerializers.VECTOR3);
        check(Display.class, "DATA_LEFT_ROTATION_ID", 13, EntityDataSerializers.QUATERNION);
        check(Display.class, "DATA_RIGHT_ROTATION_ID", 14, EntityDataSerializers.QUATERNION);
        check(Display.class, "DATA_VIEW_RANGE_ID", 17, EntityDataSerializers.FLOAT);
        check(Display.class, "DATA_WIDTH_ID", 20, EntityDataSerializers.FLOAT);
        check(Display.class, "DATA_HEIGHT_ID", 21, EntityDataSerializers.FLOAT);
        check(Display.ItemDisplay.class, "DATA_ITEM_STACK_ID", 23, EntityDataSerializers.ITEM_STACK);
        check(Display.ItemDisplay.class, "DATA_ITEM_DISPLAY_ID", 24, EntityDataSerializers.BYTE);
    }

    private static void check(Class<?> owner, String name, int id, Object serializer) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
        if (accessor.id() != id || accessor.serializer() != serializer) {
            throw new AssertionError(name + " expected id=" + id + " actual=" + accessor);
        }
    }
}
