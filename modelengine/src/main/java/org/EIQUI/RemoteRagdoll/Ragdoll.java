package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.render.DisplayBone;
import com.ticxo.modelengine.core.model.render.DisplayRendererImpl;
import org.EIQUI.GCBAPI.modelengine.MEAPI;
import org.EIQUI.RemoteRagdoll.visual.PartVisual;
import org.EIQUI.RemoteRagdoll.visual.Visuals;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.EIQUI.RemoteRagdoll.main.that;

/**
 * player.bbmodel 을 파츠별로 뜯어 물리를 먹인 래그돌.
 *
 * <p>동작 순서
 * <ol>
 *   <li>{@link MEAPI#getBoneLocation} 으로 각 본의 현재 월드 위치를 읽는다(= 죽는 순간의 포즈).</li>
 *   <li>본이 렌더 중인 ItemStack 을 DisplayBone.BoneData 에서 꺼내 ItemDisplay 로 떼어낸다.
 *       (부위별 물리 피벗과 PLAYER_LIMB 셰이더 오프셋을 유지한다 - detachBone 참고)</li>
 *   <li>파츠마다 강체를 만들고 관절 위치를 앵커로 SwingTwist 로 묶는다.</li>
 * </ol>
 *
 * <p><b>플레이어 스킨 팔다리의 핵심</b>: 리소스팩 core 셰이더(stable_player_display 방식)가
 * 정점의 Y 로 부위를 판별한다. 그래서 Transformation.translation.y 에 부위별 오프셋
 * (머리 0 / 오른팔 -1024 / ... / 슬림팔 -3072,-4096 / 몸통 -5120 / 다리 -6144,-7168)을 넣어야 한다.
 * 엔티티 좌표를 내리면 월드 높이 밖이 되어 클라이언트에 전송되지 않으므로 반드시 translation 으로 준다.
 *
 * <p>치수는 blueprints/player.bbmodel 에서 그대로 뽑았다. bbmodel 단위는 <b>16 = 1블록</b>이다
 * (hitbox 큐브가 9.6 x 28.8 x 9.6 이고 플레이어 히트박스가 0.6 x 1.8 x 0.6 인 것으로 확인).
 *
 * <p>shadow(바닥 그림자 평면)와 hitbox(비가시 충돌 상자)는 제외한다.
 */
public final class Ragdoll {

    /**
     * 래그돌이 만든 Display 에 붙이는 태그.
     *
     * <p>정상 경로로는 PhysicsBody.destroy() 가 지운다. 중간에 예외가 나서 물리에 등록되지 못한
     * Display 는 아무도 안 치우므로, 판이 끝날 때 이 태그로 쓸어담는다.
     */
    public static final String RAGDOLL_TAG = "GCB:Ragdoll";

    /** bbmodel 단위 -> 블록 */
    private static final float U = 1f / 16f;

    /** 파츠별 렌더 아이템을 콘솔에 찍는다. 문제 잡을 때만 켠다. */
    public static boolean DEBUG_ITEMS = false;
    public static boolean AUDIT_TRANSFORMS = false;
    private static final List<String> TRANSFORM_AUDITS = new ArrayList<>();

    /** Renderer for subsequently spawned parts. Existing ragdolls retain their renderer. */
    public static boolean PACKET_RENDER = true;

    /**
     * 팔 좌우 위치 보정(블록). <b>음수가 바깥쪽(어깨가 넓어짐)</b>이다.
     * pivotOffset 은 엔티티 배치에서 빼는 값이라 부호가 뒤집혀 보인다.
     *
     * 슬림 스킨은 팔 모델이 안쪽에 붙어 그려지는데(리소스팩 slim_right.json 의 translation 0.46875,
     * 일반 팔은 0.9375) 물리 치수는 player.bbmodel 의 일반 팔(4px) 기준이라 어깨가 좁아 보인다.
     * -0.1 이 실측으로 맞았다.
     */
    public static double ARM_SPREAD = -0.1;

    /** 팔 관절(어깨) 높이 미세 조정(블록). */
    public static double ARM_Y = 0.0;

    /**
     * 관절 한계(도). cone = 주 꺾임, plane = 옆으로 벌어짐, twist = 비틀림.
     *
     * 너무 좁으면 축(월드 -Y) 기준 원뿔의 중심이 "선 자세"라서 뻣뻣한 인형처럼 서버리고,
     * 너무 넓으면 팔다리가 접히듯 꺾인다. 사람 관절 범위 근처가 자연스럽다.
     * 눈으로 맞추는 값이라 런타임 조절 가능하게 뒀다.
     */
    public static double HEAD_CONE = 50, HEAD_PLANE = 45, HEAD_TWIST = 45;
    public static double ARM_CONE  = 95, ARM_PLANE  = 75, ARM_TWIST  = 55;
    public static double LEG_CONE  = 70, LEG_PLANE  = 45, LEG_TWIST  = 30;

    /**
     * 상하체가 갈렸을 때 두 다리를 서로 묶는 관절의 한계(도).
     *
     * <p>완전히 고정하면 하체가 통짜 블록처럼 굴러서 어색하다. 조금 놀 수 있게 열어둔다.
     */
    public static double WAIST_CONE = 22, WAIST_PLANE = 22, WAIST_TWIST = 12;

    /**
     * 장비(무기/모자) 크기 배율. DisplayBone 의 scale 을 그대로 복사해도 실제와 다르게 보일 때 쓴다.
     * ModelEngine 이 손에 든 아이템에 별도 배율을 적용하는 것으로 보인다.
     */
    public static double ATTACH_SCALE = 1.0;

    /** 장비 위치 보정(블록). 파츠 로컬 기준. */
    public static double ATTACH_X = 0.0, ATTACH_Y = 0.0, ATTACH_Z = 0.0;

    /**
     * 모델이 바라보는 방향 보정(도). DisplayBone 의 회전은 본 <b>로컬</b> 값이라
     * 모델 전체가 어디를 보고 있는지는 안 들어있다. 그래서 엔티티 yaw 를 합성해준다.
     */
    public static double YAW_OFFSET = 0.0;

    /**
     * @param bone        ModelEngine 본 이름
     * @param half        충돌 상자 반쪽 크기(블록)
     * @param pivotOffset 본 피벗 -> 큐브 중심 오프셋(블록)
     * @param mass        질량
     */
    private record Part(String bone, Vector3f half, Vector3f pivotOffset, float mass) {}

    /** 떼어낸 Display 들 + 렌더용 Y 오프셋(플레이어 limb 용). */
    private record SourceTransform(Vector3f anchor, Vector3f translation, Quaternionf left,
                                   Vector3f scale, Quaternionf right, String display,
                                   List<String> items) {}
    private record Detached(List<PartVisual> displays, double yOffset, SourceTransform source) {}

    // 큐브 from/to 와 본 origin 에서 계산한 값이다. 주석의 숫자는 bbmodel 원본 단위.
    private static final Part HEAD = new Part("head",
            new Vector3f(3.76f * U, 3.76f * U, 3.76f * U),   // 7.52 정육면체
            new Vector3f(0, 3.76f * U, 0),                    // 피벗 y=22.56 -> 중심 y=26.32
            5f);
    private static final Part BODY = new Part("body",
            new Vector3f(3.76f * U, 5.64f * U, 1.88f * U),   // 7.52 x 11.28 x 3.76
            new Vector3f(0, -5.64f * U, 0),                   // 피벗 y=22.56 -> 중심 y=16.92
            12f);
    private static final Part R_ARM = new Part("right_arm",
            new Vector3f(1.88f * U, 5.64f * U, 1.88f * U),
            new Vector3f(0.94f * U, -3.76f * U, 0),           // 피벗 (4.7,20.68) -> 중심 (5.64,16.92)
            3f);
    private static final Part L_ARM = new Part("left_arm",
            new Vector3f(1.88f * U, 5.64f * U, 1.88f * U),
            new Vector3f(-0.94f * U, -3.76f * U, 0),          // 피벗 (-4.7,20.68) -> 중심 (-5.64,16.92)
            3f);
    private static final Part R_LEG = new Part("right_leg",
            new Vector3f(1.88f * U, 5.64f * U, 1.88f * U),
            new Vector3f(0, -5.64f * U, 0),                   // 피벗 y=11.28 -> 중심 y=5.64
            4f);
    private static final Part L_LEG = new Part("left_leg",
            new Vector3f(1.88f * U, 5.64f * U, 1.88f * U),
            new Vector3f(0, -5.64f * U, 0),
            4f);

    private static final Part[] PARTS = {BODY, HEAD, R_ARM, L_ARM, R_LEG, L_LEG};

    /**
     * 장비 본 -> 붙일 파츠. player.bbmodel 의 ih_helmet / ir_righthand / il_lefthand 가
     * ModelEngine 에서 helmet / righthand / lefthand 로 정규화된다.
     */
    /**
     * @param mirrorX 붙일 위치의 좌우를 뒤집을지.
     *                player.bbmodel 의 il_lefthand 가 오른손 자리(x=+5)에 그대로 복사돼 있어서
     *                왼손 장비가 몸을 가로질러 오른쪽에 붙는다. 그 비대칭을 여기서 되돌린다.
     *                (ir_righthand 도 x=+5 로 같은 값이다. 팔 본은 ±4.7 로 제대로 대칭이다)
     */
    private record Attach(String bone, String host, float scale, boolean mirrorX) {}

    /**
     * 장비 본의 배율은 <b>건드리지 않는다.</b>
     *
     * <p>ModelEngine 이 helmet 본의 렌더 배율에 이미 0.57 을 넣어두고 있다(실측 로그로 확인).
     * detachBone 이 그 값을 그대로 복사해오므로 여기서 또 곱하면 0.57² 이 되어 쪼그라든다.
     * 눈으로 맞출 일이 생기면 ATTACH_SCALE 을 쓴다.
     */

    /**
     * 코스튬 배율/미세 오프셋(블록). 재빌드 없이 조절하려고 열어둔다.
     *
     * <p>코스튬은 ModelEngine 본이 아니라 <b>투명 아머스탠드가 헬멧 슬롯에 쓴 아이템</b>이다
     * ({@code Costume.java}). 그래서 HEAD 디스플레이 변환으로 그려야 착용했을 때와 같아 보인다.
     * 다만 HEAD 변환에는 자체 이동값이 들어있어서, Transformation 의 scale 을 줄이면
     * 그 이동값까지 같이 줄어들어 아이템이 앵커 쪽으로 끌려온다. 그 차이를 Y 로 메운다.
     */
    public static double COSTUME_SCALE = 0.625;
    /**
     * Y 기본값은 canadianman_target.bbmodel 에서 가져왔다.
     * g_costumes(y=56.25) - ih_helmet(y=26.32) = 29.93 units = 1.87블록.
     * 그 모델러도 같은 보정을 하고 있고, 눈으로 맞춘 값과도 일치했다.
     */
    public static double COSTUME_X = 0.0, COSTUME_Y = 1.87, COSTUME_Z = 0.0;

    private static final Attach[] ATTACHMENTS = {
            new Attach("helmet",    HEAD.bone(),  1.0f, false),
            new Attach("righthand", R_ARM.bone(), 1.0f, false),
            new Attach("lefthand",  L_ARM.bone(), 1.0f, true),
    };

    /**
     * 왼손 장비 좌우 보정.
     *
     * <p>player.bbmodel 의 il_lefthand 가 오른손 자리(x=+5)에 복사돼 있던 것을 고쳤으므로
     * (캐나디언맨의 g_lefthandfixer 처럼 x=-5) 기본은 꺼둔다.
     * 블루프린트를 되돌리거나 같은 문제가 있는 다른 모델을 쓸 때만 켠다.
     */
    public static boolean MIRROR_LEFT_HAND = false;

    private final List<PhysicsBody> bodies = new ArrayList<>();
    /** Visuals created during spawn but not yet owned by a PhysicsBody. */
    private final List<PartVisual> pendingVisuals = new ArrayList<>();

    /**
     * 몸통 파츠 피벗(목) -> 코스튬이 놓이는 자리의 로컬 오프셋.
     *
     * <p>코스튬은 모자가 아니라 보통 등 장식 계열이라 <b>몸통</b>에 붙인다.
     * 머리에 붙이면 목이 잘렸을 때 날개나 망토가 머리를 따라 날아간다.
     *
     * <p>기준 자리는 helmet 본에서 구한다. player.bbmodel 은 머리 본과 몸통 본의 피벗이
     * 둘 다 목(y=22.56)이라 어느 쪽 기준으로 재도 같은 값이지만, 그 우연에 기대지 않게
     * 몸통 피벗 기준으로 명시해서 잰다.
     */
    private final Vector3f costumeAnchor = new Vector3f(0, 3.76f * U, 0);

    /** 생성 시점에 모델이 보던 방향. 마네킹을 똑바로 세울 때 이 각도로 되돌린다. */
    private final Quaternionf uprightRotation = new Quaternionf();
    /**
     * 걸어둔 관절. <b>바디보다 먼저 제거해야 한다.</b>
     * 조인트가 참조 중인 바디를 먼저 지우면 Jolt 쪽 assert 가 터지면서 abort() 로 프로세스가 즉사한다
     * (JVM 크래시 덤프도 안 남아서 원인을 찾기 어렵다. 실제로 한 번 당했다).
     */
    private final List<TwoBodyConstraint> constraints = new ArrayList<>();

    /** 부위 이름 -> 그 부위를 몸통에 묶고 있는 관절. 잘라낼 때 찾아야 한다. */
    private final Map<String, TwoBodyConstraint> jointByBone = new LinkedHashMap<>();
    /** 부위 이름 -> 강체. */
    private final Map<String, PhysicsBody> bodyByBone = new LinkedHashMap<>();
    /** 부위 이름 -> 생성 시점의 본 피벗(= 관절 위치). 나중에 새 관절을 걸 때 앵커로 쓴다. */
    private final Map<String, Location> spawnPivots = new LinkedHashMap<>();
    /**
     * 부위 이름 -> 그 관절 자리를 몸통 로컬 좌표로 바꿔둔 것.
     * 잘린 자리는 양쪽에서 다 보여야 하는데, 몸통 쪽 자리는 몸통이 굴러가면 같이 움직이므로
     * 매번 현재 자세로 다시 계산해야 한다.
     */
    private final Map<String, Vector3f> woundOnTorso = new LinkedHashMap<>();
    private PhysicsWorld world;
    private int ticksLeft;

    /**
     * 마지막으로 만든 래그돌. /phys cut 같은 테스트 명령이 직전 래그돌을 집으려고 쓴다.
     * 명령이 각각 따로 실행돼서 스크립트 지역변수로는 넘길 수가 없다.
     */
    public static Ragdoll LAST;

    private Ragdoll() {}

    /**
     * 지금 포즈 그대로 래그돌을 만든다. 원본 모델은 건드리지 않으므로,
     * 부르는 쪽에서 원래 엔티티/모델을 숨기거나 제거해야 한다.
     *
     * @param source        모델을 달고 있는 엔티티
     * @param modelId       ModelEngine 모델 ID (보통 "player")
     * @param impulse       생성 직후 몸통에 가할 충격(사망 방향 날리기용). null 이면 안 줌
     * @param lifetimeTicks 이 틱 뒤 자동 소멸. 0 이하면 무제한
     */
    public static Ragdoll spawn(Entity source, String modelId, Vector impulse, int lifetimeTicks) {
        if (source == null || !JoltNatives.isAvailable()) {
            return null;
        }
        Ragdoll doll = new Ragdoll();
        try {
            return spawnParts(source, modelId, impulse, lifetimeTicks, doll);
        } catch (RuntimeException | LinkageError failure) {
            try {
                doll.destroy();
            } finally {
                doll.pendingVisuals.forEach(PartVisual::remove);
                doll.pendingVisuals.clear();
            }
            throw failure;
        }
    }

    private static Ragdoll spawnParts(Entity source, String modelId, Vector impulse, int lifetimeTicks, Ragdoll doll) {
        // 이 래그돌만의 충돌 그룹. 파츠끼리는 안 부딪히고 지형과는 정상 충돌한다.
        final int groupId = PhysicsWorld.nextRagdollGroupId();
        Map<String, PhysicsBody> byBone = new LinkedHashMap<>();
        Map<String, Location> pivotByBone = new LinkedHashMap<>();

        // 모델 배율. 플레이어가 커지거나 작아진 상태면 파츠 치수도 같이 가야 한다.
        // 본의 월드 위치와 Display 배율은 ModelEngine 이 이미 키워서 주므로 여기서 손댈 게 없고,
        // 상수로 박아둔 충돌 상자 크기와 피벗 오프셋만 곱해주면 된다.
        float ms = 1f;
        try {
            ActiveModel am0 = MEAPI.getActiveModel(source, modelId);
            if (am0 != null && am0.getScale() != null) {
                ms = am0.getScale().y();
            }
        } catch (Throwable ignored) {
        }
        if (ms <= 0.01f || !Float.isFinite(ms)) {
            ms = 1f;
        }

        // 서브그룹 번호는 PARTS 인덱스로 고정한다. 성공한 파츠만 세면 하나라도 실패했을 때
        // 번호가 밀려서 PhysicsWorld 의 관절 쌍 표와 어긋난다.
        for (int idx = 0; idx < PARTS.length; idx++) {
            Part part = PARTS[idx];
            final int subGroup = idx;
            Location pivot = MEAPI.getBoneLocation(source, modelId, part.bone());
            if (pivot == null) {
                that.getLogger().warning("[Ragdoll] 본을 못 찾음: " + part.bone()
                        + " (모델 " + modelId + ") - /phys bones 로 실제 이름 확인 필요");
                continue;
            }
            Detached det = detachBone(source, modelId, part.bone(), pivot);
            List<PartVisual> parts = det.displays();
            doll.pendingVisuals.addAll(parts);
            if (parts.isEmpty()) {
                continue;
            }
            // 파츠의 현재 회전. Display 의 Transformation 에 들어있다.
            Quaternionf rot = new Quaternionf(parts.get(0).getTransformation().getLeftRotation());

            // 물리는 상자 중심 기준이므로 피벗에서 회전시킨 오프셋만큼 옮긴 곳에 만든다.
            Vector3f po = new Vector3f(part.pivotOffset());
            if (part == R_ARM) {
                po.x += (float) ARM_SPREAD;
                po.y += (float) ARM_Y;
            } else if (part == L_ARM) {
                po.x -= (float) ARM_SPREAD;
                po.y += (float) ARM_Y;
            }
            po.mul(ms);
            // 피벗->중심 오프셋도 같은 방향으로 돌려야 팔다리가 제 위치에 붙는다.
            Vector3f off = new Vector3f(po);
            new Quaternionf()
                    .rotateY((float) Math.toRadians(-source.getLocation().getYaw() + YAW_OFFSET))
                    .transform(off);
            Location center = pivot.clone().add(off.x, off.y, off.z);

            // 수명은 파츠가 아니라 Ragdoll 이 관리한다. 파츠가 각자 사라지면
            // 조인트가 참조하는 바디가 먼저 없어져서 Jolt 가 죽는다.
            PhysicsBody pb = PhysicsBody.createVisuals(center, parts,
                    new Vector(part.half().x * ms, part.half().y * ms, part.half().z * ms),
                    part.mass(), 0,
                    new Vector(po.x, po.y, po.z),
                    PhysicsWorld.LAYER_DYNAMIC, det.yOffset(), groupId, subGroup);
            if (pb == null) {
                parts.forEach(PartVisual::remove);
                doll.pendingVisuals.removeAll(parts);
                continue;
            }
            byBone.put(part.bone(), pb);
            pivotByBone.put(part.bone(), pivot);
            doll.bodies.add(pb);
            doll.pendingVisuals.removeAll(parts);
        }

        // 장비(모자/무기)를 해당 파츠에 붙인다.
        //
        // 주의: 엔티티에 장비를 입히는 것만으로는 본 아이템이 채워지지 않는다.
        // 부르는 쪽에서 MEAPI.setItem(호스트, 모델, 본, 아이템) 을 먼저 해줘야 한다.
        // (캐나디언맨.sk 의 modelItemEquip 이 쓰는 방식이 그것이다)
        doll.uprightRotation.rotateY((float) Math.toRadians(
                -source.getLocation().getYaw() + YAW_OFFSET));

        // 코스튬 앵커. 모자가 놓이는 자리는 helmet 본이 알려준다.
        // 헬멧을 안 쓴 상태여도 본 자체는 있으므로 착용 여부와 무관하게 구할 수 있다.
        Location torsoPivot = pivotByBone.get(BODY.bone());
        Location helmetPivot = MEAPI.getBoneLocation(source, modelId, "helmet");
        if (torsoPivot != null && helmetPivot != null) {
            doll.costumeAnchor.set(
                    (float) (helmetPivot.getX() - torsoPivot.getX()),
                    (float) (helmetPivot.getY() - torsoPivot.getY()),
                    (float) (helmetPivot.getZ() - torsoPivot.getZ()));
        }

        for (Attach a : ATTACHMENTS) {
            PhysicsBody host = byBone.get(a.host());
            Location hostPivot = pivotByBone.get(a.host());
            if (host == null || hostPivot == null) {
                continue;
            }
            Location bonePivot = MEAPI.getBoneLocation(source, modelId, a.bone());
            if (bonePivot == null) {
                continue;
            }
            Detached att = detachBone(source, modelId, a.bone(), bonePivot);
            doll.pendingVisuals.addAll(att.displays());
            if (att.displays().isEmpty()) {
                continue;
            }

            // HeldItem 의 location/rotation 은 여기서 따로 곱하지 않는다.
            // 그 값들은 ModelEngine 이 이미 DisplayBone 의 렌더 변환(위치/left/right 회전)에
            // 반영해둔 것이라, detachBone 이 그대로 복사해온다. 여기서 또 곱하면 이중 적용이 된다.
            Vector3f local = new Vector3f(
                    (float) (bonePivot.getX() - hostPivot.getX() + ATTACH_X),
                    (float) (bonePivot.getY() - hostPivot.getY() + ATTACH_Y),
                    (float) (bonePivot.getZ() - hostPivot.getZ() + ATTACH_Z));

            if (a.mirrorX() && MIRROR_LEFT_HAND) {
                // 오프셋은 월드 좌표라 모델이 보는 방향이 섞여 있다.
                // 모델 기준으로 되돌린 뒤 x 를 뒤집고 다시 되돌린다.
                Quaternionf inv = new Quaternionf(doll.uprightRotation).conjugate();
                inv.transform(local);
                local.x = -local.x;
                new Quaternionf(doll.uprightRotation).transform(local);
            }

            float sc = a.scale() * (float) ATTACH_SCALE;
            try {
                for (PartVisual d : att.displays()) {
                    Transformation tr = d.getTransformation();
                    d.setTransform(
                            tr.getTranslation(),
                            tr.getLeftRotation(),
                            new Vector3f(tr.getScale()).mul(sc),
                            tr.getRightRotation());
                    host.attachVisual(d, local, att.yOffset());
                }
                host.refreshVisualPose();
                if (AUDIT_TRANSFORMS && att.source()!=null && !att.source().items().isEmpty()) {
                    for (int i=0;i<att.displays().size();i++) {
                        recordTransformAudit(a.bone(),att.source(),
                                att.source().items().get(Math.min(i,att.source().items().size()-1)),att.displays().get(i));
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                att.displays().forEach(PartVisual::remove);
                throw failure;
            }
            doll.pendingVisuals.removeAll(att.displays());
        }

        PhysicsWorld pw = PhysicsWorld.get(source.getWorld());
        doll.world = pw;
        doll.ticksLeft = lifetimeTicks;
        if (pw != null) {
            pw.trackRagdoll(doll);
        }

        PhysicsBody body = byBone.get(BODY.bone());
        if (body == null) {
            // 몸통이 없으면 관절을 걸 수 없다. 파츠는 그대로 두고(각자 따로 굴러감) 끝낸다.
            return doll;
        }

        // 관절: 앵커는 각 파츠 본의 피벗 위치 그대로다. (목 = 머리 피벗, 어깨 = 팔 피벗, 고관절 = 다리 피벗)
        // 한계를 좁게 잡으면 축(월드 -Y) 기준 원뿔의 중심이 곧 "선 자세"라서
        // 전체가 뻣뻣한 인형이 되어 발로 딛고 서버린다(실측: 몸통 Y속도 0으로 정지).
        // 래그돌은 흐물거려야 하므로 넉넉하게 연다.
        doll.link(pw, HEAD.bone(), body, byBone.get(HEAD.bone()),  pivotByBone.get(HEAD.bone()),
                (float) HEAD_CONE, (float) HEAD_PLANE, (float) HEAD_TWIST);
        doll.link(pw, R_ARM.bone(), body, byBone.get(R_ARM.bone()), pivotByBone.get(R_ARM.bone()),
                (float) ARM_CONE, (float) ARM_PLANE, (float) ARM_TWIST);
        doll.link(pw, L_ARM.bone(), body, byBone.get(L_ARM.bone()), pivotByBone.get(L_ARM.bone()),
                (float) ARM_CONE, (float) ARM_PLANE, (float) ARM_TWIST);
        doll.link(pw, R_LEG.bone(), body, byBone.get(R_LEG.bone()), pivotByBone.get(R_LEG.bone()),
                (float) LEG_CONE, (float) LEG_PLANE, (float) LEG_TWIST);
        doll.link(pw, L_LEG.bone(), body, byBone.get(L_LEG.bone()), pivotByBone.get(L_LEG.bone()),
                (float) LEG_CONE, (float) LEG_PLANE, (float) LEG_TWIST);

        doll.bodyByBone.putAll(byBone);
        doll.spawnPivots.putAll(pivotByBone);

        // 관절 자리를 몸통 기준 로컬로 저장해둔다.
        PhysicsBody torso0 = byBone.get(BODY.bone());
        if (torso0 != null) {
            Quaternionf inv = torso0.getStartRotation().conjugate();
            Location tc = torso0.getLocation();
            for (Map.Entry<String, Location> e : pivotByBone.entrySet()) {
                if (e.getKey().equals(BODY.bone())) {
                    continue;
                }
                Vector3f v = new Vector3f(
                        (float) (e.getValue().getX() - tc.getX()),
                        (float) (e.getValue().getY() - tc.getY()),
                        (float) (e.getValue().getZ() - tc.getZ()));
                doll.woundOnTorso.put(e.getKey(), inv.transform(v));
            }
        }
        LAST = doll;

        if (impulse != null) {
            body.addImpulse(impulse);
        }
        return doll;
    }

    /**
     * 두 파츠를 원뿔+비틀림 제한이 걸린 관절로 묶는다.
     *
     * @param normalCone 원뿔 반각(도) - 관절이 꺾일 수 있는 주 범위
     * @param planeCone  평면 반각(도) - 옆으로 벌어지는 범위
     * @param twist      비틀림 한계(도)
     */
    private void link(PhysicsWorld pw, String bone, PhysicsBody a, PhysicsBody b, Location anchor,
                      float normalCone, float planeCone, float twist) {
        if (pw == null || a == null || b == null || anchor == null) {
            return;
        }
        SwingTwistConstraintSettings s = new SwingTwistConstraintSettings();
        s.setSpace(EConstraintSpace.WorldSpace);

        RVec3 at = new RVec3(anchor.getX(), anchor.getY(), anchor.getZ());
        s.setPosition1(at);
        s.setPosition2(at);

        // 팔다리는 아래로 뻗어있으니 비틀림 축을 -Y, 평면 축을 +X 로 잡는다.
        s.setTwistAxis1(new Vec3(0, -1, 0));
        s.setTwistAxis2(new Vec3(0, -1, 0));
        s.setPlaneAxis1(new Vec3(1, 0, 0));
        s.setPlaneAxis2(new Vec3(1, 0, 0));

        s.setNormalHalfConeAngle((float) Math.toRadians(normalCone));
        s.setPlaneHalfConeAngle((float) Math.toRadians(planeCone));
        s.setTwistMinAngle((float) Math.toRadians(-twist));
        s.setTwistMaxAngle((float) Math.toRadians(twist));

        TwoBodyConstraint c = s.create(a.getBody(), b.getBody());
        pw.addConstraint(c);
        constraints.add(c);
        jointByBone.put(bone, c);
    }

    /**
     * 본 하나를 독립 ItemDisplay 로 떼어낸다.
     *
     * <p>MEAPI.createReal 을 쓰지 않는 이유: 그쪽은 아이템을 구할 때
     * 블루프린트의 {@code getModelData().getSingleComposite()} 아니면 {@code BoneBehaviorTypes.ITEM}
     * 만 본다. 그런데 player.bbmodel 의 파츠는 <b>PLAYER_LIMB</b>(플레이어 스킨 팔다리)라
     * 둘 다 해당되지 않아 항상 빈손으로 돌아온다. 실제로 6개 파츠 전부 실패했다.
     *
     * <p>{@link ModelBone#getModel()} 은 그 본이 지금 렌더 중인 ItemStack 을 그대로 준다.
     * 플레이어 limb 이든 일반 아이템 본이든 이걸 쓰면 된다.
     */
    /**
     * 플레이어 스킨 팔다리(PLAYER_LIMB)는 <b>Transformation.translation.y</b>로 정점을 아래에 놓는다.
     *
     * <p>리소스팩의 core 셰이더(stable_player_display 방식, entity.vsh)가
     * 정점의 월드 Y 를 보고 부위를 판별한다:
     * <pre>
     *   #define SPACING 1024.0
     *   int partId = -int((wpos.y - MAXRANGE) / SPACING);   // Y 로 부위 결정
     *   wpos.y += SPACING * partId;                         // 화면 위치는 원래대로 복원
     *   UVout = origins[2 * (partId - 1) + hatLayer];       // 부위별 스킨 UV
     * </pre>
     * 즉 셰이더의 부위 선택은 CustomModelData 가 아니라 <b>변환 후 정점 Y 좌표</b>다.
     * 정상 Y 에 두면 partId=0 이 되어 전부 머리로 그려진다. (실제로 그 증상을 겪었다)
     *
     * <p>오프셋 값 자체는 ModelEngine 의 {@code PlayerLimb.Limb} enum 이 갖고 있다
     * ({@code defaultYOffset} / {@code slimYOffset}).
     *
     * @return 적용할 Y 오프셋(보통 음수). 플레이어 limb 이 아니면 0.
     */
    private static double limbYOffset(ModelBone mb, ItemStack item) {
        if (mb == null) {
            return 0;
        }
        try {
            var opt = mb.getBoneBehavior(com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes.PLAYER_LIMB);
            if (opt.isEmpty()) {
                return 0;
            }
            com.ticxo.modelengine.api.model.bone.type.PlayerLimb.Limb limb = opt.get().getLimbType();
            if (limb == null) {
                return 0;
            }
            // R4.1.1 PlayerLimb 의 CMD ID 는 floats[0]이다. strings 는 일반 본 variant,
            // colors[0]은 틴트이므로 슬림 판정에 쓰지 않는다.
            boolean slim = false;
            try {
                Float modelId = limbModelId(item);
                slim = modelId != null && modelId == limb.slimId;
            } catch (Throwable ignored) {
            }
            return slim ? limb.slimYOffset : limb.defaultYOffset;
        } catch (Throwable t) {
            that.getLogger().warning("[Ragdoll] limb 오프셋 조회 실패: " + t);
            return 0;
        }
    }

    /** 26.3 player_head item 의 range_dispatch 가 읽는 첫 CMD float. */
    private static Float limbModelId(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return null;
        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        return floats.isEmpty() ? null : floats.get(0);
    }

    /**
     * 부위를 몸통에서 잘라낸다. 관절만 끊으므로 파츠 자체는 그대로 남아 굴러다닌다.
     *
     * <p>충돌 그룹도 새로 발급한다. 래그돌 파츠끼리는 서로 안 부딪히게 묶어뒀는데,
     * 잘린 부위는 남남이 되어야 몸통을 통과해 지나가지 않는다.
     *
     * @param bone  head / right_arm / left_arm / right_leg / left_leg
     * @param power 잘리면서 튀어나가는 세기. 0 이면 그냥 떨어진다.
     * @return 실제로 잘렸으면 true. 이미 잘렸거나 없는 부위면 false.
     */
    public boolean dismember(String bone, double power) {
        if (!cutJoint(bone)) {
            return false;
        }
        PhysicsBody part = bodyByBone.get(bone);
        regroup(part, PhysicsWorld.nextRagdollGroupId());
        kick(part, power);
        return true;
    }

    /**
     * 허리에서 상하체를 가른다.
     *
     * <p>두 다리를 몸통에서 떼고 그 둘끼리는 고정 조인트로 붙여 한 덩어리로 만든다.
     * 관절만 끊으면 다리가 두 조각으로 흩어져서 하체로 안 보인다.
     *
     * <p>충돌 그룹은 두 다리에 <b>같은 번호</b>를 준다. 서로 다른 번호를 주면 골반에서
     * 딱 맞닿아 있는 두 다리가 그때부터 부딪히기 시작해서 덜덜 떤다.
     */
    public boolean splitAtWaist(double power) {
        boolean a = cutJoint(R_LEG.bone());
        boolean b = cutJoint(L_LEG.bone());
        if (!a && !b) {
            return false;
        }
        PhysicsBody rl = bodyByBone.get(R_LEG.bone());
        PhysicsBody ll = bodyByBone.get(L_LEG.bone());
        int gid = PhysicsWorld.nextRagdollGroupId();
        regroup(rl, gid);
        regroup(ll, gid);

        // 골반 = 두 다리 관절의 중점. 다리 본의 피벗이 곧 고관절 위치다.
        Location rp = spawnPivots.get(R_LEG.bone());
        Location lp = spawnPivots.get(L_LEG.bone());
        Location hip = null;
        if (rp != null && lp != null) {
            hip = new Location(rp.getWorld(),
                    (rp.getX() + lp.getX()) / 2,
                    (rp.getY() + lp.getY()) / 2,
                    (rp.getZ() + lp.getZ()) / 2);
        } else if (rl != null && !rl.isDead()) {
            hip = rl.getLocation();
        }
        weld(rl, ll, hip);
        // 붙어있는 한 덩어리라 절반씩 나눠 밀어야 세기가 맞는다.
        kick(rl, power * 0.5);
        kick(ll, power * 0.5);
        return true;
    }

    /** 관절만 끊는다. 충돌 그룹이나 충격은 건드리지 않는다. */
    private boolean cutJoint(String bone) {
        TwoBodyConstraint c = jointByBone.remove(bone);
        if (c == null || world == null) {
            return false;
        }
        try {
            world.getSystem().removeConstraint(c);
        } catch (Throwable t) {
            that.getLogger().warning("[Ragdoll] 관절 제거 실패(" + bone + "): " + t);
        }
        constraints.remove(c);
        return true;
    }

    /** 충돌 그룹 번호를 바꾼다. 번호가 다르면 필터가 남남으로 보고 서로 부딪힌다. */
    private void regroup(PhysicsBody part, int groupId) {
        if (part == null || part.isDead()) {
            return;
        }
        try {
            part.getBody().getCollisionGroup().setGroupId(groupId);
        } catch (Throwable ignored) {
        }
    }

    /** 몸통 반대쪽으로 밀어낸다. */
    private void kick(PhysicsBody part, double power) {
        if (part == null || part.isDead() || power <= 0) {
            return;
        }
        PhysicsBody torso = bodyByBone.get(BODY.bone());
        Vector dir;
        if (torso != null && !torso.isDead()) {
            dir = part.getLocation().toVector().subtract(torso.getLocation().toVector());
        } else {
            dir = new Vector(0, 1, 0);
        }
        if (dir.lengthSquared() < 1e-4) {
            dir = new Vector(0, 1, 0);
        }
        dir.normalize().setY(dir.getY() + 0.6);
        part.addImpulse(dir.normalize().multiply(power));
    }

    /**
     * 두 파츠를 한 덩어리로 묶되 약간 놀 수 있게 한다.
     *
     * <p>FixedConstraint 로 완전히 잠그면 상대 자세가 그대로 굳어서 두 다리가 통짜 블록처럼
     * 굴러다닌다. 좁은 한계의 SwingTwist 로 묶으면 붙어는 있으면서 살짝 흔들린다.
     *
     * @param anchor 관절 위치. 두 다리면 골반.
     */
    private void weld(PhysicsBody a, PhysicsBody b, Location anchor) {
        if (world == null || a == null || b == null || a.isDead() || b.isDead() || anchor == null) {
            return;
        }
        try {
            SwingTwistConstraintSettings s = new SwingTwistConstraintSettings();
            s.setSpace(EConstraintSpace.WorldSpace);
            RVec3 at = new RVec3(anchor.getX(), anchor.getY(), anchor.getZ());
            s.setPosition1(at);
            s.setPosition2(at);
            s.setTwistAxis1(new Vec3(0, -1, 0));
            s.setTwistAxis2(new Vec3(0, -1, 0));
            s.setPlaneAxis1(new Vec3(1, 0, 0));
            s.setPlaneAxis2(new Vec3(1, 0, 0));
            s.setNormalHalfConeAngle((float) Math.toRadians(WAIST_CONE));
            s.setPlaneHalfConeAngle((float) Math.toRadians(WAIST_PLANE));
            s.setTwistMinAngle((float) Math.toRadians(-WAIST_TWIST));
            s.setTwistMaxAngle((float) Math.toRadians(WAIST_TWIST));
            TwoBodyConstraint c = s.create(a.getBody(), b.getBody());
            world.addConstraint(c);
            constraints.add(c);
        } catch (Throwable t) {
            that.getLogger().warning("[Ragdoll] 허리 관절 생성 실패: " + t);
        }
    }

    /** 몸통을 뺀 모든 부위를 잘라낸다. */
    public int dismemberAll(double power) {
        int n = 0;
        for (String bone : new ArrayList<>(jointByBone.keySet())) {
            if (dismember(bone, power)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 파티클을 뿌릴 자리들. 잘린 관절마다 <b>양쪽</b>(떨어져 나간 파츠 쪽 + 몸통 쪽)을 준다.
     *
     * <p>모션별로 따로 계산할 필요가 없다. 끊긴 관절이 어디냐가 곧 답이라서,
     * 머리를 자르면 목 양쪽이, 다리를 자르면 골반과 몸통 하단이 저절로 나온다.
     *
     * <p>아무것도 안 잘렸으면 가슴 한 곳만 준다.
     */
    public List<Location> getWoundLocations() {
        List<Location> out = new ArrayList<>();
        PhysicsBody torso = bodyByBone.get(BODY.bone());
        for (Map.Entry<String, PhysicsBody> e : bodyByBone.entrySet()) {
            String bone = e.getKey();
            if (bone.equals(BODY.bone()) || jointByBone.containsKey(bone)) {
                continue;   // 몸통 자신이거나 아직 붙어있는 부위
            }
            PhysicsBody part = e.getValue();
            if (part != null && !part.isDead()) {
                out.add(part.getPivotLocation());
            }
            Vector3f local = woundOnTorso.get(bone);
            if (torso != null && !torso.isDead() && local != null) {
                out.add(torso.toWorld(local));
            }
        }
        if (out.isEmpty() && torso != null && !torso.isDead()) {
            out.add(torso.getLocation());
        }
        return out;
    }

    /** 아직 몸통에 붙어있는 부위 이름들. */
    public List<String> getAttachedParts() {
        return new ArrayList<>(jointByBone.keySet());
    }

    /**
     * 모든 파츠를 그 자리에 굳힌다. 장비·코스튬 정렬을 눈으로 맞출 때 쓴다.
     *
     * <p>파츠 각도를 전부 생성 시점의 yaw 로 통일한다. 그냥 굳히면 파츠마다 그 순간의
     * 애니메이션 각도가 남아서 팔·몸·머리가 따로 노는 것처럼 보여 정렬을 볼 수가 없다.
     */
    public void freeze() {
        for (PhysicsBody b : bodies) {
            b.setRotation(uprightRotation);
            b.freeze();
        }
    }

    /**
     * 몸통 파츠에 코스튬을 붙인다. ModelEngine 본이 없는 것에 쓴다.
     * (player.bbmodel 에는 costume_N 본이 없어서 본을 뗄 수가 없다)
     */
    public void attachCostume(ItemStack item) {
        PhysicsBody torso = bodyByBone.get(BODY.bone());
        if (item == null || item.getType().isAir() || torso == null || torso.isDead()) {
            return;
        }
        Location at = torso.getLocation();
        final ItemStack fi = item.clone();
        // 몸통 파츠의 생성 회전으로 만들어야 고유 회전이 단위가 되어 몸통을 그대로 따라간다.
        // 단위 회전으로 만들면 몸통이 굴러도 코스튬만 제자리를 보고 있게 된다.
        final Quaternionf headRot = torso.getStartRotation();
        float cs = (float) COSTUME_SCALE;
        PartVisual d = Visuals.create(at, fi, ItemDisplay.ItemDisplayTransform.HEAD,
                new Transformation(
                    new Vector3f(), headRot,
                    new Vector3f(cs, cs, cs), new Quaternionf()), true);
        try {
            torso.attachVisual(d, new Vector3f(costumeAnchor).add(
                (float) COSTUME_X, (float) COSTUME_Y, (float) COSTUME_Z), 0);
        } catch (RuntimeException | LinkageError failure) {
            d.remove();
            throw failure;
        }
    }

    /**
     * 렌더러에서 본을 찾는다. 렌더 맵이 비어 있으면 스폰 대기열도 본다.
     *
     * <p>getRendered() 는 "지금 실제로 클라이언트에 전송된" 본만 담는다. 모델이 막 붙었거나
     * 근처에 보는 사람이 없으면 이 맵이 통째로 빈다. 그러면 파츠 6개가 한꺼번에 실패해
     * "파츠 0개 (모델은 있는데 본을 못 떼어냄)" 가 된다 — 로그 실측으로 래그돌 386회 중 4회.
     * 아직 전송 전인 본은 spawnQueue 에 같은 DisplayBone 으로 들어있어 변환과 BoneData 를
     * 그대로 쓸 수 있으므로, 렌더 맵이 비었을 때의 대체 경로로 쓴다.
     */
    private static DisplayBone findBone(ActiveModel am, String boneId) {
        DisplayRendererImpl renderer = (DisplayRendererImpl) am.getModelRenderer();
        DisplayBone db = pickBone(renderer.getRendered(), boneId);
        if (db == null) {
            db = pickBone(renderer.getSpawnQueue(), boneId);
        }
        return db;
    }

    private static DisplayBone pickBone(Map<String, DisplayBone> map, String boneId) {
        if (map == null) {
            return null;
        }
        for (DisplayBone db : map.values()) {
            if (db != null && boneId.equals(db.getBoneId())) {
                return db;
            }
        }
        return null;
    }

    private static Detached detachBone(Entity source, String modelId, String boneId, Location at) {
        return detachBone(source, modelId, boneId, at, true);
    }

    private static Detached detachBone(Entity source, String modelId, String boneId, Location at, boolean packet) {
        ActiveModel am = MEAPI.getActiveModel(source, modelId);
        if (am == null) {
            that.getLogger().warning("[Ragdoll] ActiveModel 없음: " + modelId);
            return new Detached(List.of(), 0, null);
        }

        DisplayBone target;
        try {
            target = findBone(am, boneId);
        } catch (Throwable t) {
            that.getLogger().warning("[Ragdoll] 렌더러 접근 실패(" + boneId + "): " + t);
            return new Detached(List.of(), 0, null);
        }
        if (target == null) {
            that.getLogger().warning("[Ragdoll] DisplayBone 없음(렌더·스폰대기 모두): " + boneId);
            return new Detached(List.of(), 0, null);
        }

        // DisplayBone 의 leftRotation 은 본 로컬이 아니라 <b>완성된 월드 회전</b>이다.
        // 팔다리 본이 전부 같은 순수 yaw 값을 갖고 있고(모델이 보는 방향), righthand 는
        // 거기에 검을 눕히는 X축 90도가 더 곱해져 있는 것으로 실측 확인했다.
        // 그래서 엔티티 yaw 를 따로 합성하면 이중 적용이 된다. 그대로 쓴다.
        final Quaternionf rot = new Quaternionf()
                .rotateY((float) Math.toRadians(YAW_OFFSET))
                .mul(new Quaternionf(target.getLeftRotation().get()));
        final Vector3f scale = new Vector3f(target.getScale().get());
        final ItemDisplay.ItemDisplayTransform mode = target.getDisplay().get();

        // Display 변환은 translation + leftRotation * scale * rightRotation 이다.
        // 26.3 ModelEngine 의 SVD 분해 결과도 rightRotation 에 실린다.
        // 손에 든 아이템을 눕히는 회전은 ModelEngine 이 rightRotation 쪽에 넣는다.
        // 이걸 빼먹으면 검이 손에 들린 각도가 아니라 그냥 수직으로 선다.
        final Quaternionf rightRot = new Quaternionf(target.getRightRotation().get());

        if (DEBUG_ITEMS) {
            that.getLogger().info("[Ragdoll] " + boneId
                    + " pos=" + target.getPosition().get()
                    + " L=" + target.getLeftRotation().get()
                    + " R=" + rightRot
                    + " scale=" + scale + " display=" + mode);
        }

        // 실제 렌더 중인 아이템은 BoneData 안에 들어있다.
        // (ModelBone.getModel() 은 PLAYER_LIMB 에서 비어있어서 못 쓴다 - 실측으로 확인)
        ModelBone mb0 = am.getBone(boneId).orElse(null);
        List<PartVisual> out = new ArrayList<>();
        List<String> itemLabels = new ArrayList<>();
        double yOff = 0;
        try {
            for (DisplayBone.BoneData bd : target.getModel().values()) {
                ItemStack item = bd.getModel().get();
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                // 플레이어 limb 은 셰이더가 정점의 Y 로 부위를 판별한다.
                //
                // 엔티티 자체를 Y-5120 에 놓으면 월드 높이 밖이라 서버가 클라에 전송조차 하지 않는다.
                // ModelEngine 은 PlayerLimbImpl.modifyTransform() 에서 보듯 엔티티 위치가 아니라
                // Transformation 을 건드린다. 셰이더는 최종 정점 좌표만 보므로 translation.y 로 내리면
                // 정점만 내려가고 엔티티는 정상 위치에 남아 추적/전송된다.
                yOff = limbYOffset(mb0, item);
                // 파츠가 전부 같은 모양으로 보이는 문제를 잡기 위한 진단.
                // 플레이어 limb 은 아이템 종류가 아니라 CustomModelData(Limb.defaultId/slimId)로 부위를 구분한다.
                if (DEBUG_ITEMS) {
                    Float cmd = null;
                    try {
                        cmd = limbModelId(item);
                    } catch (Throwable ignored) {
                    }
                    that.getLogger().info("[Ragdoll] " + boneId + " slot=" + bd.getId()
                            + " item=" + item.getType() + " cmd=" + cmd + " hash=" + bd.getModelHash());
                }
                // item_model, CMD floats/strings/colors 및 스킨 컴포넌트를 통째로 보존한다.
                final ItemStack finalItem = item.clone();
                itemLabels.add(itemLabel(finalItem));
                final Vector3f baseTranslation = new Vector3f(0f, (float) yOff, 0f);
                // MEAPI.getBoneLocation 이 월드 위치를 이미 반영했다. limb 오프셋만 담는다.
                // 두 렌더러 모두 컬링 폭/높이 0, view range 3을 사용한다.
                PartVisual visual = Visuals.create(at, finalItem, mode,
                        new Transformation(baseTranslation, rot, scale, rightRot), packet);
                out.add(visual);
                if (DEBUG_ITEMS && !visual.getDisplays().isEmpty()) {
                    ItemDisplay spawned = (ItemDisplay) visual.getDisplays().get(0);
                    // 스폰된 엔티티가 실제로 들고 있는 값을 다시 읽는다.
                    // 여기서 cmd 가 사라지면 클라는 fallback(template_skull = 해골 머리)으로 렌더한다.
                    Float after = null;
                    try {
                        after = limbModelId(spawned.getItemStack());
                    } catch (Throwable ignored) {
                    }
                    that.getLogger().info("[Ragdoll] " + boneId + " 스폰후 item="
                            + spawned.getItemStack().getType() + " cmd=" + after
                            + " transform=" + spawned.getItemDisplayTransform()
                            + " yOff=" + yOff
                            + " entityY=" + String.format("%.1f", spawned.getLocation().getY())
                            + " transY=" + spawned.getTransformation().getTranslation().y);
                    // 컴포넌트 전체를 찍는다. custom_model_data 말고 item_model 같은 게 걸려 있으면
                    // 팩의 player_head.json 디스패치가 아예 안 쓰이므로 여기서 드러난다.

                }
            }
        } catch (RuntimeException | LinkageError failure) {
            out.forEach(PartVisual::remove);
            throw failure;
        }
        // A freshly generated player host can have an empty renderer BoneData entry when no real
        // viewer exists. The ITEM behavior is still the authoritative item source in that state.
        if (out.isEmpty() && mb0 != null) {
            try {
                var held=mb0.getBoneBehavior(com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes.ITEM);
                if(held.isPresent()){
                    ItemStack item=held.get().getItem();
                    if(item!=null&&!item.getType().isAir()){
                        ItemStack finalItem=item.clone();itemLabels.add(itemLabel(finalItem));
                        out.add(Visuals.create(at,finalItem,mode,
                                new Transformation(new Vector3f(),rot,scale,rightRot),packet));
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                out.forEach(PartVisual::remove);throw failure;
            }
        }
        // ITEM behavior providers can also report AIR until a real viewer has caused ModelEngine
        // to build the display snapshot. The host equipment is the same item assigned by setItem,
        // so use it only as the final item-source fallback while retaining ME's exact transform.
        if (out.isEmpty()) {
            ItemStack item=equipmentItem(source,boneId);
            if(item!=null&&!item.getType().isAir()){
                ItemStack finalItem=item.clone();itemLabels.add(itemLabel(finalItem));
                out.add(Visuals.create(at,finalItem,mode,
                        new Transformation(new Vector3f(),rot,scale,rightRot),packet));
            }
        }
        if (out.isEmpty()) {
            that.getLogger().warning("[Ragdoll] 렌더 아이템이 하나도 없음: " + boneId
                    + " (BoneData " + target.getModel().size() + "개"
                    + (mb0 == null ? "" : ", behaviors=" + mb0.getImmutableBoneBehaviors().size()) + ")");
        }
        Vector3f anchor=new Vector3f(((DisplayRendererImpl)am.getModelRenderer()).getPivot().getPosition().get());
        SourceTransform sourceTransform=new SourceTransform(anchor,new Vector3f(target.getPosition().get()),
                new Quaternionf(target.getLeftRotation().get()),new Vector3f(target.getScale().get()),
                new Quaternionf(target.getRightRotation().get()),String.valueOf(mode),List.copyOf(itemLabels));
        return new Detached(out, yOff, sourceTransform);
    }

    private static ItemStack equipmentItem(Entity source,String boneId) {
        if(!(source instanceof org.bukkit.entity.LivingEntity living)||living.getEquipment()==null)return null;
        return switch(boneId.toLowerCase(Locale.ROOT)){
            case "helmet" -> living.getEquipment().getHelmet();
            case "righthand" -> living.getEquipment().getItemInMainHand();
            case "lefthand" -> living.getEquipment().getItemInOffHand();
            default -> null;
        };
    }

    private static String itemLabel(ItemStack item) {
        String model="none";
        try { if(item.hasItemMeta()&&item.getItemMeta().getItemModel()!=null) model=item.getItemMeta().getItemModel().toString(); }
        catch(Throwable ignored) {}
        return item.getType()+":"+model;
    }

    private static void recordTransformAudit(String bone,SourceTransform live,String item,PartVisual visual) {
        Transformation rag=visual.getTransformation();
        Location at=visual.getLocation();
        Vector3f liveWorld=new Vector3f(live.anchor()).add(live.translation());
        Vector3f ragWorld=new Vector3f((float)at.getX(),(float)at.getY(),(float)at.getZ()).add(rag.getTranslation());
        double position=new Vector3f(liveWorld).sub(ragWorld).length();
        double left=angle(live.left(),rag.getLeftRotation());
        double scale=new Vector3f(live.scale()).sub(rag.getScale()).length();
        double right=angle(live.right(),rag.getRightRotation());
        String line=String.format(Locale.ROOT,
                "bone=%s item=%s render=%s display=%s liveT=%s liveL=%s liveS=%s liveR=%s ragT=%s ragL=%s ragS=%s ragR=%s worldPosError=%.8f leftDegError=%.8f scaleError=%.8f rightDegError=%.8f",
                bone,item,visual.isPacket()?"PACKET":"ENTITY",live.display(),
                vec(live.translation()),quat(live.left()),vec(live.scale()),quat(live.right()),
                vec(rag.getTranslation()),quat(rag.getLeftRotation()),vec(rag.getScale()),quat(rag.getRightRotation()),
                position,left,scale,right);
        synchronized(TRANSFORM_AUDITS){TRANSFORM_AUDITS.add(line);}
    }

    private static double angle(Quaternionf a,Quaternionf b) {
        double dot=Math.abs(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w);
        return Math.toDegrees(2*Math.acos(Math.min(1,Math.max(-1,dot))));
    }
    private static String vec(Vector3f v){return String.format(Locale.ROOT,"%.8f,%.8f,%.8f",v.x,v.y,v.z);}
    private static String quat(Quaternionf q){return String.format(Locale.ROOT,"%.8f,%.8f,%.8f,%.8f",q.x,q.y,q.z,q.w);}
    public static void setTransformAudit(boolean enabled){AUDIT_TRANSFORMS=enabled;if(enabled)synchronized(TRANSFORM_AUDITS){TRANSFORM_AUDITS.clear();}}
    public static List<String> drainTransformAudits(){synchronized(TRANSFORM_AUDITS){List<String> out=List.copyOf(TRANSFORM_AUDITS);TRANSFORM_AUDITS.clear();return out;}}

    // ------------------------------------------------------------------

    /**
     * 렌더 변형 조합을 늘어놓고 눈으로 비교하기 위한 테스트.
     *
     * 몸통(torso) 본의 아이템을 그대로 쓰되 Transformation / ItemDisplayTransform 만 바꿔서
     * 앞에 일렬로 세운다. 어느 것이 몸통 모양으로 보이는지 확인하면 원인이 확정된다.
     * 물리는 붙이지 않는다.
     */
    public static int limbTest(Entity source, String modelId) {
        ActiveModel am = MEAPI.getActiveModel(source, modelId);
        if (am == null) {
            return 0;
        }
        DisplayBone target = null;
        try {
            target = findBone(am, BODY.bone());
        } catch (Throwable ignored) {
        }
        if (target == null) {
            return 0;
        }
        ItemStack item = null;
        for (DisplayBone.BoneData bd : target.getModel().values()) {
            ItemStack it = bd.getModel().get();
            if (it != null && !it.getType().isAir()) {
                item = it.clone();
                break;
            }
        }
        if (item == null) {
            return 0;
        }

        Vector3f boneScale = new Vector3f(target.getScale().get());
        Vector3f bonePos = new Vector3f(target.getPosition().get());
        Quaternionf boneRot = new Quaternionf(target.getLeftRotation().get());

        // (라벨, transform, scale, translation, rotation)
        Object[][] variants = {
                {"A",   ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND, boneScale,               new Vector3f(),  boneRot},
                {"B",    ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND, new Vector3f(1, 1, 1),   new Vector3f(),  new Quaternionf()},
                {"C",  ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND, boneScale,               bonePos,         boneRot},
                {"D",             ItemDisplay.ItemDisplayTransform.NONE,                  new Vector3f(1, 1, 1),   new Vector3f(),  new Quaternionf()},
                {"E",             ItemDisplay.ItemDisplayTransform.HEAD,                  new Vector3f(1, 1, 1),   new Vector3f(),  new Quaternionf()},
                {"F",            ItemDisplay.ItemDisplayTransform.FIXED,                 new Vector3f(1, 1, 1),   new Vector3f(),  new Quaternionf()},
        };

        // 이전 표본 정리. 겹쳐 놓으면 라벨과 개체를 짝지을 수 없다.
        for (Entity e : source.getWorld().getNearbyEntities(source.getLocation(), 40, 40, 40)) {
            if (e instanceof ItemDisplay && e.getScoreboardTags().contains("gcb_limbtest")) {
                e.remove();
            }
        }

        // 보는 방향 앞쪽에, 시선 기준 좌우로 넓게(3칸) 벌려 세운다.
        Location eye = source.getLocation();
        org.bukkit.util.Vector fwd = eye.getDirection().setY(0).normalize();
        org.bukkit.util.Vector right = new org.bukkit.util.Vector(-fwd.getZ(), 0, fwd.getX());
        Location base = eye.clone().add(fwd.clone().multiply(5)).add(right.clone().multiply(-7.5));
        base.setY(eye.getY() + 1.0);

        int n = 0;
        for (Object[] v : variants) {
            final ItemStack fi = item.clone();
            final ItemDisplay.ItemDisplayTransform mode = (ItemDisplay.ItemDisplayTransform) v[1];
            final Vector3f sc = (Vector3f) v[2];
            final Vector3f tr = (Vector3f) v[3];
            final Quaternionf ro = (Quaternionf) v[4];
            final String label = (String) v[0];
            Location at = base.clone().add(right.clone().multiply(n * 3.0));
            at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(fi);
                d.setItemDisplayTransform(mode);
                d.setTransformation(new Transformation(tr, ro, sc, new Quaternionf()));
                d.setCustomNameVisible(true);
                d.customName(net.kyori.adventure.text.Component.text(label));
                d.addScoreboardTag("gcb_limbtest");
            });
            that.getLogger().info("[LimbTest] " + label + " @ " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
            n++;
        }
        that.getLogger().info("[LimbTest] 본 scale=" + boneScale + " pos=" + bonePos);
        return n;
    }

    /**
     * 물리를 붙이지 않고 파츠만 제자리에 떼어 놓는다.
     * 렌더 문제인지 물리(매 틱 Transformation 덮어쓰기) 문제인지 가르기 위한 것.
     */
    public static int detachOnly(Entity source, String modelId) {
        ActiveModel am = MEAPI.getActiveModel(source, modelId);
        if (am == null) {
            that.getLogger().warning("[Detach] ActiveModel 없음: " + modelId);
            return 0;
        }
        // 모델 종류를 안 가리고 렌더 중인 본을 전부 떼어본다.
        // player 모델(PLAYER_LIMB)이 아니라 일반 아이템 모델 본이 제대로 나오는지 보는 게 목적.
        List<String> boneIds = new ArrayList<>();
        try {
            DisplayRendererImpl renderer = (DisplayRendererImpl) am.getModelRenderer();
            // 렌더 맵이 비었을 수 있으므로 스폰 대기열도 합쳐서 본다. 중복은 건너뛴다.
            for (Map<String, DisplayBone> m : List.of(renderer.getRendered(), renderer.getSpawnQueue())) {
                for (DisplayBone db : m.values()) {
                    if (db != null && !boneIds.contains(db.getBoneId())) {
                        boneIds.add(db.getBoneId());
                    }
                }
            }
        } catch (Throwable t) {
            that.getLogger().warning("[Detach] 렌더러 접근 실패: " + t);
            return 0;
        }

        int n = 0;
        for (String boneId : boneIds) {
            Location pivot = MEAPI.getBoneLocation(source, modelId, boneId);
            if (pivot == null) continue;
            List<Display> parts = detachBone(source, modelId, boneId, pivot, false).displays().stream()
                    .flatMap(visual -> visual.getDisplays().stream()).toList();
            for (Display d : parts) {
                d.addScoreboardTag("gcb_limbtest");
                d.setCustomNameVisible(true);
                d.customName(net.kyori.adventure.text.Component.text(boneId));
                n++;
            }
        }
        that.getLogger().info("[Detach] " + modelId + " 본 " + boneIds.size() + "개 중 " + n + "개 분리");
        return n;
    }

    public List<PhysicsBody> getBodies() { return bodies; }

    /** 몸통 파츠. 파티클 등을 붙일 기준으로 쓴다. 없으면 첫 파츠. */
    public PhysicsBody getTorso() {
        return bodies.isEmpty() ? null : bodies.get(0);
    }

    /** 몸통(가슴) 현재 위치. 래그돌이 이미 사라졌으면 null. */
    public org.bukkit.Location getChestLocation() {
        PhysicsBody b = getTorso();
        return (b == null || b.isDead()) ? null : b.getLocation();
    }

    public int size() { return bodies.size(); }

    /** 몸통에 충격을 준다. 없으면 첫 파츠에 준다. */
    public void push(Vector impulse) {
        if (bodies.isEmpty()) return;
        bodies.get(0).addImpulse(impulse);
    }

    /** 수명 관리. PhysicsWorld 티커가 부른다. @return 소멸했으면 true */
    boolean tickLifetime() {
        if (bodies.isEmpty()) {
            return true;
        }
        if (ticksLeft > 0 && --ticksLeft == 0) {
            destroy();
            return true;
        }
        return false;
    }

    /**
     * 관절을 먼저 걷어낸 뒤 파츠를 지운다. 순서가 뒤바뀌면 Jolt 가 죽는다.
     */
    public void destroy() {
        if (world != null) {
            for (TwoBodyConstraint c : constraints) {
                try {
                    world.getSystem().removeConstraint(c);
                } catch (Throwable ignored) {
                }
            }
        }
        constraints.clear();

        for (PhysicsBody b : new ArrayList<>(bodies)) {
            b.destroy();
        }
        bodies.clear();
    }
}
