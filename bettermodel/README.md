# RemoteRagdoll-BetterModel

플레이어가 죽으면 **자기 스킨 그대로의 몸**이 그 자리에 쓰러진다. 머리·몸통·양팔·양다리 6조각이 관절 5개로 이어진
Jolt 래그돌이고, 주변 블록에 부딪히며 구른다. 조각은 BetterModel 의 스킨 매니저가 잘라 준 아이템이며(입고 있던 갑옷도
같이 구워진다) 엔티티가 아니라 **패킷**으로만 그려진다.

- 마지막 타격이 셀수록 관절이 끊어진다(최대 체력의 35%·65%·120% 가 경계). 맞은 방향으로 날아간다.
- 휘두르면 걷어차고, 걸어서 지나가면 밀린다. 손에 든 아이템(아이템 모델로 된 모자도)이 같이 구른다.
- 물에서는 뜨고, 일정 시간(기본 120초) 뒤 사라진다.

## 설치

1. 서버: Minecraft 26.3 (Paper / Purpur / DivineMC), Java 25, JVM 옵션 `--enable-native-access=ALL-UNNAMED`.
2. [BetterModel](https://github.com/toxicity188/BetterModel) 3.5.1 을 넣는다. 클라이언트가 BetterModel 이 만든
   리소스팩을 받아야 조각이 보인다.
3. `RemoteRagdoll-BetterModel-1.0.0.jar` 를 `plugins/` 에 넣는다.

부팅 로그에 `Jolt 1.0.0 ready`, `Ragdolls on (Jolt physics, BetterModel skin slices)` 가 나오면 켜진 것이다.
BetterModel 이 없거나 네이티브를 못 읽으면 경고만 남기고 아무것도 하지 않는다.

## 명령 (`remoteragdoll.command`, 기본 OP)

| 명령 | 하는 일 |
|---|---|
| `/ragdoll [플레이어]` | 그 플레이어(생략하면 나)의 몸을 내 앞 2칸에 떨어뜨린다 |
| `/ragdoll at <x> <y> <z> [월드]` | 스티브 몸을 그 자리에 떨어뜨린다(콘솔 가능) |
| `/ragdoll clear` | 모든 래그돌을 지운다 |

시체는 **72블록 안에 플레이어가 있을 때만** 만들어진다(아무도 못 보는 곳에는 물리를 쓰지 않음). 로그의
`RAGDOLL_READY` / `RAGDOLL_REFUSED <이유>` 로 결과를 확인할 수 있다.

## 설정 (`config.yml`)

| 키 | 기본 | 뜻 |
|---|---|---|
| `on-death` | `true` | 죽을 때 몸을 남긴다 |
| `lifetime-seconds` | `120` | 몸이 남아 있는 시간 |
| `hide-dying-player` | `true` | 바닐라의 죽는 모습을 다른 사람에게 숨긴다(몸과 겹치므로) |
| `kick-strength` | `1.0` | 걷어차는 세기, 0 이면 끔 |
| `corpse.seam-pixels` | `0.0` | 팔다리 안쪽 이음매를 스킨 픽셀만큼 벌린다 |
| `corpse.side-inset-percent` | `0.1` | 조각을 안쪽으로 당기는 정도(팔다리 폭의 %) |
| `corpse.hand-yaw/pitch/roll` | `-90/0/0` | 손에 든 아이템의 회전(도) |

## 다른 플러그인에서 쓰기

`kr.gcb.dungeon` 패키지가 래그돌 본체이고, `kr.gcb.ragdoll.BetterModelRagdoll` 은 그것을 쓰는 예다.
BetterModel 타입을 직접 부르는 것은 `Ragdoll` 뿐이고 바깥에서는 `Corpses` 만 거치므로, BetterModel 이 없는 서버에서도
클래스 로딩 오류가 나지 않는다.

```java
if (Corpses.available() && JoltNatives.isAvailable(plugin)) {
    PhysicsWorld physics = new PhysicsWorld(plugin, world);          // 월드마다 하나
    Bukkit.getScheduler().runTaskTimer(plugin, physics::tick, 1L, 1L); // 매 틱 직접 돌린다
}

// 죽을 때: 맞은 세기(damage / maxHealth)로 관절이 끊기고 push(블록/초) 방향으로 날아간다
Corpses.drop(plugin, physics, player.getLocation(), player, 120 * 20, damage, maxHealth, push);

// 상호작용
Shoves.kick(physics, player, 1.0);      // 휘두를 때
Shoves.barge(physics, player, travel);  // 움직일 때 (이번 틱 이동량)
```

스킨 URL 로 직접 만들 수도 있다: `Corpses.drop(plugin, physics, at, skinUrl, slim, ticks, entityOrNull, damage, maxHealth, push)`.
몸을 다시 만지려면(옷 갈아입히기, 끌기, 치우기) `Corpses.dropAndKeep(...)` 이 돌려주는 핸들을 `Corpses.redress / haul / dispel` 에 넘긴다.

## 빌드

```sh
./gradlew build   # JAVA_HOME = JDK 25
```

Paper API 와 BetterModel API(`io.github.toxicity188:bettermodel-bukkit-api:3.5.1`)는 compileOnly, jolt-jni 는 jar 에 들어간다.

## Jolt 네이티브

jolt-jni 1.0.0 (`ReleaseSp`) 네이티브 8종이 jar 에 들어 있고, 첫 실행 때 OS/아키텍처에 맞는 것 하나만
`plugins/RemoteRagdoll-BetterModel/natives/` 로 풀린다(파일 이름에 플랫폼이 붙는다).

| OS | 아키텍처 | 아티팩트 |
|---|---|---|
| Linux | x64 / x64+FMA / ARM64 / ARM32hf | `Linux64`, `Linux64_fma`, `Linux_ARM64`, `Linux_ARM32hf` |
| macOS | x64 / ARM64 | `MacOSX64`, `MacOSX_ARM64` |
| Windows | x64 / x64+AVX2 | `Windows64`, `Windows64_avx2` |

기본은 호환성이 넓은 기본판이다. `-Dremoteragdoll.jolt.variant=avx2`(Windows) 또는 `fma`(Linux)로 빠른 판을 고를 수 있다.
Windows ARM64 는 jolt-jni 가 내지 않아 없다.

## 원본과 바꾼 것

래그돌 코드(`kr.gcb.dungeon.model`, `kr.gcb.dungeon.physics`)는 Graywater(2026-11-16)의 것을 옮겼다. 바꾼 곳은 다음과 같다.

- `Ragdoll.itemFor`: Gazadry 의 BetterModel 포크에만 있는 `SkinData.rightBreast/leftBreast` 두 줄을 뺐다. 이 판에서는
  그 부위를 그리는 코드(`bust`)가 아무것도 하지 않으므로 동작은 같다.
- `EntityPuppet.blind`: 플러그인을 이름(`"Gazadry"`)으로 찾던 것을 이 클래스를 실은 플러그인으로 바꿨다.

[Fabric 판](https://github.com/biryeongtrain/RemoteRagdoll-fabric/tree/codex/fabric-port/fabric)에서 가져온 것:

- `JoltNatives`: macOS·Linux ARM 네이티브와 AVX2/FMA 판 선택, 플랫폼별 파일 이름.
- `Ragdoll.don/hang`: 모자와 손에 든 아이템도 실제 디스플레이 엔티티 대신 패킷 퍼펫으로 그린다
  (`Puppets.limb(..., PlatformItemTransform)`). 패킷을 못 만들 때만 예전처럼 엔티티로 그린다.
- `PhysicsWorld`: 물리 스텝 실패 로그가 `[Gazadry]` 이름으로 Bukkit 전역 로거에 찍히던 것을 플러그인 로거로.
- `Ragdoll.armourPiece`: 갑옷 트림 이름을 없어질 `getKey()` 대신 레지스트리에서 찾고, 등록되지 않은 트림이면 트림 없이 굽는다.

Graywater 흔적으로 `graywater` 네임스페이스의 착용 아이템을 `BetterModel/armors` 의 같은 이름 세트로 굽는 분기가 남아 있다
(다른 서버에서는 해당 없음).

## 확인한 것

- 원본 BetterModel 3.5.1 API 로 컴파일된다.
- DivineMC 26.3-4 + BetterModel 3.5.1 서버(Windows x64)에서 부팅: `Jolt 1.0.0 ready`, 명령 처리, 종료까지 오류 없음.
- 스킨 조각이 실제로 그려지는 모습은 같은 래그돌 코드로 Graywater 서버에서 시험 클라이언트로 확인했다. 이 데모 플러그인으로
  클라이언트에서 다시 보지는 않았다.
- 미확인: Windows x64 밖의 네이티브 실제 로드(jar 에 들어간 것만 확인), 패킷으로 바꾼 모자·손 아이템의 모습.
