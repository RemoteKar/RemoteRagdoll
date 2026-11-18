# RemoteRagdoll 1.0.0 — Minecraft 26.3 포팅

작업일: 2026-09-26. `gradlew.bat shadowJar` 성공. 결과는 `../output/RemoteRagdoll/RemoteRagdoll-1.0.0.jar`와 `Physics.sk`다.

최초 포팅 검증은 아래 이력에 보존했다. **이번 패킷 렌더의 빌드·단위 테스트·격리 서버 검증 결과는 문서 끝의 “패킷 렌더” 절이 최신 기준이다. 인게임 시각 확인은 미검증이다.** 운영 서버 파일은 교체하지 않았다.

## 원본과 빌드 기준

- 이관한 원본: GCBAPI 로컬 `b7cf3de7`의 미커밋 `JoltNatives`, `PhysicsWorld`, `PhysicsBody`, `Ragdoll`, `Liquid`, `TerrainCache`, `EntityCache` 7개. 새 패키지는 `org.EIQUI.RemoteRagdoll`다.
- `PhysicsAPI.java`는 이관하지 않았다. GCBAPI의 기존 궤적·콜라이더 API와 이번 플러그인은 별개다.
- GCBAPI compileOnly: `origin/main`의 `d67b72e4cf4b55ae1b0d9986b0d484599593e6f1`를 별도 detached worktree에서 **전체 빌드**했다. 1.21.8 jar나 시그니처 stub은 사용하지 않았다. 임시 worktree는 제거했다.
- ModelEngine compileOnly: 같은 26.3 커밋의 `libs/ModelEngine-R4.1.1.jar`. 자세한 출처와 SHA-256은 [libs/README.md](libs/README.md).
- 리소스팩: [GCB-resourcepack main](https://github.com/eiqq/GCB-resourcepack/tree/main), `ead3d159d168f6c1b68950fa630a550ca007e92f`. `git fetch origin` 성공 후 ref를 재확인했다. `legal14` 체크아웃은 유지하고 `git show origin/main:<경로>`로만 조사했다.
- Paperweight `2.0.0-beta.23`, Paper dev bundle `26.3.build.5-alpha`, Shadow `9.0.2`, Java release/toolchain 25.
- 실제 JDK: `GCBServer\Java\Java_25`, Temurin `25.0.3+9-LTS`. JDK 설치·자동 다운로드·foojay 사용 없음.
- 새 프로젝트의 Gradle wrapper는 `9.2.1`. 원본 wrapper의 8.14.3 대신 Java 25 실행을 지원하는 버전을 사용했다. [Gradle Java 호환 표](https://docs.gradle.org/current/userguide/compatibility.html).
- jolt-jni는 요청한 `1.0.0` 유지. Windows64 Java binding과 Windows64/Linux64 `ReleaseSp`를 shadow jar에 포함한다.

## 바꾼 API와 소유권

| 기존 | RemoteRagdoll / 26.3 |
|---|---|
| `org.EIQUI.GCBAPI.physics.<7개 클래스>` | `org.EIQUI.RemoteRagdoll.<같은 클래스 이름>` |
| `org.EIQUI.GCBAPI.main.that` | `org.EIQUI.RemoteRagdoll.main.that` — 로거·데이터 폴더·스케줄러 소유자 |
| `plugins/GCBAPI/natives` | `plugins/RemoteRagdoll/natives` |
| `ItemMeta.getCustomModelData()` / `hasCustomModelData()`로 limb ID 읽기 | `getCustomModelDataComponent().getFloats()`의 첫 값. 빈 목록은 ID 없음 |
| `MEAPI.getActiveModel(Entity, String)` | 같은 26.3 GCBAPI 시그니처 그대로 호출 |
| `MEAPI.getBoneLocation(Entity, String, String)` | 같은 26.3 GCBAPI 시그니처 그대로 호출 |
| `ComplexParticleAPI.Dot(String, Location, double, double, double, int, double, boolean, Object)` | 같은 26.3 GCBAPI 시그니처 그대로 호출 |
| Clearer가 GCBAPI 티커를 취소한 후 `resumeTicker()` 호출 | 새 티커는 RemoteRagdoll 소유. Clearer 연동 불필요. 기존 public `resumeTicker()` 유지 |
| GCBAPI에 물리 종료 책임이 섞여 있던 구조 | 새 `main.onDisable()`이 `PhysicsWorld.clearAll()` 후 티커와 RemoteRagdoll 태스크 취소 |

public 필드·메서드 선언을 원본과 대조했다. 필드 이름·타입·기본값, 메서드 이름·매개변수·반환형을 유지했다. 새 helper와 티커 종료 메서드는 public API를 늘리지 않는다. `GCB:Ragdoll`, `GCB:RagdollHost`, ARM_SPREAD/HEAD_CONE 계열, DEBUG, ENTITY_COLLISION, RAGDOLL_SELF_COLLISION, MAX_RAGDOLLS, Liquid 설정값도 그대로다.

물리 수치·관절 한계·레이어·충돌 그룹 필터·절단/허리 분리/코스튬 동작을 바꾸지 않았다. 관절을 먼저 제거한 뒤 바디를 반납하는 순서, 바디 ID 0 허용, `released` 가드 및 크래시 방지 주석을 유지했다. PhysicsBody·TerrainCache·EntityCache는 패키지만, JoltNatives는 패키지와 플러그인 소유자만, Liquid는 패키지만 변경했다.

GCBAPI 원본의 Clearer 호출과 jolt 의존성 등 다른 미커밋 변경은 수정하지 않았다. 배포 대상 26.3 GCBAPI에는 원래 이 Jolt 물리 코드가 없다.

## 요청한 7개 포팅 점검 결과

| 항목 | 판정 | 결과와 범위 |
|---|---|---|
| 1. 26.3 ModelEngine 본 렌더·SVD | 문제없음 | BoneData 아이템 전체 clone, leftRotation/scale/rightRotation/display mode 복사 유지. PhysicsBody도 rightRotation을 매 틱 보존. 1.19 버전 판정에 따른 π 보정이 원본 Ragdoll에는 없다. 실제 시각 결과는 미검증 |
| 2. Custom Model Data | 수정함 | slim 판정과 진단 로그를 CMD floats[0] 조회로 변경. R4.1.1 PlayerLimb가 기존 setter로 기록한 정수는 첫 float이며, 일반 본의 strings[0] variant나 colors[0] 틴트를 ID로 사용하지 않음 |
| 3. limb 셰이더·팩 | 수정함 | 주석 파일명을 entity.vsh로 변경. SPACING 1024/MAXRANGE 512/partId 및 아래 표 대조 완료. player_head.json와 slim 모델 두 개도 main에서 확인 |
| 4. ModelEngine 내부 API | 문제없음 | R4.1.1 javap와 컴파일로 getRendered/getSpawnQueue/BoneData API 및 Limb.defaultYOffset/slimYOffset/slimId 이름·타입 확인 |
| 5. Bukkit 26.3 API | 문제없음 | Display/Transformation/getBoundingBox/isPassable 등 사용 코드 전체가 실제 26.3 dev bundle로 컴파일됨. 서버 내부 동작은 미검증 |
| 6. Java 25 네이티브 접근 | 수정함 / 서버 확인 불가 | manifest에 Enable-Native-Access 추가. Java 25 단독 프로세스 검증은 아래 설명 참고. 플러그인 manifest만으로 서버 JVM의 권한이 활성화되지는 않음 |
| 7. jolt-jni 버전 | 문제없음 | 1.0.0 유지. Java 25 Windows 단독 프로세스의 초기화·시뮬레이션·정리 성공. Linux 실제 로드는 미검증 |

| 부위 | CMD floats[0] | Y 오프셋 | entity.vsh의 감소 후 partId |
|---|---:|---:|---:|
| 머리 | 1 | 0 | 재매핑 분기 밖 |
| 오른팔 | 2 | -1024 | 0 |
| 왼팔 | 3 | -2048 | 1 |
| 슬림 오른팔 | 7 | -3072 | 2 |
| 슬림 왼팔 | 8 | -4096 | 3 |
| 몸통 | 4 | -5120 | 4 |
| 오른다리 | 5 | -6144 | 5 |
| 왼다리 | 6 | -7168 | 6 |

슬림 조건은 `partId == 2 || partId == 3`이다. `MEAPI.getBoneLocation`이 본의 월드 위치를 이미 반환하므로, 엔티티를 그 위치에 놓고 translation에는 limb Y 오프셋만 넣는 기존 방식이 맞다. 여기에 DisplayBone.position을 다시 더하면 위치가 이중 적용된다. 바이트코드 메서드·수식·UV 매핑과 특수 모델에 대한 한계는 [docs/render-audit.md](docs/render-audit.md)에 기록했다.

## 빌드와 결과물 검사

PowerShell에서:

```powershell
Set-Location 'RemoteRagdoll\modelengine'
$env:JAVA_HOME = '<JDK 25 경로>'
.\gradlew.bat shadowJar --console=plain --no-daemon
```

두 compileOnly jar는 `libs/README.md`대로 준비한다. 자동 JDK 다운로드는 비활성화되어 있다. 빌드 결과는 `build/libs/RemoteRagdoll-1.0.0.jar`다. 최초 실행에서는 병렬로 빌드하던 GCBAPI jar가 아직 없어 컴파일이 실패했고, 의존성 배치 후 같은 코드로 `BUILD SUCCESSFUL in 16s`를 확인했다.

최종 jar 검사 결과:

- `plugin.yml` 포함. name/main/api-version 26.3/depend/softdepend와 version 1.0.0 확인.
- `windows/x86-64/com/github/stephengold/joltjni.dll` 포함, 2,509,824 bytes, PE 헤더 확인.
- `linux/x86-64/com/github/stephengold/libjoltjni.so` 포함, 3,665,416 bytes, ELF 헤더 확인.
- 플러그인 클래스의 Java class major version 69(25) 확인.
- GCBAPI·ModelEngine·Bukkit 클래스 및 원래 PhysicsAPI.class가 결과 jar에 들어가지 않음.
- 결과 jar 크기: 3,090,571 bytes.
- SHA-256: `9a3db9a94b9011a60e1fe1826433a05dfa333b6c9c7329cd140ab6cbb39209da`.

기계 판독 검사 결과와 빌드 출력은 `../output/RemoteRagdoll/artifact-verification.json`, `build-result.log`에 있다.

## Java 25 네이티브 접근

manifest에 `Enable-Native-Access: ALL-UNNAMED`를 넣었다. 다만 이 속성은 `java -jar`로 실행되는 executable jar에 적용된다. Bukkit이 나중에 로드하는 플러그인 jar의 속성만으로 서버 JVM 전체 설정을 바꿀 수 없다. 필요 시 서버 실행 명령의 **-jar 앞에** 다음 옵션을 넣는다.

```text
java --enable-native-access=ALL-UNNAMED -jar <26.3 서버 jar> nogui
```

Java 25 기본 native-access 정책은 warning 후 호출 허용이다. `--illegal-native-access=deny` 정책을 사용한다면 위 허용 옵션이 필요하다. [Java 25 공식 문서](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html).

`validation/NativeSmoke.java`는 Bukkit 서버와 독립된 검사다. Jolt 초기화, 정적 지형·동적 바디 두 개·SwingTwist 관절, 80틱 갱신, 관절 먼저 제거 후 바디 제거/파괴를 3회 반복한다. 최종 jar 기반 실행 결과와 옵션별 로그는 `../output/RemoteRagdoll/validation/`에 보관한다. 이 검사는 PhysicsWorld/Ragdoll의 Bukkit 연동이나 플러그인 onDisable 경로를 실행하는 검사가 아니다.

| 최종 jar 실행 옵션 | 실제 결과 |
|---|---|
| 기본값 | exit 0, System.load restricted-method 경고, 3회 모두 물리/정리 통과 |
| `--enable-native-access=ALL-UNNAMED` | exit 0, 경고 없이 3회 통과 |
| `--illegal-native-access=deny` | 기대한 exit 1, System.load에서 IllegalCallerException |

정상 실행 두 모드 모두 매회 정리 후 바디 수가 0임을 확인했다. 재실행 명령은 `python validation/run_native_smoke.py --jar build/libs/RemoteRagdoll-1.0.0.jar`다.

## Skript 호환

`scripts/Physics.sk` 및 출력 사본은 지정된 서버 참고본에서 **import 5개만** 새 패키지로 변경했다. 요청에 '4줄'로 적혔지만 열거된 클래스는 JoltNatives/PhysicsWorld/PhysicsBody/Ragdoll/Liquid의 5개다. `JoltRagdoll`, `JoltLiquid` 별칭을 유지했다. 함수 58개, 이름·시그니처·본문·개행·주석은 보존했고, import 역치환 후 원본과 바이트 일치를 확인했다. 따라서 기존 주석의 'GCBAPI jar' 문구도 그대로 남아 있으나 실제 네이티브 포함 jar는 RemoteRagdoll다.

서버 스크립트 전체에서 이 5개 클래스를 직접 import하는 파일은 `a/Physics.sk`뿐이었다. `b/래그돌.sk`, `physicstest.sk`, `z/custom/create/jinjogyun/304/키레이_4_jaji.sk`는 수정하지 않았다. 래퍼 밖에서 직접 호출하는 `getBodies()` 및 `PhysicsBody.setAngularVelocity()`도 유지했다. 원본 SHA-256과 선언 목록은 `../output/RemoteRagdoll/reference-audit.json`에 있다.

## 서버 설치와 인게임 확인

1. **Java 25로 실행하는 26.3 서버를 종료**한다. `legal14` 1.21.8 서버에 이번 jar를 넣지 않는다.
2. `output/RemoteRagdoll/RemoteRagdoll-1.0.0.jar`를 대상 26.3 서버의 `plugins/`에 복사한다. GCBAPI는 26.3 빌드, ModelEngine은 조사한 R4.1.1을 별도로 설치한다. 기존 스크립트가 사용하는 Skript 및 Java import 지원 환경도 유지한다.
3. `output/RemoteRagdoll/Physics.sk`로 대상 서버의 `plugins/Skript/scripts/a/Physics.sk`를 교체한다. 다른 호출 스크립트는 그대로 둔다.
4. GCB-resourcepack **main의 26.3 팩**을 클라이언트에 적용한다. 필요하면 서버 시작 명령에 위 native-access 옵션을 추가하고 서버를 다시 시작한다.
5. `depend: [GCBAPI, ModelEngine]` 때문에 이 둘이 먼저, RemoteRagdoll가 뒤에 로드된다. Skript가 설치되어 있으면 `softdepend: [Skript]`에 따라 Skript도 RemoteRagdoll보다 먼저 로드된다. 서버 시작 완료 후 Physics.sk import 성공과 플러그인 enable 로그를 확인한다.
6. 다음 순서로 실제 인게임 검증한다. 이는 Skript 함수 호출 순서이며 같은 이름의 콘솔 명령을 새로 등록한 것은 아니다.

| 순서 | 함수 | 확인할 내용 |
|---:|---|---|
| 1 | `Physics_isAvailable()` | true와 Jolt 준비 로그, `plugins/RemoteRagdoll/natives`의 DLL 또는 SO 추출 |
| 2 | `Ragdoll_spawn(e, mid, impulse, life)` | 일반/슬림 스킨, 머리·몸통·팔·다리 위치, 무기 SVD 회전, 코스튬, 지형 착지·엔티티 충돌 |
| 3 | `Ragdoll_cut(r, bone, power)` | head/양팔/양다리 및 기존 허리 분리 경로, 절단 후 낙하·충돌·관절 안정성 |
| 4 | `Physics_liquid(l, count, power, up, life, block)` | 방울 지형 충돌·파티클·수명 만료, 래그돌 없는 월드에서도 갱신 |
| 5 | `Physics_clearAll()` | 모든 물리/Display 정리, 반복 호출 안전성, 다시 spawn해 티커 재사용, 게임 종료 sweep 및 서버 종료 정리 |

추가로 활성 래그돌이 있는 상태에서 기존 GCBAPI `/cleaner`를 실행한 후 물리가 계속 갱신되는지 확인한다. `PhysicsWorld.resumeTicker()`를 호출하지 않아도 GCBAPI 태스크 취소가 RemoteRagdoll 티커에 영향을 주지 않아야 한다.

테스트 서버를 따로 띄울 때는 `GCBServer\Java\README.md`대로 TCP/RCON/query 포트가 비어 있는지 먼저 확인한다. **25585는 모든 리스닝 용도로 사용 금지**이며, 테스트 후 서버를 종료한다.

## 남은 위험 요소

- 실제 26.3 서버/Skript/인게임 검증은 미완료다. 이 문서의 빌드·바이트코드·Windows JNI 결과만으로 시각·충돌·종료 동작까지 검증했다고 해석하면 안 된다.
- Linux SO는 jar 포함/ELF 형식만 확인했다. Linux 실행, x86-64 이외 OS/아키텍처, 다른 CPU 환경은 미검증이다. 번들 대상은 Windows/Linux x86-64뿐이다.
- ModelEngine 내부 API에 의존한다. R4.1.1의 다른 패치 jar나 이후 버전으로 바꾸면 재검증이 필요하다.
- 추가 BoneTransformReadEvent 변환, 특수 camera/pivot override, 임의 모델·비균일 배율은 기본 player 모델의 translation 검증 범위 밖이다. 기존 충돌 상자·튜닝 값은 유지했다.
- JNI는 네이티브 크래시가 JVM 전체에 영향을 줄 수 있다. 기존 관절·바디 해제 규칙을 유지했다. 장시간 누적·다중 월드 부하 검증은 하지 않았다.
- 기존 clearAll은 물리 객체를 정리하되 PhysicsSystem/전역 필터의 수명 정책을 변경하지 않는다. 플러그인 hot reload 대신 서버 전체 재시작으로 교체한다. Windows는 로드한 DLL을 사용하는 동안 덮어쓸 수 없다.
- Gradle 10 관련 deprecation 경고가 남지만 지정된 Gradle 9.2.1 빌드에는 영향이 없었다.

GCBAPI·personal·GCB-resourcepack 원본 소스/작업 브랜치는 수정하거나 pull/checkout하지 않았다. 커밋은 새 RemoteRagdoll 저장소에만 하고, 원격 push는 하지 않는다.

<!-- rename-history -->
## 이름 변경 이력 — 2026-09-26

최초 포팅 커밋 `3ae03d3`의 GCBPhysics 저장소를 `.git`과 함께 `RemoteRagdoll`로 폴더째 이동했다. 패키지·group·프로젝트/플러그인 이름·설명·문서·검증 출력 경로 및 Skript import만 새 이름으로 바꿨다. 기존 `output/GCBPhysics/`는 파일별 SHA-256 대조로 변경 없이 보존했음을 확인했다.

- Java 8개는 이동 전 작업 파일에 이름 문자열만 치환한 결과와 바이트가 일치한다. 클래스 이름·public API·물리 로직/수치·`GCB:Ragdoll`/`GCB:RagdollHost`·로그 접두어는 그대로다. 버전·의존성·빌드 옵션도 그대로다.
- `JoltNatives`의 추출/삭제 경로는 모두 `getDataFolder().toPath().resolve("natives")`다. 경로 로직을 수정하지 않아도 플러그인 이름에 따라 `plugins/RemoteRagdoll/natives`를 사용한다.
- 지정된 Java 25에서 `gradlew.bat clean shadowJar --console=plain --no-daemon`이 `BUILD SUCCESSFUL in 7s`로 완료됐다. 위 jar 크기와 SHA-256은 이번 이름 변경 후 결과다.
- 소스·리소스·문서·validation 및 jar의 압축 해제된 전체 487개 항목 이름/바이트를 검사했다. 구 이름은 이 변경 이력 외의 검사 대상에 없으며, Git 이력과 Gradle 캐시는 검사 대상에서 제외했다. `plugin.yml`, 새 패키지 클래스 11개(Java 25), Windows DLL/Linux SO 두 개를 확인했다. 두 네이티브의 바이트는 이전 jar와 일치한다.
- `output/RemoteRagdoll/RemoteRagdoll-1.0.0.jar`로 네이티브 스모크 테스트를 재실행했다. default/enabled/denied 세 모드가 모두 기대한 결과로 통과했다. 실제 서버·인게임 및 Linux 실행은 이번에도 검사하지 않았다.
- 저장소와 `output/RemoteRagdoll/Physics.sk`는 동일하며, legal14 원본 대비 2·3·4·7·9행 import만 다르다. import 역치환 후 원본과 바이트가 일치하고, 함수 58개 및 `JoltRagdoll`/`JoltLiquid` 별칭을 유지했다.
- 검증 기록은 `../output/RemoteRagdoll/artifact-verification.json`, `reference-audit.json`, `build-result.log`, `validation/native-summary.json`에 있다. GCBAPI·personal·GCB-resourcepack·서버 파일은 수정하지 않았다.

## 패킷 렌더 — 2026-09-26

기준 커밋 `9c67679`의 실제 ItemDisplay 렌더를 `PartVisual`로 감쌌다. `EntityVisual`은 기존 Bukkit 호출을 그대로 수행하고, `PacketVisual`은 PacketEvents의 spawn/metadata/position-sync/destroy 패킷만 보낸다. 몸 파츠, helmet/righthand/lefthand, 코스튬이 이 경로를 쓴다. `limbTest`와 `detachOnly`, 기존 `PhysicsBody.create(... Display ...)`는 실제 엔티티다.

`Ragdoll.PACKET_RENDER` 기본값은 `true`이며, 새로 만드는 파츠에 적용된다. 이미 만들어진 파츠의 렌더 방식은 바뀌지 않는다. 물리의 translation·left/right rotation·scale·pivot·limb Y 오프셋 계산은 공통 코드에 그대로 있다. `validation/audit_render_contract.py`가 기준 커밋 대비 렌더 호출/휴면 가드만 정규화한 수식 일치, public API 및 Jolt 해제·관절·충돌·수명 관련 31개 계약을 검사한다. `released` 가드와 관절→바디 제거 순서는 변경하지 않았다.

`PhysicsBody.getDisplays()`는 패킷 바디에서 빈 목록을 돌려준다. 전부 실제 엔티티인 바디는 기존처럼 실제 Display 목록을 제공한다. legal14 Git 스냅샷의 `.sk` 1,108개에서 `getDisplays()` 직접 호출은 없었다. `getBodies()` 및 `setAngularVelocity()` 호출과 기존 58개 함수는 유지한다. `scripts/Physics.sk` 및 출력본은 원본 대비 import 5줄 변경과 `Ragdoll_setPacketRender(b:boolean)` / `Ragdoll_getPacketRender() :: boolean` 두 함수 추가만 있다. 자세한 감사는 [Skript 호환 검사](docs/script-packet-audit.md)에 있다. 실제 엔티티 태그 sweep은 패킷 파츠를 만나지 않으며, 앞선 `Physics_clearAll()`이 패킷 파츠를 정리한다.

### 의존성·ID·메타데이터 근거

지정된 `packetevents-spigot-2.14.0.jar`를 `libs/`에 복사하고 기존 `compileOnly(fileTree("libs"))`로 사용한다. 배포 jar에는 PacketEvents를 shade하지 않는다. jar의 plugin.yml에서 확인한 이름은 **`packetevents`**다. RemoteRagdoll의 `depend`에도 이 이름을 추가했다. PacketEvents [2.14.0 릴리스](https://github.com/retrooper/packetevents/releases/tag/v2.14.0)는 26.3 지원을 명시하지만, 구현 근거는 지정 jar 자체와 dev bundle의 바이트코드다.

ID는 메인 스레드에서 **`Bukkit.getUnsafe().nextEntityId(world)`**로 발급하고 UUID는 무작위로 만든다. 26.3에는 World 인자가 필요하다. 실제 엔티티 생성과 같은 `ServerLevel.getNextEntityId()` 및 전역 카운터를 사용하므로 독립적인 음수/임의 ID 범위를 만들지 않는다.

GCBAPI `origin/main`의 `core/passengable/displayhud/{PacketSender,DisplayHud,ItemDisplayHud}.java`를 `git show`로 먼저 조사했다. spawn/full metadata/destroy 래퍼, `packDirty` 기반 변경분 전송, 시청자 전환 시 전체 상태 재스폰 관용구를 따랐다. 기존 유틸은 HUD 용도이고 미등록 NMS ItemDisplay 생성으로 ID를 얻으므로 직접 사용하지 않는다. 이번 구현은 NMS 엔티티 객체도 만들지 않고 allocator만 호출한다. 기존 HUD의 WeakHashMap<Player,...> 대신 `core/glow/GlowColorInjector.java`와 같은 UUID 기록 및 quit 정리를 사용한다. `core/PacketAPI.java`에는 이 용도의 Display 소유권 관리가 없었다.

Paper dev bundle `26.3.build.5-alpha`의 `Entity` static 초기화가 인덱스 0–7을 먼저 할당한다. 그 다음 `Display` / `Display.ItemDisplay`의 `DATA_*` 선언과 `defineId` 호출 순서로 아래 인덱스를 확정했다. serializer의 wire ID는 하드코딩하지 않고 PacketEvents `EntityDataTypes`에 맡긴다. 실제 26.3 서버에서도 reflection으로 accessor ID/type을 단언했다.

| 인덱스 | NMS `DATA_*` 필드 | NMS serializer → PE type | 초기값 |
|---:|---|---|---|
| 5 | Entity `NO_GRAVITY` | BOOLEAN → BOOLEAN | true |
| 8 | `TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID` | INT → INT | 0 |
| 9 | `TRANSFORMATION_INTERPOLATION_DURATION_ID` | INT → INT | 1 |
| 10 | `POS_ROT_INTERPOLATION_DURATION_ID` | INT → INT | 1 |
| 11 | `TRANSLATION_ID` | VECTOR3 → VECTOR3F | 기존 translation, limb Y 포함 |
| 12 | `SCALE_ID` | VECTOR3 → VECTOR3F | 기존 scale |
| 13 | `LEFT_ROTATION_ID` | QUATERNION → QUATERNION | 기존 left rotation |
| 14 | `RIGHT_ROTATION_ID` | QUATERNION → QUATERNION | 기존 right rotation |
| 15 | `BILLBOARD_RENDER_CONSTRAINTS_ID` | BYTE → BYTE | vanilla FIXED=0, 전송 생략 |
| 16 | `BRIGHTNESS_OVERRIDE_ID` | INT → INT | vanilla -1, 전송 생략 |
| 17 | `VIEW_RANGE_ID` | FLOAT → FLOAT | 3f |
| 18 | `SHADOW_RADIUS_ID` | FLOAT → FLOAT | vanilla 0f, 전송 생략 |
| 19 | `SHADOW_STRENGTH_ID` | FLOAT → FLOAT | vanilla 1f, 전송 생략 |
| 20 | `WIDTH_ID` | FLOAT → FLOAT | 0f |
| 21 | `HEIGHT_ID` | FLOAT → FLOAT | 0f |
| 22 | `GLOW_COLOR_OVERRIDE_ID` | INT → INT | vanilla -1, 전송 생략 |
| 23 | ItemDisplay `ITEM_STACK_ID` | ITEM_STACK → ITEMSTACK | 검증된 전체 아이템 |
| 24 | ItemDisplay `ITEM_DISPLAY_ID` | BYTE → BYTE | NONE=0, 양손 third=1/2, first=3/4, HEAD=5, GUI=6, GROUND=7, FIXED=8 |

원본 jar SHA-256, 선언 순서, 값 클래스 및 재현 명령은 [프로토콜 감사](docs/packet-protocol-audit.md)에 있다. `validation/audit_packet_bytecode.py`가 근거 바이트코드를 출력한다.

### 위치 갱신·시청자·정리

26.3 `WrapperPlayServerEntityPositionSync`의 `LinearPositionPath`를 사용한다. 실제 26.3 클라이언트 `handleEntityPositionSync` → `moveOrInterpolateTo` → Display의 `LinearInterpolationHandler`가 metadata 10의 duration=1을 적용한다. 서버의 일반 절대 위치 추적과 같은 경로다. teleport 패킷 역시 조건에 따라 보간되므로 “teleport는 보간 불가”라는 이유로 선택한 것은 아니다. 한 번에 64블록 초과 이동하거나 클라이언트가 엔티티를 tick하지 않는 경우에는 vanilla처럼 snap할 수 있다.

이전 위치·변환을 복사해 비교하고 바뀐 필드만 전송한다. 같은 틱 중 여러 변경은 마지막 상태로 합쳐진다. 잠든 `active=false` 바디는 주기적인 렌더 갱신 패킷을 보내지 않는다. 명시적 `setRotation`/마네킹 freeze 및 새 코스튬 부착은 휴면 상태에서도 공통 수식으로 자세를 갱신한다. 변환 delay=0은 생성 때만 설정한다. 기존 CraftDisplay 경로도 매 틱 delay를 강제 갱신하지 않았으므로 동일하게 유지했다. 따라서 **변환 보간의 시작 tick을 매 프레임 재설정하도록 개선한 변경은 아니다**. 위치 duration=1 보간과 변환 보간은 별개다.

시청자는 같은 월드에서 엔티티 원점까지 **수평 거리(x/z)**가 아래 유효 범위 이하인 온라인 플레이어다. `trackingRange(World)`는 서버에 로드된 월드별 Display 설정으로 계산하며 고정 `VIEW_DISTANCE=64`는 제거했다. 최초 생성은 즉시 판정·전송하고, 전체 거리 판정은 기존 `VIEWER_INTERVAL=10`틱마다 처리한다. 입장에만 spawn+전체 metadata, 이탈에만 destroy를 보내며 **계속 보이는 파츠의 ID·클라이언트 엔티티·보간 상태는 유지한다.**

```text
서버 Display 범위 = ServerLevel.getServer().getScaledTrackingDistance(spigotConfig.displayTrackingRange)
  전용 서버에서는 정수 계산: displayTrackingRange × entity-broadcast-range-percentage / 100
월드 범위 = min(서버 Display 범위, World.getViewDistance() × 16, viewRange(3f) × 64)
플레이어 유효 범위 R = min(월드 범위, max(2, Player.getClientViewDistance()) × 16)
시청 조건 = 같은 월드 && dx² + dz² <= R²
```

배율 적용 전 설정값이 0이면 시청자가 없다. 양수 설정이 서버의 정수 배율 계산 후 0으로 잘리는 경우는 추적기가 살아 있으므로 수평 거리가 정확히 0인 시청자를 허용한다. Paper의 `entities.tracking-range-y.enabled`가 켜져 있고 `display`가 기본값(-1)이 아니면 별도로 `dy² <= displayY²`도 적용한다. 기본 설정에서는 이 Y 제한이 꺼져 있으므로 3D 구면 거리로 판정하지 않는다. 클라이언트의 실제 컬링은 `viewRange × 64 × entityDistanceScaling`에 따른 3D 거리 판정으로 계속 이루어진다. 요청에 따라 패킷 범위의 클라이언트 상한은 기본 배율 1의 **192블록**으로 고정하며, 개인 entityDistanceScaling을 읽거나 그보다 멀리 확장하지 않는다. 서버 시청 경계는 포함(`<=`)하지만 Display 클라이언트 컬링의 경계는 제외(`<`)다.

26.3 공개 API에 로드된 Display 추적 범위의 전용 getter가 없어 `((CraftWorld) world).getHandle().spigotConfig.displayTrackingRange`를 사용한다. `Server.spigot().getSpigotConfig()`의 YAML을 다시 해석하지 않고, 월드별 override와 default 상속을 이미 반영한 서버 필드를 따른다. 월드의 청크 view-distance와 플레이어 요청 view-distance는 각각 공개 API로 읽는다. 월드 범위/Y 제한은 초기화 및 `WorldLoadEvent`에서 캐시하고, `WorldUnloadEvent`와 종료 때 제거한다. 플레이어의 요청 view-distance는 각 시청자 판정 시 읽으며 월드 설정을 매 틱 다시 읽지는 않는다. 월드 설정 리로드 후에는 월드 재로드 또는 플러그인 재초기화가 필요하다. 조회 실패 시 경고를 남기고 **확인된 Spigot Display 기본값 128**에 같은 월드/클라이언트 상한을 적용한다.

참고한 본서버 `spigot.yml`의 `default.entity-tracking-range.display=196`, `server.properties`의 `view-distance=16`, `entity-broadcast-range-percentage=100`에서는 월드 범위가 `min(196, 256, 192) = 192`블록이다. 플레이어 요청 view-distance가 12청크보다 작으면 그 값 × 16으로 더 줄어든다. 서버 폴더는 읽기만 했다.

이벤트별 규칙은 다음과 같다.

| 이벤트 | 시청자 처리 |
|---|---|
| 같은 월드, 이동량이 위 시청 범위 이내인 텔레포트 | 동일한 수평 범위 R 및 선택적 Y 제한으로 판정한다. 경계도 포함하며 패킷 전송·시청자 변경·다음 틱 예약 모두 하지 않는다. 시야 진입·이탈은 정기 갱신이 처리한다. |
| 같은 월드, 이동량이 위 시청 범위를 벗어난 텔레포트 | 아직 이동 전인 `Player.getLocation()` 대신 `event.getTo()`로 즉시 차이만 전송한다. 기존 다음 틱 재계산이 대기 중이면 그 재계산에서 최종 위치를 사용한다. |
| 다른 월드 텔레포트 | `onTeleport`는 처리하지 않는다. `PlayerChangedWorldEvent`에서 기존 시청자를 해제하고 다음 틱 목적지 월드의 전체 시청 상태를 구성한다. |
| 같은 월드 리스폰 | 다음 틱 실제 리스폰 위치로 차이만 전송한다. 26.3의 같은 dimension 리스폰은 클라이언트 엔티티 저장소를 유지한다(아래 javap 근거). |
| 다른 월드 리스폰 | `onRespawn`은 처리하지 않는다. 클라이언트 월드 재생성을 반영하는 `PlayerChangedWorldEvent` 경로 하나가 재구성한다. |
| 접속·게임모드 변경(관전 포함) | 다음 틱 현재 위치로 차이만 판정한다. 게임모드는 시청 자격을 바꾸지 않으며, 그 자체로 destroy/respawn하지 않는다. |

다음 틱 재계산이 예약된 UUID는 그 사이 정기 `refreshViewers` 및 파츠별 최초 판정에서 건너뛴다. **대기 중이라는 이유로 기존 시청자를 이탈 처리하지 않는다.** 따라서 같은 월드 리스폰·관전 전환이 10틱 정기 갱신과 겹쳐도 한 틱 사라지지 않는다. 취소된 텔레포트·게임모드 이벤트는 처리하지 않는다. 퇴장 시 UUID 기록을 제거하며 관리자에 Player를 보관하지 않는다. ID 발급·Bukkit 호출·시청자 판정은 메인 스레드이고, 송신은 PacketEvents PlayerManager가 채널에 전달한다. 주기 갱신은 시청자별 `writePacket`들을 모으고 마지막 `sendPacket`에서 flush한다.

관리자는 물리에서 `detach()`한 비주얼도 마지막 자세로 계속 추적한다. Ragdoll 수명 만료/`destroy()`, `PhysicsBody.destroy()`, 월드 clear, `clearAll()`, 월드 언로드, 플러그인 종료에서 destroy 패킷을 보내 제거한다. detach 자체는 destroy하지 않는다. 수명 0의 래그돌은 기존처럼 명시적 clear/destroy까지 유지한다. 월드 언로드는 기존 물리 월드만 조회해서 정리하며 새 물리 시스템을 만들지 않는다.

일부 파츠를 만든 뒤 모델/아이템 처리에 예외가 나면 미완성 래그돌과 해당 BoneData 목록의 비주얼을 함께 정리한다. 전송 중 일부 패킷만 성공할 수 있으므로, 보내졌을 가능성이 있는 ID도 UUID별로 기록한다. 채널 부재/전송 예외에서는 다음 틱에 destroy를 재시도하고 살아 있는 비주얼의 현재 상태를 다시 스폰한다. 물리 목록에서 이미 제거된 비주얼도 이 정리 기록에 남아 재시도되며 quit/shutdown에서 기록을 제거한다.

#### 26.3 Display 추적 범위 바이트코드 근거

지정 Java 25의 `javap -p -c`로 Paper dev bundle `26.3.build.5-alpha`의 `.gradle/caches/paperweight/taskCache/mappedServerJar.jar` 및 같은 버전 Paper API를 확인했다. 재현은 `python validation/audit_tracking_range.py`이며 16개 검증 항목을 확인하고 원문·SHA-256을 `build/tracking-bytecode/`에 기록한다. mapped server SHA-256은 `e64e24c476635f41df26ae269c6a4cbc4df6193290b367d54d016c9e2c4dbac9`, API SHA-256은 `493a7f09bfea840bc2b3b3b1a40fd17d4f7ead2253ec31f12325eb280438d038`이다. 아래 위치는 메서드 내 bytecode offset이다.

| 확인한 메서드 | 실제 동작과 적용 규칙 |
|---|---|
| `SpigotWorldConfig` 생성자 / `trackingRange` | 생성자 173–176에서 `displayTrackingRange`를 **128**로 초기화하며, 설정 로더의 62–72에서 `entity-tracking-range.display`를 읽어 필드에 저장한다. 공개 `World`, `World.Spigot`, `Entity`, `Display`에는 이 로드된 값의 전용 getter가 없다. |
| `SpigotWorldConfig.viewDistance` | 49–60에서 월드 설정이 default/nonpositive이면 Bukkit 서버 설정을 사용하고, 63–77에서 **3–32청크로 보정**한다. 따라서 시작 설정이 view-distance=2여도 로드된 월드 값은 3이다. 패킷 관리자는 이 보정 후 공개 API 값을 읽는다. `ChunkMap.setServerViewDistance` 자체의 보정 범위는 2–32이므로 API로 직접 설정된 2청크는 별도로 유효하다. |
| `TrackingRange.getEntityTrackingRange` | 7–13에서 엔티티 월드의 `Level.spigotConfig`를 가져오고, 147–158의 `instanceof Display` 분기는 `displayTrackingRange`를 반환한다. `ActivationType.activationTypeFor`의 Display는 94–97의 `MISC`에 해당한다. 이 dev bundle에는 `ActivationRange.class`가 없으며 activation 거리 설정을 Display 렌더 반경으로 사용하지 않는다. |
| `ChunkMap.addEntity` | 128–137의 `EntityType.clientTrackingRange() × 16`은 `TrackingRange.getEntityTrackingRange`의 Display 설정으로 교체된다. 141–142에서 0이면 추적기를 생성하지 않는다. |
| `ChunkMap.TrackedEntity.getEffectiveRange` / `scaledRange` | 승객들의 범위와 최댓값을 구한 뒤 69–74에서 `scaledRange`를 거친다. `scaledRange`의 7–14는 서버의 `getScaledTrackingDistance`를 호출한다. 래그돌 파츠에는 승객이 없으므로 자체 Display 설정을 쓴다. |
| `DedicatedServer.getScaledTrackingDistance` | 0–9에서 `entityBroadcastRangePercentage() × range / 100`을 정수 계산한다. 상위 `MinecraftServer`는 입력을 그대로 반환하므로 전용 서버의 override를 실제 호출해야 한다. |
| `ChunkMap.getPlayerViewDistance` | 0–12에서 `Mth.clamp(player.requestedViewDistance(), 2, serverViewDistance)`를 반환한다. `CraftWorld.getViewDistance` → `FeatureHooks.getViewDistance`는 `ChunkMap.serverViewDistance`를 그대로 반환한다. **view-distance에서 1을 빼지 않는다.** `CraftPlayer.getClientViewDistance`는 `requestedViewDistance()`를 그대로 반환한다. |
| `ChunkMap.TrackedEntity.updatePlayer` | 13–38에서 dx/dz를 계산하고, 40–62에서 `min(getEffectiveRange(), getPlayerViewDistance(player) × 16)`을 구한다. 65–96은 **`dx² + dz² <= range²`**다. 103–196의 별도 Paper Y 분기는 `trackingRangeY.enabled`일 때 `display.or(-1)`을 읽고, -1이 아니면 dy 제곱을 따로 비교한다. Y 설정 생성자 12–13은 `enabled=false`다. |
| `Display.shouldRenderAtSqrDistance` | 0–26에서 `distance² < (getViewRange() × 64 × getViewScale())²`로 클라이언트 컬링 한계를 계산한다. 패킷/실제 Display가 같은 `viewRange=3f` 상수를 사용하고 패킷 범위에는 192블록 상한을 적용한다. |

실제 엔티티의 `updatePlayer`는 이후 `broadcastToPlayer`, 청크 전송 상태, Bukkit `canSee` 및 추적 이벤트도 확인한다. 이번 변경은 요청한 거리 계산을 맞추며 가짜 Display에 실제 서버 엔티티나 청크 추적기를 만들지는 않는다.

#### 26.3 리스폰·게임모드 바이트코드 근거

`GCBServer\Java\Java_25\bin\javap.exe -p -c`로 로컬 26.3 클라이언트와 Paper dev bundle `26.3.build.5-alpha`의 mapped server를 확인했다. 입력 클라이언트는 `GCBServer/ScreenRoll/build/minecraft-26.3-client.jar`이며 `version.json`의 ID는 `26.3`, SHA-256은 `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`다. mapped server SHA-256은 `e64e24c476635f41df26ae269c6a4cbc4df6193290b367d54d016c9e2c4dbac9`다. 재현 명령은 `python validation/audit_viewer_bytecode.py`; 원문 disassembly와 입력·출력 해시는 저장소 내부 `build/viewer-bytecode/`에 기록한다. 아래 숫자는 소스 줄이 아닌 각 메서드의 bytecode offset이다.

| 확인한 메서드 | 실제 분기·동작과 판단 |
|---|---|
| `ClientPacketListener.handleRespawn` | 17–58에서 패킷 `dimension()`과 기존 `LocalPlayer.level().dimension()`의 `ResourceKey`를 비교한다. 키가 같으면 **76의 `ifeq 204`**가 138의 유일한 `new ClientLevel` 및 194의 `Minecraft.setLevel`을 건너뛴다. dimension type이나 `shouldKeep` 플래그가 월드 재생성 조건은 아니다. |
| 같은 메서드의 로컬 플레이어 교체 | 225–308에서 `shouldKeep(2)`에 따라 새 LocalPlayer를 만들며, 325–341에서 이전 플레이어 ID를 부여한다. 484–490의 `ClientLevel.addEntity`는 이 플레이어만 추가한다. `ClientLevel.addEntity`의 0–16은 동일 ID의 기존 엔티티만 제거하고 새 엔티티를 등록한다. 가짜 ItemDisplay 전체를 지우는 경로가 아니다. `shouldKeep(1)`도 플레이어 attribute 복사 범위만 바꾼다. |
| `ClientLevel` 생성자 | 19–48에서 새 `EntityTickList`와 `TransientEntitySectionManager`를 만든다. 따라서 **dimension 키가 바뀌면 기존 엔티티는 새 클라이언트 월드에 없으므로 목적지의 모든 보이는 파츠를 spawn해야 한다.** 같은 dimension 리스폰은 이 생성자를 실행하지 않으므로 계속 보이는 파츠를 유지한다. |
| `ClientPacketListener.startWaitingForNewLevel` / `LevelLoadTracker.startClientLoad` | 로딩 화면과 대기 상태만 갱신한다. 기존 ClientLevel의 엔티티 저장소를 초기화하지 않는다. 로딩 화면이 뜬다는 사실만으로 전체 재스폰을 판단하지 않는다. |
| `PlayerList.respawn` / `ServerPlayer.createCommonSpawnInfo` | `PlayerRespawnEvent`는 `findRespawnPositionAndUseSpawnBlock0`의 169–204에서 기존 Bukkit 플레이어와 목적지 Location으로 발생한다. 호출자인 `PlayerList.respawn`의 `setServerLevel`(224)보다 앞이므로 이벤트 당시 플레이어 월드는 출발지다. 390–406에서 **목적지 dimension으로 단 한 번** respawn 패킷을 보낸다. `createCommonSpawnInfo`의 9는 전달된 ServerLevel의 `dimension()`을 사용한다. 같은 월드 리스폰에 가짜 다른 dimension을 끼워 보내는 경로는 없다. 766–790에서 이전·새 ServerLevel이 다를 때만 `PlayerChangedWorldEvent`를 발생시킨다. |
| `ServerPlayer.teleport` / `CraftPlayer.refreshPlayer` | teleport의 313–355는 dimension이 같으면 위치 패킷만 보내고 반환한다. 371–384의 respawn 패킷은 다른 dimension 분기에만 있다. `refreshPlayer`도 16–29에서 현재 dimension의 respawn 패킷 하나만 보낸다. |
| `ClientPacketListener.handleGameEvent` / `MultiPlayerGameMode.setLocalMode` | `CHANGE_GAME_MODE` 분기 104–126은 `setLocalMode(GameType)`만 호출한다. 해당 메서드의 0–66은 모드·설정 UI·플레이어 능력만 갱신하며 ClientLevel이나 엔티티를 재생성하지 않는다. 관전 전환도 전체 재스폰 근거가 없다. |

이 판단은 위 해시의 기본 26.3 클라이언트와 지정 Paper 서버 경로에 대한 정적 확인이다. 다른 프로토콜/클라이언트나 별도 플러그인이 임의 dimension 패킷을 주입하는 경우까지 판정한 것은 아니다. 실제 클라이언트의 프레임 단위 시각 검증은 별도로 남는다.

### 컴포넌트 보존·폴백·남은 위험

`SpigotConversionUtil`은 NMS 네트워크 codec → PacketEvents item codec으로 변환하며 역방향도 같은 전체 codec을 사용한다. 매 파츠 생성 전에 Bukkit→PE→Bukkit 왕복 후 NMS `ItemStack.matches`로 종류·개수·전체 컴포넌트를 비교한다. 예외나 손실이 있으면 해당 파츠만 실제 Display로 폴백하고 경고를 남긴다. 26.3 실서버 프로브에서 일반/슬림 × 서명 유무의 player_head 4개가 통과했다. profile UUID/name/textures/property signature, item_model, custom_model_data floats/flags/strings/colors를 확인했다. 임의 테스트 서명 문자열의 바이트 보존 검사이며, Mojang 인증이나 실제 스킨 다운로드 검사는 아니다.

초기화 실패·지원 대상이 아닌 서버 버전에서는 `PACKET_RENDER=false`와 경고로 실제 렌더 경로를 선택한다. **packetevents 파일 자체가 없는 경우는 hard `depend` 때문에 Bukkit이 onEnable 전에 플러그인을 거부한다.** 이 경우 코드로 false를 설정해 실행을 계속할 수 없다. 요청한 필수 의존성을 유지했으며, 자동 폴백은 enable에 도달한 뒤의 초기화 실패 등에 적용된다.

실제 클라이언트 시각, ViaVersion 등 다른 프로토콜, 동적 profile의 모든 조합, 장시간/다중 플레이어 네트워크 부하는 미검증이다. 런타임 전체 컴포넌트 비교는 미검증 아이템의 손실을 조용히 허용하지 않는다. 플레이어별 수신 확인은 없으므로 연결이 끊기거나 PacketEvents가 종료된 뒤에는 destroy 전달 자체를 보장할 수 없다. 일반 종료 순서는 depend 역순에 따라 RemoteRagdoll이 먼저 정리한다.

### 검증과 인게임 체크리스트

재현: `powershell -NoProfile -ExecutionPolicy Bypass -File validation/build_packet_render.ps1` (지정 Java 25, `clean shadowJar test`), `python validation/audit_packet_scripts.py`, `python validation/audit_render_contract.py`. 서버 검사는 `validation/build_packet_probe.py` 및 `validation/run_server_smoke.py`를 사용한다. 포트 25585는 사용하지 않으며 `run-test/`의 localhost 25643을 TCP/UDP bind 검사 후 사용하고 종료한다. 빌드·검사 로그는 `../output/RemoteRagdoll/`에 보관한다.

이번 추적 범위 수정은 지정 Java 25로 `clean shadowJar test`를 실행해 **`BUILD SUCCESSFUL in 30s`, 43개 전부 통과**했다(실패·오류·건너뛰기 0). 기존 이벤트 회귀 테스트를 192블록 기준으로 갱신하고, 설정값·월드/클라이언트 상한·128 폴백·월드 캐시의 로드/언로드·원본/배율 결과 0의 차이를 검사했다. 실제 송신 wrapper로 X/Z 경계의 spawn/destroy, Y 무제한 및 선택적 Y 제한, 클라이언트 시야 변경, 월드/클라이언트/수직 텔레포트 임계값, 기존 10틱 주기를 검증했다. 리스폰·게임모드·월드 전환·재시도 회귀도 통과했다. 빌드 로그의 Java 25/Gradle 경고와 Windows 콘솔 감지 보조 프로세스 `test.exe` 오류 출력은 있었으나 Gradle 종료 코드는 0이고 모든 JUnit XML 결과가 통과했다.

`python validation/audit_tracking_range.py`의 26.3 추적 규칙 16개 검증을 빌드 후 다시 통과했으며, 원문/해시를 `build/tracking-bytecode/`와 `../output/RemoteRagdoll/validation/tracking-bytecode/`에 보관했다. `python validation/verify_packet_artifact.py`로 jar를 검사하고 `../output/RemoteRagdoll/RemoteRagdoll-1.0.0.jar` 및 JUnit 결과를 갱신했다. 최종 jar는 Java class major 69이며 PacketEvents/GCBAPI/ModelEngine/Bukkit/NMS/JUnit/Mockito 클래스가 shade되지 않았고 Jolt Windows DLL/Linux SO는 포함됐다. `artifact-verification.json`에 최종 테스트 수·크기·SHA-256을 기록한다. 저장소와 output의 `Physics.sk`는 모두 작업 전후 SHA-256 `b5147223bf2dd19861665f022287e85416dfa26f16405da796de9d856b4e0019`로 동일하다. 렌더 수식·물리·public API 31개 비교 및 1,108개 Skript 감사 통과 기록은 이전 패킷 렌더 전환 때의 결과다.

**이번 jar로 격리 Purpur 26.3 스모크를 두 번 실행했다.** 테스트 전용 `run-test/spigot.yml`의 display=196으로 설정하고, view-distance=12에서 세 월드 모두 계산값 **192**, view-distance=2에서는 서버가 로드 시 3청크로 보정하므로 모두 **48**을 확인했다. 프로브는 YAML 값, 서버 로드 필드, 등록하지 않은 실제 NMS ItemDisplay의 `TrackingRange` 결과가 모두 196인지 검사한 뒤 보정 후 월드 상한과 최종 패킷 범위를 비교한다. 두 실행에서 `PACKET_TRACKING_RANGE_PROBE_OK`, `PACKET_COMPONENT_PROBE_OK`, `PACKET_RENDER_PROBE_OK`를 확인했다. 기존 컴포넌트/렌더/물리 수명 검사도 통과했다. localhost 25643은 매 실행 전 TCP/UDP bind로 확인했으며, JVM PID 77076/98236은 `stop`으로 정상 종료(exit 0)하고 포트를 반환했다. 25585는 사용하지 않았다.

GCBAPI는 기존과 같은 `InvalidPluginException: NoClassDefFoundError: ch/njol/skript/Skript`로 생성/로드가 실패했다. 같은 테스트 서버에 Skript jar를 넣었지만 GCBAPI plugin.yml에 해당 depend가 없어 로드 순서/클래스 접근이 충족되지 않았다. 외부 플러그인 파일은 수정하지 않았다. 따라서 전체 의존 플러그인 enable 성공이나 실제 ModelEngine 래그돌/Skript 실행까지 통과했다고 볼 수 없으며, 두 스모크 실행기의 종료 코드는 1, `fullSmokePassed=false`다. 프로브가 직접 검증한 RemoteRagdoll/PacketEvents/Jolt 범위만 유효하다. [서버 스모크 기록](docs/server-smoke.md) 및 `../output/RemoteRagdoll/validation/tracking-range-{192,32}.{json,log}`에 이 제한과 정상 서버 종료·포트 반환을 기록한다.

배포 jar SHA-256: `e309566222e5331cab4b43a289094815fcb436ebc6a6f3cb37632b348d56f5db`.

**인게임 시각 확인은 미검증**이며 다음 순서로 확인한다.

1. `Ragdoll_setPacketRender(true)` → `Ragdoll_spawn` 일반·슬림 스킨: 몸/장비/코스튬 모양과 스킨 확인.
2. 짧은 대시·블링크 → 계산된 유효 범위 경계 및 초과 이동 → 같은 월드 리스폰·관전 전환: 계속 보이는 파츠 유지와 시야 진입·이탈만 갱신되는지 확인. 이어서 월드 이동 → 재접속으로 목적지 파츠 재스폰 확인.
3. `Ragdoll_cut` → `Physics_clearAll`: 절단 물리와 모든 가짜 파츠 제거 확인.
4. `Ragdoll_setPacketRender(false)`로 다시 생성해 기존 방식과 모양·위치·회전·보간 비교. `limbTest`/`detachOnly` 실제 엔티티 표본도 비교.

## 2026-10-01 장비 본 복제와 첫 포즈 계측

26.3 GCBAPI `MEAPI.createReal`의 수정처럼 `DisplayBone`이 실제 전송하는 `position`, `leftRotation`, `scale`, `rightRotation`, `display`를 원본으로 삼았다. 별도의 180도 회전을 더하지 않는다. 래그돌은 물리 파츠 원점으로 다시 앵커링하므로 raw translation 숫자는 달라질 수 있지만, 첫 물리 포즈를 즉시 적용한 뒤 월드 위치와 두 quaternion·scale을 비교한다.

실서버의 새 래그돌 호스트에는 real viewer가 없어서 `helmet/righthand/lefthand`의 `BoneData`가 1개여도 모델 ItemStack이 AIR였고, ITEM behavior provider도 AIR를 반환했다. 이 경고는 “무기가 이미 다른 경로로 표시된다”는 뜻이 아니라 **그 장비가 래그돌에 생성되지 않았음**을 뜻했다. 최종 구현은 BoneData → ITEM behavior → 호스트 실제 equipment 순서로 아이템 원본만 폴백한다. 변환과 display mode는 끝까지 같은 ModelEngine `DisplayBone` 값을 사용한다. 따라서 실제 장비가 빈 helmet/left hand만 경고가 남고, 장착된 대검·창·총·모자/양손 장비에는 경고가 없다.

`trainingbot-ragdoll-fix-04`의 entity/packet 첫 포즈 결과는 다음과 같다. raw translation 차이는 파츠 기준 재앵커링 값이며 월드 위치 오차로 판정했다.

| 표본 | 렌더 표본 수 | 월드 위치 최대 오차 | left 회전 최대 오차 | scale 최대 오차 | right 회전 최대 오차 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 아이크 대검 | 4 | 0.05834322블록 | 0.06852804° | 0 | 0° |
| 랜서 창 | 2 | 0블록 | 0.06852804° | 0 | 0° |
| 그레이브즈 총 | 2 | 0.05834322블록 | 0.06852804° | 0 | 0° |
| 아이크 모자 | 4 | 0블록 | 0.05595291° | 0 | 0° |
| 사일러스 양손/겐지 수리검 | 6 | 0.05834322블록 | 0.06852804° | 0 | 0° |

18개 장비 변환 행에서 scale/right rotation 오차는 전부 0이고, left rotation의 0.04~0.07°는 float quaternion 재정규화 오차다. 대검·창·총 모두 entity와 packet 경로에서 생성됐으며 거대한 배율이나 180° 추가 회전은 재현되지 않았다. 실제 접속 클라이언트의 픽셀 단위 시각 확인은 여전히 별도 체크 항목이다. 지정 Java 25 `clean shadowJar test`와 기존 packet/Jolt JUnit은 통과했다.
