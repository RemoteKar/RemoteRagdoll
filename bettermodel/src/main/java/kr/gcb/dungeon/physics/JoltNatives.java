/* Ported from TerritoryWar (io.lightest.tw.physics / .model), same author's project.
 * Changes: package, dungeon terrain collider instead of a flat ground plane, and a
 * ragdoll built from whole limbs rather than one body per bone. */
package kr.gcb.dungeon.physics;

import com.github.stephengold.joltjni.Jolt;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Unpacks the Jolt native library out of the plugin jar and initialises it.
 *
 * <p>Failure is never fatal: if the library will not load, {@link #isAvailable}
 * returns false and the server runs with physics switched off rather than refusing
 * to start.
 */
public final class JoltNatives {

    private JoltNatives() {}

    private static boolean attempted = false;
    private static boolean available = false;

    public static synchronized boolean isAvailable(Plugin plugin) {
        if (attempted) return available;
        attempted = true;
        try {
            loadAndInit(plugin);
            available = true;
            plugin.getLogger().info("Jolt " + Jolt.versionString() + " ready");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().log(Level.WARNING,
                    "Jolt native failed to load - physics disabled, the rest of the game still runs", t);
        }
        return available;
    }

    private static void loadAndInit(Plugin plugin) throws IOException {
        String resource = resourcePath();
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);

        Path dir = plugin.getDataFolder().toPath().resolve("natives");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);

        try (InputStream in = JoltNatives.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("native missing from jar: " + resource);
            byte[] bytes = in.readAllBytes();
            // Windows will not let us overwrite a DLL that is already loaded, so only
            // rewrite when the size differs - that also makes restarts cheap.
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.write(target, bytes);
            }
        }
        System.load(target.toAbsolutePath().toString());

        // This exact order is required; skipping any of it kills the native on the
        // first PhysicsSystem construction.
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();
    }

    private static String resourcePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new UnsupportedOperationException("unsupported architecture: " + arch);
        }
        if (os.contains("win"))   return "windows/x86-64/com/github/stephengold/joltjni.dll";
        if (os.contains("linux")) return "linux/x86-64/com/github/stephengold/libjoltjni.so";
        throw new UnsupportedOperationException("unsupported OS: " + os);
    }
}
