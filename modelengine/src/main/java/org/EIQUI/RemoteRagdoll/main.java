package org.EIQUI.RemoteRagdoll;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.EIQUI.RemoteRagdoll.visual.Visuals;

public final class main extends JavaPlugin {
    public static main that;

    @Override
    public void onEnable() {
        that = this;
        Visuals.initialize(this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onWorldUnload(WorldUnloadEvent event) {
                PhysicsWorld.clearWorld(event.getWorld());
            }
        }, this);
        JoltNatives.isAvailable();
    }

    @Override
    public void onDisable() {
        try {
            PhysicsWorld.clearAll();
        } finally {
            try {
                Visuals.shutdown();
            } finally {
                PhysicsWorld.stopTicker();
                getServer().getScheduler().cancelTasks(this);
            }
        }
    }
}
