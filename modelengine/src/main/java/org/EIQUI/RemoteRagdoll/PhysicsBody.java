package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import org.EIQUI.RemoteRagdoll.visual.EntityVisual;
import org.EIQUI.RemoteRagdoll.visual.PartVisual;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Jolt 강체 하나 + 그것을 그려줄 Display 엔티티들을 묶은 것.
 *
 * <p>매 틱 Jolt 가 계산한 위치·회전을 Display 의 {@link Transformation} 과 좌표에 반영한다.
 * 20TPS 라 그대로 두면 뚝뚝 끊겨 보이므로 보간(teleportDuration / interpolationDuration)을 1틱으로 켠다.
 *
 * <p>Display 가 <b>여러 개</b>인 이유: 플레이어 스킨 팔다리(PLAYER_LIMB)는 기본 레이어와 겉옷 레이어처럼
 * 아이템이 여러 장 겹쳐서 렌더된다. 한 파츠가 여러 ItemDisplay 로 그려지므로 전부 같이 움직여야 한다.
 */
public class PhysicsBody {

    private final PhysicsWorld world;
    private final com.github.stephengold.joltjni.Body body;
    private final int bodyId;
    private final List<PartVisual> visuals;

    /**
     * Display 마다의 로컬 오프셋/스케일/limb Y오프셋.
     *
     * 파츠 본체는 오프셋 0 이지만, 헬멧이나 손에 든 무기처럼 <b>붙여둔</b> 것들은
     * 파츠 원점에서 떨어진 자리에 그려져야 하므로 각자 값을 갖는다.
     */
    private final List<Vector3f> localOffsets = new ArrayList<>();
    private final List<Vector3f> scales = new ArrayList<>();
    private final List<Double> yOffsets = new ArrayList<>();

    /**
     * Display 마다의 <b>파츠 기준 고유 회전</b>.
     *
     * <p>강체 회전만 그대로 씌우면 파츠에 딸린 것들이 자기 각도를 잃는다. 예를 들어 손에 든 검은
     * ModelEngine 이 X축 90도로 눕혀둔 상태인데, 팔의 강체 회전으로 통째로 덮어쓰면 검이 그냥 선다.
     * 그래서 붙일 때 {@code 생성시강체회전⁻¹ * 그Display의회전} 을 저장해두고 매 틱 다시 곱한다.
     * 파츠 본체는 이 값이 단위 회전이라 예전과 동일하게 동작한다.
     */
    private final List<Quaternionf> localRots = new ArrayList<>();
    /** Display 마다의 rightRotation. Transformation 은 translation + left * scale * right 다. */
    private final List<Quaternionf> rightRots = new ArrayList<>();

    /** 생성 시점의 강체 회전. 월드 좌표를 파츠 기준으로 되돌릴 때 쓴다. */
    private final Quaternionf startRot;

    /** 첫 Display 기준값 (호환용) */
    private final Vector3f modelOffset;
    private final Vector3f scale;

    /**
     * 본 피벗에서 충돌 상자 중심까지의 로컬 오프셋(블록 단위).
     *
     * ModelEngine 은 엔티티를 본 <b>피벗</b>에 두고 모델을 그리는데, 물리는 상자 <b>중심</b>을 기준으로 돈다.
     * 예를 들어 머리는 피벗이 목(y=22.56 units)인데 큐브 중심은 y=26.32 units 라 0.235블록 차이가 난다.
     * 그래서 엔티티를 놓을 때 {@code 상자중심 - R*pivotOffset} 으로 되돌려준다.
     */
    private final Vector3f pivotOffset;

    /**
     * 렌더 전용 Y 오프셋(기록용). 실제 적용은 modelOffset(Transformation.translation)이 담당한다.
     * 엔티티 좌표를 내리면 월드 높이 밖이 되어 클라이언트에 전송되지 않기 때문이다.
     */
    private final double renderYOffset;

    private boolean dead = false;

    /**
     * Jolt 바디를 이미 반납했는가.
     *
     * dead 로는 대신할 수 없다. onPhysicsTick 이 수명 만료·월드 밖 이탈·Display 소실 때
     * dead 만 세워 두고 실제 반납은 PhysicsWorld 의 정리 루프에 맡기기 때문이다.
     * dead 를 가드로 쓰면 그 바디가 영영 반납되지 않아 Jolt 바디 풀이 샌다.
     *
     * 반대로 가드가 없으면 같은 id 를 두 번 destroyBody 한다. 래그돌은 파츠를 자기
     * 목록에도 들고 있어서, 월드 정리 루프가 먼저 지운 파츠를 수명 만료 때 또 지운다.
     * 두 번째 호출은 이미 풀린 네이티브 포인터를 건드려 JVM 이 통째로 죽는다
     * (EXCEPTION_ACCESS_VIOLATION in joltjni.dll — Java 예외가 아니라 catch 로도 못 막는다).
     */
    private boolean released = false;
    private int ticksLeft;

    private PhysicsBody(PhysicsWorld world, com.github.stephengold.joltjni.Body body, List<PartVisual> visuals,
                        Vector3f modelOffset, Vector3f scale, Vector3f pivotOffset,
                        double renderYOffset, int lifetimeTicks, Quaternionf startRot) {
        this.world = world;
        this.body = body;
        this.bodyId = body.getId();
        this.visuals = visuals;
        this.modelOffset = modelOffset;
        this.scale = scale;
        this.pivotOffset = pivotOffset;
        this.renderYOffset = renderYOffset;
        this.ticksLeft = lifetimeTicks;
        this.startRot = new Quaternionf(startRot);
        Quaternionf inv = new Quaternionf(startRot).conjugate();
        for (PartVisual d : visuals) {
            Transformation tr = d.getTransformation();
            localOffsets.add(new Vector3f());
            scales.add(new Vector3f(tr.getScale()));
            yOffsets.add(renderYOffset);
            localRots.add(new Quaternionf(inv).mul(tr.getLeftRotation()));
            rightRots.add(new Quaternionf(tr.getRightRotation()));
        }
    }

    /** 생성 시점의 강체 회전. 파츠를 따라 돌게 붙이려면 이 회전으로 Display 를 만들면 된다. */
    public Quaternionf getStartRotation() {
        return new Quaternionf(startRot);
    }

    /**
     * 이 파츠에 딸려 움직일 Display 를 추가한다. (헬멧, 손에 든 무기 등)
     *
     * @param worldOffset 파츠 원점에서의 오프셋(블록). <b>생성 시점의 월드 좌표 기준</b>으로 주면
     *                    여기서 파츠 기준으로 되돌린다. 안 그러면 매 틱 강체 회전이 또 곱해져 이중 적용된다.
     * @param yOff        그 Display 의 limb Y오프셋. 일반 아이템 모델이면 0.
     */
    public void attach(Display d, Vector3f worldOffset, double yOff) {
        if (d == null) return;
        attachVisual(new EntityVisual(d), worldOffset, yOff);
    }

    void attachVisual(PartVisual d, Vector3f worldOffset, double yOff) {
        if (d == null) return;
        d.configureInterpolation();
        Quaternionf inv = new Quaternionf(startRot).conjugate();
        Transformation tr = d.getTransformation();
        visuals.add(d);
        localOffsets.add(inv.transform(new Vector3f(worldOffset)));
        scales.add(new Vector3f(tr.getScale()));
        yOffsets.add(yOff);
        localRots.add(new Quaternionf(inv).mul(tr.getLeftRotation()));
        rightRots.add(new Quaternionf(tr.getRightRotation()));
        if (d.isPacket()) refreshPacketPose();
    }

    /** 현재 물리 위치(상자 중심). 파티클 등을 붙일 때 쓴다. */
    public Location getLocation() {
        RVec3 p = world.getBodies().getPosition(bodyId);
        return new Location(world.getWorld(), p.xx(), p.yy(), p.zz());
    }

    /** 상자 중심 기준 로컬 좌표를 현재 자세의 월드 좌표로. */
    public Location toWorld(Vector3f local) {
        RVec3 p = world.getBodies().getPosition(bodyId);
        Quat r = world.getBodies().getRotation(bodyId);
        Vector3f v = new Vector3f(local);
        new Quaternionf(r.getX(), r.getY(), r.getZ(), r.getW()).transform(v);
        return new Location(world.getWorld(), p.xx() + v.x, p.yy() + v.y, p.zz() + v.z);
    }

    /** 본 피벗의 현재 위치. 이 파츠가 부모와 이어져 있던 관절 자리다. */
    public Location getPivotLocation() {
        return toWorld(new Vector3f(pivotOffset).negate());
    }

    public static PhysicsBody create(Location at, Display display, Vector halfExtent,
                                     float mass, int lifetimeTicks) {
        return create(at, display == null ? Collections.emptyList() : List.of(display),
                halfExtent, mass, lifetimeTicks, new Vector(0, 0, 0), PhysicsWorld.LAYER_DYNAMIC);
    }

    public static PhysicsBody create(Location at, Display display, Vector halfExtent,
                                     float mass, int lifetimeTicks, Vector pivotOffset) {
        return create(at, display == null ? Collections.emptyList() : List.of(display),
                halfExtent, mass, lifetimeTicks, pivotOffset, PhysicsWorld.LAYER_DYNAMIC);
    }

    /**
     * 물리 객체를 만든다.
     *
     * @param displays      같이 움직일 Display 들. 비어있으면 보이지 않는 순수 충돌체가 된다.
     * @param halfExtent    충돌 상자의 반쪽 크기(블록 단위)
     * @param mass          질량
     * @param lifetimeTicks 이 틱 수가 지나면 자동 제거. 0 이하면 무제한
     * @param pivotOffset   본 피벗 -> 상자 중심 로컬 오프셋(블록)
     * @param layer         충돌 레이어 (PhysicsWorld.LAYER_*)
     */
    public static PhysicsBody create(Location at, List<Display> displays, Vector halfExtent,
                                     float mass, int lifetimeTicks, Vector pivotOffset, int layer) {
        return create(at, displays, halfExtent, mass, lifetimeTicks, pivotOffset, layer, 0);
    }

    public static PhysicsBody create(Location at, List<Display> displays, Vector halfExtent,
                                     float mass, int lifetimeTicks, Vector pivotOffset, int layer,
                                     double renderYOffset) {
        return create(at, displays, halfExtent, mass, lifetimeTicks, pivotOffset, layer, renderYOffset, -1, -1);
    }

    public static PhysicsBody create(Location at, List<Display> displays, Vector halfExtent,
                                     float mass, int lifetimeTicks, Vector pivotOffset, int layer,
                                     double renderYOffset, int groupId, int subGroupId) {
        List<PartVisual> visuals = new ArrayList<>();
        if (displays != null) {
            for (Display display : displays) {
                if (display != null) visuals.add(new EntityVisual(display));
            }
        }
        return createVisuals(at, visuals, halfExtent, mass, lifetimeTicks, pivotOffset, layer,
                renderYOffset, groupId, subGroupId);
    }

    static PhysicsBody createVisuals(Location at, List<PartVisual> visuals, Vector halfExtent,
                                     float mass, int lifetimeTicks, Vector pivotOffset, int layer,
                                     double renderYOffset, int groupId, int subGroupId) {
        PhysicsWorld pw = PhysicsWorld.get(at.getWorld());
        if (pw == null) {
            return null;
        }
        List<PartVisual> list = visuals == null ? new ArrayList<>() : new ArrayList<>(visuals);
        list.removeIf(d -> d == null);

        Quaternionf startRot = new Quaternionf();
        Vector3f modelOffset = new Vector3f();
        Vector3f scale = new Vector3f(1, 1, 1);
        if (!list.isEmpty()) {
            Transformation t = list.get(0).getTransformation();
            startRot.set(t.getLeftRotation());
            modelOffset.set(t.getTranslation());
            scale.set(t.getScale());
        }

        com.github.stephengold.joltjni.Body jb = pw.createBoxBody(at,
                new Vec3((float) halfExtent.getX(), (float) halfExtent.getY(), (float) halfExtent.getZ()),
                new Quat(startRot.x, startRot.y, startRot.z, startRot.w),
                mass, layer, groupId, subGroupId);
        if (jb == null) {
            return null;
        }
        for (PartVisual d : list) {
            // 매 틱 갱신되므로 1틱짜리 보간을 켜 둔다. 안 그러면 20TPS 로 뚝뚝 끊긴다.
            d.configureInterpolation();
        }
        PhysicsBody pb = new PhysicsBody(pw, jb, list, modelOffset, scale,
                new Vector3f((float) pivotOffset.getX(), (float) pivotOffset.getY(), (float) pivotOffset.getZ()),
                renderYOffset, lifetimeTicks, startRot);
        pw.track(pb);
        return pb;
    }

    // ------------------------------------------------------------------ 물리 조작

    public void setVelocity(Vector v) {
        world.getBodies().setLinearVelocity(bodyId,
                new Vec3((float) v.getX(), (float) v.getY(), (float) v.getZ()));
    }

    public void setAngularVelocity(Vector v) {
        world.getBodies().setAngularVelocity(bodyId,
                new Vec3((float) v.getX(), (float) v.getY(), (float) v.getZ()));
    }

    public void addImpulse(Vector v) {
        world.getBodies().addImpulse(bodyId,
                new Vec3((float) v.getX(), (float) v.getY(), (float) v.getZ()));
    }

    public void addTorque(Vector v) {
        world.getBodies().addTorque(bodyId,
                new Vec3((float) v.getX(), (float) v.getY(), (float) v.getZ()));
    }

    public Vector getVelocity() {
        Vec3 v = world.getBodies().getLinearVelocity(bodyId);
        return new Vector(v.getX(), v.getY(), v.getZ());
    }

    /** 위치는 그대로 두고 회전만 바꾼다. 마네킹을 똑바로 세울 때 쓴다. */
    public void setRotation(Quaternionf q) {
        var bi = world.getBodies();
        bi.setPositionAndRotation(bodyId, bi.getPosition(bodyId),
                new Quat(q.x, q.y, q.z, q.w), EActivation.DontActivate);
        refreshPacketPose();
    }

    /**
     * 그 자리에 굳힌다. 중력도 안 받고 관절이 끌어당겨도 안 움직인다.
     *
     * <p>Kinematic 으로 바꾸는 이유: 중력만 0 으로 두면 관절 솔버가 초기 포즈의
     * 미세한 어긋남을 풀려고 파츠를 조금씩 밀어낸다. Kinematic 은 아예 대상이 아니다.
     * 정렬 확인용 마네킹에 쓴다.
     */
    public void freeze() {
        var bi = world.getBodies();
        bi.setLinearVelocity(bodyId, new Vec3(0, 0, 0));
        bi.setAngularVelocity(bodyId, new Vec3(0, 0, 0));
        bi.setMotionType(bodyId, EMotionType.Kinematic, EActivation.DontActivate);
        bi.deactivateBody(bodyId);
    }

    public boolean isActive() {
        return !dead && world.getBodies().isActive(bodyId);
    }

    public int getBodyId() { return bodyId; }
    /** 조인트 연결용 Jolt Body 참조. */
    public com.github.stephengold.joltjni.Body getBody() { return body; }
    /**
     * Bukkit displays for the legacy entity renderer. Packet-rendered bodies return an empty list;
     * packet entity IDs have no Bukkit entity. Use the physics API to manipulate either renderer.
     */
    public List<Display> getDisplays() {
        if (visuals.stream().anyMatch(PartVisual::isPacket)) return Collections.emptyList();
        // Keep the legacy mutable list contract without storing entities in the physics body.
        return new java.util.AbstractList<>() {
            @Override public Display get(int index) { return visuals.get(index).getDisplays().get(0); }
            @Override public int size() { return visuals.size(); }
            @Override public Display set(int index, Display display) {
                return visuals.set(index, new EntityVisual(display)).getDisplays().get(0);
            }
            @Override public void add(int index, Display display) { visuals.add(index, new EntityVisual(display)); }
            @Override public Display remove(int index) { return visuals.remove(index).getDisplays().get(0); }
        };
    }
    public boolean isDead() { return dead; }

    // ------------------------------------------------------------------ 틱 반영

    void onPhysicsTick(RVec3 pos, Quat rot, boolean active) {
        if (dead) return;

        if (ticksLeft > 0 && --ticksLeft == 0) {
            dead = true;
            return;
        }
        // 바닥을 뚫고 월드 밖으로 빠진 것은 정리한다.
        // 수명이 무제한인 래그돌 파츠가 이렇게 빠지면 영원히 추적 목록에 남는다.
        if (pos.yy() < world.getWorld().getMinHeight() - 8) {
            dead = true;
            return;
        }
        if (visuals.isEmpty()) {
            return;
        }
        for (int i = visuals.size() - 1; i >= 0; i--) {
            if (!visuals.get(i).isValid()) {
                visuals.remove(i);
                localOffsets.remove(i);
                scales.remove(i);
                yOffsets.remove(i);
                localRots.remove(i);
                rightRots.remove(i);
            }
        }
        if (visuals.isEmpty()) {
            dead = true;
            return;
        }

        renderVisuals(pos, rot, active, false);
    }

    // Explicit script edits also apply to frozen bodies; ordinary sleeping ticks stay silent.
    private void refreshPacketPose() {
        if (dead || visuals.stream().noneMatch(PartVisual::isPacket)) return;
        renderVisuals(world.getBodies().getPosition(bodyId), world.getBodies().getRotation(bodyId), true, true);
    }

    /** Apply the current Jolt pose immediately so spawn-time audits see the first physics pose. */
    void refreshVisualPose() {
        if (dead) return;
        renderVisuals(world.getBodies().getPosition(bodyId), world.getBodies().getRotation(bodyId), true, false);
    }

    private void renderVisuals(RVec3 pos, Quat rot, boolean active, boolean packetsOnly) {
        Quaternionf q = new Quaternionf(rot.getX(), rot.getY(), rot.getZ(), rot.getW());

        // 엔티티는 본 피벗에 놓여야 모델이 제자리에 그려진다. 물리 좌표(상자 중심)에서 회전시킨
        // 피벗 오프셋을 빼서 되돌린다.
        Vector3f back = new Vector3f(pivotOffset).negate();
        q.transform(back);
        // limb Y오프셋은 엔티티 좌표가 아니라 Transformation.translation 에 들어간다.
        // 엔티티를 월드 밖으로 내리면 서버가 클라에 전송하지 않으므로 위치는 정상 범위에 둔다.
        Location to = new Location(world.getWorld(),
                pos.xx() + back.x, pos.yy() + back.y, pos.zz() + back.z);

        for (int i = 0; i < visuals.size(); i++) {
            // Sleeping packet bodies produce no update packets. Keep the legacy entity path intact.
            if (!active && visuals.get(i).isPacket()) continue;
            if (packetsOnly && !visuals.get(i).isPacket()) continue;
            // 로컬 오프셋은 파츠 회전을 따라 돌아야 무기가 손에 붙어 있다.
            Vector3f off = new Vector3f(localOffsets.get(i));
            q.transform(off);
            off.y += yOffsets.get(i).floatValue();
            // 강체 회전에 그 Display 고유의 회전을 얹는다. 이걸 빼먹으면 손에 든 검이
            // 팔 각도만 따라가면서 자기가 눕혀져 있던 각도를 잃는다.
            Quaternionf left = new Quaternionf(q).mul(localRots.get(i));
            visuals.get(i).setTransform(off, left, scales.get(i), rightRots.get(i));
            visuals.get(i).moveTo(to);
        }
    }

    // ------------------------------------------------------------------ 정리

    /** 물리 객체와 Display 를 모두 제거한다. */
    public void destroy() {
        dead = true;
        release();
        world.untrack(this);
        for (PartVisual d : visuals) {
            if (d != null && d.isValid()) {
                d.remove();
            }
        }
        visuals.clear();
    }

    /** 물리만 떼고 마지막 자세를 남긴다. 패킷 비주얼은 래그돌 수명/월드 clear까지 추적된다. */
    public void detach() {
        dead = true;
        release();
        world.untrack(this);
    }

    /** Jolt 바디 반납. 몇 번을 불러도 실제 반납은 한 번뿐이다. */
    private void release() {
        if (released) {
            return;
        }
        released = true;
        world.remove(bodyId);
    }
}
