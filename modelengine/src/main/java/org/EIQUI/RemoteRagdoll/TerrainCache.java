package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 마크 블록 지형을 Jolt 정적 충돌체로 올려주는 캐시.
 *
 * 월드 전체를 올릴 수는 없으므로 <b>물리 객체 주변 블록만</b> 올리고, 물체가 멀어지면 내린다.
 * 블록 하나당 정적 바디 하나를 만들고 {@code (x,y,z) -> bodyId} 로 들고 있다가 필요 없어지면 제거한다.
 *
 * 매 틱 전 범위를 다시 계산하면 낭비라, 물체가 <b>다른 블록으로 이동했을 때만</b> 갱신한다.
 * 가만히 누워있는 래그돌은 지형 갱신 비용이 0 이다.
 */
final class TerrainCache {

    /** 물체 주변 몇 블록까지 올릴지. 2 면 5x5x5. */
    private static final int RADIUS = 2;

    /**
     * 빠른 물체는 한 틱에 RADIUS 를 훌쩍 넘어간다. 그러면 진행 방향 앞쪽 바닥이
     * 애초에 Jolt 에 올라가 있지도 않아서, CCD(LinearCast) 를 켜도 통과해 버린다.
     * 그래서 다음 틱 예상 위치까지 훑어서 그 구간의 바닥도 같이 올린다.
     * 상한을 두는 건 아주 빠른 파츠 하나가 수천 블록을 올리는 걸 막기 위해서다.
     */
    private static final int MAX_SWEEP = 6;
    /** 이번 틱에 이만큼 넘게 추가했으면 브로드페이즈를 다시 최적화한다. */
    private static final int OPTIMIZE_THRESHOLD = 24;

    private final PhysicsWorld owner;
    private final Map<Long, Integer> loaded = new HashMap<>();
    /** 반쪽크기별 BoxShape 재사용. 꽉 찬 블록이 대부분이라 보통 1~2개로 끝난다. */
    private final Map<Long, BoxShape> shapeCache = new HashMap<>();
    /** 물체별 마지막 블록 좌표. 안 움직였으면 갱신을 건너뛴다. */
    private final Map<Integer, Long> lastBlock = new HashMap<>();

    TerrainCache(PhysicsWorld owner) {
        this.owner = owner;
    }

    private static long key(int x, int y, int z) {
        // y 는 -2048..2047 로 충분 (마크 높이 한계 밖)
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }

    /**
     * 지형을 올릴 대상은 <b>바디 id</b> 로 받는다.
     *
     * <p>PhysicsBody 목록만 받으면 액체 방울처럼 PhysicsBody 를 안 만드는 것들이 빠진다.
     * 그러면 방울 주변에 충돌체가 안 올라가서 지형을 그대로 통과한다.
     */
    void update(List<Integer> bodyIds) {
        World w = owner.getWorld();
        BodyInterface bi = owner.getBodies();

        // 1) 이번에 필요한 블록 좌표 모으기 + 아무도 안 움직였으면 조기 종료
        boolean moved = false;
        // {minX,minY,minZ,maxX,maxY,maxZ} - 현재 위치와 다음 틱 예상 위치를 모두 감싼 박스
        List<int[]> centers = new ArrayList<>(bodyIds.size());
        for (int id : bodyIds) {
            RVec3 p = bi.getPosition(id);
            int bx = (int) Math.floor(p.xx());
            int by = (int) Math.floor(p.yy());
            int bz = (int) Math.floor(p.zz());

            // 한 틱(1/20초) 뒤 위치까지 스윕한다.
            Vec3 v = bi.getLinearVelocity(id);
            int nx = (int) Math.floor(p.xx() + clampSweep(v.getX() / 20.0));
            int ny = (int) Math.floor(p.yy() + clampSweep(v.getY() / 20.0));
            int nz = (int) Math.floor(p.zz() + clampSweep(v.getZ() / 20.0));

            centers.add(new int[]{
                    Math.min(bx, nx) - RADIUS, Math.min(by, ny) - RADIUS, Math.min(bz, nz) - RADIUS,
                    Math.max(bx, nx) + RADIUS, Math.max(by, ny) + RADIUS, Math.max(bz, nz) + RADIUS});
            long k = key(bx, by, bz);
            Long prev = lastBlock.put(id, k);
            if (prev == null || prev != k) {
                moved = true;
            }
        }
        // 사라진 바디의 항목은 걷어낸다. 안 그러면 서버가 도는 내내 계속 쌓인다.
        if (lastBlock.size() > bodyIds.size()) {
            lastBlock.keySet().retainAll(new HashSet<>(bodyIds));
        }
        if (!moved && !loaded.isEmpty()) {
            return;
        }

        Set<Long> need = new HashSet<>();
        for (int[] c : centers) {
            for (int x = c[0]; x <= c[3]; x++) {
                for (int y = c[1]; y <= c[4]; y++) {
                    for (int z = c[2]; z <= c[5]; z++) {
                        need.add(key(x, y, z));
                    }
                }
            }
        }

        // 2) 더 이상 필요 없는 정적 바디 제거
        loaded.entrySet().removeIf(e -> {
            if (need.contains(e.getKey())) {
                return false;
            }
            owner.remove(e.getValue());
            return true;
        });

        // 3) 새로 필요한 블록 추가
        int added = 0;
        for (int[] c : centers) {
            for (int x = c[0]; x <= c[3]; x++) {
                for (int y = c[1]; y <= c[4]; y++) {
                    for (int z = c[2]; z <= c[5]; z++) {
                        long k = key(x, y, z);
                        if (loaded.containsKey(k)) continue;
                        if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
                        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

                        Block block = w.getBlockAt(x, y, z);
                        if (block.isPassable() || block.isEmpty()) continue;

                        BoundingBox bb = block.getBoundingBox();
                        if (bb.getVolume() <= 0) continue;

                        int id = addStatic(bi, bb);
                        // Jolt 의 실패값은 0 이 아니라 cInvalidBodyId(-1) 다.
                        // 0 으로 비교하면 실패한 -1 을 정상 id 로 넣어버리는데, 그러면
                        // 그 블록은 "이미 올림" 취급이라 다시 시도하지 않아서 바닥에 구멍이 남는다.
                        if (id != com.github.stephengold.joltjni.Jolt.cInvalidBodyId) {
                            loaded.put(k, id);
                            added++;
                        }
                    }
                }
            }
        }
        if (added > OPTIMIZE_THRESHOLD) {
            owner.getSystem().optimizeBroadPhase();
        }
    }

    private int addStatic(BodyInterface bi, BoundingBox bb) {
        float hx = (float) (bb.getWidthX() / 2.0);
        float hy = (float) (bb.getHeight() / 2.0);
        float hz = (float) (bb.getWidthZ() / 2.0);
        if (hx <= 0 || hy <= 0 || hz <= 0) return com.github.stephengold.joltjni.Jolt.cInvalidBodyId;

        BoxShape shape = shapeCache.computeIfAbsent(shapeKey(hx, hy, hz),
                ignored -> new BoxShape(new Vec3(hx, hy, hz), 0.01f));

        BodyCreationSettings bcs = new BodyCreationSettings(
                shape,
                new RVec3(bb.getCenterX(), bb.getCenterY(), bb.getCenterZ()),
                new Quat(),
                EMotionType.Static,
                PhysicsWorld.LAYER_STATIC);
        bcs.setFriction(0.9f);
        bcs.setRestitution(0.0f);
        return bi.createAndAddBody(bcs, EActivation.DontActivate);
    }

    /** 스윕 거리를 MAX_SWEEP 안으로 자른다. */
    private static double clampSweep(double d) {
        if (d > MAX_SWEEP) return MAX_SWEEP;
        if (d < -MAX_SWEEP) return -MAX_SWEEP;
        return d;
    }

    /** 0.0625(1/16) 단위로 뭉쳐서 shape 재사용률을 올린다. */
    private static long shapeKey(float hx, float hy, float hz) {
        long a = Math.round(hx * 16.0);
        long b = Math.round(hy * 16.0);
        long c = Math.round(hz * 16.0);
        return (a << 42) | (b << 21) | c;
    }

    void clear() {
        if (loaded.isEmpty() && lastBlock.isEmpty()) {
            return;
        }
        for (int id : loaded.values()) {
            owner.remove(id);
        }
        loaded.clear();
        lastBlock.clear();
    }

    int getLoadedCount() {
        return loaded.size();
    }
}
