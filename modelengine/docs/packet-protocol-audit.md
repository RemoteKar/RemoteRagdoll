# Minecraft 26.3 / PacketEvents 2.14.0 패킷 근거

조사일: 2026-09-26. 인터넷의 다른 버전 인덱스 표를 사용하지 않고, 지정된 jar와 실제 26.3 서버·클라이언트의 `javap -p -c` 출력으로 확인했다. 재현 스크립트는 `validation/audit_packet_bytecode.py`, 전체 출력은 `../output/RemoteRagdoll/validation/packet-bytecode/`다.

| 입력 | SHA-256 |
|---|---|
| Paper dev bundle 26.3.build.5-alpha의 `.gradle/caches/paperweight/taskCache/mappedServerJar.jar` | `e64e24c476635f41df26ae269c6a4cbc4df6193290b367d54d016c9e2c4dbac9` |
| 지정 서버의 `packetevents-spigot-2.14.0.jar` | `060087c58ec268eae7dd0eb1f17ac3bea1eaadda1693414141ff64e506091bd7` |
| 로컬 `GCBServer/ScreenRoll/build/minecraft-26.3-client.jar` | `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d` |

지정 PacketEvents jar의 `plugin.yml` 이름은 소문자 `packetevents`다. 플러그인 의존성에도 이 이름을 쓴다.

## Entity data 인덱스와 직렬화 타입

`Entity.<clinit>`가 `SynchedEntityData.defineId`를 호출하는 순서는 shared flags, air supply, custom name, name visible, silent, no gravity, pose, ticks frozen이다. 이 8개가 인덱스 0–7이다. `Display extends Entity`, `Display.ItemDisplay extends Display`이며, 아래 순서는 **필드 선언과 static 초기화의 실제 defineId 호출 순서**다. `defineSynchedData`가 기본값을 등록하는 순서와 혼동하지 않는다. 예를 들어 기본값 메서드에서는 pos/rot duration이 먼저 나오지만 인덱스는 10이다.

| 인덱스 | NMS `DATA_*` 필드 | NMS serializer | PacketEvents `EntityDataTypes` | 초기값 |
|---:|---|---|---|---|
| 8 | `TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID` | INT | INT | 0 |
| 9 | `TRANSFORMATION_INTERPOLATION_DURATION_ID` | INT | INT | 1 |
| 10 | `POS_ROT_INTERPOLATION_DURATION_ID` | INT | INT | 1 |
| 11 | `TRANSLATION_ID` | VECTOR3 | VECTOR3F | 공통 물리 렌더 계산의 translation |
| 12 | `SCALE_ID` | VECTOR3 | VECTOR3F | 원래 scale |
| 13 | `LEFT_ROTATION_ID` | QUATERNION | QUATERNION | 원래 left quaternion |
| 14 | `RIGHT_ROTATION_ID` | QUATERNION | QUATERNION | 원래 right quaternion |
| 15 | `BILLBOARD_RENDER_CONSTRAINTS_ID` | BYTE | BYTE | vanilla 기본 0(FIXED) |
| 16 | `BRIGHTNESS_OVERRIDE_ID` | INT | INT | vanilla 기본 -1 |
| 17 | `VIEW_RANGE_ID` | FLOAT | FLOAT | 3f |
| 18 | `SHADOW_RADIUS_ID` | FLOAT | FLOAT | vanilla 기본 0f |
| 19 | `SHADOW_STRENGTH_ID` | FLOAT | FLOAT | vanilla 기본 1f |
| 20 | `WIDTH_ID` | FLOAT | FLOAT | 0f |
| 21 | `HEIGHT_ID` | FLOAT | FLOAT | 0f |
| 22 | `GLOW_COLOR_OVERRIDE_ID` | INT | INT | vanilla 기본 -1 |
| 23 | ItemDisplay `ITEM_STACK_ID` | ITEM_STACK | ITEMSTACK | 변환·검증된 전체 아이템 |
| 24 | ItemDisplay `ITEM_DISPLAY_ID` | BYTE | BYTE | Bukkit ItemDisplayTransform의 대응 ID |

VECTOR3의 NMS Java 값은 `Vector3fc`, QUATERNION은 `Quaternionfc`이다. PacketEvents 값 클래스는 각각 `com.github.retrooper.packetevents.util.Vector3f`, `Quaternion4f`이고 quaternion 순서는 x/y/z/w다. wire serializer의 숫자 ID를 직접 쓰지 않고 PacketEvents의 버전 대응 `EntityDataType`을 사용한다.

26.3 `ItemDisplayContext`의 ID는 NONE 0, THIRD_PERSON_LEFT_HAND 1, THIRD_PERSON_RIGHT_HAND 2, FIRST_PERSON_LEFT_HAND 3, FIRST_PERSON_RIGHT_HAND 4, HEAD 5, GUI 6, GROUND 7, FIXED 8, ON_SHELF 9다. 조사한 Paper API의 Bukkit `ItemDisplayTransform`은 NONE부터 FIXED까지만 노출하며 이 0–8 순서는 NMS와 같다. ON_SHELF는 현재 Bukkit API에 없고 래그돌 입력에서도 사용하지 않는다.

검증용 플러그인 `PacketComponentProbe`는 enable 다음 틱에 NMS `EntityDataAccessor.id()`와 `serializer()`를 reflection으로 조회해 실제 서버에서도 이 표의 사용 인덱스·타입을 단언한다. 결과는 서버 스모크 로그에 기록한다.

## 실제 서버와 공유하는 엔티티 ID

26.3의 API는 **`Bukkit.getUnsafe().nextEntityId(world)`**다. 이전 버전의 no-argument 메서드나 `Entity.nextEntityId()`를 추측해서 호출하지 않는다.

`CraftMagicNumbers.nextEntityId(World)` → `CraftWorld.getHandle().getNextEntityId()` → `ServerLevel.getNextEntityId()`로 이어진다. 마지막 메서드는 정적 `ENTITY_COUNTER: AtomicInteger`를 증가시키고 0 및 서버 tracker에서 이미 사용 중인 ID를 건너뛴다. 실제 `Entity` 생성자도 `level.getNextEntityId()`를 호출한다. 따라서 메인 스레드에서 같은 할당기를 사용하면 실제 엔티티와 가짜 엔티티가 같은 ID를 독립적으로 만들어 내지 않는다.

## 위치 갱신 패킷과 보간

선택한 패킷은 `WrapperPlayServerEntityPositionSync`다. PacketEvents 2.14.0의 `write()`는 `V_26_3` 이상에서 `PositionPath.write`, yaw float, pitch float, onGround boolean을 쓴다. 예전 `EntityPositionData` 생성자도 내부적으로 `LinearPositionPath`를 구성하므로 26.3에 사용할 수 있다. 26.3 NMS `ServerEntity`도 절대 위치 동기화에 `ClientboundEntityPositionSyncPacket.of(Entity, PositionPath)`를 사용한다.

실제 26.3 `ClientPacketListener.handleEntityPositionSync`는 다음 순서다.

1. `PositionPath.endPosition()`으로 delta codec 기준점을 갱신한다.
2. 로컬 권한 엔티티는 위치 덮어쓰기를 건너뛴다. 가짜 ItemDisplay는 이 대상이 아니다.
3. 클라이언트가 tick 중인 엔티티이고 이동 거리 제곱이 4096 이하이면 `Entity.moveOrInterpolateTo(PositionPath, yaw, pitch)`를 호출한다. 그 밖에는 `snapTo`한다.
4. `Display.createInterpolationHandler()`는 `LinearInterpolationHandler`를 만들고, 메타데이터 10 변경 시 `setInterpolationLength`에 그 값을 넣는다. 따라서 teleport duration=1 설정을 따르는 보간 경로다.

26.3의 `handleTeleportEntity`도 조건에 따라 `setValuesFromPositionPacket` → `moveOrInterpolateTo`에 도달한다. 따라서 “teleport는 보간하지 않는다”는 주장은 맞지 않다. 일반적인 절대 위치 추적 경로와 같은 packet을 선택한 것이며, 64블록 초과 한 번 이동 또는 클라이언트 비활성 청크에서는 vanilla와 같이 snap할 수 있다. 실제 프레임별 시각 결과는 클라이언트 접속 전에는 미검증이다.

변환 보간과 위치 보간은 별개다. 클라이언트 `Display.tick`는 메타데이터 8 변경 통지를 받으면 transformation의 시작 tick을 다시 잡는다. 기존 실제 Display 경로는 생성 시 delay=0을 한 번 지정하고, 이후 `setTransformation`만 호출했다. 동일한 delay 값을 매 틱 강제 재전송하는 변경은 기존 코드에 없으므로, 이번 변경분 전송에서도 이를 새로 추가하지 않는다.

## 아이템 컴포넌트 보존

`SpigotConversionUtil.fromBukkitItemStack`은 `SpigotReflectionUtil.decodeBukkitItemStack`에 위임한다. 이 메서드는 Bukkit 아이템을 NMS 아이템으로 바꾸고 NMS 네트워크 codec으로 buffer에 기록한 다음 PacketEvents `PacketWrapper.readItemStack()`으로 읽는다. 역변환은 PE `writeItemStack()` → NMS codec decode → Bukkit이다. 오래된 NBT 필드 몇 개를 수동 복사하는 경로가 아니다.

PacketEvents `ItemProfile.read/write`는 이름, UUID, property 목록을 다루며, 1.21.9 이후의 동적/정적 profile 표시와 SkinPatch도 직렬화한다. `ItemProfile.Property`는 name/value/optional signature를 보존한다. `ItemCustomModelData.read/write`의 1.21.4 이후 경로는 floats, flags, strings, colors 목록을 모두 기록한다. `ITEM_MODEL`은 별도 component codec으로 처리한다.

정적 분석만으로 모든 런타임 값을 보장하지 않으므로, 렌더 직전에 전체 Bukkit → PE → Bukkit 변환을 수행하고 NMS `ItemStack.matches`로 아이템 종류·개수·전체 컴포넌트의 동일성을 검사한다. 예외나 불일치가 생긴 개별 파츠는 실제 Display 경로로 돌아가야 한다. 이렇게 하면 새로운 custom component나 미지원 profile 형태를 조용히 잃고 가짜 머리를 표시하지 않는다.

`validation/PacketComponentProbe.java`는 일반·slim 스킨 구조 각각에 unsigned/signed textures property를 넣은 player_head 4개를 만들고 실제 서버 codec을 왕복한다. 모든 입력에 `item_model`과 CMD floats/flags/strings/colors를 함께 넣고, NMS 전체 비교와 명시적인 필드별 비교를 둘 다 한다. 서명은 보존 검사용 임의 문자열이며 Mojang 서명 유효성·실제 텍스처 다운로드·클라이언트 렌더를 검증하는 테스트가 아니다. 성공 로그 marker는 `PACKET_COMPONENT_PROBE_OK`, 실패는 `PACKET_COMPONENT_PROBE_FAILED`다.

첫 실제 Purpur 26.3 스모크에서 4개 wire roundtrip과 NMS 메타데이터 reflection 단언이 모두 통과했다(`../output/RemoteRagdoll/validation/server-preflight.log`). 이 preflight는 이전 RemoteRagdoll jar로 수행해 새 렌더러 자체의 성공을 뜻하지 않는다.

새 배포 jar를 넣은 최종 재실행에서도 **`PACKET_COMPONENT_PROBE_OK`와 `PACKET_RENDER_PROBE_OK`를 모두 확인했다**(`../output/RemoteRagdoll/validation/server-smoke.log`). 실제 production ItemComponentBridge, PacketRenderManager 준비 상태, 서버 ItemDisplay 수 불변, 가짜 visual 생성/이동/제거, 동결 바디의 명시적 회전, PhysicsBody detach 후 자세 유지/clearAll 제거, clearWorld, false 스위치의 실제 Display 생성·제거를 검사했다. 첫 새 jar 실행에서 실제 Display 개수 단언은 실패했지만, 플레이어 없는 서버의 테스트 청크를 plugin chunk ticket으로 로드한 뒤 동일 배포 jar로 통과했다. 테스트는 count 단언을 완화하지 않았으며 검사 종료 후 ticket을 제거한다.

GCBAPI는 자체 Skript 클래스 로드 오류 때문에 정상 enable되지 않았으므로, 이 개별 렌더/컴포넌트 테스트 통과를 전체 플러그인 조합 성공으로 해석하지 않는다. 접속 클라이언트가 없는 상태에서 실제 visual 등록·자원 정리와 codec을 검사한 결과다. 서버 스모크의 최종 결과는 PORTING.md, [격리 서버 검사](server-smoke.md)와 출력 로그를 따른다.

프로브 빌드: `python validation/build_packet_probe.py`. 결과는 격리된 `run-test/plugins/PacketComponentProbe.jar`뿐이며 배포 jar에 포함하지 않는다. 테스트 서버에서는 지정 포트 규칙을 지키고 반드시 정상 종료해야 한다.
