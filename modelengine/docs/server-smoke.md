# 26.3 격리 서버 스모크

`validation/run_server_smoke.py`는 `run-test/` 아래에만 테스트 서버를 만든다.
기존 서버의 jar·라이브러리를 복사하고 새 설정·월드를 사용한다.
기존 서버와 GCBAPI·personal·리소스팩 작업 트리는 수정하지 않는다.

```powershell
python validation/build_packet_probe.py
python validation/run_server_smoke.py --probe run-test/plugins/PacketComponentProbe.jar --success-marker PACKET_RENDER_PROBE_OK
```

실행 JDK는 지정된 Java 25다. Minecraft는 원본 server.jar의 version.json으로
확인한 Purpur 26.3(protocol 777)다. 서버는 `127.0.0.1:25643`에서만 수신하며,
실행 전 TCP/UDP 배타적 bind로 사용 가능 여부를 검사한다. RCON·query는
비활성화했다. 25585는 검사·실행 대상에서 제외한다. `finally`에서 해당
프로세스에 `stop`을 보내고, 종료가 지연되는 경우에만 그 프로세스를 종료한다.
종료 후 포트가 다시 비었는지도 검사한다.

설치하는 플러그인은 RemoteRagdoll, compileOnly에 사용한 26.3 GCBAPI와
ModelEngine R4.1.1, 지정 PacketEvents 2.14.0이다. GCBAPI의 `onLoad`/
`onEnable` 직접 참조에 필요한 Skript 2.13.2·BetterHud 2.1.0-26.3과
PlaceholderAPI 2.11.6도 함께 복사한다. 플러그인의 설정 파일은 복사하지 않는다.
테스트 전용 PacketComponentProbe는 배포 jar에 포함되지 않는다.

GCBAPI 원본 `plugin.yml`에는 Skript 의존/로드 순서 선언이 없으며, 실제
테스트 환경에서는 `ModernPluginLoadingStrategy`가 GCBAPI를 로드하는 도중
다음 오류가 발생했다. 같은 서버에 Skript jar를 설치한 상태의 결과다.

```text
InvalidPluginException: java.lang.NoClassDefFoundError: ch/njol/skript/Skript
Caused by: java.lang.ClassNotFoundException: ch.njol.skript.Skript
```

그러므로 GCBAPI가 정상 enable된 전체 조합의 스모크는 **차단**으로 취급한다.
외부 플러그인의 jar·의존성 선언을 패치하거나 대신하는 stub을 넣지 않는다.
서버 로더가 RemoteRagdoll의 enable을 계속 진행하는 결과는 GCBAPI의 정상
기동을 뜻하지 않는다. 보고서는 `gcbApiEnableLogged`와
`remoteRagdollEnableLogged`를 따로 기록하며, 전체 오류 없는 기동·종료가
확인되지 않으면 `fullSmokePassed=false`와 종료 코드 1을 반환한다.

PacketComponentProbe는 일반/슬림 프로필과 서명 유무를 조합한 4가지
player_head에 profile·텍스처·item_model·CMD floats/flags/strings/colors를
넣어 Bukkit→PacketEvents→Bukkit 변환 후 전체 NMS ItemStack 일치를 검사한다.
프로덕션 변환 브리지도 실행한다. 런타임 NMS Display/ItemDisplay data accessor의
인덱스·serializer를 반사로 검증한다.
텍스처 URL·서명 문자열은 합성 fixture이며, 실제 Mojang 서명의 유효성이나
텍스처 다운로드를 검사하는 것이 아니라 직렬화 중 값의 보존을 검사한다.

추가 렌더 검사는 manager 준비, 실제 엔티티 수를 늘리지 않는 packet 파츠,
변환·위치 변경과 flush, remove 반복, PhysicsBody.detach 후 시각 유지,
clearAll/clearWorld의 시각 제거, released 가드, PACKET_RENDER=false의 실제
ItemDisplay 생성·제거를 검사한다. 접속 클라이언트가 없으므로 실제 패킷 수신,
보간·외형·거리/월드/재접속 시청자 경험과 ModelEngine ragdoll spawn 전체는
이 검사로 확인하지 않는다.

실행별 로그와 사용한 jar SHA-256, 프로세스 종료·포트 반환 여부는
`../output/RemoteRagdoll/validation/server-smoke.log` 및 `server-smoke.json`에
기록한다. `server-preflight.*`는 렌더 변경 전 jar로 의존성만 먼저 점검한
기록이므로 새 구현의 최종 검증 결과와 구분한다.

2026-09-26 이전 패킷 렌더 전환의 실행에서 `PACKET_COMPONENT_PROBE_OK`와
`PACKET_RENDER_PROBE_OK`를 모두 확인했다. RemoteRagdoll enable/disable,
Jolt 초기화 및 위 렌더/수명 검사는 통과했다. GCBAPI는 위 Skript 클래스
로드 오류로 enable되지 않아 전체 조합의 스모크 결과는 차단이다.
서버 JVM PID 7984는 `stop`으로 정상 종료(exit 0)했고 강제 종료 없이
25643 포트 반환을 확인했다. 검증 jar SHA-256은
`28d66ba932c922113b1748a071165fad4339a27cc31d9f1b49409012433b1ba0`다.

첫 렌더 probe 실행에서는 무접속 서버의 테스트 청크가 로드되지 않아
실제 엔티티 수의 즉시 조회가 실패했다. probe가 해당 청크에만 임시
plugin ticket을 추가/해제하도록 고친 뒤 동일 프로덕션 jar로 재실행했다.
실제 엔티티 수가 정확히 1 증가한다는 검사는 그대로 유지했고 통과했다.
첫 실행 기록은 `server-smoke-first-probe.*`로 보존했다.

## Display 추적 범위 변경 검증 (2026-09-26)

현재 jar의 추적 범위 프로브는 각 월드의 테스트 `spigot.yml` 값과 로드된
`spigotConfig.displayTrackingRange`, 등록하지 않은 실제 NMS ItemDisplay의
`TrackingRange.getEntityTrackingRange` 결과가 같은지 확인한다. 서버 배율,
월드 view-distance × 16, Display viewRange 3 × 64 상한을 적용한 결과를
패킷 관리자의 `trackingRange(World)`와 비교하여 월드별 로그를 남긴다.

```powershell
python validation/build_packet_probe.py
python validation/run_server_smoke.py --probe run-test/plugins/PacketComponentProbe.jar --view-distance 12 --display-tracking-range 196 --success-marker PACKET_TRACKING_RANGE_PROBE_OK --success-marker PACKET_RENDER_PROBE_OK --name tracking-range-192
python validation/run_server_smoke.py --probe run-test/plugins/PacketComponentProbe.jar --view-distance 2 --display-tracking-range 196 --success-marker PACKET_TRACKING_RANGE_PROBE_OK --success-marker PACKET_RENDER_PROBE_OK --name tracking-range-32
```

| 실행 | YAML / 로드값 / 실제 Display | 설정 → 로드된 view-distance | 세 월드 계산값 | 종료 |
|---|---|---|---|---|
| `tracking-range-192` | 196 / 196 / 196 | 12 → 12청크 | 192블록 | PID 77076, exit 0 |
| `tracking-range-32` | 196 / 196 / 196 | 2 → 3청크 | 48블록 | PID 98236, exit 0 |

낮은 시야의 실행명은 `tracking-range-32`지만 실제 로드값은 3청크다.
`SpigotWorldConfig.viewDistance()`의 시작 설정 최솟값 3 보정 때문에 생긴
차이이며, 패킷 계산은 설정 파일의 2가 아닌 공개 API의 로드값 3을 사용한다.

두 실행 모두 추적 범위·컴포넌트·렌더 프로브가 통과했다. 기존 GCBAPI/Skript
로드 오류는 재현되어 전체 조합은 `fullSmokePassed=false`이고 실행기 종료
코드는 1이다. 서버 JVM은 강제 종료 없이 정상 종료됐으며 25643의 실행 전
사용 가능 여부와 종료 후 반환을 각각 확인했다. 25585는 사용하지 않았다.
로그와 보고서는 `../output/RemoteRagdoll/validation/tracking-range-{192,32}.{log,json}`다.
검증 jar SHA-256:
`e309566222e5331cab4b43a289094815fcb436ebc6a6f3cb37632b348d56f5db`.
