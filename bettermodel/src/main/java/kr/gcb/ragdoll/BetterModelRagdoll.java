package kr.gcb.ragdoll;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import kr.gcb.dungeon.model.Corpses;
import kr.gcb.dungeon.model.Puppets;
import kr.gcb.dungeon.model.Shoves;
import kr.gcb.dungeon.physics.JoltNatives;
import kr.gcb.dungeon.physics.PhysicsWorld;

/**
 * A small plugin around the ragdoll code in {@code kr.gcb.dungeon}: a player who dies leaves a
 * body in their own skin (six limbs on five joints, simulated by Jolt against the blocks around
 * it, drawn as BetterModel skin slices sent by packets), it can be kicked and walked through,
 * and it goes after a while. The wiring is Graywater's {@code Bodies}, without its loot boxes.
 *
 * <p>BetterModel and the Jolt native are both optional at load time: without either the plugin
 * stays enabled and does nothing. Nothing here names a BetterModel type, so the classes that do
 * are never loaded on a server without it (see {@link Corpses}).
 */
public final class BetterModelRagdoll extends JavaPlugin implements Listener {
    /** Steve, for a player whose profile carries no skin. */
    private static final String PLAIN_SKIN =
            "http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";

    /** One Jolt world per Minecraft world, made when the first corpse there is. */
    private final Map<World, PhysicsWorld> worlds = new HashMap<>();
    /** Which way and how hard each player was last struck (blocks a second), and on which tick. */
    private final Map<UUID, Object[]> blows = new HashMap<>();
    /** Players whose dying figure is hidden from everyone else until they respawn. */
    private final Set<UUID> hidden = new HashSet<>();
    private boolean ready;
    private BukkitTask ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        // BetterModel is a soft dependency and enables first; wait one tick so every plugin is up.
        getServer().getScheduler().runTask(this, this::start);
    }

    private void start() {
        if (!Corpses.available()) {
            getLogger().warning("BetterModel is not enabled - ragdolls are off");
            return;
        }
        if (!JoltNatives.isAvailable(this)) return;   // logged there
        ready = true;
        ticker = getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Ragdolls on (Jolt physics, BetterModel skin slices)");
    }

    private void tick() {
        for (PhysicsWorld physics : worlds.values()) physics.tick();
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        for (PhysicsWorld physics : worlds.values()) physics.clearAll();
        worlds.clear();
        if (ready) Puppets.clear();
        for (UUID id : Set.copyOf(hidden)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) show(p);
        }
        hidden.clear();
    }

    /** The physics of a world, or null while ragdolls are off. */
    private PhysicsWorld physics(World world) {
        if (!ready || world == null) return null;
        return worlds.computeIfAbsent(world, w -> new PhysicsWorld(this, w));
    }

    /** Drops a body of that player at a place. False when none could be made. */
    private boolean lay(Player p, Location at, double damage, double whole, Vector push) {
        PhysicsWorld physics = physics(at.getWorld());
        if (physics == null) return false;
        int ticks = Math.max(1, getConfig().getInt("lifetime-seconds", 120)) * 20;
        try {
            PlayerTextures textures = p.getPlayerProfile().getTextures();
            if (textures.getSkin() != null) return Corpses.drop(this, physics, at, p, ticks, damage, whole, push);
            return Corpses.drop(this, physics, at, PLAIN_SKIN, false, ticks, p, damage, whole, push);
        } catch (Throwable t) {
            getLogger().warning("Could not make a ragdoll: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ dying

    /** Remembers which way a blow went, so that a body that dies of it falls that way. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!ready || !(e.getEntity() instanceof Player p)) return;
        Entity by = e.getDamager();
        Vector away = by instanceof Projectile shot
                ? shot.getVelocity().setY(0)
                : p.getLocation().toVector().subtract(by.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0e-4) return;
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double whole = max == null ? 20 : max.getValue();
        double speed = Math.min(4.0, 1.2 + e.getFinalDamage() / whole * 2.0);
        blows.put(p.getUniqueId(), new Object[] {away.normalize().multiply(speed), (long) Bukkit.getCurrentTick()});
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Object[] last = blows.remove(p.getUniqueId());
        if (!ready || !getConfig().getBoolean("on-death", true)) return;
        Vector push = last != null && Bukkit.getCurrentTick() - (Long) last[1] <= 20 ? (Vector) last[0] : new Vector();
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double whole = max == null ? 20 : max.getValue();
        // How hard the last blow was against a whole body: a scratch that finishes someone leaves them in one piece.
        @SuppressWarnings("deprecation")
        var cause = p.getLastDamageCause();
        double damage = cause == null ? 0 : cause.getFinalDamage();
        if (push.lengthSquared() > 36) damage = Math.max(damage, whole * 0.7);
        if (!lay(p, p.getLocation(), damage, whole, push)) return;
        if (getConfig().getBoolean("hide-dying-player", true)) {
            // The game's own dying figure would stand where the body lies.
            hidden.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.hidePlayer(this, p);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        show(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        show(e.getPlayer());
        blows.remove(e.getPlayer().getUniqueId());
    }

    private void show(Player p) {
        if (!hidden.remove(p.getUniqueId())) return;
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.showPlayer(this, p);
    }

    // ------------------------------------------------------------------ touching

    /** Swinging at a body kicks it. */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        double kick = getConfig().getDouble("kick-strength", 1.0);
        PhysicsWorld physics = worlds.get(e.getPlayer().getWorld());
        if (physics == null || kick <= 0 || physics.trackedCount() == 0 || !Shoves.handsOn(e.getPlayer())) return;
        Shoves.kick(physics, e.getPlayer(), kick);
    }

    /** Walking through a body pushes it aside. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        PhysicsWorld physics = worlds.get(e.getPlayer().getWorld());
        if (physics == null || physics.trackedCount() == 0 || !Shoves.handsOn(e.getPlayer())) return;
        Vector travel = e.getTo().toVector().subtract(e.getFrom().toVector());
        if (travel.lengthSquared() < 1.0e-6) return;
        Shoves.barge(physics, e.getPlayer(), travel);
    }

    /** A client that reloaded its resource pack threw the corpses away; send them again. */
    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (ready && e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) Puppets.repaint(e.getPlayer());
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) {
        PhysicsWorld physics = worlds.remove(e.getWorld());
        if (physics != null) physics.clearAll();
    }

    // ------------------------------------------------------------------ /ragdoll

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!ready) {
            sender.sendMessage("Ragdolls are off (BetterModel or the Jolt native is missing - see the log).");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            int n = 0;
            for (PhysicsWorld physics : worlds.values()) {
                n += physics.trackedCount();
                physics.clearAll();
            }
            sender.sendMessage("Cleared " + n + " physics bodies.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("at")) {
            // A Steve body at a spot; works from the console, with nobody online.
            World world = args.length >= 5 ? Bukkit.getWorld(args[4])
                    : sender instanceof Player self ? self.getWorld() : Bukkit.getWorlds().getFirst();
            Location where;
            try {
                where = new Location(world, Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]));
            } catch (RuntimeException bad) {
                sender.sendMessage("Usage: /ragdoll at <x> <y> <z> [world]");
                return true;
            }
            if (world == null) {
                sender.sendMessage("No world named " + args[4] + ".");
                return true;
            }
            int ticks = Math.max(1, getConfig().getInt("lifetime-seconds", 120)) * 20;
            boolean made;
            try {
                made = Corpses.drop(this, physics(world), where, PLAIN_SKIN, false, ticks, null, 0, 20, new Vector());
            } catch (Throwable t) {
                getLogger().warning("Could not make a ragdoll: " + t);
                made = false;
            }
            sender.sendMessage(made ? "Dropped a ragdoll at " + where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ() + "."
                    : "Could not make a ragdoll there (see the log).");
            return true;
        }
        Player whose = args.length >= 1 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player self ? self : null;
        if (whose == null) {
            sender.sendMessage(args.length >= 1 ? "No player named " + args[0] + " is online." : "Usage: /ragdoll <player> | at <x> <y> <z> [world] | clear");
            return true;
        }
        // Two blocks in front of whoever asked, thrown gently away from them.
        Player at = sender instanceof Player self ? self : whose;
        Vector ahead = at.getLocation().getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0e-4) ahead = new Vector(0, 0, 1);
        ahead.normalize();
        Location where = at.getLocation().add(ahead.clone().multiply(2.0));
        var max = whose.getAttribute(Attribute.MAX_HEALTH);
        double whole = max == null ? 20 : max.getValue();
        boolean made = lay(whose, where, 0, whole, ahead.multiply(1.5));
        sender.sendMessage(made ? "Dropped a ragdoll of " + whose.getName() + "." : "Could not make a ragdoll here (see the log).");
        return true;
    }
}
