package org.EIQUI.RemoteRagdoll.visual;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.EIQUI.RemoteRagdoll.Ragdoll;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Main-thread owner of all fake displays, including displays detached from physics. */
public final class PacketRenderManager implements Listener {
    public static final int VIEWER_INTERVAL = 10;
    private static PacketRenderManager instance;
    private final JavaPlugin plugin;
    private final Function<World, DisplayTrackingRange> rangeLoader;
    private final Map<World, DisplayTrackingRange> trackingRanges = new HashMap<>();
    private final Set<PacketVisual> visuals = new HashSet<>();
    private final Set<PacketVisual> dirty = new HashSet<>();
    private final Set<UUID> pendingRefresh = new HashSet<>();
    private record Outgoing(Supplier<PacketWrapper<?>> packet, int entityId, boolean spawn, boolean destroy) {}
    private final Map<UUID, List<Outgoing>> outgoing = new LinkedHashMap<>();
    // Keep cleanup ownership after a partially sent batch, even if its visual was removed.
    private final Map<UUID, Set<Integer>> possibleEntities = new HashMap<>();
    private final Set<UUID> retryViewers = new HashSet<>();
    private BukkitTask task;
    private int ticks;
    private long lastSendWarning;

    private PacketRenderManager(JavaPlugin plugin) { this(plugin, DisplayTrackingRange::load); }

    PacketRenderManager(JavaPlugin plugin, Function<World, DisplayTrackingRange> rangeLoader) {
        this.plugin = plugin;
        this.rangeLoader = rangeLoader;
    }

    public static boolean initialize(JavaPlugin plugin) {
        requireMainThread();
        if (instance != null) return true;
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents") || PacketEvents.getAPI() == null
                || !PacketEvents.getAPI().isInitialized() || PacketEvents.getAPI().isTerminated()) return false;
        if (PacketEvents.getAPI().getServerManager().getVersion() != ServerVersion.V_26_3) {
            throw new IllegalStateException("Packet display metadata is verified only for Minecraft 26.3");
        }
        PacketRenderManager manager = new PacketRenderManager(plugin);
        Bukkit.getPluginManager().registerEvents(manager, plugin);
        try {
            for (World world : Bukkit.getWorlds()) manager.trackingRange(world);
            manager.task = Bukkit.getScheduler().runTaskTimer(plugin, manager::tick, 1L, 1L);
            instance = manager;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            HandlerList.unregisterAll(manager);
            throw failure;
        }
    }

    public static boolean isReady() { return instance != null; }

    static String diagnostics() {
        PacketRenderManager manager=instance;
        if(manager==null)return "ready=false packetVisuals=0 viewerEntityIds=0 queuedPackets=0";
        int possible=manager.possibleEntities.values().stream().mapToInt(Set::size).sum();
        int queued=manager.outgoing.values().stream().mapToInt(List::size).sum();
        return "ready=true packetVisuals="+manager.visuals.size()+" viewerEntityIds="+possible+" queuedPackets="+queued;
    }

    public static PartVisual create(Location location, ItemStack item, ItemDisplayTransform context, Transformation pose) {
        requireMainThread();
        PacketRenderManager manager = instance;
        if (manager == null) throw new IllegalStateException("Packet rendering is not initialized");
        if (location.getWorld() == null) throw new IllegalArgumentException("A visual needs a world");
        // Conversion must succeed before allocating/registering any visual or sending a spawn.
        var packetItem = ItemComponentBridge.convert(item);
        int entityId = Bukkit.getUnsafe().nextEntityId(location.getWorld());
        PacketVisual visual = new PacketVisual(manager, entityId, location, packetItem, context, pose);
        manager.visuals.add(visual);
        try {
            manager.refreshVisual(visual);
            manager.sendQueued(); // First spawn is immediate, not delayed by the viewer interval.
            return visual;
        } catch (RuntimeException | LinkageError failure) {
            manager.remove(visual);
            throw failure;
        }
    }

    /** Called after physics and by the manager task; the second call has no duplicate work. */
    public static void flush() {
        if (instance == null) return;
        requireMainThread();
        instance.collectChanges();
        instance.sendQueued();
    }

    public static void clear(World world) {
        if (instance == null) return;
        requireMainThread();
        for (PacketVisual visual : List.copyOf(instance.visuals)) {
            if (world.equals(visual.location().getWorld())) instance.removeQueued(visual);
        }
        instance.sendQueued();
    }

    public static void clearAll() {
        if (instance == null) return;
        requireMainThread();
        for (PacketVisual visual : List.copyOf(instance.visuals)) instance.removeQueued(visual);
        instance.recoverViewers(); // Also destroy orphan IDs retained after a failed earlier batch.
        instance.sendQueued();
    }

    public static void shutdown() {
        if (instance == null) return;
        requireMainThread();
        PacketRenderManager manager = instance;
        try {
            clearAll();
        } finally {
            if (manager.task != null) manager.task.cancel();
            HandlerList.unregisterAll(manager);
            manager.pendingRefresh.clear();
            manager.outgoing.clear();
            manager.dirty.clear();
            manager.possibleEntities.clear();
            manager.retryViewers.clear();
            manager.trackingRanges.clear();
            instance = null;
        }
    }

    static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Ragdoll visuals must run on the server main thread");
    }

    void markDirty(PacketVisual visual) { dirty.add(visual); }

    void enqueue(UUID viewer, Supplier<PacketWrapper<?>> packet) {
        outgoing.computeIfAbsent(viewer, ignored -> new ArrayList<>()).add(new Outgoing(packet, 0, false, false));
    }

    void enqueueSpawn(UUID viewer, int entityId, Supplier<PacketWrapper<?>> packet) {
        possibleEntities.computeIfAbsent(viewer, ignored -> new HashSet<>()).add(entityId);
        outgoing.computeIfAbsent(viewer, ignored -> new ArrayList<>()).add(new Outgoing(packet, entityId, true, false));
    }

    void enqueueDestroy(UUID viewer, int entityId) {
        outgoing.computeIfAbsent(viewer, ignored -> new ArrayList<>()).add(
                new Outgoing(() -> new WrapperPlayServerDestroyEntities(entityId), entityId, false, true));
    }

    void remove(PacketVisual visual) {
        removeQueued(visual);
        sendQueued();
    }

    private void removeQueued(PacketVisual visual) {
        visual.destroy();
        visuals.remove(visual);
        dirty.remove(visual);
    }

    private void tick() {
        collectChanges();
        recoverViewers();
        if (++ticks % VIEWER_INTERVAL == 0 && !visuals.isEmpty()) refreshViewers();
        sendQueued();
    }

    private void collectChanges() {
        for (PacketVisual visual : dirty) visual.collectChanges();
        dirty.clear();
    }

    private void refreshViewers() {
        record Viewer(Location location, int viewDistance) {}
        Map<UUID, Viewer> players = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!pendingRefresh.contains(player.getUniqueId())) {
                players.put(player.getUniqueId(), new Viewer(player.getLocation(), player.getClientViewDistance()));
            }
        }
        for (PacketVisual visual : visuals) {
            for (UUID oldViewer : visual.viewers.snapshot()) {
                // A deferred refresh keeps its existing membership until the final location is known.
                if (!pendingRefresh.contains(oldViewer) && !players.containsKey(oldViewer)) {
                    visual.updateViewer(oldViewer, false);
                }
            }
            for (var player : players.entrySet()) {
                Viewer viewer = player.getValue();
                visual.updateViewer(player.getKey(), inRange(visual.location(), viewer.location(), viewer.viewDistance()));
            }
        }
    }

    private void refreshVisual(PacketVisual visual) {
        for (Player player : visual.location().getWorld().getPlayers()) {
            UUID id = player.getUniqueId();
            if (!pendingRefresh.contains(id)) {
                visual.updateViewer(id, inRange(visual.location(), player.getLocation(), player.getClientViewDistance()));
            }
        }
    }

    /** World maximum in blocks; each viewer's requested chunk distance may reduce it further. */
    public double trackingRange(World world) {
        requireMainThread();
        return range(world).horizontal();
    }

    private DisplayTrackingRange range(World world) {
        return trackingRanges.computeIfAbsent(world, key -> {
            try {
                return rangeLoader.apply(key);
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not read display tracking range for " + key.getName()
                                + "; using Spigot default " + DisplayTrackingRange.DEFAULT_DISPLAY_RANGE, failure);
                return DisplayTrackingRange.fallback(key.getViewDistance());
            }
        });
    }

    boolean inRange(Location visual, Location player, int clientViewDistance) {
        return visual.getWorld().equals(player.getWorld()) && range(visual.getWorld()).contains(
                visual.getX() - player.getX(), visual.getY() - player.getY(), visual.getZ() - player.getZ(),
                clientViewDistance);
    }

    private void sendQueued() {
        if (outgoing.isEmpty()) return;
        Map<UUID, List<Outgoing>> batches = new LinkedHashMap<>(outgoing);
        outgoing.clear();
        PlayerManager transport = PacketEvents.getAPI().getPlayerManager();
        for (var entry : batches.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                forget(entry.getKey());
                continue;
            }
            List<Outgoing> packets = entry.getValue();
            try {
                if (transport.getChannel(player) == null) {
                    retryViewers.add(entry.getKey());
                    continue;
                }
                // PacketEvents schedules channel writes correctly. Flush only the final packet
                // for this viewer, including all changed parts in the current physics tick.
                for (int i = 0; i < packets.size(); i++) {
                    PacketWrapper<?> packet = packets.get(i).packet().get();
                    if (i + 1 == packets.size()) transport.sendPacket(player, packet);
                    else transport.writePacket(player, packet);
                }
                // Commit cleanup bookkeeping only after the whole write/flush batch succeeds.
                Set<Integer> possible = possibleEntities.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>());
                for (Outgoing packet : packets) {
                    if (packet.spawn()) possible.add(packet.entityId());
                    if (packet.destroy()) possible.remove(packet.entityId());
                }
                if (possible.isEmpty()) possibleEntities.remove(entry.getKey());
            } catch (RuntimeException | LinkageError failure) {
                retryViewers.add(entry.getKey());
                long now = System.currentTimeMillis();
                if (now - lastSendWarning > 10_000L) {
                    plugin.getLogger().log(Level.WARNING, "Could not send ragdoll display packets; viewer will be retried", failure);
                    lastSendWarning = now;
                }
            }
        }
    }

    private void recoverViewers() {
        for (UUID id : Set.copyOf(retryViewers)) {
            retryViewers.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                forget(id);
                continue;
            }
            // Discard obsolete deltas and rebuild from the latest pose. Destroy every entity
            // that may have reached this viewer, including parts already removed server-side.
            outgoing.remove(id);
            for (int entityId : possibleEntities.getOrDefault(id, Set.of())) enqueueDestroy(id, entityId);
            Location location = player.getLocation();
            int viewDistance = player.getClientViewDistance();
            for (PacketVisual visual : visuals) {
                visual.viewers.forget(id);
                visual.updateViewer(id, !pendingRefresh.contains(id) && inRange(visual.location(), location, viewDistance));
            }
        }
    }

    private void forget(UUID player) {
        for (PacketVisual visual : visuals) visual.viewers.forget(player);
        outgoing.remove(player);
        pendingRefresh.remove(player);
        possibleEntities.remove(player);
        retryViewers.remove(player);
    }

    private void reset(UUID player) {
        collectChanges();
        for (PacketVisual visual : visuals) visual.updateViewer(player, false);
        sendQueued();
        refreshNextTick(player);
    }

    private void refreshViewer(Player player, Location location) {
        collectChanges();
        UUID id = player.getUniqueId();
        int viewDistance = player.getClientViewDistance();
        for (PacketVisual visual : visuals) visual.updateViewer(id, inRange(visual.location(), location, viewDistance));
        sendQueued();
    }

    private void refreshNextTick(UUID id) {
        if (!pendingRefresh.add(id)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (instance != this || !pendingRefresh.remove(id)) return;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) return;
            refreshViewer(player, player.getLocation());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { refreshNextTick(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { reset(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // 26.3 handleRespawn retains ClientLevel when the dimension key is unchanged.
        // Cross-world respawns rebuild only through onWorldChange, after the client reset.
        if (event.getPlayer().getWorld().equals(event.getRespawnLocation().getWorld())) {
            refreshNextTick(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        // World changes have their own post-transfer event; short skill teleports need no work.
        if (to == null || !from.getWorld().equals(to.getWorld())
                || inRange(from, to, event.getPlayer().getClientViewDistance())) return;
        UUID id = event.getPlayer().getUniqueId();
        // An outstanding respawn/world/join refresh must wait until the transfer is complete.
        if (!pendingRefresh.contains(id)) refreshViewer(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) { refreshNextTick(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        try {
            clear(event.getWorld());
        } finally {
            trackingRanges.remove(event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        trackingRanges.remove(event.getWorld());
        trackingRange(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("packetevents")) {
            Ragdoll.PACKET_RENDER = false;
            plugin.getLogger().warning("PacketEvents was disabled; new ragdoll parts will use real entities");
            shutdown();
        }
    }
}
