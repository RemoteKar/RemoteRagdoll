import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Standalone Jolt JNI / Java 25 smoke check; does not start or emulate Bukkit.
 * Run with the shaded plugin jar on the classpath to test its packaged natives.
 */
public final class NativeSmoke {
    private static final String WINDOWS_NATIVE =
            "windows/x86-64/com/github/stephengold/joltjni.dll";
    private static final String LINUX_NATIVE =
            "linux/x86-64/com/github/stephengold/libjoltjni.so";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: NativeSmoke <native extraction directory>");
        }
        System.out.println("Scope: standalone Jolt JNI; Bukkit/ModelEngine/Skript untested");
        System.out.println("Java: " + System.getProperty("java.runtime.version"));
        System.out.println("Platform: " + System.getProperty("os.name") + "/"
                + System.getProperty("os.arch"));
        System.out.println("Jolt classes: " + Jolt.class.getProtectionDomain().getCodeSource().getLocation());

        // Verify both bundled paths, even though this process loads only Windows.
        for (String resource : new String[]{WINDOWS_NATIVE, LINUX_NATIVE}) {
            var url = NativeSmoke.class.getClassLoader().getResource(resource);
            require(url != null, "Missing native resource: " + resource);
            System.out.println("Native resource: " + url);
        }
        require(System.getProperty("os.name").startsWith("Windows"),
                "This smoke runner loads the Windows x86-64 native");
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        Path nativePath = directory.resolve("joltjni.dll");
        try (InputStream stream = NativeSmoke.class.getClassLoader().getResourceAsStream(WINDOWS_NATIVE)) {
            Files.copy(stream, nativePath, StandardCopyOption.REPLACE_EXISTING);
        }
        System.load(nativePath.toString());
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        require(Jolt.newFactory(), "Jolt factory creation failed");
        Jolt.registerTypes();
        System.out.println("Jolt native: " + Jolt.versionString() + ", " + Jolt.buildType());

        try {
            runPhysics();
        } finally {
            Jolt.unregisterTypes();
            Jolt.destroyFactory();
        }
        System.out.println("PASS: native load, init, terrain collision, SwingTwist, ordered cleanup");
    }

    private static void runPhysics() {
        try (ObjectLayerPairFilterTable objectFilter = new ObjectLayerPairFilterTable(2);
             BroadPhaseLayerInterfaceTable broadPhase = new BroadPhaseLayerInterfaceTable(2, 2)) {
            objectFilter.enableCollision(1, 1);
            objectFilter.enableCollision(1, 0);
            objectFilter.enableCollision(0, 1);
            broadPhase.mapObjectToBroadPhaseLayer(0, 0);
            broadPhase.mapObjectToBroadPhaseLayer(1, 1);
            try (ObjectVsBroadPhaseLayerFilter broadFilter =
                         new ObjectVsBroadPhaseLayerFilterTable(broadPhase, 2, objectFilter, 2);
                 PhysicsSystem system = new PhysicsSystem();
                 TempAllocator temp = new TempAllocatorImpl(8 * 1024 * 1024);
                 JobSystem jobs = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 2)) {
                system.init(128, 0, 128, 128, broadPhase, broadFilter, objectFilter);
                system.setGravity(0, -22, 0);
                BodyInterface bodies = system.getBodyInterface();

                // Repeat creation and cleanup on one PhysicsSystem, as clearAll keeps worlds alive.
                for (int cycle = 1; cycle <= 3; cycle++) {
                    Body ground = box(bodies, new Vec3(8, 0.5f, 8), -0.5, EMotionType.Static, 0);
                    Body lower = box(bodies, new Vec3(0.3f, 0.3f, 0.3f), 3, EMotionType.Dynamic, 1);
                    Body upper = box(bodies, new Vec3(0.3f, 0.3f, 0.3f), 4, EMotionType.Dynamic, 1);
                    TwoBodyConstraint joint;
                    try (SwingTwistConstraintSettings settings = new SwingTwistConstraintSettings()) {
                        settings.setSpace(EConstraintSpace.WorldSpace);
                        settings.setPosition1(new RVec3(0, 3.5, 0));
                        settings.setPosition2(new RVec3(0, 3.5, 0));
                        settings.setTwistAxis1(new Vec3(0, 1, 0));
                        settings.setTwistAxis2(new Vec3(0, 1, 0));
                        settings.setPlaneAxis1(new Vec3(1, 0, 0));
                        settings.setPlaneAxis2(new Vec3(1, 0, 0));
                        settings.setNormalHalfConeAngle((float) Math.toRadians(50));
                        settings.setPlaneHalfConeAngle((float) Math.toRadians(45));
                        settings.setTwistMinAngle((float) Math.toRadians(-45));
                        settings.setTwistMaxAngle((float) Math.toRadians(45));
                        joint = settings.create(lower, upper);
                    }
                    system.addConstraint(joint);
                    require(system.containsConstraint(joint), "Constraint was not registered");
                    require(system.getNumBodies() == 3, "Expected one static and two dynamic bodies");

                    for (int tick = 0; tick < 80; tick++) {
                        require(system.update(1f / 20f, 2, temp, jobs) == 0, "Jolt update failed");
                    }
                    double lowerY = bodies.getPosition(lower.getId()).yy();
                    double upperY = bodies.getPosition(upper.getId()).yy();
                    require(Double.isFinite(lowerY) && lowerY > 0.1 && lowerY < 1.5,
                            "Lower body did not settle on terrain: " + lowerY);
                    require(Double.isFinite(upperY) && upperY > 0.1 && upperY < 2,
                            "Upper body did not settle on terrain: " + upperY);

                    // Joints must be removed before the bodies they reference.
                    system.removeConstraint(joint);
                    for (Body body : new Body[]{upper, lower, ground}) {
                        int id = body.getId();
                        bodies.removeBody(id);
                        bodies.destroyBody(id);
                    }
                    require(system.getNumBodies() == 0, "Bodies leaked during cleanup");
                    require(system.update(1f / 20f, 2, temp, jobs) == 0, "Empty-world update failed");
                    System.out.printf("Cycle %d: lowerY=%.6f upperY=%.6f bodiesAfterCleanup=%d%n",
                            cycle, lowerY, upperY, system.getNumBodies());
                }
            }
        }
    }

    private static Body box(BodyInterface bodies, Vec3 halfExtent, double y,
                            EMotionType motion, int layer) {
        try (BoxShape shape = new BoxShape(halfExtent, 0.01f);
             BodyCreationSettings settings = new BodyCreationSettings(
                     shape, new RVec3(0, y, 0), new Quat(), motion, layer)) {
            settings.setRestitution(0.05f);
            settings.setFriction(0.6f);
            Body body = bodies.createBody(settings);
            require(body != null, "Body creation failed");
            bodies.addBody(body, motion == EMotionType.Static
                    ? EActivation.DontActivate : EActivation.Activate);
            return body;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
