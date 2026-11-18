package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EBodyType;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.readonly.QuatArg;
import com.github.stephengold.joltjni.readonly.Vec3Arg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.EIQUI.RemoteRagdoll.visual.Visuals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.EIQUI.RemoteRagdoll.main.that;

/**
 * Bukkit World 하나당 Jolt PhysicsSystem 하나.
 *
 * 틱 구동은 전역 티커 <b>하나</b>가 모든 월드를 돌린다. 물체마다 스케줄러 태스크를 만들면
 * 정리할 때 CraftScheduler.cancelTask 가 대기 태스크 전체를 선형 탐색해서 O(취소수 x 전체태스크수) 로
 * 터진다(WeaponAnimation 에서 겪은 그대로다). 여기서는 처음부터 티커 1개로 간다.
 *
 * 활성 강체가 없으면 update 자체를 건너뛴다. Jolt 는 정지한 물체를 자동으로 sleep 시키므로
 * 래그돌이 쌓여 있어도 멈춰 있으면 비용이 0 이다.
 */
public final class PhysicsWorld {

    /** 지형(정적)과 동적 물체를 나누는 오브젝트 레이어. 동적끼리·동적↔정적만 충돌한다. */
    public static final int LAYER_STATIC = 0;
    public static final int LAYER_DYNAMIC = 1;
    /**
     * 래그돌 전용 레이어. 지형과만 부딪히고 <b>서로는 통과</b>한다.
     * 관절로 묶인 팔이 몸통 안에 파묻힌 채로 충돌 판정까지 하면 서로 밀어내며 발작하듯 떨린다.
     * 파츠끼리 안 부딪히게 하는 게 래그돌에서는 표준적인 처리다.
     */
    public static final int LAYER_RAGDOLL = 2;
    private static final int NUM_OBJ_LAYERS = 3;
    private static final int NUM_BP_LAYERS = 2;

    /**
     * Jolt 바디 상한. 지형 충돌체까지 전부 여기에 들어간다.
     *
     * <p>래그돌 하나가 파츠 6개이고 파츠마다 주변 블록을 정적 바디로 올리므로(반경 2 = 125칸),
     * 시체 한 구가 수백 개를 쓴다. 4096 은 실전에서 열 몇 구만 겹쳐도 넘어섰고,
     * 넘으면 createBody 가 예외 없이 실패해서 지형이 안 올라가고 물체가 바닥을 통과했다.
     */
    private static final int MAX_BODIES = 32768;
    private static final int MAX_BODY_PAIRS = 16384;
    private static final int MAX_CONTACTS = 8192;

    /**
     * 동시에 유지할 래그돌 수 상한. 넘으면 오래된 것부터 지운다.
     *
     * <p><b>0 이면 무제한.</b> 래그돌 파츠 자체는 50명이 다 죽어도 300개라 부담이 아니다.
     * 비용은 파츠가 아니라 그 주변에 올라가는 지형 충돌체 쪽이고, 그건 MAX_BODIES 로 막는다.
     */
    public static int MAX_RAGDOLLS = 0;

    /**
     * 주변 엔티티를 충돌체로 올릴지. 끄면 사람이 래그돌을 그냥 통과한다.
     *
     * <p>켜면 물리 객체 근처 엔티티마다 Kinematic 바디가 하나씩 생기고 매 틱 따라 움직인다.
     * 사람이 많고 시체가 많은 난전에서 비용이 붙는 곳이라 스위치를 뒀다.
     */
    public static boolean ENTITY_COLLISION = true;

    private static final Map<UUID, PhysicsWorld> WORLDS = new HashMap<>();
    private static BukkitTask TICKER;

    /**
     * 래그돌 파츠끼리 서로 충돌하지 않게 하는 필터.
     *
     * 처음엔 전용 오브젝트 레이어(LAYER_RAGDOLL)로 처리하려 했는데, 그러면 지형(LAYER_STATIC)과의
     * 충돌까지 같이 죽어서 파츠가 바닥을 뚫고 공허로 떨어졌다. Jolt 에서 래그돌은 레이어가 아니라
     * <b>충돌 그룹</b>으로 다루는 게 정석이다. 같은 groupId 안의 subGroup 끼리만 무시하고,
     * 지형이나 다른 물체와는 정상적으로 부딪힌다.
     */
    private static GroupFilterTable FILTER_SELF_TABLE, FILTER_NONE_TABLE;
    private static GroupFilterTableRef FILTER_SELF, FILTER_NONE;
    private static int nextRagdollGroup = 1;
    private static final int MAX_RAGDOLL_PARTS = 8;

    /**
     * 래그돌 파츠끼리 부딪히게 할지.
     *
     * <p>끄면 파츠가 서로 통과해서 팔이 몸통을 뚫고 지나가는 게 보인다.
     * 켜도 <b>관절로 이어진 쌍</b>은 여전히 안 부딪힌다. 목·어깨·고관절은 설계상 상자가
     * 서로 파고들어 있어서, 그 쌍까지 충돌시키면 솔버가 매 틱 밀어내려 하면서 래그돌이 떤다.
     */
    public static boolean RAGDOLL_SELF_COLLISION = true;

    /**
     * 충돌을 끌 파츠 쌍. 서브그룹 번호는 Ragdoll.PARTS 순서다.
     * 0 몸통 / 1 머리 / 2 오른팔 / 3 왼팔 / 4 오른다리 / 5 왼다리
     */
    private static final int[][] RAGDOLL_JOINTED = {
            {0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5},   // 몸통과 각 부위 - 관절로 이어져 겹쳐 있다
            {4, 5},                                    // 두 다리는 골반에서 딱 맞닿아 있다
    };

    static synchronized int nextRagdollGroupId() {
        return nextRagdollGroup++;
    }

    /**
     * 두 테이블을 미리 만들어 두고 골라 쓴다.
     * 살아있는 바디가 참조 중인 테이블을 갈아끼우면 네이티브 쪽 수명이 꼬인다.
     */
    /** 액체 방울도 같은 필터를 쓴다(같은 그룹끼리 안 부딪힘). */
    static GroupFilterTableRef ragdollFilterRef() {
        return ragdollFilter();
    }

    private static synchronized GroupFilterTableRef ragdollFilter() {
        if (RAGDOLL_SELF_COLLISION) {
            if (FILTER_SELF == null) {
                FILTER_SELF_TABLE = new GroupFilterTable(MAX_RAGDOLL_PARTS);
                for (int[] pair : RAGDOLL_JOINTED) {
                    FILTER_SELF_TABLE.disableCollision(pair[0], pair[1]);
                }
                FILTER_SELF = FILTER_SELF_TABLE.toRef();
            }
            return FILTER_SELF;
        }
        if (FILTER_NONE == null) {
            FILTER_NONE_TABLE = new GroupFilterTable(MAX_RAGDOLL_PARTS);
            for (int i = 0; i < MAX_RAGDOLL_PARTS; i++) {
                for (int j = 0; j < MAX_RAGDOLL_PARTS; j++) {
                    FILTER_NONE_TABLE.disableCollision(i, j);
                }
            }
            FILTER_NONE = FILTER_NONE_TABLE.toRef();
        }
        return FILTER_NONE;
    }

    private final World world;
    private final PhysicsSystem system;
    private final BodyInterface bodies;
    private final TempAllocator temp;
    private final JobSystem jobs;
    private final TerrainCache terrain;
    private final EntityCache entities;
    private final List<PhysicsBody> tracked = new ArrayList<>();

    // 틱마다 새로 만들지 않도록 재사용하는 출력 버퍼
    private final RVec3 outPos = new RVec3();
    private final Quat outRot = new Quat();

    private PhysicsWorld(World world) {
        this.world = world;

        ObjectLayerPairFilterTable objFilter = new ObjectLayerPairFilterTable(NUM_OBJ_LAYERS);
        objFilter.enableCollision(LAYER_DYNAMIC, LAYER_DYNAMIC);
        objFilter.enableCollision(LAYER_DYNAMIC, LAYER_STATIC);
        objFilter.enableCollision(LAYER_RAGDOLL, LAYER_STATIC);
        objFilter.enableCollision(LAYER_STATIC, LAYER_RAGDOLL);
        objFilter.enableCollision(LAYER_RAGDOLL, LAYER_DYNAMIC);
        objFilter.enableCollision(LAYER_DYNAMIC, LAYER_RAGDOLL);
        objFilter.enableCollision(LAYER_STATIC, LAYER_DYNAMIC);

        BroadPhaseLayerInterfaceTable bpMap = new BroadPhaseLayerInterfaceTable(NUM_OBJ_LAYERS, NUM_BP_LAYERS);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_DYNAMIC, 1);
        bpMap.mapObjectToBroadPhaseLayer(LAYER_RAGDOLL, 1);

        ObjectVsBroadPhaseLayerFilter bpFilter =
                new ObjectVsBroadPhaseLayerFilterTable(bpMap, NUM_BP_LAYERS, objFilter, NUM_OBJ_LAYERS);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, bpMap, bpFilter, objFilter);
        this.system.setGravity(0f, -22f, 0f);   // 마크 체감에 맞춘 값. 실제 중력(-9.81)은 둥둥 떠 보인다.
        this.bodies = system.getBodyInterface();

        this.temp = new TempAllocatorImpl(8 * 1024 * 1024);
        this.jobs = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 2);
        this.terrain = new TerrainCache(this);
        this.entities = new EntityCache(this);
    }

    // ------------------------------------------------------------------ 획득

    /** 해당 월드의 물리 시스템을 얻는다. 없으면 만든다. Jolt 를 못 쓰면 null. */
    public static synchronized PhysicsWorld get(World world) {
        if (world == null || !JoltNatives.isAvailable()) {
            return null;
        }
        PhysicsWorld pw = WORLDS.get(world.getUID());
        if (pw == null) {
            pw = new PhysicsWorld(world);
            WORLDS.put(world.getUID(), pw);
        }
        // 티커는 RemoteRagdoll 소유이므로 GCBAPI /cleaner 의 cancelTasks 에 영향받지 않는다.
        // 그래도 외부에서 티커를 취소했을 때 기존 월드를 얻는 경로에서도 복구할 수 있어야 한다.
        // startTicker() 안에 중복 가드가 있으므로 매번 불러도 태스크가 겹치지 않는다.
        startTicker();
        return pw;
    }

    public World getWorld() { return world; }
    public PhysicsSystem getSystem() { return system; }
    public BodyInterface getBodies() { return bodies; }
    TerrainCache getTerrain() { return terrain; }

    // ------------------------------------------------------------------ 바디 생성

    /**
     * 동적 강체를 만든다.
     *
     * @param halfExtent 반쪽 크기(블록 단위). 마크 한 블록이 1.0 이다.
     */
    public int createBox(Location at, Vec3Arg halfExtent, QuatArg rotation, float mass) {
        Body b = createBoxBody(at, halfExtent, rotation, mass);
        return b == null ? 0 : b.getId();
    }

    /**
     * 위와 같지만 {@link Body} 객체를 돌려준다. 조인트({@code SwingTwistConstraintSettings.create(Body, Body)})는
     * ID 가 아니라 Body 참조를 요구하므로 래그돌 쪽에서 이걸 쓴다.
     */
    public Body createBoxBody(Location at, Vec3Arg halfExtent, QuatArg rotation, float mass) {
        return createBoxBody(at, halfExtent, rotation, mass, LAYER_DYNAMIC);
    }

    public Body createBoxBody(Location at, Vec3Arg halfExtent, QuatArg rotation, float mass, int layer) {
        return createBoxBody(at, halfExtent, rotation, mass, layer, -1, -1);
    }

    /**
     * @param groupId    같은 값끼리는 subGroup 필터를 탄다. -1 이면 그룹 안 씀.
     * @param subGroupId 0..7. 같은 groupId 안에서는 서로 충돌하지 않는다.
     */
    public Body createBoxBody(Location at, Vec3Arg halfExtent, QuatArg rotation, float mass, int layer,
                              int groupId, int subGroupId) {
        BoxShape shape = new BoxShape(halfExtent, 0.01f);
        BodyCreationSettings bcs = new BodyCreationSettings(
                shape,
                new RVec3(at.getX(), at.getY(), at.getZ()),
                rotation == null ? new Quat() : rotation,
                EMotionType.Dynamic,
                layer);
        MassProperties mp = new MassProperties();
        mp.setMass(mass);
        bcs.setMassPropertiesOverride(mp);
        bcs.setOverrideMassProperties(com.github.stephengold.joltjni.enumerate.EOverrideMassProperties.CalculateInertia);
        bcs.setRestitution(0.05f);
        // 래그돌은 마찰이 높으면 발이 바닥에 붙어 그대로 서버린다.
        bcs.setFriction(groupId >= 0 ? 0.25f : 0.6f);
        bcs.setLinearDamping(0.10f);
        // 래그돌은 각속도 감쇠를 높여야 팔다리가 덜 파닥거린다.
        bcs.setAngularDamping(groupId >= 0 ? 0.6f : 0.35f);
        if (groupId >= 0) {
            bcs.setCollisionGroup(new CollisionGroup(ragdollFilter(), groupId, subGroupId));
        }
        Body body = bodies.createBody(bcs);
        if (body == null) {
            // Jolt 는 바디 상한(MAX_BODIES)에 걸리면 예외 없이 null 을 준다.
            // 여기서 안 찍으면 "파츠 0개"만 보이고 원인을 못 찾는다.
            that.getLogger().warning("[Physics] 바디 생성 실패 - 한도 초과로 보인다"
                    + " (상한 " + MAX_BODIES + ", 추적 " + tracked.size()
                    + ", 지형 " + terrain.getLoadedCount() + ")");
            return null;
        }
        bodies.addBody(body, EActivation.Activate);
        if (groupId >= 0) {
            // 파츠가 작아서 빠르게 떨어지면 한 틱에 블록을 지나쳐 버린다(터널링).
            bodies.setMotionQuality(body.getId(),
                    com.github.stephengold.joltjni.enumerate.EMotionQuality.LinearCast);
        }
        return body;
    }

    /** 조인트 등록. 래그돌 관절 연결에 쓴다. */
    public void addConstraint(TwoBodyConstraint constraint) {
        system.addConstraint(constraint);
    }

    public void remove(int bodyId) {
        // 0 은 멀쩡한 바디 id 다. 걸러야 하는 건 cInvalidBodyId(-1).
        // 0 을 막고 있으면 그 바디가 영원히 안 풀려서 Jolt 바디 풀이 샌다.
        if (bodyId == com.github.stephengold.joltjni.Jolt.cInvalidBodyId) return;
        try {
            bodies.removeBody(bodyId);
            bodies.destroyBody(bodyId);
        } catch (Throwable ignored) {
        }
    }

    private final List<Ragdoll> ragdolls = new ArrayList<>();
    private final List<Liquid> liquids = new ArrayList<>();
    private int debugTick = 0;

    /** 지형을 올릴 대상. 일반 물체 + 액체 방울을 함께 모은다. */
    private List<Integer> collectBodyIds() {
        List<Integer> ids = new ArrayList<>(tracked.size() + 16);
        for (PhysicsBody b : tracked) {
            if (b != null) {
                ids.add(b.getBodyId());
            }
        }
        for (Liquid l : liquids) {
            ids.addAll(l.bodyIds());
        }
        return ids;
    }

    void trackLiquid(Liquid liq) {
        liquids.add(liq);
    }

    void trackRagdoll(Ragdoll doll) {
        while (MAX_RAGDOLLS > 0 && ragdolls.size() >= MAX_RAGDOLLS && !ragdolls.isEmpty()) {
            Ragdoll oldest = ragdolls.remove(0);
            try {
                oldest.destroy();
            } catch (Throwable ignored) {
            }
        }
        ragdolls.add(doll);
    }

    void track(PhysicsBody body) {
        tracked.add(body);
    }

    void untrack(PhysicsBody body) {
        tracked.remove(body);
    }

    public int getTrackedCount() { return tracked.size(); }

    public int getActiveBodyCount() {
        return system.getNumActiveBodies(EBodyType.RigidBody);
    }

    /** 지금 Jolt 에 올라가 있는 지형 충돌체 수. 물체가 바닥을 뚫을 때 이게 0 이면 지형 동기화 문제다. */
    public int getTerrainCount() { return terrain.getLoadedCount(); }

    /** 켜면 매 20틱 추적 상태를 콘솔에 찍는다. */
    public static boolean DEBUG = false;

    // ------------------------------------------------------------------ 틱

    private static void startTicker() {
        if (TICKER != null && !TICKER.isCancelled()) {
            return;
        }
        TICKER = that.getServer().getScheduler().runTaskTimer(that, PhysicsWorld::tickAll, 1L, 1L);
    }

    /**
     * 호환용 수동 티커 복구. 이미 살아 있으면 아무 일도 하지 않는다.
     * GCBAPI Clearer 와의 연동은 필요 없다. 티커 소유자는 RemoteRagdoll 다.
     */
    public static void resumeTicker() {
        if (WORLDS.isEmpty()) {
            return;
        }
        startTicker();
    }

    /** 플러그인 종료 시 clearAll() 이후에 전역 티커 하나를 정리한다. */
    static void stopTicker() {
        if (TICKER != null) {
            TICKER.cancel();
            TICKER = null;
        }
    }

    private static void tickAll() {
        List<PhysicsWorld> snapshot;
        synchronized (PhysicsWorld.class) {
            if (WORLDS.isEmpty()) return;
            snapshot = new ArrayList<>(WORLDS.values());
        }
        for (PhysicsWorld pw : snapshot) {
            try {
                pw.tick();
            } catch (Throwable t) {
                that.getLogger().warning("[Physics] " + pw.world.getName() + " 틱 실패: " + t);
            }
        }
        Visuals.flush();
    }

    private void tick() {
        // 래그돌 수명 먼저. 관절을 먼저 걷어내고 파츠를 지우기 위해 파츠보다 앞서 처리한다.
        if (!ragdolls.isEmpty()) {
            ragdolls.removeIf(Ragdoll::tickLifetime);
        }

        // 죽은 바디 정리.
        // destroy() 안에서 untrack() -> tracked.remove() 가 불리므로, 순회 중에 destroy 를 부르면
        // 리스트를 순회하면서 수정하는 꼴이 되어 ConcurrentModificationException 과 함께
        // 리스트가 깨진다(null 이 섞여 들어온다). 먼저 골라내고 순회가 끝난 뒤에 정리한다.
        List<PhysicsBody> dead = null;
        for (PhysicsBody b : tracked) {
            if (b != null && b.isDead()) {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(b);
            }
        }
        if (dead != null) {
            // 죽은 파츠가 래그돌 것이면 그 래그돌을 통째로 정리한다.
            // 파츠만 먼저 지우면 관절이 사라진 바디를 물고 남는다 — Ragdoll.destroy 가
            // "관절을 먼저 걷어낸 뒤 파츠를 지운다"고 못박아 둔 순서가 깨진다.
            if (!ragdolls.isEmpty()) {
                List<Ragdoll> owners = null;
                for (Ragdoll doll : ragdolls) {
                    for (PhysicsBody b : dead) {
                        if (doll.getBodies().contains(b)) {
                            if (owners == null) {
                                owners = new ArrayList<>();
                            }
                            owners.add(doll);
                            break;
                        }
                    }
                }
                if (owners != null) {
                    ragdolls.removeAll(owners);
                    for (Ragdoll doll : owners) {
                        try {
                            doll.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            tracked.removeAll(dead);
            for (PhysicsBody b : dead) {
                b.destroy();
            }
        }

        // 액체 방울은 Jolt body 만 직접 만들고 tracked 에는 안 들어간다.
        // 여기서 그냥 빠져나가면 액체가 시뮬레이션도 렌더도 안 된다.
        if (tracked.isEmpty()) {
            if (liquids.isEmpty()) {
                terrain.clear();
                entities.clear();
                return;
            }
            // 방울 주변 지형도 올려야 바닥을 안 뚫는다
            terrain.update(collectBodyIds());
            // 방울은 밟고 다녀도 그만이라 엔티티 충돌체는 안 올린다
            entities.clear();
            system.update(1f / 20f, 2, temp, jobs);
            liquids.removeIf(Liquid::tick);
            return;
        }

        // 활성 물체 주변 지형만 Jolt 에 올린다
        List<Integer> ids = collectBodyIds();
        terrain.update(ids);
        // 주변 사람을 Kinematic 충돌체로 올려 래그돌을 발로 찰 수 있게 한다
        if (ENTITY_COLLISION) {
            entities.update(ids);
        } else {
            entities.clear();
        }

        if (DEBUG && !tracked.isEmpty()) {
            debugTick++;
            if (debugTick % 20 == 0) {
                PhysicsBody first = tracked.get(0);
                RVec3 p0 = bodies.getPosition(first.getBodyId());
                that.getLogger().info(String.format(
                        "[Physics] 물체 %d개 / 지형블록 %d개 / 첫물체 y=%.2f active=%s",
                        tracked.size(), terrain.getLoadedCount(), p0.yy(),
                        bodies.isActive(first.getBodyId())));
            }
        }

        system.update(1f / 20f, 2, temp, jobs);

        // 액체 방울은 Display 가 없고 파티클로만 보인다. 물리 결과가 갱신된 뒤에 그린다.
        if (!liquids.isEmpty()) {
            liquids.removeIf(Liquid::tick);
        }

        // 결과를 렌더(ItemDisplay)에 반영.
        // onPhysicsTick 이 dead 로 만들 수는 있어도 리스트를 건드리진 않지만,
        // 만약을 대비해 복사본을 돈다.
        for (PhysicsBody b : new ArrayList<>(tracked)) {
            if (b == null) continue;
            bodies.getPositionAndRotation(b.getBodyId(), outPos, outRot);
            b.onPhysicsTick(outPos, outRot, bodies.isActive(b.getBodyId()));
        }
    }

    // ------------------------------------------------------------------ 정리

    /** 해당 월드의 모든 물리 객체 제거. */
    public void clear() {
        for (Liquid l : new ArrayList<>(liquids)) {
            try { l.destroy(); } catch (Throwable ignored) {}
        }
        liquids.clear();

        // 래그돌 먼저. 관절을 걷어낸 뒤에 파츠가 지워져야 한다.
        for (Ragdoll d : new ArrayList<>(ragdolls)) {
            try { d.destroy(); } catch (Throwable ignored) {}
        }
        ragdolls.clear();

        List<PhysicsBody> copy = new ArrayList<>(tracked);
        tracked.clear();
        for (PhysicsBody b : copy) {
            if (b != null) {
                b.destroy();
            }
        }
        terrain.clear();
        entities.clear();
        Visuals.clear(world);
    }

    /** Unload only an existing world; never create a physics system during world teardown. */
    public static synchronized void clearWorld(World world) {
        PhysicsWorld pw = WORLDS.remove(world.getUID());
        try {
            if (pw != null) pw.clear();
        } finally {
            Visuals.clear(world);
        }
    }

    /** 전 월드 정리. 게임 종료·서버 종료 시 호출. */
    public static synchronized void clearAll() {
        for (PhysicsWorld pw : WORLDS.values()) {
            try { pw.clear(); } catch (Throwable ignored) {}
        }
        Visuals.clearAll();
    }
}
