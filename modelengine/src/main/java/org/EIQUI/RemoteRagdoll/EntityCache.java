package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 살아있는 엔티티를 Jolt 충돌체로 올려주는 캐시.
 *
 * <p>래그돌 파츠를 사람이 발로 찰 수 있게 하려고 만들었다. 지형과 달리 대상이 매 틱 움직이므로
 * {@link TerrainCache} 처럼 "블록이 바뀔 때만 갱신" 하는 방식은 못 쓰고, 매 틱 위치를 옮긴다.
 *
 * <p>모션 타입은 <b>Kinematic</b> 이다. Static 으로 두면 몸에 닿은 파츠가 튕겨나가기만 하고
 * 걷어차는 느낌이 안 난다. Dynamic 으로 두면 반대로 사람이 래그돌에 밀려 날아간다.
 * Kinematic 은 남을 밀되 자기는 안 밀리는, 딱 필요한 성질이다.
 *
 * <p>옮길 때 {@code setPosition} 이 아니라 {@link BodyInterface#moveKinematic} 을 쓴다.
 * setPosition 은 순간이동이라 접촉면에 속도가 전달되지 않아 파츠가 그냥 파묻힌다.
 * moveKinematic 은 "이 시간 안에 저기까지 간다"는 속도를 만들어 주므로 충돌 때 운동량이 넘어간다.
 *
 * <p>물리 객체 근처에 있는 엔티티만 올린다. 서버 전체를 올릴 이유가 없고, 멀리 있는 사람까지
 * 매 틱 옮기면 그게 곧 비용이다.
 */
final class EntityCache {

    /** 이 거리 안에 물리 객체가 있는 엔티티만 올린다. (블록) */
    private static final double RANGE = 6.0;

    /** 너무 얇으면 빠른 파츠가 그냥 통과한다. 최소 두께를 준다. */
    private static final float MIN_HALF = 0.15f;

    private final PhysicsWorld owner;

    /** 엔티티 UUID -> Jolt 바디 id */
    private final Map<UUID, Integer> loaded = new HashMap<>();

    /** 엔티티 UUID -> 그 바디를 만들 때 쓴 반높이. 몸 크기가 바뀌면 다시 만든다. */
    private final Map<UUID, Long> shapeOf = new HashMap<>();

    EntityCache(PhysicsWorld owner) {
        this.owner = owner;
    }

    /**
     * @param bodyIds 지금 살아있는 물리 바디들. 이 주변 엔티티만 대상으로 삼는다.
     */
    void update(List<Integer> bodyIds) {
        if (bodyIds.isEmpty()) {
            clear();
            return;
        }
        World w = owner.getWorld();
        BodyInterface bi = owner.getBodies();

        // 물리 객체 위치를 먼저 모아 둔다. 엔티티마다 전부 훑어야 하므로 한 번만 뜬다.
        List<RVec3> spots = new ArrayList<>(bodyIds.size());
        for (int id : bodyIds) {
            spots.add(bi.getPosition(id));
        }

        Set<UUID> alive = new HashSet<>();
        for (LivingEntity e : w.getLivingEntities()) {
            if (e.isDead() || !e.isValid()) {
                continue;
            }
            if (!collides(e)) {
                continue;
            }
            if (!near(e, spots)) {
                continue;
            }
            alive.add(e.getUniqueId());
            sync(bi, e);
        }

        // 멀어졌거나 사라진 것은 내린다.
        loaded.entrySet().removeIf(en -> {
            if (alive.contains(en.getKey())) {
                return false;
            }
            owner.remove(en.getValue());
            shapeOf.remove(en.getKey());
            return true;
        });
    }

    /**
     * 이 엔티티가 물리 객체를 밀어도 되는가.
     *
     * <p>관전자는 벽도 통과하는 유령이다. 그런 것이 시체를 밀고 다니면 죽은 사람이
     * 구경하다가 남의 래그돌을 걷어차게 된다. 크리에이티브는 몸이 실재하므로 그대로 둔다.
     */
    private boolean collides(LivingEntity e) {
        if (e instanceof Player p) {
            return p.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    private boolean near(Entity e, List<RVec3> spots) {
        Location l = e.getLocation();
        double ex = l.getX();
        double ey = l.getY();
        double ez = l.getZ();
        for (RVec3 p : spots) {
            double dx = p.xx() - ex;
            double dy = p.yy() - ey;
            double dz = p.zz() - ez;
            if (dx * dx + dy * dy + dz * dz <= RANGE * RANGE) {
                return true;
            }
        }
        return false;
    }

    /** 있으면 옮기고, 없거나 몸 크기가 달라졌으면 새로 만든다. */
    private void sync(BodyInterface bi, LivingEntity e) {
        BoundingBox bb = e.getBoundingBox();
        float hx = (float) Math.max(bb.getWidthX() / 2.0, MIN_HALF);
        float hy = (float) Math.max(bb.getHeight() / 2.0, MIN_HALF);
        float hz = (float) Math.max(bb.getWidthZ() / 2.0, MIN_HALF);
        Long shape = shapeKey(hx, hy, hz);

        UUID uid = e.getUniqueId();
        Integer id = loaded.get(uid);
        if (id != null && !shape.equals(shapeOf.get(uid))) {
            // 웅크리거나 크기 스탯이 바뀐 경우. 크기는 못 바꾸니 만들었던 걸 버리고 다시 만든다.
            owner.remove(id);
            loaded.remove(uid);
            shapeOf.remove(uid);
            id = null;
        }

        RVec3 to = new RVec3(bb.getCenterX(), bb.getCenterY(), bb.getCenterZ());
        if (id == null) {
            int made = add(bi, to, hx, hy, hz);
            if (made != com.github.stephengold.joltjni.Jolt.cInvalidBodyId) {
                loaded.put(uid, made);
                shapeOf.put(uid, shape);
            }
            return;
        }
        // 1틱(=1/20초) 안에 목표 지점까지 가는 속도로 움직인다.
        bi.moveKinematic(id, to, new Quat(), 1.0f / 20.0f);
    }

    private int add(BodyInterface bi, RVec3 at, float hx, float hy, float hz) {
        BodyCreationSettings bcs = new BodyCreationSettings(
                new BoxShape(new Vec3(hx, hy, hz), 0.01f),
                at,
                new Quat(),
                EMotionType.Kinematic,
                PhysicsWorld.LAYER_DYNAMIC);
        bcs.setFriction(0.6f);
        bcs.setRestitution(0.0f);
        return bi.createAndAddBody(bcs, EActivation.Activate);
    }

    /** 0.0625(1/16) 단위로 뭉쳐서 미세한 크기 변화로 매번 다시 만들지 않게 한다. */
    private static Long shapeKey(float hx, float hy, float hz) {
        long a = Math.round(hx * 16.0);
        long b = Math.round(hy * 16.0);
        long c = Math.round(hz * 16.0);
        return (a << 42) ^ (b << 21) ^ c;
    }

    int getLoadedCount() {
        return loaded.size();
    }

    void clear() {
        if (loaded.isEmpty()) {
            return;
        }
        for (int id : loaded.values()) {
            owner.remove(id);
        }
        loaded.clear();
        shapeOf.clear();
    }
}
