# 26.3 ModelEngine · 플레이어 limb 렌더 조사

2026-09-26에 소스와 바이트코드를 대조했다. 이 문서는 렌더 포팅의 정적 검증 근거이며, 인게임 시각 검증 결과는 아니다. 실제 서버 검증 여부와 배포 절차는 `../PORTING.md`를 따른다.

## 조사 기준

- 원본: 로컬 GCBAPI `b7cf3de7` 작업트리에 있던 미커밋 `physics/Ragdoll.java`, `physics/PhysicsBody.java`. 원본 파일은 수정하지 않았다.
- 26.3 GCBAPI: `origin/main`의 `d67b72e4cf4b55ae1b0d9986b0d484599593e6f1`.
- 빌드·버전 변경 근거: GCBAPI 커밋 `2b269f6c8f9eda9cfa46b800d06a9505159520e1`.
- createReal 변경 근거: GCBAPI 커밋 `ede9854e3ed64c67639b5735ab63586d8ed2857e`의 메시지와 `src/main/java/org/EIQUI/GCBAPI/modelengine/MEAPI.java` diff.
- 리소스팩: [GCB-resourcepack main](https://github.com/eiqq/GCB-resourcepack/tree/main)의 `ead3d159d168f6c1b68950fa630a550ca007e92f`. 로컬 `legal14` 체크아웃은 유지하고 `git show origin/main:<경로>`로 읽었다.
- ModelEngine: 26.3 GCBAPI의 `origin/main:libs/ModelEngine-R4.1.1.jar`에서 추출한 `libs/ModelEngine-R4.1.1.jar`.
- 위 ModelEngine jar의 SHA-256: `065DA77F6CC93C7C75A64D6864BD7E763977348DD97AC96EF245C5F140D0C3DB`.
- 바이트코드 도구: 지정된 Temurin 25.0.3의 `bin/javap.exe -p -c`, `bin/jar.exe tf`.

## 본 아이템과 Custom Model Data

`com.ticxo.modelengine.core.model.bone.behavior.PlayerLimbImpl.setTexture(PlayerProfile)`는 다음 순서로 플레이어 limb 아이템을 만든다.

1. `Material.PLAYER_HEAD` 아이템에 스킨 프로필을 설정한다.
2. 스킨 종류에 따라 `Limb.defaultId` 또는 `Limb.slimId`를 골라 `ItemMeta.setCustomModelData(Integer)`를 호출한다.
3. `NMSHandler.applyPlayerHeadItemModel(ItemStack)`을 호출한다.
4. 아이템을 `ModelBone.setModel(ItemStack)`로 등록한다.

`com.ticxo.modelengine.v26_3.NMSHandler_v26_3.applyPlayerHeadItemModel`의 lambda는 `ItemMeta.setItemModel(getPlayerHeadModelKey())`를 호출한다. 리소스팩의 `assets/modelengine/items/player_head.json`은 `minecraft:range_dispatch`의 `minecraft:custom_model_data` 속성으로 모델을 선택한다. 별도 index가 없으므로 첫 번째 float가 선택값이다.

반면 일반 본의 `NMSHandler_v26_3.lambda$createStack$0`은 `item_model`, CMD `colors[0]`, 선택적 CMD `strings[0]`을 설정한다. `strings[0]`은 일반 본 variant이며 limb의 슬림 판정값이 아니다. `lambda$colorStack$0`도 CMD component의 colors 목록만 바꾼다.

따라서 새 `Ragdoll.limbModelId`는 `getCustomModelDataComponent().getFloats()`의 첫 값을 읽는다. 빈 목록은 값 없음으로 처리한다. `limbYOffset`은 이 값과 기존 `limb.slimId`를 비교하며, 기존 예외 시 일반 오프셋으로 돌아가는 동작을 유지한다. 진단 로그도 같은 component 값을 읽는다.

`detachBone`은 `DisplayBone.BoneData.getModel().get()`의 아이템을 통째로 `clone()`한다. 이 경로를 유지하여 item_model, CMD floats/strings/colors, 스킨 프로필을 보존한다. 특정 Material이나 레거시 CMD 정수로 아이템을 재구성하지 않는다.

## entity.vsh와 Y 오프셋

정본 셰이더 경로는 `assets/minecraft/shaders/core/entity.vsh`다. 기존 주석의 `rendertype_entity_translucent.vsh`를 이 경로에 맞춰 수정했다.

확인한 셰이더 계산은 다음과 같다.

```glsl
#define SPACING 1024.0
#define MAXRANGE (0.5 * SPACING)
int partId = -int((Position.y - MAXRANGE) / SPACING);
if (partId != 0) {
    partId -= 1;
    bool slim = partId == 2 || partId == 3;
    int partIdMod = partId % 7;
    // origins[2 * partIdMod + outerLayer]에서 UV 시작점을 선택
    // wpos.y += SPACING * (partId + 1)로 렌더 위치 복원
}
```

다음 표의 `partId`는 `partId -= 1` 이후 인덱스다. 머리는 최초 partId가 0이므로 재매핑 분기에 들어가지 않는다.

| 부위 | CMD floats[0] | Limb Y 오프셋 | 셰이더 partId | origins 기본 / 외부 레이어 |
|---|---:|---:|---:|---|
| 머리 | 1 | 0 | 해당 없음 | 플레이어 머리 기본 UV |
| 오른팔 | 2 | -1024 | 0 | (40,16) / (40,32) |
| 왼팔 | 3 | -2048 | 1 | (32,48) / (48,48) |
| 슬림 오른팔 | 7 | -3072 | 2 | (40,16) / (40,32) |
| 슬림 왼팔 | 8 | -4096 | 3 | (32,48) / (48,48) |
| 몸통 | 4 | -5120 | 4 | (16,16) / (16,32) |
| 오른다리 | 5 | -6144 | 5 | (0,16) / (0,32) |
| 왼다리 | 6 | -7168 | 6 | (16,48) / (0,48) |

`com.ticxo.modelengine.api.model.bone.type.PlayerLimb$Limb`의 생성자와 정적 초기화 바이트코드에서 같은 defaultId/slimId 및 Y 오프셋을 확인했다. 생성자는 오프셋 인자에 -1024를 곱한다. 팔 이외의 부위는 default/slim 값이 같다.

다음 두 모델 파일도 정본에서 확인했다.

| 파일 | thirdperson_righthand translation | scale | rotation |
|---|---|---|---|
| `assets/modelengine/models/custom/entities/player/slim_right.json` | [0.46875, 1.875, 0] | [0.3515625, 1.40625, 0.46875] | [0, 180, 0] |
| `assets/modelengine/models/custom/entities/player/slim_left.json` | [-0.46875, 1.875, 0] | [0.3515625, 1.40625, 0.46875] | [0, 180, 0] |

이 모델의 rotation과 ME의 렌더 회전을 이미 복사하므로 별도 π 보정을 추가하지 않는다. 엔티티 위치는 정상 높이에 두고, 셰이더 부위 선택용 Y 오프셋은 `Transformation.translation.y`에만 넣는다.

## leftRotation · scale · rightRotation

`ede9854e`의 `MEAPI.createReal` 수정은 26.3에서 `ServerInfo.VERSION_NUMBER`를 3으로 잘못 읽어 1.19용 180° 보정이 적용되는 문제를 제거했다. 또한 SVD 분해 결과를 유지하기 위해 rightRotation까지 복사한다.

원본 `Ragdoll.detachBone`은 이미 `DisplayBone.getLeftRotation()`, `getScale()`, `getRightRotation()`, `getDisplay()`를 복사한다. `ServerInfo.VERSION_NUMBER` 분기도 없다. 따라서 해당 변환을 그대로 유지한다. `YAW_OFFSET`의 기본값 0과 공개 조절 기능도 유지한다.

`PhysicsBody`는 생성자와 `attach`에서 각 Display의 rightRotation을 `rightRots`에 저장하고, 매 틱 `new Transformation(off, left, scales.get(i), rightRots.get(i))`로 복원한다. 이 경로도 원본과 같다.

## translation이 중복 적용되지 않는 근거

GCBAPI `MEAPI.getBoneLocation(ModelBone, boolean)`은 `bone.getLocation(OffsetMode.GLOBAL, new Vector3f(), true)`로 위치를 구한다. 이후 yaw/pitch 계산은 Location의 좌표를 바꾸지 않는다.

다음 ModelEngine R4.1.1 메서드를 대조했다.

- `ModelBone.getLocation(OffsetMode, Vector3f, boolean)` 기본 메서드: 네 번째 인자를 true로 하여 구현체를 호출한다.
- `ModelBoneImpl.getLocation(OffsetMode, Vector3f, boolean, boolean)`: globalTransform의 위치를 본 yaw의 음수만큼 Y 회전하고 pivot 또는 baseLocation에 더한다. 전달된 추가 오프셋은 0이므로 스케일 적용으로 추가 이동이 생기지 않는다.
- `ModelBoneImpl.calculatePivotLocation`, `getBaseLocation`, `updateYaw`: 기본 player 본의 기준 위치와 yaw를 확인했다.
- `DisplayRendererImpl.initialize`, `readModelData`, `create`, `read`: globalTransform 위치에 behavior 변환을 적용하고 pivot yaw로 회전한 후 mountOffset을 뺀 값을 DisplayBone position으로 보관한다.
- `DisplayRendererImpl$PivotImpl.getMountOffset`, `updatePosition`: 렌더 pivot 위치는 baseLocation에 mountOffset을 더한 값이다.
- `PlayerLimbImpl.modifyTransform`: position에 `(0, limbYOffset, 0)`만 더한다.

기본 player 모델에서 `B=baseLocation`, `P=globalTransform.position`, `M=mountOffset`, `Y=(0, limbYOffset, 0)`, `R=본 yaw의 음수에 대한 Y 회전`이라 두면 다음과 같다.

```text
MEAPI.getBoneLocation = B + R(P)
renderer pivot       = B + M
DisplayBone position = R(P + Y) - M

renderer pivot + DisplayBone position
    = B + R(P) + Y
    = MEAPI.getBoneLocation + Y
```

Y축 회전은 Y 오프셋에 영향을 주지 않는다. 따라서 엔티티를 MEAPI가 반환한 본 위치에 놓고 transformation에 limb Y 오프셋만 넣는 기존 방식이 같은 위치를 만든다. 이 상태에서 `target.getPosition()`까지 더하면 본 위치가 이중 적용된다. translation 처리 로직은 바꾸지 않았다.

이 등식은 기본 player 모델과 PLAYER_LIMB behavior에 대한 확인이다. 제3자가 `BoneTransformReadEvent`에서 추가 이동을 넣거나 특수 camera/pivot override를 사용하는 모델은 별도 검증이 필요하다. 물리 충돌 상자 크기는 기존 player 모델 고정값이므로 임의 모델·비균일 배율의 시각 정합성까지 입증한 것은 아니다.

## 내부 API와 원본 대비 검토

`javap`에서 다음 시그니처가 유지됨을 확인했다.

- `DisplayRendererImpl.getRendered()` / `getSpawnQueue()`: `Map<String, DisplayBone>`.
- `DisplayBone.BoneData.getModel()`: `DataTracker<ItemStack>`.
- `DisplayBone.BoneData.getId()` / `getModelHash()`: int.
- `PlayerLimb.Limb.defaultYOffset` / `slimYOffset`: public final float.
- `PlayerLimb.Limb.defaultId` / `slimId`: public final int.
- `PlayerLimbImpl.getLimbType()`: `PlayerLimb.Limb`.

새 파일과 원본을 `git diff --no-index`로 비교한 결과, `PhysicsBody.java`의 변경은 패키지 선언뿐이다. `Ragdoll.java`는 패키지·플러그인 인스턴스 import, CMD component 조회와 그 진단 로그, 포팅 설명 주석만 바뀌었다. 새 `limbModelId`는 private 메서드다.

공개 필드·메서드 이름과 타입, 두 스코어보드 태그, 수치, 관절 한계, 절단·허리 분리·코스튬 동작, 관절 우선 제거 순서, `released` 가드에 의도치 않은 변경은 발견하지 못했다. 실제 스킨·무기 각도·래그돌 충돌의 인게임 검증은 여전히 별도 확인 대상이다.
