package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import org.EIQUI.GCBAPI.particle.ComplexParticleAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.EIQUI.RemoteRagdoll.main.that;

/**
 * 액체 흉내. 작은 구체 강체를 여럿 뿌리고 그 자리에 파티클을 그린다.
 *
 * <p>Jolt 는 강체 엔진이라 진짜 유체(SPH)는 없다. SoftBody 가 있긴 하지만 그건 천이나 젤리용이라
 * 흘러내리는 액체에는 안 맞는다. 게임에서 흔히 쓰는 방식대로 <b>작은 방울 다수</b>로 근사한다.
 *
 * <p>액체처럼 보이게 하려고 일반 강체와 다르게 잡은 것들:
 * <ul>
 *   <li>구체 모양 - 상자는 모서리가 걸려서 흐르지 않고 자갈처럼 군다.</li>
 *   <li>방울끼리 충돌하지 않음 - 서로 밀어내면 뭉치지 못하고 흩어진다. 지형과만 부딪힌다.</li>
 *   <li>마찰·반발을 낮게 - 잘 미끄러지고 튀지 않아야 흘러내리는 느낌이 난다.</li>
 *   <li>LinearCast - 작고 빨라서 그냥 두면 한 틱에 블록을 지나쳐 버린다.</li>
 * </ul>
 *
 * <p>파티클은 물리 틱에서 직접 그린다. Skript 에서 매 틱 위치를 읽으면 방울 수만큼
 * JNI 를 왕복해야 해서 수십 개만 돼도 비용이 눈에 띈다.
 */
public final class Liquid {

    /** 방울 반지름(블록). 작을수록 물처럼, 크면 걸쭉해 보인다. */
    public static float RADIUS = 0.06f;

    /** 잘 미끄러져야 흘러내린다. */
    public static float FRICTION = 0.02f;

    /** 튀지 않아야 액체다. */
    public static float RESTITUTION = 0.0f;

    /** 공기 저항. 너무 낮으면 끝없이 굴러다닌다. */
    public static float DAMPING = 0.15f;

    /** 한 번에 만들 수 있는 방울 수 상한. */
    public static int MAX_DROPS = 400;

    private final PhysicsWorld world;
    private final List<Integer> bodies = new ArrayList<>();
    private final String particle;
    private final BlockData blockData;
    private final int particleCount;
    private int ticksLeft;
    private boolean dead = false;

    private Liquid(PhysicsWorld world, String particle, BlockData blockData,
                   int particleCount, int lifetimeTicks) {
        this.world = world;
        this.particle = particle;
        this.blockData = blockData;
        this.particleCount = particleCount;
        this.ticksLeft = lifetimeTicks;
    }

    /**
     * 액체를 뿌린다.
     *
     * @param at        뿌릴 자리
     * @param count     방울 수
     * @param power     처음 튀는 세기. 0 이면 그 자리에서 흘러내린다.
     * @param up        위로 솟는 정도(0~1)
     * @param lifetime  유지 틱
     * @param blockName 파티클로 쓸 블록 이름 (예: "REDSTONE_BLOCK")
     */
    public static Liquid spawn(Location at, int count, double power, double up,
                               int lifetime, String blockName) {
        if (at == null || at.getWorld() == null || !JoltNatives.isAvailable()) {
            return null;
        }
        PhysicsWorld pw = PhysicsWorld.get(at.getWorld());
        if (pw == null) {
            return null;
        }
        BlockData bd;
        try {
            bd = Material.valueOf(blockName).createBlockData();
        } catch (Throwable t) {
            that.getLogger().warning("[Liquid] 블록 이름이 잘못됐다: " + blockName);
            return null;
        }

        Liquid liq = new Liquid(pw, "BLOCK", bd, 1, lifetime);
        int n = Math.min(count, MAX_DROPS);

        // 방울끼리는 안 부딪히게 한 그룹으로 묶는다. 서로 밀어내면 액체가 아니라 자갈이 된다.
        int groupId = PhysicsWorld.nextRagdollGroupId();

        BodyInterface bi = pw.getBodies();
        SphereShape shape = new SphereShape(RADIUS);
        Random rng = ThreadLocalRandom.current();

        for (int i = 0; i < n; i++) {
            BodyCreationSettings bcs = new BodyCreationSettings(
                    shape,
                    new RVec3(at.getX(), at.getY(), at.getZ()),
                    new Quat(),
                    EMotionType.Dynamic,
                    PhysicsWorld.LAYER_DYNAMIC);
            bcs.setFriction(FRICTION);
            bcs.setRestitution(RESTITUTION);
            bcs.setLinearDamping(DAMPING);
            // 모든 방울이 같은 서브그룹 -> 서로 무시하고 지형과만 부딪힌다
            bcs.setCollisionGroup(new CollisionGroup(PhysicsWorld.ragdollFilterRef(), groupId, 0));

            Body b = bi.createBody(bcs);
            if (b == null) {
                break;
            }
            bi.addBody(b, EActivation.Activate);
            // 작고 빨라서 그냥 두면 한 틱에 바닥을 지나쳐 버린다
            bi.setMotionQuality(b.getId(), EMotionQuality.LinearCast);

            if (power > 0) {
                double dx = rng.nextDouble() * 2 - 1;
                double dz = rng.nextDouble() * 2 - 1;
                double dy = rng.nextDouble();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-6) {
                    len = 1;
                }
                double s = power * (0.5 + rng.nextDouble() * 0.5);
                bi.setLinearVelocity(b.getId(), new Vec3(
                        (float) (dx / len * s),
                        (float) ((dy / len * up + up) * s),
                        (float) (dz / len * s)));
            }
            liq.bodies.add(b.getId());
        }

        if (liq.bodies.isEmpty()) {
            return null;
        }
        pw.trackLiquid(liq);
        return liq;
    }

    /** 물리 틱마다 불린다. 수명이 다하면 true. */
    boolean tick() {
        if (dead) {
            return true;
        }
        if (ticksLeft > 0 && --ticksLeft <= 0) {
            destroy();
            return true;
        }
        BodyInterface bi = world.getBodies();
        for (int id : bodies) {
            RVec3 p = bi.getPosition(id);
            Location l = new Location(world.getWorld(), p.xx(), p.yy(), p.zz());
            // force=true 여야 멀리서도 보인다. 원본 스크립트도 그렇게 뿌렸다.
            ComplexParticleAPI.Dot(particle, l, 0, 0, 0, particleCount, 0, true, blockData);
        }
        return false;
    }

    /** 지형을 올릴 때 쓰라고 방울 바디 id 를 내준다. */
    List<Integer> bodyIds() {
        return bodies;
    }

    /** 남은 방울 수. */
    public int size() {
        return bodies.size();
    }

    public void destroy() {
        if (dead) {
            return;
        }
        dead = true;
        for (int id : bodies) {
            world.remove(id);
        }
        bodies.clear();
    }
}
